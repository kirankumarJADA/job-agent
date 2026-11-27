package com.personal.jobagent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.common.SchedulerDatabaseGuard;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationRecord;
import com.personal.jobagent.notifications.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns both the normal dispatch cadence and the DLQ/reconciliation sweep —
 * per the architectural-conflict-review's resolution, the Scheduler
 * component (unassigned in the original P1-a..g build order) is folded
 * into P1-c alongside the Reconciler it was always going to need to run.
 *
 * dispatchPending(): fast poll (every 2s), processes newly-written rows.
 * This is also what satisfies the "kill mid-dispatch, restart, zero lost
 * events" DoD item — any row left with published_at=null after a crash is
 * picked up by the very next tick after restart, no special recovery code
 * needed beyond "the query already excludes published rows."
 *
 * reconcileDlq(): slow sweep (every 5 min), per architecture §C4's DLQ
 * definition. Rows that have failed MAX_ATTEMPTS times are excluded from
 * the fast path (see the WHERE clause below) to avoid a hot retry loop
 * against a permanently-broken handler; this sweep is what surfaces them
 * via a notification instead of retrying forever silently.
 *
 * Exclusion (database migration Option C, V052): while an
 * outbox_exclusion_batches row is active, neither path reads, dispatches,
 * retries or dead-letters a pending job.discovered event at or below its
 * watermark (max_sequence_id), so none is ever marked published or recorded
 * in consumed_events. Every other event is dispatched in sequence order as
 * before. outbox_exclude_pending_discovery() records exactly that set, under
 * a lock that keeps any later event from committing below the watermark.
 * With no active batch, both queries are the original ones.
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 50;

    /** The next events to dispatch, in sequence order; used when no exclusion is active. */
    static final String PENDING_SQL = """
            select id, aggregate_type, aggregate_id, event_type, payload::text as payload,
                   correlation_id, causation_id, attempt_count
            from outbox_events
            where published_at is null and attempt_count < ?
            order by sequence_id
            limit ?
            """;

    /**
     * The same selection while an exclusion is active (watermark W):
     * everything above W, plus the events at or below W that are not
     * job.discovered. Each branch is an index range scan (outbox_pending_idx
     * above W, outbox_pending_non_discovery_idx at or below it), so the
     * excluded rows are never read, however many there are. The literal
     * 'job.discovered' must stay a literal: it is what lets the planner use
     * the partial index.
     */
    static final String PENDING_WITH_EXCLUSION_SQL = """
            select id, aggregate_type, aggregate_id, event_type, payload, correlation_id, causation_id, attempt_count
            from (
                (select sequence_id, id, aggregate_type, aggregate_id, event_type, payload::text as payload,
                        correlation_id, causation_id, attempt_count
                   from outbox_events
                  where published_at is null and attempt_count < ? and sequence_id > ?
                  order by sequence_id
                  limit ?)
                union all
                (select sequence_id, id, aggregate_type, aggregate_id, event_type, payload::text as payload,
                        correlation_id, causation_id, attempt_count
                   from outbox_events
                  where published_at is null and event_type <> 'job.discovered'
                    and attempt_count < ? and sequence_id <= ?
                  order by sequence_id
                  limit ?)
            ) eligible
            order by sequence_id
            limit ?
            """;

    static final String DEAD_LETTER_SQL = """
            select id, event_type, attempt_count, last_error
            from outbox_events
            where published_at is null and attempt_count >= ?
            """;

    static final String DEAD_LETTER_WITH_EXCLUSION_SQL = """
            select id, event_type, attempt_count, last_error
            from outbox_events
            where published_at is null and attempt_count >= ?
              and not (event_type = 'job.discovered' and sequence_id <= ?)
            """;

    /** The active exclusion's watermark, or 0. At most one batch is active (V052's unique index). */
    static final String EXCLUSION_WATERMARK_SQL =
            "select coalesce(max(max_sequence_id), 0) from outbox_exclusion_batches where reverted_at is null";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;
    private final NotificationRepository notificationRepository;
    private final MaintenanceModeService maintenanceMode;

    /**
     * Migration kill switch (database migration Phase 2, plan §6 and §10.7).
     * With {@code app.events.dispatch-enabled=false} the backend starts and
     * serves requests, but neither {@link #dispatchPending()} nor
     * {@link #reconcileDlq()} reads or writes {@code outbox_events}: no event
     * is published, retried or dead-lettered, so a restored backlog stays
     * exactly as it was restored until an operator turns dispatch back on.
     * Events written meanwhile are kept, pending, in sequence order. Default:
     * enabled, so nothing changes unless the variable is set.
     */
    @Value("${app.events.dispatch-enabled:true}")
    private boolean dispatchEnabled = true;
    private final AtomicBoolean pausedLogged = new AtomicBoolean(false);
    private final AtomicLong loggedWatermark = new AtomicLong(0);

    @Autowired(required = false)
    private SchedulerDatabaseGuard databaseGuard;

    @Autowired
    public OutboxProcessor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, EventPublisher eventPublisher,
                           NotificationRepository notificationRepository,
                           @Autowired(required = false) MaintenanceModeService maintenanceMode) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.notificationRepository = notificationRepository;
        this.maintenanceMode = maintenanceMode != null ? maintenanceMode : new MaintenanceModeService(false);
    }

    public OutboxProcessor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, EventPublisher eventPublisher,
                           NotificationRepository notificationRepository) {
        this(jdbcTemplate, objectMapper, eventPublisher, notificationRepository, new MaintenanceModeService(false));
    }

    private record PendingRow(UUID id, String aggregateType, UUID aggregateId, String eventType,
                               String payloadJson, UUID correlationId, UUID causationId, int attemptCount) {
    }

    private static final RowMapper<PendingRow> ROW_MAPPER = (rs, rowNum) -> new PendingRow(
            (UUID) rs.getObject("id"),
            rs.getString("aggregate_type"),
            (UUID) rs.getObject("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            (UUID) rs.getObject("correlation_id"),
            (UUID) rs.getObject("causation_id"),
            rs.getInt("attempt_count")
    );

    @Scheduled(fixedDelayString = "${app.events.dispatch-interval-ms:2000}")
    public void dispatchPending() {
        if (!dispatchEnabled || maintenanceMode.isMaintenanceMode()) {
            logPausedOnce();
            return;
        }
        if (databaseGuard != null && !databaseGuard.isReady()) {
            return;
        }
        try {
            // Read first, every round, so an exclusion or a revert applies from the
            // next round. If it cannot be read, the round fails and nothing is
            // dispatched: the unfiltered query is never a fallback.
            long watermark = exclusionWatermark();
            List<PendingRow> rows = watermark > 0
                    ? jdbcTemplate.query(PENDING_WITH_EXCLUSION_SQL, ROW_MAPPER,
                            MAX_ATTEMPTS, watermark, BATCH_SIZE, MAX_ATTEMPTS, watermark, BATCH_SIZE, BATCH_SIZE)
                    : jdbcTemplate.query(PENDING_SQL, ROW_MAPPER, MAX_ATTEMPTS, BATCH_SIZE);

            for (PendingRow row : rows) {
                dispatchOne(row);
            }
        } catch (RuntimeException e) {
            if (databaseGuard != null && !databaseGuard.isReady()) {
                log.debug("Skipping outbox dispatch error during shutdown: {}", e.getClass().getSimpleName());
                return;
            }
            throw e;
        }
    }

    private void dispatchOne(PendingRow row) {
        try {
            Object payload = objectMapper.readValue(row.payloadJson(), Map.class);
            Envelope envelope = new Envelope(
                    row.id(), row.eventType(), 1, java.time.Instant.now(),
                    row.aggregateType(), row.aggregateId(), row.correlationId(), row.causationId(), payload);

            eventPublisher.publish(envelope);

            jdbcTemplate.update("update outbox_events set published_at = now() where id = ?", row.id());
        } catch (Exception e) {
            log.warn("Outbox dispatch failed for event {} (attempt {}): {}", row.id(), row.attemptCount() + 1, e.getMessage());
            jdbcTemplate.update(
                    "update outbox_events set attempt_count = attempt_count + 1, last_error = ? where id = ?",
                    truncate(e.getMessage(), 2000), row.id());
        }
    }

    @Scheduled(fixedDelayString = "${app.events.reconcile-interval-ms:300000}")
    public void reconcileDlq() {
        if (!dispatchEnabled || maintenanceMode.isMaintenanceMode()) {
            logPausedOnce();
            return;
        }
        if (databaseGuard != null && !databaseGuard.isReady()) {
            return;
        }
        long watermark;
        List<Map<String, Object>> stuck;
        try {
            watermark = exclusionWatermark();
            stuck = watermark > 0
                    ? jdbcTemplate.queryForList(DEAD_LETTER_WITH_EXCLUSION_SQL, MAX_ATTEMPTS, watermark)
                    : jdbcTemplate.queryForList(DEAD_LETTER_SQL, MAX_ATTEMPTS);
        } catch (RuntimeException e) {
            if (databaseGuard != null && !databaseGuard.isReady()) {
                log.debug("Skipping outbox DLQ reconcile error during shutdown: {}", e.getClass().getSimpleName());
                return;
            }
            throw e;
        }

        for (Map<String, Object> row : stuck) {
            UUID eventId = (UUID) row.get("id");
            // Feature 8: DLQ notifications go through the same idempotent
            // seam as everything else. dedup_key keyed on the failed event
            // id replaces the old manual count(*)-then-insert check (racy
            // under concurrent sweeps) with the partial UNIQUE index —
            // exactly one DLQ notification per permanently-failed event,
            // even if two sweeps race.
            NotificationRecord dlqNotification = new NotificationRecord(
                    UuidV7.generate(),
                    "ERROR",
                    "OUTBOX_DLQ",
                    "Event failed permanently: " + row.get("event_type"),
                    "Attempt count reached " + row.get("attempt_count") + ". Last error: " + row.get("last_error"),
                    eventId.toString(),
                    "outbox-dlq:" + eventId,
                    java.util.Map.of("event_id", eventId.toString(),
                            "event_type", String.valueOf(row.get("event_type"))),
                    null, null, null, null,
                    // System notification: an outbox dead-letter belongs to no user,
                    // so it is stored unowned (V022) and is invisible to every
                    // user-facing read. It is an ops signal, and the notification
                    // table is not its system of record — the outbox row is.
                    null);

            NotificationRecord stored = notificationRepository.insertIfAbsent(dlqNotification);
            if (stored != null) {
                log.error("Outbox event {} exceeded {} attempts, moving to DLQ. Last error: {}",
                        eventId, MAX_ATTEMPTS, row.get("last_error"));
            }
        }
    }

    /** Whether dispatch is on ({@code app.events.dispatch-enabled} and maintenance mode is inactive). */
    public boolean isDispatchEnabled() {
        return dispatchEnabled && !maintenanceMode.isMaintenanceMode();
    }

    /**
     * The highest sequence_id the active exclusion batch covers, or 0 when no
     * batch is active (V052). Logs each change once.
     */
    long exclusionWatermark() {
        Long read = jdbcTemplate.queryForObject(EXCLUSION_WATERMARK_SQL, Long.class);
        long watermark = read == null ? 0L : read;
        long previous = loggedWatermark.getAndSet(watermark);
        if (previous != watermark) {
            if (watermark > 0) {
                log.warn("Outbox exclusion active: pending job.discovered events at or below sequence {} are not "
                        + "dispatched, retried or dead-lettered (outbox_exclusion_batches)", watermark);
            } else {
                log.warn("Outbox exclusion lifted: every pending event is dispatched again, in sequence order");
            }
        }
        return watermark;
    }

    private void logPausedOnce() {
        if (pausedLogged.compareAndSet(false, true)) {
            if (maintenanceMode.isMaintenanceMode()) {
                log.warn("Outbox dispatch is PAUSED (maintenance mode active): no event is published, "
                        + "retried or dead-lettered until maintenance mode is lifted");
            } else {
                log.warn("Outbox dispatch is PAUSED (app.events.dispatch-enabled=false): no event is published, "
                        + "retried or dead-lettered until it is re-enabled");
            }
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
