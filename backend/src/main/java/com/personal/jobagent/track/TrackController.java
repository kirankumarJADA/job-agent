package com.personal.jobagent.track;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * TRACK endpoints. Every route is authenticated (SecurityConfig), owner-scoped
 * from the session — never from a request body — and answers 404 for another
 * account's application, message or evidence, exactly like a missing one.
 * Writes are CSRF-protected, rate-limited ("track" bucket) and audited.
 *
 * <pre>
 * GET  /api/v1/applications/{id}/track                       reconciled status, evidence, reviews
 * GET  /api/v1/applications/{id}/track/timeline?cursor&limit chronological timeline page
 * GET  /api/v1/applications/{id}/receipt                     durable submission receipt (latest attempt)
 * GET  /api/v1/applications/{id}/receipts/{attemptId}        durable receipt for specific attempt
 * GET  /api/v1/applications/{id}/receipts                    attempt history list for application
 * POST /api/v1/applications/{id}/track/reports               candidate report (e.g. "I applied myself")
 * POST /api/v1/applications/{id}/track/receipts/{rid}/review confirm/reject a piece of evidence
 * GET  /api/v1/track/reviews                                 employer messages awaiting the candidate
 * POST /api/v1/track/emails/{emailId}/review                 confirm/reject a message association
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class TrackController {

    private static final Logger log = LoggerFactory.getLogger(TrackController.class);

    private final ApplicationTrackingService tracking;
    private final ApplicationReceiptService receipts;
    private final OwnerContext ownerContext;

    public TrackController(ApplicationTrackingService tracking, ApplicationReceiptService receipts, OwnerContext ownerContext) {
        this.tracking = tracking;
        this.receipts = receipts;
        this.ownerContext = ownerContext;
    }

    public record ReportRequest(String kind, String occurredOn, String note, String clientReportId) {
    }

    public record ReceiptReviewRequest(String decision, String note, Integer expectedVersion) {
    }

    public record MessageReviewRequest(String decision, UUID applicationId, String category, Integer expectedVersion) {
    }

    @GetMapping("/applications/{id}/track")
    public ResponseEntity<?> track(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        return run(request, () -> tracking.view(profileId, id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(request, "No application with that id")));
    }

    @GetMapping("/applications/{id}/track/timeline")
    public ResponseEntity<?> timeline(@PathVariable UUID id, @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        return run(request, () -> tracking.timeline(profileId, id, cursor, limit)
                .<ResponseEntity<?>>map(page -> ResponseEntity.ok(Map.of("items", page.items(),
                        "nextCursor", page.nextCursor() == null ? "" : page.nextCursor(), "limit", page.limit())))
                .orElseGet(() -> notFound(request, "No application with that id")));
    }

    @GetMapping("/applications/{id}/receipt")
    public ResponseEntity<?> receipt(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        return run(request, () -> receipts.getReceipt(profileId, id, null)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(request, "No application with that id")));
    }

    @GetMapping("/applications/{id}/receipts/{attemptId}")
    public ResponseEntity<?> receiptAttempt(@PathVariable UUID id, @PathVariable UUID attemptId, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        return run(request, () -> receipts.getReceipt(profileId, id, attemptId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(request, "No receipt attempt with that id")));
    }

    @GetMapping("/applications/{id}/receipts")
    public ResponseEntity<?> receiptsList(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        return run(request, () -> ResponseEntity.ok(Map.of("items", receipts.getAttempts(profileId, id))));
    }

    @PostMapping("/applications/{id}/track/reports")
    public ResponseEntity<?> report(@PathVariable UUID id, @RequestBody(required = false) ReportRequest body,
                                    HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return notFound(request, "No application with that id");
        return run(request, () -> ResponseEntity.ok(tracking.report(profileId, id, body == null ? null
                : new ApplicationTrackingService.ReportCommand(body.kind(), body.occurredOn(), body.note(), body.clientReportId()))));
    }

    @PostMapping("/applications/{id}/track/receipts/{receiptId}/review")
    public ResponseEntity<?> reviewReceipt(@PathVariable UUID id, @PathVariable UUID receiptId,
                                           @RequestBody(required = false) ReceiptReviewRequest body,
                                           HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return notFound(request, "No such evidence for this application");
        if (body == null) return badRequest(request, "decision must be CONFIRM or REJECT");
        return run(request, () -> ResponseEntity.ok(tracking.reviewReceipt(profileId, id, receiptId, body.decision(),
                body.note(), body.expectedVersion())));
    }

    @GetMapping("/track/reviews")
    public ResponseEntity<?> reviews(HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        return run(request, () -> ResponseEntity.ok(Map.of("items", tracking.reviews(profileId),
                "mailboxConnected", tracking.mailboxConnected())));
    }

    @PostMapping("/track/emails/{emailId}/review")
    public ResponseEntity<?> reviewMessage(@PathVariable UUID emailId, @RequestBody(required = false) MessageReviewRequest body,
                                           HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return notFound(request, "No such message");
        if (body == null) return badRequest(request, "decision must be CONFIRM or REJECT");
        return run(request, () -> ResponseEntity.ok(tracking.reviewMessage(profileId, emailId,
                new ApplicationTrackingService.MessageReviewCommand(body.decision(), body.applicationId(), body.category(),
                        body.expectedVersion()))));
    }

    private ResponseEntity<?> run(HttpServletRequest request, Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (NoSuchElementException e) {
            return notFound(request, e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(request, e.getMessage());
        } catch (ApplicationTrackingService.TrackConflictException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict", e.getMessage(),
                    request.getRequestURI(), correlationId()));
        } catch (DataAccessException e) {
            // A dependency failure is an explicit unavailable state — never an
            // empty timeline and never a guessed status.
            log.warn("TRACK request failed on the database: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "availability", "UNAVAILABLE",
                    "message", "Tracking is unavailable right now. Nothing was changed."));
        }
    }

    private ResponseEntity<?> notFound(HttpServletRequest request, String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found",
                message == null ? "Not found" : message, request.getRequestURI(), correlationId()));
    }

    private ResponseEntity<?> badRequest(HttpServletRequest request, String message) {
        return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", message, request.getRequestURI(),
                correlationId()));
    }

    private static String correlationId() {
        return String.valueOf(MDC.get("correlation_id"));
    }
}
