package com.personal.jobagent.ats;

import com.personal.jobagent.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ats")
public class AtsController {

    private final AtsAdapterRegistry adapterRegistry;

    public AtsController(AtsAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    public record DetectRequest(String url) {}
    public record TestSubmitRequest(String url, AtsAdapter.SubmissionPayload payload, boolean dryRun) {}

    @GetMapping("/adapters")
    public List<Map<String, Object>> listAdapters() {
        // Declarative and side-effect free: the listing must never probe live
        // services (a probe once 500'd the whole endpoint on the real
        // Greenhouse inspector). Each adapter reports whether its inspection
        // is LIVE (deterministic real inspection) or not — a mock or
        // client-rendered boundary is labelled, never passed off as a scan.
        return adapterRegistry.getAdapters().stream()
                .<Map<String, Object>>map(a -> Map.of(
                        "kind", a.kind().name(),
                        "supportsLiveInspection", a.supportsLiveInspection(),
                        "inspection", a.supportsLiveInspection() ? "live" : "unavailable"))
                .toList();
    }

    @PostMapping("/detect")
    public ResponseEntity<?> detectAdapter(@RequestBody DetectRequest body, HttpServletRequest request) {
        if (body.url() == null || body.url().isBlank()) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", "URL is required", request.getRequestURI(), correlationId()));
        }

        return adapterRegistry.findAdapterForUrl(body.url())
                .<ResponseEntity<?>>map(adapter -> {
                    try {
                        return ResponseEntity.ok(adapter.inspectForm(body.url()));
                    } catch (IllegalStateException e) {
                        // Real inspection (e.g. Greenhouse) is unavailable —
                        // deterministic 503, never a silent mock fallback.
                        return ResponseEntity.status(503).body(ApiError.of(503, "Inspection Unavailable",
                                e.getMessage(), request.getRequestURI(), correlationId()));
                    }
                })
                .orElseGet(() -> ResponseEntity.status(404).body(ApiError.of(404, "Not Found", "No adapter matched URL: " + body.url(), request.getRequestURI(), correlationId())));
    }

    @PostMapping("/test-submit")
    public ResponseEntity<?> testSubmit(@RequestBody TestSubmitRequest body, HttpServletRequest request) {
        if (body.url() == null || body.payload() == null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", "url and payload are required", request.getRequestURI(), correlationId()));
        }

        var adapterOpt = adapterRegistry.findAdapterForUrl(body.url());
        if (adapterOpt.isEmpty()) {
            return ResponseEntity.status(404).body(ApiError.of(404, "Not Found", "No adapter matches URL", request.getRequestURI(), correlationId()));
        }

        AtsAdapter.SubmissionResult result = adapterOpt.get().submitApplication(body.url(), body.payload(), body.dryRun());
        return ResponseEntity.ok(result);
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}