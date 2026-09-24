package com.personal.jobagent.audit;

import com.personal.jobagent.security.OwnerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Audit Controller: surfaces the immutable audit log for the frontend
 * Logs & Audit page.
 *
 * <p><b>Scoped to the caller.</b> audit_logs is global by nature (it records
 * every account's actions, plus system actions), so the read path gets two
 * filters and returns the union of exactly two things:
 *
 * <ol>
 *   <li>rows whose {@code profile_id} is the caller's — the modern case, written
 *       at INSERT by {@link JdbcAuditLogWriter};</li>
 *   <li>rows with no owner whose actor IS the caller's email — the legacy case.
 *       V008 makes audit_logs append-only, so V022 could not backfill an owner
 *       onto pre-existing rows (the migration genuinely fails with
 *       "audit_logs is append-only" if it tries), and this read-time derivation
 *       from the {@code actor} column is the substitute. It is exact: the actor
 *       column holds the acting account's email.</li>
 * </ol>
 *
 * <p>What is deliberately NOT returned is anything else — in particular the
 * unattributed system rows. Those include LOGIN_FAILURE entries whose actor is
 * the email of whoever typed a wrong password, so returning them to every
 * signed-in user would hand out other people's account identifiers. The cost is
 * that ops-level rows (SYSTEM, worker) are no longer visible in the user-facing
 * audit page; that is the fail-closed direction to fail in.
 *
 * (The /notifications read surface moved to the notifications module's
 * NotificationApiController in Feature 8 — richer rows: dedup_key,
 * metadata, job/application correlation, read-state — and the old
 * duplicate GET /api/v1/notifications mapping here collided with it at
 * context startup. LogsPage now consumes the new endpoint.)
 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final JdbcTemplate jdbcTemplate;
    private final OwnerContext ownerContext;

    public AuditController(JdbcTemplate jdbcTemplate, OwnerContext ownerContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.ownerContext = ownerContext;
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> listAuditLogs(@RequestParam(defaultValue = "50") int limit) {
        UUID profileId = ownerContext.profileIdOrNull();
        String actor = ownerContext.actorOr("");
        if (profileId == null && actor.isBlank()) {
            // Unauthenticated (or an account with no profile yet and no
            // identity to match on): nothing is attributable, so nothing is
            // returned rather than everything.
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                select id, actor, action, entity_type, entity_id,
                       before_state::text as before_state,
                       after_state::text as after_state,
                       correlation_id, created_at
                from audit_logs
                where (profile_id = ?)
                   or (profile_id is null and lower(actor) = lower(?))
                order by created_at desc
                limit ?
                """, profileId, actor, Math.min(limit, 100));
    }
}
