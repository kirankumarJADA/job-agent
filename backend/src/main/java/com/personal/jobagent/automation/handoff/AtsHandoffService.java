package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
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
     * Completes a handoff after candidate interaction and explicit verification confirmation.
     * Atomically transitions the session to COMPLETED and sets plan status to READY_TO_SUBMIT.
     *
     * @param ownerProfileId caller profile ID
     * @param sessionId handoff session ID
     * @return true if completion succeeded; false if expired, cancelled, or not owned
     */
    public boolean confirmHandoffCompletion(UUID ownerProfileId, UUID sessionId) {
        Optional<HandoffSessionRecord> sessionOpt = handoffRepository.findById(ownerProfileId, sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("Handoff completion refused: session {} not found for owner {}", sessionId, ownerProfileId);
            return false;
        }

        HandoffSessionRecord session = sessionOpt.get();
        if (session.isExpired()) {
            log.warn("Handoff completion refused: session {} is expired", sessionId);
            handoffRepository.expireStaleSessions();
            return false;
        }

        boolean updated = handoffRepository.completeSession(ownerProfileId, sessionId);
        if (!updated) {
            log.warn("Handoff completion refused: atomic update failed for session {}", sessionId);
            return false;
        }

        // Resume automation plan to READY_TO_SUBMIT so candidate can proceed with guarded submission
        boolean resumed = planRepository.transition(session.planId(), "HANDOFF_REQUIRED", "READY_TO_SUBMIT");
        if (!resumed) {
            // Also try transition from BLOCKED_ANTI_BOT if legacy state
            planRepository.transition(session.planId(), "BLOCKED_ANTI_BOT", "READY_TO_SUBMIT");
        }

        log.info("Handoff session {} completed by candidate. Plan {} set to READY_TO_SUBMIT", sessionId, session.planId());

        // Under Option B (External Human Completion):
        // Record candidate external submission on the application record
        recordExternalSubmission(ownerProfileId, session.applicationId(), sessionId, session.blockReason().name());

        if (audit != null) {
            audit.write(new AuditEntry(
                    "CANDIDATE",
                    "ATS_HANDOFF_COMPLETED",
                    "AUTOMATION_PLAN",
                    session.planId(),
                    session.status().name(),
                    Map.of(
                            "mode", "EXTERNAL_COMPLETION",
                            "submissionType", "CANDIDATE_DIRECT",
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
        if (applicationId == null) {
            return;
        }
        try {
            if (applicationStatus != null && ownerProfileId != null) {
                String currentStatus = handoffRepository.getApplicationStatus(applicationId).orElse(null);
                if ("READY_TO_APPLY".equals(currentStatus)) {
                    applicationStatus.apply(ownerProfileId, applicationId, "APPLICATION_STARTED",
                            "handoff.start:" + sessionId, "candidate",
                            Map.of("sessionId", sessionId.toString(), "reason", reason));
                }
                applicationStatus.apply(ownerProfileId, applicationId, "APPLICATION_SUBMITTED",
                        "handoff.external_submit:" + sessionId, "candidate",
                        Map.of("sessionId", sessionId.toString(), "mode", "EXTERNAL_COMPLETION", "reason", reason));
            }
            handoffRepository.markApplicationSubmittedDirectly(applicationId, ownerProfileId);
        } catch (Exception e) {
            log.warn("Could not transition application status via ApplicationStatusService for session {}: {}", sessionId, e.getMessage());
            handoffRepository.markApplicationSubmittedDirectly(applicationId, ownerProfileId);
        }
    }

    public int expireStaleSessions() {
        return handoffRepository.expireStaleSessions();
    }

    /**
     * Cancels an active handoff session upon candidate request.
     */
    public boolean cancelHandoff(UUID ownerProfileId, UUID sessionId) {
        Optional<HandoffSessionRecord> sessionOpt = handoffRepository.findById(ownerProfileId, sessionId);
        if (sessionOpt.isEmpty()) {
            return false;
        }

        HandoffSessionRecord session = sessionOpt.get();
        boolean cancelled = handoffRepository.cancelSession(ownerProfileId, sessionId);
        if (cancelled) {
            planRepository.transition(session.planId(), "HANDOFF_REQUIRED", "FAILED");
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
