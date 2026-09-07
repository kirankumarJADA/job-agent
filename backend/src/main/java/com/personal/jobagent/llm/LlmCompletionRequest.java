package com.personal.jobagent.llm;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LlmCompletionRequest(
        String modelKey,
        String systemPrompt,
        List<Map<String, String>> messages,
        Map<String, Object> responseSchema,
        double temperature,
        int maxOutputTokens,
        UUID correlationId
) {

    public static LlmCompletionRequest simple(String modelKey, String prompt, UUID correlationId) {
        return new LlmCompletionRequest(modelKey, null, List.of(Map.of("role", "user", "content", prompt)),
                null, 0.2, 512, correlationId);
    }
}
