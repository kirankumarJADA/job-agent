package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationDecisionService;
import com.personal.jobagent.apply.ApplyReadinessService;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.HardFilterResult;
import com.personal.jobagent.jobs.HardFilterService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.preferences.StandingAutoApplyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 3I Phase 8: Explicit opt-in standing autonomous submission authorization.
 *
 * <p>Evaluated strictly after Pass-1 browser preparation lands in {@code AWAITING_APPROVAL}.
 * Ordinary {@code CONTROLLED_AUTO} remains approval-gated unless the candidate has explicitly
 * enabled {@code standingAutoSubmit} in their active preference set AND every eligibility,
 * package-readiness, drift, prohibited-declaration, unresolved-question, duplicate, and
 * daily submit cap check passes cleanly.
 *
 * <p>Even after promotion to {@code PREPARED}, the backend pre-submit fence
 * ({@link PreSubmitAuthorizationService}) re-verifies every standing constraint immediately
 * before any browser submit click occurs.
 */
@Service
public class StandingAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(StandingAuthorizationService.class);

    public static final String STANDING_SUBMIT_QUOTA_KEY = "standing_auto_submit_daily";

    private static final Set<String> ELIGIBLE_APP_STATUSES = Set.of("READY_TO_APPLY", "APPLICATION_STARTED");
    private static final Set<String> SUBMIT_CAPABLE_PLANS = Set.of("GREENHOUSE", "ASHBY", "WORKDAY", "UNIVERSAL");
    private static final Set<String> FORBIDDEN_SUBMIT_STEPS = Set.of(
            "CLICK", "MOCK_SUBMIT", "REAL_SUBMIT_CLICK", "ASHBY_SUBMIT_CLICK",
            "WORKDAY_SUBMIT_CLICK", "UNIVERSAL_SUBMIT_CLICK");

    private final JdbcTemplate db;
    private final AutomationPlanRepository plans;
    private final ApplyReadinessService applyReadiness;
    private final ApplyDocumentSelector documentSelector;
    private final ExecutionPackageService executionPackages;
    private final UniversalBrowserPlanService universalPlans;
    private final HardFilterService hardFilters;
    private final PreferenceSetRepository preferenceSets;
    private final ApplicationDecisionService decisions;
    private final QuotaService quotaService;
    private final AuditLogWriter audit;
    private final ObjectMapper json;
    private final PostingLivenessService livenessService;

    @Autowired
    public StandingAuthorizationService(
            JdbcTemplate db,
            AutomationPlanRepository plans,
            ApplyReadinessService applyReadiness,
            ApplyDocumentSelector documentSelector,
            ExecutionPackageService executionPackages,
            @Autowired(required = false) UniversalBrowserPlanService universalPlans,
            HardFilterService hardFilters,
            PreferenceSetRepository preferenceSets,
            ApplicationDecisionService decisions,
            QuotaService quotaService,
            AuditLogWriter audit,
            ObjectMapper json,
            @Autowired(required = false) PostingLivenessService livenessService) {
        this.db = db;
        this.plans = plans;
        this.applyReadiness = applyReadiness;
        this.documentSelector = documentSelector;
        this.executionPackages = executionPackages;
        this.universalPlans = universalPlans;
        this.hardFilters = hardFilters;
        this.preferenceSets = preferenceSets;
        this.decisions = decisions;
        this.quotaService = quotaService;
        this.audit = audit;
        this.json = json == null ? new ObjectMapper() : json;
        this.livenessService = livenessService;
    }

    /**
     * Attempts to automatically promote a freshly prepared {@code AWAITING_APPROVAL} plan into
     * a standing-authorized submit plan ({@code PREPARED}, {@code submit_approved=true},
     * {@code authorizationMode="STANDING_AUTO_SUBMIT"}).
     *
     * @return {@code true} only if the plan was atomically promoted to {@code PREPARED}.
     */
    public boolean tryAutoAuthorizeAfterPreparation(UUID planId) {
        if (planId == null) {
            return false;
        }
        AutomationPlanRepository.PlanRow plan = plans.findById(planId).orElse(null);
        if (plan == null || !"AWAITING_APPROVAL".equals(plan.status())
                || plan.submitApproved()
                || plans.hasPreSubmitAttemptCheckpoint(planId)) {
            return false;
        }
        UUID profileId = plans.findProfileId(planId).orElse(null);
        if (profileId == null) {
            return false;
        }
        PreferenceSetRecord prefs = preferenceSets.findActiveByProfileId(profileId).orElse(null);
        StandingAutoApplyPolicy policy = prefs == null
                ? StandingAutoApplyPolicy.disabled()
                : StandingAutoApplyPolicy.fromExtraFilters(prefs.extraFilters());
        if (prefs == null || !"CONTROLLED_AUTO".equalsIgnoreCase(prefs.applicationMode()) || !policy.enabled()) {
            return false;
        }

        // Candidate explicitly opted into standing autonomous submission — every failure beyond here
        // records a structured audit trail explaining why automation deferred to AWAITING_APPROVAL.
        ApplicationDecisionService.RuleState ruleState = decisions.ruleState(profileId);
        if (ruleState.availability() != ApplicationDecisionService.RuleAvailability.CONFIGURED
                || ruleState.rule() == null || !ruleState.rule().autoApproveEnabled()) {
            return defer(planId, plan.applicationId(), "APPROVAL_RULE_DISABLED",
                    "Standing approval rule is disabled or unavailable.");
        }

        Map<String, Object> stored = plan.plan() == null ? Map.of() : plan.plan();
        String planType = asString(stored.get("planType"));
        if (planType == null || !SUBMIT_CAPABLE_PLANS.contains(planType)) {
            return defer(planId, plan.applicationId(), "UNSUPPORTED_ADAPTER",
                    "Adapter " + planType + " is not permitted for autonomous submission.");
        }
        String outcomeBlocker = checkPreparedOutcome(plan, stored, planType);
        if (outcomeBlocker != null) {
            String code = outcomeBlocker.startsWith("PROHIBITED_DECLARATION:")
                    ? "PROHIBITED_DECLARATION" : "HUMAN_REVIEW_REQUIRED";
            return defer(planId, plan.applicationId(), code, outcomeBlocker);
        }

        PlanTargetContext ctx = loadTargetContext(planId, profileId);
        if (ctx == null || ctx.job == null || ctx.jobDeleted || "ARCHIVED".equalsIgnoreCase(ctx.job.status())) {
            return defer(planId, plan.applicationId(), "JOB_UNAVAILABLE",
                    "Linked job posting is missing, deleted, or archived.");
        }
        if (ctx.jobStale) {
            return defer(planId, plan.applicationId(), "JOB_STALE",
                    "Linked job posting is stale and requires manual verification.");
        }
        if (ctx.appStatus == null || !ELIGIBLE_APP_STATUSES.contains(ctx.appStatus)) {
            return defer(planId, plan.applicationId(), "APPLICATION_STATUS_INELIGIBLE",
                    "Application status " + ctx.appStatus + " cannot enter autonomous submission.");
        }
        if (plans.hasOtherSubmittedOrFencedForJob(profileId, ctx.job.id(), planId)) {
            return defer(planId, plan.applicationId(), "DUPLICATE_SUBMISSION",
                    "Another application by this candidate for the same job is already submitted or in flight.");
        }
        String eligibilityErr = checkProfileWorkEligibility(ctx.workEligibilityJson);
        if (eligibilityErr != null) {
            return defer(planId, plan.applicationId(), "WORK_ELIGIBILITY_FAILED", eligibilityErr);
        }

        if (livenessService != null) {
            PostingLivenessService.LivenessResult liveness = livenessService.evaluateForPlan(planId);
            if (liveness.blocksExecution()) {
                List<Object> failureResult = new java.util.ArrayList<>();
                failureResult.add(Map.of(
                        "type", "LIVENESS_FAILED",
                        "code", liveness.code(),
                        "detail", liveness.detail()));
                plans.failClosedBeforeClaim(planId, "AWAITING_APPROVAL", failureResult);
                return false;
            }
        }

        ScoreCheck scoreCheck = checkMatchScore(profileId, ctx.job.id(), ruleState.rule().minScore(), policy.minScore());
        if (!scoreCheck.ok()) {
            return defer(planId, plan.applicationId(), scoreCheck.code(), scoreCheck.detail());
        }

        HardFilterResult strictFilter = hardFilters.evaluateStrict(
                ctx.job, prefs, policy.effectiveAllowedCountries(prefs));
        if (!strictFilter.passed()) {
            String msg = strictFilter.reasons().stream()
                    .map(HardFilterResult.FilterReason::message)
                    .findFirst().orElse("Strict preference constraints failed.");
            return defer(planId, plan.applicationId(), "STRICT_ELIGIBILITY_FAILED", msg);
        }
        if (!policy.matchesPermittedRole(ctx.job.title(), prefs)) {
            return defer(planId, plan.applicationId(), "ROLE_NOT_PERMITTED",
                    "Job title '" + ctx.job.title() + "' is outside permitted standing roles.");
        }

        Map<String, Object> readinessState;
        try {
            readinessState = applyReadiness.evaluate(profileId, plan.applicationId());
        } catch (RuntimeException e) {
            return defer(planId, plan.applicationId(), "READINESS_UNAVAILABLE",
                    "Package readiness could not be verified: " + e.getMessage());
        }
        if (!Boolean.TRUE.equals(readinessState.get("packageReady"))
                || hasDuplicates(readinessState.get("duplicates"))) {
            return defer(planId, plan.applicationId(), "PACKAGE_NOT_READY",
                    "Application package blockers or duplicates prevent standing submission.");
        }

        if ("UNIVERSAL".equals(planType) && universalPlans != null
                && universalPlans.countUnresolvedAnswers(profileId, ctx.job.id(), plan.applicationId()) > 0) {
            return defer(planId, plan.applicationId(), "UNRESOLVED_QUESTIONS",
                    "Unresolved discovered questions require candidate answers.");
        }

        String drift = PreSubmitAuthorizationService.detectPackageDrift(
                profileId, plan.applicationId(), ctx.job.id(), stored, documentSelector, executionPackages);
        if (drift != null) {
            return defer(planId, plan.applicationId(), "PACKAGE_DRIFT", drift);
        }
        String artifactErr = verifyPackageArtifacts(planId, profileId, plan.applicationId(), ctx.job.id(), stored, planType);
        if (artifactErr != null) {
            return defer(planId, plan.applicationId(), "ARTIFACT_VERIFICATION_FAILED", artifactErr);
        }

        QuotaService.ReservationResult reservation = quotaService.tryReserve(
                profileId, STANDING_SUBMIT_QUOTA_KEY, 1, policy.dailySubmitCap());
        if (!reservation.reserved()) {
            return defer(planId, plan.applicationId(), "DAILY_SUBMIT_LIMIT_REACHED",
                    "Standing daily submission limit reached (" + (int) reservation.used()
                            + "/" + (int) reservation.limit() + ").");
        }

        boolean authorized = plans.authorizeStandingSubmission(planId, planType);
        if (!authorized) {
            quotaService.release(profileId, STANDING_SUBMIT_QUOTA_KEY, 1, reservation.periodStart());
            return defer(planId, plan.applicationId(), "STATE_CONFLICT",
                    "Plan state changed concurrently before standing authorization could be committed.");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("applicationId", plan.applicationId().toString());
        details.put("planType", planType);
        details.put("authorizationMode", "STANDING_AUTO_SUBMIT");
        details.put("matchScore", scoreCheck.score());
        details.put("effectiveMinScore", scoreCheck.effectiveMinScore());
        details.put("dailyQuotaUsed", (int) reservation.used());
        details.put("dailyQuotaLimit", (int) reservation.limit());
        audit.write(new AuditEntry("SYSTEM", "STANDING_SUBMIT_AUTHORIZED", "AUTOMATION_PLAN",
                planId, null, details, null, UuidV7.generate()));
        log.info("Standing autonomous submission authorized for plan={} app={} score={}/{}",
                planId, plan.applicationId(), scoreCheck.score(), scoreCheck.effectiveMinScore());
        return true;
    }

    private String checkPreparedOutcome(AutomationPlanRepository.PlanRow plan, Map<String, Object> stored, String planType) {
        if (stored.get("steps") instanceof List<?> steps) {
            for (Object o : steps) {
                Map<String, Object> step = asMap(o);
                String type = asString(step.get("type"));
                if (FORBIDDEN_SUBMIT_STEPS.contains(type)) {
                    return "Plan already contains submit step " + type;
                }
                if ("POLICY_CHECK".equals(type) && "REAL_SUBMIT".equals(asString(asMap(step.get("params")).get("action")))) {
                    return "Plan contains REAL_SUBMIT policy step";
                }
            }
        }
        Map<String, Object> pkg = asMap(stored.get("package"));
        if ("GREENHOUSE".equals(planType) && hasNonEmptyList(pkg.get("requiredGaps"))) {
            return "Greenhouse package has unresolved required gaps";
        }

        List<Object> results = plan.result() == null ? List.of() : plan.result();
        Map<String, Object> matchedOutcome = null;
        for (Object item : results) {
            Map<String, Object> entry = asMap(item);
            String type = asString(entry.get("type"));
            if ("AUTHORIZATION_REFUSED".equals(type)) {
                return "Prior pre-submit authorization was refused before click";
            }
            if (Boolean.TRUE.equals(entry.get("accountSetupPending"))
                    || Boolean.TRUE.equals(entry.get("verificationPending"))
                    || Boolean.TRUE.equals(entry.get("credentialRequired"))
                    || Boolean.TRUE.equals(entry.get("captchaDetected"))
                    || Boolean.TRUE.equals(entry.get("mfaDetected"))
                    || Boolean.TRUE.equals(entry.get("requiresHuman"))) {
                return "Employer flow requires manual candidate authentication or security verification";
            }
            if (entry.get("requiredGaps") instanceof List<?> gaps && !gaps.isEmpty()) {
                for (Object gapObj : gaps) {
                    Map<String, Object> gap = asMap(gapObj);
                    if ("PROHIBITED_DECLARATION".equals(asString(gap.get("category")))
                            || Boolean.TRUE.equals(gap.get("prohibitedDeclaration"))) {
                        return "PROHIBITED_DECLARATION: Required declaration/certification control requires explicit candidate review";
                    }
                }
                return gaps.size() + " mandatory question(s) remain unresolved";
            }
            if (hasNonEmptyList(entry.get("validationErrors"))) {
                return "Form validation errors remain after preparation";
            }
            if (("UNIVERSAL".equals(planType) && "UNIVERSAL_OUTCOME".equals(type))
                    || ("ASHBY".equals(planType) && "ASHBY_OUTCOME".equals(type))
                    || ("WORKDAY".equals(planType)
                            && ("WORKDAY_OUTCOME".equals(type) || "WORKDAY_APPLICATION_OUTCOME".equals(type)))) {
                matchedOutcome = entry;
            }
        }
        if ("UNIVERSAL".equals(planType) || "ASHBY".equals(planType) || "WORKDAY".equals(planType)) {
            if (matchedOutcome == null) {
                return "No verified Pass-1 outcome payload recorded for " + planType;
            }
        }
        if ("GREENHOUSE".equals(planType)) {
            Map<String, Object> correlation = asMap(stored.get("correlation"));
            if (pkg.isEmpty() || correlation.isEmpty()
                    || !plan.id().toString().equals(asString(pkg.get("planId")))
                    || !plan.targetUrl().equals(asString(pkg.get("expectedUrl")))) {
                return "Greenhouse package correlation is incomplete";
            }
        }
        return null;
    }

    private String verifyPackageArtifacts(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                          Map<String, Object> stored, String planType) {
        Map<String, Object> pkg = asMap(stored.get("package"));
        boolean cvRequired = "GREENHOUSE".equals(planType) || "UNIVERSAL".equals(planType);
        String cvReason = verifyArtifact(planId, profileId, applicationId, jobId, "cv", pkg.get("cv"), cvRequired);
        if (cvReason != null) {
            return cvReason;
        }
        if ("GREENHOUSE".equals(planType) || "WORKDAY".equals(planType) || "UNIVERSAL".equals(planType)) {
            return verifyArtifact(planId, profileId, applicationId, jobId, "cover-letter", pkg.get("coverLetter"), false);
        }
        return null;
    }

    private String verifyArtifact(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                  String kind, Object artifactObj, boolean required) {
        Map<String, Object> meta = asMap(artifactObj);
        if (meta.isEmpty()) {
            return required ? "missing bound " + kind + " artifact" : null;
        }
        String versionStr = asString(meta.get("versionId"));
        String sha = asString(meta.get("sha256"));
        if (versionStr == null || sha == null) {
            return kind + " artifact is missing immutable versionId or sha256";
        }
        try {
            ExecutionPackageService.ArtifactBytes bytes = executionPackages.artifactBytes(
                    planId, profileId, applicationId, jobId, kind, UUID.fromString(versionStr));
            if (bytes == null || !sha.equals(bytes.sha256())) {
                return kind + " artifact checksum mismatch";
            }
            return null;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return kind + " artifact could not be verified: " + e.getMessage();
        }
    }

    private record ScoreCheck(boolean ok, Integer score, int effectiveMinScore, String code, String detail) {}

    private ScoreCheck checkMatchScore(UUID profileId, UUID jobId, int ruleMinScore, int policyMinScore) {
        int effectiveMin = Math.max(ruleMinScore, policyMinScore);
        List<Map<String, Object>> decisionRows = db.queryForList("""
                select decision, match_score from application_decisions
                where profile_id = ? and job_id = ?
                order by updated_at desc limit 1
                """, profileId, jobId);
        Integer score = null;
        if (!decisionRows.isEmpty()) {
            String decision = asString(decisionRows.get(0).get("decision"));
            if (decision != null && ("SKIP".equals(decision) || "REJECTED".equals(decision)
                    || "PAUSED".equals(decision) || "EXPIRED".equals(decision))) {
                return new ScoreCheck(false, null, effectiveMin, "DECISION_NOT_AUTO_ELIGIBLE",
                        "Existing decision state is " + decision);
            }
            Object raw = decisionRows.get(0).get("match_score");
            if (raw instanceof Number n) score = n.intValue();
        }
        if (score == null) {
            List<Integer> matchScores = db.query(
                    "select score from job_matches where profile_id = ? and job_id = ? limit 1",
                    (rs, n) -> (Integer) rs.getObject(1), profileId, jobId);
            if (!matchScores.isEmpty()) score = matchScores.get(0);
        }
        if (score == null) {
            return new ScoreCheck(false, null, effectiveMin, "MISSING_MATCH_SCORE",
                    "No persisted match score exists for standing verification.");
        }
        if (score < effectiveMin) {
            return new ScoreCheck(false, score, effectiveMin, "SCORE_BELOW_THRESHOLD",
                    "Match score " + score + " is below standing minimum " + effectiveMin + ".");
        }
        return new ScoreCheck(true, score, effectiveMin, null, null);
    }

    private String checkProfileWorkEligibility(String workEligibilityJson) {
        Map<String, Object> we = readJsonMap(workEligibilityJson);
        if (Boolean.FALSE.equals(we.get("rightToWork")) || Boolean.FALSE.equals(we.get("eligible"))) {
            return "Candidate work eligibility profile flags rightToWork/eligible as false.";
        }
        String visaStatus = asString(we.get("visaStatus"));
        if (visaStatus != null && ("NOT_ELIGIBLE".equalsIgnoreCase(visaStatus) || "NONE".equalsIgnoreCase(visaStatus))) {
            return "Candidate visaStatus does not permit autonomous submission.";
        }
        return null;
    }

    private record PlanTargetContext(String appStatus, JobRecord job, boolean jobDeleted, boolean jobStale,
                                     String workEligibilityJson) {}

    private PlanTargetContext loadTargetContext(UUID planId, UUID profileId) {
        List<PlanTargetContext> rows = db.query("""
                select a.status as app_status,
                       j.id as job_id, j.source_id, j.external_id, j.company_id, j.company_name_raw,
                       j.title, j.location_raw, j.city, j.country, j.remote_type,
                       j.employment_type, j.experience_level, j.salary_min, j.salary_max, j.salary_currency,
                       j.description_text, j.skills_extracted, j.application_url,
                       j.canonical_url, j.posted_at, j.status as job_status,
                       (j.deleted_at is not null) as job_deleted,
                       (j.last_seen_at is not null and j.last_seen_at < now() - interval '30 days') as job_stale,
                       coalesce(pr.work_eligibility::text, '{}') as work_eligibility_json
                from automation_plans p
                join applications a on a.id = p.application_id and a.profile_id = p.profile_id
                join jobs j on j.id = a.job_id
                join profiles pr on pr.id = p.profile_id
                where p.id = ? and p.profile_id = ?
                """, (rs, n) -> {
            Timestamp posted = rs.getTimestamp("posted_at");
            JobRecord job = new JobRecord(
                    (UUID) rs.getObject("job_id"),
                    (UUID) rs.getObject("source_id"),
                    rs.getString("external_id"),
                    (UUID) rs.getObject("company_id"),
                    rs.getString("company_name_raw"),
                    rs.getString("title"),
                    rs.getString("location_raw"),
                    rs.getString("city"),
                    rs.getString("country"),
                    rs.getString("remote_type"),
                    rs.getString("employment_type"),
                    rs.getString("experience_level"),
                    rs.getBigDecimal("salary_min"),
                    rs.getBigDecimal("salary_max"),
                    rs.getString("salary_currency"),
                    rs.getString("description_text"),
                    JdbcConversions.readStringArray(rs, "skills_extracted"),
                    rs.getString("application_url"),
                    rs.getString("canonical_url"),
                    posted == null ? null : posted.toInstant(),
                    rs.getString("job_status"));
            return new PlanTargetContext(
                    rs.getString("app_status"),
                    job,
                    rs.getBoolean("job_deleted"),
                    rs.getBoolean("job_stale"),
                    rs.getString("work_eligibility_json"));
        }, planId, profileId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean defer(UUID planId, UUID applicationId, String reasonCode, String detail) {
        if (audit != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("applicationId", applicationId == null ? "" : applicationId.toString());
            data.put("reasonCode", reasonCode);
            data.put("detail", detail == null ? "" : detail);
            audit.write(new AuditEntry("SYSTEM", "STANDING_SUBMIT_DEFERRED", "AUTOMATION_PLAN",
                    planId, null, data, null, UuidV7.generate()));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (!(obj instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static boolean hasNonEmptyList(Object raw) {
        return raw instanceof List<?> list && !list.isEmpty();
    }

    private static boolean hasDuplicates(Object raw) {
        if (raw instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (raw instanceof Map<?, ?> map) {
            Object status = map.get("status");
            if (status != null && "DUPLICATE".equalsIgnoreCase(status.toString())) {
                return true;
            }
            return hasNonEmptyList(map.get("items")) || hasNonEmptyList(map.get("matches"));
        }
        return false;
    }

    private static String asString(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() || "null".equals(s.toLowerCase(Locale.ROOT)) ? null : s;
    }
}
