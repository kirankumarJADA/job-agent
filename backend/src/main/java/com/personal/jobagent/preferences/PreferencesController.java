package com.personal.jobagent.preferences;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * GET/PUT /api/v1/preferences per docs/contracts/api.md. Full-replacement
 * PUT semantics (not partial patch) — matches the architecture's framing of
 * preferences as "a single configurable vector," not a field-by-field
 * patchable resource.
 */
@RestController
public class PreferencesController {

    private final PreferenceSetRepository preferenceSetRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogWriter auditLogWriter;

    public PreferencesController(PreferenceSetRepository preferenceSetRepository,
                                  JdbcTemplate jdbcTemplate,
                                  AuditLogWriter auditLogWriter) {
        this.preferenceSetRepository = preferenceSetRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogWriter = auditLogWriter;
    }

    @GetMapping("/api/v1/preferences")
    public ResponseEntity<?> get(HttpServletRequest httpRequest) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return notFound(httpRequest, "No profile exists yet for this account.");
        }
        return preferenceSetRepository.findActiveByProfileId(profileId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(httpRequest, "No active preference set exists for this profile."));
    }

    @PutMapping("/api/v1/preferences")
    public ResponseEntity<?> update(@RequestBody PreferenceSetRecord request, HttpServletRequest httpRequest) {
        UUID profileId = currentProfileId();
        if (profileId == null) {
            return notFound(httpRequest, "No profile exists yet for this account.");
        }

        try {
            ScoringWeights.validate(request.scoringWeights());
        } catch (ScoringWeights.ValidationException e) {
            ApiError error = ApiError.of(400, "Invalid scoring weights", e.getMessage(),
                    httpRequest.getRequestURI(), String.valueOf(org.slf4j.MDC.get("correlation_id")));
            return ResponseEntity.badRequest().body(error);
        }

        var existing = preferenceSetRepository.findActiveByProfileId(profileId);
        if (existing.isEmpty()) {
            return notFound(httpRequest, "No active preference set exists for this profile.");
        }

        Map<String, Object> before = existing.get().scoringWeights();
        preferenceSetRepository.update(existing.get().id(), request);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null ? authentication.getName() : "UNKNOWN";
        auditLogWriter.write(new AuditEntry(
                actor, "PREFERENCES_UPDATED", "PREFERENCE_SET", existing.get().id(),
                Map.of("scoringWeights", before), Map.of("scoringWeights", request.scoringWeights()),
                httpRequest.getRemoteAddr(),
                com.personal.jobagent.common.UuidV7.generate()
        ));

        return preferenceSetRepository.findActiveByProfileId(profileId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(httpRequest, "Preference set vanished after update — this should not happen."));
    }

    private ResponseEntity<?> notFound(HttpServletRequest httpRequest, String detail) {
        ApiError error = ApiError.of(404, "Not found", detail, httpRequest.getRequestURI(),
                String.valueOf(org.slf4j.MDC.get("correlation_id")));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    private UUID currentProfileId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
        return jdbcTemplate.query(
                        "select id from profiles where user_id = ?",
                        (rs, rowNum) -> (UUID) rs.getObject("id"), principal.getUserId())
                .stream().findFirst().orElse(null);
    }
}
