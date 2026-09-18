package com.personal.jobagent.notifications;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.events.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one seam every notification-producing code path uses.
 *
 * Flow (pure outbox-driven — no direct notification writes from business
 * code):
 *   1. emit() appends an event to the transactional outbox via
 *      OutboxWriter. Called inside a @Transactional business method, the
 *      outbox row commits atomically with the business row it describes —
 *      a crash after commit still delivers, a rollback never emits.
 *   2. OutboxProcessor dispatches the event; InProcessEventPublisher
 *      enforces once-per-(event, consumer) via consumed_events.
 *   3. NotificationEventHandler maps the event to a category and inserts
 *      the notifications row through NotificationRepository.insertIfAbsent()
 *      — dedup_key + the partial UNIQUE index make replay a no-op.
 *   4. A matching audit_logs row is written ONLY when a NEW notification
 *      was actually created (a replayed event must not re-audit;
 *      audit_logs is append-only).
 *
 * Idempotency contract:
 *  - dedup_key null  -> the insert always happens (genuinely
 *                       per-occurrence classes, e.g. OUTBOX_DLQ rows).
 *  - dedup_key set   -> exactly one notification per key, enforced by the
 *                       partial UNIQUE index, not by check-then-insert.
 *
 * Secrets rule: neither metadata nor body may contain OTPs, tokens,
 * passwords, cookies, or raw email content — callers pass correlation IDs
 * only; this service never logs payload contents.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** The single consumer name the fan-out is idempotent under. */
    public static final String CONSUMER_NAME = "notification-fanout";

    private final OutboxWriter outboxWriter;
    private final AuditLogWriter auditLogWriter;
    private final NotificationRepository repository;

    public NotificationService(OutboxWriter outboxWriter,
                               AuditLogWriter auditLogWriter,
                               NotificationRepository repository) {
        this.outboxWriter = outboxWriter;
        this.auditLogWriter = auditLogWriter;
        this.repository = repository;
    }

    /**
     * Appends the notification event to the outbox. Must be called from
     * within the caller's transaction when there is one (OutboxWriter
     * contract) so the notification can never outlive its business row.
     *
     * @return the outbox event id — the correlation anchor for tests and
     *         downstream causation chains.
     */
    public UUID emit(NotificationCommand command) {
        return outboxWriter.append(
                command.aggregateType(),
                command.aggregateId(),
                command.eventType(),
                command.payload(),
                command.correlationId(),
                command.causationId());
    }

    /**
     * Shared delivery path used by NotificationEventHandler. Idempotent via
     * dedup_key; writes the audit row only when a notification was newly
     * created. Returns the stored row, or null on replay.
     */
    public NotificationRecord deliver(Delivery delivery) {
        Map<String, Object> meta = delivery.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(delivery.metadata());
        meta.putIfAbsent("event_id", delivery.eventId().toString());
        meta.putIfAbsent("event_type", delivery.eventType());

        NotificationRecord record = new NotificationRecord(
                UuidV7.generate(),
                delivery.severity(),
                delivery.category(),
                delivery.title(),
                delivery.body(),
                delivery.link(),
                delivery.dedupKey(),
                meta,
                delivery.jobId(),
                delivery.applicationId(),
                null,
                null
        );

        NotificationRecord stored = repository.insertIfAbsent(record);
        if (stored == null) {
            // Replay: the notification for this exact business occurrence
            // already exists. Nothing written, nothing re-audited.
            log.debug("Notification deduplicated for key {}", delivery.dedupKey());
            return null;
        }

        auditLogWriter.write(new AuditEntry(
                "SYSTEM",
                "NOTIFICATION_CREATED",
                "notification",
                stored.id(),
                null,
                Map.of(
                        "category", stored.category(),
                        "severity", stored.severity(),
                        "dedup_key", stored.dedupKey() != null ? stored.dedupKey() : "",
                        "job_id", stored.jobId() != null ? stored.jobId().toString() : "",
                        "application_id", stored.applicationId() != null ? stored.applicationId().toString() : "",
                        "event_id", delivery.eventId().toString()
                ),
                null,
                delivery.correlationId()
        ));
        return stored;
    }

    /** Fan-out request as derived by the handler from a delivered Envelope. */
    public record Delivery(
            UUID eventId,
            String eventType,
            UUID jobId,
            UUID applicationId,
            UUID correlationId,
            String dedupKey,
            String severity,
            String category,
            String title,
            String body,
            String link,
            Map<String, Object> metadata
    ) {
    }

    /** Emission request — one per business occurrence. */
    public record NotificationCommand(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Object payload,
            UUID correlationId,
            UUID causationId
    ) {
    }
}
