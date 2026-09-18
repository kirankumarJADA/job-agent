package com.personal.jobagent.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for notifications (V001 table + V007 columns).
 *
 * insertIfAbsent() is the idempotency mechanism: `on conflict do nothing`
 * against the partial UNIQUE index notifications_dedup_key_uq makes it
 * atomic — two concurrent dispatchers (or a redelivered outbox event) can
 * race, but only one row is ever created. The null return is the replay
 * signal: "a notification for this business occurrence already existed".
 *
 * Serde failure of metadata is non-fatal by design: a notification is an
 * operator/candidate-facing convenience, not the system of record (the
 * outbox row + audit_logs are).
 */
@Component
public class NotificationRepository {

    private static final Logger log = LoggerFactory.getLogger(NotificationRepository.class);

    private static final String SELECT_COLUMNS =
            "select id, severity, category, title, body, link, dedup_key, metadata, "
                    + "job_id, application_id, read_at, created_at from notifications";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<NotificationRecord> rowMapper;

    public NotificationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        // Assigned in the constructor (not a field initializer) because the
        // lambda captures objectMapper.
        this.rowMapper = (rs, rowNum) -> new NotificationRecord(
                (UUID) rs.getObject("id"),
                rs.getString("severity"),
                rs.getString("category"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("link"),
                rs.getString("dedup_key"),
                JdbcConversions.readJsonMap(rs, "metadata", objectMapper),
                (UUID) rs.getObject("job_id"),
                (UUID) rs.getObject("application_id"),
                toInstant(rs.getTimestamp("read_at")),
                toInstant(rs.getTimestamp("created_at")));
    }

    /**
     * Atomic insert-if-absent keyed on dedup_key.
     *
     * @return the stored row, or null when a notification with the same
     *         dedup_key already existed (the replay case — nothing written).
     */
    public NotificationRecord insertIfAbsent(NotificationRecord notification) {
        boolean inserted = jdbcTemplate.update("""
                        insert into notifications
                            (id, severity, category, title, body, link, dedup_key, metadata, job_id, application_id, read_at, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())
                        on conflict (dedup_key) where dedup_key is not null do nothing
                        """,
                notification.id(),
                notification.severity(),
                notification.category(),
                notification.title(),
                notification.body(),
                notification.link(),
                notification.dedupKey(),
                toJson(notification.metadata()),
                notification.jobId(),
                notification.applicationId(),
                null) > 0;

        if (!inserted) {
            return null;
        }
        return findById(notification.id()).orElse(notification);
    }

    public Optional<NotificationRecord> findById(UUID id) {
        List<NotificationRecord> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " where id = ?", rowMapper, id);
        return rows.stream().findFirst();
    }

    public List<NotificationRecord> findRecent(int limit) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " order by created_at desc limit ?", rowMapper, Math.min(limit, 200));
    }

    public List<NotificationRecord> findRecentUnread(int limit) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " where read_at is null order by created_at desc limit ?",
                rowMapper, Math.min(limit, 200));
    }

    public long countUnread() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from notifications where read_at is null", Long.class);
        return count != null ? count : 0;
    }

    /** Marks a single notification read. Idempotent (no-op if already read). */
    public boolean markRead(UUID id) {
        return jdbcTemplate.update(
                "update notifications set read_at = now() where id = ? and read_at is null", id) > 0;
    }

    /** Marks every unread notification read. Returns how many rows changed. */
    public int markAllRead() {
        return jdbcTemplate.update("update notifications set read_at = now() where read_at is null");
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize notification metadata; persisting empty metadata: {}", e.getMessage());
            return "{}";
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}
