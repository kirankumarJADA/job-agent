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
        boolean humanConfirmed,
        Instant createdAt,
        Instant updatedAt,
        UUID formQuestionId,
        String answerOrigin) {

    public ApplicationAnswerRecord(UUID id, UUID profileId, UUID jobId, UUID applicationId,
                                   String questionText, String questionType, String answerText,
                                   BigDecimal confidence, String status, Map<String, Object> validationNotes,
                                   boolean humanConfirmed, Instant createdAt, Instant updatedAt) {
        this(id, profileId, jobId, applicationId, questionText, questionType, answerText,
                confidence, status, validationNotes, humanConfirmed, createdAt, updatedAt, null, "MODEL_DRAFT");
    }

    public ApplicationAnswerRecord(UUID id, UUID profileId, UUID jobId, UUID applicationId,
                                   String questionText, String questionType, String answerText,
                                   BigDecimal confidence, String status, Map<String, Object> validationNotes,
                                   Instant createdAt, Instant updatedAt) {
        this(id, profileId, jobId, applicationId, questionText, questionType, answerText,
                confidence, status, validationNotes, false, createdAt, updatedAt, null, "MODEL_DRAFT");
    }

    /** True when a candidate (not a model draft) confirmed this answer. */
    public boolean candidateConfirmed() {
        return humanConfirmed && ("CANDIDATE_CONFIRMED".equals(answerOrigin) || "CANDIDATE_EDITED".equals(answerOrigin));
    }
}
