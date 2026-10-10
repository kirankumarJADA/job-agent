package com.personal.jobagent.prep;

import com.personal.jobagent.security.OwnerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * GET /api/v1/prep/jobs/{jobId}/readiness — the caller's preparation state
 * for one job (optionally one of their applications). Owner-scoped: the
 * profile comes from the session, never from the request.
 */
@RestController
@RequestMapping("/api/v1/prep")
public class PrepController {
    private static final Logger log = LoggerFactory.getLogger(PrepController.class);

    private final PrepReadinessService readiness;
    private final OwnerContext ownerContext;

    public PrepController(PrepReadinessService readiness, OwnerContext ownerContext) {
        this.readiness = readiness;
        this.ownerContext = ownerContext;
    }

    @GetMapping("/jobs/{jobId}/readiness")
    public ResponseEntity<?> readiness(@PathVariable UUID jobId, @RequestParam(required = false) UUID applicationId) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "a profile is required"));
        }
        try {
            return ResponseEntity.ok(readiness.readiness(profileId, jobId, applicationId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            // A storage failure is reported as unavailable, never as "not started".
            log.warn("Preparation readiness unavailable for job {}: {}", jobId, e.getClass().getSimpleName());
            return ResponseEntity.status(503).body(Map.of("error", "PREP_UNAVAILABLE",
                    "detail", "Preparation status could not be read from storage. It has not been reset."));
        }
    }
}
