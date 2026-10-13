package com.personal.jobagent.apply;

import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.ExecutionPackageBlockedException;
import com.personal.jobagent.automation.GreenhouseExecutionPlanService;
import com.personal.jobagent.automation.InspectionPlanService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.security.OwnerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * /api/v1/apply — the APPLY review workspace's server side (Phase 8.2).
 *
 * <p>Everything here is owner-scoped from the authenticated identity: a
 * foreign application id reads as 404, exactly like the rest of this API.
 * The readiness endpoint is a re-evaluation over current records on every
 * request — it never serves cached state — and package creation re-runs the
 * same gate server-side, so a stale frontend can never bypass a blocker.
 * Nothing here submits an application: REAL_SUBMIT stays hard-stopped.
 */
@RestController
@RequestMapping("/api/v1/apply")
public class ApplyController {

    private static final Logger log = LoggerFactory.getLogger(ApplyController.class);

    private final OwnerContext ownerContext;
    private final ApplyReadinessService readiness;
    private final GreenhouseExecutionPlanService greenhousePlans;
    private final InspectionPlanService inspectionPlans;
    private final JobRepository jobs;
    private final AuditLogWriter audit;

    public ApplyController(OwnerContext ownerContext, ApplyReadinessService readiness,
                           GreenhouseExecutionPlanService greenhousePlans, InspectionPlanService inspectionPlans,
                           JobRepository jobs, AuditLogWriter audit) {
        this.ownerContext = ownerContext;
        this.readiness = readiness;
        this.greenhousePlans = greenhousePlans;
        this.inspectionPlans = inspectionPlans;
        this.jobs = jobs;
        this.audit = audit;
    }

    @GetMapping("/applications/{id}/readiness")
    public ResponseEntity<?> readiness(@PathVariable UUID id) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        try {
            return ResponseEntity.ok(readiness.evaluate(profileId, id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (ApplyReadinessService.ReadinessUnavailableException e) {
            // A dependency failure is an explicit unavailable state — never a
            // false "ready" and never a false "blocked".
            return ResponseEntity.status(503).body(Map.of(
                    "availability", ApplyReadinessService.UNAVAILABLE,
                    "reason", e.getMessage(),
                    "message", "Preparation state could not be verified right now. Nothing has been approved."));
        }
    }

    /**
     * Builds the execution package for this application. The readiness gate
     * runs again server-side first: a stale frontend state cannot bypass a
     * blocker, and a refusal names the blockers instead of a generic error.
     */
    @PostMapping("/applications/{id}/package")
    public ResponseEntity<?> createPackage(@PathVariable UUID id) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        Map<String, Object> state;
        try {
            state = readiness.evaluate(profileId, id);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (ApplyReadinessService.ReadinessUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "availability", ApplyReadinessService.UNAVAILABLE,
                    "reason", e.getMessage(),
                    "message", "Preparation state could not be verified, so no package was created."));
        }
        if (!Boolean.TRUE.equals(state.get("packageReady"))) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "APPLY_PACKAGE_REFUSED", "APPLICATION", id,
                    null, Map.of("blockers", state.get("blockers")), null, UuidV7.generate()));
            return ResponseEntity.status(409).body(Map.of(
                    "error", "this application is not ready for an execution package",
                    "code", "APPLY_NOT_READY",
                    "blockers", state.get("blockers"),
                    "warnings", state.get("warnings"),
                    "unknowns", state.get("unknowns")));
        }
        UUID jobId = UUID.fromString(String.valueOf(
                ((Map<?, ?>) state.get("application")).get("jobId")));
        String applicationUrl = String.valueOf(
                ((Map<?, ?>) state.get("application")).get("applicationUrl"));
        try {
            var planId = jobs.findById(jobId)
                    .map(JobRecord::applicationUrl)
                    .filter(url -> url != null && greenhousePlans.handles(url))
                    .map(url -> greenhousePlans.createExecutionPlan(profileId, id, jobId))
                    .orElseGet(() -> inspectionPlans.createInspectionPlan(profileId, id, jobId));
            if (planId.isEmpty()) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "no automation plan could be created for this job's application URL",
                        "code", "NO_PLAN_TARGET"));
            }
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "APPLY_PACKAGE_CREATED", "APPLICATION", id,
                    null, Map.of("planId", planId.get().toString(), "jobId", jobId.toString(),
                            "applicationUrl", applicationUrl), null, UuidV7.generate()));
            return ResponseEntity.ok(Map.of("planId", planId.get().toString(), "status", "PREPARED"));
        } catch (ExecutionPackageBlockedException blocked) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "APPLY_PACKAGE_REFUSED", "APPLICATION", id,
                    null, Map.of("blockers", blocked.blockers()), null, UuidV7.generate()));
            return ResponseEntity.status(409).body(Map.of(
                    "error", "the exact document versions for this application cannot be selected",
                    "code", "EXECUTION_PACKAGE_BLOCKED",
                    "blockers", blocked.blockers()));
        } catch (IllegalStateException e) {
            log.warn("Package creation failed for application {}: {}", id, e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "the application form could not be inspected right now",
                    "code", "FORM_INSPECTION_UNAVAILABLE"));
        }
    }
}
