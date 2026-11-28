package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlanRepository;
import com.personal.jobagent.automation.UniversalBrowserPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3: Service orchestrating candidate-controlled ATS browser handoff workflows.
 * Guarantees zero CAPTCHA/MFA automated bypass, explicit candidate consent, and duplicate prevention.
 */
@Service
public class AtsHandoffService {

    private static final Logger log = LoggerFactory.getLogger(AtsHandoffService.class);

    private final HandoffSessionRepository handoffRepository;
    private final AutomationPlanRepository planRepository;
    private final AuditLogWriter audit;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationStatusService applicationStatus;
    private final UniversalBrowserPlanService universalPlans;
    private final boolean allowLocalTestHosts;

    @Autowired
    public AtsHandoffService(HandoffSessionRepository handoffRepository,
                             AutomationPlanRepository planRepository,
                             @Autowired(required = false) AuditLogWriter audit,
                             @Autowired(required = false) TransactionTemplate transactionTemplate,
                             @Autowired(required = false) ApplicationStatusService applicationStatus,
                             @Autowired(required = false) UniversalBrowserPlanService universalPlans,
                             @Value("${app.sandbox.allow-local-test-hosts:false}") boolean allowLocalTestHosts) {
        this.handoffRepository = handoffRepository;
        this.planRepository = planRepository;
        this.audit = audit;
        this.transactionTemplate = transactionTemplate;
        this.applicationStatus = applicationStatus;
        this.universalPlans = universalPlans;
        this.allowLocalTestHosts = allowLocalTestHosts;
    }

    public AtsHandoffService(HandoffSessionRepository handoffRepository,
                             AutomationPlanRepository planRepository,
                             AuditLogWriter audit,
                             TransactionTemplate transactionTemplate,
                             ApplicationStatusService applicationStatus,
                             UniversalBrowserPlanService universalPlans) {
        this(handoffRepository, planRepository, audit, transactionTemplate, applicationStatus, universalPlans, false);
    }

    public AtsHandoffService(HandoffSessionRepository handoffRepository,
                             AutomationPlanRepository planRepository,
                             AuditLogWriter audit,
                             TransactionTemplate transactionTemplate,
                             ApplicationStatusService applicationStatus) {
        this(handoffRepository, planRepository, audit, transactionTemplate, applicationStatus, null, false);
    }

    public AtsHandoffService(HandoffSessionRepository handoffRepository,
                             AutomationPlanRepository planRepository,
                             AuditLogWriter audit,
                             TransactionTemplate transactionTemplate) {
        this(handoffRepository, planRepository, audit, transactionTemplate, null, null, false);
    }

    /**
     * Validates that the target URL is an absolute HTTPS URL with a valid host,
     * no embedded user credentials, and no protocol-relative or dangerous schemes.
     */
    /**
     * Dedicated ATS platforms that serve one posting from several of their own hosts
     * (e.g. {@code boards.greenhouse.io} and {@code job-boards.greenhouse.io}).
     */
    private static final java.util.Set<String> ATS_PLATFORM_DOMAINS = java.util.Set.of(
            "greenhouse.io", "myworkdayjobs.com", "myworkday.com", "lever.co", "ashbyhq.com",
            "zohorecruit.com", "zohorecruit.in");

    /**
     * Stage 3F: a handoff link is on "the same employer host" when it is the plan's own host,
     * or when both hosts belong to the same dedicated ATS platform domain. Any other domain —
     * including look-alikes such as {@code greenhouse.io.evil.example} — is refused.
     */
    public static boolean sameEmployerHost(String handoffHost, String planHost) {
        if (handoffHost.equals(planHost)) return true;
        for (String platform : ATS_PLATFORM_DOMAINS) {
            if (com.personal.jobagent.common.SafeUrls.hostIsOrIsSubdomainOf(handoffHost, platform)
                    && com.personal.jobagent.common.SafeUrls.hostIsOrIsSubdomainOf(planHost, platform)) {
                return true;
            }
        }
        return false;
    }

    public void validateConfiguredTargetUrl(String targetUrl) {
        validateTargetUrl(targetUrl, allowLocalTestHosts);
    }

    public static void validateTargetUrl(String targetUrl) {
        validateTargetUrl(targetUrl, false);
    }

    public static void validateTargetUrl(String targetUrl, boolean allowLocalTestHosts) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("Target URL must not be null or blank");
        }
        String trimmed = targetUrl.trim();
        if (trimmed.startsWith("//")) {
            throw new IllegalArgumentException("Protocol-relative target URLs are not permitted");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid target URL syntax: " + e.getMessage(), e);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("Target URL must be absolute");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Target URL must contain a valid host");
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            if (!(allowLocalTestHosts && "http".equalsIgnoreCase(scheme) && isExplicitLoopbackHost(host))) {
                throw new IllegalArgumentException("Target URL scheme must be HTTPS (got: " + scheme + ")");
            }
        }
        if (uri.getUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
            throw new IllegalArgumentException("Target URL must not contain embedded credentials");
        }
    }

    private static boolean isExplicitLoopbackHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!"localhost".equals(normalized) && !"127.0.0.1".equals(normalized) && !"::1".equals(normalized)) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(normalized);
            return addr.isLoopbackAddress() && !addr.isLinkLocalAddress() && !addr.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Initiates a candidate handoff when browser automation detects CAPTCHA, MFA, or security challenge.
     * Transitions the plan to HANDOFF_REQUIRED and persists a short-lived session.
     */
    @Transactional
    public HandoffSessionRecord initiateHandoff(UUID ownerProfileId, UUID planId, UUID applicationId,
                                                String targetUrl, HandoffBlockReason reason, int ttlMinutes) {
        if (ownerProfileId == null || planId == null || applicationId == null) {
            throw new IllegalArgumentException("ownerProfileId, planId, and applicationId are required");
        }
        validateTargetUrl(targetUrl, allowLocalTestHosts);

        // Invariant: automation_plans.application_id == requested applicationId
        AutomationPlanRepository.PlanRow plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Automation plan not found: " + planId));
        if (!applicationId.equals(plan.applicationId())) {
            throw new IllegalArgumentException("Mismatched plan and application: plan " + planId
                    + " belongs to application " + plan.applicationId() + ", but requested application was " + applicationId);
        }

        // Stage 3F: the link handed to the candidate must stay on this plan's own employer host —
        // never a worker- or page-supplied redirect to some other (possibly phishing) domain.
        String handoffHost = com.personal.jobagent.common.SafeUrls.canonicalHost(targetUrl).orElse(null);
        String planHost = com.personal.jobagent.common.SafeUrls.canonicalHost(plan.targetUrl()).orElse(null);
        if (handoffHost == null || planHost == null || !sameEmployerHost(handoffHost, planHost)) {
            throw new IllegalArgumentException("Handoff target must be on the application's own employer host");
        }

        // Invariant: plan owner must match supplied ownerProfileId
        UUID planOwner = planRepository.ownerOfPlan(planId)
                .orElseThrow(() -> new SecurityException("Plan owner not found for plan: " + planId));
        if (!ownerProfileId.equals(planOwner)) {
            throw new SecurityException("Plan " + planId + " does not belong to profile " + ownerProfileId);
        }

        // Invariant: application owner must match supplied ownerProfileId
        UUID appOwner = handoffRepository.findProfileIdForApplication(applicationId)
                .orElseThrow(() -> new SecurityException("Application owner not found for application: " + applicationId));
        if (!ownerProfileId.equals(appOwner)) {
            throw new SecurityException("Application " + applicationId + " does not belong to profile " + ownerProfileId);
        }

        log.info("Initiating candidate handoff for plan {} (app {}) due to {}", planId, applicationId, reason);
        HandoffSessionRecord session = handoffRepository.createSession(ownerProfileId, planId, applicationId, targetUrl, reason, ttlMinutes);

        // Pause plan status safely: support transition from RUNNING or BLOCKED_ANTI_BOT to HANDOFF_REQUIRED
        boolean transitioned = planRepository.transition(planId, "RUNNING", "HANDOFF_REQUIRED");
        if (!transitioned) {
            transitioned = planRepository.transition(planId, "BLOCKED_ANTI_BOT", "HANDOFF_REQUIRED");
        }
        if (!transitioned) {
            Optional<String> currentStatus = planRepository.findById(planId).map(AutomationPlanRepository.PlanRow::status);
            if (currentStatus.isEmpty() || !"HANDOFF_REQUIRED".equals(currentStatus.get())) {
                throw new IllegalStateException("Failed to transition plan " + planId + " to HANDOFF_REQUIRED (current status: " + currentStatus.orElse("not found") + ")");
            }
        }

        if (audit != null) {
            audit.write(new AuditEntry(
                    "WORKER",
                    "ATS_HANDOFF_INITIATED",
                    "AUTOMATION_PLAN",
                    planId,
                    null,
                    HandoffSessionResponse.from(session),
                    null,
                    null
            ));
        }

        return session;
    }

    public Optional<HandoffSessionRecord> getActiveHandoffForApplication(UUID ownerProfileId, UUID applicationId) {
        return handoffRepository.findActiveByApplication(ownerProfileId, applicationId);
    }

    public Optional<HandoffSessionRecord> getActiveHandoffForPlan(UUID ownerProfileId, UUID planId) {
        return handoffRepository.findActiveByPlan(ownerProfileId, planId);
    }

    public Optional<HandoffSessionRecord> findSessionAnyOwner(UUID sessionId) {
        return handoffRepository.findByIdAnyOwner(sessionId);
    }

    public boolean isPlanAssociatedWithApplication(UUID planId, UUID applicationId) {
        if (planId == null || applicationId == null) {
            return false;
        }
        return planRepository.findById(planId)
                .map(AutomationPlanRepository.PlanRow::applicationId)
                .map(applicationId::equals)
                .orElse(false);
    }

    public Optional<UUID> resolveProfileId(UUID planId, UUID applicationId) {
        if (planId == null || applicationId == null) {
            return Optional.empty();
        }
        // Invariant: plan must exist and be associated with applicationId
        Optional<AutomationPlanRepository.PlanRow> planOpt = planRepository.findById(planId);
        if (planOpt.isEmpty() || !applicationId.equals(planOpt.get().applicationId())) {
            return Optional.empty();
        }
        Optional<UUID> planOwner = planRepository.ownerOfPlan(planId);
        Optional<UUID> appOwner = handoffRepository.findProfileIdForApplication(applicationId);
        if (planOwner.isEmpty() || appOwner.isEmpty() || !planOwner.get().equals(appOwner.get())) {
            return Optional.empty();
        }
        return planOwner;
    }

    public boolean isApplicationOwnedBy(UUID applicationId, UUID profileId) {
        if (applicationId == null || profileId == null) return false;
        return handoffRepository.findProfileIdForApplication(applicationId)
                .map(profileId::equals)
                .orElse(false);
    }

    public boolean isPlanOwnedBy(UUID planId, UUID profileId) {
        if (planId == null || profileId == null) return false;
        return planRepository.ownerOfPlan(planId)
                .map(profileId::equals)
                .orElse(false);
    }

    /**
     * Completes a handoff after candidate interaction and explicit attestation of external submission.
     * Atomically transitions the session to COMPLETED, the application to APPLICATION_SUBMITTED via ApplicationStatusService,
     * and sets plan status to COMPLETED (terminal state).
     *
     * @param ownerProfileId caller profile ID
     * @param sessionId handoff session ID
     * @return true if completion succeeded; false if expired, cancelled, or not owned
     */
    @Transactional
    public boolean confirmHandoffCompletion(UUID ownerProfileId, UUID sessionId) {
        Optional<HandoffSessionRecord> sessionOpt = handoffRepository.findById(ownerProfileId, sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("Handoff completion refused: session {} not found for owner {}", sessionId, ownerProfileId);
            return false;
        }

        HandoffSessionRecord session = sessionOpt.get();
        if (session.isExpired()) {
            log.warn("Handoff completion refused: session {} is expired", sessionId);
            expireStaleSessions();
            return false;
        }

        boolean updated = handoffRepository.completeSession(ownerProfileId, sessionId);
        if (!updated) {
            log.warn("Handoff completion refused: atomic update failed for session {}", sessionId);
            return false;
        }

        // 1. Transition the application status to APPLICATION_SUBMITTED following the state machine
        recordExternalSubmission(ownerProfileId, session.applicationId(), sessionId, session.blockReason().name());

        // 2. Transition automation plan to terminal state COMPLETED with external attestation result
        List<Object> completionResult = List.of(Map.of(
                "type", "EXTERNAL_SUBMISSION_ATTESTATION",
                "outcome", "COMPLETED",
                "submissionSource", "CANDIDATE_ATTESTATION",
                "verificationLevel", "CANDIDATE_REPORTED",
                "attestedAt", Instant.now().toString(),
                "sessionId", sessionId.toString(),
                "blockReason", session.blockReason().name()
        ));

        boolean transitioned = planRepository.transitionWithResult(
                session.planId(), "HANDOFF_REQUIRED", "COMPLETED", completionResult
        );
        if (!transitioned) {
            // Also attempt transition if plan was previously in BLOCKED_ANTI_BOT
            transitioned = planRepository.transitionWithResult(
                    session.planId(), "BLOCKED_ANTI_BOT", "COMPLETED", completionResult
            );
        }
        if (!transitioned) {
            throw new IllegalStateException("Failed to transition automation plan " + session.planId() + " to terminal COMPLETED");
        }

        log.info("Handoff session {} completed by candidate attestation. Plan {} set to COMPLETED (terminal)", sessionId, session.planId());

        if (audit != null) {
            audit.write(new AuditEntry(
                    "CANDIDATE",
                    "ATS_HANDOFF_COMPLETED",
                    "AUTOMATION_PLAN",
                    session.planId(),
                    session.status().name(),
                    Map.of(
                            "mode", "EXTERNAL_COMPLETION",
                            "submissionSource", "CANDIDATE_ATTESTATION",
                            "verificationLevel", "CANDIDATE_REPORTED",
                            "blockReason", session.blockReason().name(),
                            "sessionId", sessionId.toString()
                    ),
                    null,
                    null
            ));
        }

        return true;
    }

    private void recordExternalSubmission(UUID ownerProfileId, UUID applicationId, UUID sessionId, String reason) {
        if (applicationId == null || ownerProfileId == null) {
            return;
        }
        if (applicationStatus == null) {
            throw new IllegalStateException("ApplicationStatusService is required for external submission transitions");
        }

        String currentStatus = handoffRepository.getApplicationStatus(applicationId).orElse(null);
        if (currentStatus == null) {
            throw new NoSuchElementException("Application not found: " + applicationId);
        }

        // Handle already-submitted applications idempotently without regressing status
        if ("APPLICATION_SUBMITTED".equals(currentStatus) ||
                "CONFIRMATION_RECEIVED".equals(currentStatus) ||
                "RECRUITER_CONTACT".equals(currentStatus) ||
                "ASSESSMENT".equals(currentStatus) ||
                "INTERVIEW".equals(currentStatus) ||
                "OFFER".equals(currentStatus)) {
            log.info("Application {} is already in terminal/submitted status {}; maintaining status", applicationId, currentStatus);
            handoffRepository.recordSubmissionTimestamp(applicationId);
            return;
        }

        // State machine progression: READY_TO_APPLY -> APPLICATION_STARTED -> APPLICATION_SUBMITTED
        if ("READY_TO_APPLY".equals(currentStatus)) {
            applicationStatus.apply(ownerProfileId, applicationId, "APPLICATION_STARTED",
                    "handoff.start:" + sessionId, "candidate",
                    Map.of("sessionId", sessionId.toString(), "reason", reason));
        }

        applicationStatus.apply(ownerProfileId, applicationId, "APPLICATION_SUBMITTED",
                "handoff.external_submit:" + sessionId, "candidate",
                Map.of(
                        "sessionId", sessionId.toString(),
                        "submissionSource", "CANDIDATE_ATTESTATION",
                        "verificationLevel", "CANDIDATE_REPORTED",
                        "reason", reason
                ));

        handoffRepository.recordSubmissionTimestamp(applicationId);
    }

    @Transactional
    public int expireStaleSessions() {
        List<UUID> expiredPlanIds = handoffRepository.expireStaleSessionsAndGetPlanIds();
        for (UUID planId : expiredPlanIds) {
            List<Object> errorResult = List.of(Map.of(
                    "type", "PLAN_ERROR",
                    "outcome", "FAILED",
                    "reason", "HANDOFF_EXPIRED",
                    "message", "Handoff session expired without candidate attestation"
            ));
            String previousState = "HANDOFF_REQUIRED";
            boolean transitioned = planRepository.transitionWithResult(planId, previousState, "FAILED", errorResult);
            if (!transitioned) {
                previousState = "BLOCKED_ANTI_BOT";
                transitioned = planRepository.transitionWithResult(planId, previousState, "FAILED", errorResult);
            }
            if (transitioned) {
                if (audit != null) {
                    audit.write(new AuditEntry(
                            "SYSTEM",
                            "ATS_HANDOFF_EXPIRED",
                            "AUTOMATION_PLAN",
                            planId,
                            previousState,
                            "FAILED",
                            null,
                            null
                    ));
                }
                continue;
            }

            // Both expected transitions failed: inspect actual plan status
            Optional<String> currentStatus = planRepository.findById(planId).map(AutomationPlanRepository.PlanRow::status);
            if (currentStatus.isPresent() && "FAILED".equals(currentStatus.get())) {
                log.info("Plan {} is already in terminal FAILED status; treating expiry as idempotent", planId);
                // Do not record an expiry audit event claiming a transition occurred when it did not
                continue;
            }

            throw new IllegalStateException("Failed to transition expired plan " + planId + " to FAILED (current status: " + currentStatus.orElse("not found") + ")");
        }
        return expiredPlanIds.size();
    }

    /**
     * Cancels an active handoff session upon candidate request.
     */
    @Transactional
    public boolean cancelHandoff(UUID ownerProfileId, UUID sessionId) {
        Optional<HandoffSessionRecord> sessionOpt = handoffRepository.findById(ownerProfileId, sessionId);
        if (sessionOpt.isEmpty()) {
            return false;
        }

        HandoffSessionRecord session = sessionOpt.get();
        boolean cancelled = handoffRepository.cancelSession(ownerProfileId, sessionId);
        if (cancelled) {
            List<Object> errorResult = List.of(Map.of(
                    "type", "PLAN_ERROR",
                    "outcome", "FAILED",
                    "reason", "HANDOFF_CANCELLED",
                    "message", "Handoff session cancelled by candidate"
            ));
            boolean transitioned = planRepository.transitionWithResult(session.planId(), "HANDOFF_REQUIRED", "FAILED", errorResult);
            if (!transitioned) {
                transitioned = planRepository.transitionWithResult(session.planId(), "BLOCKED_ANTI_BOT", "FAILED", errorResult);
            }
            if (!transitioned) {
                throw new IllegalStateException("Failed to transition automation plan " + session.planId() + " to FAILED on handoff cancellation");
            }
            if (audit != null) {
                audit.write(new AuditEntry(
                        "CANDIDATE",
                        "ATS_HANDOFF_CANCELLED",
                        "AUTOMATION_PLAN",
                        session.planId(),
                        session.status().name(),
                        "CANCELLED",
                        null,
                        null
                ));
            }
        }
        return cancelled;
    }

    /**
     * Stage 3D: Resumes the same pre-submit automation plan after candidate intervention on
     * a handoff blocker (e.g. saving employer credentials or clearing login/security gate),
     * transitioning {@code HANDOFF_REQUIRED -> PREPARED} without falsely marking external
     * submission and refusing if any submission attempt has already occurred.
     */
    @Transactional
    public UUID resumeAfterHandoff(UUID ownerProfileId, UUID sessionId) {
        Optional<HandoffSessionRecord> sessionOpt = handoffRepository.findById(ownerProfileId, sessionId);
        if (sessionOpt.isEmpty()) {
            return null;
        }
        HandoffSessionRecord session = sessionOpt.get();
        if (session.status() != HandoffStatus.PENDING) {
            throw new IllegalStateException("Handoff session is not active (status: " + session.status() + ")");
        }
        if (session.isExpired()) {
            expireStaleSessions();
            throw new IllegalStateException("Handoff session expired; re-prepare the plan instead");
        }

        AutomationPlanRepository.PlanRow plan = planRepository.find(ownerProfileId, session.planId())
                .orElseThrow(() -> new IllegalStateException("Automation plan not found or not owned"));
        if (!"HANDOFF_REQUIRED".equals(plan.status()) || plan.submitApproved()
                || planRepository.hasPreSubmitAttemptCheckpoint(plan.id())) {
            throw new IllegalStateException("Plan cannot resume from handoff after submit approval or submit attempt");
        }
        Map<String, Object> stored = plan.plan() == null ? Map.of() : plan.plan();
        if (stored.get("steps") instanceof List<?> steps) {
            for (Object o : steps) {
                if (o instanceof Map<?, ?> step) {
                    Object type = step.get("type");
                    if ("UNIVERSAL_SUBMIT_CLICK".equals(type) || "REAL_SUBMIT_CLICK".equals(type)
                            || "ASHBY_SUBMIT_CLICK".equals(type) || "WORKDAY_SUBMIT_CLICK".equals(type)
                            || "MOCK_SUBMIT".equals(type)) {
                        throw new IllegalStateException("Plan already contains a submit step and cannot resume via handoff");
                    }
                }
            }
        }

        if (!handoffRepository.completeSession(ownerProfileId, sessionId)) {
            throw new IllegalStateException("Failed to complete active handoff session atomically");
        }

        Map<String, Object> nextPlanPayload = stored;
        if ("UNIVERSAL".equals(stored.get("planType")) && universalPlans != null) {
            nextPlanPayload = universalPlans.rebuildForResume(ownerProfileId, session.planId());
        }

        if (!planRepository.resumeFromHandoff(session.planId(), nextPlanPayload)) {
            throw new IllegalStateException("Plan state changed concurrently and could not return to PREPARED");
        }

        if (audit != null) {
            audit.write(new AuditEntry(
                    "CANDIDATE",
                    "ATS_HANDOFF_RESUMED",
                    "AUTOMATION_PLAN",
                    session.planId(),
                    "HANDOFF_REQUIRED",
                    Map.of("mode", "RESUME_AUTOMATION", "sessionId", sessionId.toString(),
                            "blockReason", session.blockReason().name()),
                    null,
                    null
            ));
        }

        return session.planId();
    }
}
