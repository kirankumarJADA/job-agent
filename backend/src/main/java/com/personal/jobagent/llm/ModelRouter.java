package com.personal.jobagent.llm;

import com.personal.jobagent.common.LogScrubber;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHASE 1 SIMPLIFICATION, documented explicitly: the architecture's
 * intended design routes via routing_policies (primary_model_id +
 * fallback_model_ids per task_type), promoted from real benchmark runs
 * (P1-f). That table needs real llm_models rows, which need real NIM/
 * Gemini model identifiers — not available during this build pass (no
 * real API keys/credentials). Until those exist, this router uses a fixed
 * provider priority order (nim, gemini, simulated) for every task type.
 * The /routing GET/PUT endpoints still read/write routing_policies
 * directly so the contract and table are exercised — swapping the
 * execute() method to consult that table instead of the fixed order is a
 * contained future change, not a redesign.
 *
 * Every attempt (including providers skipped for being unconfigured) is
 * ledgered to llm_calls, per architecture doc §C5's "all attempts land in
 * llm_calls" guarantee.
 */
@Component
public class ModelRouter {

    private final Map<String, LlmProvider> providerMap;
    private final List<LlmProvider> defaultProviders;
    private final JdbcTemplate jdbcTemplate;

    private record ModelCandidate(UUID modelId, String providerId, String modelKey, LlmProvider provider) {
    }

    public ModelRouter(List<LlmProvider> providers, JdbcTemplate jdbcTemplate) {
        this.providerMap = providers.stream()
                .collect(java.util.stream.Collectors.toMap(LlmProvider::providerId, p -> p, (a, b) -> a));
        // SimulatedProvider sorted last so real providers are always preferred by default
        this.defaultProviders = providers.stream()
                .sorted((a, b) -> Integer.compare(
                        b.providerId().equals("simulated") ? 0 : 1,
                        a.providerId().equals("simulated") ? 0 : 1))
                .toList();
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ExecutionResult(LlmCompletion completion, RoutingTrace trace) {
    }

    public ExecutionResult execute(TaskType task, LlmCompletionRequest request, Duration deadline) {
        return execute(task, request, deadline, java.util.Set.of());
    }

    /**
     * @param forceSkipProviders provider ids (or "PRIMARY") to treat as failed without
     *        actually calling them — backs /system/llm/ping's
     *        forceFallback query param so the failover path can be
     *        demonstrated on demand rather than only when a provider
     *        happens to be genuinely down.
     */
    public ExecutionResult execute(TaskType task, LlmCompletionRequest request, Duration deadline,
                                    java.util.Set<String> forceSkipProviders) {
        List<ModelCandidate> candidates = resolveCandidates(task);
        List<RoutingTrace.ModelAttempt> attempts = new ArrayList<>();
        boolean skippedFirst = false;

        for (ModelCandidate candidate : candidates) {
            LlmProvider provider = candidate.provider();
            String providerId = candidate.providerId();
            UUID modelId = candidate.modelId();
            String modelKey = candidate.modelKey();

            long start = System.currentTimeMillis();
            boolean forceSkip = forceSkipProviders.contains(providerId)
                    || (forceSkipProviders.contains("PRIMARY") && !skippedFirst);

            if (forceSkip) {
                skippedFirst = true;
                attempts.add(new RoutingTrace.ModelAttempt(providerId, 0, false, "FORCED_SKIP"));
                ledgerCall(task, providerId, modelId, false, null, "FORCED_SKIP", 0, request.correlationId());
                continue;
            }
            if (!provider.isHealthy()) {
                attempts.add(new RoutingTrace.ModelAttempt(providerId, 0, false, "NOT_CONFIGURED"));
                ledgerCall(task, providerId, modelId, false, null, "NOT_CONFIGURED", 0, request.correlationId());
                continue;
            }
            try {
                LlmCompletionRequest effectiveRequest = (modelKey != null && !modelKey.equals("default")
                        && !modelKey.equals(request.modelKey()))
                        ? new LlmCompletionRequest(modelKey, request.systemPrompt(), request.messages(),
                        request.responseSchema(), request.temperature(), request.maxOutputTokens(),
                        request.correlationId())
                        : request;

                LlmCompletion completion = provider.complete(effectiveRequest, deadline);
                long latency = System.currentTimeMillis() - start;
                attempts.add(new RoutingTrace.ModelAttempt(providerId, latency, true, null));
                ledgerCall(task, providerId, modelId, true, completion, null, latency, request.correlationId());

                RoutingTrace trace = new RoutingTrace(task, providerId,
                        attempts.size() == 1 ? "PRIMARY_SUCCESS" : "FAILOVER_SUCCESS", attempts);
                return new ExecutionResult(completion, trace);

            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                String errorClass = e.getClass().getSimpleName();
                attempts.add(new RoutingTrace.ModelAttempt(providerId, latency, false, errorClass));
                ledgerCall(task, providerId, modelId, false, null, errorClass, latency, request.correlationId());
            }
        }

        throw new AllProvidersExhaustedException(
                "All providers exhausted for task " + task + ": " + attempts);
    }

    private List<ModelCandidate> resolveCandidates(TaskType task) {
        List<ModelCandidate> candidates = new ArrayList<>();
        try {
            List<Map<String, Object>> policies = jdbcTemplate.queryForList(
                    "select primary_model_id, fallback_model_ids from routing_policies where task_type = ?", task.name());

            if (!policies.isEmpty()) {
                Map<String, Object> policy = policies.get(0);
                UUID primaryModelId = (UUID) policy.get("primary_model_id");
                if (primaryModelId != null) {
                    addModelCandidate(primaryModelId, candidates);
                }

                Object fallbacksObj = policy.get("fallback_model_ids");
                if (fallbacksObj instanceof UUID[] fallbackUuids) {
                    for (UUID fid : fallbackUuids) {
                        addModelCandidate(fid, candidates);
                    }
                } else if (fallbacksObj instanceof java.sql.Array sqlArray) {
                    UUID[] fallbackUuids = (UUID[]) sqlArray.getArray();
                    for (UUID fid : fallbackUuids) {
                        addModelCandidate(fid, candidates);
                    }
                }
            }
        } catch (Exception ignored) {
            // DB read failed or table empty, fall back gracefully
        }

        // Add remaining default providers not already in candidates
        for (LlmProvider provider : defaultProviders) {
            boolean alreadyPresent = candidates.stream().anyMatch(c -> c.providerId().equals(provider.providerId()));
            if (!alreadyPresent) {
                candidates.add(new ModelCandidate(null, provider.providerId(), "default", provider));
            }
        }

        return candidates;
    }

    private void addModelCandidate(UUID modelId, List<ModelCandidate> candidates) {
        try {
            List<Map<String, Object>> models = jdbcTemplate.queryForList(
                    "select id, provider_id, model_key, enabled from llm_models where id = ? and enabled = true", modelId);
            if (!models.isEmpty()) {
                Map<String, Object> m = models.get(0);
                String providerId = (String) m.get("provider_id");
                String modelKey = (String) m.get("model_key");
                LlmProvider provider = providerMap.get(providerId);
                if (provider != null) {
                    candidates.add(new ModelCandidate(modelId, providerId, modelKey, provider));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void ledgerCall(TaskType task, String providerId, UUID modelId, boolean ok, LlmCompletion completion,
                             String errorClass, long latencyMs, UUID correlationId) {
        Integer inputTokens = completion != null ? completion.usage().inputTokens() : null;
        Integer outputTokens = completion != null ? completion.usage().outputTokens() : null;
        String requestRedacted = LogScrubber.scrub("{\"note\":\"request body not persisted verbatim in Phase 1\"}");

        jdbcTemplate.update("""
                        insert into llm_calls
                            (id, task_type, provider_id, model_id, ok, http_status, error_class, latency_ms,
                             input_tokens, output_tokens, est_cost, attempt, prompt_checksum, correlation_id,
                             request_redacted, response_excerpt, created_at)
                        values (?, ?, ?, ?, ?, null, ?, ?, ?, ?, null, 1, null, ?, ?::jsonb, null, now())
                        """,
                UuidV7.generate(), task.name(), providerId, modelId, ok, errorClass, latencyMs,
                inputTokens, outputTokens, correlationId, requestRedacted);
    }
}
