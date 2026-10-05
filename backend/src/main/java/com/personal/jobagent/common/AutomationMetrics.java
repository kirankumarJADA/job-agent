package com.personal.jobagent.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One seam for the automation pipeline's operational counters. Every state
 * transition that matters to an operator (matches, preparation steps, plan
 * outcomes, approvals, stale recoveries, scheduled discovery runs) increments
 * a tagged counter here instead of each service hand-rolling its own meter,
 * so the Prometheus/Grafana stack (infra/docker-compose.obs.yml) gets one
 * coherent catalogue and no secrets or personal data ever pass through —
 * only enumerable outcome labels.
 */
@Component
public class AutomationMetrics {

    private final MeterRegistry registry;
    private final AtomicReference<MeterRegistry> effective = new AtomicReference<>();

    public AutomationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.effective.set(registry);
    }

    private Counter counter(String name, String description, String... tags) {
        return Counter.builder(name).description(description).tags(tags).register(effective.get());
    }

    /** One per match decision, tagged by APPLY / REVIEW / SKIP. */
    public void matchRecorded(String recommendation) {
        counter("robin_matches_total", "Deterministic match decisions", "recommendation", recommendation).increment();
    }

    /** One per preparation step, tagged by step name and OK / FAILED. */
    public void preparationStep(String step, String status) {
        counter("robin_preparation_steps_total", "Application preparation step outcomes",
                "step", step, "status", status).increment();
    }

    /** One per owner-requested re-preparation. */
    public void rePreparationRequested() {
        counter("robin_repreparations_total", "Owner-requested preparation re-runs").increment();
    }

    /** One per worker-reported plan outcome, tagged by outcome string. */
    public void planOutcome(String outcome) {
        counter("robin_plan_outcomes_total", "Automation plan outcomes",
                "outcome", outcome).increment();
    }

    /** One per automatic application decision, tagged AUTO_APPLY / NEEDS_REVIEW / SKIP. */
    public void decisionRecorded(String decision) {
        counter("robin_application_decisions_total", "Automatic application decisions",
                "decision", decision).increment();
    }

    /** One per explicit owner approval (APPROVED_FOR_SUBMISSION). */
    public void submitApproved() {
        counter("robin_submit_approvals_total", "Explicit human approvals for submission").increment();
    }

    /** One per stale plan reclaimed by the recovery sweeper. */
    public void stalePlansRecovered(long count) {
        counter("robin_stale_plans_recovered_total", "Plans reclaimed after heartbeat loss").increment(count);
    }

    /** One per scheduled discovery run, tagged by outcome. */
    public void scheduledDiscoveryRun(String outcome) {
        counter("robin_discovery_runs_total", "Scheduled board-source runs",
                "outcome", outcome).increment();
    }

    /** Test seam: redirect counting into an isolated registry. */
    void useRegistryForTesting(MeterRegistry testRegistry) {
        this.effective.set(testRegistry);
    }
}
