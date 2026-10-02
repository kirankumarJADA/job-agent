package com.personal.jobagent.automation;

import com.personal.jobagent.common.AutomationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
public class AutomationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationRecoveryScheduler.class);

    private final AutomationPlanRepository plans;
    private final long stalePlanMinutes;
    private final AutomationMetrics metrics;

    public AutomationRecoveryScheduler(AutomationPlanRepository plans,
                                       @Value("${app.automation.stale-plan-minutes:15}") long stalePlanMinutes,
                                       AutomationMetrics metrics) {
        this.plans = plans;
        this.stalePlanMinutes = stalePlanMinutes;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.automation.stale-sweep-interval-ms:60000}")
    public void recoverStalePlans() {
        int recovered = plans.recoverStale(Instant.now().minusSeconds(stalePlanMinutes * 60));
        metrics.stalePlansRecovered(recovered);
        if (recovered > 0) {
            log.warn("Recovered {} automation plan(s) with no heartbeat for over {} minute(s) — returned to PREPARED",
                    recovered, stalePlanMinutes);
        }
    }
}
