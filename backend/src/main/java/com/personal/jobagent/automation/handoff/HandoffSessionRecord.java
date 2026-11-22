package com.personal.jobagent.automation.handoff;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3: Immutable projection of an automation handoff session.
 */
public record HandoffSessionRecord(
        UUID id,
        UUID planId,
        UUID applicationId,
        UUID profileId,
        HandoffStatus status,
        HandoffBlockReason blockReason,
        String targetUrl,
        @JsonIgnore UUID handoffToken,
        Instant expiresAt,
        Instant completedAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean isPending() {
        return status == HandoffStatus.PENDING && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    public boolean isExpired() {
        return status == HandoffStatus.EXPIRED || (status == HandoffStatus.PENDING && expiresAt != null && !expiresAt.isAfter(Instant.now()));
    }
}
