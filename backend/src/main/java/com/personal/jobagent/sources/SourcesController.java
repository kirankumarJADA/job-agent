package com.personal.jobagent.sources;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.discovery.platform.PlatformCoverageService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sources Controller: provides GET /api/v1/sources and POST /api/v1/sources/{id}/health-check
 * per architecture §C3, plus GET /api/v1/sources/coverage (Phase 8.2.5).
 * Phase 18 adds GET/PATCH /api/v1/sources/{id} and POST /api/v1/sources/{id}/run.
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

    public record UpdateSourceRequest(
            Boolean enabled,
            String policy,
            @JsonAlias({"scheduleCron", "schedule_cron"}) String schedule_cron,
            @JsonAlias({"rateLimitPerMin", "rate_limit_per_min"}) Integer rate_limit_per_min
    ) {}

    @GetMapping
    public List<Map<String, Object>> listSources() {
        return jdbcTemplate.queryForList("""
                select id, kind, org_identifier, display_name, capabilities::text as capabilities,
                       policy, schedule_cron, rate_limit_per_min, enabled, failure_streak, last_run_at,
                       last_attempted_at, last_success_at, coalesce(last_run::text, '{}') as last_run,
                       coalesce(health::text, '{}') as health
                from job_sources
                order by display_name
                """);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getSource(@PathVariable UUID id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, kind, org_identifier, display_name, capabilities::text as capabilities,
                       policy, schedule_cron, rate_limit_per_min, enabled, failure_streak, last_run_at,
                       last_attempted_at, last_success_at, coalesce(last_run::text, '{}') as last_run,
                       coalesce(health::text, '{}') as health
                from job_sources
                where id = ?
                """, id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(rows.get(0));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> updateSource(@PathVariable UUID id, @RequestBody(required = false) UpdateSourceRequest req) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("select id from job_sources where id = ?", id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        if (req == null) return getSource(id);

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (req.enabled() != null) {
            setClauses.add("enabled = ?");
            params.add(req.enabled());
        }
        if (req.policy() != null) {
            String p = req.policy().trim().toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("POLITE", "AGGRESSIVE", "DISABLED", "DISCOVERY_ONLY").contains(p)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid policy; must be POLITE, AGGRESSIVE, DISCOVERY_ONLY, or DISABLED"));
            }
            setClauses.add("policy = ?");
            params.add(p);
        }
        if (req.schedule_cron() != null) {
            String cron = req.schedule_cron().trim();
            if (!cron.isEmpty()) {
                try {
                    CronExpression.parse(cron);
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid cron expression: " + e.getMessage()));
                }
            }
            setClauses.add("schedule_cron = ?");
            params.add(cron.isEmpty() ? null : cron);
        }
        if (req.rate_limit_per_min() != null) {
            if (req.rate_limit_per_min() <= 0 || req.rate_limit_per_min() > 600) {
                return ResponseEntity.badRequest().body(Map.of("error", "rate_limit_per_min must be between 1 and 600"));
            }
            setClauses.add("rate_limit_per_min = ?");
            params.add(req.rate_limit_per_min());
        }

        if (!setClauses.isEmpty()) {
            params.add(id);
            jdbcTemplate.update("update job_sources set " + String.join(", ", setClauses) + " where id = ?", params.toArray());
        }
        return getSource(id);
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> runSource(@PathVariable UUID id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select kind, enabled, policy from job_sources where id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        String kind = (String) rows.get(0).get("kind");
        if (!boards.isRunnable(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Source platform " + kind + " is not runnable"));
        }
        try {
            BoardDiscoveryService.SourceRunReport report = boards.run(id, BoardDiscoveryService.Mode.MANUAL);
            Map<String, Object> body = new java.util.LinkedHashMap<>(jdbcTemplate.queryForMap("""
                    select id, kind, org_identifier, display_name, capabilities::text as capabilities,
                           policy, schedule_cron, rate_limit_per_min, enabled, failure_streak, last_run_at,
                           last_attempted_at, last_success_at, coalesce(last_run::text, '{}') as last_run,
                           coalesce(health::text, '{}') as health
                    from job_sources where id = ?
                    """, id));
            body.put("run", Map.of(
                    "status", report.status(),
                    "complete", report.complete(),
                    "counts", report.counts(),
                    "ok", report.ok(),
                    "failure_category", report.failureCategory() != null ? report.failureCategory() : ""
            ));
            return ResponseEntity.ok(body);
        } catch (BoardDiscoveryService.RunRefused refused) {
            return ResponseEntity.badRequest().body(Map.of("error", refused.getMessage()));
        }
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
