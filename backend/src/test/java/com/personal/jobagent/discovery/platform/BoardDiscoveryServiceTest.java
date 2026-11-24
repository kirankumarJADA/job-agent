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
        when(ingestion.ingestJob(any(), any(), any())).thenAnswer(inv -> new JobDiscoveryService.IngestResult(
                UUID.randomUUID(), "INSERTED", "d", "h"));

        // Default: source row exists in job_sources with no initial plan-specific or default scan offset
        Map<String, Object> defaultSourceRow = new java.util.HashMap<>();
        when(db.queryForList(contains("select last_run->>'scan_offset'"), any(Object[].class)))
                .thenReturn(List.of(defaultSourceRow));
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenReturn(List.of(defaultSourceRow));
        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                .thenReturn(List.of(defaultSourceRow));
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
        long listDelayMs = 0;

        @Override public PlatformKind kind() { return PlatformKind.GEM; }
        @Override public Optional<BoardRef> recognize(URI url) { return Optional.empty(); }
        @Override public BoardRef parseIdentifier(String id) {
            if ("bad".equals(id)) throw new BoardConfigException("board identifier is not valid");
            return new BoardRef(kind(), id, Map.of());
        }
        @Override public ListingResult list(BoardRef board, CrawlContext context) {
            if (listFailure != null) throw listFailure;
            if (listDelayMs > 0) {
                try {
                    Thread.sleep(listDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
        verify(db).update(contains("failure_streak = failure_streak + 1"), anyString(), health.capture(), any(), eq(SOURCE));
        assertThat(health.getValue()).contains("UPSTREAM_ERROR");
    }

    @Test
    void anEmptyCompleteListingNeverRemovesExistingJobsSafeDeletionGuard() {
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(), 1, null);

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.SCHEDULED);

        assertThat(report.ok()).isTrue();
        assertThat(report.counts()).containsEntry("listed", 0);
        verify(db, never()).update(contains("source_status = 'MISSING'"), any(Object[].class));
        verify(db, never()).update(contains("set deleted_at = now()"), any(Object[].class));
    }

    @Test
    void noModeMayRemoveJobsWhenACompleteListingReturnsZeroEntries() {
        // C1 regression: an empty-but-complete listing is indistinguishable from a silent upstream
        // failure, so no mode - present or future - may treat it as authoritative. This loops every
        // Mode so that adding one cannot quietly reintroduce the bypass.
        for (BoardDiscoveryService.Mode mode : BoardDiscoveryService.Mode.values()) {
            org.mockito.Mockito.reset(db);
            Map<String, Object> defaultSourceRow = new java.util.HashMap<>();
            when(db.queryForList(contains("select last_run->>'scan_offset'"), any(Object[].class)))
                    .thenReturn(List.of(defaultSourceRow));
            when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                    .thenReturn(List.of(defaultSourceRow));
            when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                    .thenReturn(List.of(defaultSourceRow));
            Scripted a = new Scripted();
            a.listing = ListingResult.complete(List.of(), 1, null);

            var report = service.run(SOURCE, a, "acme", null, mode);

            assertThat(report.ok()).as("run stays healthy in %s mode", mode).isTrue();
            assertThat(report.counts()).as("nothing listed in %s mode", mode).containsEntry("listed", 0);
            assertThat(report.counts()).as("zero removals in %s mode", mode).containsEntry("removed", 0);
            verify(db, never()).update(contains("source_status = 'MISSING'"), any(Object[].class));
            verify(db, never()).update(contains("set deleted_at = now()"), any(Object[].class));
        }
    }

    @Test
    void aFailedListingInManualModeRemovesNothing() {
        Scripted a = new Scripted();
        a.listing = ListingResult.failed(FailureCategory.TIMEOUT, "timed out");

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL);

        assertThat(report.status()).isEqualTo("failed");
        assertThat(report.counts()).containsEntry("removed", 0);
        verify(db, never()).update(contains("source_status = 'MISSING'"), any(Object[].class));
        verify(db, never()).update(contains("set deleted_at = now()"), any(Object[].class));
    }

    @Test
    void anIncompleteListingInManualModeRemovesNothing() {
        Scripted a = new Scripted();
        a.listing = ListingResult.partial(List.of(full("1")), 2, FailureCategory.UPSTREAM_ERROR, "HTTP 503", null);

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL);

        assertThat(report.complete()).isFalse();
        assertThat(report.counts()).containsEntry("removed", 0);
        verify(db, never()).update(contains("source_status = 'MISSING'"), any(Object[].class));
        verify(db, never()).update(contains("set deleted_at = now()"), any(Object[].class));
    }

    @Test
    void aCompleteNonEmptyListingInManualModeStillPerformsSynchronisation() {
        // The guard must not cost legitimate manual synchronisation: a complete listing that
        // actually returned entries still reconciles missing postings.
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(full("1"), full("2")), 1, null);

        var report = service.run(SOURCE, a, "acme", "Acme Ltd", BoardDiscoveryService.Mode.MANUAL);

        assertThat(report.ok()).isTrue();
        assertThat(report.counts()).containsEntry("listed", 2);
        verify(db).update(contains("source_status = 'MISSING'"), eq(SOURCE), any(java.sql.Timestamp.class));
        verify(db).update(contains("update jobs j set deleted_at = now()"), eq(SOURCE));
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

    /**
     * Proves Arbeitnow is genuinely wired into the real discovery pipeline —
     * not a disconnected adapter nothing ever calls — using the REAL
     * {@link com.personal.jobagent.discovery.platform.adapters.ArbeitnowAdapter}
     * (resolved from the real {@link PlatformRegistry}) against REAL captured
     * responses, all the way to a captured
     * {@link JobDiscoveryService.IngestJobCommand}.
     *
     * <p>This is also the regression guard for a real bug this integration
     * surfaced and fixed: {@code ingest()} skips ANY aggregator-kind posting
     * whose {@code applicationUrl} is null (see the line above checking
     * {@code kind.isAggregator()}) — Arbeitnow's own hosted page must
     * therefore actually be set as {@code applicationUrl}, or every single
     * one of its postings would be silently dropped here, every run,
     * forever (ArbeitnowAdapter's own javadoc explains why that URL is a
     * genuine application destination, not a placeholder).
     */
    @Test
    void arbeitnowResultsReachRealIngestionWithItsOwnGenuineApplicationUrlSet() {
        BoardAdapter arbeitnow = registry.adapter(PlatformKind.ARBEITNOW).orElseThrow();
        http.sequence("GET", "api/job-board-api",
                json(fixture("arbeitnow-page1.json")),
                json(fixture("arbeitnow-empty.json")));

        var report = service.run(SOURCE, arbeitnow, "jobs", null, BoardDiscoveryService.Mode.MANUAL);

        assertThat(report.counts().get("listed")).isEqualTo(3);
        assertThat(report.complete()).isFalse(); // never exhaustive, by design
        assertThat(report.counts().getOrDefault("skipped_no_employer_url", 0)).isEqualTo(0); // the bug this guards against
        ArgumentCaptor<JobDiscoveryService.IngestJobCommand> captor =
                ArgumentCaptor.forClass(JobDiscoveryService.IngestJobCommand.class);
        verify(ingestion, times(3)).ingestJob(captor.capture());
        JobDiscoveryService.IngestJobCommand first = captor.getAllValues().get(0);
        assertThat(first.title()).isEqualTo("Technical Onboarding Specialist - Praxissoftware (x/f/m)");
        assertThat(first.companyNameRaw()).isEqualTo("Doctolib");
        assertThat(first.applicationUrl()).isEqualTo(
                "https://www.arbeitnow.com/jobs/companies/doctolib/technical-onboarding-specialist-praxissoftware-berlin-berlin-335262");
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
        verify(db).update(contains("failure_streak = failure_streak + 1"), anyString(), anyString(), any(), eq(SOURCE));
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
    void maxResultsCeilingHaltsIngestionAndSkipsMarkUnlisted() {
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(full("1"), full("2"), full("3"), full("4")), 1, null);
        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 2, 45000L);

        // Only 2 of the 4 entries were ingested
        assertThat(report.counts().get("created")).isEqualTo(2);
        assertThat(report.ingested()).hasSize(2);
        assertThat(report.complete()).isFalse();

        // Safe deletion guard: markUnlisted must NEVER be called when run was truncated by budget
        verify(db, never()).update(contains("update job_source_observations set source_status = 'MISSING'"), any(Object[].class));
    }

    @Test
    void timeoutCeilingHaltsExecutionAndRecordsFailureCategoryTimeout() {
        Scripted a = new Scripted();
        a.listDelayMs = 20; // Deterministically exceeds 5ms timeout
        a.listing = ListingResult.complete(List.of(full("1"), full("2"), full("3")), 1, null);

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 10, 5L);

        assertThat(report.complete()).isFalse();
        assertThat(report.failureCategory()).isEqualTo("TIMEOUT");

        // Safe deletion guard: markUnlisted must NEVER be called on timeout
        verify(db, never()).update(contains("update job_source_observations set source_status = 'MISSING'"), any(Object[].class));
    }

    @Test
    void existingKnownJobsAreTouchedWithoutConsumingIngestionBudget() {
        Scripted a = new Scripted();
        // Entry 1 and 2 need detail (partial)
        a.listing = ListingResult.complete(List.of(partial("known-1"), partial("new-2")), 1, null);

        // Mark known-1 as already seen
        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE)))
                .thenReturn(List.of(Map.of("external_job_id", "known-1")));

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 1, 45000L);

        // known-1 was touched (unchanged)
        assertThat(report.counts().get("unchanged")).isEqualTo(1);
        // new-2 was fetched and created (1 ingested, meeting maxResults = 1)
        assertThat(report.counts().get("created")).isEqualTo(1);
        assertThat(report.ingested()).hasSize(1);
        assertThat(a.detailed).containsExactly("new-2");
    }

    @Test
    void theRecordedRunMetadataCarriesCountsAndNoSecrets() {
        Scripted a = new Scripted();
        a.listing = ListingResult.partial(List.of(), 0, FailureCategory.ACCESS_DENIED, "HTTP 403", null);
        service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL);
        ArgumentCaptor<String> lastRun = ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("last_run = ?::jsonb"), lastRun.capture(), anyString(), any(), eq(SOURCE));
        assertThat(lastRun.getValue()).contains("\"failure_category\":\"ACCESS_DENIED\"", "\"duration_ms\"", "\"mode\":\"MANUAL\"")
                .doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("password");
    }

    @Test
    void completeFeedWithTenKnownJobsDoesNotStarveFiveNewJobsLaterInFeed() {
        Scripted a = new Scripted();
        List<ListingEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            entries.add(full("known-" + i));
        }
        for (int i = 1; i <= 5; i++) {
            entries.add(full("new-" + i));
        }
        a.listing = ListingResult.complete(entries, 1, null);

        // Mock ingestion: "known-" jobs return TOUCHED, "new-" jobs return INSERTED
        when(ingestion.ingestJob(any())).thenAnswer(inv -> {
            JobDiscoveryService.IngestJobCommand cmd = inv.getArgument(0);
            if (cmd.externalId().startsWith("known-")) {
                return new JobDiscoveryService.IngestResult(UUID.randomUUID(), "TOUCHED", "d", "h");
            } else {
                return new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "d", "h");
            }
        });

        // maxResults = 5
        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 5, 45000L);

        // All 10 known jobs touched without consuming budget
        assertThat(report.counts().get("unchanged")).isEqualTo(10);
        // All 5 new jobs discovered and created
        assertThat(report.counts().get("created")).isEqualTo(5);
        assertThat(report.counts().get("result_count")).isEqualTo(5);
        assertThat(report.complete()).isTrue();
    }

    @Test
    void existingJobWithChangedContentIsUpdatedAndConsumesBudget() {
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(full("job-1"), full("job-2")), 1, null);

        when(ingestion.ingestJob(any())).thenAnswer(inv -> {
            JobDiscoveryService.IngestJobCommand cmd = inv.getArgument(0);
            if ("job-1".equals(cmd.externalId())) {
                return new JobDiscoveryService.IngestResult(UUID.randomUUID(), "UPDATED", "d", "h");
            } else {
                return new JobDiscoveryService.IngestResult(UUID.randomUUID(), "INSERTED", "d", "h");
            }
        });

        // maxResults = 1 -> job-1 (UPDATED) consumes the single result budget, halting before job-2
        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 1, 45000L);

        assertThat(report.counts().get("updated")).isEqualTo(1);
        assertThat(report.counts().get("created")).isEqualTo(0); // job-2 was not ingested
        assertThat(report.counts().get("result_count")).isEqualTo(1);
        assertThat(report.complete()).isFalse(); // truncated by budget
        verify(db, never()).update(contains("update job_source_observations set source_status = 'MISSING'"), any(Object[].class));
    }

    @Test
    void detailResponseArrivingAfterDeadlineDoesNotPerformIngestionWrite() {
        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE))).thenReturn(List.of());

        // Custom adapter that delays in detail() to push past deadline
        BoardAdapter lateAdapter = new BoardAdapter() {
            @Override public PlatformKind kind() { return PlatformKind.GEM; }
            @Override public Optional<BoardRef> recognize(URI url) { return Optional.empty(); }
            @Override public BoardRef parseIdentifier(String id) { return new BoardRef(kind(), id, Map.of()); }
            @Override public ListingResult list(BoardRef b, CrawlContext ctx) {
                return ListingResult.complete(List.of(partial("late-job")), 1, null);
            }
            @Override public NormalizedJob detail(BoardRef b, ListingEntry e, CrawlContext ctx) {
                try { Thread.sleep(25); } catch (InterruptedException ignored) {}
                return NormalizedJob.builder(e.externalId(), "Role").description("d")
                        .applicationUrl("https://example.com/app").build();
            }
        };

        var report = service.run(SOURCE, lateAdapter, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 10, 10L);

        assertThat(report.complete()).isFalse();
        assertThat(report.failureCategory()).isEqualTo("TIMEOUT");
        // Ingestion write was NOT performed because detail completed after deadline
        verify(ingestion, never()).ingestJob(any());
        verify(db, never()).update(contains("update job_source_observations set source_status = 'MISSING'"), any(Object[].class));
    }

    @Test
    void repeatRunsRotateScanOffsetBeyondMaxEntriesToProcessPreventingStarvation() {
        // Feed of 2,500 entries:
        // Entries 0..1999 are already known
        // Entries 2000..2499 are new postings beyond maxEntriesToProcess (2,000)
        List<Map<String, Object>> knownList = new ArrayList<>();
        List<BoardAdapter.ListingEntry> entries = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String id = "known-" + i;
            knownList.add(Map.of("external_job_id", id));
            entries.add(partial(id));
        }
        for (int i = 2000; i < 2500; i++) {
            entries.add(full("new-" + i));
        }

        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE))).thenReturn(knownList);

        java.util.concurrent.atomic.AtomicReference<String> persistedOffset =
                new java.util.concurrent.atomic.AtomicReference<>("0");
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenAnswer(inv -> List.of(Map.of("default_offset", persistedOffset.get())));
        when(db.update(contains("update job_sources set"), any(Object[].class)))
                .thenAnswer(inv -> {
                    Object[] args = extractVarargs(inv);
                    if (args.length > 0 && args[0] != null) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode root =
                                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(args[0]));
                            if (root.has("scan_offset")) {
                                persistedOffset.set(root.get("scan_offset").asText());
                            }
                        } catch (Exception ignored) {}
                    }
                    return 1;
                });

        Scripted a = new Scripted();
        a.listing = ListingResult.complete(entries, 1, null);

        // Run 1: maxResults = 50 -> maxEntriesToProcess = max(50 * 20, 2000) = 2000
        // Inspects entries 0..1999 (all known), halts at cap 2000, finds 0 new results
        var report1 = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 50, 60000L);

        assertThat(report1.counts().get("unchanged")).isEqualTo(2000);
        assertThat(report1.counts().get("created")).isEqualTo(0);
        assertThat(report1.counts().get("scan_offset")).isEqualTo(2000);
        assertThat(report1.complete()).isFalse(); // truncated by maxEntriesToProcess cap

        // Run 2: Exact same source and feed.
        // Thanks to persistent scan offset (2000), Run 2 starts inspection at index 2000!
        // Discovers new postings 2000..2049, consuming maxResults budget (50).
        var report2 = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 50, 60000L);

        assertThat(report2.counts().get("created")).isEqualTo(50);
        assertThat(report2.counts().get("scan_offset")).isEqualTo(2050);
        // Proves later postings beyond maxEntriesToProcess are NOT starved across repeat runs
    }

    @Test
    void persistentScanOffsetSurvivesDatabaseReloadViaLastRunJson() {
        // Mock DB returning persisted scan_offset = 2000 from job_sources.last_run
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenReturn(List.of(Map.of("default_offset", "2000")));

        List<BoardAdapter.ListingEntry> entries = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            entries.add(partial("job-" + i));
        }
        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE))).thenReturn(List.of());

        Scripted a = new Scripted();
        a.listing = ListingResult.complete(entries, 1, null);

        // Fresh service instance simulates server restart
        BoardDiscoveryService freshInstance = new BoardDiscoveryService(
                db, ingestion, registry, kind -> http.client(), 50, 2, 3, 10);

        var report = freshInstance.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 2, 60000L);

        // Starts at offset 2000 and ingests job-2000 and job-2001
        assertThat(a.detailed).containsExactly("job-2000", "job-2001");
        assertThat(report.counts().get("created")).isEqualTo(2);
        assertThat(report.counts().get("scan_offset")).isEqualTo(2002);
    }

    private static Object[] extractVarargs(org.mockito.invocation.InvocationOnMock inv) {
        Object[] all = inv.getArguments();
        if (all.length == 2 && all[1] instanceof Object[] varargs) {
            return varargs;
        }
        Object[] varargs = new Object[all.length - 1];
        System.arraycopy(all, 1, varargs, 0, varargs.length);
        return varargs;
    }

    @Test
    @SuppressWarnings("unchecked")
    void alternatingSearchPlansPersistIndependentScanOffsetsAcrossRestarts() throws Exception {
        UUID planA = UUID.randomUUID();
        UUID tokenA = UUID.randomUUID();
        UUID planB = UUID.randomUUID();
        UUID tokenB = UUID.randomUUID();

        com.personal.jobagent.discovery.plan.SearchPlanRepository searchPlanRepo =
                mock(com.personal.jobagent.discovery.plan.SearchPlanRepository.class);
        when(searchPlanRepo.isLeaseOwner(any(), any())).thenReturn(true);
        service.setSearchPlanRepository(searchPlanRepo);

        List<BoardAdapter.ListingEntry> entries = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            entries.add(full("job-" + i));
        }
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(entries, 1, null);
        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE))).thenReturn(List.of());

        // Simulated persistent database column job_sources.last_run JSON
        java.util.concurrent.atomic.AtomicReference<String> simulatedLastRun =
                new java.util.concurrent.atomic.AtomicReference<>("{}");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // Simulate PostgreSQL atomic JSONB update for job_sources:
        when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            Object[] args = extractVarargs(inv);
            if (sql.contains("jsonb_set") && sql.contains("plan_offsets")) {
                String planId = String.valueOf(args[0]);
                Integer offset = Integer.valueOf(String.valueOf(args[1]));
                Map<String, Object> currentLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
                Map<String, Object> planOffsets = (Map<String, Object>) currentLastRun.getOrDefault("plan_offsets", new java.util.HashMap<>());
                planOffsets.put(planId, offset);
                currentLastRun.put("plan_offsets", planOffsets);
                simulatedLastRun.set(mapper.writeValueAsString(currentLastRun));
            } else if (sql.contains("update job_sources set")) {
                String lastRunJson = String.valueOf(args[0]);
                Map<String, Object> currentLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
                Map<String, Object> newLastRun = mapper.readValue(lastRunJson, Map.class);
                if (currentLastRun.containsKey("plan_offsets")) {
                    newLastRun.put("plan_offsets", currentLastRun.get("plan_offsets"));
                }
                simulatedLastRun.set(mapper.writeValueAsString(newLastRun));
            }
            return 1;
        });

        // Simulate PostgreSQL reading per-plan offset from last_run->'plan_offsets'->>?
        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class))).thenAnswer(inv -> {
            Object[] args = extractVarargs(inv);
            String planId = String.valueOf(args[0]);
            Map<String, Object> current = mapper.readValue(simulatedLastRun.get(), Map.class);
            Map<String, Object> planOffsets = (Map<String, Object>) current.get("plan_offsets");
            if (planOffsets != null && planOffsets.containsKey(planId)) {
                return List.of(Map.of("plan_offset", String.valueOf(planOffsets.get(planId))));
            }
            return List.of(new java.util.HashMap<String, Object>());
        });

        // Run 1: Plan A scans 2 jobs (starts at 0 -> saves offset 2)
        var reportA1 = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 2, 60000L);
        assertThat(reportA1.counts().get("scan_offset")).isEqualTo(2);

        // Run 2: Plan B scans 3 jobs (starts at 0 -> saves offset 3)
        var reportB1 = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planB, tokenB, 3, 60000L);
        assertThat(reportB1.counts().get("scan_offset")).isEqualTo(3);

        // SIMULATE APPLICATION RESTART:
        // A fresh service instance has completely empty in-memory scanOffsets cache.
        BoardDiscoveryService freshInstance = new BoardDiscoveryService(
                db, ingestion, registry, kind -> http.client(), 50, 2, 3, 10);
        freshInstance.setSearchPlanRepository(searchPlanRepo);

        // Run 3: Plan A runs again on fresh instance.
        // It must NOT be overwritten by Plan B's offset (3)!
        // It must resume at its own saved offset (2) and scan 2 jobs (offset becomes 4).
        a.detailed.clear();
        var reportA2 = freshInstance.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 2, 60000L);
        assertThat(reportA2.counts().get("scan_offset")).isEqualTo(4);

        // Run 4: Plan B runs again on fresh instance.
        // It must resume at its own saved offset (3) and scan 2 jobs (offset becomes 5).
        a.detailed.clear();
        var reportB2 = freshInstance.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planB, tokenB, 2, 60000L);
        assertThat(reportB2.counts().get("scan_offset")).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentPlanOffsetUpdatesAgainstSameSourceDoNotRace() throws Exception {
        int threadCount = 8;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> simulatedLastRun =
                new java.util.concurrent.atomic.AtomicReference<>("{}");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        com.personal.jobagent.discovery.plan.SearchPlanRepository searchPlanRepo =
                mock(com.personal.jobagent.discovery.plan.SearchPlanRepository.class);
        when(searchPlanRepo.isLeaseOwner(any(), any())).thenReturn(true);
        service.setSearchPlanRepository(searchPlanRepo);

        when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            synchronized (simulatedLastRun) {
                String sql = inv.getArgument(0, String.class);
                Object[] args = extractVarargs(inv);
                if (sql.contains("jsonb_set") && sql.contains("plan_offsets")) {
                    String planId = String.valueOf(args[0]);
                    Integer offset = Integer.valueOf(String.valueOf(args[1]));
                    Map<String, Object> currentLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
                    Map<String, Object> planOffsets = (Map<String, Object>) currentLastRun.getOrDefault("plan_offsets", new java.util.HashMap<>());
                    planOffsets.put(planId, offset);
                    currentLastRun.put("plan_offsets", planOffsets);
                    simulatedLastRun.set(mapper.writeValueAsString(currentLastRun));
                } else if (sql.contains("update job_sources set")) {
                    String lastRunJson = String.valueOf(args[0]);
                    Map<String, Object> currentLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
                    Map<String, Object> newLastRun = mapper.readValue(lastRunJson, Map.class);
                    if (currentLastRun.containsKey("plan_offsets")) {
                        newLastRun.put("plan_offsets", currentLastRun.get("plan_offsets"));
                    }
                    simulatedLastRun.set(mapper.writeValueAsString(newLastRun));
                }
                return 1;
            }
        });

        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class))).thenAnswer(inv -> {
            synchronized (simulatedLastRun) {
                Object[] args = extractVarargs(inv);
                String planId = String.valueOf(args[0]);
                Map<String, Object> current = mapper.readValue(simulatedLastRun.get(), Map.class);
                Map<String, Object> planOffsets = (Map<String, Object>) current.get("plan_offsets");
                if (planOffsets != null && planOffsets.containsKey(planId)) {
                    return List.of(Map.of("plan_offset", String.valueOf(planOffsets.get(planId))));
                }
                return List.of(new java.util.HashMap<String, Object>());
            }
        });

        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE))).thenReturn(List.of());

        List<BoardAdapter.ListingEntry> entries = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entries.add(full("job-" + i));
        }
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(entries, 1, null);

        List<UUID> planIds = new ArrayList<>();
        List<UUID> ownerTokens = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            planIds.add(UUID.randomUUID());
            ownerTokens.add(UUID.randomUUID());
        }

        List<java.util.concurrent.Future<BoardDiscoveryService.SourceRunReport>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final UUID planId = planIds.get(i);
            final UUID token = ownerTokens.get(i);
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                        Map.of(), planId, token, 2, 60000L);
            }));
        }

        readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        startLatch.countDown();

        for (var future : futures) {
            BoardDiscoveryService.SourceRunReport report = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(report.ok()).isTrue();
            assertThat(report.counts().get("scan_offset")).isEqualTo(2);
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Verify that in simulated DB, all 8 plan offsets were durably recorded without being lost
        Map<String, Object> finalLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
        Map<String, Object> planOffsets = (Map<String, Object>) finalLastRun.get("plan_offsets");
        assertThat(planOffsets).hasSize(threadCount);
        for (UUID planId : planIds) {
            assertThat(planOffsets).containsKey(planId.toString());
            assertThat(planOffsets.get(planId.toString())).isEqualTo(2);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void crossInstanceOffsetConsistencyPicksUpPostgresAdvancementWithoutStaleCache() throws Exception {
        UUID planA = UUID.randomUUID();
        UUID tokenA = UUID.randomUUID();

        com.personal.jobagent.discovery.plan.SearchPlanRepository searchPlanRepo =
                mock(com.personal.jobagent.discovery.plan.SearchPlanRepository.class);
        when(searchPlanRepo.isLeaseOwner(any(), any())).thenReturn(true);

        java.util.concurrent.atomic.AtomicReference<String> simulatedLastRun =
                new java.util.concurrent.atomic.AtomicReference<>("{}");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            Object[] args = extractVarargs(inv);
            if (sql.contains("jsonb_set") && sql.contains("plan_offsets")) {
                String planId = String.valueOf(args[0]);
                Integer offset = Integer.valueOf(String.valueOf(args[1]));
                Map<String, Object> currentLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
                Map<String, Object> planOffsets = (Map<String, Object>) currentLastRun.getOrDefault("plan_offsets", new java.util.HashMap<>());
                planOffsets.put(planId, offset);
                currentLastRun.put("plan_offsets", planOffsets);
                simulatedLastRun.set(mapper.writeValueAsString(currentLastRun));
            } else if (sql.contains("update job_sources set")) {
                String lastRunJson = String.valueOf(args[0]);
                Map<String, Object> currentLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
                Map<String, Object> newLastRun = mapper.readValue(lastRunJson, Map.class);
                if (currentLastRun.containsKey("plan_offsets")) {
                    newLastRun.put("plan_offsets", currentLastRun.get("plan_offsets"));
                }
                simulatedLastRun.set(mapper.writeValueAsString(newLastRun));
            }
            return 1;
        });

        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class))).thenAnswer(inv -> {
            Object[] args = extractVarargs(inv);
            String planId = String.valueOf(args[0]);
            Map<String, Object> current = mapper.readValue(simulatedLastRun.get(), Map.class);
            Map<String, Object> planOffsets = (Map<String, Object>) current.get("plan_offsets");
            if (planOffsets != null && planOffsets.containsKey(planId)) {
                return List.of(Map.of("plan_offset", String.valueOf(planOffsets.get(planId))));
            }
            // Row exists in job_sources, but plan_offsets has no entry for this plan yet
            return List.of(new java.util.HashMap<String, Object>());
        });

        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE))).thenReturn(List.of());

        List<BoardAdapter.ListingEntry> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(full("job-" + i));
        }
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(entries, 1, null);

        // Instance A
        BoardDiscoveryService instanceA = new BoardDiscoveryService(
                db, ingestion, registry, kind -> http.client(), 50, 2, 3, 10);
        instanceA.setSearchPlanRepository(searchPlanRepo);

        // Instance B
        BoardDiscoveryService instanceB = new BoardDiscoveryService(
                db, ingestion, registry, kind -> http.client(), 50, 2, 3, 10);
        instanceB.setSearchPlanRepository(searchPlanRepo);

        // 1. Instance A reads offset (0), runs 2 jobs, and persists offset 2 to PostgreSQL.
        // Instance A now has 2 in its local memory cache.
        var reportA1 = instanceA.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 2, 60000L);
        assertThat(reportA1.counts().get("scan_offset")).isEqualTo(2);
        assertThat(instanceA.getPersistedScanOffset(SOURCE, planA)).isEqualTo(2);

        // 2. Instance B runs against the same plan and source.
        // Instance B queries PostgreSQL, reads offset 2, runs 3 jobs, and advances offset to 5 in PostgreSQL.
        var reportB1 = instanceB.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 3, 60000L);
        assertThat(reportB1.counts().get("scan_offset")).isEqualTo(5);
        assertThat(instanceB.getPersistedScanOffset(SOURCE, planA)).isEqualTo(5);

        // 3. Instance A executes AGAIN!
        // Instance A had 2 in its local cache.
        // PostgreSQL is the authoritative source: Instance A MUST NOT use stale cached 2!
        // Instance A queries PostgreSQL, reads 5, runs 2 jobs, and advances to offset 7 in PostgreSQL.
        var reportA2 = instanceA.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 2, 60000L);
        assertThat(reportA2.counts().get("scan_offset")).isEqualTo(7);
        assertThat(instanceA.getPersistedScanOffset(SOURCE, planA)).isEqualTo(7);

        // 4. Repeat after constructing fresh service instances
        BoardDiscoveryService instanceC = new BoardDiscoveryService(
                db, ingestion, registry, kind -> http.client(), 50, 2, 3, 10);
        instanceC.setSearchPlanRepository(searchPlanRepo);
        assertThat(instanceC.getPersistedScanOffset(SOURCE, planA)).isEqualTo(7);

        var reportC = instanceC.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 2, 60000L);
        assertThat(reportC.counts().get("scan_offset")).isEqualTo(9);

        BoardDiscoveryService instanceD = new BoardDiscoveryService(
                db, ingestion, registry, kind -> http.client(), 50, 2, 3, 10);
        instanceD.setSearchPlanRepository(searchPlanRepo);
        assertThat(instanceD.getPersistedScanOffset(SOURCE, planA)).isEqualTo(9);

        var reportD = instanceD.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 2, 60000L);
        assertThat(reportD.counts().get("scan_offset")).isEqualTo(11);
        assertThat(instanceD.getPersistedScanOffset(SOURCE, planA)).isEqualTo(11);
    }

    @Test
    void newlyCalculatedOffsetIsNotCachedIfPersistenceFails() {
        UUID planA = UUID.randomUUID();
        UUID tokenA = UUID.randomUUID();

        com.personal.jobagent.discovery.plan.SearchPlanRepository searchPlanRepo =
                mock(com.personal.jobagent.discovery.plan.SearchPlanRepository.class);
        when(searchPlanRepo.isLeaseOwner(any(), any())).thenReturn(true);
        service.setSearchPlanRepository(searchPlanRepo);

        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE))).thenReturn(List.of());

        // Initial offset in DB is 0
        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                .thenReturn(List.of(new java.util.HashMap<String, Object>()));

        // Simulate database persistence failure
        when(db.update(contains("update job_sources set"), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Database disk full"));

        List<BoardAdapter.ListingEntry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entries.add(full("job-" + i));
        }
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(entries, 1, null);

        // Execution fails during persistence
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                        Map.of(), planA, tokenA, 2, 60000L));

        // Offset MUST NOT have been updated in local cache (verified via reflection on in-memory map)
        try {
            java.lang.reflect.Field field = BoardDiscoveryService.class.getDeclaredField("scanOffsets");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> memoryMap = (Map<String, ?>) field.get(service);
            assertThat(memoryMap.get(planA + ":" + SOURCE)).isNotEqualTo(2);
            assertThat(memoryMap.get(planA + ":" + SOURCE)).isEqualTo(0);
        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail(e);
        }
        assertThat(service.getPersistedScanOffset(SOURCE, planA)).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void databaseReadFailureFailsClosedWithoutUsingStaleCache() throws Exception {
        UUID planA = UUID.randomUUID();
        UUID tokenA = UUID.randomUUID();

        com.personal.jobagent.discovery.plan.SearchPlanRepository searchPlanRepo =
                mock(com.personal.jobagent.discovery.plan.SearchPlanRepository.class);
        when(searchPlanRepo.isLeaseOwner(any(), any())).thenReturn(true);

        java.util.concurrent.atomic.AtomicReference<String> simulatedLastRun =
                new java.util.concurrent.atomic.AtomicReference<>("{}");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        java.util.concurrent.atomic.AtomicBoolean failDatabaseReads =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            Object[] args = extractVarargs(inv);
            if (sql.contains("jsonb_set") && sql.contains("plan_offsets")) {
                String planId = String.valueOf(args[0]);
                Integer offset = Integer.valueOf(String.valueOf(args[1]));
                Map<String, Object> currentLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
                Map<String, Object> planOffsets = (Map<String, Object>) currentLastRun.getOrDefault("plan_offsets", new java.util.HashMap<>());
                planOffsets.put(planId, offset);
                currentLastRun.put("plan_offsets", planOffsets);
                simulatedLastRun.set(mapper.writeValueAsString(currentLastRun));
            } else if (sql.contains("update job_sources set")) {
                String lastRunJson = String.valueOf(args[0]);
                Map<String, Object> currentLastRun = mapper.readValue(simulatedLastRun.get(), Map.class);
                Map<String, Object> newLastRun = mapper.readValue(lastRunJson, Map.class);
                if (currentLastRun.containsKey("plan_offsets")) {
                    newLastRun.put("plan_offsets", currentLastRun.get("plan_offsets"));
                }
                simulatedLastRun.set(mapper.writeValueAsString(newLastRun));
            }
            return 1;
        });

        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class))).thenAnswer(inv -> {
            if (failDatabaseReads.get()) {
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException("PostgreSQL connection refused");
            }
            Object[] args = extractVarargs(inv);
            String planId = String.valueOf(args[0]);
            Map<String, Object> current = mapper.readValue(simulatedLastRun.get(), Map.class);
            Map<String, Object> planOffsets = (Map<String, Object>) current.get("plan_offsets");
            if (planOffsets != null && planOffsets.containsKey(planId)) {
                return List.of(Map.of("plan_offset", String.valueOf(planOffsets.get(planId))));
            }
            return List.of(new java.util.HashMap<String, Object>());
        });

        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE))).thenAnswer(inv -> {
            if (failDatabaseReads.get()) {
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException("PostgreSQL connection refused");
            }
            return List.of(new java.util.HashMap<String, Object>());
        });

        when(db.queryForList(contains("from job_source_observations"), eq(SOURCE))).thenReturn(List.of());

        List<BoardAdapter.ListingEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(full("job-" + i));
        }
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(entries, 1, null);

        // Instance A
        BoardDiscoveryService instanceA = new BoardDiscoveryService(
                db, ingestion, registry, kind -> http.client(), 50, 2, 3, 10);
        instanceA.setSearchPlanRepository(searchPlanRepo);

        // Instance B
        BoardDiscoveryService instanceB = new BoardDiscoveryService(
                db, ingestion, registry, kind -> http.client(), 50, 2, 3, 10);
        instanceB.setSearchPlanRepository(searchPlanRepo);

        // 1. Instance A reads offset (starts at 0), runs 2 jobs, and persists offset 2 to PostgreSQL.
        var reportA1 = instanceA.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 2, 60000L);
        assertThat(reportA1.counts().get("scan_offset")).isEqualTo(2);

        // 2. Instance A now has 2 cached locally in its in-memory scanOffsets map.
        // Reading it through getPersistedScanOffset returns 2 while DB is healthy.
        assertThat(instanceA.getPersistedScanOffset(SOURCE, planA)).isEqualTo(2);

        // 3. Instance B runs against the same plan and source, reads offset 2, runs 3 jobs,
        // and advances the persisted database offset to 5 in PostgreSQL.
        var reportB1 = instanceB.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 3, 60000L);
        assertThat(reportB1.counts().get("scan_offset")).isEqualTo(5);
        assertThat(instanceB.getPersistedScanOffset(SOURCE, planA)).isEqualTo(5);

        // Instance A still has stale offset 2 cached locally in memory!
        // Simulated PostgreSQL currently holds offset 5.

        // 4. PostgreSQL now fails with a connection exception on next read.
        failDatabaseReads.set(true);

        // 5, 6, 7. Instance A attempts another read of the scan offset:
        // Because PostgreSQL is authoritative, Instance A MUST query PostgreSQL.
        // When the query throws CannotGetJdbcConnectionException, it MUST NOT fall back
        // to its local cached offset of 2. It must fail closed and throw IllegalStateException!
        assertThatThrownBy(() -> instanceA.getPersistedScanOffset(SOURCE, planA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database query failure; failing closed")
                .hasCauseInstanceOf(org.springframework.jdbc.CannotGetJdbcConnectionException.class);

        // 8. Instance A attempts another execution:
        // run() must fail immediately when attempting to read the initial scan offset.
        org.mockito.Mockito.clearInvocations(ingestion);
        org.mockito.Mockito.clearInvocations(db);
        a.detailed.clear();

        assertThatThrownBy(() -> instanceA.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planA, tokenA, 2, 60000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database query failure; failing closed")
                .hasCauseInstanceOf(org.springframework.jdbc.CannotGetJdbcConnectionException.class);

        // Verify that NO jobs were detailed or ingested, and NO database update/write occurred
        assertThat(a.detailed).isEmpty();
        verify(ingestion, never()).ingestJob(any());
        verify(ingestion, never()).ingestJob(any(), any(), any());
        verify(db, never()).update(anyString(), any(Object[].class));

        // Verify the persisted database state still holds offset 5 (unchanged, never overwritten)
        failDatabaseReads.set(false);
        assertThat(instanceB.getPersistedScanOffset(SOURCE, planA)).isEqualTo(5);

        // Also verify default offset (null searchPlanId) read failure fails closed
        failDatabaseReads.set(true);
        assertThatThrownBy(() -> instanceA.getPersistedScanOffset(SOURCE, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database query failure; failing closed")
                .hasCauseInstanceOf(org.springframework.jdbc.CannotGetJdbcConnectionException.class);
    }

    @Test
    void missingSourceRowFailsExplicitlyWithoutReturningCachedOffsetOrIngesting() {
        UUID unknownSource = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        // Database returns empty list (source row does not exist in job_sources)
        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                .thenReturn(List.of());
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(unknownSource)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getPersistedScanOffset(unknownSource, planId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist in database; cannot determine authoritative scan offset (failing closed)");

        assertThatThrownBy(() -> service.getPersistedScanOffset(unknownSource, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist in database; cannot determine authoritative scan offset (failing closed)");

        // Also verify run() fails explicitly before any ingestion or detail calls
        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(full("job-1")), 1, null);
        assertThatThrownBy(() -> service.run(unknownSource, a, "acme", null, BoardDiscoveryService.Mode.MANUAL, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist in database");
        assertThat(a.detailed).isEmpty();
        verify(ingestion, never()).ingestJob(any());
    }

    @Test
    void malformedPersistedScanOffsetFailsClosed() {
        UUID planId = UUID.randomUUID();

        // 1. Malformed plan-specific offset
        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                .thenReturn(List.of(Map.of("plan_offset", "not-a-number")));

        assertThatThrownBy(() -> service.getPersistedScanOffset(SOURCE, planId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a valid integer (failing closed)")
                .hasCauseInstanceOf(NumberFormatException.class);

        // 2. Malformed default scan offset
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenReturn(List.of(Map.of("default_offset", "corrupted-json-val")));

        assertThatThrownBy(() -> service.getPersistedScanOffset(SOURCE, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a valid integer (failing closed)")
                .hasCauseInstanceOf(NumberFormatException.class);

        // 3. Negative offset fails closed
        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                .thenReturn(List.of(Map.of("plan_offset", "-1")));

        assertThatThrownBy(() -> service.getPersistedScanOffset(SOURCE, planId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Negative persisted scan offset")
                .hasMessageContaining("is invalid (failing closed)");

        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenReturn(List.of(Map.of("default_offset", "-5")));

        assertThatThrownBy(() -> service.getPersistedScanOffset(SOURCE, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Negative persisted scan offset")
                .hasMessageContaining("is invalid (failing closed)");
    }

    @Test
    void legacyOffsetKeyCompatibilityIsPreservedWhenNestedPlanOffsetsAbsent() {
        UUID planId = UUID.randomUUID();
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("plan_offset", null);
        row.put("legacy_plan_offset", "42");

        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                .thenReturn(List.of(row));

        int offset = service.getPersistedScanOffset(SOURCE, planId);
        assertThat(offset).isEqualTo(42);
    }

    @Test
    void planOffsetTakesPrecedenceOverLegacyPlanOffset() {
        UUID planId = UUID.randomUUID();
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("plan_offset", "10");
        row.put("legacy_plan_offset", "42");

        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                .thenReturn(List.of(row));

        int offset = service.getPersistedScanOffset(SOURCE, planId);
        assertThat(offset).isEqualTo(10);
    }

    @Test
    void nullSourceIdThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> service.getPersistedScanOffset(null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceId must not be null");
    }

    @Test
    void upstream500ListingFailurePreservesGlobalScanOffset() {
        Map<String, Object> sourceRow = new java.util.HashMap<>();
        sourceRow.put("default_offset", "42");
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenReturn(List.of(sourceRow));

        Scripted a = new Scripted();
        a.listing = ListingResult.failed(FailureCategory.UPSTREAM_ERROR, "HTTP 500 Internal Server Error");

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.SCHEDULED);

        assertThat(report.ok()).isFalse();
        assertThat(report.counts().get("scan_offset")).isEqualTo(42);
        verify(db).update(contains("failure_streak = failure_streak + 1"), contains("\"scan_offset\":42"), anyString(), any(), eq(SOURCE));
    }

    @Test
    void upstreamRateLimitedListingFailurePreservesGlobalScanOffset() {
        Map<String, Object> sourceRow = new java.util.HashMap<>();
        sourceRow.put("default_offset", "15");
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenReturn(List.of(sourceRow));

        Scripted a = new Scripted();
        a.listing = ListingResult.failed(FailureCategory.RATE_LIMITED, "HTTP 429 Too Many Requests");

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.SCHEDULED);

        assertThat(report.ok()).isFalse();
        assertThat(report.counts().get("scan_offset")).isEqualTo(15);
        verify(db).update(contains("failure_streak = failure_streak + 1"), contains("\"scan_offset\":15"), anyString(), any(), eq(SOURCE));
    }

    @Test
    void upstreamListingFailurePreservesPlanSpecificScanOffset() {
        UUID planId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        com.personal.jobagent.discovery.plan.SearchPlanRepository searchPlanRepo =
                mock(com.personal.jobagent.discovery.plan.SearchPlanRepository.class);
        when(searchPlanRepo.isLeaseOwner(planId, token)).thenReturn(true);
        service.setSearchPlanRepository(searchPlanRepo);

        Map<String, Object> planRow = new java.util.HashMap<>();
        planRow.put("plan_offset", "88");
        when(db.queryForList(contains("last_run->'plan_offsets'"), any(Object[].class)))
                .thenReturn(List.of(planRow));

        Scripted a = new Scripted();
        a.listing = ListingResult.failed(FailureCategory.UPSTREAM_ERROR, "HTTP 500");

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), planId, token, 10, 60000L);

        assertThat(report.ok()).isFalse();
        assertThat(report.counts().get("scan_offset")).isEqualTo(88);
        verify(db).update(contains("jsonb_set"), eq(planId.toString()), eq(88), eq(SOURCE));
    }

    @Test
    void successfulEmptyListingSetsScanOffsetToZero() {
        Map<String, Object> sourceRow = new java.util.HashMap<>();
        sourceRow.put("default_offset", "25");
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenReturn(List.of(sourceRow));

        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(), 1, null);

        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.SCHEDULED);

        assertThat(report.ok()).isTrue();
        assertThat(report.counts().get("scan_offset")).isEqualTo(0);
    }

    @Test
    void successfulListingAdvancesScanOffsetNormally() {
        Map<String, Object> sourceRow = new java.util.HashMap<>();
        sourceRow.put("default_offset", "2");
        when(db.queryForList(contains("select last_run->>'scan_offset'"), eq(SOURCE)))
                .thenReturn(List.of(sourceRow));

        Scripted a = new Scripted();
        a.listing = ListingResult.complete(List.of(full("1"), full("2"), full("3"), full("4"), full("5")), 1, null);

        // Process with maxResults = 2, so 2 entries are processed starting at offset 2 (entries 2 and 3)
        // nextOffset = (2 + 2) % 5 = 4
        var report = service.run(SOURCE, a, "acme", null, BoardDiscoveryService.Mode.MANUAL,
                Map.of(), null, null, 2, 60000L);

        assertThat(report.ok()).isTrue();
        assertThat(report.counts().get("scan_offset")).isEqualTo(4);
    }
}
