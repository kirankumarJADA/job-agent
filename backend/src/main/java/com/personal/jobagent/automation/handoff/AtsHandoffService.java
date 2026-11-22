package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    public AtsHandoffService(HandoffSessionRepository handoffRepository,
                             AutomationPlanRepository planRepository,
                             @Autowired(required = false) AuditLogWriter audit,
                             @Autowired(required = false) TransactionTemplate transactionTemplate,
                             @Autowired(required = false) ApplicationStatusService applicationStatus) {
        this.handoffRepository = handoffRepository;
        this.planRepository = planRepository;
        this.audit = audit;
        this.transactionTemplate = transactionTemplate;
        this.applicationStatus = applicationStatus;
    }

    public AtsHandoffService(HandoffSessionRepository handoffRepository,
                             AutomationPlanRepository planRepository,
                             AuditLogWriter audit,
                             TransactionTemplate transactionTemplate) {
        this(handoffRepository, planRepository, audit, transactionTemplate, null);
    }

    /**
     * Initiates a candidate handoff when browser automation detects CAPTCHA, MFA, or security challenge.
     * Transitions the plan to HANDOFF_REQUIRED and persists a short-lived session.
     */
    public HandoffSessionRecord initiateHandoff(UUID ownerProfileId, UUID planId, UUID applicationId,
                                                String targetUrl, HandoffBlockReason reason, int ttlMinutes) {
        log.info("Initiating candidate handoff for plan {} (app {}) due to {}", planId, applicationId, reason);
        HandoffSessionRecord session = handoffRepository.createSession(ownerProfileId, planId, applicationId, targetUrl, reason, ttlMinutes);

        // Pause plan status safely
        planRepository.transition(planId, "RUNNING", "HANDOFF_REQUIRED");

        if (audit != null) {
            audit.write(new AuditEntry(
                    "WORKER",
                    "ATS_HANDOFF_INITIATED",
                    "AUTOMATION_PLAN",
                    planId,
                    null,
                    session,
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

    public Optional<UUID> resolveProfileId(UUID planId, UUID applicationId) {
        if (planId != null) {
            Optional<UUID> owner = planRepository.ownerOfPlan(planId);
            if (owner.isPresent()) return owner;
        }
        if (applicationId != null) {
            return handoffRepository.findProfileIdForApplication(applicationId);
        }
        return Optional.empty();
    }

    public boolean isApplicationOwnedBy(UUID applicationId, UUID profileId) {
        if (applicationId == null || profileId == null) return false;
        return handoffRepository.findProfileIdForApplication(applicationId)
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
            planRepository.transitionWithResult(planId, "HANDOFF_REQUIRED", "FAILED", errorResult);
            if (audit != null) {
                audit.write(new AuditEntry(
                        "SYSTEM",
                        "ATS_HANDOFF_EXPIRED",
                        "AUTOMATION_PLAN",
                        planId,
                        "HANDOFF_REQUIRED",
                        "FAILED",
                        null,
                        null
                ));
            }
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
}
