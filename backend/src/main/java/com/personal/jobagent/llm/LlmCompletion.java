package com.personal.jobagent.llm;

public record LlmCompletion(
        String text,
        TokenUsage usage,
        long latencyMs,
        FinishReason finishReason
) {

    public record TokenUsage(int inputTokens, int outputTokens) {
    }

    public enum FinishReason {
        STOP, LENGTH, ERROR
    }
}
