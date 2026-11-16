package com.personal.jobagent.automation;

import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutomationRecoverySchedulerMaintenanceTest {

    private final AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
    private final AutomationMetrics metrics = mock(AutomationMetrics.class);

    @BeforeEach
    void setUp() {
        reset(plans, metrics);
    }

    @Test
    @DisplayName("When maintenance mode is active, scheduler sweep is completely skipped with zero DB calls")
    void schedulerSkipsSweepWhenMaintenanceActive() {
        MaintenanceModeService maintenanceMode = new MaintenanceModeService(true);
        AutomationRecoveryScheduler scheduler = new AutomationRecoveryScheduler(plans, 15, metrics, maintenanceMode);

        scheduler.recoverStalePlans();

        verify(plans, never()).recoverStale(any(Instant.class));
        verify(metrics, never()).stalePlansRecovered(anyInt());
    }

    @Test
    @DisplayName("When maintenance mode is disabled, scheduler sweep executes normally")
    void schedulerExecutesSweepWhenMaintenanceDisabled() {
        MaintenanceModeService maintenanceMode = new MaintenanceModeService(false);
        AutomationRecoveryScheduler scheduler = new AutomationRecoveryScheduler(plans, 15, metrics, maintenanceMode);

        when(plans.recoverStale(any(Instant.class))).thenReturn(3);

        scheduler.recoverStalePlans();

        verify(plans, times(1)).recoverStale(any(Instant.class));
        verify(metrics, times(1)).stalePlansRecovered(3);
    }

    @Test
    @DisplayName("When maintenance mode is active, manual recovery endpoint returns 503 and zero DB calls")
    void controllerBlocksManualRecoveryWhenMaintenanceActive() {
        MaintenanceModeService maintenanceMode = new MaintenanceModeService(true);
        AutomationController controller = new AutomationController(
                plans,
                mock(AuditLogWriter.class),
                mock(OwnerContext.class),
                "", // empty worker token -> machineOrLocalDev() returns true in local dev mode
                mock(ExecutionPackageService.class),
                mock(NotificationService.class),
                metrics,
                mock(com.personal.jobagent.apply.ApplyReadinessService.class),
                mock(ApplyDocumentSelector.class),
                mock(com.personal.jobagent.application.ApplicationStatusService.class),
                mock(GreenhouseExecutionPlanService.class),
                mock(AshbyExecutionPlanService.class),
                mock(WorkdayExecutionPlanService.class),
                maintenanceMode
        );

        ResponseEntity<?> response = controller.recover(15);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "maintenance mode active");
        assertThat(body).containsEntry("recovered", 0);

        verify(plans, never()).recoverStale(any(Instant.class));
    }

    @Test
    @DisplayName("When maintenance mode is disabled, manual recovery endpoint executes recovery")
    void controllerExecutesManualRecoveryWhenMaintenanceDisabled() {
        MaintenanceModeService maintenanceMode = new MaintenanceModeService(false);
        AutomationController controller = new AutomationController(
                plans,
                mock(AuditLogWriter.class),
                mock(OwnerContext.class),
                "",
                mock(ExecutionPackageService.class),
                mock(NotificationService.class),
                metrics,
                mock(com.personal.jobagent.apply.ApplyReadinessService.class),
                mock(ApplyDocumentSelector.class),
                mock(com.personal.jobagent.application.ApplicationStatusService.class),
                mock(GreenhouseExecutionPlanService.class),
                mock(AshbyExecutionPlanService.class),
                mock(WorkdayExecutionPlanService.class),
                maintenanceMode
        );

        when(plans.recoverStale(any(Instant.class))).thenReturn(2);

        ResponseEntity<?> response = controller.recover(15);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("recovered", 2);

        verify(plans, times(1)).recoverStale(any(Instant.class));
    }
}
