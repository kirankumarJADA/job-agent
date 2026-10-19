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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests FreeHireAdapter against REAL captured responses from
 * https://freehire.me/api/v1/agent/jobs/search (fixtures under
 * src/test/resources/discovery/platforms/freehire-*.json, captured live
 * 2026-10-19 — see the adapter's own javadoc for the endpoint and the
 * verified API contract). Nothing here depends on the public site being
 * reachable; the small defensive-path fixtures (error envelope, pagination
 * boundary) are hand-built from the exact envelope shape the live API
 * returned, not modelled from documentation alone.
 */
class FreeHireAdapterTest {

    @Test
    void kindAndIdentifierBehaviour() {
        FreeHireAdapter adapter = new FreeHireAdapter();
        assertThat(adapter.kind()).isEqualTo(PlatformKind.FREEHIRE);
        // No board URL exists for a candidate to paste — this is a global, keyless search API, not a per-tenant board.
        assertThat(adapter.recognize(URI.create("https://freehire.me/jobs/some-job-abc123"))).isEmpty();
        assertThat(adapter.recognize(URI.create("https://boards.greenhouse.io/acme"))).isEmpty();
    }

    @Test
    void parseIdentifierAcceptsEveryDocumentedCategoryAndRejectsAnInvalidOne() {
        FreeHireAdapter adapter = new FreeHireAdapter();
        for (String category : FreeHireAdapter.SEEDED_CATEGORIES) {
            BoardRef ref = adapter.parseIdentifier(category);
            assertThat(ref.kind()).isEqualTo(PlatformKind.FREEHIRE);
            assertThat(ref.identifier()).isEqualTo(category);
        }
        assertThat(adapter.parseIdentifier(" Backend ").identifier()).isEqualTo("backend"); // trimmed, lower-cased
        assertThatThrownBy(() -> adapter.parseIdentifier("not-a-real-category"))
                .isInstanceOf(BoardConfigException.class);
    }

    @Test
    void listParsesARealCapturedPageIntoCompleteNormalizedJobs() {
        // Page 1 returns two real records (see field-by-field assertions below); the
        // second request (page 2, offset=2) returns the real, hand-verified empty page
        // so the loop ends cleanly after exactly one page of real entries.
        FixtureHttp http = new FixtureHttp()
                .sequence("GET", "api/v1/agent/jobs/search",
                        FixtureHttp.json(FixtureHttp.fixture("freehire-search-page1.json")),
                        FixtureHttp.json(FixtureHttp.fixture("freehire-search-empty.json")));

        FreeHireAdapter adapter = new FreeHireAdapter(2);
        BoardRef board = adapter.parseIdentifier("software_engineering");
        // This fixture was captured live on 2026-10-19, so its real posted_at
        // values are naturally after FixtureHttp.context()'s fixed 2026-10-10
        // clock — Normalize.postedAt would correctly reject them as implausibly
        // "future" relative to that fixed clock. A custom, later "now" is used
        // here instead, purely so this specific real-fixture test can assert the
        // exact parsed timestamp; every other test keeps the shared fixed clock.
        CrawlContext context = new CrawlContext(http.client(), 50, Instant.parse("2026-10-19T12:00:00Z"), null);
        ListingResult result = adapter.list(board, context);

        assertThat(result.entries()).hasSize(2);
        assertThat(result.complete()).isFalse(); // never exhaustive, by design — even on a clean run
        assertThat(result.pagesFetched()).isEqualTo(2);

        NormalizedJob first = result.entries().get(0).job();
        assertThat(first.externalId()).isEqualTo("system-engineer-sana-soft-sdvla6rc");
        assertThat(first.title()).isEqualTo("System Engineer");
        assertThat(first.employerName()).isEqualTo("Sana Soft");
        assertThat(first.locationRaw()).isEqualTo("Cairo, Egypt");
        assertThat(first.city()).isEqualTo("Cairo"); // exactly one city in the real record
        assertThat(first.country()).isEqualTo("eg");
        assertThat(first.applicationUrl())
                .isEqualTo("https://sanasofteg.zohorecruit.com/jobs/Careers/681293000002210024?utm_source=freehire.me");
        assertThat(first.sourceUrl()).isEqualTo(first.applicationUrl());
        assertThat(first.skills()).contains("vmware", "linux", "windows-server");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-10-18T23:50:22Z"));
        assertThat(first.descriptionText()).isNotBlank();

        NormalizedJob second = result.entries().get(1).job();
        // This real record's `cities` array is empty — never a guessed single city.
        assertThat(second.city()).isNull();
        assertThat(second.country()).isEqualTo("in");
    }

    @Test
    void listStopsCleanlyOnTheFirstEmptyPageWithoutBeingTreatedAsAFailure() {
        FixtureHttp http = new FixtureHttp()
                .on("GET", "api/v1/agent/jobs/search", FixtureHttp.json(FixtureHttp.fixture("freehire-search-empty.json")));
        FreeHireAdapter adapter = new FreeHireAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("backend"), http.context());

        assertThat(result.entries()).isEmpty();
        assertThat(result.failure()).isNull();
        assertThat(result.pagesFetched()).isEqualTo(1);
    }

    @Test
    void listNeverExceedsTheApisOwnPaginationDepthCeiling() {
        // Every page returns a full page of 100-ish records forever, so without the
        // adapter's own ceiling check this would run until the page cap. With a page
        // size of 100 and a generous page cap, the loop must still stop the moment
        // offset + limit would exceed 10,000 — never sending that request at all.
        FixtureHttp http = new FixtureHttp()
                .on("GET", "api/v1/agent/jobs/search", FixtureHttp.json(FixtureHttp.fixture("freehire-search-page1.json")));
        FreeHireAdapter adapter = new FreeHireAdapter(100);
        ListingResult result = adapter.list(adapter.parseIdentifier("devops"), http.context(500));

        int maxOffsetRequested = http.requests.stream()
                .map(r -> r.uri().getQuery())
                .mapToInt(q -> Integer.parseInt(q.replaceAll(".*offset=(\\d+).*", "$1")))
                .max().orElseThrow();
        assertThat(maxOffsetRequested + 100).isLessThanOrEqualTo(FreeHireAdapter.MAX_PAGINATION_DEPTH);
        // A clean stop at the API's own ceiling is never reported as a failure.
        assertThat(result.failure()).isNull();
    }

    @Test
    void listReportsAGenuineFetchFailureAsPartialNotSilentlyDropped() {
        FixtureHttp http = new FixtureHttp().on("GET", "api/v1/agent/jobs/search", FixtureHttp.status(503));
        FreeHireAdapter adapter = new FreeHireAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("frontend"), http.context());

        assertThat(result.complete()).isFalse();
        assertThat(result.failure()).isEqualTo(FailureCategory.UPSTREAM_ERROR);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void anUnexpectedErrorEnvelopeInAnOtherwiseSuccessfulResponseIsTreatedAsMalformedNotAsValidJobs() {
        // FreeHire's documented contract always pairs {"error": ...} with a non-2xx
        // status (confirmed live) — this exercises the adapter's own defensive check
        // for a contract drift where an error body arrived with HTTP 200 anyway.
        FixtureHttp http = new FixtureHttp()
                .on("GET", "api/v1/agent/jobs/search", FixtureHttp.json("{\"error\":\"search unavailable\"}"));
        FreeHireAdapter adapter = new FreeHireAdapter();
        ListingResult result = adapter.list(adapter.parseIdentifier("qa"), http.context());

        assertThat(result.failure()).isEqualTo(FailureCategory.MALFORMED_RESPONSE);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void aRecordMissingAMandatoryFieldIsSkippedWithoutFailingTheWholePage() {
        String pageWithOneBadRecord = "{\"data\":["
                + "{\"public_slug\":\"ok-job\",\"title\":\"Backend Engineer\",\"url\":\"https://acme.example/jobs/1\"},"
                + "{\"public_slug\":\"missing-title\",\"url\":\"https://acme.example/jobs/2\"}"
                + "],\"meta\":{\"limit\":2,\"offset\":0,\"total\":1}}";
        FixtureHttp http = new FixtureHttp()
                .sequence("GET", "api/v1/agent/jobs/search",
                        FixtureHttp.json(pageWithOneBadRecord),
                        FixtureHttp.json(FixtureHttp.fixture("freehire-search-empty.json")));
        FreeHireAdapter adapter = new FreeHireAdapter(2);
        ListingResult result = adapter.list(adapter.parseIdentifier("security"), http.context());

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).externalId()).isEqualTo("ok-job");
    }

    @Test
    void detailIsNeverActuallyCallableBecauseEveryListingEntryIsAlreadyComplete() {
        FreeHireAdapter adapter = new FreeHireAdapter();
        var entry = com.personal.jobagent.discovery.platform.BoardAdapter.ListingEntry.complete(
                NormalizedJob.builder("x", "Title").build());
        assertThat(entry.needsDetail()).isFalse();
        assertThatThrownBy(() -> adapter.detail(adapter.parseIdentifier("backend"), entry,
                new CrawlContext(SafeHttpClient.live(SafeHttpClient.Settings.defaults()), 1, Instant.now(), null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void seededCategoriesAreAllValidAgainstTheDocumentedVocabulary() {
        FreeHireAdapter adapter = new FreeHireAdapter();
        for (String category : FreeHireAdapter.seededCategories()) {
            assertThat(adapter.parseIdentifier(category).identifier()).isEqualTo(category);
        }
        assertThat(FreeHireAdapter.seededCategories()).containsExactlyInAnyOrderElementsOf(
                List.of("software_engineering", "backend", "frontend", "fullstack", "devops"));
    }
}
