package com.personal.jobagent.llm;

import com.personal.jobagent.common.JdbcConversions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class LlmAdminController {
    private final JdbcTemplate jdbcTemplate;
    private final NimModelRegistry nimModelRegistry;
    public LlmAdminController(JdbcTemplate jdbcTemplate, NimModelRegistry nimModelRegistry) { this.jdbcTemplate = jdbcTemplate; this.nimModelRegistry = nimModelRegistry; }
    @GetMapping("/models")
    public List<Map<String, Object>> listModels() { return jdbcTemplate.queryForList("select m.id,m.provider_id,m.model_key,m.display_name,m.enabled,m.notes::text as notes from llm_models m order by m.provider_id,m.model_key"); }
    public record EnabledRequest(boolean enabled) {}
    @PutMapping("/models/{id}")
    public Map<String,Object> setModelEnabled(@PathVariable UUID id, @RequestBody EnabledRequest request) {
        jdbcTemplate.update("update llm_models set enabled=? where id=?", request.enabled(), id);
        return jdbcTemplate.queryForMap("select id,provider_id,model_key,enabled from llm_models where id=?", id);
    }
    @PostMapping("/models/nim/refresh")
    public Map<String,Object> refreshNimModels() {
        List<ModelDescriptor> discovered = nimModelRegistry.refresh();
        return Map.of("provider", "nim", "discovered", discovered.size(), "eligibleFreeModels", nimModelRegistry.eligibleModelIds());
    }
    @GetMapping("/models/nim/registry")
    public List<Map<String,Object>> nimRegistry() { return nimModelRegistry.registryRows(); }
    // routing_policies.fallback_model_ids is uuid[]: raw rows would hand pgjdbc's PgArray to
    // Jackson, which bean-serialises it (getResultSet() -> live JDBC ResultSet) and fails the
    // response with a JsonMappingException. jsonSafeRows converts arrays into plain Lists.
    @GetMapping("/routing") public List<Map<String,Object>> listRouting() { return JdbcConversions.jsonSafeRows(jdbcTemplate.queryForList("select * from routing_policies order by task_type")); }
    public record RoutingUpdateRequest(UUID primaryModelId, List<UUID> fallbackModelIds, String rationale) {}
    @PutMapping("/routing/{taskType}")
    public Map<String,Object> updateRouting(@PathVariable TaskType taskType, @RequestBody RoutingUpdateRequest request) {
        UUID[] fallbacks = request.fallbackModelIds() == null ? new UUID[0] : request.fallbackModelIds().toArray(new UUID[0]);
        jdbcTemplate.execute((java.sql.Connection conn) -> { try (var ps=conn.prepareStatement("insert into routing_policies(task_type,primary_model_id,fallback_model_ids,basis,rationale,updated_at) values(?,?,?,'MANUAL',?,now()) on conflict(task_type) do update set primary_model_id=excluded.primary_model_id,fallback_model_ids=excluded.fallback_model_ids,basis='MANUAL',rationale=excluded.rationale,updated_at=now()")) { ps.setString(1,taskType.name()); ps.setObject(2,request.primaryModelId()); ps.setArray(3,conn.createArrayOf("uuid",fallbacks)); ps.setString(4,request.rationale()); return ps.executeUpdate(); } });
        return JdbcConversions.jsonSafeRow(jdbcTemplate.queryForMap("select * from routing_policies where task_type=?", taskType.name()));
    }
    @GetMapping("/llm-calls/stats")
    public List<Map<String,Object>> llmCallStats(@RequestParam(required=false) TaskType taskType) {
        if (taskType != null) return jdbcTemplate.queryForList("select provider_id,count(*) as call_count,avg(latency_ms) as avg_latency_ms,sum(case when ok then 1 else 0 end)::float/count(*) as success_rate from llm_calls where task_type=? group by provider_id", taskType.name());
        return jdbcTemplate.queryForList("select task_type,provider_id,count(*) as call_count,avg(latency_ms) as avg_latency_ms,sum(case when ok then 1 else 0 end)::float/count(*) as success_rate from llm_calls group by task_type,provider_id");
    }
}
