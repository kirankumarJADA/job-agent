package com.personal.jobagent.benchmark;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

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

    public BenchmarkController(BenchmarkRunner benchmarkRunner, PromotionService promotionService,
                                JdbcTemplate jdbcTemplate) {
        this.benchmarkRunner = benchmarkRunner;
        this.promotionService = promotionService;
        this.jdbcTemplate = jdbcTemplate;
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
        return jdbcTemplate.queryForList("select * from model_benchmark_runs order by started_at desc");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getRun(@PathVariable UUID id, HttpServletRequest httpRequest) {
        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                "select * from model_benchmark_runs where id = ?", id);
        if (runs.isEmpty()) {
            ApiError error = ApiError.of(404, "Not found", "No benchmark run with that id",
                    httpRequest.getRequestURI(), String.valueOf(org.slf4j.MDC.get("correlation_id")));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "select * from benchmark_results where run_id = ? order by case_index", id);
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
