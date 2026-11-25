package com.personal.jobagent.track.receipt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link ReceiptTrustStore}. Every query is scoped by
 * profile, application and plan together, so a record belonging to another
 * candidate, application or plan can never satisfy a check.
 */
@Component
public class JdbcReceiptTrustStore implements ReceiptTrustStore {

    private final JdbcTemplate db;
    private final SubmissionEvidenceRepository evidence;

    public JdbcReceiptTrustStore(JdbcTemplate db, SubmissionEvidenceRepository evidence) {
        this.db = db;
        this.evidence = evidence;
    }

    @Override
    public boolean ownsPlan(UUID profileId, UUID applicationId, UUID planId) {
        if (profileId == null || applicationId == null || planId == null) {
            return false;
        }
        Integer count = db.queryForObject("""
                select count(*) from automation_plans ap
                join applications a on a.id = ap.application_id and a.profile_id = ap.profile_id
                where ap.id = ? and ap.application_id = ? and ap.profile_id = ?
                """, Integer.class, planId, applicationId, profileId);
        return count != null && count > 0;
    }

    /**
     * Strict approval lookup: the {@code <PLANTYPE>_SUBMIT_APPROVED} row that
     * {@code AutomationController#approve} writes, for exactly this plan, whose
     * plan type matches the plan's own stored type, whose payload records
     * {@code approvedForSubmission=true}, and whose actor is the owning
     * candidate's own account (email, user id or profile id) — never a worker,
     * system or anonymous principal. There is no looser fallback.
     */
    /** Parameters, in order: planId, applicationId, profileId. Returns columns id, actor, created_at. */
    public static final String STRICT_APPROVAL_SQL = """
            select al.id, al.actor, al.created_at
            from audit_logs al
            join automation_plans ap on ap.id = al.entity_id
            join applications a on a.id = ap.application_id and a.profile_id = ap.profile_id
            join profiles p on p.id = ap.profile_id
            join users u on u.id = p.user_id
            where al.entity_id = ?
              and ap.application_id = ?
              and ap.profile_id = ?
              and ap.submit_approved = true
              and al.entity_type = 'AUTOMATION_PLAN'
              and al.action in ('GREENHOUSE_SUBMIT_APPROVED', 'ASHBY_SUBMIT_APPROVED', 'WORKDAY_SUBMIT_APPROVED')
              and al.action = coalesce(ap.plan ->> 'planType', 'GREENHOUSE') || '_SUBMIT_APPROVED'
              and al.after_state is not null
              and (al.after_state ->> 'approvedForSubmission') = 'true'
              and (lower(trim(al.actor)) = lower(trim(u.email)) or al.actor = u.id::text or al.actor = p.id::text)
              and lower(trim(al.actor)) not in ('worker', 'system', 'anonymous', 'service', 'user')
            order by al.created_at desc
            limit 1
            """;

    @Override
    public Optional<VerifiedApproval> verifiedApproval(UUID profileId, UUID applicationId, UUID planId) {
        if (profileId == null || applicationId == null || planId == null) {
            return Optional.empty();
        }
        return db.query(STRICT_APPROVAL_SQL, (rs, n) -> new VerifiedApproval(
                (UUID) rs.getObject("id"),
                rs.getString("actor"),
                rs.getTimestamp("created_at").toInstant()), planId, applicationId, profileId)
                .stream().findFirst();
    }

    @Override
    public Optional<WorkerEvidence> workerEvidence(UUID profileId, UUID applicationId, UUID planId) {
        return evidence.findForPlan(profileId, applicationId, planId).map(e -> new WorkerEvidence(
                e.id(),
                e.workerPrincipal(),
                e.recordedVia(),
                e.atsPlatform(),
                e.targetUrl(),
                e.employerConfirmationId(),
                e.observedSignals(),
                e.rawEvidenceSha256(),
                e.rawEvidenceStatus(),
                e.recordedAt()));
    }

    @Override
    public Optional<SandboxRegistration> activeSandboxRegistration(UUID profileId, String atsPlatform, String postingUrl) {
        if (profileId == null || atsPlatform == null || postingUrl == null || postingUrl.isBlank()) {
            return Optional.empty();
        }
        String trimmed = postingUrl.trim();
        String canonical = SandboxRegistrationPolicy.canonicalizePostingUrl(trimmed);
        String canonicalLookup = canonical != null ? canonical : trimmed;
        String matchCanonical = SandboxRegistrationPolicy.canonicalizePostingUrlForMatch(trimmed);
        String matchLookup = matchCanonical != null ? matchCanonical : canonicalLookup;
        return db.query("""
                select id, profile_id, ats_platform, posting_url, permitted_domain, approved_at, revoked_at
                from candidate_sandbox_registrations
                where profile_id = ? and ats_platform = ? and posting_url in (?, ?, ?) and revoked_at is null
                order by approved_at desc
                limit 1
                """, (rs, n) -> mapSandbox(rs),
                profileId, atsPlatform, trimmed, canonicalLookup, matchLookup).stream().findFirst();
    }

    @Override
    public Optional<SandboxRegistration> sandboxRegistrationById(UUID registrationId) {
        if (registrationId == null) {
            return Optional.empty();
        }
        return db.query("""
                select id, profile_id, ats_platform, posting_url, permitted_domain, approved_at, revoked_at
                from candidate_sandbox_registrations
                where id = ?
                """, (rs, n) -> mapSandbox(rs), registrationId).stream().findFirst();
    }

    private static SandboxRegistration mapSandbox(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new SandboxRegistration(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("profile_id"),
                rs.getString("ats_platform"),
                rs.getString("posting_url"),
                rs.getString("permitted_domain"),
                rs.getTimestamp("approved_at").toInstant(),
                revoked == null ? null : revoked.toInstant());
    }
}
