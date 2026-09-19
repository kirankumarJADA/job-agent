package com.personal.jobagent.discovery;

import com.personal.jobagent.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {

    private final JobDiscoveryService discoveryService;
    private final DiscoveryOrchestrator orchestrator;
    private final LinkedInDiscoveryService linkedInDiscovery;

    public DiscoveryController(JobDiscoveryService discoveryService, DiscoveryOrchestrator orchestrator,
                               LinkedInDiscoveryService linkedInDiscovery) {
        this.discoveryService = discoveryService;
        this.orchestrator = orchestrator;
        this.linkedInDiscovery = linkedInDiscovery;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingestJob(@RequestBody JobDiscoveryService.IngestJobCommand cmd, HttpServletRequest request) {
        if (cmd.sourceId() == null || cmd.title() == null || cmd.descriptionText() == null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", "sourceId, title, and descriptionText are required", request.getRequestURI(), correlationId()));
        }

        JobDiscoveryService.IngestResult result = discoveryService.ingestJob(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestParam java.util.UUID sourceId, @RequestParam String sourceType, @RequestParam String url) {
        return ResponseEntity.ok(orchestrator.discover(sourceId, sourceType, url));
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

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}