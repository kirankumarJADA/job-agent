package com.personal.jobagent.automation.handoff;

import java.time.Instant;
import java.util.UUID;

/**
 * Public candidate-facing API response DTO for an ATS handoff session.
 * Excludes internal authentication secrets such as handoffToken.
 */
public record HandoffSessionResponse(
        UUID id,
        UUID planId,
        UUID applicationId,
        UUID profileId,
        HandoffStatus status,
        HandoffBlockReason blockReason,
        String targetUrl,
        Instant expiresAt,
        Instant completedAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static HandoffSessionResponse from(HandoffSessionRecord record) {
        if (record == null) {
            return null;
        }
        return new HandoffSessionResponse(
                record.id(),
                record.planId(),
                record.applicationId(),
                record.profileId(),
                record.status(),
                record.blockReason(),
                record.targetUrl(),
                record.expiresAt(),
                record.completedAt(),
                record.cancelledAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
