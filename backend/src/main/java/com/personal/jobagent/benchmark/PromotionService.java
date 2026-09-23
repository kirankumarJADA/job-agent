package com.personal.jobagent.benchmark;

import com.personal.jobagent.common.JdbcConversions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHASE 1 SIMPLIFICATION of architecture §C6's promotion gate. The full
 * gate requires "two consecutive runs within δ agreement" — tracking
 * agreement across runs needs run-history comparison logic not built here
 * given Phase 1's scope and the fact that with only SimulatedProvider
 * reliably available (no real NIM/Gemini keys during this build pass),
 * there's no real multi-provider quality signal to compare yet anyway.
 * This implements the single-run portion of the gate: minimum case count
 * and a quality floor. Promoting past this simplified gate sets
 * basis='BENCHMARK'; the full two-run consistency check is a documented
 * gap to close once real providers exist to make it meaningful.
 */
@Service
public class PromotionService {

    private static final int MIN_CASES = 10;
    private static final double QUALITY_FLOOR = 0.6;

    private final JdbcTemplate jdbcTemplate;

    public PromotionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final class PromotionDeniedException extends RuntimeException {
        public PromotionDeniedException(String message) {
            super(message);
        }
    }

    public Map<String, Object> promote(UUID runId) {
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "select suite, task_type, status from model_benchmark_runs where id = ?", runId);
        if (!"COMPLETED".equals(run.get("status"))) {
            throw new PromotionDeniedException("Run is not COMPLETED (status=" + run.get("status") + ")");
        }

        List<Map<String, Object>> byModel = jdbcTemplate.queryForList("""
                select model_id, count(*) as case_count, avg(score) as mean_score
                from benchmark_results where run_id = ?
                group by model_id
                order by mean_score desc
                """, runId);

        if (byModel.isEmpty()) {
            throw new PromotionDeniedException("Run has no results to promote from");
        }

        Map<String, Object> best = byModel.get(0);
        long caseCount = ((Number) best.get("case_count")).longValue();
        double meanScore = ((Number) best.get("mean_score")).doubleValue();

        if (caseCount < MIN_CASES) {
            throw new PromotionDeniedException(
                    "Best model only has " + caseCount + " cases, minimum is " + MIN_CASES);
        }
        if (meanScore < QUALITY_FLOOR) {
            throw new PromotionDeniedException(
                    "Best model's mean score " + meanScore + " is below the quality floor " + QUALITY_FLOOR);
        }

        UUID bestModelId = (UUID) best.get("model_id");
        String taskType = (String) run.get("task_type");

        jdbcTemplate.update("""
                        insert into routing_policies (task_type, primary_model_id, fallback_model_ids, basis, based_on_run_id, rationale, updated_at)
                        values (?, ?, '{}', 'BENCHMARK', ?, ?, now())
                        on conflict (task_type) do update set
                            primary_model_id = excluded.primary_model_id,
                            basis = 'BENCHMARK',
                            based_on_run_id = excluded.based_on_run_id,
                            rationale = excluded.rationale,
                            updated_at = now()
                        """,
                taskType, bestModelId, runId,
                "Promoted from run " + runId + " with mean score " + meanScore + " over " + caseCount + " cases");

        // fallback_model_ids is uuid[]; jsonSafeRow converts the driver's PgArray into a plain
        // List so the promote response can actually be rendered by Jackson.
        return JdbcConversions.jsonSafeRow(
                jdbcTemplate.queryForMap("select * from routing_policies where task_type = ?", taskType));
    }
}
