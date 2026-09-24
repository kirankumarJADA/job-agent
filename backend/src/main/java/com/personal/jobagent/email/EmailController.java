package com.personal.jobagent.email;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * /api/v1/emails — ingest a message, and extract/resolve the OTP an employer
 * portal emailed.
 *
 * <p>The owning profile comes from the session and is passed to the service on
 * every call, so an email is stored against the account that ingested it and an
 * extraction can only be requested against that account's own message and
 * automation session. Ingestion without a profile is refused rather than stored
 * unowned: an unowned email is either invisible to its rightful owner or
 * visible to everyone, and neither is acceptable.
 */
@RestController
@RequestMapping("/api/v1/emails")
public class EmailController {

    private final EmailIntelligenceService service;
    private final OwnerContext ownerContext;

    public EmailController(EmailIntelligenceService service, OwnerContext ownerContext) {
        this.service = service;
        this.ownerContext = ownerContext;
    }

    public record IngestRequest(String messageId, String from, String to, String subject, String body, Instant receivedAt) {
    }

    public record VerifyRequest(UUID emailId, UUID sessionId, String expectedDomain) {
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody IngestRequest r, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found",
                    "No profile exists for this account.", request.getRequestURI(), correlationId()));
        }
        return ResponseEntity.ok(service.ingest(profileId, r.messageId(), r.from(), r.to(), r.subject(), r.body(),
                r.receivedAt() == null ? Instant.now() : r.receivedAt()));
    }

    @PostMapping("/verification/extract")
    public Map<String, Object> extract(@RequestBody VerifyRequest r) {
        return service.extractVerification(ownerContext.profileIdOrNull(), r.emailId(), r.sessionId(), r.expectedDomain())
                .orElse(Map.of("accepted", false));
    }

    @PostMapping("/verification/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable UUID id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(Map.of("accepted", true, "value", service.resolve(ownerContext.profileIdOrNull(), id)));
        } catch (IllegalStateException e) {
            // Same shape for "not yours", "expired" and "already used" — a caller
            // learns only that the code is not available.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found",
                    "verification unavailable", request.getRequestURI(), correlationId()));
        }
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}
