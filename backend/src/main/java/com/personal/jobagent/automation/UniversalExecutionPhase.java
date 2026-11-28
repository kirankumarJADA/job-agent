package com.personal.jobagent.automation;

import java.util.Locale;

/**
 * Durable execution phases recorded in {@code automation_steps.result->>'phase'}
 * across inspection, filling, candidate-input pauses, resume transitions, and
 * the synchronous pre-submit click fence.
 */
public enum UniversalExecutionPhase {
    INSPECTING,
    FILLING,
    UPLOADING,
    AWAITING_CANDIDATE_INPUT,
    PAUSED,
    READY_TO_CONTINUE,
    PRE_SUBMIT_ATTEMPTING,
    COMPLETED,
    FAILED;

    public static UniversalExecutionPhase parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
