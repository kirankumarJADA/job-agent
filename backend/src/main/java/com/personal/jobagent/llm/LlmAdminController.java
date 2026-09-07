package com.personal.jobagent.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GET /models, PUT /models/{id}, GET /routing, PUT /routing/{taskType},
 * GET /llm-calls/stats per docs/contracts/api.md. Plain JDBC, consistent
 * with the rest of Phase 1's persistence approach.
 */
@RestController
@RequestMapping("/api/v1")
public class LlmAdminController {

    private final JdbcTemplate jdbcTemplate;

    public LlmAdminController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/models")
    public List<Map<String, Object>> listModels() {
        return jdbcTemplate.queryForList("""
                select m.id, m.provider_id, m.model_key, m.display_name, m.enabled, m.notes::text as notes
                from llm_models m order by m.provider_id, m.model_key
                """);
    }

    public record EnabledRequest(boolean enabled) {
    }

    @PutMapping("/models/{id}")
    public Map<String, Object> setModelEnabled(@PathVariable UUID id, @RequestBody EnabledRequest request) {
        jdbcTemplate.update("update llm_models set enabled = ? where id = ?", request.enabled(), id);
        return jdbcTemplate.queryForMap(
                "select id, provider_id, model_key, enabled from llm_models where id = ?", id);
    }

    @GetMapping("/routing")
    public List<Map<String, Object>> listRouting() {
        return jdbcTemplate.queryForList("select * from routing_policies order by task_type");
    }

    public record RoutingUpdateRequest(UUID primaryModelId, List<UUID> fallbackModelIds, String rationale) {
    }

    @PutMapping("/routing/{taskType}")
    public Map<String, Object> updateRouting(@PathVariable TaskType taskType, @RequestBody RoutingUpdateRequest request) {
        UUID[] fallbackArray = request.fallbackModelIds() != null
                ? request.fallbackModelIds().toArray(new UUID[0]) : new UUID[0];

        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (var ps = conn.prepareStatement("""
                    insert into routing_policies (task_type, primary_model_id, fallback_model_ids, basis, rationale, updated_at)
                    values (?, ?, ?, 'MANUAL', ?, now())
                    on conflict (task_type) do update set
                        primary_model_id = excluded.primary_model_id,
                        fallback_model_ids = excluded.fallback_model_ids,
                        basis = 'MANUAL',
                        rationale = excluded.rationale,
                        updated_at = now()
                    """)) {
                ps.setString(1, taskType.name());
                ps.setObject(2, request.primaryModelId());
                ps.setArray(3, conn.createArrayOf("uuid", fallbackArray));
                ps.setString(4, request.rationale());
                return ps.executeUpdate();
            }
        });

        return jdbcTemplate.queryForMap("select * from routing_policies where task_type = ?", taskType.name());
    }

    @GetMapping("/llm-calls/stats")
    public List<Map<String, Object>> llmCallStats(@RequestParam(required = false) TaskType taskType) {
        if (taskType != null) {
            return jdbcTemplate.queryForList("""
                    select provider_id, count(*) as call_count,
                           avg(latency_ms) as avg_latency_ms,
                           sum(case when ok then 1 else 0 end)::float / count(*) as success_rate
                    from llm_calls where task_type = ?
                    group by provider_id
                    """, taskType.name());
        }
        return jdbcTemplate.queryForList("""
                select task_type, provider_id, count(*) as call_count,
                       avg(latency_ms) as avg_latency_ms,
                       sum(case when ok then 1 else 0 end)::float / count(*) as success_rate
                from llm_calls
                group by task_type, provider_id
                """);
    }
}
