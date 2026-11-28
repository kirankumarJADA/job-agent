package com.personal.jobagent.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationDecisionService;
import com.personal.jobagent.apply.ApplyReadinessService;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.HardFilterResult;
import com.personal.jobagent.jobs.HardFilterService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.track.EmployerDomainPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared authoritative backend pre-submit authorization gate and atomic fence.
 *
 * <p>Every submit-capable ATS adapter ({@code GREENHOUSE}, {@code ASHBY},
 * {@code WORKDAY}, {@code UNIVERSAL}) converges here immediately before an
 * irreversible browser submit click. Non-submit adapters ({@code ZOHO_RECRUIT},
 * {@code LEVER}, {@code INSPECTION}) are always refused.
 *
 * <p>Fails closed ({@code 503}) whenever any required repository, readiness,
 * approval-rule, or database dependency is unavailable or in maintenance mode,
 * and refuses ({@code 409}) with machine-readable reason codes on any missing
 * approval, withdrawn approval, duplicate attempt, ownership/correlation
 * mismatch, target domain drift, readiness/package drift, or standing
 * eligibility/permission failure.
 */
@Service
public class PreSubmitAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(PreSubmitAuthorizationService.class);

    private static final Map<String, String> ADAPTER_SUBMIT_STEP = Map.of(
            "GREENHOUSE", "REAL_SUBMIT_CLICK",
            "ASHBY", "ASHBY_SUBMIT_CLICK",
            "WORKDAY", "WORKDAY_SUBMIT_CLICK",
            "UNIVERSAL", "UNIVERSAL_SUBMIT_CLICK"
    );

    private static final Set<String> ELIGIBLE_APPLICATION_STATUSES = Set.of(
            "READY_TO_APPLY",
            "APPLICATION_STARTED",
            "OTP_PENDING"
    );

    private static final Set<String> ASSISTED_HARD_CONSTRAINTS = Set.of(
            "SALARY_MIN",
            "KEYWORDS_EXCLUDE",
            "SPONSORSHIP",
            "COMPANY_FILTER"
    );

    public record AuthorizationOutcome(
            boolean authorized,
            int httpStatus,
            String code,
            String error,
            Map<String, Object> details
    ) {
        public static AuthorizationOutcome ok(Map<String, Object> details) {
            return new AuthorizationOutcome(true, 204, "PRE_SUBMIT_AUTHORIZED", null, details);
        }

        public static AuthorizationOutcome notFound() {
            return new AuthorizationOutcome(false, 404, "PLAN_NOT_FOUND", "automation plan not found", Map.of());
        }

        public static AuthorizationOutcome conflict(String code, String error) {
            return new AuthorizationOutcome(false, 409, code, error, Map.of());
        }

        public static AuthorizationOutcome unavailable(String code, String error) {
            return new AuthorizationOutcome(false, 503, code, error, Map.of());
        }
    }

    private final JdbcTemplate db;
    private final AutomationPlanRepository plans;
    private final ApplyReadinessService applyReadiness;
    private final ApplyDocumentSelector documentSelector;
    private final ExecutionPackageService executionPackages;
    private final HardFilterService hardFilters;
    private final PreferenceSetRepository preferenceSets;
    private final ApplicationDecisionService decisions;
    private final AuditLogWriter audit;
    private final ObjectMapper json;
    private final MaintenanceModeService maintenanceMode;
    private final TransactionTemplate txTemplate;
    private final boolean allowLocalTestHosts;

    @Autowired
    public PreSubmitAuthorizationService(
            JdbcTemplate db,
            AutomationPlanRepository plans,
            ApplyReadinessService applyReadiness,
            ApplyDocumentSelector documentSelector,
            ExecutionPackageService executionPackages,
            HardFilterService hardFilters,
            PreferenceSetRepository preferenceSets,
            ApplicationDecisionService decisions,
            AuditLogWriter audit,
            ObjectMapper json,
            PlatformTransactionManager txManager,
            @Autowired(required = false) MaintenanceModeService maintenanceMode,
            @Value("${app.sandbox.allow-local-test-hosts:false}") boolean allowLocalTestHosts) {
        this.db = db;
        this.plans = plans;
        this.applyReadiness = applyReadiness;
        this.documentSelector = documentSelector;
        this.executionPackages = executionPackages;
        this.hardFilters = hardFilters;
        this.preferenceSets = preferenceSets;
        this.decisions = decisions;
        this.audit = audit;
        this.json = json == null ? new ObjectMapper() : json;
        this.maintenanceMode = maintenanceMode != null ? maintenanceMode : new MaintenanceModeService(false);
        this.allowLocalTestHosts = allowLocalTestHosts;
        if (txManager != null) {
            TransactionTemplate template = new TransactionTemplate(txManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            this.txTemplate = template;
        } else {
            this.txTemplate = null;
        }
    }

    /**
     * Authorizes and atomically persists the single-shot {@code PRE_SUBMIT_ATTEMPTING}
     * fence for a submit-capable automation plan under a PostgreSQL plan row lock.
     */
    public AuthorizationOutcome authorizeAndFence(
            UUID planId,
            int index,
            String stepStatus,
            Map<String, Object> resultMap,
            String screenshotRef) {
        if (planId == null) {
            return AuthorizationOutcome.notFound();
        }
        if (db == null || plans == null || applyReadiness == null || documentSelector == null
                || executionPackages == null || hardFilters == null || preferenceSets == null
                || decisions == null || txTemplate == null) {
            return unavailableAndAudit(planId, "PRE_SUBMIT_AUTH_UNAVAILABLE",
                    "pre-submit authorization dependencies are unavailable");
        }
        if (maintenanceMode != null && maintenanceMode.isMaintenanceMode()) {
            return unavailableAndAudit(planId, "MAINTENANCE_MODE_ACTIVE",
                    "automation maintenance mode is enabled; pre-submit authorization paused");
        }

        Map<String, Object> normalizedResult = resultMap == null ? Map.of() : resultMap;
        try {
            AuthorizationOutcome outcome = txTemplate.execute(tx ->
                    authorizeLocked(planId, index, stepStatus, normalizedResult, screenshotRef));
            if (outcome == null) {
                return unavailableAndAudit(planId, "PRE_SUBMIT_AUTH_UNAVAILABLE",
                        "pre-submit authorization returned no outcome");
            }
            if (outcome.authorized()) {
                writeAudit(planId, "PRE_SUBMIT_AUTHORIZED", outcome.details());
            } else if (outcome.httpStatus() != 404) {
                writeAudit(planId, "PRE_SUBMIT_AUTHORIZATION_REFUSED",
                        Map.of("code", outcome.code(), "httpStatus", outcome.httpStatus(),
                                "reason", outcome.error() == null ? "" : outcome.error()));
            }
            return outcome;
        } catch (ApplyReadinessService.ReadinessUnavailableException e) {
            log.warn("Pre-submit readiness check unavailable for plan {}: {}", planId, e.getMessage());
            return unavailableAndAudit(planId, "READINESS_UNAVAILABLE",
                    "apply readiness could not be verified: " + e.getMessage());
        } catch (DataAccessException e) {
            log.warn("Pre-submit database check unavailable for plan {}: {}", planId, e.getMessage());
            return unavailableAndAudit(planId, "PRE_SUBMIT_AUTH_UNAVAILABLE",
                    "pre-submit authorization database check failed");
        }
    }

    private AuthorizationOutcome authorizeLocked(
            UUID planId,
            int index,
            String stepStatus,
            Map<String, Object> resultMap,
            String screenshotRef) {
        LockedContext ctx = loadAndLockContext(planId);
        if (ctx == null) {
            return AuthorizationOutcome.notFound();
        }

        // Duplicate fence check FIRST under the plan row lock: guarantees any second concurrent
        // worker or crash-recovery retry is always classified as DUPLICATE_PRE_SUBMIT_ATTEMPT
        // rather than clicking or re-authorizing an in-flight attempt.
        if (plans.hasPreSubmitAttemptCheckpoint(planId)) {
            return AuthorizationOutcome.conflict("DUPLICATE_PRE_SUBMIT_ATTEMPT",
                    "a pre-submit attempt checkpoint already exists for this plan");
        }

        if (ctx.appId == null || !ctx.planProfileId.equals(ctx.appProfileId) || ctx.profileRowId == null) {
            return AuthorizationOutcome.conflict("BINDING_MISMATCH",
                    "automation plan is not cleanly bound to the candidate's application and profile");
        }

        if (ctx.job == null || ctx.jobDeletedAt != null || "ARCHIVED".equalsIgnoreCase(ctx.job.status())) {
            return AuthorizationOutcome.conflict("JOB_DELETED",
                    "the target job posting is deleted or archived");
        }

        if (ctx.appStatus == null || !ELIGIBLE_APPLICATION_STATUSES.contains(ctx.appStatus)) {
            return AuthorizationOutcome.conflict("APPLICATION_NOT_ELIGIBLE",
                    "application status " + ctx.appStatus + " is not eligible for submission");
        }

        Boolean anotherSubmitted = db.queryForObject("""
                select exists(
                    select 1 from automation_plans
                    where application_id = ? and id <> ? and status = 'SUBMITTED'
                )
                """, Boolean.class, ctx.applicationId, planId);
        if (Boolean.TRUE.equals(anotherSubmitted)) {
            return AuthorizationOutcome.conflict("APPLICATION_ALREADY_SUBMITTED",
                    "another plan for this application has already been submitted");
        }

        Map<String, Object> planMap = parseJsonMap(ctx.planJson);
        String planType = stringOrNull(planMap.get("planType"));
        String expectedSubmitStep = planType == null ? null : ADAPTER_SUBMIT_STEP.get(planType);
        if (expectedSubmitStep == null) {
            return AuthorizationOutcome.conflict("ADAPTER_SUBMIT_UNSUPPORTED",
                    "adapter planType " + planType + " is not authorized for submission");
        }

        String reportedPlatform = stringOrNull(resultMap.get("atsPlatform"));
        if (reportedPlatform != null && !planType.equals(reportedPlatform)) {
            return AuthorizationOutcome.conflict("ADAPTER_MISMATCH",
                    "worker reported atsPlatform " + reportedPlatform + " for " + planType + " plan");
        }
        String reportedStepType = stringOrNull(resultMap.get("stepType"));
        if (reportedStepType != null && !expectedSubmitStep.equals(reportedStepType)) {
            return AuthorizationOutcome.conflict("SUBMIT_STEP_MISMATCH",
                    "worker reported stepType " + reportedStepType + " when " + expectedSubmitStep + " is required");
        }

        String lastStepType = extractLastStepType(planMap);
        if (!expectedSubmitStep.equals(lastStepType)) {
            return AuthorizationOutcome.conflict("SUBMIT_STEP_MISSING",
                    "plan does not terminate in required submit step " + expectedSubmitStep);
        }

        Map<String, Object> correlation = asObjectMap(planMap.get("correlation"));
        UUID corrApp = parseUuidOrNull(correlation.get("applicationId"));
        if (corrApp != null && !corrApp.equals(ctx.applicationId)) {
            return AuthorizationOutcome.conflict("BINDING_MISMATCH",
                    "plan correlation applicationId does not match locked application");
        }
        UUID corrJob = parseUuidOrNull(correlation.get("jobId"));
        if (corrJob != null && !corrJob.equals(ctx.job.id())) {
            return AuthorizationOutcome.conflict("BINDING_MISMATCH",
                    "plan correlation jobId does not match locked job");
        }

        String employerDomain = stringOrNull(correlation.get("employerDomain"));
        String currentUrl = stringOrNull(resultMap.get("currentUrl"));
        String domainError = verifyTargetAndCurrentUrls(
                planType, ctx.job.applicationUrl(), ctx.targetUrl, employerDomain, currentUrl);
        if (domainError != null) {
            return AuthorizationOutcome.conflict("TARGET_DOMAIN_MISMATCH", domainError);
        }

        // Standing approval & RUNNING state checks before live readiness/eligibility so withdrawn approvals
        // return the clearest machine-readable code immediately.
        if (!ctx.submitApproved) {
            return AuthorizationOutcome.conflict("SUBMIT_APPROVAL_WITHDRAWN",
                    "the submit approval was withdrawn before the submission click");
        }
        if (!"RUNNING".equals(ctx.planStatus)) {
            return AuthorizationOutcome.conflict("PLAN_NOT_RUNNING",
                    "plan is in status " + ctx.planStatus + "; only RUNNING plans can cross the submit fence");
        }

        // Live eligibility & standing auto-apply permission check.
        AuthorizationOutcome eligibilityOutcome = verifyEligibilityAndStandingPermission(ctx);
        if (eligibilityOutcome != null) {
            return eligibilityOutcome;
        }

        // Live readiness & document/answer package integrity check (shared with approve-submit & prepare-submit).
        Map<String, Object> readiness = applyReadiness.evaluate(ctx.planProfileId, ctx.applicationId);
        if (!Boolean.TRUE.equals(readiness.get("packageReady"))) {
            String message = firstBlockerMessage(readiness);
            return AuthorizationOutcome.conflict("PACKAGE_NOT_READY",
                    "application readiness blockers present: " + message);
        }
        String drift = detectPackageDrift(
                ctx.planProfileId, ctx.applicationId, ctx.job.id(), planMap, documentSelector, executionPackages);
        if (drift != null) {
            return AuthorizationOutcome.conflict("PACKAGE_DRIFT", drift);
        }

        boolean recorded = plans.recordPreSubmitAttemptIfAbsent(
                planId, index, stepStatus, resultMap, screenshotRef);
        if (!recorded) {
            boolean duplicate = plans.hasPreSubmitAttemptCheckpoint(planId);
            return duplicate
                    ? AuthorizationOutcome.conflict("DUPLICATE_PRE_SUBMIT_ATTEMPT",
                            "a pre-submit attempt checkpoint already exists for this plan")
                    : AuthorizationOutcome.conflict("SUBMIT_APPROVAL_WITHDRAWN",
                            "the submit approval was withdrawn before the submission click");
        }

        Map<String, Object> authDetails = new LinkedHashMap<>();
        authDetails.put("planType", planType);
        authDetails.put("applicationId", ctx.applicationId.toString());
        authDetails.put("jobId", ctx.job.id().toString());
        authDetails.put("profileId", ctx.planProfileId.toString());
        return AuthorizationOutcome.ok(authDetails);
    }

    private AuthorizationOutcome verifyEligibilityAndStandingPermission(LockedContext ctx) {
        Map<String, Object> workEligibility = parseJsonMap(ctx.workEligibilityJson);
        if (Boolean.FALSE.equals(workEligibility.get("rightToWork"))
                || Boolean.FALSE.equals(workEligibility.get("eligible"))) {
            return AuthorizationOutcome.conflict("ELIGIBILITY_CONSTRAINT_FAILED",
                    "candidate profile work eligibility indicates not eligible");
        }
        String visaStatus = stringOrNull(workEligibility.get("visaStatus"));
        if (visaStatus != null && ("NOT_ELIGIBLE".equalsIgnoreCase(visaStatus)
                || "NONE".equalsIgnoreCase(visaStatus))) {
            return AuthorizationOutcome.conflict("ELIGIBILITY_CONSTRAINT_FAILED",
                    "candidate visa status does not permit submission");
        }

        PreferenceSetRecord prefs = preferenceSets.findActiveByProfileId(ctx.planProfileId).orElse(null);
        DecisionRow decisionRow = loadDecisionRow(ctx.planProfileId, ctx.job.id());
        boolean standingAutoFlow = "CONTROLLED_AUTO".equals(ctx.appMode)
                || (decisionRow != null && "AUTO_APPLY".equals(decisionRow.decision()));

        if (standingAutoFlow) {
            if (prefs == null || "MANUAL".equals(prefs.applicationMode())) {
                return AuthorizationOutcome.conflict("STANDING_PERMISSION_WITHDRAWN",
                        "candidate switched application mode to MANUAL or removed active preferences");
            }
            ApplicationDecisionService.RuleState rule = decisions.ruleState(ctx.planProfileId);
            if (rule.availability() == ApplicationDecisionService.RuleAvailability.UNREADABLE) {
                return AuthorizationOutcome.unavailable("PRE_SUBMIT_AUTH_UNAVAILABLE",
                        "approval rules could not be read; failing closed");
            }
            if (rule.availability() != ApplicationDecisionService.RuleAvailability.CONFIGURED
                    || rule.rule() == null || !rule.rule().autoApproveEnabled()) {
                return AuthorizationOutcome.conflict("STANDING_PERMISSION_WITHDRAWN",
                        "candidate disabled standing auto-approval rules");
            }
            Integer score = decisionRow != null ? decisionRow.matchScore() : loadMatchScore(ctx.planProfileId, ctx.job.id());
            if (score != null && score < rule.rule().minScore()) {
                return AuthorizationOutcome.conflict("STANDING_PERMISSION_WITHDRAWN",
                        "job match score " + score + " is now below standing minimum " + rule.rule().minScore());
            }
            HardFilterResult filter = hardFilters.evaluate(ctx.job, prefs);
            if (!filter.passed()) {
                return AuthorizationOutcome.conflict("ELIGIBILITY_CONSTRAINT_FAILED",
                        filter.reasons().stream().map(HardFilterResult.FilterReason::message).findFirst()
                                .orElse("job no longer satisfies standing preference constraints"));
            }
        } else if (prefs != null) {
            // Even on candidate-queued ASSISTED/MANUAL flows, fail closed on hard deal-breakers
            // (salary floor violated, excluded keyword added, sponsorship mismatch, blocked company).
            HardFilterResult filter = hardFilters.evaluate(ctx.job, prefs);
            if (!filter.passed()) {
                var blockingReason = filter.reasons().stream()
                        .filter(r -> ASSISTED_HARD_CONSTRAINTS.contains(r.criterion()))
                        .findFirst();
                if (blockingReason.isPresent()) {
                    return AuthorizationOutcome.conflict("ELIGIBILITY_CONSTRAINT_FAILED",
                            blockingReason.get().message());
                }
            }
        }
        return null;
    }

    /**
     * Shared package drift detector reused by {@link AutomationController#approve},
     * {@link AutomationController#prepareSubmit}, and the pre-submit fence.
     */
    public static String detectPackageDrift(
            UUID profileId,
            UUID applicationId,
            UUID jobId,
            Map<String, Object> planMap,
            ApplyDocumentSelector documentSelector,
            ExecutionPackageService executionPackages) {
        if (applicationId == null || jobId == null) {
            return "plan correlation is incomplete";
        }
        ApplyDocumentSelector.DocumentSelection selection = documentSelector.select(profileId, jobId, applicationId);
        if (selection.blocked()) {
            Object first = selection.blockers().isEmpty() ? null : selection.blockers().get(0).get("message");
            return "the package's documents are no longer usable: " + first;
        }
        Map<String, Object> stored = planMap == null ? Map.of() : planMap;
        Map<String, Object> pkg = asObjectMap(stored.get("package"));
        Map<String, Object> cv = asObjectMap(pkg.get("cv"));
        if (!cv.isEmpty()) {
            if (selection.cv() == null) {
                return "the CV in this package is no longer selected for this application; rebuild the package";
            }
            if (!String.valueOf(selection.cv().versionId()).equals(cv.get("versionId"))) {
                return "the CV attached to this package is no longer the reviewed version; rebuild the package";
            }
        }
        Map<String, Object> cover = asObjectMap(pkg.get("coverLetter"));
        if (!cover.isEmpty()) {
            if (selection.coverLetter() == null) {
                return "the cover letter in this package is no longer approved for this application; rebuild the package";
            }
            if (!String.valueOf(selection.coverLetter().versionId()).equals(cover.get("versionId"))) {
                return "the cover letter in this package is no longer the approved version; rebuild the package";
            }
        }
        List<Map<String, Object>> packagedAnswers = pkg.get("answers") instanceof List<?> list
                ? list.stream().filter(o -> o instanceof Map).map(PreSubmitAuthorizationService::asObjectMap).toList()
                : List.of();
        if (!executionPackages.answersStillCurrent(profileId, applicationId, jobId, packagedAnswers)) {
            return "an answer in this package has changed since it was built; rebuild the package";
        }
        return null;
    }

    String verifyTargetAndCurrentUrls(
            String planType,
            String jobApplicationUrl,
            String planTargetUrl,
            String employerDomain,
            String currentUrl) {
        URI jobUri = parseHttpUri(jobApplicationUrl);
        URI targetUri = parseHttpUri(planTargetUrl);
        if (jobUri == null || targetUri == null) {
            return "job application URL or plan target URL is missing or invalid";
        }
        String targetError = validateUrlPair(planType, jobUri, targetUri, employerDomain);
        if (targetError != null) {
            return targetError;
        }
        if (currentUrl != null && !currentUrl.isBlank()) {
            URI currentUri = parseHttpUri(currentUrl);
            if (currentUri == null) {
                return "worker reported an invalid or non-HTTP(S) live URL";
            }
            String liveError = validateUrlPair(planType, targetUri, currentUri, employerDomain);
            if (liveError != null) {
                return "live browser URL does not match authorized employer target: " + liveError;
            }
        }
        return null;
    }

    private String validateUrlPair(String planType, URI expectedUri, URI actualUri, String employerDomain) {
        boolean expectedLoopback = isLoopbackHost(expectedUri.getHost());
        boolean actualLoopback = isLoopbackHost(actualUri.getHost());
        if (expectedLoopback || actualLoopback) {
            if (!allowLocalTestHosts) {
                return "loopback URLs are forbidden when app.sandbox.allow-local-test-hosts=false";
            }
            if (!(expectedLoopback && actualLoopback)) {
                return "loopback and remote hosts cannot be mixed";
            }
            if (!expectedUri.getHost().equalsIgnoreCase(actualUri.getHost())
                    || effectivePort(expectedUri) != effectivePort(actualUri)) {
                return "loopback origin mismatch";
            }
            return null;
        }

        if (!"https".equalsIgnoreCase(expectedUri.getScheme()) || !"https".equalsIgnoreCase(actualUri.getScheme())) {
            return "HTTPS is required for employer target URLs";
        }

        String expectedHost = normalizeHost(expectedUri.getHost());
        String actualHost = normalizeHost(actualUri.getHost());
        if (expectedHost == null || actualHost == null) {
            return "target host is missing";
        }

        return switch (planType) {
            case "GREENHOUSE" -> validateGreenhousePair(expectedHost, expectedUri.getPath(), actualHost, actualUri.getPath());
            case "ASHBY" -> validateAshbyPair(expectedHost, expectedUri.getPath(), actualHost, actualUri.getPath());
            case "WORKDAY" -> validateWorkdayPair(expectedHost, actualHost);
            case "UNIVERSAL" -> validateUniversalPair(expectedHost, actualHost, employerDomain);
            default -> "unsupported adapter";
        };
    }

    private String validateGreenhousePair(String expectedHost, String expectedPath, String actualHost, String actualPath) {
        boolean sameHost = expectedHost.equals(actualHost);
        boolean bothGreenhouse = (expectedHost.endsWith(".greenhouse.io") || expectedHost.equals("greenhouse.io"))
                && (actualHost.endsWith(".greenhouse.io") || actualHost.equals("greenhouse.io"));
        if (!sameHost && !bothGreenhouse) {
            return "Greenhouse host mismatch (" + actualHost + " vs " + expectedHost + ")";
        }
        String expectedBoard = firstPathSegment(expectedPath);
        String actualBoard = firstPathSegment(actualPath);
        if (expectedBoard != null && actualBoard != null && !expectedBoard.equalsIgnoreCase(actualBoard)) {
            return "Greenhouse board tenant mismatch (" + actualBoard + " vs " + expectedBoard + ")";
        }
        return null;
    }

    private String validateAshbyPair(String expectedHost, String expectedPath, String actualHost, String actualPath) {
        if (!expectedHost.equals(actualHost)) {
            return "Ashby host mismatch (" + actualHost + " vs " + expectedHost + ")";
        }
        String expectedOrg = firstPathSegment(expectedPath);
        String actualOrg = firstPathSegment(actualPath);
        if (expectedOrg != null && actualOrg != null && !expectedOrg.equalsIgnoreCase(actualOrg)) {
            return "Ashby organization slug mismatch (" + actualOrg + " vs " + expectedOrg + ")";
        }
        return null;
    }

    private String validateWorkdayPair(String expectedHost, String actualHost) {
        if (!expectedHost.equals(actualHost)) {
            return "Workday tenant host mismatch (" + actualHost + " vs " + expectedHost + ")";
        }
        return null;
    }

    private String validateUniversalPair(String expectedHost, String actualHost, String employerDomain) {
        if (!hostsBelongToSameEmployer(expectedHost, actualHost)) {
            return "Universal host mismatch (" + actualHost + " vs " + expectedHost + ")";
        }
        if (employerDomain != null && !employerDomain.isBlank()) {
            String normalizedDecl = normalizeHost(employerDomain);
            if (normalizedDecl == null || !hostsBelongToSameEmployer(normalizedDecl, actualHost)) {
                return "Universal correlation employerDomain mismatch";
            }
        }
        return null;
    }

    private static boolean hostsBelongToSameEmployer(String hostA, String hostB) {
        if (hostA.equals(hostB)) return true;
        String regA = EmployerDomainPolicy.registrableDomain(hostA);
        String regB = EmployerDomainPolicy.registrableDomain(hostB);
        return regA != null && regA.equals(regB);
    }

    private static String firstPathSegment(String path) {
        if (path == null) return null;
        for (String seg : path.split("/")) {
            if (!seg.isBlank()) return seg;
        }
        return null;
    }

    private static URI parseHttpUri(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h) || "[::1]".equals(h);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.isEmpty() ? null : h;
    }

    private record DecisionRow(String decision, Integer matchScore) {}

    private DecisionRow loadDecisionRow(UUID profileId, UUID jobId) {
        List<DecisionRow> rows = db.query("""
                select decision, match_score from application_decisions
                where profile_id = ? and job_id = ?
                order by updated_at desc limit 1
                """, (rs, n) -> new DecisionRow(rs.getString("decision"), (Integer) rs.getObject("match_score")),
                profileId, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Integer loadMatchScore(UUID profileId, UUID jobId) {
        List<Integer> rows = db.query("""
                select score from job_matches where profile_id = ? and job_id = ? limit 1
                """, (rs, n) -> (Integer) rs.getObject(1), profileId, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record LockedContext(
            UUID planId,
            UUID planProfileId,
            UUID applicationId,
            String targetUrl,
            String planStatus,
            boolean submitApproved,
            String planJson,
            UUID appId,
            UUID appProfileId,
            String appStatus,
            String appMode,
            JobRecord job,
            Instant jobDeletedAt,
            UUID profileRowId,
            String workEligibilityJson
    ) {}

    private LockedContext loadAndLockContext(UUID planId) {
        List<LockedContext> rows = db.query("""
                /* pre-submit-authorization-lock */
                select p.id as plan_id, p.profile_id as plan_profile_id, p.application_id,
                       p.target_url, p.status as plan_status, p.submit_approved, p.plan::text as plan_json,
                       a.id as app_id, a.profile_id as app_profile_id, a.status as app_status, a.mode as app_mode,
                       j.id as job_row_id, j.source_id, j.external_id, j.company_id, j.company_name_raw,
                       j.title, j.location_raw, j.city, j.country, j.remote_type,
                       j.employment_type, j.experience_level, j.salary_min, j.salary_max, j.salary_currency,
                       j.description_text, j.skills_extracted, j.application_url as job_application_url,
                       j.canonical_url, j.posted_at, j.status as job_status, j.deleted_at as job_deleted_at,
                       pr.id as prof_id, coalesce(pr.work_eligibility::text, '{}') as work_eligibility_json
                from automation_plans p
                left join applications a on a.id = p.application_id
                left join jobs j on j.id = a.job_id
                left join profiles pr on pr.id = p.profile_id
                where p.id = ?
                for update of p
                """, (rs, n) -> mapLockedContext(rs), planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LockedContext mapLockedContext(ResultSet rs) throws java.sql.SQLException {
        UUID jobId = (UUID) rs.getObject("job_row_id");
        JobRecord job = null;
        if (jobId != null) {
            Timestamp posted = rs.getTimestamp("posted_at");
            job = new JobRecord(
                    jobId,
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
                    rs.getString("job_application_url"),
                    rs.getString("canonical_url"),
                    posted == null ? null : posted.toInstant(),
                    rs.getString("job_status")
            );
        }
        Timestamp deletedTs = rs.getTimestamp("job_deleted_at");
        return new LockedContext(
                (UUID) rs.getObject("plan_id"),
                (UUID) rs.getObject("plan_profile_id"),
                (UUID) rs.getObject("application_id"),
                rs.getString("target_url"),
                rs.getString("plan_status"),
                rs.getBoolean("submit_approved"),
                rs.getString("plan_json"),
                (UUID) rs.getObject("app_id"),
                (UUID) rs.getObject("app_profile_id"),
                rs.getString("app_status"),
                rs.getString("app_mode"),
                job,
                deletedTs == null ? null : deletedTs.toInstant(),
                (UUID) rs.getObject("prof_id"),
                rs.getString("work_eligibility_json")
        );
    }

    private Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String extractLastStepType(Map<String, Object> planMap) {
        Object stepsObj = planMap.get("steps");
        if (!(stepsObj instanceof List<?> steps) || steps.isEmpty()) return null;
        Object last = steps.get(steps.size() - 1);
        if (!(last instanceof Map<?, ?> step)) return null;
        return stringOrNull(step.get("type"));
    }

    private static String firstBlockerMessage(Map<String, Object> readiness) {
        Object blockersObj = readiness.get("blockers");
        if (blockersObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
            Object msg = m.get("message");
            if (msg != null) return String.valueOf(msg);
            Object code = m.get("code");
            if (code != null) return String.valueOf(code);
        }
        return "application readiness failed";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static UUID parseUuidOrNull(Object o) {
        if (o == null) return null;
        try {
            return UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private AuthorizationOutcome unavailableAndAudit(UUID planId, String code, String message) {
        writeAudit(planId, "PRE_SUBMIT_AUTHORIZATION_REFUSED",
                Map.of("code", code, "httpStatus", 503, "reason", message));
        return AuthorizationOutcome.unavailable(code, message);
    }

    private void writeAudit(UUID planId, String action, Map<String, Object> details) {
        if (audit == null) return;
        try {
            audit.write(new AuditEntry("WORKER", action, "AUTOMATION_PLAN", planId,
                    Map.of(), details == null ? Map.of() : details, null, UuidV7.generate()));
        } catch (Exception e) {
            log.debug("Could not write {} audit log for plan {}: {}", action, planId, e.getMessage());
        }
    }
}
