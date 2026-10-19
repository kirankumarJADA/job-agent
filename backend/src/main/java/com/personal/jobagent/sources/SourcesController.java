package com.personal.jobagent.sources;

import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.discovery.platform.PlatformCoverageService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sources Controller: provides GET /api/v1/sources and POST /api/v1/sources/{id}/health-check
 * per architecture §C3, plus GET /api/v1/sources/coverage (Phase 8.2.5).
 *
 * <p>This is the OPERATIONS view of discovery — the one place where source
 * names, platforms and health are shown. Candidate job views never expose them.
 *
 * <p>The health check performs a REAL verification for every board platform
 * with a registered adapter: a bounded live run (small page and detail
 * budget) through the same {@link BoardDiscoveryService} the scheduler uses,
 * with the outcome, counters and failure category recorded on the source. It
 * never fakes a clean bill of health. A disabled source is never fetched and
 * its recorded state is returned unchanged; kinds without an adapter (legacy
 * non-board kinds, unverified or blocked platforms) are returned as recorded.
 */
@RestController
@RequestMapping("/api/v1/sources")
public class SourcesController {

    private final JdbcTemplate jdbcTemplate;
    private final BoardDiscoveryService boards;
    private final PlatformCoverageService coverage;

    public SourcesController(JdbcTemplate jdbcTemplate, BoardDiscoveryService boards, PlatformCoverageService coverage) {
        this.jdbcTemplate = jdbcTemplate;
        this.boards = boards;
        this.coverage = coverage;
    }

    @GetMapping
    public List<Map<String, Object>> listSources() {
        return jdbcTemplate.queryForList("""
                select id, kind, org_identifier, display_name, capabilities::text as capabilities,
                       policy, rate_limit_per_min, enabled, failure_streak, last_run_at,
                       last_attempted_at, last_success_at, coalesce(last_run::text, '{}') as last_run,
                       coalesce(health::text, '{}') as health
                from job_sources
                order by display_name
                """);
    }

    /** One row per platform (30 ATS + Tsenta) with its current coverage status. */
    @GetMapping("/coverage")
    public List<Map<String, Object>> coverage() {
        return coverage.coverage();
    }

    @PostMapping("/{id}/health-check")
    public ResponseEntity<?> healthCheck(@PathVariable UUID id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select kind, enabled, policy from job_sources where id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        String kind = (String) rows.get(0).get("kind");
        boolean enabled = Boolean.TRUE.equals(rows.get(0).get("enabled")) && !"DISABLED".equals(rows.get(0).get("policy"));
        Map<String, Object> run = null;
        if (enabled && boards.isRunnable(kind)) {
            try {
                BoardDiscoveryService.SourceRunReport report = boards.run(id, BoardDiscoveryService.Mode.HEALTH_CHECK);
                run = Map.of("status", report.status(), "complete", report.complete(), "counts", report.counts());
            } catch (BoardDiscoveryService.RunRefused refused) {
                // Raced with a disable or a delete: report what is recorded.
            }
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>(jdbcTemplate.queryForMap(
                "select id, display_name, kind, policy, enabled, failure_streak, last_run_at, last_attempted_at, "
                        + "last_success_at, coalesce(last_run::text, '{}') as last_run, "
                        + "coalesce(health::text, '{}') as health from job_sources where id = ?", id));
        if (run != null) body.put("run", run);
        return ResponseEntity.ok(body);
    }
}
