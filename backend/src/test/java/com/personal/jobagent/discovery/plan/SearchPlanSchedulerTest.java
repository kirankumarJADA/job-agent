package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.MaintenanceModeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Phase 2 Deliverable A: SearchPlanScheduler Unit Tests")
class SearchPlanSchedulerTest {

    private SearchPlanRepository repository;
    private SearchPlanExecutionCoordinator coordinator;
    private MaintenanceModeService maintenanceMode;

    @BeforeEach
    void setUp() {
        repository = mock(SearchPlanRepository.class);
        coordinator = mock(SearchPlanExecutionCoordinator.class);
        maintenanceMode = new MaintenanceModeService(false);
    }

    @Test
    @DisplayName("Sweeps and executes due search plans")
    void sweepDueSearchPlans_executesDue() {
        SearchPlanScheduler scheduler = new SearchPlanScheduler(repository, coordinator, maintenanceMode, true);

        UUID plan1 = UUID.randomUUID();
        UUID prof1 = UUID.randomUUID();
        UUID plan2 = UUID.randomUUID();
        UUID prof2 = UUID.randomUUID();

        SearchPlanRecord r1 = new SearchPlanRecord(plan1, prof1, "Plan 1", List.of(), Map.of(), List.of(), "HOURLY", 50, true, null, Instant.now(), Instant.now());
        SearchPlanRecord r2 = new SearchPlanRecord(plan2, prof2, "Plan 2", List.of(), Map.of(), List.of(), "DAILY", 50, true, null, Instant.now(), Instant.now());

        when(repository.findDuePlans(any(Instant.class))).thenReturn(List.of(r1, r2));

        when(coordinator.executePlan(eq(plan1), eq(prof1), eq(false))).thenReturn(
                new SearchPlanExecutionCoordinator.ExecutionSummary(plan1, "Plan 1", "SUCCESS", 1, 1, 0, 5, 5, List.of(), List.of(), null, Map.of())
        );
        when(coordinator.executePlan(eq(plan2), eq(prof2), eq(false))).thenReturn(
                new SearchPlanExecutionCoordinator.ExecutionSummary(plan2, "Plan 2", "SUCCESS", 1, 1, 0, 3, 3, List.of(), List.of(), null, Map.of())
        );

        scheduler.sweepDueSearchPlans();

        verify(coordinator).executePlan(eq(plan1), eq(prof1), eq(false));
        verify(coordinator).executePlan(eq(plan2), eq(prof2), eq(false));
    }

    @Test
    @DisplayName("Skips sweep when scheduler is disabled via config")
    void sweepDueSearchPlans_skipsWhenDisabled() {
        SearchPlanScheduler scheduler = new SearchPlanScheduler(repository, coordinator, maintenanceMode, false);
        scheduler.sweepDueSearchPlans();
        verifyNoInteractions(repository);
        verifyNoInteractions(coordinator);
    }

    @Test
    @DisplayName("Skips sweep when maintenance mode is active")
    void sweepDueSearchPlans_skipsDuringMaintenance() {
        MaintenanceModeService mm = mock(MaintenanceModeService.class);
        when(mm.isMaintenanceMode()).thenReturn(true);

        SearchPlanScheduler scheduler = new SearchPlanScheduler(repository, coordinator, mm, true);
        scheduler.sweepDueSearchPlans();

        verifyNoInteractions(repository);
        verifyNoInteractions(coordinator);
    }
}
