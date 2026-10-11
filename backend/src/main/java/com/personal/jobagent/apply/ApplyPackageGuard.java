package com.personal.jobagent.apply;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlanRepository;
import com.personal.jobagent.common.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Keeps an approved execution package honest (Phase 8.2).
 *
 * <p>An approval says "the exact documents and answers in this package are
 * the ones I reviewed". When any of those records change afterwards — a CV
 * review is granted or withdrawn, a cover letter is approved, corrected or
 * unapproved, an answer is edited or unconfirmed — that statement is no
 * longer true, so the approval is withdrawn and the plan returns to human
 * review. The approval gate re-checks everything anyway; this makes the
 * invalidation visible and immediate instead of waiting for the next use.
 */
@Service
public class ApplyPackageGuard {

    private static final Logger log = LoggerFactory.getLogger(ApplyPackageGuard.class);

    private final JdbcTemplate db;
    private final AutomationPlanRepository plans;
    private final AuditLogWriter audit;

    public ApplyPackageGuard(JdbcTemplate db, AutomationPlanRepository plans, AuditLogWriter audit) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
    }

    public void cvReviewChanged(UUID profileId, UUID cvVersionId) {
        resolveAndInvalidate(profileId,
                "select application_id from cv_versions where id = ? and profile_id = ?",
                cvVersionId, "CV review changed");
    }

    public void letterChanged(UUID profileId, UUID letterId) {
        resolveAndInvalidate(profileId,
                "select application_id from cover_letters where id = ? and profile_id = ?",
                letterId, "cover letter changed");
    }

    public void answerChanged(UUID profileId, UUID answerId) {
        resolveAndInvalidate(profileId,
                "select application_id from application_answers where id = ? and profile_id = ?",
                answerId, "application answer changed");
    }

    private void resolveAndInvalidate(UUID profileId, String sql, UUID entityId, String reason) {
        UUID applicationId = db.query(sql, (rs, n) -> (UUID) rs.getObject(1), entityId, profileId)
                .stream().findFirst().orElse(null);
        if (applicationId == null) return;
        invalidateForApplication(profileId, applicationId, reason);
    }

    /** Withdraws any approved package for this application; audited. */
    public void invalidateForApplication(UUID profileId, UUID applicationId, String reason) {
        int invalidated;
        try {
            invalidated = plans.invalidateApproval(applicationId);
        } catch (Exception e) {
            // Never let bookkeeping break the user's actual edit, and never
            // claim an invalidation happened when it did not.
            log.warn("Could not invalidate package approval for application {}: {}", applicationId, e.getMessage());
            return;
        }
        if (invalidated <= 0) return;
        audit.write(new AuditEntry("SYSTEM", "PACKAGE_APPROVAL_INVALIDATED", "APPLICATION", applicationId,
                Map.of("submitApproved", true),
                Map.of("reason", reason, "profileScope", profileId == null ? "" : profileId.toString()),
                null, UuidV7.generate()));
        log.info("Package approval invalidated for application {} ({})", applicationId, reason);
    }
}
