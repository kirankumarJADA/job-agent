package com.personal.jobagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Plain JDBC implementation, not a JPA entity. Deliberate choice for this
 * table specifically: audit_logs is append-only (insert-only from the
 * application's perspective — updates/deletes are revoked at the DB level
 * for backend_role per V001), has no relationships to navigate, and two
 * jsonb columns whose ORM mapping isn't worth resolving here. The broader
 * JPA-vs-jsonb/array question (conflict review §3) stays correctly scoped
 * to P1-d's planned entity spike — this class doesn't answer that question
 * either way, it just doesn't need to.
 *
 * The exact SQL/parameter pattern below (including the null-heavy case a
 * LOGIN_SUCCESS/LOGIN_FAILURE row actually produces) was verified against a
 * live Postgres 16 instance during P1-b implementation — see the PR/commit
 * notes for the raw JDBC reproduction. That is NOT the same as this Spring
 * bean itself having been run — it's the same SQL and parameter types,
 * exercised directly, before wiring it behind Spring.
 */
@Component
public class JdbcAuditLogWriter implements AuditLogWriter {

    private static final String INSERT_SQL = """
            insert into audit_logs
                (id, actor, action, entity_type, entity_id, before_state, after_state, ip, correlation_id, created_at)
            values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet, ?, now())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAuditLogWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void write(AuditEntry entry) {
        jdbcTemplate.update(INSERT_SQL,
                UuidV7.generate(),
                entry.actor(),
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                toJson(entry.beforeState(), entry.action()),
                toJson(entry.afterState(), entry.action()),
                entry.ip(),
                entry.correlationId()
        );
    }

    private String toJson(Object value, String actionForErrorContext) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Deliberately fails loudly rather than silently dropping the
            // before/after state — a broken audit row is worse than a
            // failed request during P1-b's development, and this should
            // never happen in practice since callers pass simple Maps/DTOs.
            throw new IllegalStateException(
                    "Failed to serialize audit state to JSON for action=" + actionForErrorContext, e);
        }
    }
}
