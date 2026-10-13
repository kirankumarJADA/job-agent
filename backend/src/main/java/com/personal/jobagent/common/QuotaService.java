package com.personal.jobagent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unified quota and cost engine for LLM calls and discovery operations.
 *
 * <p>Tracks per-profile daily usage against configurable limits.  Each
 * call to {@link #recordAndCheck} atomically increments the counter and
 * returns whether the operation is within quota.  Cost estimation uses
 * the pricing stored in {@code llm_models}.
 *
 * <p>Quotas are enforced at the profile level (owner isolation), so one
 * user's heavy use cannot starve another.  The limits are soft — a call
 * that pushes usage slightly over the limit still succeeds, but subsequent
 * calls are blocked until the next period rolls over.
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    /** Result of a quota check. */
    public record QuotaCheckResult(boolean allowed, double used, double limit, String unit) {
        public double remaining() { return Math.max(0, limit - used); }
    }

    /** Tracks the estimated cost of an LLM call. */
    public record CostEstimate(BigDecimal inputCost, BigDecimal outputCost, BigDecimal totalCost) {
        public static final CostEstimate ZERO = new CostEstimate(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private final JdbcTemplate db;
    private final double llmDailyLimit;
    private final double discoveryDailyLimit;
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public QuotaService(JdbcTemplate db,
                        @Value("${app.quota.llm-daily-calls:500}") double llmDailyLimit,
                        @Value("${app.quota.discovery-daily-calls:200}") double discoveryDailyLimit,
                        @Value("${app.quota.enabled:true}") boolean enabled) {
        this.db = db;
        this.llmDailyLimit = llmDailyLimit;
        this.discoveryDailyLimit = discoveryDailyLimit;
        this.enabled = enabled;
    }

    /** Test seam. */
    QuotaService(JdbcTemplate db, double llmDailyLimit, double discoveryDailyLimit) {
        this(db, llmDailyLimit, discoveryDailyLimit, true);
    }

    /**
     * Records one unit of usage and returns whether the operation is allowed.
     *
     * <p>The first call that exceeds the limit still succeeds (soft limit).
     * Subsequent calls are blocked until the period rolls over.
     */
    public QuotaCheckResult recordAndCheck(UUID profileId, String quotaKey, double amount) {
        if (!enabled) return new QuotaCheckResult(true, 0, Double.MAX_VALUE, "calls");

        double limit = limitFor(quotaKey);
        LocalDate today = LocalDate.now();

        // Upsert the counter
        db.update("""
                INSERT INTO usage_quotas (profile_id, quota_key, period_start, used, quota_limit, unit, updated_at)
                VALUES (?, ?, ?, ?, ?, 'calls', now())
                ON CONFLICT (profile_id, quota_key, period_start)
                DO UPDATE SET used = usage_quotas.used + EXCLUDED.used, updated_at = now()
                """, profileId, quotaKey, today, amount, limit);

        // Read back current usage
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT used, quota_limit, unit FROM usage_quotas WHERE profile_id = ? AND quota_key = ? AND period_start = ?",
                profileId, quotaKey, today);

        if (rows.isEmpty()) {
            return new QuotaCheckResult(true, amount, limit, "calls");
        }

        double used = ((Number) rows.get(0).get("used")).doubleValue();
        double storedLimit = ((Number) rows.get(0).get("quota_limit")).doubleValue();
        String unit = (String) rows.get(0).get("unit");

        // Soft limit: the call that pushed us over still succeeds
        boolean allowed = (used - amount) <= storedLimit;
        return new QuotaCheckResult(allowed, used, storedLimit, unit);
    }

    /**
     * Checks quota without recording usage.  Use this for pre-flight checks.
     */
    public QuotaCheckResult check(UUID profileId, String quotaKey) {
        if (!enabled) return new QuotaCheckResult(true, 0, Double.MAX_VALUE, "calls");

        double limit = limitFor(quotaKey);
        LocalDate today = LocalDate.now();

        List<Map<String, Object>> rows = db.queryForList(
                "SELECT used, quota_limit, unit FROM usage_quotas WHERE profile_id = ? AND quota_key = ? AND period_start = ?",
                profileId, quotaKey, today);

        if (rows.isEmpty()) {
            return new QuotaCheckResult(true, 0, limit, "calls");
        }

        double used = ((Number) rows.get(0).get("used")).doubleValue();
        double storedLimit = ((Number) rows.get(0).get("quota_limit")).doubleValue();
        String unit = (String) rows.get(0).get("unit");

        return new QuotaCheckResult(used < storedLimit, used, storedLimit, unit);
    }

    /**
     * Estimates the cost of an LLM call based on token counts and model pricing.
     *
     * @param modelKey  the model key (e.g. "llama-3.3-70b-versatile")
     * @param inputTokens  number of input tokens
     * @param outputTokens  number of output tokens
     * @return cost estimate, or ZERO if pricing is unavailable
     */
    public CostEstimate estimateCost(String modelKey, int inputTokens, int outputTokens) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT input_cost_per_mtok, output_cost_per_mtok FROM llm_models WHERE model_key = ? AND enabled = true",
                modelKey);

        if (rows.isEmpty()) return CostEstimate.ZERO;

        Object inCostObj = rows.get(0).get("input_cost_per_mtok");
        Object outCostObj = rows.get(0).get("output_cost_per_mtok");
        if (inCostObj == null || outCostObj == null) return CostEstimate.ZERO;

        BigDecimal inCostPerMTok = toBigDecimal(inCostObj);
        BigDecimal outCostPerMTok = toBigDecimal(outCostObj);
        BigDecimal million = BigDecimal.valueOf(1_000_000);

        BigDecimal inputCost = inCostPerMTok.multiply(BigDecimal.valueOf(inputTokens)).divide(million, 8, RoundingMode.HALF_UP);
        BigDecimal outputCost = outCostPerMTok.multiply(BigDecimal.valueOf(outputTokens)).divide(million, 8, RoundingMode.HALF_UP);

        return new CostEstimate(inputCost, outputCost, inputCost.add(outputCost));
    }

    /**
     * Returns daily usage summary for a profile across all quota keys.
     */
    public List<Map<String, Object>> dailySummary(UUID profileId) {
        return db.queryForList(
                "SELECT quota_key, used, quota_limit, unit FROM usage_quotas WHERE profile_id = ? AND period_start = ?",
                profileId, LocalDate.now());
    }

    private double limitFor(String quotaKey) {
        return switch (quotaKey) {
            case "llm_daily" -> llmDailyLimit;
            case "discovery_daily" -> discoveryDailyLimit;
            default -> 1000; // generous default
        };
    }

    private static BigDecimal toBigDecimal(Object obj) {
        if (obj instanceof BigDecimal bd) return bd;
        if (obj instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}
