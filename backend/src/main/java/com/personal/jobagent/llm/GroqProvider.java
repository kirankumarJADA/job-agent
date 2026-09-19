package com.personal.jobagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class GroqProvider implements LlmProvider {
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json;
    @Value("${GROQ_BASE_URL:https://api.groq.com/openai}") private String baseUrl;
    @Value("${GROQ_API_KEY:}") private String apiKey;
    public GroqProvider(ObjectMapper json) { this.json = json; }
    public String providerId() { return "groq"; }
    /** Deprecated llama-3.3-70b-versatile is intentionally not an active default. */
    public Set<String> supportedModels() { return Set.of(); }
    public boolean isHealthy() { return apiKey != null && !apiKey.isBlank(); }
    public LlmCompletion complete(LlmCompletionRequest request, Duration timeout) throws LlmProviderException {
        if (!isHealthy()) throw new LlmProviderException("Groq provider not configured (GROQ_API_KEY missing)");
        long start = System.currentTimeMillis();
        try {
            Map<String,Object> body = Map.of("model", request.modelKey(), "messages", request.messages(), "temperature", request.temperature(), "max_tokens", request.maxOutputTokens());
            HttpRequest call = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = client.send(call, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new LlmProviderException("Groq returned HTTP " + response.statusCode());
            JsonNode node = json.readTree(response.body());
            String text = node.at("/choices/0/message/content").asText("");
            return new LlmCompletion(text, new LlmCompletion.TokenUsage(node.at("/usage/prompt_tokens").asInt(0), node.at("/usage/completion_tokens").asInt(0)), System.currentTimeMillis() - start, LlmCompletion.FinishReason.STOP);
        } catch (LlmProviderException e) { throw e; } catch (Exception e) { throw new LlmProviderException("Groq call failed: " + e.getMessage(), e); }
    }
}
