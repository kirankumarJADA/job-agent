package com.personal.jobagent.mailbox;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Worker-facing mailbox-verification boundary (Phase 8.5).
 *
 * <p>The worker authenticates with WORKER_EVENT_TOKEN and correlates the
 * request to one application session it is executing. The backend resolves
 * the OTP through the owner's connected Gmail (never from a request body),
 * checks employer domain + time window + session ownership, stores the
 * extraction audit trail, and hands the one-time code to the worker. If the
 * mailbox is not connected or nothing unambiguous is found, the endpoint says
 * so and the worker pauses for the human.
 */
@RestController
@RequestMapping("/api/v1/mailbox-verification")
public class MailboxVerificationController {

    private final MailboxVerificationProvider provider;
    private final OwnerContext ownerContext;
    private final int windowMinutes;

    public MailboxVerificationController(MailboxVerificationProvider provider,
                                         OwnerContext ownerContext,
                                         @Value("${app.mailbox-verification.window-minutes:30}") int windowMinutes) {
        this.provider = provider;
        this.ownerContext = ownerContext;
        this.windowMinutes = windowMinutes;
    }

    public record ResolveRequest(UUID applicationId, UUID sessionId,
                                 String employerDomain, String mailboxAddress) {}

    /** Owner probe: is a mailbox verification provider connected for this profile? */
    @GetMapping("/connected")
    public ResponseEntity<?> connected() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        return ResponseEntity.ok(Map.of("connected", provider.connected(profileId),
                "provider", provider.providerId()));
    }

    /**
     * Worker call during ACCOUNT_VERIFY_OTP: resolves the freshest OTP for
     * this application's employer domain. 404 when nothing unambiguous is
     * found (the worker then pauses for the human).
     */
    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestBody ResolveRequest r, HttpServletRequest request) {
        if (!ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(403).body(ApiError.of(403, "Forbidden",
                    "Only the automation worker may resolve mailbox verification codes.",
                    request.getRequestURI(), correlationId()));
        }
        if (r == null || r.applicationId() == null || r.employerDomain() == null || r.employerDomain().isBlank()) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request",
                    "applicationId and employerDomain are required", request.getRequestURI(), correlationId()));
        }
        // The application must belong to the profile whose mailbox is queried:
        // the provider is scoped to that profile, so cross-user mail is unreachable.
        UUID ownerProfileId = ownerContext.ownerOfApplication(r.applicationId()).orElse(null);
        if (ownerProfileId == null) {
            return ResponseEntity.status(404).body(ApiError.of(404, "Not Found",
                    "No such application", request.getRequestURI(), correlationId()));
        }
        Instant after = Instant.now().minusSeconds(windowMinutes * 60L);
        var hit = provider.findVerification(ownerProfileId, r.employerDomain(),
                r.mailboxAddress(), after);
        if (hit.isEmpty() || (!hit.get().hasOtp() && !hit.get().hasLink())) {
            return ResponseEntity.status(404).body(ApiError.of(404, "Not Found",
                    "No unambiguous verification message found in the connected mailbox. "
                            + "The workflow is paused for manual verification.",
                    request.getRequestURI(), correlationId()));
        }
        var hitValue = hit.get();
        // The OTP is handed to the authenticated worker only; the audit log
        // entry above records who, when and for which application.
        return ResponseEntity.ok(Map.of(
                "provider", hitValue.providerId(),
                "otp", hitValue.hasOtp() ? hitValue.otp() : "",
                "link", hitValue.hasLink() ? hitValue.link() : "",
                "receivedAt", hitValue.receivedAt() == null ? "" : hitValue.receivedAt().toString()));
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}
