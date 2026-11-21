package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.MaintenanceModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Phase 2A Hardened: Autonomous Search Plan Scheduler.
 * <p>
 * Periodically polls the database for due active search plans based on their declared
 * frequency (HOURLY, DAILY, WEEKLY). Automatically recovers stale/crashed executions
 * whose leases expired, and invokes {@link SearchPlanExecutionCoordinator} under atomic
 * execution lease protection to eliminate multi-instance scheduler races.
 */
@Component
public class SearchPlanScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchPlanScheduler.class);

    private final SearchPlanRepository repository;
    private final SearchPlanExecutionCoordinator coordinator;
    private final MaintenanceModeService maintenanceMode;
    private final boolean schedulerEnabled;

    @Autowired
    public SearchPlanScheduler(SearchPlanRepository repository,
                               SearchPlanExecutionCoordinator coordinator,
                               MaintenanceModeService maintenanceMode,
                               @Value("${app.discovery.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.maintenanceMode = maintenanceMode != null ? maintenanceMode : new MaintenanceModeService(false);
        this.schedulerEnabled = schedulerEnabled;
    }

    @Scheduled(fixedDelayString = "${app.discovery.search-plan.sweep-interval-ms:60000}")
    public void sweepDueSearchPlans() {
        if (!schedulerEnabled) {
            return;
        }
        if (maintenanceMode.isMaintenanceMode()) {
            log.info("Search plan scheduler sweep is paused: maintenance mode active");
            return;
        }

        Instant now = Instant.now();

        // Step 1: Recover abandoned or crashed executions whose lease expired
        try {
            int recovered = repository.recoverStaleExecutions(now);
            if (recovered > 0) {
                log.warn("Recovered {} stale search plan execution(s) whose leases had expired", recovered);
            }
        } catch (Exception e) {
            log.error("Failed to run stale execution recovery: {}", e.getMessage(), e);
        }

        // Step 2: Sweep due active search plans (excluding MANUAL and currently running plans)
        List<SearchPlanRecord> duePlans = repository.findDuePlans(now);
        if (duePlans.isEmpty()) {
            return;
        }

        log.info("Found {} due active search plan(s) for automated execution", duePlans.size());

        for (SearchPlanRecord plan : duePlans) {
            try {
                log.info("Executing due search plan {} (name: '{}', freq: {}) for profile {}",
                        plan.id(), plan.name(), plan.searchFrequency(), plan.profileId());
                SearchPlanExecutionCoordinator.ExecutionSummary summary =
                        coordinator.executePlan(plan.id(), plan.profileId(), false);
                log.info("Completed execution for search plan {}: status={}, ingested={}, matched={}",
                        plan.id(), summary.status(), summary.jobsIngested(), summary.jobsMatched());
            } catch (Exception e) {
                log.error("Unhandled error running due search plan {}: {}", plan.id(), e.getMessage(), e);
            }
        }
    }
}
