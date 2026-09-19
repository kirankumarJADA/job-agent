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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GeminiProvider implements LlmProvider {
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    @Value("${GEMINI_API_KEY:}") private String apiKey;
    public GeminiProvider(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
    @Override public String providerId() { return "gemini"; }
    @Override public Set<String> supportedModels() { return Set.of(); }
    @Override public boolean isHealthy() { return apiKey != null && !apiKey.isBlank(); }
    @Override
    @CircuitBreaker(name = "gemini", fallbackMethod = "completeFallback")
    public LlmCompletion complete(LlmCompletionRequest request, Duration timeout) throws LlmProviderException {
        if (!isHealthy()) throw new LlmProviderException("Gemini provider not configured (GEMINI_API_KEY missing)");
        long start = System.currentTimeMillis();
        try {
            String combinedText = request.messages().stream().map(m -> m.getOrDefault("content", "")).collect(Collectors.joining("\n"));
            Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", combinedText)))),
                    "generationConfig", Map.of("temperature", request.temperature(), "maxOutputTokens", request.maxOutputTokens()));
            HttpRequest call = HttpRequest.newBuilder().uri(URI.create(BASE_URL + request.modelKey() + ":generateContent?key=" + apiKey))
                    .header("Content-Type", "application/json").timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
            HttpResponse<String> response = httpClient.send(call, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            if (response.statusCode() / 100 != 2) throw new LlmProviderException("Gemini returned HTTP " + response.statusCode());
            JsonNode json = objectMapper.readTree(response.body());
            String text = json.at("/candidates/0/content/parts/0/text").asText("");
            return new LlmCompletion(text, new LlmCompletion.TokenUsage(json.at("/usageMetadata/promptTokenCount").asInt(0), json.at("/usageMetadata/candidatesTokenCount").asInt(0)), latency, LlmCompletion.FinishReason.STOP);
        } catch (LlmProviderException e) { throw e; } catch (Exception e) { throw new LlmProviderException("Gemini call failed: " + e.getMessage(), e); }
    }
    @SuppressWarnings("unused") private LlmCompletion completeFallback(LlmCompletionRequest request, Duration timeout, Throwable t) { throw new LlmProviderException("Gemini circuit open or call failed: " + t.getMessage(), t); }
}
