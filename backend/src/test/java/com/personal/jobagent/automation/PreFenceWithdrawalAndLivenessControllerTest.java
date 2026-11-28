package com.personal.jobagent.automation;

import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.apply.ApplyReadinessService;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.MaintenanceModeService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreFenceWithdrawalAndLivenessControllerTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();

    private AutomationPlanRepository plans;
    private AuditLogWriter audit;
    private OwnerContext owner;
    private ExecutionPackageService executionPackages;
    private NotificationService notifications;
    private AutomationMetrics metrics;
    private ApplyReadinessService applyReadiness;
    private ApplyDocumentSelector documentSelector;
    private ApplicationStatusService applicationStatus;
    private GreenhouseExecutionPlanService greenhousePlans;
    private AshbyExecutionPlanService ashbyPlans;
    private WorkdayExecutionPlanService workdayPlans;
    private PostingLivenessService livenessService;
    private StandingAuthorizationService standingAuth;
    private AutomationController controller;

    @BeforeEach
    void setUp() {
        plans = mock(AutomationPlanRepository.class);
        audit = mock(AuditLogWriter.class);
        owner = mock(OwnerContext.class);
        executionPackages = mock(ExecutionPackageService.class);
        notifications = mock(NotificationService.class);
        metrics = mock(AutomationMetrics.class);
        applyReadiness = mock(ApplyReadinessService.class);
        documentSelector = mock(ApplyDocumentSelector.class);
        applicationStatus = mock(ApplicationStatusService.class);
        greenhousePlans = mock(GreenhouseExecutionPlanService.class);
        ashbyPlans = mock(AshbyExecutionPlanService.class);
        workdayPlans = mock(WorkdayExecutionPlanService.class);
        livenessService = mock(PostingLivenessService.class);
        standingAuth = mock(StandingAuthorizationService.class);

        when(owner.isWorkerRequest()).thenReturn(true);
        when(owner.actorOr("worker")).thenReturn("worker");

        controller = new AutomationController(
                plans, audit, owner, "worker-token",
                executionPackages, notifications, metrics, applyReadiness, documentSelector,
                applicationStatus, greenhousePlans, ashbyPlans, workdayPlans,
                new MaintenanceModeService(false), null, null, null,
                null, livenessService, standingAuth);
    }

    private AutomationPlanRepository.PlanRow runningUniversalSubmitPlan() {
        return new AutomationPlanRepository.PlanRow(
                PLAN_ID, APP_ID, "https://careers.acme.example/apply",
                "RUNNING",
                Map.of("planType", "UNIVERSAL", "steps", List.of(
                        Map.of("id", "s1", "type", "UNIVERSAL_APPLY"),
                        Map.of("id", "s2", "type", "UNIVERSAL_SUBMIT_CLICK"))),
                List.of(), true, Instant.now(), Instant.now());
    }

    @Test
    void preFenceAuthorizationRefusalReturnsPlanToAwaitingApprovalWithoutReconciliationOrStandingRetry() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(runningUniversalSubmitPlan()));
        when(plans.hasPreSubmitAttemptCheckpoint(PLAN_ID)).thenReturn(false);
        when(plans.returnUnfencedSubmitToAwaitingApproval(eq(PLAN_ID), any())).thenReturn(true);

        List<Map<String, Object>> result = List.of(Map.of(
                "type", "AUTHORIZATION_REFUSED",
                "planType", "UNIVERSAL",
                "code", "SUBMIT_APPROVAL_WITHDRAWN",
                "category", "SUBMIT_APPROVAL_WITHDRAWN",
                "authorizationDenied", true,
                "clicked", false,
                "evidence", "Approval withdrawn pre-fence"));

        ResponseEntity<?> res = controller.complete(PLAN_ID, new AutomationController.OutcomeRequest(
                "HUMAN_REQUIRED", "Pre-fence refusal", result));

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        verify(plans).returnUnfencedSubmitToAwaitingApproval(eq(PLAN_ID), any());
        verify(standingAuth, never()).tryAutoAuthorizeAfterPreparation(any());
    }

    @Test
    void humanRequiredWithExistingCheckpointOrMissingAuthorizationRefusalStillReturns400() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(runningUniversalSubmitPlan()));
        when(plans.hasPreSubmitAttemptCheckpoint(PLAN_ID)).thenReturn(true);

        List<Map<String, Object>> result = List.of(Map.of(
                "type", "AUTHORIZATION_REFUSED",
                "authorizationDenied", true,
                "clicked", false,
                "code", "SUBMIT_APPROVAL_WITHDRAWN"));
        ResponseEntity<?> postFence = controller.complete(PLAN_ID, new AutomationController.OutcomeRequest(
                "HUMAN_REQUIRED", "Should fail", result));
        assertThat(postFence.getStatusCode().value()).isEqualTo(400);
        verify(plans, never()).returnUnfencedSubmitToAwaitingApproval(any(), any());
    }

    @Test
    void claimBlocksClosedPostingsAndMarksFailed() {
        when(livenessService.evaluateForPlan(PLAN_ID)).thenReturn(new PostingLivenessService.LivenessResult(
                PostingLivenessService.Status.CLOSED, "JOB_POSTING_CLOSED", "HTTP 404", 404));

        ResponseEntity<?> res = controller.claim(PLAN_ID);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        verify(plans).failClosedBeforeClaim(eq(PLAN_ID), eq("PREPARED"), any());
        verify(plans, never()).claim(any());
    }
}
