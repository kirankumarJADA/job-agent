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

    public DiscoveryController(JobDiscoveryService discoveryService, DiscoveryOrchestrator orchestrator,
                               LinkedInDiscoveryService linkedInDiscovery, JdbcTemplate jdbcTemplate) {
        this.discoveryService = discoveryService;
        this.orchestrator = orchestrator;
        this.linkedInDiscovery = linkedInDiscovery;
        this.jdbcTemplate = jdbcTemplate;
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
     *       {@code job_sources} decides the connector. Currently
     *       {@code kind = GREENHOUSE} is supported, using the source's
     *       {@code org_identifier} as the board token; any other kind without
     *       a URL is refused rather than guessed at.</li>
     * </ul>
     * The source id and its org identifier come from the source row — never
     * from the request body — so a caller cannot point the connector at an
     * arbitrary board.
     */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestParam java.util.UUID sourceId,
                                 @RequestParam(required = false) String sourceType,
                                 @RequestParam(required = false) String url,
                                 HttpServletRequest request) {
        if (url != null && !url.isBlank()) {
            return ResponseEntity.ok(orchestrator.discover(sourceId, sourceType, url));
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select kind, org_identifier, enabled from job_sources where id = ?", sourceId);
        if (rows.isEmpty()) {
            return notFound(request, "Discovery source not found");
        }
        Map<String, Object> source = rows.get(0);
        String kind = String.valueOf(source.get("kind"));
        if (!Boolean.TRUE.equals(source.get("enabled"))) {
            return badRequest(request, "Discovery source is disabled");
        }
        if (!GreenhouseProvider.SOURCE_KIND.equals(kind)) {
            return badRequest(request, "url is required for discovery kind " + kind);
        }
        String orgIdentifier = source.get("org_identifier") == null ? null : String.valueOf(source.get("org_identifier"));
        if (orgIdentifier == null || orgIdentifier.isBlank()) {
            return badRequest(request, "Greenhouse source has no org_identifier");
        }
        try {
            return ResponseEntity.ok(orchestrator.discoverGreenhouseBoard(sourceId, orgIdentifier));
        } catch (IllegalArgumentException e) {
            return badRequest(request, e.getMessage());
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