package com.personal.jobagent.discovery.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain entity and wire contract for configurable candidate search plans.
 * A search plan specifies multi-source retrieval criteria, title/skill facets,
 * cadence controls, and execution outcome tracking scoped to a candidate profile.
 */
public record SearchPlanRecord(
        UUID id,
        UUID profileId,
        String name,
        List<String> targetTitles,
        Map<String, Object> queryFacets,
        List<String> targetSources,
        String searchFrequency,
        int maxResultsPerRun,
        boolean active,
        Instant lastRunAt,
        String lastExecutionStatus,
        String lastExecutionError,
        int lastExecutionResults,
        Instant createdAt,
        Instant updatedAt
) {
    public SearchPlanRecord {
        name = name != null ? name.trim() : "";
        targetTitles = targetTitles == null ? List.of() : List.copyOf(targetTitles);
        queryFacets = queryFacets == null ? Map.of() : Map.copyOf(queryFacets);
        targetSources = targetSources == null ? List.of() : List.copyOf(targetSources);
        searchFrequency = (searchFrequency == null || searchFrequency.isBlank()) ? "DAILY" : searchFrequency.toUpperCase();
        if (maxResultsPerRun <= 0) {
            maxResultsPerRun = 50;
        }
        if (lastExecutionResults < 0) {
            lastExecutionResults = 0;
        }
    }

    /**
     * Backward-compatible 12-parameter constructor for existing callers and fixtures.
     */
    public SearchPlanRecord(UUID id, UUID profileId, String name, List<String> targetTitles,
                            Map<String, Object> queryFacets, List<String> targetSources,
                            String searchFrequency, int maxResultsPerRun, boolean active,
                            Instant lastRunAt, Instant createdAt, Instant updatedAt) {
        this(id, profileId, name, targetTitles, queryFacets, targetSources, searchFrequency,
                maxResultsPerRun, active, lastRunAt, null, null, 0, createdAt, updatedAt);
    }
}
