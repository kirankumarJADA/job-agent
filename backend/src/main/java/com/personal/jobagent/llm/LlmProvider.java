package com.personal.jobagent.llm;

import java.time.Duration;
import java.util.Set;

/**
 * The entire seam other modules see, per architecture doc §C5. Every
 * implementation (NimProvider, GeminiProvider, SimulatedProvider) is a
 * Spring bean discovered by ModelRouter via Spring's List<LlmProvider>
 * injection — adding a new provider means writing one class, not touching
 * the router.
 */
public interface LlmProvider {

    String providerId();

    Set<String> supportedModels();

    boolean isHealthy();

    LlmCompletion complete(LlmCompletionRequest request, Duration timeout) throws LlmProviderException;
}
