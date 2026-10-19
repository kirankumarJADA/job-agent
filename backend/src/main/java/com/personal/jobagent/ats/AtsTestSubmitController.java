package com.personal.jobagent.ats;

import com.personal.jobagent.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Local-development-only adapter smoke test.
 *
 * <p>Lets a developer exercise an {@link AtsAdapter}'s payload-validation
 * path end to end without a live browser run. It can never report a real
 * submission: every adapter's {@code submitApplication} unconditionally
 * refuses to claim success for a non-dry-run call (see
 * {@link BaseAtsAdapter#submitApplication}), and — as a second, independent
 * layer — this controller exists only under the {@code local} Spring
 * profile: it is not registered at all in {@code prod} or in the default
 * (no-profile) configuration CI runs under. Real execution for any ATS runs
 * exclusively through the reviewed {@code automation} package's
 * AutomationPlan / worker pipeline, never through this endpoint.
 */
@RestController
@RequestMapping("/api/v1/ats")
@Profile("local")
public class AtsTestSubmitController {

    private final AtsAdapterRegistry adapterRegistry;

    public AtsTestSubmitController(AtsAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    public record TestSubmitRequest(String url, AtsAdapter.SubmissionPayload payload, boolean dryRun) {}

    @PostMapping("/test-submit")
    public ResponseEntity<?> testSubmit(@RequestBody TestSubmitRequest body, HttpServletRequest request) {
        if (body.url() == null || body.payload() == null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request",
                    "url and payload are required", request.getRequestURI(), correlationId()));
        }

        var adapterOpt = adapterRegistry.findAdapterForUrl(body.url());
        if (adapterOpt.isEmpty()) {
            return ResponseEntity.status(404).body(ApiError.of(404, "Not Found",
                    "No adapter matches URL", request.getRequestURI(), correlationId()));
        }

        AtsAdapter.SubmissionResult result = adapterOpt.get().submitApplication(body.url(), body.payload(), body.dryRun());
        return ResponseEntity.ok(result);
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}
