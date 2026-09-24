package com.personal.jobagent.identity;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.profile.ProfileRecord;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * External-account identities and automation sessions.
 *
 * <p><b>Ownership is derived from the session, never from the request.</b> This
 * class previously accepted {@code profileId} in the request body and passed it
 * straight to the service, so any authenticated caller could create an identity
 * — including a stored credential — against any other account's profile. It now
 * resolves the caller's own profile from the authenticated principal and uses
 * that for every operation.
 *
 * <p>The {@code profileId} field is still accepted on the identity request so
 * existing clients do not break, but it is deliberately <em>ignored</em>. It is
 * carried only to keep the JSON shape stable; using it would reintroduce the
 * vulnerability.
 */
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {

    private final IdentityService service;
    private final CredentialVault vault;
    private final ProfileRepository profileRepository;

    public IdentityController(IdentityService service, CredentialVault vault, ProfileRepository profileRepository) {
        this.service = service;
        this.vault = vault;
        this.profileRepository = profileRepository;
    }

    public record IdentityRequest(UUID profileId, String email, String domain, String credential) {
    }

    public record SessionRequest(UUID applicationId, String domain) {
    }

    @PostMapping("/identities")
    public ResponseEntity<?> create(@RequestBody IdentityRequest request, HttpServletRequest httpRequest) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return notFound(httpRequest, "No profile exists for this account.");
        }
        if (request.email() == null || request.domain() == null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request",
                    "email and domain are required", httpRequest.getRequestURI(), correlationId()));
        }

        String credentialRef = request.credential() == null
                ? null
                : vault.store(request.credential(), Duration.ofHours(1));
        return ResponseEntity.ok(Map.of("identityId", service.create(profileId, request.email(), request.domain(), credentialRef)));
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> session(@RequestBody SessionRequest request, HttpServletRequest httpRequest) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return notFound(httpRequest, "No profile exists for this account.");
        }
        if (request.applicationId() == null || request.domain() == null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request",
                    "applicationId and domain are required", httpRequest.getRequestURI(), correlationId()));
        }
        return service.startSession(request.applicationId(), request.domain(), profileId)
                .<ResponseEntity<?>>map(sessionId ->
                        ResponseEntity.ok(Map.of("sessionId", sessionId)))
                .orElseGet(() -> notFound(httpRequest, "No application with that id"));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id, HttpServletRequest httpRequest) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return notFound(httpRequest, "Session not found");
        }
        return service.session(id, profileId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(httpRequest, "Session not found"));
    }

    @PostMapping("/sessions/{id}/hard-stop")
    public ResponseEntity<?> stop(@PathVariable UUID id, @RequestBody Map<String, String> body,
                                  HttpServletRequest httpRequest) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return notFound(httpRequest, "Session not found");
        }
        boolean changed = service.hardStop(id, body.getOrDefault("reason", "unspecified"), profileId);
        if (!changed) {
            return notFound(httpRequest, "Session not found");
        }
        return ResponseEntity.ok(Map.of("changed", true));
    }

    private UUID currentProfileIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserDetails principal)) {
            return null;
        }
        return profileRepository.findByUserId(principal.getUserId())
                .map(ProfileRecord::id)
                .orElse(null);
    }

    private ResponseEntity<?> notFound(HttpServletRequest request, String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not Found", detail, request.getRequestURI(), correlationId()));
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }
}
