package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.security.OwnerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3: REST API for candidate-controlled browser handoff during CAPTCHA/MFA security events.
 */
@RestController
@RequestMapping("/api/v1/handoff")
public class HandoffController {

    private final AtsHandoffService handoffService;
    private final OwnerContext ownerContext;
    private final boolean allowLocalTestHosts;

    @Autowired
    public HandoffController(
            AtsHandoffService handoffService,
            OwnerContext ownerContext,
            @Value("${app.sandbox.allow-local-test-hosts:false}") boolean allowLocalTestHosts) {
        this.handoffService = handoffService;
        this.ownerContext = ownerContext;
        this.allowLocalTestHosts = allowLocalTestHosts;
    }

    public HandoffController(AtsHandoffService handoffService, OwnerContext ownerContext) {
        this(handoffService, ownerContext, false);
    }

    public record InitiateRequest(
            UUID planId,
            UUID applicationId,
            String atsPlatform,
            String targetUrl,
            String blockReason,
            String domScreenshotRef,
            Integer ttlMinutes
    ) {}

    /**
     * Checks if an active handoff session is waiting for candidate action on this application.
     */
    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<?> getActiveHandoff(@PathVariable UUID applicationId) {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Worker tokens may not access candidate handoff endpoints"));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return handoffService.getActiveHandoffForApplication(profileId, applicationId)
                .map(HandoffSessionResponse::from)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Checks if an active handoff session exists for a specific automation plan.
     */
    @GetMapping("/plans/{planId}")
    public ResponseEntity<?> getActiveHandoffForPlan(@PathVariable UUID planId) {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Worker tokens may not access candidate handoff endpoints"));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return handoffService.getActiveHandoffForPlan(profileId, planId)
                .map(HandoffSessionResponse::from)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CompleteRequest(
            Boolean submittedExternally,
            String attestationNotes
    ) {}

    /**
     * Called by candidate UI when candidate has explicitly attested that they submitted
     * the application on the employer portal.
     * Atomically marks session COMPLETED and sets plan to terminal COMPLETED.
     */
    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<?> confirmCompletion(@PathVariable UUID sessionId,
                                               @RequestBody(required = false) CompleteRequest req) {
        if (req == null || !Boolean.TRUE.equals(req.submittedExternally())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "External submission attestation (submittedExternally = true) is required to complete handoff"
            ));
        }

        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Worker tokens may not complete handoff sessions"));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<HandoffSessionRecord> any = handoffService.findSessionAnyOwner(sessionId);
        if (any.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Handoff session not found"));
        }
        if (!profileId.equals(any.get().profileId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied: session belongs to another profile"));
        }

        try {
            boolean success = handoffService.confirmHandoffCompletion(profileId, sessionId);
            if (!success) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "Handoff session expired, cancelled, or not authorized for completion"
                ));
            }
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Handoff completion failed: " + e.getMessage()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "status", "COMPLETED",
                "message", "External submission attestation recorded. Application marked as candidate-submitted."
        ));
    }

    /**
     * Called by candidate UI to abandon/cancel a pending handoff session.
     */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<?> cancelHandoff(@PathVariable UUID sessionId) {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Worker tokens may not cancel handoff sessions"));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<HandoffSessionRecord> any = handoffService.findSessionAnyOwner(sessionId);
        if (any.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Handoff session not found"));
        }
        if (!profileId.equals(any.get().profileId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied: session belongs to another profile"));
        }

        try {
            boolean success = handoffService.cancelHandoff(profileId, sessionId);
            if (!success) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "Handoff session not found or already terminal"
                ));
            }
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Handoff cancellation failed: " + e.getMessage()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "status", "CANCELLED",
                "message", "Handoff cancelled by candidate."
        ));
    }

    /**
     * Stage 3D: Candidate asks Robin to resume the paused pre-submit automation plan
     * after updating employer credentials or addressing a pre-submit blocker.
     */
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<?> resumeAfterHandoff(@PathVariable UUID sessionId) {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Worker tokens may not resume handoff sessions"));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<HandoffSessionRecord> any = handoffService.findSessionAnyOwner(sessionId);
        if (any.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Handoff session not found"));
        }
        if (!profileId.equals(any.get().profileId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied: session belongs to another profile"));
        }

        try {
            UUID resumedPlanId = handoffService.resumeAfterHandoff(profileId, sessionId);
            if (resumedPlanId == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Handoff session not found"));
            }
            return ResponseEntity.ok(Map.of(
                    "sessionId", sessionId,
                    "planId", resumedPlanId,
                    "status", "COMPLETED",
                    "planStatus", "PREPARED",
                    "message", "Handoff resolved and automation plan re-queued for execution."
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Handoff resume refused: " + e.getMessage()
            ));
        }
    }

    /**
     * Worker or internal trigger to initiate handoff when security boundary is reached.
     */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiateHandoff(@RequestBody InitiateRequest req) {
        if (req == null || req.applicationId() == null || req.planId() == null || req.targetUrl() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "applicationId, planId, and targetUrl are required"));
        }

        try {
            AtsHandoffService.validateTargetUrl(req.targetUrl(), allowLocalTestHosts);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid target URL: " + e.getMessage()));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (profileId != null && !ownerContext.isWorkerRequest()) {
            boolean appOwned = handoffService.isApplicationOwnedBy(req.applicationId(), profileId);
            boolean planOwned = handoffService.isPlanOwnedBy(req.planId(), profileId);
            if (!appOwned || !planOwned) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied: application or plan belongs to another profile"));
            }
            if (!handoffService.isPlanAssociatedWithApplication(req.planId(), req.applicationId())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Plan does not belong to the specified application"));
            }
        } else if (profileId == null && ownerContext.isWorkerRequest()) {
            if (!handoffService.isPlanAssociatedWithApplication(req.planId(), req.applicationId())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Plan does not belong to the specified application"));
            }
            profileId = handoffService.resolveProfileId(req.planId(), req.applicationId()).orElse(null);
            if (profileId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unable to resolve owner profile for application/plan"));
            }
        }

        HandoffBlockReason reason = HandoffBlockReason.parse(req.blockReason());
        int ttl = req.ttlMinutes() != null ? req.ttlMinutes() : 15;

        try {
            HandoffSessionRecord session = handoffService.initiateHandoff(
                    profileId, req.planId(), req.applicationId(), req.targetUrl(), reason, ttl
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(HandoffSessionResponse.from(session));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }
}
