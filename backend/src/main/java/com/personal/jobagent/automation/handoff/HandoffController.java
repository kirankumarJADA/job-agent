package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.security.OwnerContext;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public HandoffController(AtsHandoffService handoffService, OwnerContext ownerContext) {
        this.handoffService = handoffService;
        this.ownerContext = ownerContext;
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
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return handoffService.getActiveHandoffForApplication(profileId, applicationId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Checks if an active handoff session exists for a specific automation plan.
     */
    @GetMapping("/plans/{planId}")
    public ResponseEntity<?> getActiveHandoffForPlan(@PathVariable UUID planId) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return handoffService.getActiveHandoffForPlan(profileId, planId)
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
        if (req != null && Boolean.FALSE.equals(req.submittedExternally())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "External submission attestation is required to complete handoff"
            ));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (profileId != null && !ownerContext.isWorkerRequest()) {
            Optional<HandoffSessionRecord> any = handoffService.findSessionAnyOwner(sessionId);
            if (any.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Handoff session not found"));
            }
            if (!profileId.equals(any.get().profileId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied: session belongs to another profile"));
            }
        }

        boolean success = handoffService.confirmHandoffCompletion(profileId, sessionId);
        if (!success) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Handoff session expired, cancelled, or not authorized for completion"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "status", "COMPLETED",
                "message", "External submission attestation recorded. Application marked as candidate-submitted."
        ));
    }

    public ResponseEntity<?> confirmCompletion(UUID sessionId) {
        return confirmCompletion(sessionId, null);
    }

    /**
     * Called by candidate UI to abandon/cancel a pending handoff session.
     */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<?> cancelHandoff(@PathVariable UUID sessionId) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (profileId != null && !ownerContext.isWorkerRequest()) {
            Optional<HandoffSessionRecord> any = handoffService.findSessionAnyOwner(sessionId);
            if (any.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Handoff session not found"));
            }
            if (!profileId.equals(any.get().profileId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied: session belongs to another profile"));
            }
        }

        boolean success = handoffService.cancelHandoff(profileId, sessionId);
        if (!success) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Handoff session not found or already terminal"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "status", "CANCELLED",
                "message", "Handoff cancelled by candidate."
        ));
    }

    /**
     * Worker or internal trigger to initiate handoff when security boundary is reached.
     */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiateHandoff(@RequestBody InitiateRequest req) {
        if (req == null || req.applicationId() == null || req.targetUrl() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "applicationId and targetUrl are required"));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (profileId != null && !ownerContext.isWorkerRequest()) {
            boolean owned = handoffService.isApplicationOwnedBy(req.applicationId(), profileId);
            if (!owned) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied: application belongs to another profile"));
            }
        } else if (profileId == null && ownerContext.isWorkerRequest()) {
            profileId = handoffService.resolveProfileId(req.planId(), req.applicationId()).orElse(null);
            if (profileId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unable to resolve owner profile for application/plan"));
            }
        }

        HandoffBlockReason reason = HandoffBlockReason.parse(req.blockReason());
        int ttl = req.ttlMinutes() != null ? req.ttlMinutes() : 15;

        HandoffSessionRecord session = handoffService.initiateHandoff(
                profileId, req.planId(), req.applicationId(), req.targetUrl(), reason, ttl
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }
}
