package com.personal.jobagent.llm;

import com.personal.jobagent.common.LogScrubber;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ModelRouter {
    private final Map<String, LlmProvider> providerMap;
    private final List<LlmProvider> defaultProviders;
    private final JdbcTemplate jdbcTemplate;
    private record ModelCandidate(UUID modelId, String providerId, String modelKey, LlmProvider provider) {}

    public ModelRouter(List<LlmProvider> providers, JdbcTemplate jdbcTemplate) {
        providerMap = providers.stream().collect(Collectors.toMap(LlmProvider::providerId, p -> p, (a,b) -> a));
        defaultProviders = providers.stream().sorted(Comparator.comparing(p -> p.providerId().equals("simulated") ? 1 : 0)).toList();
        this.jdbcTemplate = jdbcTemplate;
    }
    public record ExecutionResult(LlmCompletion completion, RoutingTrace trace) {}
    public ExecutionResult execute(TaskType task, LlmCompletionRequest request, Duration deadline) { return execute(task, request, deadline, Set.of()); }

    public ExecutionResult execute(TaskType task, LlmCompletionRequest request, Duration deadline, Set<String> forceSkipProviders) {
        List<ModelCandidate> candidates = resolveCandidates(task);
        List<RoutingTrace.ModelAttempt> attempts = new ArrayList<>();
        boolean skippedFirst = false;
        for (ModelCandidate candidate : candidates) {
            long start = System.currentTimeMillis();
            boolean forced = forceSkipProviders.contains(candidate.providerId()) || (forceSkipProviders.contains("PRIMARY") && !skippedFirst);
            if (forced) {
                skippedFirst = true;
                attempts.add(new RoutingTrace.ModelAttempt(candidate.providerId(), 0, false, "FORCED_SKIP"));
                ledgerCall(task, candidate.providerId(), candidate.modelId(), false, null, "FORCED_SKIP", 0, request.correlationId());
                continue;
            }
            if (!candidate.provider().isHealthy()) {
                attempts.add(new RoutingTrace.ModelAttempt(candidate.providerId(), 0, false, "NOT_CONFIGURED"));
                ledgerCall(task, candidate.providerId(), candidate.modelId(), false, null, "NOT_CONFIGURED", 0, request.correlationId());
                continue;
            }
            try {
                LlmCompletionRequest effective = new LlmCompletionRequest(candidate.modelKey(), request.systemPrompt(), request.messages(), request.responseSchema(), request.temperature(), request.maxOutputTokens(), request.correlationId());
                LlmCompletion completion = candidate.provider().complete(effective, deadline);
                long latency = System.currentTimeMillis() - start;
                attempts.add(new RoutingTrace.ModelAttempt(candidate.providerId(), latency, true, null));
                ledgerCall(task, candidate.providerId(), candidate.modelId(), true, completion, null, latency, request.correlationId());
                return new ExecutionResult(completion, new RoutingTrace(task, candidate.providerId(), attempts.size() == 1 ? "PRIMARY_SUCCESS" : "FAILOVER_SUCCESS", attempts));
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                String error = e.getClass().getSimpleName();
                attempts.add(new RoutingTrace.ModelAttempt(candidate.providerId(), latency, false, error));
                ledgerCall(task, candidate.providerId(), candidate.modelId(), false, null, error, latency, request.correlationId());
            }
        }
        throw new AllProvidersExhaustedException("No eligible free LLM provider succeeded for task " + task + ": " + attempts);
    }

    private List<ModelCandidate> resolveCandidates(TaskType task) {
        List<ModelCandidate> out = new ArrayList<>();
        try {
            List<Map<String,Object>> policies = jdbcTemplate.queryForList("select primary_model_id, fallback_model_ids from routing_policies where task_type = ?", task.name());
            if (!policies.isEmpty()) {
                Map<String,Object> p = policies.get(0);
                Object primary = p.get("primary_model_id");
                if (primary instanceof UUID primaryId) addModelCandidate(primaryId, out);
                Object fallback = p.get("fallback_model_ids");
                if (fallback instanceof java.sql.Array array) {
                    for (Object rawId : (Object[]) array.getArray()) if (rawId instanceof UUID fallbackId) addModelCandidate(fallbackId, out);
                } else if (fallback instanceof UUID[] ids) {
                    for (UUID fallbackId : ids) addModelCandidate(fallbackId, out);
                }
            }
        } catch (Exception ignored) { }
        for (String key : preferredModels(task)) addModelByKey(key, out);
        for (LlmProvider provider : defaultProviders) for (String key : provider.supportedModels()) addModelByKey(provider.providerId(), key, out);
        return out;
    }

    private List<String> preferredModels(TaskType task) {
        return switch (task) {
            case JOB_DESCRIPTION_ANALYSIS, SPONSORSHIP_ANALYSIS, CV_TAILORING, COVER_LETTER -> List.of("nvidia/nemotron-3.5-lightning-30b-a3b", "z-ai/glm-5-3");
            case JOB_CLASSIFICATION, SKILL_EXTRACTION, HIGH_VOLUME_EXTRACTION -> List.of("z-ai/glm-5-3-flash", "nvidia/nemotron-3.5-lightning-30b-a3b", "openai/gpt-oss-20b");
            case APPLICATION_QA -> List.of("nvidia/nemotron-3.5-lightning-30b-a3b", "z-ai/glm-5-3-flash");
            case TECHNICAL_REASONING -> List.of("qwen/qwen3-next-80b-a3b-thinking", "z-ai/glm-5-3");
            case LARGE_CONTEXT -> List.of("moonshotai/kimi-k3", "openai/gpt-oss-120b");
            case SKILL_MATCHING -> List.of("z-ai/glm-5-3-flash", "nvidia/nemotron-3.5-lightning-30b-a3b");
            case EMAIL_CLASSIFICATION -> List.of("z-ai/glm-5-3-flash", "openai/gpt-oss-20b");
        };
    }
    private void addModelByKey(String key, List<ModelCandidate> out) { for (LlmProvider provider : defaultProviders) addModelByKey(provider.providerId(), key, out); }
    private void addModelByKey(String providerId, String key, List<ModelCandidate> out) {
        LlmProvider provider = providerMap.get(providerId);
        if (provider == null || !provider.supportedModels().contains(key) || out.stream().anyMatch(c -> c.providerId().equals(providerId) && c.modelKey().equals(key))) return;
        try {
            List<Map<String,Object>> rows = jdbcTemplate.queryForList("select id, enabled from llm_models where provider_id=? and model_key=?", providerId, key);
            if (rows.size() == 1 && Boolean.TRUE.equals(rows.get(0).get("enabled"))) out.add(new ModelCandidate((UUID) rows.get(0).get("id"), providerId, key, provider));
        } catch (Exception ignored) { }
    }
    private void addModelCandidate(UUID id, List<ModelCandidate> out) {
        try {
            Map<String,Object> row = jdbcTemplate.queryForMap("select id, provider_id, model_key, enabled from llm_models where id=?", id);
            String providerId = (String) row.get("provider_id"); String key = (String) row.get("model_key"); LlmProvider provider = providerMap.get(providerId);
            if (provider != null && Boolean.TRUE.equals(row.get("enabled")) && provider.supportedModels().contains(key) && out.stream().noneMatch(c -> c.modelId() != null && c.modelId().equals(id))) out.add(new ModelCandidate(id, providerId, key, provider));
        } catch (Exception ignored) { }
    }
    private void ledgerCall(TaskType task, String providerId, UUID modelId, boolean ok, LlmCompletion completion, String error, long latency, UUID correlationId) {
        try {
            Integer in = completion == null ? null : completion.usage().inputTokens(); Integer out = completion == null ? null : completion.usage().outputTokens();
            jdbcTemplate.update("insert into llm_calls(id,task_type,provider_id,model_id,ok,http_status,error_class,latency_ms,input_tokens,output_tokens,est_cost,attempt,prompt_checksum,correlation_id,request_redacted,response_excerpt,created_at) values(?,?,?,?,?,null,?,?,?, ?,null,1,null,?,?::jsonb,null,now())", UuidV7.generate(), task.name(), providerId, modelId, ok, error, latency, in, out, correlationId, LogScrubber.scrub("{\"note\":\"request body not persisted verbatim\"}"));
        } catch (Exception ignored) { }
    }
}
