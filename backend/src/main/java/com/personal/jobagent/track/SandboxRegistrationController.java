package com.personal.jobagent.track;

import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.track.receipt.SandboxRegistrationRepository.CandidateSandboxRegistration;
import com.personal.jobagent.track.receipt.SandboxRegistrationService;
import com.personal.jobagent.track.receipt.SandboxRegistrationService.SandboxCatalogView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Candidate-owned sandbox posting registration API ({@code /api/v1/sandbox-registrations}).
 *
 * <p>Strictly scoped to authenticated candidate sessions: worker bearer tokens are refused (403),
 * and any caller-supplied trust fields ({@code profileId}, {@code approvedBy}, {@code permittedDomain})
 * are rejected (400). Foreign or missing registration IDs return 404.
 */
@RestController
@RequestMapping("/api/v1/sandbox-registrations")
public class SandboxRegistrationController {

    private final SandboxRegistrationService service;
    private final OwnerContext ownerContext;

    public SandboxRegistrationController(SandboxRegistrationService service, OwnerContext ownerContext) {
        this.service = service;
        this.ownerContext = ownerContext;
    }

    public record CreateSandboxRegistrationRequest(
            String atsPlatform,
            String postingUrl,
            UUID profileId,
            String approvedBy,
            String permittedDomain,
            Object approvedAt,
            Object revokedAt
    ) {}

    public record SandboxRegistrationDto(
            UUID id,
            String atsPlatform,
            String postingUrl,
            String permittedDomain,
            String approvedBy,
            Instant approvedAt,
            Instant revokedAt,
            boolean active
    ) {
        static SandboxRegistrationDto from(CandidateSandboxRegistration r) {
            return new SandboxRegistrationDto(
                    r.id(),
                    r.atsPlatform(),
                    r.postingUrl(),
                    r.permittedDomain(),
                    r.approvedBy(),
                    r.approvedAt(),
                    r.revokedAt(),
                    r.isActive());
        }
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "sandbox registrations are candidate-managed only"));
        }
        UUID profileId = ownerContext.requireProfileId();
        SandboxCatalogView catalog = service.listForProfile(profileId);
        List<SandboxRegistrationDto> items = catalog.items().stream().map(SandboxRegistrationDto::from).toList();
        return ResponseEntity.ok(Map.of(
                "configured", catalog.configured(),
                "allowedDomains", catalog.allowedDomains(),
                "allowedPathPrefixes", catalog.allowedPathPrefixes(),
                "requireHttps", catalog.requireHttps(),
                "items", items));
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody(required = false) CreateSandboxRegistrationRequest body) {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "workers cannot register or authorize sandboxes"));
        }
        UUID profileId = ownerContext.requireProfileId();
        if (body == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (body.profileId() != null || body.approvedBy() != null || body.permittedDomain() != null
                || body.approvedAt() != null || body.revokedAt() != null) {
            throw new IllegalArgumentException(
                    "Caller-supplied profileId, approvedBy, permittedDomain, approvedAt, and revokedAt are forbidden");
        }
        String actor = ownerContext.actorOr(profileId.toString());
        CandidateSandboxRegistration created = service.register(profileId, actor, body.atsPlatform(), body.postingUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(SandboxRegistrationDto.from(created));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<?> revoke(@PathVariable UUID id) {
        if (ownerContext.isWorkerRequest()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "workers cannot revoke candidate sandboxes"));
        }
        UUID profileId = ownerContext.requireProfileId();
        String actor = ownerContext.actorOr(profileId.toString());
        CandidateSandboxRegistration revoked = service.revoke(profileId, actor, id);
        return ResponseEntity.ok(SandboxRegistrationDto.from(revoked));
    }
}
