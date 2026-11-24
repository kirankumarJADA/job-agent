package com.personal.jobagent.discovery;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Discovery telemetry metrics (P1-2).
 * Metrics are tagged by kind, mode and status; never by source_id.
 */
@Component
public class DiscoveryMetrics {

    private final MeterRegistry registry;

    public DiscoveryMetrics(@Autowired(required = false) MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRun(String kind, String mode, String status) {
        if (registry != null) {
            registry.counter("app.discovery.runs", "kind", kind, "mode", mode, "status", status).increment();
        }
    }

    public void recordJobIngestion(String kind, String action, boolean crossSource) {
        if (registry != null) {
            registry.counter("app.discovery.jobs", "kind", kind, "action", action, "cross_source", String.valueOf(crossSource)).increment();
        }
    }

    public void recordFreshness(String kind, String freshnessCategory) {
        if (registry != null) {
            registry.counter("app.discovery.freshness", "kind", kind, "freshness", freshnessCategory).increment();
        }
    }
}
