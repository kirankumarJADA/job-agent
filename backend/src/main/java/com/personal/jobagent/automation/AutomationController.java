package com.personal.jobagent.automation;

import com.personal.jobagent.audit.*;
import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AutomationController.class);

    private final AutomationPlanRepository plans;
    private final ExecutionPackageService executionPackages;
    private final AuditLogWriter audit;
    private final OwnerContext ownerContext;
    private final String workerToken;
    private final NotificationService notifications;
    private final AutomationMetrics metrics;
    private final com.personal.jobagent.apply.ApplyReadinessService applyReadiness;
    private final ApplyDocumentSelector documentSelector;
    private final com.personal.jobagent.application.ApplicationStatusService applicationStatus;
    private final GreenhouseExecutionPlanService greenhousePlans;
    private final AshbyExecutionPlanService ashbyPlans;
    private final WorkdayExecutionPlanService workdayPlans;

    public AutomationController(AutomationPlanRepository plans,
                                AuditLogWriter audit,
                                OwnerContext ownerContext,
                                @Value("${app.worker-event-token:}") String workerToken,
                                ExecutionPackageService executionPackages,
                                NotificationService notifications,
                                AutomationMetrics metrics,
                                com.personal.jobagent.apply.ApplyReadinessService applyReadiness,
                                ApplyDocumentSelector documentSelector,
                                com.personal.jobagent.application.ApplicationStatusService applicationStatus,
                                GreenhouseExecutionPlanService greenhousePlans,
                                AshbyExecutionPlanService ashbyPlans,
                                WorkdayExecutionPlanService workdayPlans) {
        this.plans = plans;
        this.audit = audit;
        this.ownerContext = ownerContext;
        this.workerToken = workerToken == null ? "" : workerToken;
        this.executionPackages = executionPackages;
        this.notifications = notifications;
        this.metrics = metrics;
        this.applyReadiness = applyReadiness;
        this.documentSelector = documentSelector;
        this.applicationStatus = applicationStatus;
        this.greenhousePlans = greenhousePlans;
        this.ashbyPlans = ashbyPlans;
        this.workdayPlans = workdayPlans;
    }

    public record CreateRequest(UUID applicationId, UUID jobId, String targetUrl, String idempotencyKey, List<AutomationPlan.Step> steps) {}
    public record StepRequest(int index, String stepId, String status, Map<String, Object> result, String screenshotRef) {}
    public record OutcomeRequest(String outcome, String detail, List<Map<String, Object>> result) {
        public OutcomeRequest(String outcome, String detail) { this(outcome, detail, null); }
    }
    public record WorkerEvent(String eventId, UUID planId, UUID applicationId, UUID jobId, String type, Map<String, Object> payload) {}
    /** Phase 15: an optional, free-text note the candidate may attach when requesting reconciliation — never evidence by itself. */
    public record ReconcileRequest(String note) {}

    /**
     * Phase 15: the one, narrowly-scoped step forward from {@code
     * RECONCILIATION_REQUIRED} — a plan whose real submit-click attempt
     * (Greenhouse {@code REAL_SUBMIT_CLICK} or Ashby {@code
     * ASHBY_SUBMIT_CLICK}) never reached a confirmed outcome. This never
     * re-approves or re-clicks the SAME plan — {@code approvalPrecondition}
     * already refuses that permanently — it only ever creates a genuinely
     * NEW, independent plan ({@code GreenhouseExecutionPlanService}/{@code
     * AshbyExecutionPlanService}{@code #createRetryExecutionPlan}), and only
     * when the worker's own persisted evidence for THIS exact attempt is one
     * of the narrow, platform-confirmed-non-acceptance shapes listed in
     * {@link #SAFE_RETRY_CATEGORIES} — never from the candidate's own report
     * alone (a candidate statement is recorded for visibility via the
     * existing TRACK {@code /track/reports} endpoint, but is deliberately
     * never sufficient evidence here; see this phase's own report for why).
     * Every other, genuinely ambiguous category (the response was never
     * observed, the form was still present, the employer's own response was
     * blocked rather than cleanly rejected, or a crash left the attempt
     * unverified) has no automated path forward at all — the superseded plan
     * stays in {@code RECONCILIATION_REQUIRED} permanently, a deliberate,
     * disclosed dead end rather than a bypass button that overrides
     * uncertainty.
     */
    private static final Map<String, Set<String>> SAFE_RETRY_CATEGORIES = Map.of(
            // Greenhouse's own employer-side form validation refused the
            // submission before any server-side acceptance — see confirmation.js.
            "GREENHOUSE", Set.of("VALIDATION_ERROR"),
            // Ashby's own applicationForm.submit response reported success:false
            // — a platform-confirmed non-acceptance, never a guess — see ashby.js.
            "ASHBY", Set.of("SUBMISSION_REJECTED"),
            // Phase 19: Workday platform validation errors or explicit rejection
            "WORKDAY", Set.of("VALIDATION_ERROR", "SUBMISSION_REJECTED", "FORM_STILL_PRESENT"));

    @PostMapping("/plans/{id}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable UUID id, @RequestBody(required = false) ReconcileRequest r) {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(403).body(Map.of("error", "reconciliation requires a human action"));
        }
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        UUID profileId = ownerContext.profileIdOrNull();
        var plan = plans.find(profileId, id).orElse(null);
        if (plan == null || !"RECONCILIATION_REQUIRED".equals(plan.status())) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "reconciliation is only possible for a plan whose real submission outcome is unconfirmed"));
        }
        String planType = planTypeOf(plan);
        Map<String, Object> reconciliation = (plan.result() == null ? List.<Object>of() : plan.result()).stream()
                .map(this::asObjectMap)
                .filter(o -> "SUBMISSION_RECONCILIATION".equals(o.get("type")))
                .findFirst().orElse(null);
        String category = reconciliation == null ? null : String.valueOf(reconciliation.get("category"));
        Set<String> safeCategories = SAFE_RETRY_CATEGORIES.getOrDefault(planType, Set.of());
        if (category == null || !safeCategories.contains(category)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "this attempt's outcome cannot be safely resolved automatically — a human must verify "
                            + "directly with the employer before Robin can prepare another attempt",
                    "code", "RECONCILIATION_EVIDENCE_INSUFFICIENT",
                    "category", category == null ? "UNKNOWN" : category,
                    "evidence", reconciliation == null ? "no result was recorded for this attempt" : String.valueOf(reconciliation.get("evidence"))));
        }
        Map<String, Object> correlation = asObjectMap(plan.plan() == null ? null : plan.plan().get("correlation"));
        UUID jobId = uuid(correlation.get("jobId"));
        if (jobId == null) {
            return ResponseEntity.status(409).body(Map.of("error", "plan correlation is incomplete"));
        }
        Optional<UUID> retryPlanId = "ASHBY".equals(planType)
                ? ashbyPlans.createRetryExecutionPlan(profileId, plan.applicationId(), jobId, id)
                : "WORKDAY".equals(planType)
                ? workdayPlans.createRetryExecutionPlan(profileId, plan.applicationId(), jobId, id)
                : greenhousePlans.createRetryExecutionPlan(profileId, plan.applicationId(), jobId, id);
        if (retryPlanId.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("error", "could not prepare a new attempt for this application"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("user"), planType + "_RECONCILED_RETRY_CREATED", "AUTOMATION_PLAN", id,
                null, Map.of("supersededPlanId", id.toString(), "newPlanId", retryPlanId.get().toString(),
                        "category", category, "note", r == null || r.note() == null ? "" : r.note()),
                null, UuidV7.generate()));
        return ResponseEntity.ok(Map.of("status", "RECONCILIATION_RETRY_CREATED",
                "supersededPlanId", id, "newPlanId", retryPlanId.get()));
    }

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
        // Phase 8.2: approval re-runs the server-side readiness gate over the
        // CURRENT records. A stale frontend state cannot bypass a blocker, and
        // a refusal names the blockers instead of a generic error.
        UUID profileId = ownerContext.profileIdOrNull();
        Map<String, Object> state;
        try {
            state = applyReadiness.evaluate(profileId, plan.applicationId());
        } catch (java.util.NoSuchElementException e) {
            return notFound();
        } catch (com.personal.jobagent.apply.ApplyReadinessService.ReadinessUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "availability", "UNAVAILABLE", "reason", e.getMessage(),
                    "message", "Preparation state could not be verified, so nothing was approved."));
        }
        if (!Boolean.TRUE.equals(state.get("packageReady"))) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), planTypeOf(plan) + "_SUBMIT_APPROVAL_REFUSED",
                    "AUTOMATION_PLAN", id, null,
                    Map.of("blockers", state.get("blockers")), null, UuidV7.generate()));
            return ResponseEntity.status(409).body(Map.of(
                    "error", "the application is not ready for approval",
                    "code", "APPLY_NOT_READY",
                    "blockers", state.get("blockers")));
        }
        String drift = packageDrift(plan, profileId);
        if (drift != null) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), planTypeOf(plan) + "_SUBMIT_APPROVAL_REFUSED",
                    "AUTOMATION_PLAN", id, null,
                    Map.of("reason", drift), null, UuidV7.generate()));
            return ResponseEntity.status(409).body(Map.of("error", drift, "code", "PACKAGE_DRIFT"));
        }
        String rejection = approvalPrecondition(plan);
        if (rejection != null) {
            return ResponseEntity.status(409).body(Map.of("error", rejection));
        }
        if (!plans.approveForSubmission(id)) {
            return ResponseEntity.status(409).body(Map.of("error", "review state changed"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("user"), planTypeOf(plan) + "_SUBMIT_APPROVED", "AUTOMATION_PLAN", id,
                null, Map.of("approvedForSubmission", true, "submissionEnabled", false), null, UuidV7.generate()));
        metrics.submitApproved();
        // Approval records intent only: READY_TO_SUBMIT plans are never served
        // to a worker again and no endpoint transitions them to SUBMITTED.
        return ResponseEntity.ok(Map.of("status", "READY_TO_SUBMIT", "submissionEnabled", false));
    }

    /**
     * Phase 4: the one, deliberately separate human action that queues the
     * real submission click for an already-approved plan. Distinct from
     * {@link #approve}: approval alone never did and still does not enable
     * submission (READY_TO_SUBMIT only records intent) — this endpoint is
     * the second, explicit confirmation that actually appends the
     * REAL_SUBMIT_CLICK step, and it independently re-verifies readiness and
     * document drift again, exactly as approval did, since time may have
     * passed since the plan was approved.
     */
    @PostMapping("/plans/{id}/prepare-submit")
    public ResponseEntity<?> prepareSubmit(@PathVariable UUID id) {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(403).body(Map.of("error", "queuing a real submission requires a human action"));
        }
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        UUID profileId = ownerContext.profileIdOrNull();
        var plan = plans.find(profileId, id).orElse(null);
        if (plan == null || !"READY_TO_SUBMIT".equals(plan.status()) || !plan.submitApproved()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "a plan can only be queued for real submission once it is approved and READY_TO_SUBMIT"));
        }
        String drift = packageDrift(plan, profileId);
        if (drift != null) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), planTypeOf(plan) + "_SUBMIT_QUEUE_REFUSED",
                    "AUTOMATION_PLAN", id, null, Map.of("reason", drift), null, UuidV7.generate()));
            return ResponseEntity.status(409).body(Map.of("error", drift, "code", "PACKAGE_DRIFT"));
        }
        // Phase 14: ASHBY is the first plan type besides GREENHOUSE to reach
        // this point at all — approvalPrecondition() refuses every other
        // plan type, so only these two queuing methods are ever dispatched
        // to here.
        String planType = planTypeOf(plan);
        boolean queued = "ASHBY".equals(planType)
                ? plans.prepareAshbySubmission(id)
                : "WORKDAY".equals(planType)
                ? plans.prepareWorkdaySubmission(id)
                : plans.prepareRealSubmission(id);
        if (!queued) {
            return ResponseEntity.status(409).body(Map.of("error", "plan state changed"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("user"), planType + "_SUBMIT_QUEUED", "AUTOMATION_PLAN", id,
                null, Map.of("submissionQueued", true), null, UuidV7.generate()));
        return ResponseEntity.ok(Map.of("status", "PREPARED", "submissionQueued", true));
    }

    @PostMapping("/plans/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable UUID id, @RequestBody OutcomeRequest r) {
        if (!mayActOnPlan(id)) {
            return notFound();
        }
        String outcome = r.outcome();
        // A real submission outcome may only be reported by the worker
        // identity, and only with the confirmation evidence #runRealSubmitClick
        // actually observed — never fabricated, and never silently absent.
        if ("SUBMITTED".equals(outcome) && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(403).body(Map.of("error", "only the worker may report a real submission"));
        }
        if ("SUBMITTED".equals(outcome) && (r.detail() == null || r.detail().isBlank())) {
            return ResponseEntity.status(400).body(Map.of("error", "a real submission outcome requires confirmation evidence"));
        }
        // Phase 15: RECONCILIATION_REQUIRED reports the same kind of fact as
        // SUBMITTED — the worker actually clicked a real submit action and is
        // reporting what it observed — so the same two guards apply: only the
        // worker identity may report it, and only with evidence attached.
        if ("RECONCILIATION_REQUIRED".equals(outcome) && !ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(403).body(Map.of("error", "only the worker may report an unconfirmed real submission"));
        }
        if ("RECONCILIATION_REQUIRED".equals(outcome) && (r.detail() == null || r.detail().isBlank())) {
            return ResponseEntity.status(400).body(Map.of("error", "an unconfirmed real submission outcome requires evidence"));
        }
        // Phase 15 defence in depth: a plan whose last step is a real
        // submission click can never resolve to HUMAN_REQUIRED/AWAITING_APPROVAL
        // — the worker's own outcome.js never sends that combination any more
        // (see computePlanOutcome), but this refuses it here too, since
        // AWAITING_APPROVAL would otherwise be re-approvable through the
        // normal review flow for a plan that may already have reached the
        // employer.
        if ("HUMAN_REQUIRED".equals(outcome) && isRealSubmitAttempt(id)) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "a real submission attempt can never resolve to HUMAN_REQUIRED; report RECONCILIATION_REQUIRED instead"));
        }
        boolean ok = switch (outcome == null ? "" : outcome) {
            case "HUMAN_REQUIRED", "COMPLETED", "FAILED", "BLOCKED_ANTI_BOT", "RECONCILIATION_REQUIRED" -> {
                String to = outcome.equals("HUMAN_REQUIRED") ? "AWAITING_APPROVAL" : outcome;
                yield r.result() != null && !r.result().isEmpty()
                        ? plans.transitionWithResult(id, "RUNNING", to, new java.util.ArrayList<>(r.result()))
                        : plans.transition(id, "RUNNING", to);
            }
            case "SUBMITTED" -> plans.completeRealSubmission(id);
            default -> false;
        };
        if (!ok) {
            return ResponseEntity.status(409).body(Map.of("error", "illegal or unsafe transition"));
        }
        audit.write(new AuditEntry(ownerContext.actorOr("worker"), "AUTOMATION_PLAN_" + outcome, "AUTOMATION_PLAN", id,
                null, Map.of("detail", r.detail() == null ? "" : r.detail()), null, UuidV7.generate()));
        metrics.planOutcome(outcome);
        if ("SUBMITTED".equals(outcome)) {
            markApplicationSubmitted(id, r.detail());
        }
        notifyPlanOutcome(id, outcome, r.detail());
        return ResponseEntity.noContent().build();
    }

    /** True when the plan's own stored last step is a real submission click (GREENHOUSE or ASHBY). */
    private boolean isRealSubmitAttempt(UUID id) {
        var plan = plans.findById(id).orElse(null);
        if (plan == null || plan.plan() == null) return false;
        Object stepsObj = plan.plan().get("steps");
        if (!(stepsObj instanceof List<?> steps) || steps.isEmpty()) return false;
        Map<String, Object> last = asObjectMap(steps.get(steps.size() - 1));
        String type = String.valueOf(last.get("type"));
        return "REAL_SUBMIT_CLICK".equals(type) || "ASHBY_SUBMIT_CLICK".equals(type) || "WORKDAY_SUBMIT_CLICK".equals(type);
    }

    /**
     * Mirrors a confirmed real submission onto the application's own status,
     * so the applications list/timeline (the surface the rest of the product
     * reads) reflects it, not just the automation plan row. Deliberately
     * tolerant of an unexpected current application status: the
     * automation_plans row reaching SUBMITTED is the authoritative,
     * database-guarded fact (completeRealSubmission's own atomic guard); a
     * mismatch here is logged, not allowed to undo or mask that fact.
     */
    private void markApplicationSubmitted(UUID planId, String detail) {
        var plan = plans.findById(planId).orElse(null);
        UUID profileId = plans.ownerOfPlan(planId).orElse(null);
        if (plan == null || plan.applicationId() == null || profileId == null) {
            return;
        }
        try {
            applicationStatus.apply(profileId, plan.applicationId(), "APPLICATION_SUBMITTED",
                    "automation.real_submit:" + planId, "worker",
                    Map.of("planId", planId.toString(), "detail", detail == null ? "" : detail));
        } catch (IllegalStateException | java.util.NoSuchElementException e) {
            log.warn("Could not mirror real submission onto application status for plan {}: {}", planId, e.getMessage());
        }
    }

    /**
     * Failures and human gates must not silently disappear in the worker
     * event log: the plan owner gets a notification through the standard
     * outbox fan-out. COMPLETED is deliberately silent — a finished
     * inspection is routine, and greenhouse runs never report COMPLETED
     * (they end at AWAITING_APPROVAL).
     */
    private void notifyPlanOutcome(UUID planId, String outcome, String detail) {
        String eventType = switch (outcome) {
            // Phase 15: reuses the existing APPROVAL_REQUIRED category — a
            // human needs to look at this plan, same as HUMAN_REQUIRED — but
            // the detail text and frontend review panel are what actually
            // distinguish "please review what was filled" from "a real
            // submission attempt could not be confirmed and now requires
            // reconciliation," never the notification category alone.
            case "HUMAN_REQUIRED", "RECONCILIATION_REQUIRED" -> NotificationEvents.APPROVAL_REQUIRED;
            case "FAILED" -> NotificationEvents.AUTOMATION_FAILURE;
            case "BLOCKED_ANTI_BOT" -> NotificationEvents.HARD_STOP;
            default -> null;
        };
        if (eventType == null) return;
        UUID profileId = plans.ownerOfPlan(planId).orElse(null);
        if (profileId == null) return;
        var plan = plans.findById(planId).orElse(null);
        Object jobId = "";
        if (plan != null && plan.plan() instanceof Map<?, ?> stored
                && stored.get("correlation") instanceof Map<?, ?> correlation) {
            jobId = String.valueOf(correlation.get("jobId"));
        }
        try {
            notifications.emit(new NotificationService.NotificationCommand(
                    eventType,
                    "AUTOMATION_PLAN",
                    planId,
                    Map.of("plan_id", planId.toString(),
                            "application_id", plan == null ? "" : String.valueOf(plan.applicationId()),
                            "job_id", String.valueOf(jobId),
                            "profile_id", profileId.toString(),
                            "detail", detail == null ? "" : detail),
                    UuidV7.generate(),
                    null));
        } catch (Exception e) {
            log.warn("Could not enqueue {} notification for plan {}: {}", eventType, planId, e.getMessage());
        }
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
            // COMPLETED or SUBMITTED. Phase 14: the audit action name truthfully
            // names which platform's form this is, rather than always saying
            // GREENHOUSE for an Ashby plan.
            audit.write(new AuditEntry(ownerContext.actorOr("user"), planTypeOf(plan) + "_FORM_REVIEWED", "AUTOMATION_PLAN", id,
                    null, Map.of("acknowledged", true, "submissionEnabled", false), null, UuidV7.generate()));
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.badRequest().body(Map.of("error", "review acknowledgement is required"));
    }

    /**
     * Phase 14: the stored {@code planType} a plan's own JSON carries,
     * defaulting to {@code GREENHOUSE} for the historical rows that predate
     * this field — never {@code null}, so every audit action name built from
     * it stays a valid, non-blank string.
     */
    private String planTypeOf(AutomationPlanRepository.PlanRow plan) {
        Object planType = plan.plan() == null ? null : plan.plan().get("planType");
        return planType == null ? "GREENHOUSE" : String.valueOf(planType);
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
        if ("ASHBY".equals(stored.get("planType"))) {
            return ashbyApprovalPrecondition(plan, stored);
        }
        if ("WORKDAY".equals(stored.get("planType"))) {
            return workdayApprovalPrecondition(plan, stored);
        }
        if (!"GREENHOUSE".equals(stored.get("planType"))) {
            return "only greenhouse, ashby, and workday execution plans can be approved for submission";
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
                // Phase 15 fix: this used to check only the literal legacy type
                // "CLICK", never the real step type "REAL_SUBMIT_CLICK" that
                // #prepareRealSubmission actually appends — so a plan carrying
                // a stale REAL_SUBMIT_CLICK step from an earlier, unconfirmed
                // attempt was never caught here at all. In practice the new
                // RECONCILIATION_REQUIRED status (see AutomationPlanRepository
                // and worker/src/outcome.js) already keeps such a plan out of
                // AWAITING_APPROVAL in the first place, so this never fires on
                // the normal path any more — this check is defense in depth
                // against a plan ever reaching AWAITING_APPROVAL some other
                // way while still carrying one.
                if ("CLICK".equals(type) || "MOCK_SUBMIT".equals(type) || "REAL_SUBMIT_CLICK".equals(type)) {
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

    /**
     * Phase 14: the ASHBY counterpart of the GREENHOUSE preconditions above —
     * structurally different because an Ashby plan's required-gap list is
     * never known upfront (Ashby's form is inspected live, in-browser, by
     * the worker, never statically on the backend the way
     * {@code GreenhouseExecutionPlanService} does), so it is read from the
     * plan's own persisted {@code ASHBY_OUTCOME} result row
     * (written by the {@code /complete} endpoint's {@code HUMAN_REQUIRED}
     * branch) rather than from {@code package.requiredGaps}. The forbidden-
     * step and artifact-drift checks are the same discipline as Greenhouse's,
     * adapted to Ashby's own package shape (cv only, no cover letter, no
     * top-level planId/expectedUrl fields — see
     * {@code AshbyExecutionPlanService#persistWorkerPlan}).
     */
    private String ashbyApprovalPrecondition(AutomationPlanRepository.PlanRow plan, Map<String, Object> stored) {
        if (stored.get("steps") instanceof List<?> steps) {
            for (Object o : steps) {
                Map<String, Object> step = asObjectMap(o);
                String type = String.valueOf(step.get("type"));
                if ("REAL_SUBMIT_CLICK".equals(type) || "MOCK_SUBMIT".equals(type) || "ASHBY_SUBMIT_CLICK".equals(type)) {
                    return "submission-capable steps cannot be approved";
                }
            }
        }
        Map<String, Object> outcome = (plan.result() == null ? List.<Object>of() : plan.result()).stream()
                .map(this::asObjectMap)
                .filter(o -> "ASHBY_OUTCOME".equals(o.get("type")))
                .findFirst().orElse(null);
        if (outcome == null) {
            return "no Ashby preparation outcome is recorded for this plan";
        }
        if (outcome.get("requiredGaps") instanceof List<?> gaps && !gaps.isEmpty()) {
            return "unresolved human-required fields remain (" + gaps.size() + ")";
        }
        Map<String, Object> correlation = asObjectMap(stored.get("correlation"));
        UUID applicationId = uuid(correlation.get("applicationId"));
        UUID jobId = uuid(correlation.get("jobId"));
        UUID profileId = ownerContext.profileIdOrNull();
        if (applicationId == null || jobId == null || profileId == null) {
            return "plan correlation is incomplete";
        }
        Map<String, Object> pkg = asObjectMap(stored.get("package"));
        return verifyArtifact(plan.id(), profileId, applicationId, jobId, "cv", pkg.get("cv"), false);
    }

    /**
     * Phase 19: Workday approval precondition check.
     */
    private String workdayApprovalPrecondition(AutomationPlanRepository.PlanRow plan, Map<String, Object> stored) {
        if (stored.get("steps") instanceof List<?> steps) {
            for (Object o : steps) {
                Map<String, Object> step = asObjectMap(o);
                String type = String.valueOf(step.get("type"));
                if ("REAL_SUBMIT_CLICK".equals(type) || "MOCK_SUBMIT".equals(type) || "WORKDAY_SUBMIT_CLICK".equals(type)) {
                    return "submission-capable steps cannot be approved";
                }
            }
        }
        Map<String, Object> outcome = (plan.result() == null ? List.<Object>of() : plan.result()).stream()
                .map(this::asObjectMap)
                .filter(o -> "WORKDAY_APPLICATION_OUTCOME".equals(o.get("type")) || "WORKDAY_OUTCOME".equals(o.get("type")))
                .findFirst().orElse(null);
        if (outcome == null) {
            return "no Workday preparation outcome is recorded for this plan";
        }
        if (outcome.get("requiredGaps") instanceof List<?> gaps && !gaps.isEmpty()) {
            return "unresolved human-required fields remain (" + gaps.size() + ")";
        }
        Map<String, Object> correlation = asObjectMap(stored.get("correlation"));
        UUID applicationId = uuid(correlation.get("applicationId"));
        UUID jobId = uuid(correlation.get("jobId"));
        UUID profileId = ownerContext.profileIdOrNull();
        if (applicationId == null || jobId == null || profileId == null) {
            return "plan correlation is incomplete";
        }
        Map<String, Object> pkg = asObjectMap(stored.get("package"));
        if (!pkg.isEmpty()) {
            String cvReason = verifyArtifact(plan.id(), profileId, applicationId, jobId, "cv", pkg.get("cv"), false);
            if (cvReason != null) return cvReason;
            String clReason = verifyArtifact(plan.id(), profileId, applicationId, jobId, "coverLetter", pkg.get("coverLetter"), false);
            if (clReason != null) return clReason;
        }
        return null;
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

    /**
     * Phase 8.2: the package is a snapshot of exact document versions and
     * confirmed answers. If anything it carries moved underneath it — document
     * replacement, approval withdrawal, an edited or unconfirmed answer — the
     * approval statement is no longer true and the plan must be rebuilt and
     * re-reviewed. Returns null while the package still matches its records.
     */
    private String packageDrift(AutomationPlanRepository.PlanRow plan, UUID profileId) {
        Map<String, Object> stored = plan.plan() == null ? Map.of() : plan.plan();
        Map<String, Object> pkg = asObjectMap(stored.get("package"));
        Map<String, Object> correlation = asObjectMap(stored.get("correlation"));
        UUID applicationId = uuid(correlation.get("applicationId"));
        UUID jobId = uuid(correlation.get("jobId"));
        if (applicationId == null || jobId == null) return "plan correlation is incomplete";
        ApplyDocumentSelector.DocumentSelection selection = documentSelector.select(profileId, jobId, applicationId);
        if (selection.blocked()) {
            Object first = selection.blockers().isEmpty() ? null : selection.blockers().get(0).get("message");
            return "the package's documents are no longer usable: " + first;
        }
        Map<String, Object> cv = asObjectMap(pkg.get("cv"));
        if (!cv.isEmpty()) {
            if (selection.cv() == null) {
                return "the CV in this package is no longer selected for this application; rebuild the package";
            }
            if (!String.valueOf(selection.cv().versionId()).equals(cv.get("versionId"))) {
                return "the CV attached to this package is no longer the reviewed version; rebuild the package";
            }
        }
        Map<String, Object> cover = asObjectMap(pkg.get("coverLetter"));
        if (!cover.isEmpty()) {
            if (selection.coverLetter() == null) {
                return "the cover letter in this package is no longer approved for this application; rebuild the package";
            }
            if (!String.valueOf(selection.coverLetter().versionId()).equals(cover.get("versionId"))) {
                return "the cover letter in this package is no longer the approved version; rebuild the package";
            }
        }
        List<Map<String, Object>> packagedAnswers = pkg.get("answers") instanceof List<?> list
                ? list.stream().filter(o -> o instanceof Map).map(this::asObjectMap).toList()
                : List.of();
        if (!executionPackages.answersStillCurrent(profileId, applicationId, jobId, packagedAnswers)) {
            return "an answer in this package has changed since it was built; rebuild the package";
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
