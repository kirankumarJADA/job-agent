package com.personal.jobagent.automation;

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
    private AutomationController controller;

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
        controller = new AutomationController(plans, mock(AuditLogWriter.class), owner, "worker-secret",
                executionPackages, notifications);
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

    @Test
    void workerCannotApproveHumanReviewOrSubmit() {
        when(owner.isWorkerRequest()).thenReturn(true);

        assertThat(controller.approve(planId).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.complete(planId, new AutomationController.OutcomeRequest("SUBMITTED", "test"))
                .getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).approveForSubmission(any());
        verify(plans, never()).approveSubmit(any());
        verify(plans, never()).transition(any(), anyString(), eq("SUBMITTED"));
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
        Map<String, Object> cv = Map.of("versionId", UUID.randomUUID().toString(), "sha256", cvSha,
                "jobId", jobId.toString(), "applicationId", applicationId.toString());
        Map<String, Object> coverLetter = Map.of("versionId", UUID.randomUUID().toString(), "sha256", coverSha,
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
        return new AutomationPlanRepository.PlanRow(planId, applicationId,
                "https://boards.greenhouse.io/acme/jobs/42", status, plan, List.of(), false, null, Instant.now());
    }
}
