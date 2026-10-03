package com.personal.jobagent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 18 verification: with NVIDIA NIM configured and healthy, the router
 * must select a NIM model FIRST and never fall through to the
 * SimulatedProvider; the simulator is reachable ONLY when every real provider
 * is unhealthy (no keys) or exhausted. Key presence is modelled by
 * isHealthy(), exactly as NimProvider implements it (NIM_BASE_URL +
 * NIM_API_KEY non-blank).
 */
class ModelRouterNimPriorityTest {

    private static final String NIM_MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LlmProvider nim = mock(LlmProvider.class);
    private final LlmProvider simulated = mock(LlmProvider.class);
    private ModelRouter router;

    @BeforeEach
    void setUp() {
        Mockito.reset(jdbc, nim, simulated);
        when(nim.providerId()).thenReturn("nim");
        when(nim.supportedModels()).thenReturn(Set.of(NIM_MODEL));
        when(nim.isHealthy()).thenReturn(true); // == NIM_API_KEY + NIM_BASE_URL present
        when(nim.complete(any(), any(Duration.class))).thenReturn(new LlmCompletion(
                "NIM completion", new LlmCompletion.TokenUsage(1, 2), 5, LlmCompletion.FinishReason.STOP));

        when(simulated.providerId()).thenReturn("simulated");
        when(simulated.supportedModels()).thenReturn(Set.of("simulated-v1"));
        when(simulated.isHealthy()).thenReturn(true);
        when(simulated.complete(any(), any(Duration.class))).thenReturn(new LlmCompletion(
                "simulated completion", new LlmCompletion.TokenUsage(1, 2), 5, LlmCompletion.FinishReason.STOP));

        // llm_models rows: both models exist and are enabled (the NIM registry
        // upsert enables eligible models on startup when a key is present).
        // Two stubs, belt-and-braces: the routing-policy path (queryForMap on
        // the model id) and the preferred-model path (queryForList keyed by
        // provider + model key).
        java.util.UUID nimModelId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(inv -> List.of());
        java.util.Map<String, Object> policyRow = new java.util.HashMap<>();
        policyRow.put("primary_model_id", nimModelId);
        policyRow.put("fallback_model_ids", null);
        when(jdbc.queryForList(contains("routing_policies"), anyString())).thenAnswer(inv -> List.of(policyRow));
        when(jdbc.queryForMap(contains("llm_models"), any())).thenReturn(Map.of(
                "id", nimModelId, "provider_id", "nim", "model_key", NIM_MODEL, "enabled", true));
        when(jdbc.queryForList(contains("llm_models"), anyString(), anyString())).thenAnswer(inv -> {
            String providerId = (String) inv.getArgument(1);
            String modelKey = (String) inv.getArgument(2);
            if ("nim".equals(providerId) && NIM_MODEL.equals(modelKey)) {
                return List.of(Map.of("id", nimModelId, "enabled", true));
            }
            if ("simulated".equals(providerId) && "simulated-v1".equals(modelKey)) {
                return List.of(Map.of("id", UUID.randomUUID(), "enabled", true));
            }
            return List.of();
        });

        router = new ModelRouter(List.of(nim, simulated), jdbc);
    }

    private LlmCompletionRequest request() {
        return LlmCompletionRequest.simple("default", "Write a grounded cover letter.", UUID.randomUUID());
    }

    @Test
    void aHealthyNimProviderServesCoverLettersAndTheSimulatorIsNeverInvoked() {
        ModelRouter.ExecutionResult result = router.execute(TaskType.COVER_LETTER, request(), Duration.ofSeconds(30));

        assertThat(result.completion().text()).isEqualTo("NIM completion");
        assertThat(result.trace().chosenProvider()).isEqualTo("nim");
        verify(nim).complete(any(), any(Duration.class));
        verify(simulated, never()).complete(any(), any(Duration.class));
    }

    @Test
    void applicationQaRoutesThroughNimTheSameWay() {
        ModelRouter.ExecutionResult result = router.execute(TaskType.APPLICATION_QA, request(), Duration.ofSeconds(30));

        assertThat(result.completion().text()).isEqualTo("NIM completion");
        verify(simulated, never()).complete(any(), any(Duration.class));
    }

    @Test
    void theSimulatorIsOnlyReachedWhenEveryRealProviderIsUnhealthy() {
        when(nim.isHealthy()).thenReturn(false); // == no NIM_API_KEY on the deployment

        ModelRouter.ExecutionResult result = router.execute(TaskType.COVER_LETTER, request(), Duration.ofSeconds(30));

        assertThat(result.completion().text()).isEqualTo("simulated completion");
        verify(nim, never()).complete(any(), any(Duration.class));
        verify(simulated).complete(any(), any(Duration.class));
    }
}
