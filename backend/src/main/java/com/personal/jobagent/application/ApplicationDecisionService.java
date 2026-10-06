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
                             String decision, String reason, java.time.Instant createdAt) {}

    /**
     * Lazily expires NEEDS_REVIEW items older than the configured window, then
     * lists the owner's open review items (NEEDS_REVIEW and PAUSED) with the
     * job details needed to act on them. Owner-scoped by profile_id.
     */
    public List<ReviewItem> listForReview(UUID profileId, int expireDays) {
        expireStale(profileId, expireDays);
        return db.query("""
                select d.id, d.job_id, j.title, j.company_name_raw, j.location_raw,
                       j.application_url, d.match_score, d.decision, d.reason, d.created_at
                from application_decisions d
                join jobs j on j.id = d.job_id
                where d.profile_id = ? and d.decision = 'NEEDS_REVIEW'
                order by d.created_at desc
                """, (rs, n) -> new ReviewItem(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("job_id"),
                rs.getString("title"), rs.getString("company_name_raw"),
                rs.getString("location_raw"), rs.getString("application_url"),
                (Integer) rs.getObject("match_score"), rs.getString("decision"),
                rs.getString("reason"),
                rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant() : null),
                profileId);
    }

    /** Moves unreviewed items past the expiry window to EXPIRED (idempotent). */
    public void expireStale(UUID profileId, int expireDays) {
        db.update("""
                update application_decisions
                set decision = 'EXPIRED', reviewed_at = now()
                where profile_id = ? and decision = 'NEEDS_REVIEW'
                  and created_at < now() - make_interval(days => ?)
                """, profileId, expireDays);
    }

    /** Loads one owner-scoped decision with its job. Null when not found/not owned. */
    public Map<String, Object> loadOwned(UUID profileId, UUID decisionId) {
        List<Map<String, Object>> rows = db.queryForList("""
                select d.id, d.profile_id, d.job_id, d.match_score, d.decision, d.reason,
                       d.application_mode, d.created_at, d.application_id,
                       j.title, j.company_name_raw, j.location_raw, j.application_url,
                       j.remote_type, j.salary_min, j.salary_max, j.salary_currency
                from application_decisions d
                join jobs j on j.id = d.job_id
                where d.id = ? and d.profile_id = ?
                """, decisionId, profileId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Guarded lifecycle transition; returns false when the row moved already. */
    public boolean transition(UUID profileId, UUID decisionId, String from, String to) {
        return db.update("""
                update application_decisions
                set decision = ?, reviewed_at = now()
                where id = ? and profile_id = ? and decision = ?
                """, to, decisionId, profileId, from) == 1;
    }

    /** Links the application created by an approval to the decision row. */
    public void linkApplication(UUID profileId, UUID decisionId, UUID applicationId) {
        db.update("""
                update application_decisions
                set application_id = ?, reviewed_at = now()
                where id = ? and profile_id = ?
                """, applicationId, decisionId, profileId);
    }

    /**
     * Persists the decision (upsert on profile+job) and increments the
     * metrics counter. Returns the decision result for the caller to act on.
     */
    private DecisionResult record(UUID profileId, UUID jobId,
                                  int matchScore, String recommendation,
                                  String applicationMode,
                                  String decision, String reason) {
        try {
            db.update("""
                    INSERT INTO application_decisions
                        (id, profile_id, job_id, match_score, recommendation,
                         application_mode, decision, reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (profile_id, job_id)
                    DO UPDATE SET match_score = EXCLUDED.match_score,
                                  recommendation = EXCLUDED.recommendation,
                                  application_mode = EXCLUDED.application_mode,
                                  decision = EXCLUDED.decision,
                                  reason = EXCLUDED.reason,
                                  created_at = now()
                    """, UuidV7.generate(), profileId, jobId, matchScore,
                    recommendation, applicationMode, decision, reason);
        } catch (Exception e) {
            // Decision recording is best-effort: a DB hiccup must not block
            // the pipeline. The decision itself is still returned.
            log.warn("Failed to record application decision for profile={} job={}: {}",
                    profileId, jobId, e.getMessage());
        }

        metrics.decisionRecorded(decision);
        log.info("Decision {} for profile={} job={} (score={}, mode={}, recommendation={}): {}",
                decision, profileId, jobId, matchScore, applicationMode, recommendation, reason);
        return new DecisionResult(decision, reason, matchScore);
    }
}
