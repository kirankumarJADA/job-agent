package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlanRepository;
import com.personal.jobagent.automation.StandingAuthorizationService;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.preferences.StandingAutoApplyPolicy;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /api/v1/approval-rules — the owner's configurable
 * auto-approval rule (Phase 7).
 *
 * <p>GET reports the rule's <b>availability</b>, which is the distinction
 * Phase 7.3 exists to make:
 *
 * <ul>
 *   <li>{@code CONFIGURED} — a valid saved rule was loaded;</li>
 *   <li>{@code ABSENT} — no custom rule exists;</li>
 *   <li>{@code UNREADABLE} — the rule could not be reliably retrieved or
 *       validated, so the decision engine is failing closed. This is returned
 *       as <b>503</b>, never as a successful response describing an
 *       unconfigured rule: a database or validation error reported as "no rule
 *       configured" would tell the owner their settings are simply absent when
 *       in fact they exist and are being ignored.</li>
 * </ul>
 *
 * <p>PUT upserts the rule. The rule can toggle automatic application creation
 * and set a minimum score — nothing more: it can never bypass a hard stop, a
 * required-field failure, an artifact-integrity failure, the daily quota, or
 * any other safety gate; those live downstream of this table and are
 * unaffected by it.
 *
 * <p>Both operations are owner-scoped from the authenticated identity. No id
 * in a request body is ever treated as an identifier. The {@code applicationMode}
 * field mirrors what {@link ApplicationDecisionService} will actually do, so a
 * client can describe the real default behaviour for a user without a rule.
 */
@RestController
@RequestMapping("/api/v1/approval-rules")
public class ApprovalRulesController {

    /**
     * Score applied in ASSISTED mode when no rule is configured. Sourced from
     * the decision engine so the API can never disagree with it.
     */
    private static final int ASSISTED_FLOOR = ApplicationDecisionService.HIGH_CONFIDENCE_THRESHOLD;

    private final ApplicationDecisionService decisions;
    private final AuditLogWriter audit;
    private final OwnerContext ownerContext;
    private final QuotaService quotas;
    private final com.personal.jobagent.apply.ApplyPackageGuard packageGuard;
    private final PreferenceSetRepository preferenceSets;
    private final AutomationPlanRepository automationPlans;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalRulesController(ApplicationDecisionService decisions,
                                   AuditLogWriter audit,
                                   OwnerContext ownerContext,
                                   QuotaService quotas,
                                   org.springframework.beans.factory.ObjectProvider<com.personal.jobagent.apply.ApplyPackageGuard> packageGuard,
                                   org.springframework.beans.factory.ObjectProvider<PreferenceSetRepository> preferenceSets,
                                   org.springframework.beans.factory.ObjectProvider<AutomationPlanRepository> automationPlans) {
        this.decisions = decisions;
        this.audit = audit;
        this.ownerContext = ownerContext;
        this.quotas = quotas;
        this.packageGuard = packageGuard == null ? null : packageGuard.getIfAvailable();
        this.preferenceSets = preferenceSets == null ? null : preferenceSets.getIfAvailable();
        this.automationPlans = automationPlans == null ? null : automationPlans.getIfAvailable();
    }

    public ApprovalRulesController(ApplicationDecisionService decisions,
                                   AuditLogWriter audit,
                                   OwnerContext ownerContext,
                                   QuotaService quotas,
                                   org.springframework.beans.factory.ObjectProvider<com.personal.jobagent.apply.ApplyPackageGuard> packageGuard) {
        this(decisions, audit, ownerContext, quotas, packageGuard, null, null);
    }

    public ApprovalRulesController(ApplicationDecisionService decisions,
                                   AuditLogWriter audit,
                                   OwnerContext ownerContext,
                                   QuotaService quotas) {
        this(decisions, audit, ownerContext, quotas, null, null, null);
    }

    public record RuleRequest(
            Boolean autoApproveEnabled,
            Integer minScore,
            Boolean standingAutoSubmitEnabled,
            Integer standingMinScore,
            Integer dailySubmitCap,
            List<String> allowedCountries,
            List<String> permittedRoles
    ) {
        public RuleRequest(Boolean autoApproveEnabled, Integer minScore) {
            this(autoApproveEnabled, minScore, null, null, null, null, null);
        }

        boolean hasStandingUpdates() {
            return standingAutoSubmitEnabled != null
                    || standingMinScore != null
                    || dailySubmitCap != null
                    || allowedCountries != null
                    || permittedRoles != null;
        }
    }

    @GetMapping
    public ResponseEntity<?> get() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }

        ApplicationDecisionService.RuleState state = decisions.ruleState(profileId);
        String applicationMode = decisions.effectiveApplicationMode(profileId);

        if (state.availability() == ApplicationDecisionService.RuleAvailability.UNREADABLE) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("availability", state.availability().name());
            body.put("reason", state.unreadableCause() != null ? state.unreadableCause().name() : null);
            body.put("message", unreadableMessage(state.unreadableCause()));
            body.put("applicationMode", applicationMode);
            body.put("assistedFloor", ASSISTED_FLOOR);
            // Deliberately no autoApproveEnabled/minScore: there are no settings
            // we can honestly report, and echoing stale defaults would invite a
            // client to treat them as loaded state.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        Map<String, Object> body = readableBody(state, applicationMode);
        if (preferenceSets != null) {
            PreferenceSetRecord prefs = preferenceSets.findActiveByProfileId(profileId).orElse(null);
            StandingAutoApplyPolicy policy = prefs == null
                    ? StandingAutoApplyPolicy.disabled()
                    : StandingAutoApplyPolicy.fromExtraFilters(prefs.extraFilters());
            QuotaService.QuotaCheckResult submitQuota = quotas.check(
                    profileId, StandingAuthorizationService.STANDING_SUBMIT_QUOTA_KEY);
            int cap = policy.dailySubmitCap();
            int remaining = Math.max(0, cap - (int) submitQuota.used());
            Map<String, Object> standingMap = new LinkedHashMap<>(policy.toMap());
            standingMap.put("dailySubmitQuota", Map.of(
                    "used", submitQuota.used(),
                    "limit", cap,
                    "remaining", remaining));
            body.put("standingAutoSubmit", standingMap);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * A readable rule. {@code configured} is retained for existing consumers;
     * it is exactly {@code availability == CONFIGURED}.
     */
    private static Map<String, Object> readableBody(ApplicationDecisionService.RuleState state,
                                                    String applicationMode) {
        boolean configured = state.availability() == ApplicationDecisionService.RuleAvailability.CONFIGURED;
        ApplicationDecisionService.UserApprovalRule rule = state.rule();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("availability", state.availability().name());
        body.put("configured", configured);
        body.put("autoApproveEnabled", configured && rule.autoApproveEnabled());
        body.put("minScore", configured ? rule.minScore() : ASSISTED_FLOOR);
        body.put("applicationMode", applicationMode);
        body.put("assistedFloor", ASSISTED_FLOOR);
        return body;
    }

    /** Bounded, user-safe explanation — never the raw database error. */
    private static String unreadableMessage(ApplicationDecisionService.RuleUnavailableCause cause) {
        if (cause == ApplicationDecisionService.RuleUnavailableCause.INVALID_THRESHOLD) {
            return "Your saved approval rule has an invalid minimum score, so automatic approval is "
                    + "paused for safety and matches wait for your review.";
        }
        return "Your approval rule could not be read, so automatic approval is paused for safety "
                + "and matches wait for your review.";
    }

    @PutMapping
    public ResponseEntity<?> put(@RequestBody RuleRequest request, HttpServletRequest httpRequest) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        try {
            if (request.standingMinScore() != null
                    && (request.standingMinScore() < 1 || request.standingMinScore() > 100)) {
                return ResponseEntity.badRequest().body(Map.of("error", "standingMinScore must be between 1 and 100"));
            }
            if (request.dailySubmitCap() != null
                    && (request.dailySubmitCap() < 1 || request.dailySubmitCap() > StandingAutoApplyPolicy.MAX_DAILY_SUBMIT_CAP)) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "dailySubmitCap must be between 1 and " + StandingAutoApplyPolicy.MAX_DAILY_SUBMIT_CAP));
            }

            ApplicationDecisionService.RuleState before = decisions.ruleState(profileId);
            ApplicationDecisionService.UserApprovalRule saved =
                    decisions.saveRule(profileId, request.autoApproveEnabled(), request.minScore());

            StandingAutoApplyPolicy savedStanding = null;
            boolean standingTightened = false;
            if (request.hasStandingUpdates()) {
                if (preferenceSets == null) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(Map.of("error", "preferences repository is unavailable"));
                }
                PreferenceSetRecord existing = preferenceSets.findActiveByProfileId(profileId).orElse(null);
                if (existing == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "an active preference set is required to update standing auto-submit settings"));
                }
                StandingAutoApplyPolicy prev = StandingAutoApplyPolicy.fromExtraFilters(existing.extraFilters());
                boolean nextEnabled = request.standingAutoSubmitEnabled() != null
                        ? request.standingAutoSubmitEnabled() : prev.enabled();
                int nextScore = request.standingMinScore() != null
                        ? request.standingMinScore() : prev.minScore();
                int nextCap = request.dailySubmitCap() != null
                        ? request.dailySubmitCap() : prev.dailySubmitCap();
                List<String> nextCountries = request.allowedCountries() != null
                        ? request.allowedCountries() : prev.allowedCountries();
                List<String> nextRoles = request.permittedRoles() != null
                        ? request.permittedRoles() : prev.permittedRoles();
                savedStanding = new StandingAutoApplyPolicy(
                        nextEnabled, nextScore, nextCap, nextCountries, nextRoles, prev.requireConfirmedSponsorship());
                standingTightened = (!nextEnabled && prev.enabled())
                        || nextScore > prev.minScore()
                        || request.allowedCountries() != null
                        || request.permittedRoles() != null;

                Map<String, Object> nextExtra = new LinkedHashMap<>(
                        existing.extraFilters() != null ? existing.extraFilters() : Map.of());
                nextExtra.put(StandingAutoApplyPolicy.EXTRA_FILTERS_KEY, savedStanding.toMap());
                PreferenceSetRecord updatedPrefs = new PreferenceSetRecord(
                        existing.id(), existing.profileId(), existing.titles(), existing.keywordsInclude(),
                        existing.keywordsExclude(), existing.requiredSkills(), existing.locationsAllowed(),
                        existing.remoteTypes(), existing.employmentTypes(), existing.experienceLevels(),
                        existing.salaryMinGbp(), existing.sponsorshipPolicy(), existing.companySizePref(),
                        existing.industryPref(), existing.applicationMode(), existing.scoringWeights(),
                        nextExtra, existing.isActive());
                preferenceSets.update(existing.id(), updatedPrefs);
            }

            boolean tightened = !saved.autoApproveEnabled()
                    || standingTightened
                    || (before != null && before.availability() == ApplicationDecisionService.RuleAvailability.CONFIGURED
                        && before.rule() != null && saved.minScore() > before.rule().minScore());
            if (tightened) {
                if (packageGuard != null) {
                    packageGuard.invalidateForProfile(profileId, "approval rule disabled or tightened");
                } else if (automationPlans != null) {
                    automationPlans.invalidateApprovalsForProfile(profileId);
                }
            }

            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("autoApproveEnabled", saved.autoApproveEnabled());
            auditDetails.put("minScore", saved.minScore());
            if (savedStanding != null) {
                auditDetails.put("standingAutoSubmitEnabled", savedStanding.enabled());
                auditDetails.put("standingMinScore", savedStanding.minScore());
                auditDetails.put("dailySubmitCap", savedStanding.dailySubmitCap());
            }
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "APPROVAL_RULE_UPDATED", "PROFILE",
                    profileId, null, auditDetails,
                    httpRequest.getRemoteAddr(), UuidV7.generate()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("availability", ApplicationDecisionService.RuleAvailability.CONFIGURED.name());
            body.put("configured", true);
            body.put("autoApproveEnabled", saved.autoApproveEnabled());
            body.put("minScore", saved.minScore());
            if (savedStanding != null) {
                body.put("standingAutoSubmit", savedStanding.toMap());
            }
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Run-history summary: how many matches the decision engine has
     * considered today and over the last 7 days, broken down by how each one
     * resolved, plus today's daily-application-quota usage. A read of the
     * engine's own persisted decisions and quota counters — never a separate
     * tracking mechanism, so it can never disagree with what actually happened.
     */
    @GetMapping("/activity")
    public ResponseEntity<?> activity() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        ApplicationDecisionService.ActivitySummary summary = decisions.activitySummary(profileId);
        QuotaService.QuotaCheckResult quota = quotas.check(profileId, "application_daily");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("today", windowBody(summary.today()));
        body.put("last7Days", windowBody(summary.last7Days()));
        Map<String, Object> quotaBody = new LinkedHashMap<>();
        quotaBody.put("used", quota.used());
        quotaBody.put("limit", quota.limit());
        quotaBody.put("remaining", quota.remaining());
        body.put("dailyApplicationQuota", quotaBody);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> windowBody(ApplicationDecisionService.ActivityWindow window) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("considered", window.considered());
        body.put("byDecision", window.byDecision());
        return body;
    }
}
