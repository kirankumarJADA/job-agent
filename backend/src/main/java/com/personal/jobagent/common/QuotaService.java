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

    /**
     * Result of an atomic {@link #tryReserve} attempt. {@code used}/{@code limit}
     * reflect the row's state immediately after the attempt — after a
     * successful reservation, {@code used} already includes the amount just
     * reserved; after a refused one, it is the current usage the caller was
     * refused against, for an honest "N/M" message.
     *
     * <p>{@code periodStart} is the exact day the reservation was written
     * against ({@code LocalDate.now()} at the moment {@link #tryReserve} ran)
     * — a caller that later needs to {@link #release} this reservation MUST
     * pass this value back, not re-derive "today" itself. Reservation and
     * release can straddle real wall-clock time (document generation, a slow
     * database, an outbox redelivery): if {@code release} were to compute its
     * own {@code LocalDate.now()} instead, a reservation made at 23:59 that is
     * released after midnight would silently update the WRONG day's row (one
     * that was never actually reserved against), matching zero rows and
     * leaking the original reservation forever — a real, previously-confirmed
     * defect this field exists specifically to close.
     */
    public record ReservationResult(boolean reserved, double used, double limit, LocalDate periodStart) {
        public double remaining() { return Math.max(0, limit - used); }
    }

    /** Tracks the estimated cost of an LLM call. */
    public record CostEstimate(BigDecimal inputCost, BigDecimal outputCost, BigDecimal totalCost) {
        public static final CostEstimate ZERO = new CostEstimate(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private final JdbcTemplate db;
    private final double llmDailyLimit;
    private final double discoveryDailyLimit;
    private final double applicationDailyLimit;
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public QuotaService(JdbcTemplate db,
                        @Value("${app.quota.llm-daily-calls:500}") double llmDailyLimit,
                        @Value("${app.quota.discovery-daily-calls:200}") double discoveryDailyLimit,
                        @Value("${app.quota.application-daily:25}") double applicationDailyLimit,
                        @Value("${app.quota.enabled:true}") boolean enabled) {
        this.db = db;
        this.llmDailyLimit = llmDailyLimit;
        this.discoveryDailyLimit = discoveryDailyLimit;
        this.applicationDailyLimit = applicationDailyLimit;
        this.enabled = enabled;
    }

    /** Test seam. */
    QuotaService(JdbcTemplate db, double llmDailyLimit, double discoveryDailyLimit, double applicationDailyLimit) {
        this(db, llmDailyLimit, discoveryDailyLimit, applicationDailyLimit, true);
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
     * Atomically reserves {@code amount} of usage against the caller's daily
     * quota — the concurrency-safe replacement for the
     * check-then-later-{@link #recordAndCheck} pattern, which has a
     * check-then-act race: two concurrent callers can both observe
     * {@code used < limit} before either has recorded its usage, and both
     * proceed, overspending the limit.
     *
     * <p>Correctness here comes from the second statement alone: an
     * {@code UPDATE ... WHERE used + amount <= quota_limit}, atomic and
     * self-serializing at the database row level (PostgreSQL holds the row
     * lock for the duration of the UPDATE, so a second concurrent caller for
     * the same {@code (profile_id, quota_key, period_start)} row waits for
     * the first to commit and then re-evaluates the WHERE clause against the
     * now-updated {@code used} — it can never read the pre-increment value).
     * The first statement only ensures the row exists before that UPDATE
     * runs (itself safe under concurrent first-use via {@code ON CONFLICT ...
     * DO NOTHING} — whichever caller's INSERT loses the race simply finds the
     * row already there for its own subsequent UPDATE).
     *
     * <p>A caller that reserves and then does NOT end up spending it (the
     * thing it reserved for turned out to be a replay, a duplicate, or
     * failed outright) must call {@link #release} with the same amount —
     * reservation and release are the explicit, symmetric halves of this
     * quota's consumption semantics; nothing here guesses at that on the
     * caller's behalf.
     */
    public ReservationResult tryReserve(UUID profileId, String quotaKey, double amount) {
        LocalDate today = LocalDate.now();
        if (!enabled) return new ReservationResult(true, 0, Double.MAX_VALUE, today);

        double limit = limitFor(quotaKey);

        db.update("""
                INSERT INTO usage_quotas (profile_id, quota_key, period_start, used, quota_limit, unit, updated_at)
                VALUES (?, ?, ?, 0, ?, 'calls', now())
                ON CONFLICT (profile_id, quota_key, period_start) DO NOTHING
                """, profileId, quotaKey, today, limit);

        List<Map<String, Object>> reserved = db.queryForList("""
                UPDATE usage_quotas SET used = used + ?, updated_at = now()
                WHERE profile_id = ? AND quota_key = ? AND period_start = ? AND used + ? <= quota_limit
                RETURNING used, quota_limit
                """, amount, profileId, quotaKey, today, amount);

        if (!reserved.isEmpty()) {
            return new ReservationResult(true, toDouble(reserved.get(0).get("used")), toDouble(reserved.get(0).get("quota_limit")), today);
        }

        // The conditional UPDATE matched no row: quota is exhausted. Read
        // the row's actual current state for an honest "used/limit" message
        // rather than reporting the pre-attempt numbers.
        List<Map<String, Object>> current = db.queryForList("""
                SELECT used, quota_limit FROM usage_quotas
                WHERE profile_id = ? AND quota_key = ? AND period_start = ?
                """, profileId, quotaKey, today);
        if (current.isEmpty()) {
            // Could only happen if the row was deleted between the INSERT
            // and the UPDATE above — never by any code path in this class.
            // Fail closed (refused) rather than silently granting more than
            // the configured limit.
            log.warn("Quota row for profile {} key {} vanished mid-reservation; refusing", profileId, quotaKey);
            return new ReservationResult(false, amount, limit, today);
        }
        return new ReservationResult(false, toDouble(current.get(0).get("used")), toDouble(current.get(0).get("quota_limit")), today);
    }

    /**
     * Releases a previously {@link #tryReserve}d amount that was not
     * actually spent (replay, duplicate, or a failed creation) — the other
     * explicit half of reserve/release. Floors at zero so a double-release
     * (a bug, never expected in normal operation) can never push a day's
     * usage negative and silently grant more room than was ever really
     * reserved.
     *
     * <p>{@code periodStart} MUST be the exact {@link ReservationResult#periodStart()}
     * the original {@link #tryReserve} call returned — never re-derived as
     * "today" here. This method has no other way to know which day's row the
     * reservation it is reversing actually lives on; guessing "today" is
     * exactly the day-boundary bug this signature exists to make
     * structurally impossible to reintroduce (there used to be a
     * same-named, no-`periodStart` overload that computed {@code
     * LocalDate.now()} internally — removed entirely, not merely
     * deprecated, so no caller can silently fall back to the unsafe
     * behaviour).
     */
    public void release(UUID profileId, String quotaKey, double amount, LocalDate periodStart) {
        if (!enabled) return;
        db.update("""
                UPDATE usage_quotas SET used = GREATEST(0, used - ?), updated_at = now()
                WHERE profile_id = ? AND quota_key = ? AND period_start = ?
                """, amount, profileId, quotaKey, periodStart);
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
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
            // Robin x Tsenta continuation: the Auto Apply daily cap. Previously
            // fell through to the generous 1000 default below — syntactically
            // checked (ApplicationDecisionService#decide) but never a real limit.
            case "application_daily" -> applicationDailyLimit;
            default -> 1000; // generous default
        };
    }

    private static BigDecimal toBigDecimal(Object obj) {
        if (obj instanceof BigDecimal bd) return bd;
        if (obj instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}
