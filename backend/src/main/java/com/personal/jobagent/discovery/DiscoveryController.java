package com.personal.jobagent.discovery;

import com.personal.jobagent.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {

    private final JobDiscoveryService discoveryService;
    private final DiscoveryOrchestrator orchestrator;
    private final LinkedInDiscoveryService linkedInDiscovery;
    private final JdbcTemplate jdbcTemplate;
    private final com.personal.jobagent.discovery.platform.BoardDiscoveryService boards;

    public DiscoveryController(JobDiscoveryService discoveryService, DiscoveryOrchestrator orchestrator,
                               LinkedInDiscoveryService linkedInDiscovery, JdbcTemplate jdbcTemplate,
                               com.personal.jobagent.discovery.platform.BoardDiscoveryService boards) {
        this.discoveryService = discoveryService;
        this.orchestrator = orchestrator;
        this.linkedInDiscovery = linkedInDiscovery;
        this.jdbcTemplate = jdbcTemplate;
        this.boards = boards;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingestJob(@RequestBody JobDiscoveryService.IngestJobCommand cmd, HttpServletRequest request) {
        if (cmd.sourceId() == null || cmd.title() == null || cmd.descriptionText() == null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", "sourceId, title, and descriptionText are required", request.getRequestURI(), correlationId()));
        }

        JobDiscoveryService.IngestResult result = discoveryService.ingestJob(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Runs discovery for one source. Two forms:
     * <ul>
     *   <li>with {@code url} — generic URL discovery through the configured
     *       scraper providers (unchanged behavior);</li>
     *   <li>without {@code url} — kind-aware dispatch: the source row in
     *       {@code job_sources} decides the board adapter (every supported
     *       platform, Phase 8.2.5) and the run goes through
     *       {@link com.personal.jobagent.discovery.platform.BoardDiscoveryService};
     *       a hosted Ashby URL is accepted only when it matches that persisted source.</li>
     * </ul>
     * The source id and org identifier come from the source row. When an Ashby
     * URL is supplied it must match the source board, preventing cross-source
     * board selection.
     */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestParam java.util.UUID sourceId,
                                 @RequestParam(required = false) String sourceType,
                                 @RequestParam(required = false) String url,
                                 HttpServletRequest request) {
        if (url != null && !url.isBlank() && AshbyProvider.isHostedBoardUrl(url)) {
            try {
                String board = AshbyProvider.boardNameFromHostedUrl(url);
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "select kind, org_identifier, enabled from job_sources where id = ?", sourceId);
                if (rows.isEmpty()) return notFound(request, "Discovery source not found");
                Map<String, Object> source = rows.get(0);
                if (!Boolean.TRUE.equals(source.get("enabled"))) return badRequest(request, "Discovery source is disabled");
                if (!AshbyProvider.SOURCE_KIND.equals(String.valueOf(source.get("kind")))) {
                    return badRequest(request, "Hosted Ashby URL requires an ASHBY discovery source");
                }
                String registeredBoard = AshbyProvider.boardNameFromIdentifier(String.valueOf(source.get("org_identifier")));
                if (!board.equalsIgnoreCase(registeredBoard)) {
                    return badRequest(request, "Ashby URL board does not match the registered source");
                }
                return runBoard(sourceId, request);
            } catch (IllegalArgumentException e) {
                return badRequest(request, e.getMessage());
            }
        }
        if (url != null && !url.isBlank()) {
            return ResponseEntity.ok(orchestrator.discover(sourceId, sourceType, url));
        }

        return runBoard(sourceId, request);
    }

    private ResponseEntity<?> runBoard(java.util.UUID sourceId, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(boards.run(sourceId, com.personal.jobagent.discovery.platform.BoardDiscoveryService.Mode.MANUAL));
        } catch (com.personal.jobagent.discovery.platform.BoardDiscoveryService.RunRefused refused) {
            return refused.notFound() ? notFound(request, refused.getMessage()) : badRequest(request, refused.getMessage());
        }
    }

    @PostMapping("/linkedin/search")
    public ResponseEntity<?> linkedinSearch(@RequestParam java.util.UUID sourceId,
                                             @RequestParam String keywords,
                                             @RequestParam(defaultValue = "") String location,
                                             @RequestParam(defaultValue = "") String remoteType,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int pageSize) {
        return ResponseEntity.ok(linkedInDiscovery.search(sourceId,
                new LinkedInDiscoveryService.SearchRequest(keywords, location, remoteType, page, pageSize)));
    }

    @PostMapping("/maintenance")
    public ResponseEntity<?> runMaintenance() {
        discoveryService.scheduledDiscoveryMaintenance();
        return ResponseEntity.ok(java.util.Map.of("status", "MAINTENANCE_COMPLETED"));
    }

    private ResponseEntity<?> notFound(HttpServletRequest request, String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not Found", detail, request.getRequestURI(), correlationId()));
    }

    private ResponseEntity<?> badRequest(HttpServletRequest request, String detail) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Bad Request", detail, request.getRequestURI(), correlationId()));
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}