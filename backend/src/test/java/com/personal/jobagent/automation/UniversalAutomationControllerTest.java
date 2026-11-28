package com.personal.jobagent.automation;

import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.apply.ApplyReadinessService;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.qa.ApplicationAnswerRepository;
import com.personal.jobagent.security.OwnerContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniversalAutomationControllerTest {

    @Test
    void duplicatePreSubmitAttemptCheckpointReturns409AndPrepareSubmitReChecksReadiness() {
        AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
        OwnerContext owner = mock(OwnerContext.class);
        ApplyReadinessService readiness = mock(ApplyReadinessService.class);
        UniversalBrowserPlanService universalPlans = mock(UniversalBrowserPlanService.class);
        ApplicationAnswerRepository answers = mock(ApplicationAnswerRepository.class);

        PreSubmitAuthorizationService preSubmitAuth = mock(PreSubmitAuthorizationService.class);

        AutomationController controller = new AutomationController(
                plans, mock(AuditLogWriter.class), owner, "worker-token",
                mock(ExecutionPackageService.class), mock(NotificationService.class),
                new AutomationMetrics(new SimpleMeterRegistry()), readiness,
                mock(ApplyDocumentSelector.class), mock(ApplicationStatusService.class),
                mock(GreenhouseExecutionPlanService.class), mock(AshbyExecutionPlanService.class),
                mock(WorkdayExecutionPlanService.class), new MaintenanceModeService(false),
                mock(SubmissionCompletionService.class), universalPlans, answers, preSubmitAuth);

        UUID planId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        Map<String, Object> planMap = Map.of(
                "planType", "UNIVERSAL",
                "correlation", Map.of("applicationId", appId.toString(), "jobId", UUID.randomUUID().toString()),
                "steps", List.of(Map.of("id", "universal-apply", "type", "UNIVERSAL_APPLY")));

        // 1. Duplicate PRE_SUBMIT_ATTEMPTING rejected with 409 via shared PreSubmitAuthorizationService
        when(owner.isWorkerRequest()).thenReturn(true);
        when(plans.findById(planId)).thenReturn(Optional.of(
                new AutomationPlanRepository.PlanRow(planId, appId, "https://careers.acme.example/apply", "RUNNING", planMap, null, true, Instant.now(), Instant.now())));
        when(preSubmitAuth.authorizeAndFence(eq(planId), eq(99), eq("RUNNING"), anyMap(), isNull()))
                .thenReturn(PreSubmitAuthorizationService.AuthorizationOutcome.conflict(
                        "DUPLICATE_PRE_SUBMIT_ATTEMPT", "a pre-submit attempt checkpoint already exists"));

        ResponseEntity<?> stepRes = controller.step(planId,
                new AutomationController.StepRequest(99, "pre-submit-attempting", "RUNNING",
                        Map.of("phase", "PRE_SUBMIT_ATTEMPTING"), null));
        assertThat(stepRes.getStatusCode().value()).isEqualTo(409);

        // 1b. Missing PreSubmitAuthorizationService fails closed with 503 (never self-authorizes)
        AutomationController unwired = new AutomationController(
                plans, mock(AuditLogWriter.class), owner, "worker-token",
                mock(ExecutionPackageService.class), mock(NotificationService.class),
                new AutomationMetrics(new SimpleMeterRegistry()), readiness,
                mock(ApplyDocumentSelector.class), mock(ApplicationStatusService.class),
                mock(GreenhouseExecutionPlanService.class), mock(AshbyExecutionPlanService.class),
                mock(WorkdayExecutionPlanService.class), new MaintenanceModeService(false),
                mock(SubmissionCompletionService.class), universalPlans, answers);
        ResponseEntity<?> unwiredRes = unwired.step(planId,
                new AutomationController.StepRequest(99, "pre-submit-attempting", "RUNNING",
                        Map.of("phase", "PRE_SUBMIT_ATTEMPTING"), null));
        assertThat(unwiredRes.getStatusCode().value()).isEqualTo(503);

        // 2. prepareSubmit refuses when readiness fails after approval
        when(owner.isWorkerRequest()).thenReturn(false);
        when(owner.profileIdOrNull()).thenReturn(profileId);
        when(plans.owns(profileId, planId)).thenReturn(true);
        when(plans.find(profileId, planId)).thenReturn(Optional.of(
                new AutomationPlanRepository.PlanRow(planId, appId, "https://careers.acme.example/apply", "READY_TO_SUBMIT", planMap, null, true, Instant.now(), Instant.now())));
        when(readiness.evaluate(profileId, appId)).thenReturn(Map.of(
                "packageReady", false,
                "blockers", List.of(Map.of("code", "ANSWER_NEEDS_USER_INPUT"))));

        ResponseEntity<?> prepRes = controller.prepareSubmit(planId);
        assertThat(prepRes.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(prepRes.getBody())).contains("APPLY_NOT_READY");
        verify(plans, never()).prepareUniversalSubmission(any());
    }
}
