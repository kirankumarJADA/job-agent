package com.personal.jobagent.automation;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.MaintenanceModeService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Durable-orchestration sweeper: reclaims automation plans whose worker
 * stopped heartbeating mid-run and puts them back in the queue.
 *
 * <p>Without this, a worker crash between claim and complete leaves the plan
 * in RUNNING forever — nothing else in the system transitions it. The worker
 * can also call {@code POST /plans/recover-stale} manually, but recovery must
 * not depend on the process that died. Reclaimed plans return to PREPARED and
 * are re-served by claim-next; the worker's durable step state skips already
 * completed steps on re-execution, and no greenhouse plan contains a
 * submit-capable step, so a reclaim can never double-submit anything.
 *
 * <p>When maintenance mode is active ({@code app.maintenance.enabled=true}),
 * both scheduled and startup sweeps are suppressed.
 */
@Component
public class AutomationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationRecoveryScheduler.class);

    private final AutomationPlanRepository plans;
    private final long stalePlanMinutes;
    private final AutomationMetrics metrics;
    private final MaintenanceModeService maintenanceMode;

    @Autowired
    public AutomationRecoveryScheduler(AutomationPlanRepository plans,
                                       @Value("${app.automation.stale-plan-minutes:15}") long stalePlanMinutes,
                                       AutomationMetrics metrics,
                                       @Autowired(required = false) MaintenanceModeService maintenanceMode) {
        this.plans = plans;
        this.stalePlanMinutes = stalePlanMinutes;
        this.metrics = metrics;
        this.maintenanceMode = maintenanceMode != null ? maintenanceMode : new MaintenanceModeService(false);
    }

    public AutomationRecoveryScheduler(AutomationPlanRepository plans,
                                       long stalePlanMinutes,
                                       AutomationMetrics metrics) {
        this(plans, stalePlanMinutes, metrics, new MaintenanceModeService(false));
    }

    @PostConstruct
    void init() {
        if (maintenanceMode.isMaintenanceMode()) {
            log.warn("Automation recovery scheduler is PAUSED (maintenance mode active): "
                    + "no stale plans will be recovered at startup or on schedule.");
        }
    }

    @Scheduled(fixedDelayString = "${app.automation.stale-sweep-interval-ms:60000}")
    public void recoverStalePlans() {
        if (maintenanceMode.isMaintenanceMode()) {
            return;
        }
        int recovered = plans.recoverStale(Instant.now().minusSeconds(stalePlanMinutes * 60));
        metrics.stalePlansRecovered(recovered);
        if (recovered > 0) {
            log.warn("Recovered {} automation plan(s) with no heartbeat for over {} minute(s) — returned to PREPARED",
                    recovered, stalePlanMinutes);
        }
    }
}
