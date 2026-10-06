package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * /api/v1/review-queue — the human review queue over NEEDS_REVIEW decisions.
 *
 * <p>Phase 5 decided <em>whether</em> an APPLY match auto-applies; this queue
 * is where the human acts on everything that was queued instead. Every route
 * is owner-scoped (a foreign decision id is a 404, like every other resource
 * in this API), every state change is audited, and every transition is
 * guarded in SQL so concurrent clicks cannot double-apply or resurrect a
 * rejected item.
 *
 * <p>Approve creates the application through the same idempotent pipeline
 * the APPLY flow uses (the human decision replaces the automatic one), links
 * it to the decision row, and preparation proceeds exactly as if the match
 * had auto-applied.
 */
@RestController
@RequestMapping("/api/v1/review-queue")
public class ReviewQueueController {

    private final ApplicationDecisionService decisions;
    private final ApplicationPipelineService pipeline;
    private final AuditLogWriter audit;
    private final OwnerContext ownerContext;
    private final int expireDays;

    public ReviewQueueController(ApplicationDecisionService decisions,
                                 ApplicationPipelineService pipeline,
                                 AuditLogWriter audit,
                                 OwnerContext ownerContext,
                                 @Value("${app.review.expire-days:14}") int expireDays) {
        this.decisions = decisions;
        this.pipeline = pipeline;
        this.audit = audit;
        this.ownerContext = ownerContext;
        this.expireDays = expireDays;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        return ResponseEntity.ok(Map.of("items", decisions.listForReview(profileId, expireDays)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID id) {
        UUID profileId = ownerContext.profileIdOrNull();
        Map<String, Object> detail = decisions.loadOwned(profileId, id);
        return detail == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(detail);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        var item = decisions.loadOwned(profileId, id);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        String current = String.valueOf(item.get("decision"));
        // Idempotent replay: an already-approved item returns its application.
        if ("APPROVED".equals(current)) {
            UUID existingApplication = item.get("application_id") == null
                    ? null : UUID.fromString(String.valueOf(item.get("application_id")));
            if (existingApplication == null) {
                return ResponseEntity.status(409).body(Map.of("error", "approved item has no linked application"));
            }
            return ResponseEntity.ok(Map.of(
                    "application_id", existingApplication.toString(),
                    "created", false,
                    "status", "READY_TO_APPLY"));
        }
        if (!"NEEDS_REVIEW".equals(current) && !"PAUSED".equals(current)) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "the decision is no longer awaiting review"));
        }
        if (!decisions.transition(profileId, id, "NEEDS_REVIEW", "APPROVED")
                && !decisions.transition(profileId, id, "PAUSED", "APPROVED")) {
            return ResponseEntity.status(409).body(Map.of("error", "review state changed"));
        }
        var created = pipeline.createApplicationFromMatch(profileId, (UUID) item.get("job_id"));
        if (created == null || created.applicationId() == null) {
            // Roll the decision back so the item is actionable again.
            decisions.transition(profileId, id, "APPROVED", "NEEDS_REVIEW");
            return ResponseEntity.status(409).body(Map.of("error", "application could not be created"));
        }
        decisions.linkApplication(profileId, id, created.applicationId());
        audit.write(new AuditEntry(ownerContext.actorOr("user"), "REVIEW_ITEM_APPROVED", "APPLICATION_DECISION", id,
                Map.of("decision", item.get("decision") == null ? "" : item.get("decision")),
                Map.of("application_id", created.applicationId().toString(),
                        "applicationCreated", created.created()),
                request.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.ok(Map.of(
                "application_id", created.applicationId().toString(),
                "created", created.created(),
                "status", created.status()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        var item = decisions.loadOwned(profileId, id);
        if (item == null || !"NEEDS_REVIEW".equals(item.get("decision")) && !"PAUSED".equals(item.get("decision"))) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "the decision is no longer awaiting review"));
        }
        if (!decisions.transition(profileId, id, "NEEDS_REVIEW", "REJECTED")
                && !decisions.transition(profileId, id, "PAUSED", "REJECTED")) {
            return ResponseEntity.status(409).body(Map.of("error", "review state changed"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("user"), "REVIEW_ITEM_REJECTED", "APPLICATION_DECISION", id,
                Map.of("decision", String.valueOf(item.get("decision"))),
                Map.of("rejected", true),
                request.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        if (!decisions.transition(profileId, id, "NEEDS_REVIEW", "PAUSED")) {
            return ResponseEntity.status(409).body(Map.of("error", "only awaiting-review items can be paused"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("user"), "REVIEW_ITEM_PAUSED", "APPLICATION_DECISION", id,
                null, Map.of("paused", true), request.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        if (!decisions.transition(profileId, id, "PAUSED", "NEEDS_REVIEW")) {
            return ResponseEntity.status(409).body(Map.of("error", "only paused items can be resumed"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("user"), "REVIEW_ITEM_RESUMED", "APPLICATION_DECISION", id,
                null, Map.of("resumed", true), request.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.noContent().build();
    }

    /** Overload kept for the audit signature shape used across this API. */
    private ResponseEntity<?> notFound(HttpServletRequest request) {
        return ResponseEntity.status(404).body(ApiError.of(404, "Not Found",
                "No review item with that id", request.getRequestURI(),
                String.valueOf(org.slf4j.MDC.get("correlation_id"))));
    }
}
