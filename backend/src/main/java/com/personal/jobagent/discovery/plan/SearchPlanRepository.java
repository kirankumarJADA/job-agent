package com.personal.jobagent.discovery.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SearchPlanRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SearchPlanRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private RowMapper<SearchPlanRecord> rowMapper() {
        return (rs, rowNum) -> {
            Timestamp lastRun = rs.getTimestamp("last_run_at");
            Timestamp leaseExpires = rs.getTimestamp("execution_lease_expires_at");
            Timestamp created = rs.getTimestamp("created_at");
            Timestamp updated = rs.getTimestamp("updated_at");

            return new SearchPlanRecord(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("profile_id"),
                    rs.getString("name"),
                    JdbcConversions.readStringArray(rs, "target_titles"),
                    JdbcConversions.readJsonMap(rs, "query_facets", objectMapper),
                    JdbcConversions.readStringArray(rs, "target_sources"),
                    rs.getString("search_frequency"),
                    rs.getInt("max_results_per_run"),
                    rs.getBoolean("active"),
                    lastRun != null ? lastRun.toInstant() : null,
                    rs.getString("last_execution_status"),
                    rs.getString("last_execution_error"),
                    rs.getInt("last_execution_results"),
                    (UUID) rs.getObject("execution_owner_token"),
                    leaseExpires != null ? leaseExpires.toInstant() : null,
                    created != null ? created.toInstant() : Instant.now(),
                    updated != null ? updated.toInstant() : Instant.now()
            );
        };
    }

    public List<SearchPlanRecord> findByProfileId(UUID profileId) {
        return jdbcTemplate.query(
                "SELECT * FROM search_plans WHERE profile_id = ? ORDER BY created_at DESC",
                rowMapper(), profileId);
    }

    public Optional<SearchPlanRecord> findByIdAndProfileId(UUID id, UUID profileId) {
        List<SearchPlanRecord> results = jdbcTemplate.query(
                "SELECT * FROM search_plans WHERE id = ? AND profile_id = ? LIMIT 1",
                rowMapper(), id, profileId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<SearchPlanRecord> findById(UUID id) {
        List<SearchPlanRecord> results = jdbcTemplate.query(
                "SELECT * FROM search_plans WHERE id = ? LIMIT 1",
                rowMapper(), id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<SearchPlanRecord> findActivePlans() {
        return jdbcTemplate.query(
                "SELECT * FROM search_plans WHERE active = true ORDER BY last_run_at ASC NULLS FIRST",
                rowMapper());
    }

    public List<SearchPlanRecord> findDuePlans(Instant now) {
        Timestamp ts = Timestamp.from(now);
        Timestamp hourlyCutoff = Timestamp.from(now.minus(Duration.ofHours(1)));
        Timestamp dailyCutoff = Timestamp.from(now.minus(Duration.ofDays(1)));
        Timestamp weeklyCutoff = Timestamp.from(now.minus(Duration.ofDays(7)));
        return jdbcTemplate.query("""
                SELECT * FROM search_plans
                WHERE active = true
                  AND (
                      last_execution_status IS NULL
                      OR last_execution_status <> 'RUNNING'
                      OR execution_lease_expires_at IS NULL
                      OR execution_lease_expires_at < ?
                  )
                  AND (
                      (search_frequency = 'HOURLY' AND (last_run_at IS NULL OR last_run_at <= ?))
                   OR (search_frequency = 'DAILY' AND (last_run_at IS NULL OR last_run_at <= ?))
                   OR (search_frequency = 'WEEKLY' AND (last_run_at IS NULL OR last_run_at <= ?))
                  )
                ORDER BY last_run_at ASC NULLS FIRST
                """, rowMapper(), ts, hourlyCutoff, dailyCutoff, weeklyCutoff);
    }

    /**
     * Atomically claims an exclusive execution lease on a search plan.
     * Prevents overlapping executions across threads and application cluster instances.
     *
     * @param planId plan identifier
     * @param ownerToken unique worker instance token
     * @param leaseDurationMs maximum duration of the lease before it expires
     * @return true if the lease was claimed successfully; false if another execution holds an active unexpired lease
     */
    public boolean claimExecutionLease(UUID planId, UUID ownerToken, long leaseDurationMs) {
        long duration = leaseDurationMs > 0 ? leaseDurationMs : 600000L; // default 10 minutes
        int updated = jdbcTemplate.update("""
                UPDATE search_plans
                SET last_execution_status = 'RUNNING',
                    execution_owner_token = ?,
                    execution_lease_expires_at = clock_timestamp() + (INTERVAL '1 millisecond' * ?),
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND active = true
                  AND (
                      last_execution_status IS NULL
                      OR last_execution_status <> 'RUNNING'
                      OR execution_lease_expires_at IS NULL
                      OR execution_lease_expires_at < clock_timestamp()
                  )
                """, ownerToken, duration, planId);
        return updated > 0;
    }

    /**
     * Atomically renews an execution lease using its exact execution owner token.
     * Fails if the token is no longer owner or if the lease has already expired.
     *
     * @param planId plan identifier
     * @param ownerToken worker owner token
     * @param leaseDurationMs new lease extension duration from clock_timestamp()
     * @return true if renewed successfully; false if lease expired or ownership lost
     */
    public boolean renewExecutionLease(UUID planId, UUID ownerToken, long leaseDurationMs) {
        if (planId == null || ownerToken == null) {
            return false;
        }
        long duration = leaseDurationMs > 0 ? leaseDurationMs : 60000L;
        int updated = jdbcTemplate.update("""
                UPDATE search_plans
                SET execution_lease_expires_at = clock_timestamp() + (INTERVAL '1 millisecond' * ?),
                    updated_at = clock_timestamp()
                WHERE id = ?
                  AND execution_owner_token = ?
                  AND execution_lease_expires_at IS NOT NULL
                  AND execution_lease_expires_at > clock_timestamp()
                  AND active = true
                  AND last_execution_status = 'RUNNING'
                """, duration, planId, ownerToken);
        return updated > 0;
    }

    /**
     * Atomically verifies lease ownership at the database write boundary.
     * Locks the search_plans row with FOR NO KEY UPDATE to prevent concurrent
     * lease takeover while the write transaction proceeds.
     *
     * <p>If planId or ownerToken is null, this is a non-search-plan operation and returns safely.</p>
     *
     * @param planId plan identifier (nullable)
     * @param ownerToken worker owner token (nullable)
     * @throws ExecutionFencedException if the lease expired, was overwritten, or plan is not running
     */
    public void assertLeaseOwnership(UUID planId, UUID ownerToken) {
        if (planId == null || ownerToken == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT 1 FROM search_plans
                WHERE id = ?
                  AND execution_owner_token = ?
                  AND execution_lease_expires_at IS NOT NULL
                  AND execution_lease_expires_at > clock_timestamp()
                  AND last_execution_status = 'RUNNING'
                FOR NO KEY UPDATE
                """, planId, ownerToken);
        if (rows.isEmpty()) {
            throw new ExecutionFencedException("Execution lease lost or expired for search plan " + planId + " and token " + ownerToken);
        }
    }

    /**
     * Non-locking check to determine if the worker is currently the active lease owner.
     * Suitable for in-memory cooperative pre-flight checks before external I/O.
     *
     * @param planId plan identifier
     * @param ownerToken worker owner token
     * @return true if currently owner and lease is unexpired
     */
    public boolean isLeaseOwner(UUID planId, UUID ownerToken) {
        if (planId == null || ownerToken == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM search_plans
                WHERE id = ?
                  AND execution_owner_token = ?
                  AND execution_lease_expires_at IS NOT NULL
                  AND execution_lease_expires_at > clock_timestamp()
                  AND last_execution_status = 'RUNNING'
                """, Integer.class, planId, ownerToken);
        return count != null && count > 0;
    }

    /**
     * Completes an execution and releases the lease with fencing protection.
     * If the worker was expired and another execution took over, the update affects 0 rows.
     *
     * @param planId plan identifier
     * @param ownerToken worker owner token
     * @param status final execution status (SUCCESS, PARTIAL, FAILED)
     * @param error error detail or null
     * @param resultsCount number of ingested results
     * @return true if the execution was completed by this token; false if fenced out
     */
    public boolean completeExecution(UUID planId, UUID ownerToken, String status, String error, int resultsCount) {
        int updated = jdbcTemplate.update("""
                UPDATE search_plans
                SET last_run_at = now(),
                    last_execution_status = ?,
                    last_execution_error = ?,
                    last_execution_results = ?,
                    execution_owner_token = NULL,
                    execution_lease_expires_at = NULL,
                    updated_at = now()
                WHERE id = ?
                  AND execution_owner_token = ?
                """, status, error, resultsCount, planId, ownerToken);
        return updated > 0;
    }

    /**
     * Releases an execution lease without marking terminal run outcome (e.g. on early abort).
     */
    public boolean releaseExecutionLease(UUID planId, UUID ownerToken) {
        int updated = jdbcTemplate.update("""
                UPDATE search_plans
                SET execution_owner_token = NULL,
                    execution_lease_expires_at = NULL,
                    updated_at = now()
                WHERE id = ?
                  AND execution_owner_token = ?
                """, planId, ownerToken);
        return updated > 0;
    }

    /**
     * Recovers abandoned or crashed executions whose lease has expired.
     *
     * @param now current timestamp
     * @return count of recovered plans
     */
    public int recoverStaleExecutions(Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbcTemplate.update("""
                UPDATE search_plans
                SET last_execution_status = 'FAILED',
                    last_execution_error = 'Execution lease expired or worker terminated unexpectedly',
                    last_run_at = now(),
                    execution_owner_token = NULL,
                    execution_lease_expires_at = NULL,
                    updated_at = now()
                WHERE active = true
                  AND last_execution_status = 'RUNNING'
                  AND execution_lease_expires_at IS NOT NULL
                  AND execution_lease_expires_at < ?
                """, ts);
    }

    public SearchPlanRecord create(SearchPlanRecord plan) {
        UUID id = plan.id() != null ? plan.id() : UuidV7.generate();
        jdbcTemplate.update((Connection conn) -> {
            PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO search_plans (
                        id, profile_id, name, target_titles, query_facets,
                        target_sources, search_frequency, max_results_per_run,
                        active, last_execution_status, last_execution_error,
                        last_execution_results, execution_owner_token,
                        execution_lease_expires_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                    """);
            ps.setObject(1, id);
            ps.setObject(2, plan.profileId());
            ps.setString(3, plan.name());
            ps.setArray(4, conn.createArrayOf("text", plan.targetTitles().toArray()));
            ps.setString(5, JdbcConversions.toJson(plan.queryFacets(), objectMapper));
            ps.setArray(6, conn.createArrayOf("text", plan.targetSources().toArray()));
            ps.setString(7, plan.searchFrequency());
            ps.setInt(8, plan.maxResultsPerRun());
            ps.setBoolean(9, plan.active());
            ps.setString(10, plan.lastExecutionStatus());
            ps.setString(11, plan.lastExecutionError());
            ps.setInt(12, plan.lastExecutionResults());
            ps.setObject(13, plan.executionOwnerToken());
            ps.setTimestamp(14, plan.executionLeaseExpiresAt() != null ? Timestamp.from(plan.executionLeaseExpiresAt()) : null);
            return ps;
        });

        return findByIdAndProfileId(id, plan.profileId())
                .orElseThrow(() -> new IllegalStateException("Search plan missing after insert"));
    }

    public boolean update(UUID id, SearchPlanRecord plan) {
        int updated = jdbcTemplate.update((Connection conn) -> {
            PreparedStatement ps = conn.prepareStatement("""
                    UPDATE search_plans SET
                        name = ?,
                        target_titles = ?,
                        query_facets = ?::jsonb,
                        target_sources = ?,
                        search_frequency = ?,
                        max_results_per_run = ?,
                        active = ?,
                        updated_at = now()
                    WHERE id = ? AND profile_id = ?
                    """);
            ps.setString(1, plan.name());
            ps.setArray(2, conn.createArrayOf("text", plan.targetTitles().toArray()));
            ps.setString(3, JdbcConversions.toJson(plan.queryFacets(), objectMapper));
            ps.setArray(4, conn.createArrayOf("text", plan.targetSources().toArray()));
            ps.setString(5, plan.searchFrequency());
            ps.setInt(6, plan.maxResultsPerRun());
            ps.setBoolean(7, plan.active());
            ps.setObject(8, id);
            ps.setObject(9, plan.profileId());
            return ps;
        });
        return updated > 0;
    }

    public boolean delete(UUID id, UUID profileId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM search_plans WHERE id = ? AND profile_id = ?",
                id, profileId);
        return deleted > 0;
    }

    public void recordExecution(UUID id) {
        recordExecution(id, "SUCCESS", null, 0);
    }

    public void recordExecution(UUID id, String status, String error, int resultsCount) {
        jdbcTemplate.update("""
                UPDATE search_plans SET
                    last_run_at = now(),
                    last_execution_status = ?,
                    last_execution_error = ?,
                    last_execution_results = ?,
                    execution_owner_token = NULL,
                    execution_lease_expires_at = NULL,
                    updated_at = now()
                WHERE id = ?
                """, status, error, resultsCount, id);
    }

    public void markExecutionStatus(UUID id, String status) {
        jdbcTemplate.update("""
                UPDATE search_plans SET
                    last_execution_status = ?,
                    updated_at = now()
                WHERE id = ?
                """, status, id);
    }
}
