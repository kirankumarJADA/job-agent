package com.personal.jobagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * VERIFICATION STATUS: this class has NOT been exercised against a live
 * NIM endpoint. This sandbox has no route to arbitrary external domains
 * (only a small egress allowlist — see network configuration) and no real
 * NIM_BASE_URL/NIM_API_KEY was available during implementation. The
 * request/response shape assumes NIM's OpenAI-compatible
 * /v1/chat/completions endpoint, which is the commonly-documented NIM
 * interface, but this is NOT independently confirmed here. Test against a
 * real endpoint before relying on this for anything beyond isHealthy()
 * reporting DOWN when unconfigured.
 */
@Component
public class NimProvider implements LlmProvider {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final NimModelRegistry registry;

    @Value("${NIM_BASE_URL:}")
    private String baseUrl;

    @Value("${NIM_API_KEY:}")
    private String apiKey;

    public NimProvider(ObjectMapper objectMapper, NimModelRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public String providerId() {
        return "nim";
    }

    @Override
    public Set<String> supportedModels() {
        return registry.eligibleModelIds();
    }

    @Override
    public boolean isHealthy() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    @CircuitBreaker(name = "nim", fallbackMethod = "completeFallback")
    public LlmCompletion complete(LlmCompletionRequest request, Duration timeout) throws LlmProviderException {
        if (!isHealthy()) {
            throw new LlmProviderException("NIM provider not configured (NIM_API_KEY missing)");
        }
        if (!supportedModels().contains(request.modelKey())) {
            throw new LlmProviderException("NIM model is not confirmed free and available: " + request.modelKey());
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = Map.of(
                    "model", request.modelKey(),
                    "messages", request.messages(),
                    "temperature", request.temperature(),
                    "max_tokens", request.maxOutputTokens()
            );
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (response.statusCode() / 100 != 2) {
                throw new LlmProviderException("NIM returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String text = json.at("/choices/0/message/content").asText("");
            int promptTokens = json.at("/usage/prompt_tokens").asInt(0);
            int completionTokens = json.at("/usage/completion_tokens").asInt(0);

            return new LlmCompletion(text, new LlmCompletion.TokenUsage(promptTokens, completionTokens),
                    latency, LlmCompletion.FinishReason.STOP);
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmProviderException("NIM call failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unused")
    private LlmCompletion completeFallback(LlmCompletionRequest request, Duration timeout, Throwable t) {
        throw new LlmProviderException("NIM circuit open or call failed: " + t.getMessage(), t);
    }
}
