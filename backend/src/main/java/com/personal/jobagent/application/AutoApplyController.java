package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /api/v1/auto-apply — Phase 5 (Robin x Tsenta continuation): the explicit,
 * bounded Auto Apply batch workflow.
 *
 * <p>Everything here is owner-scoped from the authenticated identity, exactly
 * like the rest of this API. This controller never submits an application —
 * {@link AutoApplyBatchService} only creates applications and hands them to
 * the EXISTING asynchronous preparation pipeline; REAL_SUBMIT stays
 * hard-stopped and every created application still requires the same human
 * review and approval the rest of Robin already enforces.
 *
 * <p>Starting a run is always an explicit POST from the caller — nothing here
 * is triggered by saving settings, and there is no scheduled trigger in this
 * phase.
 */
@RestController
@RequestMapping("/api/v1/auto-apply")
public class AutoApplyController {

    private final OwnerContext ownerContext;
    private final AutoApplySettingsService settingsService;
    private final AutoApplyBatchService batchService;
    private final AuditLogWriter audit;

    public AutoApplyController(OwnerContext ownerContext, AutoApplySettingsService settingsService,
                               AutoApplyBatchService batchService, AuditLogWriter audit) {
        this.ownerContext = ownerContext;
        this.settingsService = settingsService;
        this.batchService = batchService;
        this.audit = audit;
    }

    public record SettingsRequest(Boolean enabled, Integer minScore, Integer dailyCap, Integer maxBatchSize,
                                  String remoteType, String location, String employmentType,
                                  BigDecimal salaryMin, Boolean clearFilters) {}

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        return ResponseEntity.ok(settingsBody(settingsService.get(profileId)));
    }

    @PutMapping("/settings")
    public ResponseEntity<?> putSettings(@RequestBody SettingsRequest request, HttpServletRequest httpRequest) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        try {
            AutoApplySettingsService.Settings saved = settingsService.save(profileId,
                    request.enabled(), request.minScore(), request.dailyCap(), request.maxBatchSize(),
                    request.remoteType(), request.location(), request.employmentType(), request.salaryMin(),
                    Boolean.TRUE.equals(request.clearFilters()));
            audit.write(new AuditEntry(ownerContext.actorOr("user"), "AUTO_APPLY_SETTINGS_UPDATED", "PROFILE",
                    profileId, null, Map.of("enabled", saved.enabled(), "minScore", saved.minScore(),
                            "dailyCap", saved.dailyCap(), "maxBatchSize", saved.maxBatchSize()),
                    httpRequest.getRemoteAddr(), UuidV7.generate()));
            return ResponseEntity.ok(settingsBody(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> settingsBody(AutoApplySettingsService.Settings s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", s.enabled());
        body.put("configured", s.configured());
        body.put("minScore", s.minScore());
        body.put("dailyCap", s.dailyCap());
        body.put("maxBatchSize", s.maxBatchSize());
        body.put("remoteType", s.remoteType());
        body.put("location", s.location());
        body.put("employmentType", s.employmentType());
        body.put("salaryMin", s.salaryMin());
        return body;
    }

    /** Dry-run: what a run would do right now. Never creates anything. */
    @GetMapping("/preview")
    public ResponseEntity<?> preview() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        AutoApplyBatchService.PreviewResult result = batchService.preview(profileId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobsConsidered", result.jobsConsidered());
        body.put("jobsEligible", result.jobsEligible());
        body.put("items", result.items().stream().map(AutoApplyController::previewItemBody).toList());
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> previewItemBody(AutoApplyBatchService.PreviewItem i) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", i.jobId().toString());
        body.put("jobTitle", i.jobTitle());
        body.put("companyName", i.companyName());
        body.put("matchScore", i.matchScore());
        body.put("predictedOutcome", i.predictedOutcome());
        body.put("detail", i.detail());
        return body;
    }

    /** Starts one bounded run now. An explicit action — never triggered implicitly. */
    @PostMapping("/runs")
    public ResponseEntity<?> startRun() {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        try {
            UUID runId = batchService.runBatch(profileId);
            return batchService.findRun(profileId, runId)
                    .<ResponseEntity<?>>map(run -> ResponseEntity.ok(runBody(run)))
                    .orElseGet(() -> ResponseEntity.status(500).body(Map.of("error", "run could not be read back")));
        } catch (AutoApplyBatchService.BatchDisabledException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage(), "code", "AUTO_APPLY_DISABLED"));
        } catch (AutoApplyBatchService.ProfileNotReadyException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage(), "code", "PROFILE_NOT_READY"));
        }
    }

    @GetMapping("/runs")
    public ResponseEntity<?> history(@RequestParam(required = false, defaultValue = "20") int limit) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        List<Map<String, Object>> runs = batchService.history(profileId, limit).stream()
                .map(AutoApplyController::runBody).toList();
        return ResponseEntity.ok(Map.of("items", runs));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> getRun(@PathVariable UUID id) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        return batchService.findRun(profileId, id)
                .<ResponseEntity<?>>map(run -> {
                    Map<String, Object> body = runBody(run);
                    body.put("items", batchService.findRunItems(profileId, id).stream()
                            .map(AutoApplyController::runItemBody).toList());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Map<String, Object> runBody(AutoApplyBatchService.RunSummary r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", r.id().toString());
        body.put("status", r.status());
        body.put("jobsConsidered", r.jobsConsidered());
        body.put("jobsEligible", r.jobsEligible());
        body.put("prepared", r.prepared());
        body.put("skippedDuplicate", r.skippedDuplicate());
        body.put("skippedThreshold", r.skippedThreshold());
        body.put("skippedMissingProfile", r.skippedMissingProfile());
        body.put("skippedUnsupportedFlow", r.skippedUnsupportedFlow());
        body.put("quotaLimited", r.quotaLimited());
        body.put("failures", r.failures());
        body.put("startedAt", r.startedAt().toString());
        body.put("completedAt", r.completedAt() == null ? null : r.completedAt().toString());
        body.put("error", r.error());
        return body;
    }

    private static Map<String, Object> runItemBody(AutoApplyBatchService.RunItem i) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", i.jobId().toString());
        body.put("jobTitle", i.jobTitle());
        body.put("companyName", i.companyName());
        body.put("applicationId", i.applicationId() == null ? null : i.applicationId().toString());
        body.put("matchScore", i.matchScore());
        body.put("outcome", i.outcome());
        body.put("detail", i.detail());
        body.put("createdAt", i.createdAt().toString());
        return body;
    }
}
