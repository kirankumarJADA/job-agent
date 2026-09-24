package com.personal.jobagent.notifications;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The persisted notification row (notifications table, V001 + V007 + V022).
 *
 * metadata carries event payload correlation (job_id, application_id,
 * cover_letter_id, event_id, ...). Producers must never place secrets
 * (OTPs, tokens, passwords, cookies) in here — same rule LogScrubber
 * enforces for logs, applied to the notification surface.
 *
 * {@code profileId} is the owning candidate (V022). It is null only for
 * system/ops notifications that genuinely belong to no user — an outbox
 * dead-letter being the main one — and those are never returned by the
 * user-facing API. Every notification derived from a user's application or
 * account carries that user's profile id, so two users notified about the
 * same shared job get two rows instead of one silently deduplicated row.
 */
public record NotificationRecord(
        UUID id,
        String severity,
        String category,
        String title,
        String body,
        String link,
        String dedupKey,
        Map<String, Object> metadata,
        UUID jobId,
        UUID applicationId,
        Instant readAt,
        Instant createdAt,
        UUID profileId
) {
}
