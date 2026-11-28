package com.personal.jobagent.track.receipt;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.track.receipt.SandboxRegistrationPolicy.ValidatedSandboxTarget;
import com.personal.jobagent.track.receipt.SandboxRegistrationRepository.CandidateSandboxRegistration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Application service for candidate-owned sandbox registrations.
 *
 * <p>Enforces policy validation, server-derived domain/ownership binding, immutable registration
 * history (revocation only stamps {@code revoked_at}), and transactional audit logging.
 */
@Service
public class SandboxRegistrationService {

    private final SandboxRegistrationRepository repository;
    private final SandboxRegistrationPolicy policy;
    private final AuditLogWriter audit;

    public SandboxRegistrationService(
            SandboxRegistrationRepository repository,
            SandboxRegistrationPolicy policy,
            AuditLogWriter audit) {
        this.repository = repository;
        this.policy = policy;
        this.audit = audit;
    }

    public record SandboxCatalogView(
            boolean configured,
            List<String> allowedDomains,
            List<String> allowedPathPrefixes,
            boolean requireHttps,
            List<CandidateSandboxRegistration> items
    ) {}

    public SandboxCatalogView listForProfile(UUID profileId) {
        List<CandidateSandboxRegistration> items = repository.listForProfile(profileId);
        return new SandboxCatalogView(
                policy.isConfigured(),
                policy.allowedDomains(),
                policy.allowedPathPrefixes(),
                policy.requireHttps(),
                items);
    }

    @Transactional
    public CandidateSandboxRegistration register(
            UUID profileId,
            String actor,
            String atsPlatform,
            String rawPostingUrl) {
        if (profileId == null) {
            throw new IllegalStateException("No profile exists for the current user");
        }
        ValidatedSandboxTarget target = policy.validateAndCanonicalize(atsPlatform, rawPostingUrl);
        String resolvedActor = (actor == null || actor.isBlank()) ? profileId.toString() : actor;
        CandidateSandboxRegistration created;
        try {
            created = repository.insert(
                    profileId,
                    target.atsPlatform(),
                    target.canonicalPostingUrl(),
                    target.permittedDomain(),
                    resolvedActor);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException(
                    "A sandbox registration already exists for this ATS platform and canonical posting URL");
        }

        audit.write(new AuditEntry(
                resolvedActor,
                "SANDBOX_REGISTRATION_CREATED",
                "SANDBOX_REGISTRATION",
                created.id(),
                null,
                Map.of(
                        "profileId", profileId.toString(),
                        "atsPlatform", created.atsPlatform(),
                        "postingUrl", created.postingUrl(),
                        "permittedDomain", created.permittedDomain()),
                null,
                UuidV7.generate()));
        return created;
    }

    @Transactional
    public CandidateSandboxRegistration revoke(UUID profileId, String actor, UUID registrationId) {
        if (profileId == null || registrationId == null) {
            throw new NoSuchElementException("Sandbox registration not found");
        }
        CandidateSandboxRegistration existing = repository.findOwnedById(profileId, registrationId)
                .orElseThrow(() -> new NoSuchElementException("Sandbox registration not found"));
        if (!existing.isActive()) {
            throw new IllegalStateException("Sandbox registration is already revoked");
        }
        int updated = repository.markRevoked(profileId, registrationId);
        if (updated != 1) {
            throw new IllegalStateException("Sandbox registration state changed concurrently");
        }
        CandidateSandboxRegistration revoked = repository.findOwnedById(profileId, registrationId)
                .orElseThrow(() -> new NoSuchElementException("Sandbox registration not found"));

        String resolvedActor = (actor == null || actor.isBlank()) ? profileId.toString() : actor;
        audit.write(new AuditEntry(
                resolvedActor,
                "SANDBOX_REGISTRATION_REVOKED",
                "SANDBOX_REGISTRATION",
                revoked.id(),
                Map.of("active", true, "postingUrl", existing.postingUrl()),
                Map.of("active", false, "revokedAt", String.valueOf(revoked.revokedAt())),
                null,
                UuidV7.generate()));
        return revoked;
    }
}
