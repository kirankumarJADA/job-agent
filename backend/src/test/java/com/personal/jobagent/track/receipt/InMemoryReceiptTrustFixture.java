package com.personal.jobagent.track.receipt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEST-ONLY trusted state and durable store for {@link ReceiptCaptureService}
 * unit tests. Lives under {@code src/test}: production code has no in-memory
 * registry, seeding method or fallback of any kind.
 *
 * <p>Models the database's guarantees the service relies on: one receipt per
 * plan (unique), atomic insert, and failure injection for trust reads and for
 * persistence.
 */
public final class InMemoryReceiptTrustFixture implements ReceiptTrustStore, ReceiptStore {

    private record PlanKey(UUID profileId, UUID applicationId, UUID planId) {}
    private record SandboxKey(UUID profileId, String atsPlatform, String postingUrl) {}

    private final Set<PlanKey> plans = ConcurrentHashMap.newKeySet();
    private final Map<PlanKey, VerifiedApproval> approvals = new ConcurrentHashMap<>();
    private final Map<PlanKey, WorkerEvidence> evidence = new ConcurrentHashMap<>();
    private final Map<SandboxKey, SandboxRegistration> sandboxes = new ConcurrentHashMap<>();
    private final Map<UUID, SandboxRegistration> sandboxesById = new ConcurrentHashMap<>();
    private final Map<UUID, StoredReceipt> receiptsByPlan = new ConcurrentHashMap<>();

    public final AtomicBoolean failTrustReads = new AtomicBoolean(false);
    public final AtomicBoolean failPersistence = new AtomicBoolean(false);
    public final AtomicInteger inserts = new AtomicInteger();
    public final AtomicInteger auditRows = new AtomicInteger();

    // ── seeding ──────────────────────────────────────────────────────────

    public InMemoryReceiptTrustFixture plan(UUID profileId, UUID applicationId, UUID planId) {
        plans.add(new PlanKey(profileId, applicationId, planId));
        return this;
    }

    public InMemoryReceiptTrustFixture approval(UUID profileId, UUID applicationId, UUID planId,
                                               String actor, Instant approvedAt) {
        approvals.put(new PlanKey(profileId, applicationId, planId),
                new VerifiedApproval(UUID.randomUUID(), actor, approvedAt));
        return this;
    }

    public InMemoryReceiptTrustFixture workerEvidence(UUID profileId, UUID applicationId, UUID planId,
                                                     WorkerEvidence workerEvidence) {
        evidence.put(new PlanKey(profileId, applicationId, planId), workerEvidence);
        return this;
    }

    public InMemoryReceiptTrustFixture sandbox(UUID profileId, String atsPlatform, String postingUrl,
                                              String permittedDomain, Instant revokedAt) {
        return sandbox(profileId, atsPlatform, postingUrl, permittedDomain,
                Instant.parse("2026-11-01T00:00:00Z"), revokedAt);
    }

    public InMemoryReceiptTrustFixture sandbox(UUID profileId, String atsPlatform, String postingUrl,
                                              String permittedDomain, Instant approvedAt, Instant revokedAt) {
        SandboxRegistration reg = new SandboxRegistration(
                UUID.randomUUID(), profileId, atsPlatform, postingUrl, permittedDomain,
                approvedAt, revokedAt);
        sandboxes.put(new SandboxKey(profileId, atsPlatform, postingUrl), reg);
        sandboxesById.put(reg.id(), reg);
        return this;
    }

    public InMemoryReceiptTrustFixture revokeSandbox(UUID profileId, String atsPlatform, String postingUrl) {
        SandboxKey key = new SandboxKey(profileId, atsPlatform, postingUrl);
        SandboxRegistration existing = sandboxes.get(key);
        if (existing != null) {
            SandboxRegistration revoked = new SandboxRegistration(
                    existing.id(), existing.profileId(), existing.atsPlatform(),
                    existing.postingUrl(), existing.permittedDomain(), existing.approvedAt(), Instant.now());
            sandboxes.put(key, revoked);
            sandboxesById.put(revoked.id(), revoked);
        }
        return this;
    }

    public void removeApproval(UUID profileId, UUID applicationId, UUID planId) {
        approvals.remove(new PlanKey(profileId, applicationId, planId));
    }

    public int receiptCount() {
        return receiptsByPlan.size();
    }

    // ── ReceiptTrustStore ────────────────────────────────────────────────

    @Override
    public boolean ownsPlan(UUID profileId, UUID applicationId, UUID planId) {
        checkTrust();
        return plans.contains(new PlanKey(profileId, applicationId, planId));
    }

    @Override
    public Optional<VerifiedApproval> verifiedApproval(UUID profileId, UUID applicationId, UUID planId) {
        checkTrust();
        return Optional.ofNullable(approvals.get(new PlanKey(profileId, applicationId, planId)));
    }

    @Override
    public Optional<WorkerEvidence> workerEvidence(UUID profileId, UUID applicationId, UUID planId) {
        checkTrust();
        return Optional.ofNullable(evidence.get(new PlanKey(profileId, applicationId, planId)));
    }

    @Override
    public Optional<SandboxRegistration> activeSandboxRegistration(UUID profileId, String atsPlatform, String postingUrl) {
        checkTrust();
        SandboxRegistration r = sandboxes.get(new SandboxKey(profileId, atsPlatform, postingUrl));
        if (r == null) {
            String canonical = SandboxRegistrationPolicy.canonicalizePostingUrlForMatch(postingUrl);
            if (canonical != null) {
                r = sandboxes.get(new SandboxKey(profileId, atsPlatform, canonical));
            }
        }
        return r == null || r.revokedAt() != null ? Optional.empty() : Optional.of(r);
    }

    @Override
    public Optional<SandboxRegistration> sandboxRegistrationById(UUID registrationId) {
        checkTrust();
        return registrationId == null ? Optional.empty() : Optional.ofNullable(sandboxesById.get(registrationId));
    }

    private void checkTrust() {
        if (failTrustReads.get()) {
            throw new IllegalStateException("simulated database outage");
        }
    }

    // ── ReceiptStore ─────────────────────────────────────────────────────

    @Override
    public synchronized InsertResult insertIfAbsent(NewReceipt r) {
        if (failPersistence.get()) {
            throw new IllegalStateException("simulated database outage during receipt insert");
        }
        SandboxRegistration reg = sandboxesById.get(r.sandboxRegistrationId());
        if (reg == null || reg.revokedAt() != null) {
            throw new InvalidReceiptException(
                    "Sandbox registration is missing or was revoked before receipt persistence completed",
                    "SANDBOX_POSTING_UNREGISTERED");
        }
        StoredReceipt existing = receiptsByPlan.get(r.planId());
        if (existing != null) {
            return new InsertResult(existing, false);
        }
        StoredReceipt stored = new StoredReceipt(UUID.randomUUID(), r.idempotencyKey(), r.profileId(),
                r.applicationId(), r.planId(), r.attemptNumber(), r.evidenceId(), r.evidenceSha256(),
                r.atsPlatform(), r.employerConfirmationId(), List.copyOf(r.observedSignals()), r.approvalAuditId(),
                r.approvalActor(), r.approvedAt(), r.workerPrincipal(), r.sandboxRegistrationId(), r.policyVersion(),
                Instant.now());
        receiptsByPlan.put(r.planId(), stored);
        inserts.incrementAndGet();
        auditRows.incrementAndGet();
        return new InsertResult(stored, true);
    }

    @Override
    public Optional<StoredReceipt> findByPlan(UUID profileId, UUID planId) {
        StoredReceipt r = receiptsByPlan.get(planId);
        return r != null && r.profileId().equals(profileId) ? Optional.of(r) : Optional.empty();
    }
}
