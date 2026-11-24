package com.personal.jobagent.track.receipt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The trusted server-side state a receipt is verified against. Every answer
 * comes from persisted records; nothing here is ever derived from the receipt
 * command or from caller-supplied JSON.
 *
 * <p>Production implementation: {@link JdbcReceiptTrustStore}. Unit tests use
 * a test-only fixture under {@code src/test}; there is no in-memory fallback in
 * production code.
 */
public interface ReceiptTrustStore {

    /** The candidate's own submit approval, read from the immutable audit log. */
    record VerifiedApproval(UUID auditId, String actor, Instant approvedAt) {}

    /** What the authenticated worker recorded for this plan at completion time. */
    record WorkerEvidence(
            UUID evidenceId,
            String workerPrincipal,
            String recordedVia,
            String atsPlatform,
            String targetUrl,
            String employerConfirmationId,
            List<String> observedSignals,
            String rawEvidenceSha256,
            String rawEvidenceStatus,
            Instant recordedAt
    ) {}

    /** A candidate-owned sandbox posting registered on the server. */
    record SandboxRegistration(
            UUID id,
            UUID profileId,
            String atsPlatform,
            String postingUrl,
            String permittedDomain,
            Instant approvedAt,
            Instant revokedAt
    ) {}

    /** True only when the plan belongs to this application and both belong to this profile. */
    boolean ownsPlan(UUID profileId, UUID applicationId, UUID planId);

    Optional<VerifiedApproval> verifiedApproval(UUID profileId, UUID applicationId, UUID planId);

    Optional<WorkerEvidence> workerEvidence(UUID profileId, UUID applicationId, UUID planId);

    /** An unrevoked registration of exactly this posting URL for this profile and platform. */
    Optional<SandboxRegistration> activeSandboxRegistration(UUID profileId, String atsPlatform, String postingUrl);
}
