package com.personal.jobagent.automation;

import com.personal.jobagent.audit.AuditLogWriter;
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

class AutomationControllerReviewTest {
    private final UUID profileId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private AutomationPlanRepository plans;
    private OwnerContext owner;
    private AutomationController controller;

    @BeforeEach
    void setUp() {
        plans = mock(AutomationPlanRepository.class);
        owner = mock(OwnerContext.class);
        when(owner.profileIdOrNull()).thenReturn(profileId);
        when(owner.isWorkerRequest()).thenReturn(false);
        when(owner.actorOr(anyString())).thenReturn("candidate@example.test");
        when(plans.owns(profileId, planId)).thenReturn(true);
        controller = new AutomationController(plans, mock(AuditLogWriter.class), owner, "worker-secret",
                mock(ExecutionPackageService.class));
    }

    @Test
    void ownerCanAcknowledgeReviewButNeverCauseSubmission() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("AWAITING_APPROVAL")));
        when(plans.transition(planId, "AWAITING_APPROVAL", "COMPLETED")).thenReturn(true);

        ResponseEntity<?> response = controller.review(planId, Map.of("acknowledge", true));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(plans).transition(planId, "AWAITING_APPROVAL", "COMPLETED");
        verify(plans, never()).approveSubmit(planId);
        verify(plans, never()).transition(planId, "RUNNING", "SUBMITTED");
    }

    @Test
    void workerCannotApproveHumanReviewOrSubmit() {
        when(owner.isWorkerRequest()).thenReturn(true);

        assertThat(controller.approve(planId).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.complete(planId, new AutomationController.OutcomeRequest("SUBMITTED", "test"))
                .getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).approveSubmit(any());
        verify(plans, never()).transition(any(), anyString(), eq("SUBMITTED"));
    }

    @Test
    void reviewRequiresAwaitingApprovalState() {
        when(plans.find(profileId, planId)).thenReturn(Optional.of(row("RUNNING")));

        assertThat(controller.review(planId, Map.of("acknowledge", true)).getStatusCode().value()).isEqualTo(409);
        verify(plans, never()).transition(any(), anyString(), anyString());
    }

    @Test
    void approvalEndpointIsPermanentlyDisabled() {
        assertThat(controller.approve(planId).getStatusCode().value()).isEqualTo(410);
        verify(plans, never()).approveSubmit(any());
    }

    private AutomationPlanRepository.PlanRow row(String status) {
        return new AutomationPlanRepository.PlanRow(planId, UUID.randomUUID(),
                "https://boards.greenhouse.io/acme/jobs/42", status, Map.of(), List.of(), false, null, Instant.now());
    }
}
