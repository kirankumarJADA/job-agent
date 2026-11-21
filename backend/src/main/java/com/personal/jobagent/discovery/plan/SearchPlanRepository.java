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
import java.time.Instant;
import java.util.List;
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
        return jdbcTemplate.query("""
                SELECT * FROM search_plans
                WHERE active = true
                  AND (
                      (search_frequency = 'HOURLY' AND (last_run_at IS NULL OR last_run_at <= ? - INTERVAL '1 hour'))
                   OR (search_frequency = 'DAILY' AND (last_run_at IS NULL OR last_run_at <= ? - INTERVAL '1 day'))
                   OR (search_frequency = 'WEEKLY' AND (last_run_at IS NULL OR last_run_at <= ? - INTERVAL '7 days'))
                  )
                ORDER BY last_run_at ASC NULLS FIRST
                """, rowMapper(), ts, ts, ts);
    }

    public SearchPlanRecord create(SearchPlanRecord plan) {
        UUID id = plan.id() != null ? plan.id() : UuidV7.generate();
        jdbcTemplate.update((Connection conn) -> {
            PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO search_plans (
                        id, profile_id, name, target_titles, query_facets,
                        target_sources, search_frequency, max_results_per_run,
                        active, last_execution_status, last_execution_error,
                        last_execution_results, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, now(), now())
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
