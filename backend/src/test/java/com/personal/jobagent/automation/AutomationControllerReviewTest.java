package com.personal.jobagent.automation;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 3 approval-flow semantics: REVIEWED and APPROVED_FOR_SUBMISSION are
 * different human actions with different outcomes. A review acknowledgement
 * only records an audit trail — the plan stays AWAITING_APPROVAL. Only an
 * explicit, preconditioned approval moves a plan to READY_TO_SUBMIT, and
 * nothing anywhere completes or submits it.
 */
class AutomationControllerReviewTest {
    private final UUID profileId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final String cvSha = "a".repeat(64);
    private final String coverSha = "b".repeat(64);
    private AutomationPlanRepository plans;
    private ExecutionPackageService executionPackages;
    private NotificationService notifications;
    private OwnerContext owner;
    private com.personal.jobagent.apply.ApplyReadinessService applyReadiness;
    private ApplyDocumentSelector documentSelector;
    private com.personal.jobagent.application.ApplicationStatusService applicationStatus;
    private AutomationController controller;
    private final UUID cvVersionId = UUID.randomUUID();
    private final UUID coverVersionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        plans = mock(AutomationPlanRepository.class);
        executionPackages = mock(ExecutionPackageService.class);
        notifications = mock(NotificationService.class);
        owner = mock(OwnerContext.class);
        when(owner.profileIdOrNull()).thenReturn(profileId);
        when(owner.isWorkerRequest()).thenReturn(false);
        when(owner.actorOr(anyString())).thenReturn("candidate@example.test");
        when(plans.owns(profileId, planId)).thenReturn(true);
        applyReadiness = mock(com.personal.jobagent.apply.ApplyReadinessService.class);
        documentSelector = mock(ApplyDocumentSelector.class);
        // Default posture: the application is ready and the exact document
        // versions the package bound are still the selected ones.
        when(applyReadiness.evaluate(eq(profileId), any()))
                .thenReturn(Map.of("packageReady", true, "blockers", List.of()));
        when(documentSelector.select(any(), any(), any())).thenReturn(new ApplyDocumentSelector.DocumentSelection(
                new ApplyDocumentSelector.SelectedCv(cvVersionId, cvSha, 10, Instant.now().toString()),
                new ApplyDocumentSelector.SelectedCoverLetter(coverVersionId, 1, "GENERATED", coverSha, null, false),
                com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement.UNKNOWN,
                List.of(), List.of()));
        when(executionPackages.answersStillCurrent(any(), any(), any(), any())).thenReturn(true);
        applicationStatus = mock(com.personal.jobagent.application.ApplicationStatusService.class);
        controller = new AutomationController(plans, mock(AuditLogWriter.class), owner, "worker-secret",
                executionPackages, notifications, new AutomationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                applyReadiness, documentSelector, applicationStatus);
    }

    /** Phase 8.2: approval re-runs the readiness gate server-side. */
    @Test
    void approvalIsRefusedWhenTheReadinessGateReportsBlockers() {
        when(applyReadiness.evaluate(eq(profileId), any())).thenReturn(Map.of(
                "packageReady", false,
                "blockers", List.of(Map.of("area", "CV", "code", "CV_REVIEW_REQUIRED", "message", "review the CV"))));
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));

        ResponseEntity<?> response = controller.approve(planId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(response.getBody())).contains("APPLY_NOT_READY");
        verify(plans, never()).approveForSubmission(any());
    }

    /** A dependency failure is an explicit unavailable state, never a false refusal or success. */
    @Test
    void approvalReportsUnavailableWhenReadinessCannotBeComputed() {
        when(applyReadiness.evaluate(eq(profileId), any()))
                .thenThrow(new com.personal.jobagent.apply.ApplyReadinessService.ReadinessUnavailableException("DB_DOWN"));
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));

        ResponseEntity<?> response = controller.approve(planId);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verify(plans, never()).approveForSubmission(any());
    }

    /** A replaced document invalidates the package instead of riding on the old approval. */
    @Test
    void approvalIsRefusedWhenTheSelectedDocumentsChangedSincePackaging() {
        when(documentSelector.select(any(), any(), any())).thenReturn(new ApplyDocumentSelector.DocumentSelection(
                new ApplyDocumentSelector.SelectedCv(UUID.randomUUID(), cvSha, 10, Instant.now().toString()),
                new ApplyDocumentSelector.SelectedCoverLetter(coverVersionId, 1, "GENERATED", coverSha, null, false),
                com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement.UNKNOWN,
                List.of(), List.of()));
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));

        ResponseEntity<?> response = controller.approve(planId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(response.getBody())).contains("PACKAGE_DRIFT");
        verify(plans, never()).approveForSubmission(any());
    }

    /** A failed run must not silently disappear: the owner is notified. */
    @Test
    void failedOutcomeNotifiesThePlanOwner() {
        when(plans.transition(planId, "RUNNING", "FAILED")).thenReturn(true);
        when(plans.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));
        when(plans.findById(planId)).thenReturn(Optional.of(row("FAILED", planPayload())));

        assertThat(controller.complete(planId, new AutomationController.OutcomeRequest("FAILED", "step crashed"))
                .getStatusCode().value()).isEqualTo(204);
        verify(notifications).emit(argThat(cmd ->
                NotificationEvents.AUTOMATION_FAILURE.equals(cmd.eventType())
                        && String.valueOf(((Map<?, ?>) cmd.payload()).get("profile_id")).equals(profileId.toString())));
    }

    @Test
    void completedOutcomeStaysSilent() {
        when(plans.transition(planId, "RUNNING", "COMPLETED")).thenReturn(true);
        when(plans.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));
        when(plans.findById(planId)).thenReturn(Optional.of(row("COMPLETED", planPayload())));

        assertThat(controller.complete(planId, new AutomationController.OutcomeRequest("COMPLETED", "done"))
                .getStatusCode().value()).isEqualTo(204);
        verify(notifications, never()).emit(any());
    }

    /**
     * Robin x Tsenta continuation: a HUMAN_REQUIRED outcome carrying a
     * structured result (Zoho Recruit's filled/requiredGaps/fields) persists
     * it via the result-aware transition, not the plain one — the review
     * queue has nowhere else to read what the worker actually found.
     */
    @Test
    void humanRequiredWithAStructuredResultPersistsItViaTheResultAwareTransition() {
        List<Map<String, Object>> result = List.of(Map.of(
                "type", "ZOHO_RECRUIT_OUTCOME",
                "filled", List.of(Map.of("label", "First Name *", "category", "IDENTITY_FIRST_NAME")),
                "requiredGaps", List.of(Map.of("label", "Mobile *", "category", "COMPOSITE_WIDGET_PART"))));
        when(plans.transitionWithResult(eq(planId), eq("RUNNING"), eq("AWAITING_APPROVAL"), anyList())).thenReturn(true);
        when(plans.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));
        when(plans.findById(planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));

        var response = controller.complete(planId,
                new AutomationController.OutcomeRequest("HUMAN_REQUIRED", "1 field filled, 1 needs review", result));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(plans).transitionWithResult(eq(planId), eq("RUNNING"), eq("AWAITING_APPROVAL"), anyList());
        verify(plans, never()).transition(eq(planId), eq("RUNNING"), eq("AWAITING_APPROVAL"));
    }

    /** No result payload at all still uses the plain transition — no behavior change for Greenhouse. */
    @Test
    void humanRequiredWithoutAResultPayloadUsesThePlainTransition() {
        when(plans.transition(planId, "RUNNING", "AWAITING_APPROVAL")).thenReturn(true);
        when(plans.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));
        when(plans.findById(planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));

        var response = controller.complete(planId,
                new AutomationController.OutcomeRequest("HUMAN_REQUIRED", "needs review"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(plans).transition(planId, "RUNNING", "AWAITING_APPROVAL");
        verify(plans, never()).transitionWithResult(any(), anyString(), anyString(), anyList());
    }

    /** Case 1: review alone never completes and never readies the plan. */
    @Test
    void reviewAcknowledgementRecordsAuditButNeverCompletesOrReadiesThePlan() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL")));

        ResponseEntity<?> response = controller.review(planId, Map.of("acknowledge", true));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(plans, never()).transition(any(), anyString(), anyString());
        verify(plans, never()).approveForSubmission(any());
        verify(plans, never()).approveSubmit(any());
    }

    @Test
    void reviewRequiresAwaitingApprovalState() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("RUNNING")));

        assertThat(controller.review(planId, Map.of("acknowledge", true)).getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).transition(any(), anyString(), anyString());
    }

    /**
     * Approval stays human-only. A worker-identity SUBMITTED report with no
     * properly-approved plan behind it is rejected too — here specifically
     * because {@code completeRealSubmission} is never stubbed, so the
     * Mockito-default {@code false} makes the transition fail, exactly as it
     * would for a real, un-approved plan. The worker's own ability to report
     * SUBMITTED for a plan that genuinely did go through prepare-submit is
     * covered separately below (Phase 4), not by this test.
     */
    @Test
    void workerCannotApproveHumanReviewOrSubmitWithoutAProperlyApprovedPlan() {
        when(owner.isWorkerRequest()).thenReturn(true);

        assertThat(controller.approve(planId).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.complete(planId, new AutomationController.OutcomeRequest("SUBMITTED", "test"))
                .getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).approveForSubmission(any());
        verify(plans, never()).approveSubmit(any());
        verify(plans, never()).transition(any(), anyString(), eq("SUBMITTED"));
    }

    // ── Phase 4: queuing and completing a real submission ──────────────

    @Test
    void prepareSubmitQueuesTheRealClickWhenApprovedAndReadyToSubmit() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("READY_TO_SUBMIT", planPayload(), true)));
        when(plans.prepareRealSubmission(planId)).thenReturn(true);

        ResponseEntity<?> response = controller.prepareSubmit(planId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "PREPARED", "submissionQueued", true));
        verify(plans).prepareRealSubmission(planId);
    }

    @Test
    void prepareSubmitRejectsAPlanThatIsNotYetApproved() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload(), false)));

        assertThat(controller.prepareSubmit(planId).getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).prepareRealSubmission(any());
    }

    @Test
    void prepareSubmitRejectsAPlanApprovedButNotReadyToSubmit() {
        // submit_approved can only ever be true alongside READY_TO_SUBMIT in
        // practice, but prepareSubmit must not trust status alone either.
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("RUNNING", planPayload(), true)));

        assertThat(controller.prepareSubmit(planId).getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).prepareRealSubmission(any());
    }

    @Test
    void prepareSubmitRejectsWhenCalledByTheWorker() {
        when(owner.isWorkerRequest()).thenReturn(true);

        assertThat(controller.prepareSubmit(planId).getStatusCode().value()).isEqualTo(403);
        verify(plans, never()).prepareRealSubmission(any());
    }

    @Test
    void prepareSubmitRejectsOnDocumentDriftEvenAfterApproval() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("READY_TO_SUBMIT", planPayload(), true)));
        when(documentSelector.select(any(), any(), any())).thenReturn(new ApplyDocumentSelector.DocumentSelection(
                new ApplyDocumentSelector.SelectedCv(UUID.randomUUID(), cvSha, 10, Instant.now().toString()),
                new ApplyDocumentSelector.SelectedCoverLetter(coverVersionId, 1, "GENERATED", coverSha, null, false),
                com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement.UNKNOWN,
                List.of(), List.of()));

        ResponseEntity<?> response = controller.prepareSubmit(planId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(response.getBody())).contains("PACKAGE_DRIFT");
        verify(plans, never()).prepareRealSubmission(any());
    }

    @Test
    void completeRejectsASubmittedOutcomeFromANonWorkerCaller() {
        when(owner.isWorkerRequest()).thenReturn(false);

        ResponseEntity<?> response = controller.complete(planId, new AutomationController.OutcomeRequest("SUBMITTED", "confirmed on page"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(plans, never()).completeRealSubmission(any());
    }

    @Test
    void completeRejectsASubmittedOutcomeWithoutConfirmationEvidence() {
        when(owner.isWorkerRequest()).thenReturn(true);

        ResponseEntity<?> blank = controller.complete(planId, new AutomationController.OutcomeRequest("SUBMITTED", ""));
        ResponseEntity<?> missing = controller.complete(planId, new AutomationController.OutcomeRequest("SUBMITTED", null));

        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        verify(plans, never()).completeRealSubmission(any());
    }

    /** The one successful path: worker, real evidence, repository guard passes. */
    @Test
    void completeRecordsAConfirmedRealSubmissionAndMirrorsItOntoTheApplication() {
        when(owner.isWorkerRequest()).thenReturn(true);
        when(plans.completeRealSubmission(planId)).thenReturn(true);
        when(plans.findById(planId)).thenReturn(Optional.of(row("SUBMITTED", planPayload(), true)));
        when(plans.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));

        ResponseEntity<?> response = controller.complete(planId,
                new AutomationController.OutcomeRequest("SUBMITTED", "page text matched the expected confirmation"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(plans).completeRealSubmission(planId);
        verify(applicationStatus).apply(eq(profileId), eq(applicationId), eq("APPLICATION_SUBMITTED"),
                anyString(), eq("worker"), any());
    }

    /** The repository's own atomic guard failing must not be papered over. */
    @Test
    void completeRejectsASubmittedOutcomeWhenTheRepositoryGuardFails() {
        when(owner.isWorkerRequest()).thenReturn(true);
        when(plans.completeRealSubmission(planId)).thenReturn(false);

        ResponseEntity<?> response = controller.complete(planId,
                new AutomationController.OutcomeRequest("SUBMITTED", "page text matched the expected confirmation"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(applicationStatus, never()).apply(any(), any(), any(), any(), any(), any());
    }

    /**
     * A real-submission attempt that comes back uncertain (HUMAN_REQUIRED)
     * clears submit_approved via the shared transition() path — re-approval
     * must be a fresh, deliberate human action, never automatic.
     */
    @Test
    void uncertainSubmitOutcomeFallsBackToHumanReviewThroughTheSharedTransition() {
        when(plans.transition(planId, "RUNNING", "AWAITING_APPROVAL")).thenReturn(true);
        when(plans.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));
        when(plans.findById(planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));

        ResponseEntity<?> response = controller.complete(planId,
                new AutomationController.OutcomeRequest("HUMAN_REQUIRED", "no confirmation signal was found"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(plans).transition(planId, "RUNNING", "AWAITING_APPROVAL");
        verify(applicationStatus, never()).apply(any(), any(), any(), any(), any(), any());
    }

    /** Case 5: validation passed + explicit owner approval → READY_TO_SUBMIT. */
    @Test
    void explicitApprovalMovesValidatedAwaitingPlanToReadyToSubmit() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));
        when(executionPackages.artifactBytes(eq(planId), eq(profileId), eq(applicationId), eq(jobId), eq("cv"), any()))
                .thenReturn(new ExecutionPackageService.ArtifactBytes(new byte[]{1}, cvSha, "application/pdf", "cv.pdf"));
        when(executionPackages.artifactBytes(eq(planId), eq(profileId), eq(applicationId), eq(jobId), eq("cover-letter"), any()))
                .thenReturn(new ExecutionPackageService.ArtifactBytes(new byte[]{2}, coverSha, "text/plain", "cover.pdf"));
        when(plans.approveForSubmission(planId)).thenReturn(true);

        ResponseEntity<?> response = controller.approve(planId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "READY_TO_SUBMIT", "submissionEnabled", false));
        verify(plans).approveForSubmission(planId);
        verify(plans, never()).transition(any(), anyString(), eq("COMPLETED"));
        verify(plans, never()).transition(any(), anyString(), eq("SUBMITTED"));
    }

    /** Case 3: unresolved human-required fields → approval rejected. */
    @Test
    void approvalRejectsWhenHumanRequiredItemsRemain() {
        Map<String, Object> payload = planPayload();
        @SuppressWarnings("unchecked")
        Map<String, Object> pkg = (Map<String, Object>) payload.get("package");
        pkg.put("requiredGaps", List.of(Map.of("key", "salary_expectations")));
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", payload)));

        ResponseEntity<?> response = controller.approve(planId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).approveForSubmission(any());
    }

    /** Case 2 + 4: a failed or hard-stopped plan is never approvable. */
    @Test
    void approvalRejectsPlansThatDidNotValidateCleanly() {
        for (String status : List.of("FAILED", "BLOCKED_ANTI_BOT", "RUNNING", "READY_TO_SUBMIT")) {
            when(plans.find(profileId, planId)).thenReturn(Optional.of(row(status, planPayload())));

            assertThat(controller.approve(planId).getStatusCode().value())
                    .as("status %s must not be approvable", status).isEqualTo(409);
        }
        verify(plans, never()).approveForSubmission(any());
    }

    @Test
    void approvalRejectsAPackageWhoseArtifactChecksumsNoLongerMatch() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));
        when(executionPackages.artifactBytes(eq(planId), eq(profileId), eq(applicationId), eq(jobId), eq("cv"), any()))
                .thenReturn(new ExecutionPackageService.ArtifactBytes(new byte[]{1}, "different".repeat(8), "application/pdf", "cv.pdf"));

        ResponseEntity<?> response = controller.approve(planId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).approveForSubmission(any());
    }

    @Test
    void approvalRejectsAPlanThatContainsASubmissionCapableStep() {
        Map<String, Object> payload = planPayload();
        payload.put("steps", List.of(
                Map.of("id", "validate-form", "type", "VALIDATE", "policy", "AUTO", "params", Map.of()),
                Map.of("id", "submit", "type", "CLICK", "policy", "AUTO", "params", Map.of("selector", "button[type=submit]"))));
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", payload)));

        assertThat(controller.approve(planId).getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).approveForSubmission(any());
    }

    @Test
    void approvalRejectsAPlanWhosePackageCorrelationDoesNotMatch() {
        Map<String, Object> payload = planPayload();
        payload.put("correlation", Map.of("jobId", UUID.randomUUID().toString(),
                "applicationId", UUID.randomUUID().toString()));
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", payload)));

        assertThat(controller.approve(planId).getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).approveForSubmission(any());
    }

    /** A concurrent state change between preconditions and the atomic update. */
    @Test
    void approvalReturnsConflictWhenThePlanStateChangedUnderneath() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL", planPayload())));
        when(executionPackages.artifactBytes(eq(planId), eq(profileId), eq(applicationId), eq(jobId), eq("cv"), any()))
                .thenReturn(new ExecutionPackageService.ArtifactBytes(new byte[]{1}, cvSha, "application/pdf", "cv.pdf"));
        when(executionPackages.artifactBytes(eq(planId), eq(profileId), eq(applicationId), eq(jobId), eq("cover-letter"), any()))
                .thenReturn(new ExecutionPackageService.ArtifactBytes(new byte[]{2}, coverSha, "text/plain", "cover.pdf"));
        when(plans.approveForSubmission(planId)).thenReturn(false);

        assertThat(controller.approve(planId).getStatusCode().value()).isEqualTo(409);
    }

    private Map<String, Object> planPayload() {
        Map<String, Object> cv = Map.of("versionId", cvVersionId.toString(), "sha256", cvSha,
                "jobId", jobId.toString(), "applicationId", applicationId.toString());
        Map<String, Object> coverLetter = Map.of("versionId", coverVersionId.toString(), "sha256", coverSha,
                "jobId", jobId.toString(), "applicationId", applicationId.toString());
        Map<String, Object> pkg = new java.util.LinkedHashMap<>(Map.of(
                "planId", planId.toString(), "applicationId", applicationId.toString(),
                "jobId", jobId.toString(), "expectedUrl", "https://boards.greenhouse.io/acme/jobs/42",
                "requiredGaps", List.of(), "cv", cv, "coverLetter", coverLetter));
        return new java.util.LinkedHashMap<>(Map.of(
                "planType", "GREENHOUSE",
                "correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()),
                "package", pkg,
                "steps", List.of(Map.of("id", "fill-email", "type", "FILL_FIELD", "policy", "AUTO",
                        "params", Map.of("selector", "#email", "value", "candidate@example.test")))));
    }

    private AutomationPlanRepository.PlanRow row(String status) {
        return row(status, Map.of());
    }

    private AutomationPlanRepository.PlanRow row(String status, Map<String, Object> plan) {
        return row(status, plan, false);
    }

    private AutomationPlanRepository.PlanRow row(String status, Map<String, Object> plan, boolean submitApproved) {
        return new AutomationPlanRepository.PlanRow(planId, applicationId,
                "https://boards.greenhouse.io/acme/jobs/42", status, plan, List.of(), submitApproved, null, Instant.now());
    }
}
