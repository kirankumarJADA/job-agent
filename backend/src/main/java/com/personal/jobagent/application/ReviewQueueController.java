package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/review-queue")
public class ReviewQueueController {
    private static final int MAX_REJECTION_REASON_LENGTH = 1000;
    private final ApplicationDecisionService decisions;
    private final ApplicationPipelineService pipeline;
    private final AuditLogWriter audit;
    private final NotificationService notifications;
    private final OwnerContext ownerContext;
    private final int expireDays;

    public ReviewQueueController(ApplicationDecisionService decisions, ApplicationPipelineService pipeline,
                                 AuditLogWriter audit, NotificationService notifications, OwnerContext ownerContext,
                                 @Value("${app.review.expire-days:14}") int expireDays) {
        this.decisions = decisions;
        this.pipeline = pipeline;
        this.audit = audit;
        this.notifications = notifications;
        this.ownerContext = ownerContext;
        this.expireDays = expireDays;
    }

    public record ReviewActionRequest(String reason) {}

    @GetMapping
    public ResponseEntity<?> list() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        var items = decisions.listForReview(profileId, expireDays);
        return ResponseEntity.ok(Map.of("items", items, "pendingCount", items.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID id) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        Map<String, Object> detail = decisions.loadOwned(profileId, id);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @PostMapping("/{id}/approve")
    @Transactional
    public ResponseEntity<?> approve(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        var item = decisions.loadOwned(profileId, id);
        if (item == null) return ResponseEntity.notFound().build();
        String current = String.valueOf(item.get("decision"));
        if ("APPROVED".equals(current)) {
            Object linked = item.get("application_id");
            if (linked == null) return ResponseEntity.status(409).body(Map.of("error", "approved item has no linked application"));
            UUID appId = UUID.fromString(String.valueOf(linked));
            return ResponseEntity.ok(Map.of("application_id", appId.toString(), "created", false,
                    "status", String.valueOf(item.getOrDefault("applicationStatus", "READY_TO_APPLY"))));
        }
        if (!"NEEDS_REVIEW".equals(current) && !"PAUSED".equals(current)) {
            return ResponseEntity.status(409).body(Map.of("error", "the decision is no longer awaiting review"));
        }
        Object applicationUrl = item.get("applicationUrl");
        if (applicationUrl == null || String.valueOf(applicationUrl).isBlank()) {
            return ResponseEntity.status(409).body(Map.of("error", "job posting has no application URL"));
        }
        String actor = ownerContext.actorOr("user");
        if (!decisions.transition(profileId, id, current, "APPROVED", actor, "Approved by candidate")) {
            return ResponseEntity.status(409).body(Map.of("error", "review state changed"));
        }
        var created = pipeline.createApplicationFromMatch(profileId, (UUID) item.get("jobId"));
        if (created == null || created.applicationId() == null) {
            decisions.transition(profileId, id, "APPROVED", current, null, null);
            return ResponseEntity.status(409).body(Map.of("error", "application could not be created"));
        }
        if (!decisions.linkApplication(profileId, id, created.applicationId())) {
            throw new IllegalStateException("Approved decision could not be linked to its application");
        }
        audit.write(new AuditEntry(actor, "REVIEW_ITEM_APPROVED", "APPLICATION_DECISION", id,
                Map.of("decision", current),
                Map.of("application_id", created.applicationId().toString(), "applicationCreated", created.created()),
                request.getRemoteAddr(), UuidV7.generate()));
        emitOutcome(NotificationEvents.REVIEW_APPROVED, profileId, id, item, created.applicationId(),
                "Review approved — application created",
                "Your approval was recorded. Application preparation is running.", "review-approved:" + id);
        return ResponseEntity.ok(Map.of("application_id", created.applicationId().toString(),
                "created", created.created(), "status", created.status()));
    }

    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<?> reject(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest body,
                                    HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        var item = decisions.loadOwned(profileId, id);
        if (item == null) return ResponseEntity.notFound().build();
        String current = String.valueOf(item.get("decision"));
        if (!"NEEDS_REVIEW".equals(current) && !"PAUSED".equals(current)) {
            return ResponseEntity.status(409).body(Map.of("error", "the decision is no longer awaiting review"));
        }
        String reason = body == null || body.reason() == null || body.reason().isBlank()
                ? "Rejected by candidate" : body.reason().trim();
        if (reason.length() > MAX_REJECTION_REASON_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "rejection reason must be at most 1000 characters"));
        }
        String actor = ownerContext.actorOr("user");
        if (!decisions.transition(profileId, id, current, "REJECTED", actor, reason)) {
            return ResponseEntity.status(409).body(Map.of("error", "review state changed"));
        }
        audit.write(new AuditEntry(actor, "REVIEW_ITEM_REJECTED", "APPLICATION_DECISION", id,
                Map.of("decision", current), Map.of("rejected", true, "reason", reason),
                request.getRemoteAddr(), UuidV7.generate()));
        emitOutcome(NotificationEvents.REVIEW_REJECTED, profileId, id, item, null,
                "Review rejected — application skipped", reason, "review-rejected:" + id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    @Transactional
    public ResponseEntity<?> pause(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        String actor = ownerContext.actorOr("user");
        if (!decisions.transition(profileId, id, "NEEDS_REVIEW", "PAUSED", actor, "Paused by candidate")) {
            return ResponseEntity.status(409).body(Map.of("error", "only awaiting-review items can be paused"));
        }
        audit.write(new AuditEntry(actor, "REVIEW_ITEM_PAUSED", "APPLICATION_DECISION", id,
                null, Map.of("paused", true), request.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resume")
    @Transactional
    public ResponseEntity<?> resume(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        String actor = ownerContext.actorOr("user");
        if (!decisions.transition(profileId, id, "PAUSED", "NEEDS_REVIEW", actor, "Resumed by candidate")) {
            return ResponseEntity.status(409).body(Map.of("error", "only paused items can be resumed"));
        }
        audit.write(new AuditEntry(actor, "REVIEW_ITEM_RESUMED", "APPLICATION_DECISION", id,
                null, Map.of("resumed", true), request.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.noContent().build();
    }

    private void emitOutcome(String type, UUID profileId, UUID decisionId, Map<String, Object> item,
                             UUID applicationId, String title, String body, String dedupKey) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("profile_id", profileId.toString());
        payload.put("job_id", String.valueOf(item.get("jobId")));
        payload.put("decision_id", decisionId.toString());
        if (applicationId != null) payload.put("application_id", applicationId.toString());
        payload.put("message", title);
        payload.put("detail", body);
        payload.put("link", applicationId == null ? "/review-queue" : "/applications");
        payload.put("dedup_key", dedupKey);
        notifications.emit(new NotificationService.NotificationCommand(type, "APPLICATION_DECISION", decisionId,
                payload, UuidV7.generate(), null));
    }
}
