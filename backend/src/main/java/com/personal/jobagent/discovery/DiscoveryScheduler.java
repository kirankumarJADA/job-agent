package com.personal.jobagent.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs enabled board sources on their declared cron schedule. Before Phase 5
 * the {@code job_sources.schedule_cron} column existed but nothing ever read
 * it — discovery only happened when someone called {@code POST
 * /discovery/run} by hand.
 *
 * <p>Scheduling contract: {@code schedule_cron} is a standard 5-field cron in
 * the server's timezone; only GREENHOUSE and ASHBY sources with an
 * {@code org_identifier} are schedulable today (the only kinds with a
 * deterministic board API). A malformed cron is skipped with a warning and
 * recorded in the source's health JSON — never fatal to the sweep.
 *
 * <p>Reliability model: the orchestrator already isolates provider failures
 * per run, and ingestion is idempotent (dedup key + (source_id, external_id)
 * unique arbiter), so the next scheduled run IS the retry — with the cron
 * interval as the natural backoff. Consecutive failures are recorded in
 * {@code failure_streak} and surfaced in {@code health}; nothing is silently
 * auto-disabled (that stays a human decision), but the health JSON marks a
 * source with an open circuit once the streak passes the threshold.
 */
@Component
public class DiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryScheduler.class);

    private final JdbcTemplate db;
    private final DiscoveryOrchestrator orchestrator;
    private final int circuitOpenAfterFailures;

    public DiscoveryScheduler(JdbcTemplate db, DiscoveryOrchestrator orchestrator,
                              @Value("${app.discovery.circuit-open-after-failures:10}") int circuitOpenAfterFailures) {
        this.db = db;
        this.orchestrator = orchestrator;
        this.circuitOpenAfterFailures = circuitOpenAfterFailures;
    }

    private record SchedulableSource(UUID id, String kind, String orgIdentifier,
                                     String scheduleCron, Integer rateLimitPerMin, Instant lastRunAt) {}

    @Scheduled(fixedDelayString = "${app.discovery.schedule-sweep-interval-ms:60000}")
    public void runDueSources() {
        List<Map<String, Object>> rows = db.queryForList("""
                select id, kind, org_identifier, schedule_cron, rate_limit_per_min, last_run_at
                from job_sources
                where enabled = true and policy <> 'DISABLED'
                  and kind in ('GREENHOUSE','ASHBY')
                  and coalesce(org_identifier, '') <> ''
                  and coalesce(schedule_cron, '') <> ''
                """);
        for (Map<String, Object> row : rows) {
            SchedulableSource source = new SchedulableSource(
                    (UUID) row.get("id"),
                    (String) row.get("kind"),
                    (String) row.get("org_identifier"),
                    (String) row.get("schedule_cron"),
                    (Integer) row.get("rate_limit_per_min"),
                    row.get("last_run_at") == null ? null : ((java.sql.Timestamp) row.get("last_run_at")).toInstant());
            try {
                if (!due(source)) continue;
                if (rateLimited(source)) continue;
                run(source);
            } catch (Exception e) {
                // One broken source must never starve the others in the sweep.
                log.warn("Scheduled discovery for source {} failed: {}", source.id(), e.getMessage());
                recordFailure(source, e.getClass().getSimpleName());
            }
        }
    }

    private boolean due(SchedulableSource source) {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime last = source.lastRunAt() == null
                    ? ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault())
                    : source.lastRunAt().atZone(ZoneId.systemDefault());
            ZonedDateTime next = org.springframework.scheduling.support.CronExpression
                    .parse(source.scheduleCron().trim()).next(last);
            boolean isDue = next != null && !next.isAfter(now);
            if (!isDue) {
                log.debug("Source {} not due yet (cron {})", source.id(), source.scheduleCron());
            }
            return isDue;
        } catch (IllegalArgumentException e) {
            log.warn("Source {} has a malformed schedule_cron '{}': {}",
                    source.id(), source.scheduleCron(), e.getMessage());
            db.update("""
                    update job_sources set health = ?::jsonb where id = ?
                    """, healthJson("failed", "MALFORMED_SCHEDULE_CRON", null), source.id());
            return false;
        }
    }

    private boolean rateLimited(SchedulableSource source) {
        Integer perMinute = source.rateLimitPerMin();
        if (perMinute == null || perMinute <= 0 || source.lastRunAt() == null) return false;
        long minimumIntervalMs = 60_000L / perMinute;
        boolean limited = source.lastRunAt().plusMillis(minimumIntervalMs).isAfter(Instant.now());
        if (limited) {
            log.debug("Source {} skipped: rate limit {} run(s)/minute", source.id(), perMinute);
        }
        return limited;
    }

    private void run(SchedulableSource source) {
        DiscoveryOrchestrator.DiscoveryRun run = "ASHBY".equals(source.kind())
                ? orchestrator.discoverAshbyBoard(source.id(), source.orgIdentifier())
                : orchestrator.discoverGreenhouseBoard(source.id(), source.orgIdentifier());
        if (run.errors() == null || run.errors().isEmpty()) {
            log.info("Scheduled discovery for source {} ({}) ingested {} job(s)",
                    source.id(), source.kind(), run.ingested().size());
            db.update("""
                    update job_sources set last_run_at = now(), failure_streak = 0, health = ?::jsonb
                    where id = ?
                    """, healthJson("ok", null, run.ingested().size() + " job(s) this run"), source.id());
        } else {
            log.warn("Scheduled discovery for source {} ({}) failed: {}",
                    source.id(), source.kind(), run.errors());
            recordFailure(source, String.join(",", run.errors()));
        }
    }

    private void recordFailure(SchedulableSource source, String error) {
        db.update("""
                update job_sources
                set last_run_at = now(), failure_streak = failure_streak + 1,
                    health = jsonb_build_object(
                        'status', 'failed',
                        'checked_at', to_char(now() at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                        'error', ?,
                        'failure_streak', failure_streak + 1,
                        'circuit', case when failure_streak + 1 >= ? then 'open' else 'closed' end)
                where id = ?
                """, error, circuitOpenAfterFailures, source.id());
    }

    private String healthJson(String status, String error, String detail) {
        String checkedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String escapedDetail = detail == null ? "" : detail.replace("\"", "'");
        String escapedError = error == null ? "" : error.replace("\"", "'");
        return "{\"status\":\"" + status + "\",\"checked_at\":\"" + checkedAt + "\""
                + (error == null ? "" : ",\"error\":\"" + escapedError + "\"")
                + (detail == null ? "" : ",\"detail\":\"" + escapedDetail + "\"")
                + ",\"failure_streak\":0,\"circuit\":\"closed\"}";
    }
}
