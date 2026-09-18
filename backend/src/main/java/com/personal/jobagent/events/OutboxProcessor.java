package com.personal.jobagent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationRecord;
import com.personal.jobagent.notifications.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;
    private final NotificationRepository notificationRepository;

    public OutboxProcessor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, EventPublisher eventPublisher,
                           NotificationRepository notificationRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.notificationRepository = notificationRepository;
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
        List<PendingRow> rows = jdbcTemplate.query("""
                select id, aggregate_type, aggregate_id, event_type, payload::text as payload,
                       correlation_id, causation_id, attempt_count
                from outbox_events
                where published_at is null and attempt_count < ?
                order by sequence_id
                limit ?
                """, ROW_MAPPER, MAX_ATTEMPTS, BATCH_SIZE);

        for (PendingRow row : rows) {
            dispatchOne(row);
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
        List<Map<String, Object>> stuck = jdbcTemplate.queryForList("""
                select id, event_type, attempt_count, last_error
                from outbox_events
                where published_at is null and attempt_count >= ?
                """, MAX_ATTEMPTS);

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
                    null, null, null, null);

            NotificationRecord stored = notificationRepository.insertIfAbsent(dlqNotification);
            if (stored != null) {
                log.error("Outbox event {} exceeded {} attempts, moving to DLQ. Last error: {}",
                        eventId, MAX_ATTEMPTS, row.get("last_error"));
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
