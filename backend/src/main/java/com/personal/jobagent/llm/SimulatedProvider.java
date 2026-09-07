package com.personal.jobagent.llm;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Per architecture doc's validation gate item 4: "Ping endpoint can
 * simulate Gemini until then [real keys are supplied]." This provider is
 * always healthy and always available — it exists specifically so
 * /system/llm/ping and the router's fallback chain are testable before any
 * real API key exists, and so ModelRouter always has SOMETHING to fall
 * back to in the worst case.
 *
 * Deliberately NOT a stand-in for real quality evaluation — benchmark runs
 * (P1-f) should exclude this provider once real ones are configured, since
 * its "completions" are canned, not model output.
 */
@Component
public class SimulatedProvider implements LlmProvider {

    @Override
    public String providerId() {
        return "simulated";
    }

    @Override
    public Set<String> supportedModels() {
        return Set.of("simulated-v1");
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public LlmCompletion complete(LlmCompletionRequest request, Duration timeout) {
        long start = System.currentTimeMillis();
        String canned = "[simulated response for correlationId=" + request.correlationId() + "]";
        long latency = System.currentTimeMillis() - start;
        return new LlmCompletion(canned, new LlmCompletion.TokenUsage(0, 0), latency, LlmCompletion.FinishReason.STOP);
    }
}
