package com.personal.jobagent.qa;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ApplicationAnswerRecord(
        UUID id,
        UUID profileId,
        UUID jobId,
        UUID applicationId,
        String questionText,
        String questionType,
        String answerText,
        BigDecimal confidence,
        String status,
        Map<String, Object> validationNotes,
        Instant createdAt,
        Instant updatedAt
) {
}