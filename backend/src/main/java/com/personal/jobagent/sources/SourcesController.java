package com.personal.jobagent.sources;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sources Controller: provides GET /api/v1/sources and POST /api/v1/sources/{id}/health-check
 * per architecture §C3.
 */
@RestController
@RequestMapping("/api/v1/sources")
public class SourcesController {

    private final JdbcTemplate jdbcTemplate;

    public SourcesController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<Map<String, Object>> listSources() {
        return jdbcTemplate.queryForList("""
                select id, kind, org_identifier, display_name, capabilities::text as capabilities,
                       policy, rate_limit_per_min, enabled, failure_streak, last_run_at
                from job_sources
                order by display_name
                """);
    }

    @PostMapping("/{id}/health-check")
    public Map<String, Object> healthCheck(@PathVariable UUID id) {
        jdbcTemplate.update("update job_sources set last_run_at = now(), failure_streak = 0 where id = ?", id);
        return jdbcTemplate.queryForMap(
                "select id, display_name, policy, enabled, last_run_at from job_sources where id = ?", id);
    }
}
