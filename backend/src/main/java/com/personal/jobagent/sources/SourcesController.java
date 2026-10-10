package com.personal.jobagent.sources;

import com.personal.jobagent.discovery.DiscoveryOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sources Controller: provides GET /api/v1/sources and POST /api/v1/sources/{id}/health-check
 * per architecture §C3.
 *
 * <p>The health check performs a REAL verification for schedulable board
 * sources (GREENHOUSE / ASHBY with an org identifier): one live board fetch
 * through the same orchestrator the scheduled sweep uses, with the outcome
 * recorded into {@code failure_streak}/{@code health}. It used to fake a
 * clean bill of health by resetting the failure counter without checking
 * anything — a lie the dashboard would then display. Sources that cannot be
 * verified (no board API) return their recorded state unchanged.
 */
@RestController
@RequestMapping("/api/v1/sources")
public class SourcesController {

    private final JdbcTemplate jdbcTemplate;
    private final DiscoveryOrchestrator orchestrator;

    public SourcesController(JdbcTemplate jdbcTemplate, DiscoveryOrchestrator orchestrator) {
        this.jdbcTemplate = jdbcTemplate;
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public List<Map<String, Object>> listSources() {
        return jdbcTemplate.queryForList("""
                select id, kind, org_identifier, display_name, capabilities::text as capabilities,
                       policy, rate_limit_per_min, enabled, failure_streak, last_run_at, coalesce(health::text, '{}') as health
                from job_sources
                order by display_name
                """);
    }

    @PostMapping("/{id}/health-check")
    public ResponseEntity<?> healthCheck(@PathVariable UUID id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select kind, org_identifier, enabled from job_sources where id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        String kind = (String) rows.get(0).get("kind");
        String orgIdentifier = (String) rows.get(0).get("org_identifier");
        // A disabled source is never fetched live (the same rule /discovery/run
        // enforces); its recorded state is returned unchanged, never reset.
        boolean enabled = Boolean.TRUE.equals(rows.get(0).get("enabled"));

        if (enabled && ("GREENHOUSE".equals(kind) || "ASHBY".equals(kind))
                && orgIdentifier != null && !orgIdentifier.isBlank()) {
            DiscoveryOrchestrator.DiscoveryRun run = "ASHBY".equals(kind)
                    ? orchestrator.discoverAshbyBoard(id, orgIdentifier)
                    : orchestrator.discoverGreenhouseBoard(id, orgIdentifier);
            boolean ok = run.errors() == null || run.errors().isEmpty();
            jdbcTemplate.update(ok
                            ? "update job_sources set last_run_at = now(), failure_streak = 0, "
                              + "health = jsonb_build_object('status','ok','checked_at',to_char(now() at time zone 'utc','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),'detail',?::text) "
                              + "where id = ?"
                            : "update job_sources set last_run_at = now(), failure_streak = failure_streak + 1, "
                              + "health = jsonb_build_object('status','failed','checked_at',to_char(now() at time zone 'utc','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),'error',?::text,'failure_streak',failure_streak + 1) "
                              + "where id = ?",
                    ok ? run.ingested().size() + " job(s) seen" : String.join(",", run.errors()), id);
        }
        // Non-schedulable kinds return their recorded state as-is — no fake reset.
        return ResponseEntity.ok(jdbcTemplate.queryForMap(
                "select id, display_name, kind, policy, enabled, failure_streak, last_run_at, "
                        + "coalesce(health::text, '{}') as health from job_sources where id = ?", id));
    }
}
