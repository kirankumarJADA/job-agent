package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for the Phase 9 preference-driven, multi-source orchestrator.
 * {@link BoardDiscoveryService} itself is mocked throughout — its own
 * extensive behaviour (adapters, ingestion, dedup) is already covered by
 * {@code BoardDiscoveryServiceTest} and the various live/fixture adapter
 * tests; this class only proves selection, concurrency, partial-failure
 * tolerance, timeout handling and quota enforcement around that existing,
 * reused unit of work.
 */
class PreferredSourcesDiscoveryServiceTest {

    private JdbcTemplate db;
    private BoardDiscoveryService boards;
    private QuotaService quotas;
    private PreferenceSetRepository preferences;
    private PreferredSourcesDiscoveryService service;

    private static final UUID PROFILE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        boards = mock(BoardDiscoveryService.class);
        quotas = mock(QuotaService.class);
        preferences = mock(PreferenceSetRepository.class);
        service = new PreferredSourcesDiscoveryService(db, boards, quotas, preferences, 6, 2000);
        when(quotas.tryReserve(eq(PROFILE), eq("discovery_daily"), eq(1.0)))
                .thenReturn(new QuotaService.ReservationResult(true, 1, 200, LocalDate.now()));
    }

    private PreferenceSetRecord preferenceSetWithCountries(List<String> countries) {
        return new PreferenceSetRecord(UUID.randomUUID(), PROFILE, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, "SHOW_ALL", List.of(), List.of(), "ASSISTED",
                Map.of(), countries == null ? Map.of() : Map.of("countries", countries), true);
    }

    @Test
    void regionsDefaultToGbWhenTheCandidateHasNeverSetAny() {
        when(preferences.findActiveByProfileId(PROFILE)).thenReturn(Optional.of(preferenceSetWithCountries(null)));
        assertThat(service.regionsFor(PROFILE)).containsExactly("GB");
    }

    @Test
    void regionsDefaultToGbWhenNoPreferenceSetExistsAtAll() {
        when(preferences.findActiveByProfileId(PROFILE)).thenReturn(Optional.empty());
        assertThat(service.regionsFor(PROFILE)).containsExactly("GB");
    }

    @Test
    void regionsReflectTheCandidatesOwnConfiguredCountries() {
        when(preferences.findActiveByProfileId(PROFILE)).thenReturn(Optional.of(preferenceSetWithCountries(List.of("gb", "de"))));
        assertThat(service.regionsFor(PROFILE)).containsExactlyInAnyOrder("GB", "DE");
    }

    @Test
    void selectionAlwaysIncludesGlobalSourcesRegardlessOfRegion() {
        UUID globalSource = UUID.randomUUID();
        when(db.queryForList(anyString())).thenReturn(List.of(Map.of("id", globalSource, "region", "GLOBAL")));
        var selected = service.selectSources(Set.of("FR"));
        assertThat(selected).extracting(PreferredSourcesDiscoveryService.SourceSelection::id).containsExactly(globalSource);
    }

    @Test
    void selectionIncludesARegionScopedSourceOnlyWhenItMatchesACandidateRegion() {
        UUID gbSource = UUID.randomUUID();
        UUID deSource = UUID.randomUUID();
        when(db.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", gbSource, "region", "GB"), Map.of("id", deSource, "region", "DE")));
        var selected = service.selectSources(Set.of("GB"));
        assertThat(selected).extracting(PreferredSourcesDiscoveryService.SourceSelection::id).containsExactly(gbSource);
    }

    @Test
    void aQuotaExceededProfileRunsNoSourcesAtAll() {
        when(quotas.tryReserve(eq(PROFILE), eq("discovery_daily"), eq(1.0)))
                .thenReturn(new QuotaService.ReservationResult(false, 200, 200, LocalDate.now()));

        var summary = service.runForProfile(PROFILE);

        assertThat(summary.quotaExceeded()).isTrue();
        assertThat(summary.sourcesQueried()).isZero();
        verifyNoInteractions(boards);
        verify(db, never()).queryForList(anyString());
    }

    @Test
    void noConfiguredSourcesForTheCandidatesRegionIsReportedHonestlyNotAsAFailure() {
        when(preferences.findActiveByProfileId(PROFILE)).thenReturn(Optional.of(preferenceSetWithCountries(List.of("zz"))));
        when(db.queryForList(anyString())).thenReturn(List.of());

        var summary = service.runForProfile(PROFILE);

        assertThat(summary.quotaExceeded()).isFalse();
        assertThat(summary.sourcesQueried()).isZero();
        assertThat(summary.notes()).anyMatch(n -> n.contains("No discovery sources"));
        verifyNoInteractions(boards);
    }

    @Test
    void sourcesRunConcurrentlyAndAPartialFailureNeverAbortsTheOthers() {
        UUID ok1 = UUID.randomUUID(), ok2 = UUID.randomUUID(), failing = UUID.randomUUID(), configRequired = UUID.randomUUID();
        when(preferences.findActiveByProfileId(PROFILE)).thenReturn(Optional.of(preferenceSetWithCountries(List.of("gb"))));
        when(db.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", ok1, "region", "GLOBAL"), Map.of("id", ok2, "region", "GB"),
                Map.of("id", failing, "region", "GLOBAL"), Map.of("id", configRequired, "region", "GLOBAL")));

        AtomicInteger concurrentCalls = new AtomicInteger();
        AtomicInteger maxObservedConcurrency = new AtomicInteger();
        when(boards.run(any(UUID.class), eq(BoardDiscoveryService.Mode.MANUAL))).thenAnswer(invocation -> {
            int now = concurrentCalls.incrementAndGet();
            maxObservedConcurrency.updateAndGet(prev -> Math.max(prev, now));
            try {
                Thread.sleep(150); // long enough that sequential execution would be externally observable
                UUID sourceId = invocation.getArgument(0);
                if (sourceId.equals(failing)) {
                    return report(sourceId, false, "UPSTREAM_ERROR", 5, 0, 0);
                }
                if (sourceId.equals(configRequired)) {
                    return report(sourceId, false, "CONFIGURATION", 0, 0, 0);
                }
                return report(sourceId, true, null, 10, 3, 1);
            } finally {
                concurrentCalls.decrementAndGet();
            }
        });

        long start = System.nanoTime();
        var summary = service.runForProfile(PROFILE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(summary.sourcesQueried()).isEqualTo(4);
        assertThat(summary.sourcesSucceeded()).isEqualTo(2);
        assertThat(summary.sourcesFailed()).isEqualTo(1);
        assertThat(summary.sourcesRequireConfiguration()).isEqualTo(1);
        assertThat(summary.newJobs()).isEqualTo(6); // 3 + 3 from the two successful sources
        assertThat(summary.notes()).anyMatch(n -> n.contains("not fully configured"));
        assertThat(summary.notes()).anyMatch(n -> n.contains("could not be reached"));
        // Four sources each sleeping 150ms: a sequential run would take >=600ms;
        // a genuinely concurrent run completes in well under that.
        assertThat(elapsedMs).describedAs("four sources appear to have run sequentially, not concurrently").isLessThan(500);
        assertThat(maxObservedConcurrency.get()).describedAs("at least two sources must have been in flight at once").isGreaterThan(1);
    }

    @Test
    void aHangingSourceTimesOutWithoutBlockingTheRestOfTheBatch() {
        UUID hanging = UUID.randomUUID(), fast = UUID.randomUUID();
        when(preferences.findActiveByProfileId(PROFILE)).thenReturn(Optional.of(preferenceSetWithCountries(null)));
        when(db.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", hanging, "region", "GLOBAL"), Map.of("id", fast, "region", "GLOBAL")));

        PreferredSourcesDiscoveryService shortTimeoutService =
                new PreferredSourcesDiscoveryService(db, boards, quotas, preferences, 6, 100);
        when(boards.run(eq(hanging), eq(BoardDiscoveryService.Mode.MANUAL))).thenAnswer(invocation -> {
            Thread.sleep(5000);
            return report(hanging, true, null, 1, 1, 0);
        });
        when(boards.run(eq(fast), eq(BoardDiscoveryService.Mode.MANUAL)))
                .thenReturn(report(fast, true, null, 2, 1, 0));

        long start = System.nanoTime();
        var summary = shortTimeoutService.runForProfile(PROFILE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(summary.sourcesQueried()).isEqualTo(2);
        assertThat(summary.sourcesSucceeded()).isEqualTo(1);
        assertThat(summary.sourcesFailed()).isEqualTo(1);
        assertThat(elapsedMs).describedAs("the batch must not wait for the hanging source").isLessThan(4000);
    }

    private static BoardDiscoveryService.SourceRunReport report(UUID sourceId, boolean ok, String failureCategory,
                                                                int listed, int created, int updated) {
        Map<String, Object> counts = new java.util.LinkedHashMap<>();
        counts.put("listed", listed);
        counts.put("created", created);
        counts.put("updated", updated);
        return new BoardDiscoveryService.SourceRunReport(UUID.randomUUID().toString(), sourceId, "ARBEITNOW",
                ok ? "ok" : "failed", false, counts, failureCategory, failureCategory == null ? null : "detail",
                new ArrayList<>(), ok ? List.of() : List.of(failureCategory));
    }
}
