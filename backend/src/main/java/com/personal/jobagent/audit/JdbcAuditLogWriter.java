package com.personal.jobagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.OwnerContext;
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
                (id, actor, action, entity_type, entity_id, before_state, after_state, ip, correlation_id, created_at, profile_id)
            values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet, ?, now(), ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OwnerContext ownerContext;

    public JdbcAuditLogWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, OwnerContext ownerContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ownerContext = ownerContext;
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
                entry.correlationId(),
                resolveOwnerProfileId(entry.actor())
        );
    }

    /**
     * Attributes the audit row to a profile, so the append-only log can still be
     * read back per user.
     *
     * <p>Two sources, in order:
     * <ol>
     *   <li>the caller's own profile, and only when the entry's actor really is
     *       the authenticated account. The check is deliberately not "there is a
     *       session, therefore use its profile": the actor string is supplied by
     *       the calling code, and attributing an action to whoever happens to be
     *       logged in would let one account's action be filed against another's
     *       audit trail.</li>
     *   <li>a lookup of the actor email, which is what makes rows written by
     *       background threads (no session) still attributable.</li>
     * </ol>
     *
     * <p>Anything else — SYSTEM, worker, UNKNOWN — is stored with a null owner
     * and stays out of every user-facing audit read. Note that V008 makes this
     * table insert-only, so a row's owner can never be corrected later; that is
     * why attribution is derived at read time as well (see AuditController) from
     * the same actor column.
     */
    private java.util.UUID resolveOwnerProfileId(String actor) {
        try {
            String caller = ownerContext.actorOr(null);
            if (actor != null && caller != null && actor.trim().equalsIgnoreCase(caller.trim())) {
                java.util.UUID own = ownerContext.profileIdOrNull();
                if (own != null) {
                    return own;
                }
            }
            return ownerContext.ownerOfActorEmail(actor).orElse(null);
        } catch (RuntimeException e) {
            // Never fail an audited action because ownership could not be
            // resolved — an unattributed row is recoverable, a lost audit row
            // is not.
            return null;
        }
    }

    @Override
    public boolean existsActionForActor(String action, String actor) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = ? and actor = ?",
                Integer.class, action, actor);
        return count != null && count > 0;
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
