package com.personal.jobagent.notifications;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The persisted notification row (notifications table, V001 + V007).
 *
 * metadata carries event payload correlation (job_id, application_id,
 * cover_letter_id, event_id, ...). Producers must never place secrets
 * (OTPs, tokens, passwords, cookies) in here — same rule LogScrubber
 * enforces for logs, applied to the notification surface.
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
        Instant createdAt
) {
}
