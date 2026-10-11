package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.discovery.platform.BoardAdapter.ListingEntry;
import com.personal.jobagent.discovery.platform.BoardAdapter.ListingResult;
import com.personal.jobagent.discovery.platform.adapters.TsentaJobSource;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static com.personal.jobagent.discovery.platform.FixtureHttp.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tsenta public listings (fixtures captured 2026-10-10 from tsenta.com/jobs
 * pages 2 and 3 and two detail pages). Covers listing, pagination, detail,
 * employer-apply-URL correctness, optional fields, duplicates across pages,
 * expired/closed postings, malformed and empty pages, timeouts, robots and the
 * request budget. Live behaviour is checked separately by PlatformLiveIT.
 */
class TsentaJobSourceTest {

    private static final String ZOHO_ID = "8d7f73a6-5a94-4d7a-84c3-4db0ce114498";
    private static final String CSOD_ID = "b68595e6-9144-4e2d-bab1-cf7de19cd41e";
    private static final String EMPTY_PAGE = "<html><body><script>self.__next_f.push([1,\"empty\"])</script></body></html>";

    private final TsentaJobSource tsenta = new TsentaJobSource(5);
    private final BoardAdapter.BoardRef jobs = tsenta.parseIdentifier("jobs");

    private FixtureHttp site() {
        return new FixtureHttp().on("GET", "robots.txt", ok("text/plain", fixture("tsenta-robots.txt")))
                .on("GET", "page=1", html(fixture("tsenta-list-page2.html")))
                .on("GET", "page=2", html(fixture("tsenta-list-page3.html")))
                .on("GET", "page=3", html(EMPTY_PAGE));
    }

    @Test
    void scopesAreValidatedAndRecognised() {
        assertThat(tsenta.recognize(URI.create("https://tsenta.com/jobs/remote")).map(BoardAdapter.BoardRef::identifier)).contains("jobs/remote");
        assertThat(tsenta.recognize(URI.create("https://tsenta.com/jobs/some-job-" + ZOHO_ID)).map(BoardAdapter.BoardRef::identifier)).contains("jobs");
        assertThatThrownBy(() -> tsenta.parseIdentifier("dashboard")).isInstanceOf(BoardConfigException.class);
        assertThatThrownBy(() -> tsenta.parseIdentifier("../admin")).isInstanceOf(BoardConfigException.class);
    }

    @Test
    void paginatesUntilAnEmptyPageWithDisjointPagesAndNeverClaimsCompleteness() {
        FixtureHttp f = site();
        ListingResult r = tsenta.list(jobs, f.context());
        assertThat(r.entries()).hasSize(40);
        assertThat(r.pagesFetched()).isEqualTo(3);
        assertThat(r.complete()).as("an aggregator listing is never exhaustive").isFalse();
        assertThat(r.failure()).isNull();
        assertThat(r.entries()).extracting(ListingEntry::externalId).doesNotHaveDuplicates();
        assertThat(r.entries()).allMatch(e -> e.jobUrl().startsWith("https://tsenta.com/jobs/") && e.needsDetail());
        assertThat(f.requests.get(0).uri().getPath()).isEqualTo("/robots.txt");
    }

    @Test
    void duplicateCardsAcrossPagesAreKeptOnce() {
        FixtureHttp f = new FixtureHttp().on("GET", "robots.txt", ok("text/plain", fixture("tsenta-robots.txt")))
                .on("GET", "page=1", html(fixture("tsenta-list-page2.html")))
                .on("GET", "page=2", html(fixture("tsenta-list-page2.html")))
                .on("GET", "page=3", html(EMPTY_PAGE));
        ListingResult r = tsenta.list(jobs, f.context());
        Set<String> ids = new HashSet<>();
        r.entries().forEach(e -> ids.add(e.externalId()));
        assertThat(ids).hasSize(20);
        assertThat(r.entries()).hasSize(20);
    }

    @Test
    void thePageCapBoundsTheRunAndIsNotReportedAsAFailure() {
        FixtureHttp f = site();
        ListingResult r = tsenta.list(jobs, f.context(1));
        assertThat(r.entries()).hasSize(20);
        assertThat(r.complete()).isFalse();
        assertThat(r.failure()).isNull();
        assertThat(f.requests).hasSize(2); // robots + one page
    }

    @Test
    void theRequestBudgetIsEnforced() {
        FixtureHttp f = site();
        SafeHttpClient client = f.client(SafeHttpClient.Settings.defaults().withMaxRequests(2).withMinInterval(0));
        ListingResult r = tsenta.list(jobs, new BoardAdapter.CrawlContext(client, 10, Instant.now(), null));
        assertThat(f.requests).hasSize(2);
        assertThat(r.failure()).isEqualTo(FailureCategory.BUDGET_EXHAUSTED);
        assertThat(r.complete()).isFalse();
    }

    @Test
    void robotsDisallowStopsTheRunBeforeAnyListingRequest() {
        FixtureHttp f = new FixtureHttp().on("GET", "robots.txt", ok("text/plain", "User-agent: *\nDisallow: /jobs\n"))
                .on("GET", "page=", html(fixture("tsenta-list-page2.html")));
        ListingResult r = tsenta.list(jobs, f.context());
        assertThat(r.failure()).isEqualTo(FailureCategory.ROBOTS_DISALLOWED);
        assertThat(r.entries()).isEmpty();
        assertThat(f.requests).hasSize(1);
    }

    @Test
    void unreadableRobotsIsTreatedAsNoPermissionToCrawl() {
        FixtureHttp f = new FixtureHttp().on("GET", "robots.txt", status(404));
        assertThat(tsenta.list(jobs, f.context()).failure()).isEqualTo(FailureCategory.ROBOTS_DISALLOWED);
        FixtureHttp down = new FixtureHttp().on("GET", "robots.txt", TIMEOUT);
        assertThat(tsenta.list(jobs, down.context()).failure()).isEqualTo(FailureCategory.TIMEOUT);
    }

    @Test
    void robotsRulesUseTheLongestMatchForTheStarGroup() {
        String robots = fixture("tsenta-robots.txt");
        assertThat(robotsAllow(robots, "/jobs")).isTrue();
        assertThat(robotsAllow(robots, "/private/x")).isFalse();
        assertThat(robotsAllow("User-agent: *\nDisallow: /\nAllow: /jobs\n", "/jobs/remote")).isTrue();
        assertThat(robotsAllow("User-agent: other\nDisallow: /\n\nUser-agent: *\nAllow: /\n", "/jobs")).isTrue();
    }

    private static boolean robotsAllow(String robots, String path) {
        try {
            var m = TsentaJobSource.class.getDeclaredMethod("robotsAllow", String.class, String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, robots, path);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anAccessDeniedOrChallengePageIsReportedNotWorkedAround() {
        FixtureHttp f = new FixtureHttp().on("GET", "robots.txt", ok("text/plain", fixture("tsenta-robots.txt")))
                .on("GET", "page=1", status(403));
        ListingResult r = tsenta.list(jobs, f.context());
        assertThat(r.failure()).isEqualTo(FailureCategory.ACCESS_DENIED);
        assertThat(f.requests).hasSize(2);
    }

    @Test
    void aMalformedListingPageIsMalformedAndPartialPagesAreKept() {
        FixtureHttp f = new FixtureHttp().on("GET", "robots.txt", ok("text/plain", fixture("tsenta-robots.txt")))
                .on("GET", "page=1", html(fixture("tsenta-list-page2.html")))
                .on("GET", "page=2", html("<html><body>Something else</body></html>"));
        ListingResult r = tsenta.list(jobs, f.context());
        assertThat(r.failure()).isEqualTo(FailureCategory.MALFORMED_RESPONSE);
        assertThat(r.entries()).hasSize(20);
    }

    @Test
    void detailUsesTheOriginalEmployerApplicationUrlNeverTsentasApplyLink() {
        NormalizedJob j = parse("tsenta-detail-zoho.html", ZOHO_ID);
        assertThat(j.applicationUrl()).isEqualTo("https://sehyog.zohorecruit.in/jobs/Careers/167689000000570023/Senior-English-Tutor-UK-Curriculum");
        assertThat(j.applicationUrl()).doesNotContain("tsenta");
        assertThat(fixture("tsenta-detail-zoho.html")).contains("dashboard.tsenta.com/dashboard?apply=" + ZOHO_ID);
        assertThat(j.sourceUrl()).startsWith("https://tsenta.com/jobs/");
        assertThat(j.title()).isEqualTo("Senior English Tutor (UK Curriculum)");
        assertThat(j.employerName()).isEqualTo("Sehyog Academy");
        assertThat(j.remoteType()).isEqualTo("REMOTE");
        assertThat(j.employmentType()).isEqualTo("FULL_TIME");
        assertThat(j.descriptionText()).contains("English").doesNotContain("<p>");
        assertThat(j.postedAt()).as("datePosted 2029-04-09 is in the future: unknown, not stored").isNull();
    }

    @Test
    void detailForAnEmployerOnAnUnsupportedAtsStillKeepsTheEmployersUrl() {
        NormalizedJob j = parse("tsenta-detail-unsupported.html", CSOD_ID);
        assertThat(j.applicationUrl()).isEqualTo("https://seequent.csod.com/ux/ats/careersite/1/home/requisition/4366?c=seequent");
        assertThat(j.employerName()).isEqualTo("Seequent");
        assertThat(j.remoteType()).isEqualTo("HYBRID");
        assertThat(j.locationRaw()).contains("Christchurch").contains("Vancouver");
        assertThat(j.city()).as("multi-location posting: no single city").isNull();
        assertThat(j.salaryMin()).isNull();
        assertThat(j.postedAt()).isNull();
    }

    @Test
    void aClosedPostingIsMarkedInactive() {
        String closed = fixture("tsenta-detail-zoho.html").replace("\\\"active\\\":true", "\\\"active\\\":false");
        assertThat(closed).isNotEqualTo(fixture("tsenta-detail-zoho.html"));
        NormalizedJob j = TsentaJobSource_parse(closed, ZOHO_ID);
        assertThat(j.active()).isFalse();
    }

    @Test
    void aPostingWithoutAnEmployerUrlHasNoApplicationUrlRatherThanATsentaOne() {
        String noUrl = fixture("tsenta-detail-zoho.html")
                .replace("https://sehyog.zohorecruit.in/jobs/Careers/167689000000570023/Senior-English-Tutor-UK-Curriculum",
                        "https://dashboard.tsenta.com/dashboard?apply=" + ZOHO_ID);
        NormalizedJob j = TsentaJobSource_parse(noUrl, ZOHO_ID);
        assertThat(j.applicationUrl()).isNull();
    }

    @Test
    void malformedAndForeignDetailPagesAreRejected() {
        assertThatThrownBy(() -> TsentaJobSource_parse("<html><body>nothing here</body></html>", ZOHO_ID))
                .isInstanceOf(Parsing.MalformedException.class);
        assertThatThrownBy(() -> TsentaJobSource_parse(fixture("tsenta-detail-zoho.html"), "not-a-uuid"))
                .isInstanceOf(Parsing.MalformedException.class);
        // The RSC job object of ANOTHER posting is never used for this id.
        NormalizedJob other = TsentaJobSource_parse(fixture("tsenta-detail-zoho.html"), CSOD_ID);
        assertThat(other.applicationUrl()).isNull();
    }

    @Test
    void detailFetchTimeoutsSurfaceAsFetchExceptions() {
        FixtureHttp f = new FixtureHttp().on("GET", ZOHO_ID, TIMEOUT);
        assertThatThrownBy(() -> tsenta.detail(jobs, ListingEntry.partial(ZOHO_ID, "https://tsenta.com/jobs/x-" + ZOHO_ID, null,
                java.util.Map.of()), f.context())).isInstanceOf(SafeHttpClient.FetchException.class);
    }

    private static NormalizedJob parse(String fixtureName, String id) {
        return TsentaJobSource_parse(fixture(fixtureName), id);
    }

    private static NormalizedJob TsentaJobSource_parse(String html, String id) {
        try {
            var m = TsentaJobSource.class.getDeclaredMethod("parseDetail", String.class, String.class, String.class,
                    BoardAdapter.CrawlContext.class);
            m.setAccessible(true);
            return (NormalizedJob) m.invoke(null, html, id, "https://tsenta.com/jobs/x-" + id, new FixtureHttp().context());
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
