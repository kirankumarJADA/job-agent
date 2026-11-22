package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.security.OwnerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
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
            String targetUrl,
            String blockReason,
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
     * Called by candidate UI when candidate has manually solved the CAPTCHA/MFA in their browser.
     * Atomically marks session COMPLETED and resets the plan to READY_TO_SUBMIT.
     */
    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<?> confirmCompletion(@PathVariable UUID sessionId) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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
                "message", "Verification confirmed. Application resumed and ready for submission."
        ));
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
        if (req == null || req.planId() == null || req.applicationId() == null || req.targetUrl() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "planId, applicationId, and targetUrl are required"));
        }

        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        HandoffBlockReason reason = HandoffBlockReason.parse(req.blockReason());
        int ttl = req.ttlMinutes() != null ? req.ttlMinutes() : 15;

        HandoffSessionRecord session = handoffService.initiateHandoff(
                profileId, req.planId(), req.applicationId(), req.targetUrl(), reason, ttl
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }
}
