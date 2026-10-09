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
 *       review. {@code ASSISTED} uses a conservative built-in floor (≥
 *       {@value #HIGH_CONFIDENCE_THRESHOLD}) that a configured rule may
 *       raise or lower. {@code CONTROLLED_AUTO} auto-applies only an
 *       APPLY-level match that an explicitly enabled, successfully loaded
 *       rule authorises.</li>
 *   <li><b>Quota</b> — if the profile's daily application quota is exhausted,
 *       the decision falls to NEEDS_REVIEW regardless of mode, so usage
 *       cannot runaway overnight. The quota is checked before any rule can
 *       authorise automatic approval.</li>
 *   <li><b>Per-user auto-approval rule</b> (Phase 7) — a rule can switch
 *       automatic approval off and raise or lower the score threshold. It can
 *       never bypass a hard stop, a required-field failure or an
 *       artifact-integrity failure.</li>
 * </ol>
 *
 * <p><b>Fail-closed rule handling (Phase 7.2):</b> resolving the owner's rule
 * is tri-state — {@code CONFIGURED}, {@code ABSENT} or {@code UNREADABLE} — so
 * "no rule" is never conflated with "a rule we could not read".
 *
 * <ul>
 *   <li>{@code UNREADABLE} (a database or query failure, a missing column, or
 *       a threshold outside 1–100) always queues for human review. It never
 *       falls back to a default that could widen auto-approval.</li>
 *   <li>An explicitly {@code DISABLED} rule queues for human review in both
 *       automatic modes.</li>
 *   <li>{@code CONTROLLED_AUTO} with no rule at all queues for human review:
 *       "auto-apply everything" is precisely the behaviour that must require
 *       an explicit opt-in. This is the Phase 7.2 behaviour change — before
 *       it, an absent rule silently auto-applied every APPLY match.</li>
 *   <li>{@code ASSISTED} with no rule keeps its Phase 5 default floor of
 *       {@value #HIGH_CONFIDENCE_THRESHOLD}, which is a conservative,
 *       documented property of the mode rather than a silent widening.</li>
 * </ul>
 *
 * <p>Every decision is persisted in {@code application_decisions} for the
 * audit trail and for Phase 6 (review queue) and Phase 7 (auto-approval
 * rules) to consume. The table is upserted on {@code (profile_id, job_id)},
 * so event replays are idempotent.
 *
 * <p><b>Owner isolation:</b> the decision is always scoped to one profile,
 * the rule lookup is filtered by {@code profile_id}, and the quota check is
 * per-profile.
 */
@Service
public class ApplicationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationDecisionService.class);

    /**
     * In ASSISTED mode with no configured rule, only matches at or above this
     * score auto-apply. Matches between APPLY_THRESHOLD (70) and this value
     * queue for review. A configured rule may raise or lower it.
     */
    static final int HIGH_CONFIDENCE_THRESHOLD = 85;

    /** Accepted range for a rule's minimum score; anything else fails closed. */
    static final int MIN_SCORE = 1;
    static final int MAX_SCORE = 100;

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

        // ── 4. Per-user auto-approval rule (Phase 7) ──
        // The rule can only TOGGLE automatic application creation and RAISE or
        // LOWER the score threshold; it can never bypass a hard stop, a
        // required-field failure or an artifact-integrity failure — those are
        // enforced downstream at plan build, worker execution and the
        // submit-approval precondition, and no decision recorded here changes
        // them.
        RuleLookup lookup = lookupRule(profileId);

        // A rule we could not read must never widen auto-approval. Fail closed
        // to human review rather than silently reverting to a default that
        // could auto-approve a match the owner disabled.
        if (lookup.availability() == RuleAvailability.UNREADABLE) {
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Approval rule could not be loaded; awaiting human review");
        }

        // An explicit opt-out disables AUTO_APPLY in every automatic mode.
        if (lookup.availability() == RuleAvailability.CONFIGURED
                && !lookup.rule().autoApproveEnabled()) {
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Automatic approval disabled by user rule");
        }

        // ── 5. ASSISTED mode: a conservative floor applies ──
        // With no rule the mode's own high-confidence floor (85) is the bar,
        // which is stricter than the APPLY threshold and is the documented
        // Phase 5 default. A configured, enabled rule may move it.
        if ("ASSISTED".equals(mode)) {
            int assistedThreshold = lookup.availability() == RuleAvailability.CONFIGURED
                    ? lookup.rule().minScore() : HIGH_CONFIDENCE_THRESHOLD;
            if (matchScore >= assistedThreshold) {
                return record(profileId, jobId, matchScore, recommendation, mode,
                        "AUTO_APPLY", "High-confidence match in assisted mode (score " + matchScore
                                + " >= " + assistedThreshold + ")");
            }
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Score " + matchScore + " below auto-apply threshold ("
                            + assistedThreshold + ") for assisted mode");
        }

        // ── 6. CONTROLLED_AUTO requires an explicitly enabled, loaded rule ──
        // No rule means no authorisation: "auto-apply everything" must be an
        // explicit opt-in, never the silent default.
        if (lookup.availability() == RuleAvailability.ABSENT) {
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Controlled-auto mode requires an enabled approval rule; "
                            + "none is configured");
        }
        if (matchScore < lookup.rule().minScore()) {
            return record(profileId, jobId, matchScore, recommendation, mode,
                    "NEEDS_REVIEW", "Score " + matchScore + " below user threshold ("
                            + lookup.rule().minScore() + ")");
        }
        return record(profileId, jobId, matchScore, recommendation, mode,
                "AUTO_APPLY", "Controlled-auto mode with an enabled user rule (score "
                        + matchScore + " >= " + lookup.rule().minScore() + ")");
    }

    // ── Phase 7: per-user auto-approval rules ────────────────────────

    /** The owner's configurable auto-approval rule. Null = Phase 5 defaults. */
    public record UserApprovalRule(boolean autoApproveEnabled, int minScore) {}

    /**
     * Whether a profile has a usable rule, no rule at all, or one we could not
     * read. Keeping {@code ABSENT} and {@code UNREADABLE} separate is what lets
     * the decision engine fail closed on the latter without treating it as an
     * explicit (and therefore trustworthy) absence.
     */
    enum RuleAvailability { CONFIGURED, ABSENT, UNREADABLE }

    /** The resolved rule plus how it was resolved. */
    private record RuleLookup(RuleAvailability availability, UserApprovalRule rule) {
        static RuleLookup of(UserApprovalRule rule) {
            return new RuleLookup(RuleAvailability.CONFIGURED, rule);
        }
        static RuleLookup absent() {
            return new RuleLookup(RuleAvailability.ABSENT, null);
        }
        static RuleLookup unreadable() {
            return new RuleLookup(RuleAvailability.UNREADABLE, null);
        }
    }

    private static final String RULE_COLUMNS = "auto_approve_enabled, min_score";

    /**
     * Resolves the owner's rule, distinguishing absence from unreadability.
     * A query failure, a missing/unparseable threshold, or a threshold outside
     * 1–100 is reported as {@code UNREADABLE} so callers fail closed.
     */
    private RuleLookup lookupRule(UUID profileId) {
        try {
            List<Map<String, Object>> rows = db.queryForList(
                    "select " + RULE_COLUMNS + " from user_approval_rules where profile_id = ?",
                    profileId);
            if (rows.isEmpty()) return RuleLookup.absent();
            Object rawScore = rows.get(0).get("min_score");
            if (!(rawScore instanceof Number number)) {
                log.warn("Approval rule for profile {} has no readable threshold; failing closed",
                        profileId);
                return RuleLookup.unreadable();
            }
            int minScore = number.intValue();
            if (minScore < MIN_SCORE || minScore > MAX_SCORE) {
                log.warn("Approval rule for profile {} has an out-of-range threshold ({}); "
                        + "failing closed", profileId, minScore);
                return RuleLookup.unreadable();
            }
            return RuleLookup.of(new UserApprovalRule(
                    Boolean.TRUE.equals(rows.get(0).get("auto_approve_enabled")), minScore));
        } catch (Exception e) {
            // A rule we cannot read must never widen auto-apply: report it as
            // UNREADABLE so every caller fails closed to human review.
            log.warn("Could not load approval rule for profile {}: {}; failing closed",
                    profileId, e.getMessage());
            return RuleLookup.unreadable();
        }
    }

    /**
     * Reads the owner's rule for the API. Null when none is configured (or when
     * the stored rule is unreadable — decisions then fail closed to review).
     */
    public UserApprovalRule ruleFor(UUID profileId) {
        return lookupRule(profileId).rule();
    }

    /**
     * Creates or updates the owner's rule (upsert on profile_id). Returns the
     * persisted rule. Validation happens here so every caller gets the same
     * guarantees: the score must be a whole number 1–100.
     */
    public UserApprovalRule saveRule(UUID profileId, Boolean autoApproveEnabled, Integer minScore) {
        UserApprovalRule existing = lookupRule(profileId).rule();
        boolean enabled = autoApproveEnabled != null ? autoApproveEnabled
                : existing != null && existing.autoApproveEnabled();
        int score = minScore != null ? minScore
                : existing != null ? existing.minScore() : HIGH_CONFIDENCE_THRESHOLD;
        if (score < MIN_SCORE || score > MAX_SCORE) {
            throw new IllegalArgumentException("min_score must be between 1 and 100");
        }
        db.update("""
                insert into user_approval_rules (id, profile_id, auto_approve_enabled, min_score, updated_at)
                values (?, ?, ?, ?, now())
                on conflict (profile_id) do update set
                    auto_approve_enabled = excluded.auto_approve_enabled,
                    min_score = excluded.min_score,
                    updated_at = now()
                """, UuidV7.generate(), profileId, enabled, score);
        return new UserApprovalRule(enabled, score);
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
                       'PASSED' as hard_filter_outcome,
                       '[]' as hard_filter_reasons,
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
                       'PASSED' as "hardFilterOutcome",
                       '[]' as "hardFilterReasons",
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
                                  updated_at = now()
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
