package com.personal.jobagent.discovery;

import com.personal.jobagent.common.AutomationMetrics;
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
 * whose rate limit allows it, dispatch by kind, and record the outcome into
 * job_sources health — a silently failing source must become visible.
 */
class DiscoverySchedulerTest {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000cc30");

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final DiscoveryOrchestrator orchestrator = mock(DiscoveryOrchestrator.class);
    private DiscoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        reset(db, orchestrator);
        scheduler = new DiscoveryScheduler(db, orchestrator, 10, new AutomationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private void dueSource(String cron, Integer rateLimitPerMin, Instant lastRunAt) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", SOURCE);
        row.put("kind", "GREENHOUSE");
        row.put("org_identifier", "acme");
        row.put("schedule_cron", cron);
        row.put("rate_limit_per_min", rateLimitPerMin);
        row.put("last_run_at", lastRunAt == null ? null : Timestamp.from(lastRunAt));
        when(db.queryForList(anyString())).thenReturn(List.of(row));
        // The due check uses CronExpression.next(last) — a past-start cron is
        // always due, a far-future one never is.
        if (lastRunAt != null && lastRunAt.isAfter(Instant.now().plusSeconds(3600))) {
            // not applicable in tests
        }
    }

    @Test
    void aDueSourceIsDispatchedByKindAndMarkedHealthy() {
        dueSource("0 * * * * *", null, Instant.now().minusSeconds(7200));
        when(orchestrator.discoverGreenhouseBoard(SOURCE, "acme")).thenReturn(
                new DiscoveryOrchestrator.DiscoveryRun("corr", List.of(), false, List.of()));

        scheduler.runDueSources();

        verify(orchestrator).discoverGreenhouseBoard(SOURCE, "acme");
        ArgumentCaptor<String> health = ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("failure_streak = 0"), health.capture(), eq(SOURCE));
        assertThat(health.getValue()).contains("\"status\":\"ok\"");
    }

    @Test
    void aProviderFailureIsRecordedAndIncrementsTheFailureStreak() {
        dueSource("0 * * * * *", null, Instant.now().minusSeconds(7200));
        when(orchestrator.discoverGreenhouseBoard(SOURCE, "acme")).thenReturn(
                new DiscoveryOrchestrator.DiscoveryRun("corr", List.of(), false, List.of("greenhouse:UPSTREAM_503")));

        scheduler.runDueSources();

        verify(db).update(contains("failure_streak = failure_streak + 1"), eq("greenhouse:UPSTREAM_503"), eq(10), eq(SOURCE));
    }

    @Test
    void aMalformedCronIsRecordedAndNeverDispatched() {
        dueSource("not-a-cron", null, Instant.now().minusSeconds(7200));

        scheduler.runDueSources();

        verify(orchestrator, never()).discoverGreenhouseBoard(any(), any());
        verify(orchestrator, never()).discoverAshbyBoard(any(), any());
        ArgumentCaptor<String> health = ArgumentCaptor.forClass(String.class);
        verify(db).update(contains("set health"), health.capture(), eq(SOURCE));
        assertThat(health.getValue()).contains("MALFORMED_SCHEDULE_CRON");
    }

    @Test
    void aRateLimitedSourceIsSkippedWithoutDispatch() {
        // Every-second cron (always due), once-per-minute rate limit, last run
        // 5 seconds ago → the rate limit is what stops the dispatch.
        dueSource("* * * * * *", 1, Instant.now().minusSeconds(5));

        scheduler.runDueSources();

        verify(orchestrator, never()).discoverGreenhouseBoard(any(), any());
        verify(db, never()).update(contains("job_sources"), any(Object[].class));
    }

    @Test
    void anAshbySourceIsDispatchedToTheAshbyBoardApi() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", SOURCE);
        row.put("kind", "ASHBY");
        row.put("org_identifier", "acme");
        row.put("schedule_cron", "0 * * * * *");
        row.put("rate_limit_per_min", null);
        row.put("last_run_at", Timestamp.from(Instant.now().minusSeconds(7200)));
        when(db.queryForList(anyString())).thenReturn(List.of(row));
        when(orchestrator.discoverAshbyBoard(SOURCE, "acme")).thenReturn(
                new DiscoveryOrchestrator.DiscoveryRun("corr", List.of(), false, List.of()));

        scheduler.runDueSources();

        verify(orchestrator).discoverAshbyBoard(SOURCE, "acme");
        verify(orchestrator, never()).discoverGreenhouseBoard(any(), any());
    }
}
