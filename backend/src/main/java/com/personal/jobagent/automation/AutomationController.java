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
    private final ExecutionPackageService executionPackages;
    private final AuditLogWriter audit;
    private final OwnerContext ownerContext;
    private final String workerToken;

    public AutomationController(AutomationPlanRepository plans,
                                AuditLogWriter audit,
                                OwnerContext ownerContext,
                                @Value("${app.worker-event-token:}") String workerToken,
                                ExecutionPackageService executionPackages) {
        this.plans = plans;
        this.audit = audit;
        this.ownerContext = ownerContext;
        this.workerToken = workerToken == null ? "" : workerToken;
        this.executionPackages = executionPackages;
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
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(403).body(Map.of("error", "human approval is required"));
        }
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        var plan = plans.find(ownerContext.profileIdOrNull(), id).orElse(null);
        if (plan == null || !"AWAITING_APPROVAL".equals(plan.status())) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "approval requires a validated plan awaiting human review"));
        }
        String rejection = approvalPrecondition(plan);
        if (rejection != null) {
            return ResponseEntity.status(409).body(Map.of("error", rejection));
        }
        if (!plans.approveForSubmission(id)) {
            return ResponseEntity.status(409).body(Map.of("error", "review state changed"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("user"), "GREENHOUSE_SUBMIT_APPROVED", "AUTOMATION_PLAN", id,
                null, Map.of("approvedForSubmission", true, "submissionEnabled", false), null, UuidV7.generate()));
        // Approval records intent only: READY_TO_SUBMIT plans are never served
        // to a worker again and no endpoint transitions them to SUBMITTED.
        return ResponseEntity.ok(Map.of("status", "READY_TO_SUBMIT", "submissionEnabled", false));
    }

    @PostMapping("/plans/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable UUID id, @RequestBody OutcomeRequest r) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        String outcome = r.outcome();
        boolean ok = switch (outcome == null ? "" : outcome) {
            case "HUMAN_REQUIRED", "COMPLETED", "FAILED", "BLOCKED_ANTI_BOT" ->
                    plans.transition(id, "RUNNING", outcome.equals("HUMAN_REQUIRED") ? "AWAITING_APPROVAL" : outcome);
            case "SUBMITTED" -> false;
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
        if (ownerContext.isWorkerRequest() && !workerEventCorrelates(e)) {
            return ResponseEntity.badRequest().body(Map.of("error", "worker event correlation does not match its plan"));
        }
        boolean fresh = plans.recordWorkerEvent(e.eventId(), e.planId(), e.applicationId(), e.jobId(), e.type(),
                e.payload() == null ? Map.of() : e.payload());
        return ResponseEntity.ok(Map.of("accepted", fresh, "replayed", !fresh));
    }

    /**
     * Atomically claims the oldest PREPARED plan and returns it in RUNNING
     * state. Worker-only: two workers polling concurrently never receive the
     * same plan (PostgreSQL {@code FOR UPDATE SKIP LOCKED}).
     *
     * <p>Returns 204 No Content when no plan is available, so the worker can
     * distinguish "nothing to do" from an error.
     */
    @PostMapping("/plans/claim-next")
    public ResponseEntity<?> claimNext() {
        if (!machineOrLocalDev()) {
            return ResponseEntity.status(403).body(Map.of("error", "worker authentication required"));
        }
        return plans.claimNext()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/plans/{id}/review")
    public ResponseEntity<?> review(@PathVariable UUID id,
                                    @RequestBody(required = false) Map<String, Object> review) {
        if (!mayActOnPlan(id) || ownerContext.isWorkerRequest()) return notFound();
        var plan = plans.find(ownerContext.profileIdOrNull(), id).orElse(null);
        if (plan == null || !"AWAITING_APPROVAL".equals(plan.status())) {
            return ResponseEntity.status(409).body(Map.of("error", "plan is not awaiting human review"));
        }
        Map<String, Object> body = review == null ? Map.of() : review;
        if (Boolean.TRUE.equals(body.get("acknowledge"))) {
            // Review is recorded and audited, but it is NOT approval and never
            // completes the plan. Only an explicit submit approval moves a
            // plan out of AWAITING_APPROVAL — to READY_TO_SUBMIT, not to
            // COMPLETED or SUBMITTED.
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "GREENHOUSE_FORM_REVIEWED", "AUTOMATION_PLAN", id,
                    null, Map.of("acknowledged", true, "submissionEnabled", false), null, UuidV7.generate()));
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.badRequest().body(Map.of("error", "review acknowledgement is required"));
    }

    /**
     * Server-side approval preconditions. The worker already enforced the
     * execution-time safety contract — selector allowlist, per-field value
     * verification, upload binding, VALIDATE hard stops (identity, login,
     * required fields/uploads), anti-bot detection — so a plan in
     * AWAITING_APPROVAL has passed that entire chain. Approval independently
     * re-verifies what the stored plan JSON must itself guarantee before a
     * human signature is attached to it: package/correlation identity, zero
     * unresolved required gaps, artifacts bound to immutable versions whose
     * stored checksums still match, and no submission-capable step anywhere
     * in the plan. Returns null when every precondition holds, otherwise a
     * human-readable rejection reason.
     */
    private String approvalPrecondition(AutomationPlanRepository.PlanRow plan) {
        Map<String, Object> stored = plan.plan() == null ? Map.of() : plan.plan();
        if (!"GREENHOUSE".equals(stored.get("planType"))) {
            return "only greenhouse execution plans can be approved for submission";
        }
        Map<String, Object> pkg = asObjectMap(stored.get("package"));
        Map<String, Object> correlation = asObjectMap(stored.get("correlation"));
        if (pkg.isEmpty() || correlation.isEmpty()
                || !Objects.equals(pkg.get("planId"), plan.id().toString())
                || !Objects.equals(pkg.get("applicationId"), correlation.get("applicationId"))
                || !Objects.equals(pkg.get("jobId"), correlation.get("jobId"))
                || !Objects.equals(pkg.get("expectedUrl"), plan.targetUrl())) {
            return "package correlation does not match its plan";
        }
        if (pkg.get("requiredGaps") instanceof List<?> gaps && !gaps.isEmpty()) {
            return "unresolved human-required fields remain (" + gaps.size() + ")";
        }
        if (stored.get("steps") instanceof List<?> steps) {
            for (Object o : steps) {
                Map<String, Object> step = asObjectMap(o);
                String type = String.valueOf(step.get("type"));
                if ("CLICK".equals(type) || "MOCK_SUBMIT".equals(type)) {
                    return "submission-capable steps cannot be approved";
                }
                if ("POLICY_CHECK".equals(type)
                        && "REAL_SUBMIT".equals(asObjectMap(step.get("params")).get("action"))) {
                    return "real submission steps cannot be approved";
                }
            }
        }
        UUID applicationId = uuid(correlation.get("applicationId"));
        UUID jobId = uuid(correlation.get("jobId"));
        UUID profileId = ownerContext.profileIdOrNull();
        if (applicationId == null || jobId == null || profileId == null) {
            return "plan correlation is incomplete";
        }
        String cvReason = verifyArtifact(plan.id(), profileId, applicationId, jobId, "cv", pkg.get("cv"), true);
        if (cvReason != null) return cvReason;
        String coverReason = verifyArtifact(plan.id(), profileId, applicationId, jobId, "cover-letter", pkg.get("coverLetter"), false);
        return coverReason != null ? coverReason : null;
    }

    /** Verifies one artifact precondition; null when satisfied, else the reason. */
    private String verifyArtifact(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                  String kind, Object artifact, boolean required) {
        Map<String, Object> meta = asObjectMap(artifact);
        if (meta.isEmpty()) {
            return required ? "the approved package has no bound " + kind + " artifact" : null;
        }
        Object versionId = meta.get("versionId");
        Object sha256 = meta.get("sha256");
        if (!(versionId instanceof String v) || v.isBlank() || !(sha256 instanceof String s) || s.isBlank()) {
            return "the " + kind + " artifact is missing its immutable version or checksum";
        }
        ExecutionPackageService.ArtifactBytes bytes;
        try {
            bytes = executionPackages.artifactBytes(planId, profileId, applicationId, jobId, kind, UUID.fromString(v));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "the " + kind + " artifact is not linked to this application: " + e.getMessage();
        }
        if (bytes == null || !s.equals(bytes.sha256())) {
            return "the " + kind + " artifact checksum does not match the stored version";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private UUID uuid(Object o) {
        try {
            return o == null ? null : UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * READ-ONLY execution package for a GREENHOUSE plan (Phase 3B):
     * candidate fields, artifact metadata + download URLs, ANSWERED answers,
     * inspected Greenhouse field metadata with per-field classification, and
     * the safety contract. Worker-authenticated or plan-owner scoped; contains
     * no credentials and no unapproved answers.
     */
    @GetMapping("/plans/{id}/package")
    public ResponseEntity<?> packageFor(@PathVariable UUID id) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        var plan = plans.findById(id).orElse(null);
        if (plan == null || plan.applicationId() == null) {
            return notFound();
        }
        UUID profileId = plans.ownerOfPlan(id).orElse(null);
        if (profileId == null) {
            return notFound();
        }
        UUID jobId = plans.jobIdOf(id).orElse(null);
        if (jobId == null) {
            return notFound();
        }
        try {
            return ResponseEntity.ok(executionPackages.build(id, profileId, plan.applicationId(), jobId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * READ-ONLY artifact download (cv | cover-letter) for a GREENHOUSE plan.
     * Streams the exact bytes whose sha256 is published in the execution
     * package, so the worker can verify after download.
     */
    @GetMapping("/plans/{id}/artifacts/{kind}")
    public ResponseEntity<?> artifact(@PathVariable UUID id, @PathVariable String kind,
                                      @RequestParam UUID versionId) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        var plan = plans.findById(id).orElse(null);
        if (plan == null || plan.applicationId() == null) {
            return notFound();
        }
        UUID profileId = plans.ownerOfPlan(id).orElse(null);
        UUID jobId = plans.jobIdOf(id).orElse(null);
        if (profileId == null || jobId == null) {
            return notFound();
        }
        if (!kind.equals("cv") && !kind.equals("cover-letter")) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown artifact kind"));
        }
        ExecutionPackageService.ArtifactBytes bytes;
        try {
            bytes = executionPackages.artifactBytes(id, profileId, plan.applicationId(), jobId, kind, versionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
        if (bytes == null) {
            return notFound();
        }
        return ResponseEntity.ok()
                .header("Content-Type", bytes.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + bytes.fileName() + "\"")
                .header("X-Artifact-Sha256", bytes.sha256())
                .body(bytes.content());
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
    private boolean workerEventCorrelates(WorkerEvent event) {
        var plan = plans.findById(event.planId()).orElse(null);
        if (plan == null || !Objects.equals(plan.applicationId(), event.applicationId())) return false;
        UUID owner = plans.ownerOfPlan(event.planId()).orElse(null);
        if (owner == null || event.jobId() == null) return false;
        return plans.jobIdOf(event.planId()).filter(event.jobId()::equals).isPresent();
    }

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
