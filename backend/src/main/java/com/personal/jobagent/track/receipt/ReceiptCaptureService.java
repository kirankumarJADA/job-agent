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
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy.ReceiptEvidence;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy.VerificationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 20 / Wave 3 Agent A: Receipt Capture Service.
 *
 * <p>Captures and verifies genuine application receipts from ATS platforms
 * (Workday, Greenhouse, Ashby, Lever, Zoho Recruit).
 *
 * <p>Enforces all 7 genuine receipt invariants and strictly rejects any mock or
 * synthetic prefixes in production paths with {@link InvalidReceiptException} (422).
 * Reuses existing {@link SubmissionReceipt}, {@link ConfirmationEvidence}, and
 * {@link DocumentReceipt} types.
 */
@Service
public class ReceiptCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptCaptureService.class);

    private final ReceiptVerificationPolicy verificationPolicy;

    public ReceiptCaptureService(ReceiptVerificationPolicy verificationPolicy) {
        this.verificationPolicy = verificationPolicy != null ? verificationPolicy : new ReceiptVerificationPolicy();
    }

    public record CaptureReceiptCommand(
            UUID profileId,
            UUID applicationId,
            UUID jobId,
            String jobTitle,
            String company,
            String applicationUrl,
            String atsPlatform,
            String employerConfirmationId,
            String receiptSha256,
            Instant submittedAt,
            boolean attestationConfirmed,
            String attestationActor,
            IdentityReceipt submittedIdentity,
            List<AnswerReceipt> submittedAnswers,
            DocumentReceipt cv,
            DocumentReceipt coverLetter,
            List<String> observedSignals,
            String summary,
            boolean sandboxPosting,
            boolean candidateAuthorizedSandbox,
            String rawEvidencePayload,
            int attemptNumber
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private UUID profileId;
            private UUID applicationId;
            private UUID jobId;
            private String jobTitle;
            private String company;
            private String applicationUrl;
            private String atsPlatform;
            private String employerConfirmationId;
            private String receiptSha256;
            private Instant submittedAt;
            private boolean attestationConfirmed;
            private String attestationActor = "CANDIDATE";
            private IdentityReceipt submittedIdentity;
            private List<AnswerReceipt> submittedAnswers = List.of();
            private DocumentReceipt cv;
            private DocumentReceipt coverLetter;
            private List<String> observedSignals = List.of();
            private String summary;
            private boolean sandboxPosting;
            private boolean candidateAuthorizedSandbox = true;
            private String rawEvidencePayload;
            private int attemptNumber = 1;

            public Builder profileId(UUID profileId) { this.profileId = profileId; return this; }
            public Builder applicationId(UUID applicationId) { this.applicationId = applicationId; return this; }
            public Builder jobId(UUID jobId) { this.jobId = jobId; return this; }
            public Builder jobTitle(String jobTitle) { this.jobTitle = jobTitle; return this; }
            public Builder company(String company) { this.company = company; return this; }
            public Builder applicationUrl(String applicationUrl) { this.applicationUrl = applicationUrl; return this; }
            public Builder atsPlatform(String atsPlatform) { this.atsPlatform = atsPlatform; return this; }
            public Builder employerConfirmationId(String id) { this.employerConfirmationId = id; return this; }
            public Builder receiptSha256(String sha256) { this.receiptSha256 = sha256; return this; }
            public Builder submittedAt(Instant submittedAt) { this.submittedAt = submittedAt; return this; }
            public Builder attestationConfirmed(boolean confirmed) { this.attestationConfirmed = confirmed; return this; }
            public Builder attestationActor(String actor) { this.attestationActor = actor; return this; }
            public Builder submittedIdentity(IdentityReceipt identity) { this.submittedIdentity = identity; return this; }
            public Builder submittedAnswers(List<AnswerReceipt> answers) { this.submittedAnswers = answers; return this; }
            public Builder cv(DocumentReceipt cv) { this.cv = cv; return this; }
            public Builder coverLetter(DocumentReceipt coverLetter) { this.coverLetter = coverLetter; return this; }
            public Builder observedSignals(List<String> signals) { this.observedSignals = signals; return this; }
            public Builder summary(String summary) { this.summary = summary; return this; }
            public Builder sandboxPosting(boolean sandbox) { this.sandboxPosting = sandbox; return this; }
            public Builder candidateAuthorizedSandbox(boolean auth) { this.candidateAuthorizedSandbox = auth; return this; }
            public Builder rawEvidencePayload(String payload) { this.rawEvidencePayload = payload; return this; }
            public Builder attemptNumber(int num) { this.attemptNumber = num; return this; }

            public CaptureReceiptCommand build() {
                return new CaptureReceiptCommand(profileId, applicationId, jobId, jobTitle, company,
                        applicationUrl, atsPlatform, employerConfirmationId, receiptSha256, submittedAt,
                        attestationConfirmed, attestationActor, submittedIdentity, submittedAnswers, cv,
                        coverLetter, observedSignals, summary, sandboxPosting, candidateAuthorizedSandbox,
                        rawEvidencePayload, attemptNumber);
            }
        }
    }

    public record GenuineReceiptRecord(
            SubmissionReceipt submissionReceipt,
            String receiptSha256,
            String employerConfirmationId,
            String atsPlatform,
            Instant verifiedAt,
            boolean genuine
    ) {}

    /**
     * Captures and validates a genuine application submission receipt against all 7 invariants.
     *
     * @param cmd capture command containing confirmation evidence and candidate attestation
     * @return genuine {@link SubmissionReceipt}
     * @throws InvalidReceiptException if any invariant is violated or mock prefix is detected
     */
    public SubmissionReceipt captureReceipt(CaptureReceiptCommand cmd) {
        if (cmd == null) {
            throw new InvalidReceiptException("CaptureReceiptCommand cannot be null", "MISSING_COMMAND");
        }

        ReceiptEvidence evidence = new ReceiptEvidence(
                cmd.employerConfirmationId(),
                cmd.receiptSha256(),
                cmd.atsPlatform(),
                cmd.submittedAt(),
                cmd.profileId(),
                cmd.applicationId(),
                cmd.attestationConfirmed(),
                cmd.sandboxPosting(),
                cmd.candidateAuthorizedSandbox(),
                cmd.observedSignals(),
                cmd.rawEvidencePayload()
        );

        // Enforce all 7 invariants & mock rejection
        verificationPolicy.validateOrThrow(evidence);

        UUID receiptId = UUID.randomUUID();
        String normalizedPlatform = cmd.atsPlatform().trim().toUpperCase(Locale.ROOT);
        String confirmationId = cmd.employerConfirmationId().trim();
        Instant submittedAt = cmd.submittedAt();
        int attemptNum = cmd.attemptNumber() > 0 ? cmd.attemptNumber() : 1;

        List<String> signals = cmd.observedSignals() != null && !cmd.observedSignals().isEmpty()
                ? new ArrayList<>(cmd.observedSignals())
                : List.of("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED");

        String summary = cmd.summary() != null && !cmd.summary().isBlank()
                ? cmd.summary().trim()
                : "Application successfully submitted and confirmed with genuine " + normalizedPlatform + " platform evidence.";

        ConfirmationEvidence confirmation = new ConfirmationEvidence(
                true,
                confirmationId,
                signals,
                summary
        );

        AuthorizationRecord authorization = new AuthorizationRecord(
                true,
                "EXPLICIT_CANDIDATE_APPROVAL",
                submittedAt,
                cmd.attestationActor() != null ? cmd.attestationActor() : "CANDIDATE"
        );

        IdentityReceipt identity = cmd.submittedIdentity() != null
                ? cmd.submittedIdentity()
                : new IdentityReceipt(null, null, null, null, null, null, null);

        List<AnswerReceipt> answers = cmd.submittedAnswers() != null
                ? cmd.submittedAnswers()
                : List.of();

        String idempotencyKey = "receipt-sha256:" + cmd.receiptSha256().trim().toLowerCase(Locale.ROOT);

        AttemptSummary summaryItem = new AttemptSummary(
                receiptId,
                attemptNum,
                normalizedPlatform,
                idempotencyKey,
                "SUBMITTED",
                true,
                submittedAt,
                submittedAt,
                true
        );

        SubmissionReceipt receipt = new SubmissionReceipt(
                receiptId,
                cmd.applicationId(),
                cmd.jobId(),
                cmd.jobTitle(),
                cmd.company(),
                cmd.applicationUrl(),
                normalizedPlatform,
                attemptNum,
                true,
                idempotencyKey,
                "SUBMITTED",
                true,
                submittedAt,
                submittedAt,
                submittedAt,
                authorization,
                identity,
                answers,
                cmd.cv(),
                cmd.coverLetter(),
                confirmation,
                new ReconciliationDetails(false, null, null),
                new FailureDetails(false, null, null),
                List.of(summaryItem)
        );

        log.info("Captured genuine submission receipt for app={} platform={} confId={} sha256={}",
                cmd.applicationId(), normalizedPlatform, confirmationId, cmd.receiptSha256());

        return receipt;
    }

    /**
     * Captures and validates genuine receipt returning a detailed {@link GenuineReceiptRecord}.
     */
    public GenuineReceiptRecord captureGenuineReceipt(CaptureReceiptCommand cmd) {
        SubmissionReceipt receipt = captureReceipt(cmd);
        return new GenuineReceiptRecord(
                receipt,
                cmd.receiptSha256(),
                cmd.employerConfirmationId(),
                cmd.atsPlatform().trim().toUpperCase(Locale.ROOT),
                Instant.now(),
                true
        );
    }

    /**
     * Verifies an existing submission receipt against genuine receipt policy.
     */
    public VerificationOutcome verifyExistingReceipt(SubmissionReceipt receipt, String receiptSha256,
                                                     UUID profileId, boolean sandboxPosting,
                                                     boolean candidateAuthorizedSandbox) {
        verificationPolicy.validateReceipt(receipt, receiptSha256, profileId, sandboxPosting, candidateAuthorizedSandbox);
        return verificationPolicy.evaluate(new ReceiptEvidence(
                receipt.confirmation() != null ? receipt.confirmation().confirmationId() : null,
                receiptSha256,
                receipt.atsKind(),
                receipt.submittedAt(),
                profileId,
                receipt.applicationId(),
                receipt.authorization() != null && receipt.authorization().approved(),
                sandboxPosting,
                candidateAuthorizedSandbox,
                receipt.confirmation() != null ? receipt.confirmation().observedSignals() : List.of(),
                null
        ));
    }

    /**
     * Helper to compute deterministic SHA-256 confirmation evidence digest.
     */
    public static String computeReceiptSha256(String employerConfirmationId, String atsPlatform,
                                             Instant submittedAt, UUID profileId, UUID applicationId,
                                             boolean attestationConfirmed, List<String> observedSignals) {
        String canonicalPayload = ReceiptVerificationPolicy.buildCanonicalEvidencePayload(
                employerConfirmationId,
                atsPlatform,
                submittedAt,
                profileId,
                applicationId,
                attestationConfirmed,
                observedSignals
        );
        return ReceiptVerificationPolicy.computeEvidenceSha256(canonicalPayload);
    }
}
