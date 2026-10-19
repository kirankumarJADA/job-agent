package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.discovery.platform.BoardAdapter.ListingEntry;
import com.personal.jobagent.discovery.platform.BoardAdapter.ListingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static com.personal.jobagent.discovery.platform.FixtureHttp.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The single run path: ingestion, completeness-gated removal, budgets and health recording. */
class BoardDiscoveryServiceTest {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-0000000b0a2d");

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final JobDiscoveryService ingestion = mock(JobDiscoveryService.class);
    private final PlatformRegistry registry = new PlatformRegistry(40);
    private FixtureHttp http;
    private BoardDiscoveryService service;

    @BeforeEach
    void setUp() {
        http = new FixtureHttp();
        Function<PlatformKind, SafeHttpClient> factory = kind -> http.client();
        service = new BoardDiscoveryService(db, ingestion, registry, factory, 50, 2, 3, 10);
        when(ingestion.ingestJob(any())).thenAnswer(inv -> new JobDiscoveryService.IngestResult(
                UUID.randomUUID(), "INSERTED", "d", "h"));
    }

    private void sourceRow(String kind, String org, boolean enabled, String policy) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("kind", kind);
        row.put("org_identifier", org);
        row.put("enabled", enabled);
        row.put("policy", policy);
        row.put("company_name", null);
        when(db.queryForList(contains("from job_sources s left join companies"), eq(SOURCE))).thenReturn(List.of(row));
    }

    /** Minimal adapter whose listing and details are scripted by each test. */
    private static final class Scripted implements BoardAdapter {
        ListingResult listing;
        final List<String> detailed = new ArrayList<>();
        RuntimeException detailFailure;
        RuntimeException listFailure;

        @Override public PlatformKind kind() { return PlatformKind.GEM; }
        @Override public Optional<BoardRef> recognize(URI url) { return Optional.empty(); }
        @Override public BoardRef parseIdentifier(String id) {
            if ("bad".equals(id)) throw new BoardConfigException("board identifier is not valid");
            return new BoardRef(kind(), id, Map.of());
        }
        @Override public ListingResult list(BoardRef board, CrawlContext context) {
            if (listFailure != null) throw listFailure;
            return listing;
        }
        @Override public NormalizedJob detail(BoardRef board, ListingEntry entry, CrawlContext context) {
            if (detailFailure != null) throw detailFailure;
            detailed.add(entry.externalId());
            return NormalizedJob.builder(entry.externalId(), "Role " + entry.externalId()).description("d")
                    .applicationUrl("https://jobs.gem.com/acme/" + entry.externalId()).build();
        }
    }

    private static ListingEntry full(String id) {
        return ListingEntry.complete(NormalizedJob.builder(id, "Role " + id).description("d")
                .applicationUrl("https://jobs.gem.com/acme/" + id).sourceUrl("https://jobs.gem.com/acme/" + id).build());
    }

    private static ListingEntry partial(String id) {
        return ListingEntry.partial(id, "https://jobs.gem.com/acme/" + id, "Role " + id, Map.of());
    }

    @Test
    void aCompleteListingIngestsEverythingAndMayMarkUnlistedPostings() {
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(full("1"), full("2")), 1, null);

        var report = service.run(SOURCE, a, "acme", "Acme Ltd", BoardDiscoveryService.Mode.MANUAL);

        assertThat(report.ok()).isTrue();
        assertThat(report.complete()).isTrue();
        assertThat(report.counts()).containsEntry("created", 2).containsEntry("listed", 2);
        verify(ingestion, times(2)).ingestJob(any());
        verify(db).update(contains("source_status = 'MISSING'"), eq(SOURCE), any(java.sql.Timestamp.class));
        verify(db).update(contains("update jobs j set deleted_at = now()"), eq(SOURCE));
        verify(db).update(contains("last_success_at = now()"), anyString(), anyString(), eq(SOURCE));
    }

    @Test
    void theConfiguredEmployerNameIsUsedNotTheSourceDisplayName() {
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(full("1")), 1, "Board Says Inc");
        service.run(SOURCE, a, "acme", "Acme Ltd", BoardDiscoveryService.Mode.MANUAL);
        ArgumentCaptor<JobDiscoveryService.IngestJobCommand> cmd = ArgumentCaptor.forClass(JobDiscoveryService.IngestJobCommand.class);
        verify(ingestion).ingestJob(cmd.capture());
        assertThat(cmd.getValue().companyNameRaw()).isEqualTo("Acme Ltd");
    }

    @Test
    void aFailedOrPartialListingNeverRemovesAnything() {
        Scripted a = new Scripted();
        a.listing = ListingResult.partial(List.of(full("1")), 2, FailureCategory.UPSTREAM_ERROR, "HTTP 503", null);

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.SCHEDULED);

        assertThat(report.ok()).isFalse();
        assertThat(report.status()).isEqualTo("degraded");
        verify(ingestion).ingestJob(any());
        verify(db, never()).update(contains("source_status = 'MISSING'"), any(Object[].class));
        verify(db, never()).update(contains("set deleted_at = now()"), any(Object[].class));
        ArgumentCaptor<String> health = ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("failure_streak = failure_streak + 1"), anyString(), health.capture(), eq(SOURCE));
        assertThat(health.getValue()).contains("UPSTREAM_ERROR");
    }

    @Test
    void aPartialByDesignListingIsHealthyButStillNeverRemoves() {
        Scripted a = new Scripted();
        a.listing = ListingResult.partial(List.of(full("1")), 1, null, "feed is not exhaustive", null);

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.SCHEDULED);

        assertThat(report.ok()).isTrue();
        assertThat(report.complete()).isFalse();
        verify(db, never()).update(contains("source_status = 'MISSING'"), any(Object[].class));
        verify(db).update(contains("failure_streak = 0"), anyString(), anyString(), eq(SOURCE));
    }

    @Test
    void anEmptyFailedListingIsFailedAndRemovesNothing() {
        Scripted a = new Scripted();
        a.listing = ListingResult.failed(FailureCategory.TIMEOUT, "timed out");
        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.SCHEDULED);
        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.failureCategory()).isEqualTo("TIMEOUT");
        verify(db, never()).update(contains("set deleted_at = now()"), any(Object[].class));
    }

    @Test
    void knownPostingsAreTouchedNewOnesDetailedWithinBudgetAndTheRestDeferred() {
        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE)))
                .thenReturn(List.of(Map.of("external_job_id", "known")));
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(partial("known"), partial("n1"), partial("n2"), partial("n3")), 1, null);

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL);

        assertThat(a.detailed).containsExactly("n1", "n2"); // detail budget is 2 in this test
        assertThat(report.counts()).containsEntry("unchanged", 1).containsEntry("created", 2).containsEntry("deferred", 1);
        verify(db).update(contains("update job_source_observations set last_seen_at = now()"), eq(SOURCE), eq("known"));
    }

    @Test
    void detailFailuresAreCountedAndThrottlingStopsFurtherDetailRequests() {
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(partial("a"), partial("b")), 1, null);
        a.detailFailure = new SafeHttpClient.FetchException(FailureCategory.RATE_LIMITED, "HTTP 429", 429);

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL);

        assertThat(report.counts()).containsEntry("detail_failures", 1).containsEntry("deferred", 1);
        verify(ingestion, never()).ingestJob(any());
    }

    @Test
    void anAggregatorPostingWithoutAnEmployerUrlIsSkippedNotStoredWithAnAggregatorUrl() {
        BoardAdapter tsenta = registry.adapter(PlatformKind.TSENTA).orElseThrow();
        http.on("GET", "robots.txt", ok("text/plain", fixture("tsenta-robots.txt")))
                .on("GET", "page=1", html(fixture("tsenta-list-page2.html")))
                .on("GET", "page=2", html("<html><body><script>self.__next_f.push([1,\"x\"])</script></body></html>"))
                .on("GET", "/jobs/", html(fixture("tsenta-detail-zoho.html")));
        var report = service.run(SOURCE, tsenta, "jobs", null, BoardDiscoveryService.Mode.MANUAL);
        // Every card resolves to the same fixture page whose RSC job id does not match the card's
        // uuid, so no employer URL can be proven for them: none may be ingested.
        assertThat(report.counts().get("skipped_no_employer_url")).isEqualTo(2);
        verify(ingestion, never()).ingestJob(any());
        // And a Tsenta run is never complete, so it can never remove anything.
        verify(db, never()).update(contains("source_status = 'MISSING'"), any(Object[].class));
    }

    /**
     * Robin x Tsenta continuation: proves FreeHire is genuinely wired into the
     * real discovery pipeline — not a disconnected adapter nothing ever calls.
     * Uses the REAL {@link com.personal.jobagent.discovery.platform.adapters.FreeHireAdapter}
     * (resolved from the real {@link PlatformRegistry}, exactly as
     * {@code BoardDiscoveryService.run(sourceId, Mode)} would) against a REAL
     * captured FreeHire response, through this exact {@code run()} overload,
     * all the way to a captured {@link JobDiscoveryService.IngestJobCommand}.
     */
    @Test
    void freeHireResultsReachRealIngestionWithTheEmployersOwnApplicationUrlPreserved() {
        BoardAdapter freeHire = registry.adapter(PlatformKind.FREEHIRE).orElseThrow();
        http.sequence("GET", "api/v1/agent/jobs/search",
                json(fixture("freehire-search-page1.json")),
                json(fixture("freehire-search-empty.json")));

        var report = service.run(SOURCE, freeHire, "software_engineering", null, BoardDiscoveryService.Mode.MANUAL);

        assertThat(report.counts().get("listed")).isEqualTo(2);
        assertThat(report.complete()).isFalse(); // an aggregator listing is never exhaustive
        ArgumentCaptor<JobDiscoveryService.IngestJobCommand> captor =
                ArgumentCaptor.forClass(JobDiscoveryService.IngestJobCommand.class);
        verify(ingestion, times(2)).ingestJob(captor.capture());
        JobDiscoveryService.IngestJobCommand first = captor.getAllValues().get(0);
        assertThat(first.title()).isEqualTo("System Engineer");
        assertThat(first.companyNameRaw()).isEqualTo("Sana Soft");
        // The employer's OWN ATS posting URL — never a freehire.me redirect.
        assertThat(first.applicationUrl())
                .isEqualTo("https://sanasofteg.zohorecruit.com/jobs/Careers/681293000002210024?utm_source=freehire.me");
        assertThat(first.canonicalUrl()).isEqualTo(first.applicationUrl());
    }

    @Test
    void aClosedPostingClosesItsObservationInsteadOfBeingIngested() {
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(ListingEntry.complete(NormalizedJob.builder("gone", "Old").description("d")
                .active(false).build())), 1, null);
        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL);
        assertThat(report.counts()).containsEntry("closed", 1);
        verify(ingestion, never()).ingestJob(any());
        verify(db).update(contains("source_status = 'CLOSED'"), eq(SOURCE), eq("gone"));
    }

    @Test
    void aBadBoardIdentifierIsAConfigurationFailure() {
        Scripted a = new Scripted();
        var report = service.run(SOURCE, a, "bad", null, BoardDiscoveryService.Mode.MANUAL);
        assertThat(report.failureCategory()).isEqualTo("CONFIGURATION");
        assertThat(report.failureDetail()).contains("not valid");
    }

    @Test
    void anAdapterBugIsRecordedNotPropagated() {
        Scripted a = new Scripted();
        a.listFailure = new IllegalStateException("bug");
        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL);
        assertThat(report.failureCategory()).isEqualTo("MALFORMED_RESPONSE");
        verify(db).update(contains("failure_streak = failure_streak + 1"), anyString(), anyString(), eq(SOURCE));
    }

    @Test
    void disabledUnknownNonBoardAndUnsupportedSourcesAreRefusedWithoutStateChanges() {
        sourceRow("GEM", "gem", false, "DISCOVERY_ONLY");
        assertThatThrownBy(() -> service.run(SOURCE, BoardDiscoveryService.Mode.MANUAL))
                .isInstanceOf(BoardDiscoveryService.RunRefused.class).hasMessageContaining("disabled");
        sourceRow("GEM", "gem", true, "DISABLED");
        assertThatThrownBy(() -> service.run(SOURCE, BoardDiscoveryService.Mode.MANUAL)).hasMessageContaining("disabled");
        sourceRow("SEARCH_API", "x", true, "DISCOVERY_ONLY");
        assertThatThrownBy(() -> service.run(SOURCE, BoardDiscoveryService.Mode.MANUAL)).hasMessageContaining("url is required");
        sourceRow("BRASSRING", "25526/5032", true, "DISCOVERY_ONLY");
        assertThatThrownBy(() -> service.run(SOURCE, BoardDiscoveryService.Mode.MANUAL)).hasMessageContaining("blocked");
        when(db.queryForList(contains("from job_sources s left join companies"), eq(SOURCE))).thenReturn(List.of());
        assertThatThrownBy(() -> service.run(SOURCE, BoardDiscoveryService.Mode.MANUAL))
                .isInstanceOfSatisfying(BoardDiscoveryService.RunRefused.class, r -> assertThat(r.notFound()).isTrue());
        verify(db, never()).update(contains("update job_sources"), any(Object[].class));
    }

    @Test
    void aRealAdapterRunsEndToEndThroughTheRegistry() {
        sourceRow("GEM", "gem", true, "DISCOVERY_ONLY");
        http.on("GET", "api.gem.com", json(fixture("gem-posts.json")));
        var report = service.run(SOURCE, BoardDiscoveryService.Mode.MANUAL);
        assertThat(report.kind()).isEqualTo("GEM");
        assertThat(report.counts()).containsEntry("listed", 2).containsEntry("created", 2).containsEntry("requests", 1);
        assertThat(report.complete()).isTrue();
    }

    @Test
    void healthCheckModeUsesASmallDetailBudget() {
        sourceRow("RIPPLING", "rippling", true, "DISCOVERY_ONLY");
        http.on("GET", "/jobs/", json(fixture("rippling-detail.json"))).on("GET", "/rippling/jobs", json(fixture("rippling-list.json")));
        var report = service.run(SOURCE, BoardDiscoveryService.Mode.HEALTH_CHECK);
        // Three new postings, health-check detail budget min(5, configured 2) — the configured cap here is 2.
        assertThat(report.counts().get("created")).isEqualTo(2);
        assertThat(report.counts().get("deferred")).isEqualTo(1);
    }

    @Test
    void theRecordedRunMetadataCarriesCountsAndNoSecrets() {
        Scripted a = new Scripted();
        a.listing = ListingResult.partial(List.of(), 0, FailureCategory.ACCESS_DENIED, "HTTP 403", null);
        service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL);
        ArgumentCaptor<String> lastRun = ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("last_run = ?::jsonb"), lastRun.capture(), anyString(), eq(SOURCE));
        assertThat(lastRun.getValue()).contains("\"failure_category\":\"ACCESS_DENIED\"", "\"duration_ms\"", "\"mode\":\"MANUAL\"")
                .doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("password");
    }
}
