package com.personal.jobagent.track.receipt;

import com.personal.jobagent.track.ApplicationReceiptService.AnswerReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.DocumentReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.IdentityReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.SubmissionReceipt;
import com.personal.jobagent.track.receipt.ReceiptCaptureService.CaptureReceiptCommand;
import com.personal.jobagent.track.receipt.ReceiptTrustStore.WorkerEvidence;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy.ReceiptEvidence;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy.VerificationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Receipt capture and verification (Wave 3 Sprint 2.2.1).
 *
 * <p>Every trust input — plan ownership, candidate approval, worker provenance,
 * sandbox registration and durable storage — comes from
 * {@link InMemoryReceiptTrustFixture}, a test-only stand-in for the database.
 * The command carries evidence and display data only. {@link #workerRecorded}
 * models what the authenticated worker completion path stores, so the policy
 * invariants below are exercised through the full capture path.
 */
class ReceiptVerificationTest {

    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID APPLICATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID JOB_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PLAN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-11-20T14:30:00Z");
    private static final Instant APPROVED_AT = SUBMITTED_AT.minus(Duration.ofMinutes(10));
    private static final String TARGET_URL = "https://acme.wd5.myworkdayjobs.com/en-US/RobinSandbox/job/robin-sandbox/apply";
    private static final String SANDBOX_DOMAIN = "myworkdayjobs.com";
    private static final List<String> PLATFORMS = List.of("GREENHOUSE", "WORKDAY", "ASHBY", "LEVER", "ZOHO_RECRUIT");

    public static final String CANONICAL_RAW_EVIDENCE = """
            {"domEvidence":"<div id='confirmation'>Thank you for applying. Confirmation: WD-CONF-987654</div>","screenshotSha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}
            """;

    private ReceiptVerificationPolicy policy;
    private InMemoryReceiptTrustFixture fixture;
    private ReceiptCaptureService captureService;

    @BeforeEach
    void setUp() {
        policy = new ReceiptVerificationPolicy();
        fixture = new InMemoryReceiptTrustFixture()
                .plan(PROFILE_ID, APPLICATION_ID, PLAN_ID)
                .approval(PROFILE_ID, APPLICATION_ID, PLAN_ID, "ada@example.com", APPROVED_AT);
        for (String platform : PLATFORMS) {
            fixture.sandbox(PROFILE_ID, platform, TARGET_URL, SANDBOX_DOMAIN, null);
        }
        captureService = new ReceiptCaptureService(policy, fixture, fixture);
    }

    private static String validSha256() {
        return ReceiptVerificationPolicy.computeEvidenceSha256(CANONICAL_RAW_EVIDENCE);
    }

    private CaptureReceiptCommand.Builder validCommandBuilder() {
        return CaptureReceiptCommand.builder()
                .profileId(PROFILE_ID)
                .applicationId(APPLICATION_ID)
                .planId(PLAN_ID)
                .jobId(JOB_ID)
                .jobTitle("Senior Distributed Systems Engineer")
                .company("Acme Robotics")
                .applicationUrl(TARGET_URL)
                .atsPlatform("WORKDAY")
                .employerConfirmationId("WD-CONF-987654")
                .rawEvidencePayload(CANONICAL_RAW_EVIDENCE)
                .receiptSha256(validSha256())
                .submittedAt(SUBMITTED_AT)
                .submittedIdentity(new IdentityReceipt("Ada Lovelace", "Ada", "Lovelace", "ada@example.com", "+447700900123", "London", "https://linkedin.com/in/ada"))
                .submittedAnswers(List.of(new AnswerReceipt("Notice period?", "1 month")))
                .cv(new DocumentReceipt(UUID.randomUUID(), "Master CV", "PDF", "sha-cv-123", 45000L, "TAILORED_CV", "cv.pdf", true))
                .coverLetter(new DocumentReceipt(UUID.randomUUID(), "Cover Letter", "PDF", "sha-cl-456", 25000L, "COVER_LETTER", "cl.pdf", true))
                .observedSignals(List.of("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED"))
                .summary("Confirmed by Workday employer requisition acknowledgment.");
    }

    /** Stores, for the command's plan, exactly the evidence the authenticated worker completion path would. */
    private void workerRecorded(CaptureReceiptCommand cmd) {
        workerRecorded(cmd, "worker", SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE);
    }

    private void workerRecorded(CaptureReceiptCommand cmd, String principal, String recordedVia) {
        String raw = cmd.rawEvidencePayload();
        String sha = raw == null || raw.isBlank() ? null : ReceiptVerificationPolicy.computeEvidenceSha256(raw);
        fixture.workerEvidence(PROFILE_ID, APPLICATION_ID, cmd.planId(), new WorkerEvidence(
                UUID.randomUUID(), principal, recordedVia,
                cmd.atsPlatform() == null ? null : cmd.atsPlatform().trim().toUpperCase(),
                TARGET_URL, cmd.employerConfirmationId(),
                cmd.observedSignals() == null ? List.of() : cmd.observedSignals(),
                sha, sha == null ? "NOT_PROVIDED" : "STORED_ENCRYPTED", cmd.submittedAt()));
    }

    private SubmissionReceipt capture(CaptureReceiptCommand cmd) {
        workerRecorded(cmd);
        return captureService.captureReceipt(cmd);
    }

    private void assertRejected(Runnable call, String reasonCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(InvalidReceiptException.class)
                .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo(reasonCode));
        assertThat(fixture.receiptCount()).as("no receipt may be stored after a rejection").isZero();
    }

    private void assertCaptureRejected(CaptureReceiptCommand cmd, String reasonCode) {
        workerRecorded(cmd);
        assertRejected(() -> captureService.captureReceipt(cmd), reasonCode);
    }

    @Nested
    @DisplayName("Invariant 1: employer confirmation ID")
    class Invariant1EmployerConfirmationId {

        @Test
        void rejectsMissingConfirmationId() {
            assertCaptureRejected(validCommandBuilder().employerConfirmationId(null).build(), "MISSING_CONFIRMATION_ID");
        }

        @Test
        void rejectsBlankConfirmationId() {
            assertCaptureRejected(validCommandBuilder().employerConfirmationId("   ").build(), "MISSING_CONFIRMATION_ID");
        }

        @ParameterizedTest(name = "Rejects mock confirmation ID: {0}")
        @ValueSource(strings = {"MOCK-12345", "mock-wd-999", "TEST-CONF-001", "test-requisition-777", "SAMPLE-RECEIPT-42", "sample-app-123"})
        void rejectsMockConfirmationIdPrefixes(String mockId) {
            assertCaptureRejected(validCommandBuilder().employerConfirmationId(mockId).build(), "MOCK_CONFIRMATION_ID_REJECTED");
        }

        @ParameterizedTest(name = "Rejects requisition fallback: {0}")
        @ValueSource(strings = {"REQ-2026-9876", "req-workday-54321", "REQUISITION-12345", "JOB-REQ-9988", "RECEIPT-776655"})
        void rejectsRequisitionIdSubstitutedForConfirmationId(String requisitionId) {
            assertCaptureRejected(validCommandBuilder().employerConfirmationId(requisitionId).build(),
                    "REQUISITION_ID_AS_CONFIRMATION_REJECTED");
        }
    }

    @Nested
    @DisplayName("Invariant 2: raw evidence and receiptSha256")
    class Invariant2ReceiptSha256 {

        @Test
        void rejectsMissingReceiptSha256() {
            assertCaptureRejected(validCommandBuilder().receiptSha256(null).build(), "MISSING_RECEIPT_SHA256");
        }

        @ParameterizedTest(name = "Rejects invalid SHA-256: {0}")
        @ValueSource(strings = {"invalid-sha", "1234567890abcdef",
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"})
        void rejectsInvalidSha256Formats(String invalidSha) {
            assertCaptureRejected(validCommandBuilder().receiptSha256(invalidSha).build(), "INVALID_SHA256_FORMAT");
        }

        @Test
        void rejectsMockPrefixedSha256() {
            assertCaptureRejected(validCommandBuilder()
                    .receiptSha256("MOCK-11111111111111111111111111111111111111111111111111111111111111").build(),
                    "MOCK_SHA256_REJECTED");
        }

        @Test
        @DisplayName("An arbitrary valid-looking 64-char digest that is not the evidence's digest is rejected")
        void rejectsArbitraryValidLookingSha256() {
            assertCaptureRejected(validCommandBuilder().receiptSha256("a".repeat(64)).build(), "SHA256_DIGEST_MISMATCH");
        }

        @Test
        void rejectsMissingRawEvidence() {
            assertCaptureRejected(validCommandBuilder().rawEvidencePayload(null).build(), "MISSING_RAW_EVIDENCE");
            assertCaptureRejected(validCommandBuilder().rawEvidencePayload("   ").build(), "MISSING_RAW_EVIDENCE");
        }

        @Test
        void rejectsSummarySubstitution() {
            assertCaptureRejected(validCommandBuilder()
                    .rawEvidencePayload("Application successfully submitted to Acme Robotics").build(),
                    "SUMMARY_SUBSTITUTION_REJECTED");
        }
    }

    @Nested
    @DisplayName("Invariant 3: atsPlatform")
    class Invariant3AtsPlatform {

        @ParameterizedTest(name = "Accepts genuine platform: {0}")
        @ValueSource(strings = {"GREENHOUSE", "greenhouse", "WORKDAY", "workday", "ASHBY", "ashby", "LEVER", "lever", "ZOHO_RECRUIT", "zoho_recruit"})
        void acceptsAllGenuineAtsPlatforms(String platform) {
            SubmissionReceipt receipt = capture(validCommandBuilder().atsPlatform(platform).build());
            assertThat(receipt.atsKind()).isEqualTo(platform.toUpperCase());
            assertThat(receipt.confirmation().confirmed()).isTrue();
        }

        @ParameterizedTest(name = "Rejects platform: \"{0}\"")
        @ValueSource(strings = {"UNKNOWN", "NOT_ATTEMPTED", "CUSTOM_JOB_BOARD"})
        void rejectsUnsupportedPlatforms(String platform) {
            assertCaptureRejected(validCommandBuilder().atsPlatform(platform).build(), "UNSUPPORTED_ATS_PLATFORM");
        }

        @ParameterizedTest(name = "Rejects mock platform: \"{0}\"")
        @ValueSource(strings = {"MOCK-PLATFORM", "TEST-ATS", "SAMPLE-BOARD"})
        void rejectsMockPlatforms(String platform) {
            assertCaptureRejected(validCommandBuilder().atsPlatform(platform).build(), "MOCK_PLATFORM_REJECTED");
        }

        @Test
        void rejectsBlankPlatform() {
            assertCaptureRejected(validCommandBuilder().atsPlatform("").build(), "MISSING_ATS_PLATFORM");
        }
    }

    @Nested
    @DisplayName("Invariant 4: submittedAt comes from the worker completion record")
    class Invariant4SubmittedAt {

        @Test
        void rejectsMissingWorkerCompletionTime() {
            assertCaptureRejected(validCommandBuilder().submittedAt(null).build(), "MISSING_SUBMITTED_AT");
        }

        @Test
        void rejectsFutureDatedWorkerCompletionTime() {
            assertCaptureRejected(validCommandBuilder().submittedAt(Instant.now().plus(Duration.ofHours(2))).build(),
                    "FUTURE_SUBMITTED_AT");
        }
    }

    @Nested
    @DisplayName("Invariants 5 & 6: profile, application and plan binding")
    class OwnershipBinding {

        @Test
        void rejectsMissingIds() {
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder().profileId(null).build()), "MISSING_PROFILE_ID");
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder().applicationId(null).build()), "MISSING_APPLICATION_ID");
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder().planId(null).build()), "MISSING_PLAN_ID");
        }

        @Test
        @DisplayName("Another candidate's profile cannot capture a receipt for this plan")
        void rejectsCrossProfileCapture() {
            CaptureReceiptCommand cmd = validCommandBuilder().profileId(UUID.randomUUID()).build();
            workerRecorded(validCommandBuilder().build());
            assertRejected(() -> captureService.captureReceipt(cmd), "CROSS_PROFILE_RECEIPT_REJECTED");
        }

        @Test
        @DisplayName("A plan cannot be paired with a different application id")
        void rejectsCrossApplicationCapture() {
            CaptureReceiptCommand cmd = validCommandBuilder().applicationId(UUID.randomUUID()).build();
            workerRecorded(validCommandBuilder().build());
            assertRejected(() -> captureService.captureReceipt(cmd), "CROSS_PROFILE_RECEIPT_REJECTED");
        }

        @Test
        @DisplayName("A plan id that does not belong to the candidate is rejected")
        void rejectsWrongPlanId() {
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder().planId(UUID.randomUUID()).build()),
                    "CROSS_PROFILE_RECEIPT_REJECTED");
        }
    }

    @Nested
    @DisplayName("Invariant 7: candidate approval from the audit record only")
    class CandidateApproval {

        @Test
        void rejectsWhenNoApprovalRecordExists() {
            fixture.removeApproval(PROFILE_ID, APPLICATION_ID, PLAN_ID);
            assertCaptureRejected(validCommandBuilder().build(), "MISSING_CANDIDATE_ATTESTATION");
        }

        @Test
        @DisplayName("Approval of a different plan does not authorize this plan")
        void rejectsApprovalForAnotherPlan() {
            UUID otherPlan = UUID.randomUUID();
            fixture.removeApproval(PROFILE_ID, APPLICATION_ID, PLAN_ID);
            fixture.plan(PROFILE_ID, APPLICATION_ID, otherPlan)
                    .approval(PROFILE_ID, APPLICATION_ID, otherPlan, "ada@example.com", APPROVED_AT);
            assertCaptureRejected(validCommandBuilder().build(), "MISSING_CANDIDATE_ATTESTATION");
        }

        @Test
        @DisplayName("Approval recorded after the submission is not authorization for it")
        void rejectsApprovalAfterSubmission() {
            fixture.approval(PROFILE_ID, APPLICATION_ID, PLAN_ID, "ada@example.com", SUBMITTED_AT.plusSeconds(60));
            assertCaptureRejected(validCommandBuilder().build(), "APPROVAL_AFTER_SUBMISSION");
        }

        @Test
        @DisplayName("Authorization actor and time come from the verified approval record")
        void authorizationComesFromApprovalRecord() {
            SubmissionReceipt receipt = capture(validCommandBuilder().build());
            assertThat(receipt.authorization().approved()).isTrue();
            assertThat(receipt.authorization().actor()).isEqualTo("ada@example.com");
            assertThat(receipt.authorization().approvedAt()).isEqualTo(APPROVED_AT);
            assertThat(receipt.authorization().approvedAt()).isNotEqualTo(SUBMITTED_AT);
        }
    }

    @Nested
    @DisplayName("Provenance: evidence must be what the authenticated worker recorded")
    class WorkerProvenance {

        @Test
        void rejectsWhenWorkerRecordedNothing() {
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder().build()),
                    "UNAUTHENTICATED_EVIDENCE_PROVENANCE");
        }

        @Test
        void rejectsEvidenceRecordedByANonWorkerPrincipal() {
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            workerRecorded(cmd, "ada@example.com", SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE);
            assertRejected(() -> captureService.captureReceipt(cmd), "UNAUTHENTICATED_EVIDENCE_PROVENANCE");
        }

        @Test
        void rejectsEvidenceRecordedOutsideTheWorkerCompletionPath() {
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            workerRecorded(cmd, "worker", "CALLER_JSON");
            assertRejected(() -> captureService.captureReceipt(cmd), "UNAUTHENTICATED_EVIDENCE_PROVENANCE");
        }

        @Test
        @DisplayName("Evidence that differs from the worker's recorded evidence is not bound to the worker")
        void rejectsEvidenceNotRecordedByWorker() {
            workerRecorded(validCommandBuilder().build());
            String forged = CANONICAL_RAW_EVIDENCE.replace("WD-CONF-987654", "WD-CONF-000001");
            CaptureReceiptCommand cmd = validCommandBuilder().rawEvidencePayload(forged)
                    .receiptSha256(ReceiptVerificationPolicy.computeEvidenceSha256(forged)).build();
            assertRejected(() -> captureService.captureReceipt(cmd), "EVIDENCE_NOT_BOUND_TO_WORKER");
        }

        @Test
        void rejectsConfirmationIdDifferentFromWorkerRecord() {
            workerRecorded(validCommandBuilder().build());
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder().employerConfirmationId("WD-OTHER-1").build()),
                    "EVIDENCE_NOT_BOUND_TO_WORKER");
        }

        @Test
        void rejectsSignalsDifferentFromWorkerRecord() {
            workerRecorded(validCommandBuilder().build());
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder()
                            .observedSignals(List.of("PLATFORM_SUBMISSION_CONFIRMED", "INVENTED_SIGNAL")).build()),
                    "EVIDENCE_NOT_BOUND_TO_WORKER");
        }

        @Test
        @DisplayName("Empty observed signals fail and are never replaced with a synthesized signal")
        void rejectsEmptySignalsWithoutSynthesis() {
            assertCaptureRejected(validCommandBuilder().observedSignals(List.of()).build(), "MISSING_CONFIRMATION_SIGNALS");
            assertCaptureRejected(validCommandBuilder().observedSignals(null).build(), "MISSING_CONFIRMATION_SIGNALS");
        }

        @Test
        @DisplayName("When the worker stored no raw evidence the receipt cannot be verified")
        void rejectsWhenWorkerStoredNoRawEvidence() {
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            fixture.workerEvidence(PROFILE_ID, APPLICATION_ID, PLAN_ID, new WorkerEvidence(UUID.randomUUID(), "worker",
                    SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE, "WORKDAY", TARGET_URL, "WD-CONF-987654",
                    cmd.observedSignals(), null, "ENCRYPTION_UNAVAILABLE", SUBMITTED_AT));
            assertRejected(() -> captureService.captureReceipt(cmd), "MISSING_RAW_EVIDENCE");
        }

        @Test
        @DisplayName("The command has no field through which a caller could assert provenance, approval or sandbox status")
        void commandCarriesNoSelfAuthenticatingFields() {
            Set<String> components = Arrays.stream(CaptureReceiptCommand.class.getRecordComponents())
                    .map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
            assertThat(components).doesNotContain("evidenceProvenance", "sandboxPosting", "candidateAuthorizedSandbox",
                    "attestationConfirmed", "attestationActor", "auditAttestationId");
        }
    }

    @Nested
    @DisplayName("Candidate-owned sandbox, verified from server-side registration")
    class SandboxRegistration {

        @Test
        void acceptsRegisteredCandidateSandbox() {
            assertThat(capture(validCommandBuilder().build()).confirmation().confirmed()).isTrue();
        }

        @Test
        void rejectsWhenNoSandboxIsRegistered() {
            fixture = new InMemoryReceiptTrustFixture().plan(PROFILE_ID, APPLICATION_ID, PLAN_ID)
                    .approval(PROFILE_ID, APPLICATION_ID, PLAN_ID, "ada@example.com", APPROVED_AT);
            captureService = new ReceiptCaptureService(policy, fixture, fixture);
            assertCaptureRejected(validCommandBuilder().build(), "NON_SANDBOX_RECEIPT_REJECTED");
        }

        @Test
        void rejectsRevokedSandboxRegistration() {
            fixture.sandbox(PROFILE_ID, "WORKDAY", TARGET_URL, SANDBOX_DOMAIN, Instant.parse("2026-11-10T00:00:00Z"));
            assertCaptureRejected(validCommandBuilder().build(), "NON_SANDBOX_RECEIPT_REJECTED");
        }

        @Test
        void rejectsPostingOutsideThePermittedDomain() {
            fixture.sandbox(PROFILE_ID, "WORKDAY", TARGET_URL, "greenhouse.io", null);
            assertCaptureRejected(validCommandBuilder().build(), "NON_SANDBOX_RECEIPT_REJECTED");
        }

        @Test
        @DisplayName("Another candidate's sandbox registration does not authorize this candidate")
        void rejectsAnotherCandidatesRegistration() {
            fixture = new InMemoryReceiptTrustFixture().plan(PROFILE_ID, APPLICATION_ID, PLAN_ID)
                    .approval(PROFILE_ID, APPLICATION_ID, PLAN_ID, "ada@example.com", APPROVED_AT)
                    .sandbox(UUID.randomUUID(), "WORKDAY", TARGET_URL, SANDBOX_DOMAIN, null);
            captureService = new ReceiptCaptureService(policy, fixture, fixture);
            assertCaptureRejected(validCommandBuilder().build(), "NON_SANDBOX_RECEIPT_REJECTED");
        }

        @Test
        void hostCheckRequiresHttpsAndExactDomainBoundary() {
            assertThat(ReceiptCaptureService.hostWithinDomain("https://acme.wd5.myworkdayjobs.com/x", "myworkdayjobs.com")).isTrue();
            assertThat(ReceiptCaptureService.hostWithinDomain("https://myworkdayjobs.com/x", "myworkdayjobs.com")).isTrue();
            assertThat(ReceiptCaptureService.hostWithinDomain("http://acme.wd5.myworkdayjobs.com/x", "myworkdayjobs.com")).isFalse();
            assertThat(ReceiptCaptureService.hostWithinDomain("https://evilmyworkdayjobs.com/x", "myworkdayjobs.com")).isFalse();
            assertThat(ReceiptCaptureService.hostWithinDomain("https://myworkdayjobs.com.evil.io/x", "myworkdayjobs.com")).isFalse();
        }
    }

    @Nested
    @DisplayName("Durable persistence, replay and concurrency")
    class Durability {

        @Test
        @DisplayName("Without a durable store and trusted state, capture fails closed")
        void failsClosedWithoutStore() {
            ReceiptCaptureService unwired = new ReceiptCaptureService(policy);
            assertThatThrownBy(() -> unwired.captureReceipt(validCommandBuilder().build()))
                    .isInstanceOf(InvalidReceiptException.class)
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode())
                            .isEqualTo("RECEIPT_PERSISTENCE_UNAVAILABLE"));
            assertThat(unwired.findVerifiedReceipt(PROFILE_ID, PLAN_ID)).isEmpty();
        }

        @Test
        @DisplayName("A database failure while reading trusted state cannot produce a receipt")
        void failsClosedWhenTrustedStateUnavailable() {
            workerRecorded(validCommandBuilder().build());
            fixture.failTrustReads.set(true);
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder().build()), "TRUST_STATE_UNAVAILABLE");
        }

        @Test
        @DisplayName("A database failure during persistence cannot produce a receipt")
        void failsClosedWhenPersistenceFails() {
            workerRecorded(validCommandBuilder().build());
            fixture.failPersistence.set(true);
            assertRejected(() -> captureService.captureReceipt(validCommandBuilder().build()), "RECEIPT_PERSISTENCE_FAILED");
            assertThat(fixture.auditRows.get()).isZero();
        }

        @Test
        @DisplayName("A captured receipt is durable, server-identified and keyed by plan")
        void capturesCompleteSubmissionReceipt() {
            SubmissionReceipt receipt = capture(validCommandBuilder().build());

            assertThat(receipt.receiptId()).isNotNull().isNotEqualTo(PLAN_ID);
            assertThat(receipt.applicationId()).isEqualTo(APPLICATION_ID);
            assertThat(receipt.jobId()).isEqualTo(JOB_ID);
            assertThat(receipt.atsKind()).isEqualTo("WORKDAY");
            assertThat(receipt.status()).isEqualTo("SUBMITTED");
            assertThat(receipt.submitted()).isTrue();
            assertThat(receipt.submittedAt()).isEqualTo(SUBMITTED_AT);
            assertThat(receipt.confirmation().confirmed()).isTrue();
            assertThat(receipt.confirmation().confirmationId()).isEqualTo("WD-CONF-987654");
            assertThat(receipt.confirmation().observedSignals()).containsExactlyInAnyOrder("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED");
            assertThat(receipt.idempotencyKey()).isEqualTo("receipt:plan:" + PLAN_ID);
            assertThat(receipt.submittedIdentity().name()).isEqualTo("Ada Lovelace");
            assertThat(receipt.cv().sha256()).isEqualTo("sha-cv-123");
            assertThat(receipt.reconciliation().required()).isFalse();
            assertThat(receipt.failure().failed()).isFalse();
            assertThat(receipt.attemptHistory()).singleElement().satisfies(a -> assertThat(a.attemptId()).isEqualTo(PLAN_ID));
            assertThat(fixture.receiptCount()).isEqualTo(1);
            assertThat(fixture.auditRows.get()).isEqualTo(1);
            assertThat(captureService.findVerifiedReceipt(PROFILE_ID, PLAN_ID)).isPresent();
        }

        @Test
        void captureGenuineReceiptReturnsStoredIdentity() {
            workerRecorded(validCommandBuilder().build());
            var record = captureService.captureGenuineReceipt(validCommandBuilder().build());
            assertThat(record.genuine()).isTrue();
            assertThat(record.storedReceiptId()).isEqualTo(record.submissionReceipt().receiptId());
            assertThat(record.employerConfirmationId()).isEqualTo("WD-CONF-987654");
            assertThat(record.atsPlatform()).isEqualTo("WORKDAY");
            assertThat(record.receiptSha256()).isEqualTo(validSha256());
            assertThat(record.verifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("Replaying identical evidence returns the same stored receipt without a second insert")
        void identicalReplayIsIdempotent() {
            SubmissionReceipt first = capture(validCommandBuilder().build());
            SubmissionReceipt second = captureService.captureReceipt(validCommandBuilder().build());
            assertThat(second.receiptId()).isEqualTo(first.receiptId());
            assertThat(fixture.inserts.get()).isEqualTo(1);
            assertThat(captureService.isReplayConflicting(PROFILE_ID, PLAN_ID, validSha256(), "WD-CONF-987654")).isFalse();
        }

        @Test
        @DisplayName("Replaying conflicting evidence for an already-captured plan is rejected")
        void conflictingReplayIsRejected() {
            capture(validCommandBuilder().build());
            String other = CANONICAL_RAW_EVIDENCE.replace("WD-CONF-987654", "WD-CONF-111111");
            CaptureReceiptCommand conflicting = validCommandBuilder().rawEvidencePayload(other)
                    .receiptSha256(ReceiptVerificationPolicy.computeEvidenceSha256(other))
                    .employerConfirmationId("WD-CONF-111111").build();
            assertThatThrownBy(() -> captureService.captureReceipt(conflicting))
                    .isInstanceOf(InvalidReceiptException.class)
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("RECEIPT_REPLAY_REJECTED"));
            assertThat(fixture.inserts.get()).isEqualTo(1);
            assertThat(captureService.isReplayConflicting(PROFILE_ID, PLAN_ID,
                    ReceiptVerificationPolicy.computeEvidenceSha256(other), "WD-CONF-111111")).isTrue();
        }

        @Test
        @DisplayName("Concurrent duplicate captures store exactly one receipt")
        void concurrentDuplicateCapturesStoreOneReceipt() throws Exception {
            workerRecorded(validCommandBuilder().build());
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<UUID> task = () -> {
                    start.await();
                    return captureService.captureReceipt(validCommandBuilder().build()).receiptId();
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            Set<UUID> ids = new java.util.HashSet<>();
            for (Future<UUID> f : futures) {
                ids.add(f.get(10, TimeUnit.SECONDS));
            }
            pool.shutdownNow();
            assertThat(ids).hasSize(1);
            assertThat(fixture.inserts.get()).isEqualTo(1);
            assertThat(fixture.auditRows.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Receipt policy (pure validator)")
    class PolicyOnly {

        @Test
        void policyEvaluateReturnsStructuredReasons() {
            String sha = validSha256();
            ReceiptEvidence evidence = new ReceiptEvidence("WD-CONF-987654", sha, "WORKDAY", SUBMITTED_AT, PROFILE_ID,
                    APPLICATION_ID, true, true, true, List.of("PLATFORM_SUBMISSION_CONFIRMED"),
                    CANONICAL_RAW_EVIDENCE, "AUTHENTICATED_BROWSER_WORKER");
            VerificationOutcome outcome = policy.evaluate(evidence);
            assertThat(outcome.verified()).isTrue();
            assertThat(outcome.receiptSha256()).isEqualTo(sha);
            assertThat(outcome.reasons()).extracting(r -> r.get("code"))
                    .contains("ALL_7_INVARIANTS_VERIFIED", "CANDIDATE_ATTESTATION_CONFIRMED", "ZERO_MOCK_PREFIXES_VERIFIED",
                            "CANDIDATE_AUTHORIZED_SANDBOX");
        }

        @Test
        void policyRejectsUnauthenticatedProvenanceString() {
            ReceiptEvidence evidence = new ReceiptEvidence("WD-CONF-987654", validSha256(), "WORKDAY", SUBMITTED_AT,
                    PROFILE_ID, APPLICATION_ID, true, true, true, List.of("PLATFORM_SUBMISSION_CONFIRMED"),
                    CANONICAL_RAW_EVIDENCE, "CALLER_CLAIMED_WORKER");
            assertThatThrownBy(() -> policy.validateOrThrow(evidence))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Unauthenticated or untrusted evidence provenance rejected");
        }
    }
}
