package com.personal.jobagent.coverletter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable cover-letter version. The body never changes after insert;
 * a correction is a new version whose {@code parentVersionId} points at the
 * version it corrects (V035). Only approval state and stored validation
 * metadata are updated in place.
 */
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
        Instant updatedAt,
        String contentSha256,
        UUID parentVersionId,
        String origin,
        boolean pdfStored
) {
    public CoverLetterRecord(UUID id, UUID profileId, UUID jobId, UUID applicationId, int version, String title,
                             String bodyMarkdown, Map<String, Object> claimsValidation, boolean isApproved,
                             Instant createdAt, Instant updatedAt) {
        this(id, profileId, jobId, applicationId, version, title, bodyMarkdown, claimsValidation, isApproved,
                createdAt, updatedAt, null, null, "GENERATED", false);
    }
}
