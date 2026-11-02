package com.personal.jobagent.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 (Robin x Tsenta continuation): candidate-scoped Auto Apply BATCH
 * settings.
 *
 * <p>Deliberately separate from {@link ApplicationDecisionService}'s
 * MANUAL/ASSISTED/CONTROLLED_AUTO modes and {@code user_approval_rules}: that
 * engine already runs unconditionally, in the background, on every newly
 * matched job. This settings row instead governs an explicit, bounded,
 * user-triggered sweep over the candidate's EXISTING matched-job backlog
 * (see {@link AutoApplyBatchService}), with its own opt-in and its own
 * candidate-configured daily cap — so enabling it can never, by itself, start
 * any automatic activity.
 *
 * <p>Every field is validated here, so every caller (the API, tests, any
 * future caller) gets the same guarantees and the same safe defaults when no
 * row exists yet.
 */
@Service
public class AutoApplySettingsService {

    /** Safe, conservative defaults for a profile that has never configured this. */
    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_MIN_SCORE = 70;
    public static final int DEFAULT_DAILY_CAP = 10;
    public static final int DEFAULT_MAX_BATCH_SIZE = 25;

    static final int MIN_SCORE_FLOOR = 1;
    static final int MIN_SCORE_CEILING = 100;
    static final int DAILY_CAP_FLOOR = 1;
    static final int DAILY_CAP_CEILING = 50;
    static final int MAX_BATCH_SIZE_FLOOR = 1;
    static final int MAX_BATCH_SIZE_CEILING = 50;

    private static final java.util.Set<String> VALID_REMOTE_TYPES =
            java.util.Set.of("REMOTE", "HYBRID", "ONSITE", "UNKNOWN");

    private final JdbcTemplate db;

    public AutoApplySettingsService(JdbcTemplate db) {
        this.db = db;
    }

    public record Settings(boolean enabled, int minScore, int dailyCap, int maxBatchSize,
                           String remoteType, String location, String employmentType,
                           BigDecimal salaryMin, boolean configured) {

        static Settings defaults() {
            return new Settings(DEFAULT_ENABLED, DEFAULT_MIN_SCORE, DEFAULT_DAILY_CAP, DEFAULT_MAX_BATCH_SIZE,
                    null, null, null, null, false);
        }
    }

    /** The owner's settings, or the documented defaults when nothing is saved yet. */
    public Settings get(UUID profileId) {
        List<Map<String, Object>> rows = db.queryForList("""
                select enabled, min_score, daily_cap, max_batch_size,
                       remote_type, location, employment_type, salary_min
                from auto_apply_settings where profile_id = ?
                """, profileId);
        if (rows.isEmpty()) return Settings.defaults();
        Map<String, Object> row = rows.get(0);
        return new Settings(
                Boolean.TRUE.equals(row.get("enabled")),
                ((Number) row.get("min_score")).intValue(),
                ((Number) row.get("daily_cap")).intValue(),
                ((Number) row.get("max_batch_size")).intValue(),
                (String) row.get("remote_type"),
                (String) row.get("location"),
                (String) row.get("employment_type"),
                row.get("salary_min") == null ? null : new BigDecimal(row.get("salary_min").toString()),
                true);
    }

    /**
     * Validates and persists (upsert on profile_id). Unspecified (null)
     * fields fall back to the existing saved value, or the documented default
     * when nothing was ever saved — so a partial update never silently resets
     * unrelated settings.
     *
     * @throws IllegalArgumentException on any out-of-bounds value; the caller
     *         is expected to map this to a 400 response
     */
    public Settings save(UUID profileId, Boolean enabled, Integer minScore, Integer dailyCap,
                         Integer maxBatchSize, String remoteType, String location,
                         String employmentType, BigDecimal salaryMin, boolean clearFilters) {
        Settings existing = get(profileId);

        boolean nextEnabled = enabled != null ? enabled : existing.enabled();
        int nextMinScore = minScore != null ? minScore : existing.minScore();
        int nextDailyCap = dailyCap != null ? dailyCap : existing.dailyCap();
        int nextMaxBatchSize = maxBatchSize != null ? maxBatchSize : existing.maxBatchSize();
        String nextRemoteType = clearFilters ? null : (remoteType != null ? remoteType : existing.remoteType());
        String nextLocation = clearFilters ? null : (location != null ? location : existing.location());
        String nextEmploymentType = clearFilters ? null : (employmentType != null ? employmentType : existing.employmentType());
        BigDecimal nextSalaryMin = clearFilters ? null : (salaryMin != null ? salaryMin : existing.salaryMin());

        if (nextMinScore < MIN_SCORE_FLOOR || nextMinScore > MIN_SCORE_CEILING) {
            throw new IllegalArgumentException("min_score must be between " + MIN_SCORE_FLOOR + " and " + MIN_SCORE_CEILING);
        }
        if (nextDailyCap < DAILY_CAP_FLOOR || nextDailyCap > DAILY_CAP_CEILING) {
            throw new IllegalArgumentException("daily_cap must be between " + DAILY_CAP_FLOOR + " and " + DAILY_CAP_CEILING);
        }
        if (nextMaxBatchSize < MAX_BATCH_SIZE_FLOOR || nextMaxBatchSize > MAX_BATCH_SIZE_CEILING) {
            throw new IllegalArgumentException("max_batch_size must be between " + MAX_BATCH_SIZE_FLOOR + " and " + MAX_BATCH_SIZE_CEILING);
        }
        if (nextRemoteType != null && !VALID_REMOTE_TYPES.contains(nextRemoteType)) {
            throw new IllegalArgumentException("remote_type must be one of " + VALID_REMOTE_TYPES);
        }
        if (nextSalaryMin != null && nextSalaryMin.signum() < 0) {
            throw new IllegalArgumentException("salary_min must not be negative");
        }
        if (nextLocation != null && nextLocation.length() > 200) {
            throw new IllegalArgumentException("location must be 200 characters or fewer");
        }
        if (nextEmploymentType != null && nextEmploymentType.length() > 200) {
            throw new IllegalArgumentException("employment_type must be 200 characters or fewer");
        }

        db.update("""
                insert into auto_apply_settings
                    (profile_id, enabled, min_score, daily_cap, max_batch_size,
                     remote_type, location, employment_type, salary_min, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (profile_id) do update set
                    enabled = excluded.enabled,
                    min_score = excluded.min_score,
                    daily_cap = excluded.daily_cap,
                    max_batch_size = excluded.max_batch_size,
                    remote_type = excluded.remote_type,
                    location = excluded.location,
                    employment_type = excluded.employment_type,
                    salary_min = excluded.salary_min,
                    updated_at = now()
                """, profileId, nextEnabled, nextMinScore, nextDailyCap, nextMaxBatchSize,
                nextRemoteType, nextLocation, nextEmploymentType, nextSalaryMin);

        return new Settings(nextEnabled, nextMinScore, nextDailyCap, nextMaxBatchSize,
                nextRemoteType, nextLocation, nextEmploymentType, nextSalaryMin, true);
    }
}
