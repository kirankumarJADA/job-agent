package com.personal.jobagent.llm;

import java.time.Instant;
import java.util.Set;

public record ModelDescriptor(String provider, String modelId, String displayName, Set<String> capabilities, boolean reasoningSupport, boolean toolUseSupport, boolean structuredOutputSupport, boolean multimodal, Integer contextWindow, Integer maxOutputTokens, String availability, boolean freeEndpoint, boolean deprecated, Instant lastSeenAt) {
    public boolean eligibleForAutomaticUse() { return freeEndpoint && !deprecated && "AVAILABLE".equalsIgnoreCase(availability); }
}
