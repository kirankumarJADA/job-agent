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

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests AdzunaAdapter against fixtures built from Adzuna's own PUBLISHED API
 * documentation (https://developer.adzuna.com/docs) — not a live capture (see
 * the adapter's own javadoc: this environment has no registered Adzuna
 * app_id/app_key). Nothing here depends on the public API being reachable.
 */
class AdzunaAdapterTest {

    private static AdzunaAdapter configured() {
        return new AdzunaAdapter("test-app-id", "test-app-key");
    }

    @Test
    void kindAndIdentifierBehaviour() {
        AdzunaAdapter adapter = configured();
        assertThat(adapter.kind()).isEqualTo(PlatformKind.ADZUNA);
        // No per-tenant board URL exists to recognise — a global, keyed search API, not a per-tenant board.
        assertThat(adapter.recognize(URI.create("https://www.adzuna.co.uk/land/ad/4123456789"))).isEmpty();
        assertThat(adapter.recognize(URI.create("https://boards.greenhouse.io/acme"))).isEmpty();
    }

    @Test
    void parseIdentifierRequiresConfiguredCredentials() {
        AdzunaAdapter noCredentials = new AdzunaAdapter("", "");
        assertThatThrownBy(() -> noCredentials.parseIdentifier("gb")).isInstanceOf(BoardConfigException.class)
                .hasMessageContaining("app_id");
        AdzunaAdapter onlyAppId = new AdzunaAdapter("id", "");
        assertThatThrownBy(() -> onlyAppId.parseIdentifier("gb")).isInstanceOf(BoardConfigException.class);
        AdzunaAdapter onlyAppKey = new AdzunaAdapter("", "key");
        assertThatThrownBy(() -> onlyAppKey.parseIdentifier("gb")).isInstanceOf(BoardConfigException.class);
    }

    @Test
    void parseIdentifierAcceptsOnlyADocumentedCountryCode() {
        AdzunaAdapter adapter = configured();
        BoardRef ref = adapter.parseIdentifier("gb");
        assertThat(ref.kind()).isEqualTo(PlatformKind.ADZUNA);
        assertThat(ref.identifier()).isEqualTo("gb");
        assertThat(adapter.parseIdentifier(" GB ").identifier()).isEqualTo("gb"); // trimmed, lower-cased
        assertThatThrownBy(() -> adapter.parseIdentifier("zz")).isInstanceOf(BoardConfigException.class);
        assertThatThrownBy(() -> adapter.parseIdentifier(null)).isInstanceOf(BoardConfigException.class);
    }

    @Test
    void listParsesAFixturePageIntoCompleteNormalizedJobsWithCountryImpliedCurrency() {
        FixtureHttp http = new FixtureHttp()
                .sequence("GET", "v1/api/jobs/gb/search",
                        FixtureHttp.json(FixtureHttp.fixture("adzuna-search-page1.json")),
                        FixtureHttp.json(FixtureHttp.fixture("adzuna-search-empty.json")));

        AdzunaAdapter adapter = configured();
        BoardRef board = adapter.parseIdentifier("gb");
        CrawlContext context = new CrawlContext(http.client(), 50, Instant.parse("2026-10-20T00:00:00Z"), null);
        ListingResult result = adapter.list(board, context);

        // Two valid records; the third (missing id and title) is skipped.
        assertThat(result.entries()).hasSize(2);
        assertThat(result.complete()).isFalse(); // a live search index is never treated as exhaustively enumerated
        assertThat(result.pagesFetched()).isEqualTo(2);
        assertThat(result.failure()).isNull();

        NormalizedJob first = result.entries().get(0).job();
        assertThat(first.externalId()).isEqualTo("4123456789");
        assertThat(first.title()).isEqualTo("Senior Backend Engineer (Java)");
        assertThat(first.employerName()).isEqualTo("Acme Technology Ltd");
        assertThat(first.locationRaw()).isEqualTo("London, London");
        assertThat(first.country()).isEqualTo("GB");
        assertThat(first.applicationUrl()).isEqualTo("https://www.adzuna.co.uk/land/ad/4123456789?se=abc123&utm_medium=api");
        assertThat(first.sourceUrl()).isEqualTo(first.applicationUrl());
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-10-18T09:12:00Z"));
        assertThat(first.employmentType()).isEqualTo("full_time, permanent");
        assertThat(first.salaryMin()).isEqualByComparingTo(BigDecimal.valueOf(65000.0));
        assertThat(first.salaryMax()).isEqualByComparingTo(BigDecimal.valueOf(85000.0));
        assertThat(first.salaryCurrency()).isEqualTo("GBP"); // implied by the 'gb' country, never stated by the record itself
        assertThat(first.descriptionText()).contains("Senior Backend Engineer");
        // Category labels are a broad industry bucket, never fed to skills() — see the adapter's own javadoc.
        assertThat(first.skills()).isEmpty();

        NormalizedJob second = result.entries().get(1).job();
        assertThat(second.externalId()).isEqualTo("4123456790");
        assertThat(second.employerName()).isEqualTo("Northbridge Data Ltd");
        assertThat(second.salaryMin()).isEqualByComparingTo(BigDecimal.valueOf(55000.0));
    }

    @Test
    void listStopsCleanlyOnTheFirstEmptyPageWithoutBeingTreatedAsAFailure() {
        FixtureHttp http = new FixtureHttp()
                .on("GET", "v1/api/jobs/gb/search", FixtureHttp.json(FixtureHttp.fixture("adzuna-search-empty.json")));
        AdzunaAdapter adapter = configured();
        ListingResult result = adapter.list(adapter.parseIdentifier("gb"), http.context());

        assertThat(result.entries()).isEmpty();
        assertThat(result.failure()).isNull();
        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
    }

    @Test
    void listNeverExceedsItsOwnPageCapAndReportsNoFailureWhenItStopsThere() {
        FixtureHttp http = new FixtureHttp()
                .on("GET", "v1/api/jobs/gb/search", FixtureHttp.json(FixtureHttp.fixture("adzuna-search-page1.json")));
        AdzunaAdapter adapter = configured();
        ListingResult result = adapter.list(adapter.parseIdentifier("gb"), http.context(5));

        assertThat(result.pagesFetched()).isEqualTo(5);
        assertThat(result.failure()).isNull();
        assertThat(result.complete()).isFalse();
        assertThat(result.entries()).hasSize(2); // same 2 valid externalIds repeated every page, deduped by Crawl
    }

    @Test
    void listReportsAGenuineUpstreamErrorAsPartialNotSilentlyDropped() {
        FixtureHttp http = new FixtureHttp().on("GET", "v1/api/jobs/gb/search", FixtureHttp.status(503));
        AdzunaAdapter adapter = configured();
        ListingResult result = adapter.list(adapter.parseIdentifier("gb"), http.context());

        assertThat(result.complete()).isFalse();
        assertThat(result.failure()).isEqualTo(FailureCategory.UPSTREAM_ERROR);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void listReportsRateLimitingAsItsOwnFailureCategory() {
        FixtureHttp http = new FixtureHttp().on("GET", "v1/api/jobs/gb/search", FixtureHttp.status(429));
        AdzunaAdapter adapter = configured();
        ListingResult result = adapter.list(adapter.parseIdentifier("gb"), http.context());

        assertThat(result.failure()).isEqualTo(FailureCategory.RATE_LIMITED);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void listReportsInvalidCredentialsAsAccessDeniedNeverAsFabricatedSuccess() {
        // Adzuna responds 401 for an unrecognised app_id/app_key pair.
        FixtureHttp http = new FixtureHttp().on("GET", "v1/api/jobs/gb/search", FixtureHttp.status(401));
        AdzunaAdapter adapter = configured();
        ListingResult result = adapter.list(adapter.parseIdentifier("gb"), http.context());

        assertThat(result.failure()).isEqualTo(FailureCategory.ACCESS_DENIED);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void listReportsATimeoutAsItsOwnFailureCategoryNeverAsFabricatedSuccess() {
        FixtureHttp http = new FixtureHttp().on("GET", "v1/api/jobs/gb/search", FixtureHttp.TIMEOUT);
        AdzunaAdapter adapter = configured();
        ListingResult result = adapter.list(adapter.parseIdentifier("gb"), http.context());

        assertThat(result.failure()).isEqualTo(FailureCategory.TIMEOUT);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void aRecordMissingAMandatoryFieldIsSkippedWithoutFailingTheWholePage() {
        String pageWithOneBadRecord = "{\"count\":1,\"results\":["
                + "{\"id\":\"1\",\"title\":\"Backend Engineer\",\"redirect_url\":\"https://www.adzuna.co.uk/land/ad/1\"},"
                + "{\"title\":\"Missing id\",\"redirect_url\":\"https://www.adzuna.co.uk/land/ad/2\"}"
                + "]}";
        FixtureHttp http = new FixtureHttp()
                .sequence("GET", "v1/api/jobs/gb/search",
                        FixtureHttp.json(pageWithOneBadRecord),
                        FixtureHttp.json(FixtureHttp.fixture("adzuna-search-empty.json")));
        AdzunaAdapter adapter = configured();
        ListingResult result = adapter.list(adapter.parseIdentifier("gb"), http.context());

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).externalId()).isEqualTo("1");
    }

    @Test
    void aResponseWithNoResultsArrayIsTreatedAsMalformedNotAsValidJobs() {
        FixtureHttp http = new FixtureHttp().on("GET", "v1/api/jobs/gb/search", FixtureHttp.json("{\"error\":\"unexpected\"}"));
        AdzunaAdapter adapter = configured();
        ListingResult result = adapter.list(adapter.parseIdentifier("gb"), http.context());

        assertThat(result.failure()).isEqualTo(FailureCategory.MALFORMED_RESPONSE);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void detailIsNeverActuallyCallableBecauseEveryListingEntryIsAlreadyComplete() {
        AdzunaAdapter adapter = configured();
        var entry = com.personal.jobagent.discovery.platform.BoardAdapter.ListingEntry.complete(
                NormalizedJob.builder("x", "Title").build());
        assertThat(entry.needsDetail()).isFalse();
        assertThatThrownBy(() -> adapter.detail(adapter.parseIdentifier("gb"), entry,
                new CrawlContext(SafeHttpClient.live(SafeHttpClient.Settings.defaults()), 1, Instant.now(), null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
