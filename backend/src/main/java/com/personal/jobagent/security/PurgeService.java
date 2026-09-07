package com.personal.jobagent.security;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * "Delete my data" per docs/contracts/api.md's confirmed contract:
 * - Deletes the profiles row and everything cascaded under it
 *   (work_experiences, education, projects, certifications, skills,
 *   preference_sets — all already `on delete cascade` from profile_id in
 *   V001, verified for real against a live Postgres instance during
 *   implementation: deleting one profiles row correctly cascaded to a
 *   skills row and a preference_sets row, while the users row survived
 *   untouched).
 * - Retains the users row — this is a data purge, not account deletion;
 *   login still works afterward, the user just has no profile data.
 * - Retains audit_logs unconditionally — required both for security/audit
 *   integrity (you cannot meaningfully audit a deletion if the record of
 *   that deletion is itself deletable) and because V001 already enforces
 *   this at the database level (update/delete revoked from backend_role).
 *   The DATA_PURGE_REQUESTED audit row is written BEFORE the delete
 *   executes, in the same transaction, so if the delete fails the audit
 *   row rolls back with it — there's no "we tried to purge but only the
 *   audit trail survived" inconsistent state.
 */
@Service
public class PurgeService {

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogWriter auditLogWriter;

    public PurgeService(JdbcTemplate jdbcTemplate, AuditLogWriter auditLogWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogWriter = auditLogWriter;
    }

    @Transactional
    public void purgeProfileData(UUID userId, String actorEmail, UUID correlationId, String ip) {
        Map<String, Object> snapshot = snapshotBeforeDelete(userId);

        auditLogWriter.write(new AuditEntry(
                actorEmail,
                "DATA_PURGE_REQUESTED",
                "USER",
                userId,
                snapshot,
                null,
                ip,
                correlationId
        ));

        jdbcTemplate.update("delete from profiles where user_id = ?", userId);
    }

    private Map<String, Object> snapshotBeforeDelete(UUID userId) {
        try {
            return jdbcTemplate.queryForMap("""
                    select p.id as profile_id,
                           (select count(*) from work_experiences where profile_id = p.id) as experience_count,
                           (select count(*) from education where profile_id = p.id) as education_count,
                           (select count(*) from projects where profile_id = p.id) as project_count,
                           (select count(*) from certifications where profile_id = p.id) as certification_count,
                           (select count(*) from skills where profile_id = p.id) as skill_count,
                           (select count(*) from preference_sets where profile_id = p.id) as preference_set_count
                    from profiles p
                    where p.user_id = ?
                    """, userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // No profile exists yet (fresh account) — still a valid purge
            // request, just nothing to snapshot or delete.
            return Map.of("profile_existed", false);
        }
    }
}
