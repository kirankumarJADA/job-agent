package com.personal.jobagent.track.receipt;

import com.personal.jobagent.track.ApplicationReceiptService.AnswerReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.AttemptSummary;
import com.personal.jobagent.track.ApplicationReceiptService.AuthorizationRecord;
import com.personal.jobagent.track.ApplicationReceiptService.ConfirmationEvidence;
import com.personal.jobagent.track.ApplicationReceiptService.DocumentReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.FailureDetails;
import com.personal.jobagent.track.ApplicationReceiptService.IdentityReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.ReconciliationDetails;
import com.personal.jobagent.track.ApplicationReceiptService.SubmissionReceipt;
import com.personal.jobagent.track.receipt.ReceiptStore.InsertResult;
import com.personal.jobagent.track.receipt.ReceiptStore.NewReceipt;
import com.personal.jobagent.track.receipt.ReceiptStore.StoredReceipt;
import com.personal.jobagent.track.receipt.ReceiptTrustStore.SandboxRegistration;
import com.personal.jobagent.track.receipt.ReceiptTrustStore.VerifiedApproval;
import com.personal.jobagent.track.receipt.ReceiptTrustStore.WorkerEvidence;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy.ReceiptEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Captures a verified submission receipt for one automation plan attempt.
 *
 * <p><b>What the caller supplies, and what it does not.</b> The command carries
 * the evidence to verify (confirmation id, signals, raw DOM/screenshot payload,
 * digest) and display data. It carries <em>no</em> trust decisions: candidate
 * approval, worker provenance and sandbox authorization are all resolved from
 * {@link ReceiptTrustStore}, i.e. from persisted server state. There are no
 * caller-supplied booleans or provenance strings to forge.
 *
 * <p><b>Binding.</b> The command's evidence must match, field for field, the
 * evidence the authenticated worker recorded for this exact plan at completion
 * time ({@code submission_evidence}): same raw-evidence digest, same employer
 * confirmation id, same platform, same signals. Evidence that did not arrive
 * through the worker completion path therefore cannot produce a receipt.
 *
 * <p><b>Durability.</b> A receipt is reported as captured only after
 * {@link ReceiptStore#insertIfAbsent} commits the receipt row together with its
 * audit row. Without a store (or when it fails) capture throws — it never falls
 * back to memory. Concurrent and replayed captures for the same plan are
 * resolved by the database's unique constraints; a replay whose evidence
 * differs from the stored receipt is rejected.
 */
@Service
public class ReceiptCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptCaptureService.class);

    public static final String WORKER_PRINCIPAL = "worker";
    public static final String WORKER_PROVENANCE = "AUTHENTICATED_BROWSER_WORKER";

    private final ReceiptVerificationPolicy verificationPolicy;
    private final ReceiptTrustStore trust;
    private final ReceiptStore store;
    private final SubmissionEvidenceRepository evidenceRepository;

    @Autowired
    public ReceiptCaptureService(ReceiptVerificationPolicy verificationPolicy,
                                 @Autowired(required = false) ReceiptTrustStore trust,
                                 @Autowired(required = false) ReceiptStore store,
                                 @Autowired(required = false) SubmissionEvidenceRepository evidenceRepository) {
        this.verificationPolicy = verificationPolicy != null ? verificationPolicy : new ReceiptVerificationPolicy();
        this.trust = trust;
        this.store = store;
        this.evidenceRepository = evidenceRepository;
    }

    public ReceiptCaptureService(ReceiptVerificationPolicy verificationPolicy,
                                 ReceiptTrustStore trust,
                                 ReceiptStore store) {
        this(verificationPolicy, trust, store, null);
    }

    /** No trusted state and no durable store: every capture fails closed. */
    public ReceiptCaptureService(ReceiptVerificationPolicy verificationPolicy) {
        this(verificationPolicy, null, null, null);
    }

    public record CaptureReceiptCommand(
            UUID profileId,
            UUID applicationId,
            UUID planId,
            UUID jobId,
            String jobTitle,
            String company,
            String applicationUrl,
            String atsPlatform,
            String employerConfirmationId,
            String receiptSha256,
            Instant submittedAt,
            IdentityReceipt submittedIdentity,
            List<AnswerReceipt> submittedAnswers,
            DocumentReceipt cv,
            DocumentReceipt coverLetter,
            List<String> observedSignals,
            String summary,
            String rawEvidencePayload,
            int attemptNumber
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private UUID profileId;
            private UUID applicationId;
            private UUID planId;
            private UUID jobId;
            private String jobTitle;
            private String company;
            private String applicationUrl;
            private String atsPlatform;
            private String employerConfirmationId;
            private String receiptSha256;
            private Instant submittedAt;
            private IdentityReceipt submittedIdentity;
            private List<AnswerReceipt> submittedAnswers = List.of();
            private DocumentReceipt cv;
            private DocumentReceipt coverLetter;
            private List<String> observedSignals = List.of();
            private String summary;
            private String rawEvidencePayload;
            private int attemptNumber = 1;

            public Builder profileId(UUID profileId) { this.profileId = profileId; return this; }
            public Builder applicationId(UUID applicationId) { this.applicationId = applicationId; return this; }
            public Builder planId(UUID planId) { this.planId = planId; return this; }
            public Builder jobId(UUID jobId) { this.jobId = jobId; return this; }
            public Builder jobTitle(String jobTitle) { this.jobTitle = jobTitle; return this; }
            public Builder company(String company) { this.company = company; return this; }
            public Builder applicationUrl(String applicationUrl) { this.applicationUrl = applicationUrl; return this; }
            public Builder atsPlatform(String atsPlatform) { this.atsPlatform = atsPlatform; return this; }
            public Builder employerConfirmationId(String id) { this.employerConfirmationId = id; return this; }
            public Builder receiptSha256(String sha256) { this.receiptSha256 = sha256; return this; }
            public Builder submittedAt(Instant submittedAt) { this.submittedAt = submittedAt; return this; }
            public Builder submittedIdentity(IdentityReceipt identity) { this.submittedIdentity = identity; return this; }
            public Builder submittedAnswers(List<AnswerReceipt> answers) { this.submittedAnswers = answers; return this; }
            public Builder cv(DocumentReceipt cv) { this.cv = cv; return this; }
            public Builder coverLetter(DocumentReceipt coverLetter) { this.coverLetter = coverLetter; return this; }
            public Builder observedSignals(List<String> signals) { this.observedSignals = signals; return this; }
            public Builder summary(String summary) { this.summary = summary; return this; }
            public Builder rawEvidencePayload(String payload) { this.rawEvidencePayload = payload; return this; }
            public Builder attemptNumber(int num) { this.attemptNumber = num; return this; }

            public CaptureReceiptCommand build() {
                return new CaptureReceiptCommand(profileId, applicationId, planId, jobId, jobTitle, company,
                        applicationUrl, atsPlatform, employerConfirmationId, receiptSha256, submittedAt,
                        submittedIdentity, submittedAnswers, cv, coverLetter, observedSignals, summary,
                        rawEvidencePayload, attemptNumber);
            }
        }
    }

    public record GenuineReceiptRecord(
            SubmissionReceipt submissionReceipt,
            UUID storedReceiptId,
            String receiptSha256,
            String employerConfirmationId,
            String atsPlatform,
            Instant verifiedAt,
            boolean genuine
    ) {}

    public enum VerificationStatus {
        NOT_FOUND,
        VERIFIED,
        INVALID
    }

    /** Result of revalidating a durable stored receipt against its linked encrypted raw evidence. */
    public record StoredReceiptVerification(
            VerificationStatus status,
            Optional<StoredReceipt> verifiedReceipt,
            String failureReason
    ) {
        public static StoredReceiptVerification notFound() {
            return new StoredReceiptVerification(VerificationStatus.NOT_FOUND, Optional.empty(), null);
        }

        public static StoredReceiptVerification verified(StoredReceipt receipt) {
            return new StoredReceiptVerification(VerificationStatus.VERIFIED, Optional.of(receipt), null);
        }

        public static StoredReceiptVerification invalid(String reason) {
            return new StoredReceiptVerification(VerificationStatus.INVALID, Optional.empty(), reason);
        }

        public boolean isVerified() {
            return status == VerificationStatus.VERIFIED && verifiedReceipt.isPresent();
        }

        public boolean isInvalid() {
            return status == VerificationStatus.INVALID;
        }
    }

    /** Raw stored receipt row lookup without post-capture evidence revalidation (internal/replay conflict use only). */
    Optional<StoredReceipt> findStoredReceipt(UUID profileId, UUID planId) {
        if (store == null || profileId == null || planId == null) {
            return Optional.empty();
        }
        return store.findByPlan(profileId, planId);
    }

    /**
     * Revalidates a durable stored receipt against ownership, linked raw evidence provenance,
     * AES-GCM decryption, and SHA-256 digests in both submission_evidence and submission_receipts.
     *
     * <p>Note: {@link org.springframework.dao.DataAccessException} is intentionally allowed to propagate
     * so callers like tracking can distinguish infrastructure outages (503) from tampered evidence.
     */
    public StoredReceiptVerification revalidateStoredReceipt(UUID profileId, UUID applicationId, UUID planId) {
        if (profileId == null || applicationId == null || planId == null || store == null) {
            return StoredReceiptVerification.notFound();
        }
        Optional<StoredReceipt> storedOpt = store.findByPlan(profileId, planId);
        if (storedOpt.isEmpty()) {
            return StoredReceiptVerification.notFound();
        }
        return revalidateLoadedReceipt(profileId, applicationId, planId, storedOpt.get());
    }

    private StoredReceiptVerification revalidateLoadedReceipt(
            UUID profileId, UUID applicationId, UUID planId, StoredReceipt stored) {
        if (trust == null || evidenceRepository == null) {
            return StoredReceiptVerification.invalid(
                    "receipt verification is unavailable, so this stored receipt cannot be trusted.");
        }
        if (!profileId.equals(stored.profileId())
                || !applicationId.equals(stored.applicationId())
                || !planId.equals(stored.planId())) {
            return StoredReceiptVerification.invalid("stored receipt does not belong to this application or plan.");
        }
        if (!trust.ownsPlan(profileId, applicationId, planId)) {
            return StoredReceiptVerification.invalid("stored receipt plan ownership could not be verified.");
        }
        if (!WORKER_PRINCIPAL.equals(stored.workerPrincipal())
                || stored.approvalAuditId() == null
                || stored.sandboxRegistrationId() == null
                || stored.evidenceId() == null
                || stored.evidenceSha256() == null
                || stored.evidenceSha256().isBlank()
                || stored.employerConfirmationId() == null
                || stored.employerConfirmationId().isBlank()
                || stored.atsPlatform() == null
                || stored.atsPlatform().isBlank()) {
            return StoredReceiptVerification.invalid("stored receipt is missing required verification metadata.");
        }

        Optional<SubmissionEvidenceRepository.StoredEvidence> evidenceOpt =
                evidenceRepository.findById(profileId, applicationId, planId, stored.evidenceId());
        if (evidenceOpt.isEmpty()) {
            return StoredReceiptVerification.invalid("linked submission evidence could not be found for this receipt.");
        }
        SubmissionEvidenceRepository.StoredEvidence ev = evidenceOpt.get();
        if (!stored.evidenceId().equals(ev.id())
                || !profileId.equals(ev.profileId())
                || !applicationId.equals(ev.applicationId())
                || !planId.equals(ev.planId())) {
            return StoredReceiptVerification.invalid("linked submission evidence association does not match this receipt.");
        }
        if (!WORKER_PRINCIPAL.equals(ev.workerPrincipal())
                || !SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE.equals(ev.recordedVia())) {
            return StoredReceiptVerification.invalid("linked submission evidence was not recorded by an authenticated browser worker.");
        }
        if (!Objects.equals(trim(stored.employerConfirmationId()), trim(ev.employerConfirmationId()))
                || !Objects.equals(trim(stored.atsPlatform()), trim(ev.atsPlatform()))) {
            return StoredReceiptVerification.invalid("stored receipt confirmation details do not match linked evidence.");
        }
        if (ev.rawEvidenceSha256() == null
                || !ev.rawEvidenceSha256().equalsIgnoreCase(stored.evidenceSha256().trim())) {
            return StoredReceiptVerification.invalid("stored receipt digest does not match linked evidence digest.");
        }

        Optional<String> decrypted;
        try {
            decrypted = evidenceRepository.decryptRawEvidence(ev);
        } catch (EvidenceIntegrityException e) {
            log.warn("Stored evidence integrity verification failed for plan {}: {}", planId, e.getMessage());
            return StoredReceiptVerification.invalid("stored raw evidence failed decryption or integrity verification.");
        }
        if (decrypted.isEmpty()) {
            return StoredReceiptVerification.invalid("stored raw evidence ciphertext is missing or unavailable.");
        }

        String recomputedSha256 = ReceiptVerificationPolicy.computeEvidenceSha256(decrypted.get());
        if (!recomputedSha256.equalsIgnoreCase(ev.rawEvidenceSha256())
                || !recomputedSha256.equalsIgnoreCase(stored.evidenceSha256().trim())) {
            return StoredReceiptVerification.invalid("recomputed raw evidence SHA-256 does not match stored receipt.");
        }
        return StoredReceiptVerification.verified(stored);
    }

    /** True only when a stored receipt exists AND passes full encrypted-evidence integrity revalidation. */
    public boolean isStoredReceiptIntegrityVerified(UUID profileId, UUID applicationId, UUID planId) {
        return revalidateStoredReceipt(profileId, applicationId, planId).isVerified();
    }

    /** The durable verified receipt for this plan, ONLY if it passes linked encrypted-evidence revalidation. */
    public Optional<StoredReceipt> findVerifiedReceipt(UUID profileId, UUID planId) {
        Optional<StoredReceipt> stored = findStoredReceipt(profileId, planId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return revalidateLoadedReceipt(profileId, stored.get().applicationId(), planId, stored.get())
                .verifiedReceipt();
    }

    /**
     * True when a receipt already captured for this plan was captured from
     * different evidence (digest or employer confirmation id).
     */
    public boolean isReplayConflicting(UUID profileId, UUID planId, String receiptSha256, String confirmationId) {
        return findStoredReceipt(profileId, planId)
                .map(existing -> conflicts(existing, receiptSha256, confirmationId))
                .orElse(false);
    }

    /**
     * Verifies the command against trusted server state and the receipt policy,
     * then durably persists it.
     *
     * @return the persisted receipt
     * @throws InvalidReceiptException when any verification or persistence step fails
     */
    public SubmissionReceipt captureReceipt(CaptureReceiptCommand cmd) {
        return capture(cmd).submissionReceipt();
    }

    /** As {@link #captureReceipt}, also returning the stored receipt id and verification time. */
    public GenuineReceiptRecord captureGenuineReceipt(CaptureReceiptCommand cmd) {
        return capture(cmd);
    }

    private GenuineReceiptRecord capture(CaptureReceiptCommand cmd) {
        if (cmd == null) {
            throw new InvalidReceiptException("CaptureReceiptCommand cannot be null", "MISSING_COMMAND");
        }
        if (cmd.profileId() == null) {
            throw new InvalidReceiptException("profileId is required", "MISSING_PROFILE_ID");
        }
        if (cmd.applicationId() == null) {
            throw new InvalidReceiptException("applicationId is required", "MISSING_APPLICATION_ID");
        }
        if (cmd.planId() == null) {
            throw new InvalidReceiptException("planId is required: a receipt is bound to one exact automation plan",
                    "MISSING_PLAN_ID");
        }
        if (trust == null || store == null) {
            throw new InvalidReceiptException(
                    "Durable receipt persistence is unavailable: a receipt cannot be verified or captured",
                    "RECEIPT_PERSISTENCE_UNAVAILABLE");
        }

        // Trust boundary 1: plan, application and profile belong together in persisted records.
        if (!trusted(() -> trust.ownsPlan(cmd.profileId(), cmd.applicationId(), cmd.planId()))) {
            throw new InvalidReceiptException(
                    "Cross-profile receipt injection rejected: profile " + cmd.profileId()
                            + " does not own plan " + cmd.planId() + " for application " + cmd.applicationId(),
                    "CROSS_PROFILE_RECEIPT_REJECTED");
        }

        // Raw evidence must exist before anything is compared or hashed.
        if (cmd.rawEvidencePayload() == null || cmd.rawEvidencePayload().isBlank()) {
            throw new InvalidReceiptException(
                    "Confirmation evidence is required: canonical DOM evidence or screenshot bytes cannot be empty",
                    "MISSING_RAW_EVIDENCE");
        }
        if (ReceiptVerificationPolicy.isSummarySubstitution(cmd.rawEvidencePayload())) {
            throw new InvalidReceiptException(
                    "Raw evidence payload cannot be substituted with a summary text: actual DOM/screenshot evidence required",
                    "SUMMARY_SUBSTITUTION_REJECTED");
        }
        String computedSha256 = ReceiptVerificationPolicy.computeEvidenceSha256(cmd.rawEvidencePayload());

        // Trust boundary 2: a receipt already captured for this plan is final.
        Optional<StoredReceipt> existing = trusted(() -> store.findByPlan(cmd.profileId(), cmd.planId()));
        if (existing.isPresent()) {
            if (conflicts(existing.get(), computedSha256, cmd.employerConfirmationId())) {
                throw new InvalidReceiptException(
                        "Replay conflict detected for plan " + cmd.planId()
                                + ": a receipt was already captured from different evidence",
                        "RECEIPT_REPLAY_REJECTED");
            }
            if (evidenceRepository != null) {
                StoredReceiptVerification check = revalidateLoadedReceipt(
                        cmd.profileId(), cmd.applicationId(), cmd.planId(), existing.get());
                if (!check.isVerified()) {
                    throw new InvalidReceiptException(
                            "Stored receipt failed evidence integrity revalidation for plan " + cmd.planId(),
                            "RECEIPT_EVIDENCE_INTEGRITY_FAILED");
                }
            }
            log.info("Receipt replay for plan {} matched the stored receipt {}; returning it unchanged",
                    cmd.planId(), existing.get().receiptId());
            return toRecord(existing.get(), cmd);
        }

        // Trust boundary 3: the candidate's own approval of this exact plan, from the audit log.
        VerifiedApproval approval = trusted(() -> trust.verifiedApproval(cmd.profileId(), cmd.applicationId(), cmd.planId()))
                .orElseThrow(() -> new InvalidReceiptException(
                        "Candidate attestation record is missing or not confirmed in audit log for plan " + cmd.planId(),
                        "MISSING_CANDIDATE_ATTESTATION"));

        // Trust boundary 4: evidence the authenticated worker recorded for this plan.
        WorkerEvidence worker = trusted(() -> trust.workerEvidence(cmd.profileId(), cmd.applicationId(), cmd.planId()))
                .orElseThrow(() -> new InvalidReceiptException(
                        "No evidence was recorded by the authenticated worker completion path for plan " + cmd.planId(),
                        "UNAUTHENTICATED_EVIDENCE_PROVENANCE"));
        if (!WORKER_PRINCIPAL.equals(worker.workerPrincipal())
                || !SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE.equals(worker.recordedVia())) {
            throw new InvalidReceiptException(
                    "Evidence for plan " + cmd.planId() + " was not recorded by the authenticated worker completion path",
                    "UNAUTHENTICATED_EVIDENCE_PROVENANCE");
        }
        if (worker.recordedAt() == null || approval.approvedAt() == null) {
            throw new InvalidReceiptException(
                    "submittedAt timestamp is required: the worker completion time or approval time is missing",
                    "MISSING_SUBMITTED_AT");
        }
        if (approval.approvedAt().isAfter(worker.recordedAt())) {
            throw new InvalidReceiptException(
                    "Candidate approval for plan " + cmd.planId() + " post-dates the recorded submission",
                    "APPROVAL_AFTER_SUBMISSION");
        }

        // Trust boundary 5: the command's evidence is the worker's evidence, field for field.
        if (worker.rawEvidenceSha256() == null) {
            throw new InvalidReceiptException(
                    "No raw evidence was stored by the worker completion path for plan " + cmd.planId()
                            + " (" + worker.rawEvidenceStatus() + ")",
                    "MISSING_RAW_EVIDENCE");
        }
        if (!computedSha256.equalsIgnoreCase(worker.rawEvidenceSha256())) {
            throw new InvalidReceiptException(
                    "Evidence digest does not match the evidence recorded by the authenticated worker",
                    "EVIDENCE_NOT_BOUND_TO_WORKER");
        }
        if (!Objects.equals(trim(cmd.employerConfirmationId()), trim(worker.employerConfirmationId()))) {
            throw new InvalidReceiptException(
                    "Employer confirmation id does not match the confirmation recorded by the authenticated worker",
                    "EVIDENCE_NOT_BOUND_TO_WORKER");
        }
        if (cmd.atsPlatform() == null
                || !cmd.atsPlatform().trim().equalsIgnoreCase(String.valueOf(worker.atsPlatform()).trim())) {
            throw new InvalidReceiptException(
                    "ATS platform does not match the platform recorded by the authenticated worker",
                    "EVIDENCE_NOT_BOUND_TO_WORKER");
        }
        if (cmd.observedSignals() == null || cmd.observedSignals().isEmpty()) {
            throw new InvalidReceiptException(
                    "Observed confirmation signals are required and cannot be empty or manufactured",
                    "MISSING_CONFIRMATION_SIGNALS");
        }
        if (!new HashSet<>(cmd.observedSignals()).equals(new HashSet<>(worker.observedSignals()))) {
            throw new InvalidReceiptException(
                    "Observed signals do not match the signals recorded by the authenticated worker",
                    "EVIDENCE_NOT_BOUND_TO_WORKER");
        }

        // Trust boundary 6: candidate-owned sandbox, from server-side registration only.
        String platform = cmd.atsPlatform().trim().toUpperCase(Locale.ROOT);
        Optional<SandboxRegistration> registration = worker.targetUrl() == null ? Optional.empty()
                : trusted(() -> trust.activeSandboxRegistration(cmd.profileId(), platform, worker.targetUrl()));
        boolean sandboxPosting = registration.isPresent()
                && hostWithinDomain(worker.targetUrl(), registration.get().permittedDomain());
        boolean candidateAuthorizedSandbox = registration.isPresent()
                && cmd.profileId().equals(registration.get().profileId())
                && registration.get().revokedAt() == null;

        // Every invariant of the receipt policy, with trust inputs taken from server state only.
        verificationPolicy.validateOrThrow(new ReceiptEvidence(
                cmd.employerConfirmationId(),
                cmd.receiptSha256(),
                platform,
                worker.recordedAt(),
                cmd.profileId(),
                cmd.applicationId(),
                true,
                sandboxPosting,
                candidateAuthorizedSandbox,
                worker.observedSignals(),
                cmd.rawEvidencePayload(),
                WORKER_PROVENANCE));

        // Durable persistence: receipt + audit in one transaction, idempotent per plan.
        NewReceipt newReceipt = new NewReceipt(
                cmd.profileId(),
                cmd.applicationId(),
                cmd.planId(),
                cmd.attemptNumber() > 0 ? cmd.attemptNumber() : 1,
                worker.evidenceId(),
                computedSha256,
                platform,
                cmd.employerConfirmationId().trim(),
                List.copyOf(worker.observedSignals()),
                approval.auditId(),
                approval.actor(),
                approval.approvedAt(),
                worker.workerPrincipal(),
                registration.get().id(),
                ReceiptVerificationPolicy.POLICY_VERSION);
        InsertResult result;
        try {
            result = store.insertIfAbsent(newReceipt);
        } catch (RuntimeException e) {
            log.error("Durable receipt persistence failed for plan {}: {}", cmd.planId(), e.getMessage());
            throw new InvalidReceiptException(
                    "Durable persistence failed: the receipt was not captured", "RECEIPT_PERSISTENCE_FAILED");
        }
        if (result == null || result.receipt() == null) {
            throw new InvalidReceiptException(
                    "Durable persistence failed: the receipt was not captured", "RECEIPT_PERSISTENCE_FAILED");
        }
        if (!result.inserted() && conflicts(result.receipt(), computedSha256, cmd.employerConfirmationId())) {
            throw new InvalidReceiptException(
                    "Replay conflict detected for plan " + cmd.planId()
                            + ": a concurrent capture stored different evidence",
                    "RECEIPT_REPLAY_REJECTED");
        }
        log.info("Captured verified submission receipt {} for plan={} app={} platform={} sha256={}",
                result.receipt().receiptId(), cmd.planId(), cmd.applicationId(), platform, computedSha256);
        return toRecord(result.receipt(), cmd);
    }

    private GenuineReceiptRecord toRecord(StoredReceipt stored, CaptureReceiptCommand cmd) {
        AuthorizationRecord authorization = new AuthorizationRecord(
                true, "EXPLICIT_CANDIDATE_APPROVAL", stored.approvedAt(), stored.approvalActor());
        ConfirmationEvidence confirmation = new ConfirmationEvidence(
                true,
                stored.employerConfirmationId(),
                stored.observedSignals(),
                cmd.summary() != null && !cmd.summary().isBlank()
                        ? cmd.summary().trim()
                        : "Submission confirmed with verified " + stored.atsPlatform() + " platform evidence.");
        Instant submittedAt = cmd.submittedAt() != null ? cmd.submittedAt() : stored.capturedAt();
        AttemptSummary attempt = new AttemptSummary(
                stored.planId(), stored.attemptNumber(), stored.atsPlatform(), stored.idempotencyKey(),
                "SUBMITTED", true, submittedAt, submittedAt, true);
        SubmissionReceipt receipt = new SubmissionReceipt(
                stored.receiptId(),
                stored.applicationId(),
                cmd.jobId(),
                cmd.jobTitle(),
                cmd.company(),
                cmd.applicationUrl(),
                stored.atsPlatform(),
                stored.attemptNumber(),
                true,
                stored.idempotencyKey(),
                "SUBMITTED",
                true,
                submittedAt,
                submittedAt,
                stored.capturedAt(),
                authorization,
                cmd.submittedIdentity() != null ? cmd.submittedIdentity()
                        : new IdentityReceipt(null, null, null, null, null, null, null),
                cmd.submittedAnswers() != null ? cmd.submittedAnswers() : List.of(),
                cmd.cv(),
                cmd.coverLetter(),
                confirmation,
                new ReconciliationDetails(false, null, null),
                new FailureDetails(false, null, null),
                List.of(attempt));
        return new GenuineReceiptRecord(receipt, stored.receiptId(), stored.evidenceSha256(),
                stored.employerConfirmationId(), stored.atsPlatform(), stored.capturedAt(), true);
    }

    private static boolean conflicts(StoredReceipt existing, String receiptSha256, String confirmationId) {
        return receiptSha256 == null
                || !existing.evidenceSha256().equalsIgnoreCase(receiptSha256.trim())
                || !Objects.equals(existing.employerConfirmationId(), trim(confirmationId));
    }

    /** True when the URL is https and its host equals the permitted domain or is a subdomain of it. */
    static boolean hostWithinDomain(String url, String permittedDomain) {
        if (url == null || permittedDomain == null || permittedDomain.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String h = host.toLowerCase(Locale.ROOT);
            String d = permittedDomain.trim().toLowerCase(Locale.ROOT);
            return h.equals(d) || h.endsWith("." + d);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Reads trusted state; any failure to read it fails closed rather than being treated as "absent". */
    private static <T> T trusted(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (InvalidReceiptException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Trusted receipt state could not be read: {}", e.getMessage());
            throw new InvalidReceiptException(
                    "Trusted receipt state is unavailable: the receipt cannot be verified", "TRUST_STATE_UNAVAILABLE");
        }
    }
}
