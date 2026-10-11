package com.personal.jobagent.discovery;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.discovery.platform.PlatformRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduled sweep must only run sources whose cron is actually due and
 * whose rate limit allows it, and must hand every due source of a supported
 * platform to the single run path ({@link BoardDiscoveryService}), which
 * records the outcome into job_sources health (covered by
 * BoardDiscoveryServiceTest) — a silently failing source must become visible.
 *
 * <p>Phase 8.2.5 rewrite: the scheduler used to call Greenhouse/Ashby-specific
 * orchestrator methods and write health itself; both responsibilities moved to
 * BoardDiscoveryService so all 22 adapters share one path. Every behaviour
 * previously asserted here is still asserted, against the new collaborator.
 */
class DiscoverySchedulerTest {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000cc30");

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final BoardDiscoveryService boards = mock(BoardDiscoveryService.class);
    private final PlatformRegistry registry = new PlatformRegistry(40);
    private DiscoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        reset(db, boards);
        scheduler = new DiscoveryScheduler(db, boards, registry, 10, true,
                new AutomationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private void dueSource(String kind, String cron, Integer rateLimitPerMin, Instant lastRunAt) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", SOURCE);
        row.put("kind", kind);
        row.put("org_identifier", "acme");
        row.put("schedule_cron", cron);
        row.put("rate_limit_per_min", rateLimitPerMin);
        row.put("last_run_at", lastRunAt == null ? null : Timestamp.from(lastRunAt));
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
    }

    private static BoardDiscoveryService.SourceRunReport report(String failure) {
        return new BoardDiscoveryService.SourceRunReport("corr", SOURCE, "GREENHOUSE", failure == null ? "ok" : "failed",
                failure == null, Map.of(), failure, null, List.of(), failure == null ? List.of() : List.of("greenhouse:" + failure));
    }

    @Test
    void aDueSourceIsHandedToTheSingleRunPath() {
        dueSource("GREENHOUSE", "0 * * * * *", null, Instant.now().minusSeconds(7200));
        when(boards.run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED)).thenReturn(report(null));

        scheduler.runDueSources();

        verify(boards).run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED);
    }

    @Test
    void theSweepSelectsEverySupportedPlatformKindIncludingTsenta() {
        dueSource("WORKDAY", "0 * * * * *", null, Instant.now().minusSeconds(7200));
        when(boards.run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED)).thenReturn(report(null));

        scheduler.runDueSources();

        ArgumentCaptor<Object[]> kinds = ArgumentCaptor.forClass(Object[].class);
        verify(db).queryForList(contains("kind in ("), kinds.capture());
        assertThat(kinds.getValue()).contains("GREENHOUSE", "ASHBY", "WORKDAY", "LEVER", "TSENTA")
                .doesNotContain("BRASSRING", "DOVER", "SEARCH_API");
        verify(boards).run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED);
    }

    @Test
    void aFailedRunIsNotSwallowedOrRewrittenByTheScheduler() {
        dueSource("GREENHOUSE", "0 * * * * *", null, Instant.now().minusSeconds(7200));
        when(boards.run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED)).thenReturn(report("UPSTREAM_ERROR"));

        scheduler.runDueSources();

        verify(boards).run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED);
        // The run service already recorded the failure; the scheduler must not reset it.
        verify(db, never()).update(contains("failure_streak = 0"), any(Object[].class));
    }

    @Test
    void anExceptionFromARunIsRecordedAsAFailureAndDoesNotStopTheSweep() {
        dueSource("GREENHOUSE", "0 * * * * *", null, Instant.now().minusSeconds(7200));
        when(boards.run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED)).thenThrow(new IllegalStateException("boom"));

        scheduler.runDueSources();

        verify(db).update(contains("failure_streak = failure_streak + 1"), eq("IllegalStateException"), eq(10), eq(SOURCE));
    }

    @Test
    void aMalformedCronIsRecordedAndNeverDispatched() {
        dueSource("GREENHOUSE", "not-a-cron", null, Instant.now().minusSeconds(7200));

        scheduler.runDueSources();

        verify(boards, never()).run(any(), any());
        ArgumentCaptor<String> health = ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("set health"), health.capture(), eq(SOURCE));
        assertThat(health.getValue()).contains("MALFORMED_SCHEDULE_CRON");
    }

    @Test
    void aRateLimitedSourceIsSkippedWithoutDispatch() {
        // Every-second cron (always due), once-per-minute rate limit, last run
        // 5 seconds ago → the rate limit is what stops the dispatch.
        dueSource("GREENHOUSE", "* * * * * *", 1, Instant.now().minusSeconds(5));

        scheduler.runDueSources();

        verify(boards, never()).run(any(), any());
        verify(db, never()).update(contains("job_sources"), any(Object[].class));
    }

    @Test
    void anAshbySourceIsDispatchedThroughTheSamePath() {
        dueSource("ASHBY", "0 * * * * *", null, Instant.now().minusSeconds(7200));
        when(boards.run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED)).thenReturn(report(null));

        scheduler.runDueSources();

        verify(boards).run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED);
    }

    @Test
    void aSourceDisabledMidSweepIsSkippedQuietly() {
        dueSource("GREENHOUSE", "0 * * * * *", null, Instant.now().minusSeconds(7200));
        when(boards.run(SOURCE, BoardDiscoveryService.Mode.SCHEDULED))
                .thenThrow(new BoardDiscoveryService.RunRefused("Discovery source is disabled", false));

        scheduler.runDueSources();

        verify(db, never()).update(contains("failure_streak"), any(Object[].class));
    }
}
