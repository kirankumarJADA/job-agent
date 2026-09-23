package com.personal.jobagent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.UuidV7;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST /benchmarks/runs, GET /benchmarks/runs[/{id}], POST
 * /benchmarks/runs/{id}/promote per docs/contracts/api.md.
 */
@RestController
@RequestMapping("/api/v1/benchmarks/runs")
public class BenchmarkController {

    private final BenchmarkRunner benchmarkRunner;
    private final PromotionService promotionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BenchmarkController(BenchmarkRunner benchmarkRunner, PromotionService promotionService,
                                JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.benchmarkRunner = benchmarkRunner;
        this.promotionService = promotionService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // model_benchmark_runs.config and benchmark_results.metrics are jsonb. Reading them through
    // queryForList handed the driver's PGobject to Jackson, which rendered it as
    // {"type":"jsonb","value":"..."} instead of the real object — so both are cast to text here
    // and parsed back into JSON at the JDBC boundary.
    private static final String RUN_COLUMNS =
            "id, suite, task_type, config::text as config, status, started_at, finished_at, git_sha, notes";

    private RowMapper<Map<String, Object>> runRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id"));
            row.put("suite", rs.getString("suite"));
            row.put("task_type", rs.getString("task_type"));
            row.put("config", JdbcConversions.readJson(rs, "config", objectMapper));
            row.put("status", rs.getString("status"));
            row.put("started_at", rs.getObject("started_at"));
            row.put("finished_at", rs.getObject("finished_at"));
            row.put("git_sha", rs.getString("git_sha"));
            row.put("notes", rs.getString("notes"));
            return row;
        };
    }

    private RowMapper<Map<String, Object>> resultRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id"));
            row.put("run_id", rs.getObject("run_id"));
            row.put("model_id", rs.getObject("model_id"));
            row.put("case_index", rs.getObject("case_index"));
            row.put("passed", rs.getObject("passed"));
            row.put("score", rs.getBigDecimal("score"));
            row.put("metrics", JdbcConversions.readJson(rs, "metrics", objectMapper));
            return row;
        };
    }

    public record StartRunRequest(String suite, List<String> modelKeys) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> startRun(@RequestBody StartRunRequest request) {
        UUID runId = UuidV7.generate();
        String taskType = suiteTaskType(request.suite());

        jdbcTemplate.update("""
                        insert into model_benchmark_runs (id, suite, task_type, config, status, started_at)
                        values (?, ?, ?, ?::jsonb, 'RUNNING', now())
                        """,
                runId, request.suite(), taskType, "{\"modelKeys\":" + toJsonArray(request.modelKeys()) + "}");

        benchmarkRunner.runAsync(runId, request.suite(), request.modelKeys());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", runId, "status", "RUNNING"));
    }

    @GetMapping
    public List<Map<String, Object>> listRuns() {
        return jdbcTemplate.query("select " + RUN_COLUMNS + " from model_benchmark_runs order by started_at desc",
                runRowMapper());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getRun(@PathVariable UUID id, HttpServletRequest httpRequest) {
        List<Map<String, Object>> runs = jdbcTemplate.query(
                "select " + RUN_COLUMNS + " from model_benchmark_runs where id = ?", runRowMapper(), id);
        if (runs.isEmpty()) {
            ApiError error = ApiError.of(404, "Not found", "No benchmark run with that id",
                    httpRequest.getRequestURI(), String.valueOf(org.slf4j.MDC.get("correlation_id")));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
        List<Map<String, Object>> results = jdbcTemplate.query(
                "select id, run_id, model_id, case_index, passed, score, metrics::text as metrics "
                        + "from benchmark_results where run_id = ? order by case_index", resultRowMapper(), id);
        return ResponseEntity.ok(Map.of("run", runs.get(0), "results", results));
    }

    @PostMapping("/{id}/promote")
    public ResponseEntity<?> promote(@PathVariable UUID id, HttpServletRequest httpRequest) {
        try {
            return ResponseEntity.ok(promotionService.promote(id));
        } catch (PromotionService.PromotionDeniedException e) {
            ApiError error = ApiError.of(409, "Promotion criteria not met", e.getMessage(),
                    httpRequest.getRequestURI(), String.valueOf(org.slf4j.MDC.get("correlation_id")));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }
    }

    private String suiteTaskType(String suiteRef) {
        // suite naming convention is "<dataset_folder>@<version>", and the
        // dataset folder names (job_classification, sponsorship_analysis,
        // skill_matching, email_classification) map directly to TaskType
        // names modulo casing.
        String name = suiteRef.split("@")[0];
        return name.toUpperCase();
    }

    private String toJsonArray(List<String> values) {
        return "[" + values.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
    }
}
