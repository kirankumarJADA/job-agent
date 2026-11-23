package com.personal.jobagent.discovery.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public response DTO for SearchPlan endpoints.
 * Sanitizes internal distributed synchronization state (executionOwnerToken, executionLeaseExpiresAt)
 * while returning all plan configuration, scheduling, and execution outcome properties.
 */
public record SearchPlanResponse(
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
    public static SearchPlanResponse fromRecord(SearchPlanRecord record) {
        if (record == null) {
            return null;
        }
        return new SearchPlanResponse(
                record.id(),
                record.profileId(),
                record.name(),
                record.targetTitles(),
                record.queryFacets(),
                record.targetSources(),
                record.searchFrequency(),
                record.maxResultsPerRun(),
                record.active(),
                record.lastRunAt(),
                record.lastExecutionStatus(),
                record.lastExecutionError(),
                record.lastExecutionResults(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
