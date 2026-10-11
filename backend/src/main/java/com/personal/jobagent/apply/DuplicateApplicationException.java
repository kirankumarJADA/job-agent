package com.personal.jobagent.apply;

import java.util.UUID;

/**
 * Cross-source duplicate protection refused an application (Phase 8.2). The
 * existing application is always the CALLER'S OWN — a duplicate is only ever
 * detected within one owner's applications, so nothing about another user's
 * application can leak through this exception.
 */
public class DuplicateApplicationException extends RuntimeException {

    private final UUID existingApplicationId;
    private final UUID existingJobId;
    private final String identityKey;
    private final String matchReason;

    public DuplicateApplicationException(UUID existingApplicationId, UUID existingJobId,
                                         String identityKey, String matchReason) {
        super("duplicate application: " + matchReason);
        this.existingApplicationId = existingApplicationId;
        this.existingJobId = existingJobId;
        this.identityKey = identityKey;
        this.matchReason = matchReason;
    }

    public UUID existingApplicationId() { return existingApplicationId; }
    public UUID existingJobId() { return existingJobId; }
    public String identityKey() { return identityKey; }
    public String matchReason() { return matchReason; }
}
