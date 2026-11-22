package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3: Repository for candidate-controlled ATS browser handoff sessions.
 * Manages short-lived, owner-scoped security boundary handoffs with strict atomicity.
 */
@Repository
public class HandoffSessionRepository {

    private static final String SELECT_COLUMNS = """
            id, plan_id, application_id, profile_id, status, block_reason,
            target_url, handoff_token, expires_at, completed_at, cancelled_at,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public HandoffSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<HandoffSessionRecord> rowMapper = new RowMapper<>() {
        @Override
        public HandoffSessionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp expiresTs = rs.getTimestamp("expires_at");
            Timestamp completedTs = rs.getTimestamp("completed_at");
            Timestamp cancelledTs = rs.getTimestamp("cancelled_at");
            Timestamp createdTs = rs.getTimestamp("created_at");
            Timestamp updatedTs = rs.getTimestamp("updated_at");

            return new HandoffSessionRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("plan_id", UUID.class),
                    rs.getObject("application_id", UUID.class),
                    rs.getObject("profile_id", UUID.class),
                    HandoffStatus.valueOf(rs.getString("status")),
                    HandoffBlockReason.parse(rs.getString("block_reason")),
                    rs.getString("target_url"),
                    rs.getObject("handoff_token", UUID.class),
                    expiresTs != null ? expiresTs.toInstant() : null,
                    completedTs != null ? completedTs.toInstant() : null,
                    cancelledTs != null ? cancelledTs.toInstant() : null,
                    createdTs != null ? createdTs.toInstant() : null,
                    updatedTs != null ? updatedTs.toInstant() : null
            );
        }
    };

    /**
     * Creates and persists a new short-lived handoff session for an application.
     * Idempotent: If an active, non-expired session already exists for this plan, returns it.
     */
    public HandoffSessionRecord createSession(UUID ownerProfileId, UUID planId, UUID applicationId,
                                              String targetUrl, HandoffBlockReason blockReason, int ttlMinutes) {
        // Idempotency check: if an active non-expired session already exists for this plan, return it
        Optional<HandoffSessionRecord> existing = findActiveByPlan(ownerProfileId, planId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID sessionId = UuidV7.generate();
        UUID handoffToken = UUID.randomUUID();
        int safeTtl = Math.max(5, Math.min(ttlMinutes, 60));

        // Mark any existing pending session for this plan as superseded/cancelled
        jdbcTemplate.update("""
                UPDATE automation_handoff_sessions
                SET status = 'CANCELLED', cancelled_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE plan_id = ? AND status = 'PENDING'
                """, planId);

        jdbcTemplate.update("""
                INSERT INTO automation_handoff_sessions (
                    id, plan_id, application_id, profile_id, status, block_reason,
                    target_url, handoff_token, expires_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, 'PENDING', ?,
                    ?, ?, clock_timestamp() + make_interval(mins => ?), clock_timestamp(), clock_timestamp()
                )
                """, sessionId, planId, applicationId, ownerProfileId, blockReason.name(), targetUrl, handoffToken, safeTtl);

        return findById(ownerProfileId, sessionId)
                .orElseThrow(() -> new IllegalStateException("Failed to read created handoff session " + sessionId));
    }

    public Optional<HandoffSessionRecord> findById(UUID ownerProfileId, UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM automation_handoff_sessions WHERE id = ?";
        if (ownerProfileId != null) {
            sql += " AND profile_id = ?";
            return jdbcTemplate.query(sql, rowMapper, sessionId, ownerProfileId).stream().findFirst();
        }
        return jdbcTemplate.query(sql, rowMapper, sessionId).stream().findFirst();
    }

    public Optional<HandoffSessionRecord> findByIdAnyOwner(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM automation_handoff_sessions WHERE id = ?";
        return jdbcTemplate.query(sql, rowMapper, sessionId).stream().findFirst();
    }

    public Optional<UUID> findProfileIdForApplication(UUID applicationId) {
        if (applicationId == null) {
            return Optional.empty();
        }
        String sql = "SELECT profile_id FROM applications WHERE id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getObject("profile_id", UUID.class), applicationId).stream().findFirst();
    }

    public Optional<HandoffSessionRecord> findActiveByApplication(UUID ownerProfileId, UUID applicationId) {
        if (applicationId == null) {
            return Optional.empty();
        }
        String sql = "SELECT " + SELECT_COLUMNS + """
                FROM automation_handoff_sessions
                WHERE application_id = ?
                  AND status = 'PENDING'
                  AND expires_at > clock_timestamp()
                """;
        if (ownerProfileId != null) {
            sql += " AND profile_id = ?";
            return jdbcTemplate.query(sql, rowMapper, applicationId, ownerProfileId).stream().findFirst();
        }
        return jdbcTemplate.query(sql, rowMapper, applicationId).stream().findFirst();
    }

    public Optional<HandoffSessionRecord> findActiveByPlan(UUID ownerProfileId, UUID planId) {
        if (planId == null) {
            return Optional.empty();
        }
        String sql = "SELECT " + SELECT_COLUMNS + """
                FROM automation_handoff_sessions
                WHERE plan_id = ?
                  AND status = 'PENDING'
                  AND expires_at > clock_timestamp()
                """;
        if (ownerProfileId != null) {
            sql += " AND profile_id = ?";
            return jdbcTemplate.query(sql, rowMapper, planId, ownerProfileId).stream().findFirst();
        }
        return jdbcTemplate.query(sql, rowMapper, planId).stream().findFirst();
    }

    /**
     * Atomically marks a handoff session completed if and only if it is PENDING and not expired.
     */
    public boolean completeSession(UUID ownerProfileId, UUID sessionId) {
        if (sessionId == null) {
            return false;
        }
        String sql = """
                UPDATE automation_handoff_sessions
                SET status = 'COMPLETED',
                    completed_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'PENDING'
                  AND expires_at > clock_timestamp()
                """;
        if (ownerProfileId != null) {
            sql += " AND profile_id = ?";
            return jdbcTemplate.update(sql, sessionId, ownerProfileId) == 1;
        }
        return jdbcTemplate.update(sql, sessionId) == 1;
    }

    /**
     * Atomically cancels a pending handoff session.
     */
    public boolean cancelSession(UUID ownerProfileId, UUID sessionId) {
        if (sessionId == null) {
            return false;
        }
        String sql = """
                UPDATE automation_handoff_sessions
                SET status = 'CANCELLED',
                    cancelled_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'PENDING'
                """;
        if (ownerProfileId != null) {
            sql += " AND profile_id = ?";
            return jdbcTemplate.update(sql, sessionId, ownerProfileId) == 1;
        }
        return jdbcTemplate.update(sql, sessionId) == 1;
    }

    /**
     * Marks all overdue pending sessions as EXPIRED.
     */
    public int expireStaleSessions() {
        return jdbcTemplate.update("""
                UPDATE automation_handoff_sessions
                SET status = 'EXPIRED',
                    updated_at = clock_timestamp()
                WHERE status = 'PENDING'
                  AND expires_at <= clock_timestamp()
                """);
    }

    public Optional<String> getApplicationStatus(UUID applicationId) {
        if (applicationId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query("SELECT status FROM applications WHERE id = ?",
                (rs, rowNum) -> rs.getString("status"), applicationId).stream().findFirst();
    }

    public void markApplicationSubmittedDirectly(UUID applicationId, UUID ownerProfileId) {
        if (applicationId == null) {
            return;
        }
        String sql = """
                UPDATE applications
                SET status = 'APPLICATION_SUBMITTED',
                    submitted_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ?
                """;
        if (ownerProfileId != null) {
            sql += " AND profile_id = ?";
            jdbcTemplate.update(sql, applicationId, ownerProfileId);
        } else {
            jdbcTemplate.update(sql, applicationId);
        }
    }
}
