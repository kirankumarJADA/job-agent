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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmailController.class);

    private final EmailIntelligenceService service;
    private final OwnerContext ownerContext;

    public EmailController(EmailIntelligenceService service, OwnerContext ownerContext) {
        this.service = service;
        this.ownerContext = ownerContext;
    }

    /**
     * A message the candidate adds. {@code applicationId} is optional: when set,
     * it records the candidate's own choice of application (checked against the
     * message's identifiers, never treated as verification). {@code receivedAt}
     * is optional too; when absent the real time is recorded as unknown.
     */
    public record IngestRequest(String messageId, String from, String to, String subject, String body, Instant receivedAt,
                                UUID applicationId) {
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
        if (r == null || isBlank(r.subject()) && isBlank(r.body())) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request",
                    "A message needs a subject or a body.", request.getRequestURI(), correlationId()));
        }
        if (length(r.subject()) > 1_000 || length(r.body()) > 200_000 || length(r.from()) > 320 || length(r.to()) > 320
                || length(r.messageId()) > 500) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request",
                    "The message is too large.", request.getRequestURI(), correlationId()));
        }
        try {
            return ResponseEntity.ok(service.ingest(profileId, r.messageId(), r.from(), r.to(), r.subject(), r.body(),
                    r.receivedAt(), r.applicationId()));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found",
                    "No application with that id.", request.getRequestURI(), correlationId()));
        } catch (org.springframework.dao.DataAccessException e) {
            // The ingestion transaction rolled back as a whole, so nothing was
            // stored and adding the same message again is safe (idempotent). The
            // diagnostic names the failure and the request only: message content,
            // addresses and subjects never reach a log line.
            log.warn("Message ingestion failed and was rolled back ({}); correlation_id={}",
                    e.getClass().getSimpleName(), correlationId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "availability", "UNAVAILABLE",
                    "message", "The message could not be processed right now. Nothing was stored; adding it again is safe."));
        }
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}
