package com.personal.jobagent.application;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5: automatic application decision engine.
 *
 * <p>Sits between the match scorer ({@code JOB_MATCHED} with an APPLY
 * recommendation) and the application creator ({@code createApplicationFromMatch}).
 * Every APPLY-level match passes through {@link #decide}, which evaluates:
 *
 * <ol>
 *   <li><b>Application mode</b> — {@code MANUAL} always queues for human
 *       review, {@code ASSISTED} auto-applies only high-confidence matches
 *       (≥ {@value #HIGH_CONFIDENCE_THRESHOLD}), and {@code CONTROLLED_AUTO}
 *       auto-applies every APPLY-level match.</li>
 *   <li><b>Quota</b> — if the profile's daily application quota is exhausted,
 *       the decision falls to NEEDS_REVIEW regardless of mode, so usage
 *       cannot runaway overnight.</li>
 * </ol>
 *
 * <p>Every decision is persisted in {@code application_decisions} for the
 * audit trail and for Phase 6 (review queue) and Phase 7 (auto-approval
 * rules) to consume. The table is upserted on {@code (profile_id, job_id)},
 * so event replays are idempotent.
 *
 * <p><b>Owner isolation:</b> the decision is always scoped to one profile,
 * and the quota check is per-profile.
 */
@Service
public class ApplicationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationDecisionService.class);

    /**
     * In ASSISTED mode, only matches at or above this score auto-apply.
     * Matches between APPLY_THRESHOLD (70) and this value queue for review.
     */
    static final int HIGH_CONFIDENCE_THRESHOLD = 85;

    private final PreferenceSetRepository preferenceSets;
    private final QuotaService quotaService;
    private final JdbcTemplate db;
    private final AutomationMetrics metrics;

    public ApplicationDecisionService(PreferenceSetRepository preferenceSets,
                                      QuotaService quotaService,
                                      JdbcTemplate db,
                                      AutomationMetrics metrics) {
        this.preferenceSets = preferenceSets;
        this.quotaService = quotaService;
        this.db = db;
        this.metrics = metrics;
    }

    /** The outcome of the decision engine for one (profile, job) pair. */
    public record DecisionResult(String decision, String reason, int matchScore) {}

    /**
     * Evaluates whether a match that scored APPLY should proceed to
     * automatic application creation or be queued for human review.
     *
     * @param profileId      the candidate profile (owner isolation)
     * @param jobId          the matched job
     * @param matchScore     the overall match score (0–100)
     * @param recommendation the scorer's recommendation (APPLY expected here)
     * @return the decision and the reason behind it
     */
    public DecisionResult decide(UUID profileId, UUID jobId,
                                 int matchScore, String recommendation) {

        String mode = preferenceSets.findActiveByProfileId(profileId)
                .map(p -> p.applicationMode() == null || p.applicationMode().isBlank()
                        ? "ASSISTED" : p.applicationMode())
                .orElse("ASSISTED");

        // ── 1. Non-APPLY recommendations never auto-apply ──
        // (Defensive: only APPLY matches emit JOB_MATCHED events, but guard
        // against future callers that might not enforce that.)
        if ("SKIP".equals(recommendation)) {
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "SKIP", "Below review threshold");
        }
        if ("REVIEW".equals(recommendation)) {
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Score in review range");
        }

        // ── 2. MANUAL mode: always needs human review ──
        if ("MANUAL".equals(mode)) {
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Manual mode requires human review");
        }

        // ── 3. Quota gate: prevent runaway auto-applications ──
        var quotaCheck = quotaService.check(profileId, "application_daily");
        if (!quotaCheck.allowed()) {
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Daily application quota exceeded (" +
                            (int) quotaCheck.used() + "/" + (int) quotaCheck.limit() + ")");
        }

        // ── 4. ASSISTED mode: high-confidence only ──
        if ("ASSISTED".equals(mode)) {
            if (matchScore >= HIGH_CONFIDENCE_THRESHOLD) {
                return record(profileId, jobId, matchScore, recommendation, mode,
                        "AUTO_APPLY", "High-confidence match in assisted mode (score " + matchScore + ")");
            }
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Score " + matchScore + " below auto-apply threshold ("
                            + HIGH_CONFIDENCE_THRESHOLD + ") for assisted mode");
        }

        // ── 5. CONTROLLED_AUTO: all APPLY recommendations auto-apply ──
        return record(profileId, jobId, matchScore, recommendation, mode,
                "AUTO_APPLY", "Controlled-auto mode with APPLY recommendation (score " + matchScore + ")");
    }

    // ── Phase 6: human review queue ──────────────────────────────────
    public record ReviewItem(UUID decisionId, UUID jobId, String jobTitle, String companyName,
                             String location, String applicationUrl, Integer matchScore,
                             String recommendation, String hardFilterOutcome, String hardFilterReasons,
                             String applicationMode, String decision, String reason,
                             boolean applicationExists, boolean preparationExists, boolean jobStale,
                             java.time.Instant createdAt, java.time.Instant updatedAt) {}

    /** Default active queue. Paused items are separately opt-in so clients can resume them. */
    public List<ReviewItem> listForReview(UUID profileId, int expireDays) {
        return listForReview(profileId, expireDays, false);
    }

    /** Owner-scoped queue; includePaused is an explicit choice by the caller. */
    public List<ReviewItem> listForReview(UUID profileId, int expireDays, boolean includePaused) {
        expireStale(profileId, expireDays);
        return db.query("""
                select d.id, d.job_id, j.title, j.company_name_raw, j.location_raw, j.application_url,
                       d.match_score, d.recommendation,
                       case when j.filter_reasons is null then 'PASSED' else 'FAILED' end as hard_filter_outcome,
                       coalesce(j.filter_reasons::text, '[]') as hard_filter_reasons,
                       d.application_mode, d.decision, d.reason, d.created_at, d.updated_at,
                       (app.id is not null) as application_exists,
                       (j.last_seen_at < now() - interval '30 days') as job_stale,
                       exists(select 1 from application_events ae
                              where ae.application_id = app.id and ae.type = 'PREPARATION') as preparation_exists
                from application_decisions d
                join jobs j on j.id = d.job_id and j.deleted_at is null
                left join lateral (
                    select a.id from applications a
                    where a.profile_id = d.profile_id and a.job_id = d.job_id
                      and a.status not in ('FAILED','WITHDRAWN')
                    order by a.created_at desc limit 1
                ) app on true
                where d.profile_id = ?
                  and (d.decision = 'NEEDS_REVIEW' or (? and d.decision = 'PAUSED'))
                order by d.created_at desc
                """, (rs, n) -> new ReviewItem(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("job_id"), rs.getString("title"),
                rs.getString("company_name_raw"), rs.getString("location_raw"), rs.getString("application_url"),
                (Integer) rs.getObject("match_score"), rs.getString("recommendation"),
                rs.getString("hard_filter_outcome"), rs.getString("hard_filter_reasons"),
                rs.getString("application_mode"), rs.getString("decision"), rs.getString("reason"),
                rs.getBoolean("application_exists"), rs.getBoolean("preparation_exists"), rs.getBoolean("job_stale"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null),
                profileId, includePaused);
    }

    /** Expire only unresolved decisions belonging to the requested profile. */
    public void expireStale(UUID profileId, int expireDays) {
        db.update("""
                update application_decisions
                set decision = 'EXPIRED', reviewed_at = now(), reviewed_by = 'SYSTEM',
                    review_reason = 'Expired after review window', updated_at = now()
                where profile_id = ? and decision = 'NEEDS_REVIEW'
                  and created_at < now() - make_interval(days => ?)
                """, profileId, expireDays);
    }

    /** Null means missing, soft-deleted, or not owned by the current profile. */
    public Map<String, Object> loadOwned(UUID profileId, UUID decisionId) {
        if (profileId == null || decisionId == null) return null;
        List<Map<String, Object>> rows = db.queryForList("""
                select d.id as "decisionId", d.profile_id as "profileId", d.job_id as "jobId",
                       d.match_score as "matchScore", d.recommendation as recommendation,
                       d.decision as decision, d.reason as reason,
                       d.application_mode as "applicationMode", d.created_at as "createdAt",
                       d.updated_at as "updatedAt", d.reviewed_at as "reviewedAt",
                       d.reviewed_by as "reviewedBy", d.review_reason as "reviewReason",
                       d.application_id, d.application_id as "applicationId",
                       j.title as "jobTitle", j.company_name_raw as "companyName",
                       j.location_raw as location, j.application_url as "applicationUrl",
                       j.remote_type as "remoteType", j.salary_min as "salaryMin",
                       j.salary_max as "salaryMax", j.salary_currency as "salaryCurrency",
                       j.status as "jobStatus",
                       case when j.filter_reasons is null then 'PASSED' else 'FAILED' end as "hardFilterOutcome",
                       coalesce(j.filter_reasons::text, '[]') as "hardFilterReasons",
                       (app.id is not null) as "applicationExists",
                       (j.last_seen_at < now() - interval '30 days') as "jobStale",
                       exists(select 1 from application_events ae
                              where ae.application_id = app.id and ae.type = 'PREPARATION') as "preparationExists"
                from application_decisions d
                join jobs j on j.id = d.job_id and j.deleted_at is null
                left join lateral (
                    select a.id from applications a
                    where a.profile_id = d.profile_id and a.job_id = d.job_id
                      and a.status not in ('FAILED','WITHDRAWN')
                    order by a.created_at desc limit 1
                ) app on true
                where d.id = ? and d.profile_id = ?
                """, decisionId, profileId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Owner- and expected-state-guarded transition for safe concurrent actions. */
    public boolean transition(UUID profileId, UUID decisionId, String from, String to) {
        return transition(profileId, decisionId, from, to, null, null);
    }

    public boolean transition(UUID profileId, UUID decisionId, String from, String to,
                              String actor, String reviewReason) {
        return db.update("""
                update application_decisions
                set decision = ?, reviewed_at = now(), reviewed_by = coalesce(?, reviewed_by),
                    review_reason = coalesce(?, review_reason), updated_at = now()
                where id = ? and profile_id = ? and decision = ?
                """, to, actor, reviewReason, decisionId, profileId, from) == 1;
    }

    /** Links only to an APPROVED decision belonging to this profile. */
    public boolean linkApplication(UUID profileId, UUID decisionId, UUID applicationId) {
        return db.update("""
                update application_decisions
                set application_id = ?, reviewed_at = coalesce(reviewed_at, now()), updated_at = now()
                where id = ? and profile_id = ? and decision = 'APPROVED'
                """, applicationId, decisionId, profileId) == 1;
    }

    /**
     * Persists the decision (upsert on profile+job) and increments the
     * metrics counter. Returns the decision result for the caller to act on.
     */
    private DecisionResult record(UUID profileId, UUID jobId,
                                  int matchScore, String recommendation,
                                  String applicationMode,
                                  String decision, String reason) {
        String effectiveDecision = decision;
        String effectiveReason = reason;
        try {
            int changed = db.update("""
                    INSERT INTO application_decisions
                        (id, profile_id, job_id, match_score, recommendation, application_mode, decision, reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (profile_id, job_id)
                    DO UPDATE SET match_score = EXCLUDED.match_score,
                                  recommendation = EXCLUDED.recommendation,
                                  application_mode = EXCLUDED.application_mode,
                                  decision = EXCLUDED.decision,
                                  reason = EXCLUDED.reason,
                                  created_at = now(), updated_at = now()
                    WHERE application_decisions.decision IN ('AUTO_APPLY','NEEDS_REVIEW','SKIP')
                    """, UuidV7.generate(), profileId, jobId, matchScore,
                    recommendation, applicationMode, decision, reason);
            if (changed == 0) {
                List<Map<String, Object>> persisted = db.queryForList("""
                        select decision, reason from application_decisions
                        where profile_id = ? and job_id = ?
                        """, profileId, jobId);
                if (!persisted.isEmpty()) {
                    effectiveDecision = String.valueOf(persisted.get(0).get("decision"));
                    Object storedReason = persisted.get(0).get("reason");
                    effectiveReason = storedReason == null ? reason : String.valueOf(storedReason);
                }
            }
        } catch (Exception e) {
            // Preserve best-effort recording on transient DB faults.
            log.warn("Failed to record application decision for profile={} job={}: {}",
                    profileId, jobId, e.getMessage());
        }
        metrics.decisionRecorded(effectiveDecision);
        log.info("Decision {} for profile={} job={} (score={}, mode={}, recommendation={}): {}",
                effectiveDecision, profileId, jobId, matchScore, applicationMode, recommendation, effectiveReason);
        return new DecisionResult(effectiveDecision, effectiveReason, matchScore);
    }
}
