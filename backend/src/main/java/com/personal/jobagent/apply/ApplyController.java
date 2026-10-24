package com.personal.jobagent.apply;

import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AshbyExecutionPlanService;
import com.personal.jobagent.automation.ExecutionPackageBlockedException;
import com.personal.jobagent.automation.GreenhouseExecutionPlanService;
import com.personal.jobagent.automation.InspectionPlanService;
import com.personal.jobagent.automation.LeverExecutionPlanService;
import com.personal.jobagent.automation.WorkdayExecutionPlanService;
import com.personal.jobagent.automation.ZohoRecruitExecutionPlanService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
import com.personal.jobagent.security.OwnerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
    private final ZohoRecruitExecutionPlanService zohoRecruitPlans;
    private final LeverExecutionPlanService leverPlans;
    private final WorkdayExecutionPlanService workdayPlans;
    private final AshbyExecutionPlanService ashbyPlans;
    private final InspectionPlanService inspectionPlans;
    private final JobRepository jobs;
    private final AuditLogWriter audit;

    public ApplyController(OwnerContext ownerContext, ApplyReadinessService readiness,
                           GreenhouseExecutionPlanService greenhousePlans, ZohoRecruitExecutionPlanService zohoRecruitPlans,
                           LeverExecutionPlanService leverPlans, WorkdayExecutionPlanService workdayPlans,
                           AshbyExecutionPlanService ashbyPlans,
                           InspectionPlanService inspectionPlans, JobRepository jobs, AuditLogWriter audit) {
        this.ownerContext = ownerContext;
        this.readiness = readiness;
        this.greenhousePlans = greenhousePlans;
        this.zohoRecruitPlans = zohoRecruitPlans;
        this.leverPlans = leverPlans;
        this.workdayPlans = workdayPlans;
        this.ashbyPlans = ashbyPlans;
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
            String jobApplicationUrl = jobs.findById(jobId).map(JobRecord::applicationUrl).orElse(null);
            var planId = jobApplicationUrl != null && greenhousePlans.handles(jobApplicationUrl)
                    ? greenhousePlans.createExecutionPlan(profileId, id, jobId)
                    : jobApplicationUrl != null && zohoRecruitPlans.handles(jobApplicationUrl)
                    ? zohoRecruitPlans.createExecutionPlan(profileId, id, jobId)
                    : jobApplicationUrl != null && leverPlans.handles(jobApplicationUrl)
                    ? leverPlans.createExecutionPlan(profileId, id, jobId)
                    : jobApplicationUrl != null && workdayPlans.handles(jobApplicationUrl)
                    ? workdayPlans.createExecutionPlan(profileId, id, jobId)
                    : jobApplicationUrl != null && ashbyPlans.handles(jobApplicationUrl)
                    ? ashbyPlans.createExecutionPlan(profileId, id, jobId)
                    : inspectionPlans.createInspectionPlan(profileId, id, jobId);
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

    public record WorkdayAccountRequest(boolean acceptWorkdayTerms) {}

    /**
     * Phase 11: an explicit, candidate-initiated request to create a NEW
     * Workday employer account for this application's employer, via
     * {@link WorkdayExecutionPlanService#authorizeAccountCreation}.
     * {@code acceptWorkdayTerms} must be {@code true} in the request body —
     * never defaulted — mirroring the same "never accept a declaration on
     * the candidate's behalf" rule the rest of this workflow follows; a
     * missing body or a false flag is reported as 409 {@code
     * TERMS_NOT_ACCEPTED}, not silently ignored.
     */
    @PostMapping("/applications/{id}/workday-account")
    public ResponseEntity<?> createWorkdayAccount(@PathVariable UUID id,
                                                   @RequestBody(required = false) WorkdayAccountRequest r) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        boolean acceptWorkdayTerms = r != null && r.acceptWorkdayTerms();
        UUID jobId;
        try {
            Map<String, Object> state = readiness.evaluate(profileId, id);
            jobId = UUID.fromString(String.valueOf(((Map<?, ?>) state.get("application")).get("jobId")));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (ApplyReadinessService.ReadinessUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "availability", ApplyReadinessService.UNAVAILABLE,
                    "reason", e.getMessage(),
                    "message", "Preparation state could not be verified, so account creation was not attempted."));
        }
        try {
            WorkdayExecutionPlanService.AccountCreationResult result =
                    workdayPlans.authorizeAccountCreation(profileId, id, jobId, acceptWorkdayTerms);
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "WORKDAY_ACCOUNT_CREATION_AUTHORIZED",
                    "APPLICATION", id, null,
                    Map.of("planId", result.planId().toString(), "reusedExisting", result.reusedExisting()),
                    null, UuidV7.generate()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("planId", result.planId().toString());
            body.put("sessionId", result.sessionId() == null ? null : result.sessionId().toString());
            body.put("reusedExisting", result.reusedExisting());
            body.put("status", "PREPARED");
            return ResponseEntity.ok(body);
        } catch (WorkdayExecutionPlanService.AccountCreationRefusedException refused) {
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "WORKDAY_ACCOUNT_CREATION_REFUSED",
                    "APPLICATION", id, null, Map.of("reason", refused.reason()), null, UuidV7.generate()));
            return ResponseEntity.status(409).body(Map.of("error", refused.getMessage(), "code", refused.reason()));
        } catch (RegistrationIdentityService.ManagedMailboxNotReadyException notReady) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Robin-managed email is not ready, so no employer account was registered",
                    "code", "MANAGED_MAILBOX_NOT_READY", "managedMailbox", notReady.reason()));
        } catch (RegistrationIdentityService.SessionMismatchException mismatch) {
            return ResponseEntity.status(409).body(Map.of("error", mismatch.getMessage(), "code", "SESSION_MISMATCH"));
        }
    }

    /**
     * Phase 11A: a read-only poll for the candidate — never consumes a
     * verification message (that stays exclusively the worker-only
     * {@code MailboxVerificationController#resolve}'s job) and never returns
     * an OTP or a link, only a coarse status, which provider matched, and a
     * timestamp. See {@link WorkdayExecutionPlanService#checkAccountVerificationStatus}
     * for exactly what this does and does not establish.
     */
    @GetMapping("/applications/{id}/workday-account/verification-status")
    public ResponseEntity<?> workdayAccountVerificationStatus(@PathVariable UUID id) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        try {
            WorkdayExecutionPlanService.VerificationStatus status =
                    workdayPlans.checkAccountVerificationStatus(profileId, id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", status.status());
            body.put("provider", status.provider());
            body.put("receivedAt", status.receivedAt() == null ? null : status.receivedAt().toString());
            body.put("detail", status.detail());
            return ResponseEntity.ok(body);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
