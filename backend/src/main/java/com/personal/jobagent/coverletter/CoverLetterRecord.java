package com.personal.jobagent.coverletter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CoverLetterRecord(
        UUID id,
        UUID profileId,
        UUID jobId,
        UUID applicationId,
        int version,
        String title,
        String bodyMarkdown,
        Map<String, Object> claimsValidation,
        boolean isApproved,
        Instant createdAt,
        Instant updatedAt
) {
}
