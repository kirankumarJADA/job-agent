package com.personal.jobagent.llm;

import com.personal.jobagent.common.LogScrubber;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    private final List<LlmProvider> providers;
    private final JdbcTemplate jdbcTemplate;

    public ModelRouter(List<LlmProvider> providers, JdbcTemplate jdbcTemplate) {
        // Fixed priority order per the simplification above. SimulatedProvider
        // sorted last so real providers are always preferred when configured.
        this.providers = providers.stream()
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
     * @param forceSkipProviders provider ids to treat as failed without
     *        actually calling them — backs /system/llm/ping's
     *        forceFallback query param so the failover path can be
     *        demonstrated on demand rather than only when a provider
     *        happens to be genuinely down.
     */
    public ExecutionResult execute(TaskType task, LlmCompletionRequest request, Duration deadline,
                                    java.util.Set<String> forceSkipProviders) {
        List<RoutingTrace.ModelAttempt> attempts = new ArrayList<>();

        for (LlmProvider provider : providers) {
            long start = System.currentTimeMillis();
            if (forceSkipProviders.contains(provider.providerId())) {
                attempts.add(new RoutingTrace.ModelAttempt(provider.providerId(), 0, false, "FORCED_SKIP"));
                ledgerCall(task, provider.providerId(), false, null, "FORCED_SKIP", 0, request.correlationId());
                continue;
            }
            if (!provider.isHealthy()) {
                attempts.add(new RoutingTrace.ModelAttempt(provider.providerId(), 0, false, "NOT_CONFIGURED"));
                ledgerCall(task, provider.providerId(), false, null, "NOT_CONFIGURED", 0, request.correlationId());
                continue;
            }
            try {
                LlmCompletion completion = provider.complete(request, deadline);
                long latency = System.currentTimeMillis() - start;
                attempts.add(new RoutingTrace.ModelAttempt(provider.providerId(), latency, true, null));
                ledgerCall(task, provider.providerId(), true, completion, null, latency, request.correlationId());

                RoutingTrace trace = new RoutingTrace(task, provider.providerId(),
                        attempts.size() == 1 ? "PRIMARY_SUCCESS" : "FAILOVER_SUCCESS", attempts);
                return new ExecutionResult(completion, trace);

            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                String errorClass = e.getClass().getSimpleName();
                attempts.add(new RoutingTrace.ModelAttempt(provider.providerId(), latency, false, errorClass));
                ledgerCall(task, provider.providerId(), false, null, errorClass, latency, request.correlationId());
            }
        }

        throw new AllProvidersExhaustedException(
                "All providers exhausted for task " + task + ": " + attempts);
    }

    private void ledgerCall(TaskType task, String providerId, boolean ok, LlmCompletion completion,
                             String errorClass, long latencyMs, UUID correlationId) {
        Integer inputTokens = completion != null ? completion.usage().inputTokens() : null;
        Integer outputTokens = completion != null ? completion.usage().outputTokens() : null;
        String requestRedacted = LogScrubber.scrub("{\"note\":\"request body not persisted verbatim in Phase 1\"}");

        jdbcTemplate.update("""
                        insert into llm_calls
                            (id, task_type, provider_id, model_id, ok, http_status, error_class, latency_ms,
                             input_tokens, output_tokens, est_cost, attempt, prompt_checksum, correlation_id,
                             request_redacted, response_excerpt, created_at)
                        values (?, ?, ?, null, ?, null, ?, ?, ?, ?, null, 1, null, ?, ?::jsonb, null, now())
                        """,
                UuidV7.generate(), task.name(), providerId, ok, errorClass, latencyMs,
                inputTokens, outputTokens, correlationId, requestRedacted);
    }
}
