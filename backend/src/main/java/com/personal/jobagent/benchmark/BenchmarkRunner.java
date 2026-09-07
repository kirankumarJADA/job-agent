package com.personal.jobagent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.llm.LlmCompletionRequest;
import com.personal.jobagent.llm.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs cases through the REAL router seam (ModelRouter.execute), per
 * architecture §C6's requirement that benchmark runs "fan out case×model
 * calls through the real router seam ... so repair loops and parsing count
 * honestly" — this does NOT call providers directly, specifically so a
 * benchmark result reflects what production calls would actually
 * experience (fallback behavior, ledger writes, etc).
 *
 * @Async: POST /benchmarks/runs returns 202 immediately (per the API
 * contract) while this runs in the background. Requires @EnableAsync,
 * added to JobAgentApplication alongside @EnableScheduling.
 */
@Component
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final SuiteLoader suiteLoader;
    private final ModelRouter modelRouter;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BenchmarkRunner(SuiteLoader suiteLoader, ModelRouter modelRouter,
                            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.suiteLoader = suiteLoader;
        this.modelRouter = modelRouter;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Async
    public void runAsync(UUID runId, String suiteRef, List<String> modelKeys) {
        try {
            List<BenchmarkCase> cases = suiteLoader.load(suiteRef);
            int caseIndex = 0;
            for (BenchmarkCase testCase : cases) {
                for (String modelKey : modelKeys) {
                    runOneCase(runId, testCase, modelKey, caseIndex);
                }
                caseIndex++;
            }
            jdbcTemplate.update(
                    "update model_benchmark_runs set status = 'COMPLETED', finished_at = now() where id = ?", runId);
        } catch (Exception e) {
            log.error("Benchmark run {} failed: {}", runId, e.getMessage(), e);
            jdbcTemplate.update(
                    "update model_benchmark_runs set status = 'FAILED', finished_at = now(), notes = ? where id = ?",
                    e.getMessage(), runId);
        }
    }

    private void runOneCase(UUID runId, BenchmarkCase testCase, String modelKey, int caseIndex) {
        UUID correlationId = UuidV7.generate();
        Grader grader = Grader.forName(testCase.grader());

        UUID modelId = jdbcTemplate.query(
                        "select id from llm_models where model_key = ?",
                        (rs, n) -> (UUID) rs.getObject("id"), modelKey)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model_key: " + modelKey));

        long start = System.currentTimeMillis();
        boolean jsonValid = true;
        String actualOutput;
        try {
            LlmCompletionRequest request = LlmCompletionRequest.simple(
                    modelKey, testCase.input(), correlationId);
            ModelRouter.ExecutionResult result = modelRouter.execute(
                    testCase.taskType(), request, Duration.ofSeconds(30));
            actualOutput = result.completion().text();
        } catch (Exception e) {
            actualOutput = null;
            jsonValid = false;
        }
        long latency = System.currentTimeMillis() - start;

        double score = actualOutput != null ? grader.grade(actualOutput, testCase.expected()) : 0.0;
        boolean passed = score >= 1.0;

        Map<String, Object> metrics = Map.of(
                "latency_ms", latency,
                "json_valid", jsonValid,
                "retries", 0
        );

        jdbcTemplate.update("""
                        insert into benchmark_results (id, run_id, model_id, case_index, passed, score, metrics)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb)
                        """,
                UuidV7.generate(), runId, modelId, caseIndex, passed, score, toJson(metrics));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
