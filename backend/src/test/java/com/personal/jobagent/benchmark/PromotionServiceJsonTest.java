package com.personal.jobagent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * POST /api/v1/benchmarks/runs/{id}/promote returns a routing_policies row, whose
 * fallback_model_ids is uuid[] — the same driver-object serialization the routing GET/PUT had.
 */
class PromotionServiceJsonTest {

    private static final UUID RUN_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID MODEL_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID FALLBACK_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private JdbcTemplate jdbc;
    private PromotionService service;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        service = new PromotionService(jdbc);
    }

    @Test
    void promoteReturnsRoutingRowWithFallbackModelIdsAsJsonArray() throws Exception {
        when(jdbc.queryForMap(anyString(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("model_benchmark_runs")) {
                return new LinkedHashMap<>(Map.of(
                        "suite", "job_classification@v1",
                        "task_type", "JOB_CLASSIFICATION",
                        "status", "COMPLETED"));
            }
            return routingRow();
        });
        when(jdbc.queryForList(anyString(), Mockito.<Object>any())).thenReturn(List.of(new LinkedHashMap<>(Map.of(
                "model_id", MODEL_ID,
                "case_count", 12L,
                "mean_score", 0.9))));

        Map<String, Object> promoted = service.promote(RUN_ID);

        assertThat(promoted.get("task_type")).isEqualTo("JOB_CLASSIFICATION");
        assertThat(promoted.get("fallback_model_ids")).isEqualTo(List.of(FALLBACK_ID));
        assertThat(promoted.values()).noneMatch(value -> value instanceof Array);

        String json = new ObjectMapper().writeValueAsString(promoted);
        assertThat(json).contains("\"fallback_model_ids\":[\"" + FALLBACK_ID + "\"]");
    }

    private static Map<String, Object> routingRow() throws SQLException {
        Array fallbackIds = Mockito.mock(Array.class);
        when(fallbackIds.getArray()).thenReturn(new UUID[]{FALLBACK_ID});

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_type", "JOB_CLASSIFICATION");
        row.put("primary_model_id", MODEL_ID);
        row.put("fallback_model_ids", fallbackIds);
        row.put("basis", "BENCHMARK");
        row.put("based_on_run_id", RUN_ID);
        row.put("rationale", "Promoted from run " + RUN_ID);
        row.put("updated_at", Timestamp.from(java.time.Instant.parse("2026-09-23T12:00:00Z")));
        return row;
    }
}
