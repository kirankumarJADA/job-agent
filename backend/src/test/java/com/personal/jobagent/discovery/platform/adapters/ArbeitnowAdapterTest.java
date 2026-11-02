package com.personal.jobagent.discovery.platform.adapters;

import com.personal.jobagent.discovery.platform.BoardAdapter.BoardRef;
import com.personal.jobagent.discovery.platform.BoardAdapter.CrawlContext;
import com.personal.jobagent.discovery.platform.BoardAdapter.ListingResult;
import com.personal.jobagent.discovery.platform.BoardConfigException;
import com.personal.jobagent.discovery.platform.FailureCategory;
import com.personal.jobagent.discovery.platform.FixtureHttp;
import com.personal.jobagent.discovery.platform.NormalizedJob;
import com.personal.jobagent.discovery.platform.PlatformKind;
import com.personal.jobagent.discovery.platform.SafeHttpClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests ArbeitnowAdapter against REAL captured responses from
 * https://www.arbeitnow.com/api/job-board-api (fixtures under
 * src/test/resources/discovery/platforms/arbeitnow-*.json, captured live
 * 2026-10-19 — see the adapter's own javadoc for the endpoint and the
 * verified API contract: a free, keyless, documented API whose own response
 * states its terms inline). Nothing here depends on the public site being
 * reachable.
 */
class ArbeitnowAdapterTest {

    @Test
    void kindAndIdentifierBehaviour() {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        assertThat(adapter.kind()).isEqualTo(PlatformKind.ARBEITNOW);
        // No board URL exists for a candidate to paste — this is a global, keyless API, not a per-tenant board.
        assertThat(adapter.recognize(URI.create("https://www.arbeitnow.com/jobs/companies/doctolib/some-job-335262"))).isEmpty();
        assertThat(adapter.recognize(URI.create("https://boards.greenhouse.io/acme"))).isEmpty();
    }

    @Test
    void parseIdentifierAcceptsOnlyTheFixedScopeAndRejectsAnythingElse() {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        BoardRef ref = adapter.parseIdentifier("jobs");
        assertThat(ref.kind()).isEqualTo(PlatformKind.ARBEITNOW);
        assertThat(ref.identifier()).isEqualTo("jobs");
        assertThat(adapter.parseIdentifier(" Jobs ").identifier()).isEqualTo("jobs"); // trimmed, lower-cased
        assertThatThrownBy(() -> adapter.parseIdentifier("backend")).isInstanceOf(BoardConfigException.class);
        assertThatThrownBy(() -> adapter.parseIdentifier(null)).isInstanceOf(BoardConfigException.class);
    }

    @Test
    void listParsesARealCapturedPageIntoCompleteNormalizedJobs() {
        // Page 1 returns three real records (see field-by-field assertions
        // below); page 2 returns the real, hand-verified empty page so the
        // loop ends cleanly after exactly one page of real entries.
        FixtureHttp http = new FixtureHttp()
                .sequence("GET", "api/job-board-api",
                        FixtureHttp.json(FixtureHttp.fixture("arbeitnow-page1.json")),
                        FixtureHttp.json(FixtureHttp.fixture("arbeitnow-empty.json")));

        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        BoardRef board = adapter.parseIdentifier("jobs");
        // This fixture was captured live on 2026-10-19, so its real created_at
        // values are naturally after FixtureHttp.context()'s fixed 2026-10-10
        // clock — Normalize.postedAt would correctly reject them as implausibly
        // "future" relative to that fixed clock. A custom, later "now" is used
        // here instead, purely so this specific real-fixture test can assert the
        // exact parsed timestamp — the same technique FreeHireAdapterTest uses.
        CrawlContext context = new CrawlContext(http.client(), 50, Instant.parse("2026-10-20T00:00:00Z"), null);
        ListingResult result = adapter.list(board, context);

        assertThat(result.entries()).hasSize(3);
        assertThat(result.complete()).isFalse(); // never exhaustive, by design — even on a clean run
        assertThat(result.pagesFetched()).isEqualTo(2);
        assertThat(result.failure()).isNull();

        NormalizedJob first = result.entries().get(0).job();
        assertThat(first.externalId()).isEqualTo("technical-onboarding-specialist-praxissoftware-berlin-berlin-335262");
        assertThat(first.title()).isEqualTo("Technical Onboarding Specialist - Praxissoftware (x/f/m)");
        assertThat(first.employerName()).isEqualTo("Doctolib");
        assertThat(first.locationRaw()).isEqualTo("Berlin, Berlin");
        // Free-text location with no consistent structure across the real
        // catalogue — never split into city/country (see the adapter's own
        // module comment).
        assertThat(first.city()).isNull();
        assertThat(first.country()).isNull();
        assertThat(first.remoteType()).isNull(); // remote: false — never guessed as ONSITE
        assertThat(first.employmentType()).isNull(); // job_types: [] on this real record
        // job-specific, genuine Arbeitnow application page — never left null.
        assertThat(first.applicationUrl())
                .isEqualTo("https://www.arbeitnow.com/jobs/companies/doctolib/technical-onboarding-specialist-praxissoftware-berlin-berlin-335262");
        assertThat(first.sourceUrl()).isEqualTo(first.applicationUrl());
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-10-19T20:55:17Z"));
        // This real record's description is double HTML-entity-encoded
        // (`&lt;p&gt;...`) — Normalize.htmlToText's own two-pass decode must
        // strip it down to genuine plain text, not leave escaped markup visible.
        assertThat(first.descriptionText()).doesNotContain("&lt;", "<p").contains("Du bringst Leidenschaft");

        NormalizedJob second = result.entries().get(1).job();
        assertThat(second.externalId()).isEqualTo("remote-cloud-engineer-cologne-307668");
        assertThat(second.employerName()).isEqualTo("Trusted Shops SE (DE)");
        assertThat(second.remoteType()).isEqualTo("REMOTE"); // remote: true
        assertThat(second.employmentType()).isEqualTo("Mid, fulltime permanent");
        assertThat(second.postedAt()).isEqualTo(Instant.parse("2026-10-19T20:51:29Z"));
        // This real record's description is normally HTML-encoded (single pass).
        assertThat(second.descriptionText()).doesNotContain("<p").contains("Trusted Shops creates digital trust");

        NormalizedJob third = result.entries().get(2).job();
        assertThat(third.externalId()).isEqualTo("sales-manager-berufseinstieg-berlin-102802");
        assertThat(third.remoteType()).isEqualTo("REMOTE");
        assertThat(third.employmentType()).isEqualTo("Unbefristeter arbeitsvertrag, berufseinstieg");
        assertThat(third.descriptionText()).doesNotContain("<p").contains("Du stehst am Anfang");
    }

    @Test
    void listStopsCleanlyOnTheFirstEmptyPageWithoutBeingTreatedAsAFailure() {
        FixtureHttp http = new FixtureHttp()
                .on("GET", "api/job-board-api", FixtureHttp.json(FixtureHttp.fixture("arbeitnow-empty.json")));
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("jobs"), http.context());

        assertThat(result.entries()).isEmpty();
        assertThat(result.failure()).isNull();
        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
    }

    @Test
    void listNeverExceedsItsOwnPageCapAndReportsNoFailureWhenItStopsThere() {
        // Every page returns a full page of real records forever, so without
        // the run's own page-cap check this would loop indefinitely. A clean
        // stop at the configured cap is never reported as a failure.
        FixtureHttp http = new FixtureHttp()
                .on("GET", "api/job-board-api", FixtureHttp.json(FixtureHttp.fixture("arbeitnow-page1.json")));
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("jobs"), http.context(5));

        assertThat(result.pagesFetched()).isEqualTo(5);
        assertThat(result.failure()).isNull();
        assertThat(result.complete()).isFalse();
        assertThat(result.entries()).hasSize(3); // same 3 externalIds repeated every page, deduped by Crawl
    }

    @Test
    void listReportsAGenuineUpstreamErrorAsPartialNotSilentlyDropped() {
        FixtureHttp http = new FixtureHttp().on("GET", "api/job-board-api", FixtureHttp.status(503));
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("jobs"), http.context());

        assertThat(result.complete()).isFalse();
        assertThat(result.failure()).isEqualTo(FailureCategory.UPSTREAM_ERROR);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void listReportsRateLimitingAsItsOwnFailureCategory() {
        FixtureHttp http = new FixtureHttp().on("GET", "api/job-board-api", FixtureHttp.status(429));
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("jobs"), http.context());

        assertThat(result.failure()).isEqualTo(FailureCategory.RATE_LIMITED);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void listReportsATimeoutAsItsOwnFailureCategoryNeverAsFabricatedSuccess() {
        FixtureHttp http = new FixtureHttp().on("GET", "api/job-board-api", FixtureHttp.TIMEOUT);
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("jobs"), http.context());

        assertThat(result.failure()).isEqualTo(FailureCategory.TIMEOUT);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void aRecordMissingAMandatoryFieldIsSkippedWithoutFailingTheWholePage() {
        String pageWithOneBadRecord = "{\"data\":["
                + "{\"slug\":\"ok-job\",\"title\":\"Backend Engineer\",\"url\":\"https://www.arbeitnow.com/jobs/companies/acme/ok-job\",\"tags\":[],\"job_types\":[]},"
                + "{\"slug\":\"missing-title\",\"url\":\"https://www.arbeitnow.com/jobs/companies/acme/missing-title\",\"tags\":[],\"job_types\":[]}"
                + "],\"links\":{\"next\":null},\"meta\":{}}";
        FixtureHttp http = new FixtureHttp()
                .sequence("GET", "api/job-board-api",
                        FixtureHttp.json(pageWithOneBadRecord),
                        FixtureHttp.json(FixtureHttp.fixture("arbeitnow-empty.json")));
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("jobs"), http.context());

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).externalId()).isEqualTo("ok-job");
    }

    @Test
    void aResponseWithNoDataArrayIsTreatedAsMalformedNotAsValidJobs() {
        FixtureHttp http = new FixtureHttp().on("GET", "api/job-board-api", FixtureHttp.json("{\"error\":\"unexpected\"}"));
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("jobs"), http.context());

        assertThat(result.failure()).isEqualTo(FailureCategory.MALFORMED_RESPONSE);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void detailIsNeverActuallyCallableBecauseEveryListingEntryIsAlreadyComplete() {
        ArbeitnowAdapter adapter = new ArbeitnowAdapter();
        var entry = com.personal.jobagent.discovery.platform.BoardAdapter.ListingEntry.complete(
                NormalizedJob.builder("x", "Title").build());
        assertThat(entry.needsDetail()).isFalse();
        assertThatThrownBy(() -> adapter.detail(adapter.parseIdentifier("jobs"), entry,
                new CrawlContext(SafeHttpClient.live(SafeHttpClient.Settings.defaults()), 1, Instant.now(), null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
