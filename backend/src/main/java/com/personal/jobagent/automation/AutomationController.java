package com.personal.jobagent.automation;

import com.personal.jobagent.audit.*;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.OwnerContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * /api/v1/automation — automation plans and the worker event channel.
 *
 * <p><b>Who may touch a plan.</b> Two identities legitimately can: the candidate
 * the plan belongs to, and the Phase 6 worker (bearer token via
 * {@code WorkerEventTokenFilter}, principal {@code worker}). Everything else is
 * refused. Previously <em>any</em> authenticated session could read, claim,
 * heartbeat, step, approve-submit or complete <em>any</em> plan id — approving
 * submit on a stranger's application is the sharpest end of that.
 *
 * <p>The rule is expressed once, in {@link #mayActOnPlan}, instead of being
 * repeated per handler: a worker request may act on any plan, and a user request
 * may act only on one it owns. Reads use the owner-scoped repository method so a
 * foreign plan id is reported as 404, consistent with the rest of this API.
 *
 * <p><b>create</b> additionally requires that the referenced application is the
 * caller's own. Without that check a caller could attach an automation plan to
 * another candidate's application, and the worker would then drive that
 * application.
 *
 * <p>Operations with no per-row owner at all ({@code recover-stale}) are the
 * worker's job; in a deployment with no worker token configured (local
 * development, which is the pre-existing supported mode) they stay reachable by
 * a session so nothing that used to work stops working.
 */
@RestController
@RequestMapping("/api/v1/automation")
public class AutomationController {

    private final AutomationPlanRepository plans;
    private final AuditLogWriter audit;
    private final OwnerContext ownerContext;
    private final String workerToken;

    public AutomationController(AutomationPlanRepository plans,
                                AuditLogWriter audit,
                                OwnerContext ownerContext,
                                @Value("${app.worker-event-token:}") String workerToken) {
        this.plans = plans;
        this.audit = audit;
        this.ownerContext = ownerContext;
        this.workerToken = workerToken == null ? "" : workerToken;
    }

    public record CreateRequest(UUID applicationId, UUID jobId, String targetUrl, String idempotencyKey, List<AutomationPlan.Step> steps) {}
    public record StepRequest(int index, String stepId, String status, Map<String, Object> result, String screenshotRef) {}
    public record OutcomeRequest(String outcome, String detail) {}
    public record WorkerEvent(String eventId, UUID planId, UUID applicationId, UUID jobId, String type, Map<String, Object> payload) {}

    @PostMapping("/plans")
    public ResponseEntity<?> create(@RequestBody CreateRequest r) {
        if (r.applicationId() == null || r.jobId() == null || r.targetUrl() == null || r.idempotencyKey() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing correlation or target"));
        }
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.status(404).body(Map.of("error", "No profile exists for this account"));
        }
        // The plan is attached to an application, so the caller must own that
        // application; otherwise the worker ends up driving a stranger's.
        if (ownerContext.ownerOfApplication(r.applicationId()).filter(profileId::equals).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No application with that id"));
        }

        UUID id = plans.create(profileId, r.applicationId(), r.jobId(), r.targetUrl(), r.idempotencyKey(),
                r.steps() == null ? List.of() : r.steps());
        audit.write(new AuditEntry(ownerContext.actorOr("SYSTEM"), "AUTOMATION_PLAN_CREATED", "AUTOMATION_PLAN", id,
                null, Map.of("applicationId", r.applicationId().toString()), null, UuidV7.generate()));
        return ResponseEntity.ok(Map.of("planId", id, "status", "PREPARED"));
    }

    @GetMapping("/plans/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        if (ownerContext.isWorkerRequest()) {
            return plans.findById(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }
        return plans.find(ownerContext.profileIdOrNull(), id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/plans/{id}/claim")
    public ResponseEntity<?> claim(@PathVariable UUID id) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        return plans.claim(id) ? ResponseEntity.ok(Map.of("status", "RUNNING"))
                : ResponseEntity.status(409).body(Map.of("error", "plan is not PREPARED"));
    }

    @PostMapping("/plans/{id}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable UUID id) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        return plans.heartbeat(id) ? ResponseEntity.noContent().build() : ResponseEntity.status(409).build();
    }

    @PostMapping("/plans/{id}/steps")
    public ResponseEntity<?> step(@PathVariable UUID id, @RequestBody StepRequest r) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        return plans.appendStep(id, r.index(), r.stepId(), r.status(),
                r.result() == null ? Map.of() : r.result(), r.screenshotRef())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/plans/{id}/approve-submit")
    public ResponseEntity<?> approve(@PathVariable UUID id) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        return plans.approveSubmit(id) ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).body(Map.of("error", "submit approval requires AWAITING_SUBMIT_APPROVAL"));
    }

    @PostMapping("/plans/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable UUID id, @RequestBody OutcomeRequest r) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        String outcome = r.outcome();
        boolean ok = switch (outcome == null ? "" : outcome) {
            case "AWAITING_SUBMIT_APPROVAL", "COMPLETED", "FAILED", "BLOCKED_ANTI_BOT" -> plans.transition(id, "RUNNING", outcome);
            case "SUBMITTED" -> plans.findById(id).filter(p -> p.submitApproved()).map(p -> plans.transition(id, "RUNNING", outcome)).orElse(false);
            default -> false;
        };
        if (!ok) {
            return ResponseEntity.status(409).body(Map.of("error", "illegal or unsafe transition"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("worker"), "AUTOMATION_PLAN_" + outcome, "AUTOMATION_PLAN", id,
                null, Map.of("detail", r.detail() == null ? "" : r.detail()), null, UuidV7.generate()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/events")
    public ResponseEntity<?> event(@RequestBody WorkerEvent e) {
        if (e.eventId() == null || e.type() == null || e.planId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "event correlation required"));
        }
        if (!mayActOnPlan(e.planId())) {
            return notFound();
        }
        boolean fresh = plans.recordWorkerEvent(e.eventId(), e.planId(), e.applicationId(), e.jobId(), e.type(),
                e.payload() == null ? Map.of() : e.payload());
        return ResponseEntity.ok(Map.of("accepted", fresh, "replayed", !fresh));
    }

    @PostMapping("/plans/recover-stale")
    public ResponseEntity<?> recover(@RequestParam(defaultValue = "15") long minutes) {
        if (!machineOrLocalDev()) {
            return ResponseEntity.status(403).body(Map.of("error", "worker authentication required"));
        }
        return ResponseEntity.ok(Map.of("recovered", plans.recoverStale(Instant.now().minusSeconds(minutes * 60))));
    }

    // ── authorization helpers ─────────────────────────────────────────

    /**
     * The single authorization rule for anything addressed by plan id: the worker
     * may act on any plan, a user session only on a plan it owns.
     */
    private boolean mayActOnPlan(UUID planId) {
        if (ownerContext.isWorkerRequest()) {
            return true;
        }
        return plans.owns(ownerContext.profileIdOrNull(), planId);
    }

    /** True for the worker identity, or when this deployment has no worker token (local dev). */
    private boolean machineOrLocalDev() {
        return ownerContext.isWorkerRequest() || workerToken.isBlank();
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "Automation plan not found"));
    }
}
