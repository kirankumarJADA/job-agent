package com.personal.jobagent.track.receipt;

import com.personal.jobagent.track.ApplicationReceiptService.AnswerReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.DocumentReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.IdentityReceipt;
import com.personal.jobagent.track.ApplicationReceiptService.SubmissionReceipt;
import com.personal.jobagent.track.receipt.ReceiptCaptureService.CaptureReceiptCommand;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy.ReceiptEvidence;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy.VerificationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 20 / Wave 3 Agent A: Comprehensive Receipt Verification & Invariant Tests.
 *
 * <p>Verifies:
 * <ul>
 *   <li>All 7 genuine receipt invariants are strictly enforced.</li>
 *   <li>Zero synthetic or mock receipts in production code paths (MOCK-*, TEST-*, SAMPLE-* prefixes rejected with 422 / InvalidReceiptException).</li>
 *   <li>Candidate-authorised sandbox restriction: real submissions verified only in candidate-owned sandbox/test posting.</li>
 *   <li>Deterministic SHA-256 confirmation evidence hashing.</li>
 *   <li>Re-use of SubmissionReceipt, ConfirmationEvidence, and DocumentReceipt types.</li>
 * </ul>
 */
class ReceiptVerificationTest {

    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID APPLICATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID JOB_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-11-20T14:30:00Z");

    public static final String CANONICAL_RAW_EVIDENCE = """
            {"domEvidence":"<div id='confirmation'>Thank you for applying. Confirmation: WD-CONF-987654</div>","screenshotSha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}
            """;

    private ReceiptVerificationPolicy policy;
    private ReceiptCaptureService captureService;

    @BeforeEach
    void setUp() {
        policy = new ReceiptVerificationPolicy();
        captureService = new ReceiptCaptureService(policy);
        captureService.recordApplicationOwnership(APPLICATION_ID, PROFILE_ID);
        captureService.recordAuditAttestation(APPLICATION_ID, PROFILE_ID, "ada@example.com");
        captureService.recordVerifiedProvenance(APPLICATION_ID, "AUTHENTICATED_BROWSER_WORKER");
    }

    private static String validSha256() {
        return ReceiptVerificationPolicy.computeEvidenceSha256(CANONICAL_RAW_EVIDENCE);
    }

    private CaptureReceiptCommand.Builder validCommandBuilder() {
        String sha256 = validSha256();
        return CaptureReceiptCommand.builder()
                .profileId(PROFILE_ID)
                .applicationId(APPLICATION_ID)
                .jobId(JOB_ID)
                .jobTitle("Senior Distributed Systems Engineer")
                .company("Acme Robotics")
                .applicationUrl("https://acme.wd5.myworkdayjobs.com/apply")
                .atsPlatform("WORKDAY")
                .employerConfirmationId("WD-CONF-987654")
                .rawEvidencePayload(CANONICAL_RAW_EVIDENCE)
                .receiptSha256(sha256)
                .submittedAt(SUBMITTED_AT)
                .attestationConfirmed(true)
                .attestationActor("ada@example.com")
                .submittedIdentity(new IdentityReceipt("Ada Lovelace", "Ada", "Lovelace", "ada@example.com", "+447700900123", "London", "https://linkedin.com/in/ada"))
                .submittedAnswers(List.of(new AnswerReceipt("Notice period?", "1 month")))
                .cv(new DocumentReceipt(UUID.randomUUID(), "Master CV", "PDF", "sha-cv-123", 45000L, "TAILORED_CV", "cv.pdf", true))
                .coverLetter(new DocumentReceipt(UUID.randomUUID(), "Cover Letter", "PDF", "sha-cl-456", 25000L, "COVER_LETTER", "cl.pdf", true))
                .observedSignals(List.of("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED"))
                .summary("Confirmed by Workday employer requisition acknowledgment.")
                .sandboxPosting(true)
                .candidateAuthorizedSandbox(true)
                .evidenceProvenance("AUTHENTICATED_BROWSER_WORKER");
    }

    @Nested
    @DisplayName("Genuine Receipt Invariant 1: Employer Confirmation ID")
    class Invariant1EmployerConfirmationId {

        @Test
        @DisplayName("Rejects null or blank employer confirmation ID")
        void rejectsMissingConfirmationId() {
            var cmdNull = validCommandBuilder().employerConfirmationId(null).build();
            var cmdBlank = validCommandBuilder().employerConfirmationId("   ").build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmdNull))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Employer confirmation ID is required");

            assertThatThrownBy(() -> captureService.captureReceipt(cmdBlank))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Employer confirmation ID is required");
        }

        @ParameterizedTest(name = "Rejects mock confirmation ID: {0}")
        @ValueSource(strings = {
                "MOCK-12345",
                "mock-wd-999",
                "TEST-CONF-001",
                "test-requisition-777",
                "SAMPLE-RECEIPT-42",
                "sample-app-123"
        })
        void rejectsMockConfirmationIdPrefixes(String mockId) {
            var cmd = validCommandBuilder().employerConfirmationId(mockId).build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Mock or synthetic confirmation ID prefix is rejected");
        }
    }

    @Nested
    @DisplayName("Genuine Receipt Invariant 2: receiptSha256")
    class Invariant2ReceiptSha256 {

        @Test
        @DisplayName("Rejects null or blank receiptSha256")
        void rejectsMissingReceiptSha256() {
            var cmdNull = validCommandBuilder().receiptSha256(null).build();
            var cmdBlank = validCommandBuilder().receiptSha256("  ").build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmdNull))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("receiptSha256 is required");

            assertThatThrownBy(() -> captureService.captureReceipt(cmdBlank))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("receiptSha256 is required");
        }

        @ParameterizedTest(name = "Rejects invalid sha256 format: {0}")
        @ValueSource(strings = {
                "invalid-sha",
                "1234567890abcdef", // too short (16 chars)
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", // non-hex
                "MOCK-11111111111111111111111111111111111111111111111111111111111111"
        })
        void rejectsInvalidSha256Formats(String invalidSha) {
            var cmd = validCommandBuilder().receiptSha256(invalidSha).build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class);
        }

        @Test
        @DisplayName("Rejects mismatching raw evidence payload")
        void rejectsMismatchingRawEvidencePayload() {
            String payload = "{\"tampered\":\"payload\"}";
            var cmd = validCommandBuilder()
                    .rawEvidencePayload(payload)
                    .receiptSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("receiptSha256 does not match canonical evidence");
        }
    }

    @Nested
    @DisplayName("Genuine Receipt Invariant 3: atsPlatform")
    class Invariant3AtsPlatform {

        @ParameterizedTest(name = "Admits genuine ATS platform: {0}")
        @ValueSource(strings = {
                "GREENHOUSE",
                "greenhouse",
                "WORKDAY",
                "workday",
                "ASHBY",
                "ashby",
                "LEVER",
                "lever",
                "ZOHO_RECRUIT",
                "zoho_recruit"
        })
        void acceptsAllGenuineAtsPlatforms(String platform) {
            var cmd = validCommandBuilder()
                    .atsPlatform(platform)
                    .employerConfirmationId("CONF-123456")
                    .rawEvidencePayload(CANONICAL_RAW_EVIDENCE)
                    .receiptSha256(validSha256())
                    .observedSignals(List.of("PLATFORM_SUBMISSION_CONFIRMED"))
                    .build();

            SubmissionReceipt receipt = captureService.captureReceipt(cmd);
            assertThat(receipt.atsKind()).isEqualTo(platform.trim().toUpperCase());
            assertThat(receipt.submitted()).isTrue();
        }

        @ParameterizedTest(name = "Rejects invalid or mock ATS platform: {0}")
        @ValueSource(strings = {
                "",
                "UNKNOWN",
                "NOT_ATTEMPTED",
                "MOCK-PLATFORM",
                "TEST-ATS",
                "SAMPLE-BOARD",
                "CUSTOM_JOB_BOARD"
        })
        void rejectsInvalidOrMockPlatforms(String invalidPlatform) {
            var cmd = validCommandBuilder().atsPlatform(invalidPlatform).build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class);
        }
    }

    @Nested
    @DisplayName("Genuine Receipt Invariant 4: submittedAt")
    class Invariant4SubmittedAt {

        @Test
        @DisplayName("Rejects null submittedAt")
        void rejectsMissingSubmittedAt() {
            var cmd = validCommandBuilder().submittedAt(null).build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("submittedAt timestamp is required");
        }

        @Test
        @DisplayName("Rejects future-dated submittedAt")
        void rejectsFutureDatedSubmittedAt() {
            Instant future = Instant.now().plus(Duration.ofDays(2));
            var cmd = validCommandBuilder().submittedAt(future).build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("submittedAt timestamp cannot be in the future");
        }
    }

    @Nested
    @DisplayName("Genuine Receipt Invariants 5 & 6: profileId & applicationId")
    class Invariants5And6Identity {

        @Test
        @DisplayName("Rejects missing profileId")
        void rejectsMissingProfileId() {
            var cmd = validCommandBuilder().profileId(null).build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("profileId is required");
        }

        @Test
        @DisplayName("Rejects missing applicationId")
        void rejectsMissingApplicationId() {
            var cmd = validCommandBuilder().applicationId(null).build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("applicationId is required");
        }
    }

    @Nested
    @DisplayName("Genuine Receipt Invariant 7: attestationConfirmed")
    class Invariant7AttestationConfirmed {

        @Test
        @DisplayName("Rejects receipt when candidate attestation is unconfirmed")
        void rejectsUnconfirmedAttestation() {
            var cmd = validCommandBuilder().attestationConfirmed(false).build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Candidate attestation record is missing or not confirmed");
        }
    }

    @Nested
    @DisplayName("Candidate-Authorised Sandbox Restriction")
    class CandidateAuthorisedSandboxRestriction {

        @Test
        @DisplayName("Accepts candidate-authorized sandbox test posting")
        void acceptsCandidateAuthorizedSandbox() {
            var cmd = validCommandBuilder()
                    .sandboxPosting(true)
                    .candidateAuthorizedSandbox(true)
                    .build();

            SubmissionReceipt receipt = captureService.captureReceipt(cmd);
            assertThat(receipt.status()).isEqualTo("SUBMITTED");
            assertThat(receipt.submitted()).isTrue();
        }

        @Test
        @DisplayName("Rejects real submission in sandbox posting without candidate authorization")
        void rejectsUnauthorizedSandboxPosting() {
            var cmd = validCommandBuilder()
                    .sandboxPosting(true)
                    .candidateAuthorizedSandbox(false)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("candidate-owned sandbox must be explicitly verified and authorized")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("UNAUTHORIZED_SANDBOX"));
        }
    }

    @Nested
    @DisplayName("End-to-End Receipt Capture and Verification")
    class EndToEndReceiptCapture {

        @Test
        @DisplayName("Successfully captures genuine receipt and populates all SubmissionReceipt fields")
        void capturesCompleteSubmissionReceipt() {
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            SubmissionReceipt receipt = captureService.captureReceipt(cmd);

            assertThat(receipt.applicationId()).isEqualTo(APPLICATION_ID);
            assertThat(receipt.jobId()).isEqualTo(JOB_ID);
            assertThat(receipt.jobTitle()).isEqualTo("Senior Distributed Systems Engineer");
            assertThat(receipt.company()).isEqualTo("Acme Robotics");
            assertThat(receipt.atsKind()).isEqualTo("WORKDAY");
            assertThat(receipt.status()).isEqualTo("SUBMITTED");
            assertThat(receipt.submitted()).isTrue();
            assertThat(receipt.submittedAt()).isEqualTo(SUBMITTED_AT);

            // Invariant 1 in confirmation evidence
            assertThat(receipt.confirmation().confirmed()).isTrue();
            assertThat(receipt.confirmation().confirmationId()).isEqualTo("WD-CONF-987654");
            assertThat(receipt.confirmation().observedSignals()).contains("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED");

            // Invariant 2 in idempotency key
            assertThat(receipt.idempotencyKey()).startsWith("receipt-sha256:");

            // Invariant 7 in authorization
            assertThat(receipt.authorization().approved()).isTrue();
            assertThat(receipt.authorization().policy()).isEqualTo("EXPLICIT_CANDIDATE_APPROVAL");
            assertThat(receipt.authorization().actor()).isEqualTo("ada@example.com");

            // Submitted identity and documents preserved
            assertThat(receipt.submittedIdentity().name()).isEqualTo("Ada Lovelace");
            assertThat(receipt.cv().sha256()).isEqualTo("sha-cv-123");
            assertThat(receipt.coverLetter().sha256()).isEqualTo("sha-cl-456");

            // Reconciliation and failure are clean
            assertThat(receipt.reconciliation().required()).isFalse();
            assertThat(receipt.failure().failed()).isFalse();

            // Attempt history recorded
            assertThat(receipt.attemptHistory()).hasSize(1);
            assertThat(receipt.attemptHistory().get(0).status()).isEqualTo("SUBMITTED");
            assertThat(receipt.attemptHistory().get(0).isLatest()).isTrue();
        }

        @Test
        @DisplayName("CaptureGenuineReceipt returns record with verification timestamp")
        void capturesGenuineReceiptRecord() {
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            var record = captureService.captureGenuineReceipt(cmd);

            assertThat(record.genuine()).isTrue();
            assertThat(record.employerConfirmationId()).isEqualTo("WD-CONF-987654");
            assertThat(record.atsPlatform()).isEqualTo("WORKDAY");
            assertThat(record.verifiedAt()).isNotNull();
            assertThat(record.submissionReceipt()).isNotNull();
        }

        @Test
        @DisplayName("Policy evaluate method outputs structured reasons and verification outcome")
        void policyEvaluateReturnsStructuredReasons() {
            String sha = validSha256();
            ReceiptEvidence evidence = new ReceiptEvidence(
                    "WD-CONF-987654",
                    sha,
                    "WORKDAY",
                    SUBMITTED_AT,
                    PROFILE_ID,
                    APPLICATION_ID,
                    true,
                    true,
                    true,
                    List.of("PLATFORM_SUBMISSION_CONFIRMED"),
                    CANONICAL_RAW_EVIDENCE,
                    "AUTHENTICATED_BROWSER_WORKER"
            );

            VerificationOutcome outcome = policy.evaluate(evidence);
            assertThat(outcome.verified()).isTrue();
            assertThat(outcome.receiptSha256()).isEqualTo(sha);
            assertThat(outcome.reasons()).extracting(r -> r.get("code"))
                    .contains("ALL_7_INVARIANTS_VERIFIED", "CANDIDATE_ATTESTATION_CONFIRMED", "ZERO_MOCK_PREFIXES_VERIFIED", "CANDIDATE_AUTHORIZED_SANDBOX");
        }

        @Test
        @DisplayName("Verifies existing SubmissionReceipt against policy with explicit provenance")
        void verifiesExistingSubmissionReceipt() {
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            SubmissionReceipt receipt = captureService.captureReceipt(cmd);

            var outcome = captureService.verifyExistingReceipt(receipt, cmd.receiptSha256(), CANONICAL_RAW_EVIDENCE, PROFILE_ID, true, true, "AUTHENTICATED_BROWSER_WORKER");
            assertThat(outcome.verified()).isTrue();
        }

        @Test
        @DisplayName("verifyExistingReceipt without provenance fails closed")
        void verifyExistingReceiptWithoutProvenanceFailsClosed() {
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            SubmissionReceipt receipt = captureService.captureReceipt(cmd);

            assertThatThrownBy(() -> captureService.verifyExistingReceipt(receipt, cmd.receiptSha256(), CANONICAL_RAW_EVIDENCE, PROFILE_ID, true, true))
                    .isInstanceOf(InvalidReceiptException.class)
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_EVIDENCE_PROVENANCE"));
        }
    }

    @Nested
    @DisplayName("Adversarial Receipt Security Tests (P0 Remediation & Gate Suite)")
    class AdversarialReceiptSecurityTests {

        @Test
        @DisplayName("1. Adversarial: Missing raw confirmation evidence is rejected")
        void testMissingRawEvidenceIsRejected() {
            var cmdNull = validCommandBuilder().rawEvidencePayload(null).build();
            var cmdBlank = validCommandBuilder().rawEvidencePayload("   ").build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmdNull))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Confirmation evidence is required")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_RAW_EVIDENCE"));

            assertThatThrownBy(() -> captureService.captureReceipt(cmdBlank))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Confirmation evidence is required")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_RAW_EVIDENCE"));
        }

        @Test
        @DisplayName("2. Adversarial: Arbitrary valid-looking 64-char SHA-256 is rejected without matching raw evidence")
        void testArbitraryValidLookingSha256IsRejected() {
            // Valid 64-char hex format, but fabricated and detached from actual raw evidence
            String arbitrarySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
            var cmd = validCommandBuilder()
                    .rawEvidencePayload(CANONICAL_RAW_EVIDENCE)
                    .receiptSha256(arbitrarySha)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Caller-supplied receiptSha256 does not match canonical evidence digest")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("SHA256_DIGEST_MISMATCH"));
        }

        @Test
        @DisplayName("3. Adversarial: Tampered confirmation evidence is detected and rejected")
        void testTamperedEvidenceIsRejected() {
            String tamperedEvidence = CANONICAL_RAW_EVIDENCE + "<!-- attacker injected payload -->";
            var cmd = validCommandBuilder()
                    .rawEvidencePayload(tamperedEvidence)
                    .receiptSha256(validSha256()) // Reusing original digest
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Caller-supplied receiptSha256 does not match canonical evidence digest")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("SHA256_DIGEST_MISMATCH"));
        }

        @Test
        @DisplayName("4. Adversarial: Non-sandbox receipt is rejected during verification phase")
        void testNonSandboxReceiptIsRejected() {
            var cmd = validCommandBuilder()
                    .sandboxPosting(false)
                    .candidateAuthorizedSandbox(true)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("real submissions are disabled. Receipts are only accepted for candidate-owned sandbox postings")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("NON_SANDBOX_RECEIPT_REJECTED"));
        }

        @Test
        @DisplayName("5. Adversarial: Unauthorised sandbox posting is rejected")
        void testUnauthorizedSandboxIsRejected() {
            var cmd = validCommandBuilder()
                    .sandboxPosting(true)
                    .candidateAuthorizedSandbox(false) // Candidate has not authorized this sandbox
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("candidate-owned sandbox must be explicitly verified and authorized")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("UNAUTHORIZED_SANDBOX"));
        }

        @Test
        @DisplayName("6. Adversarial: Missing candidate attestation / audit approval is rejected")
        void testMissingCandidateAttestationIsRejected() {
            var cmd = validCommandBuilder()
                    .attestationConfirmed(false)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Candidate attestation record is missing or not confirmed in audit log")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_CANDIDATE_ATTESTATION"));
        }

        @ParameterizedTest(name = "7. Adversarial: Requisition ID fallback is rejected: {0}")
        @ValueSource(strings = {
                "REQ-2026-9876",
                "req-workday-54321",
                "REQUISITION-12345",
                "JOB-REQ-9988",
                "RECEIPT-776655"
        })
        void testRequisitionIdSubstitutedForConfirmationIdIsRejected(String requisitionId) {
            var cmd = validCommandBuilder()
                    .employerConfirmationId(requisitionId)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Requisition ID or receipt ID fallback cannot be substituted for employer confirmation ID")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("REQUISITION_ID_AS_CONFIRMATION_REJECTED"));
        }

        @Test
        @DisplayName("8. Adversarial: Cross-profile receipt injection is rejected")
        void testCrossProfileReceiptInjectionIsRejected() {
            UUID foreignProfile = UUID.fromString("99999999-9999-9999-9999-999999999999");
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            SubmissionReceipt receipt = captureService.captureReceipt(cmd);

            assertThatThrownBy(() -> policy.validateReceiptWithProfileOwnership(
                    receipt,
                    cmd.receiptSha256(),
                    CANONICAL_RAW_EVIDENCE,
                    PROFILE_ID,
                    foreignProfile, // Cross-profile caller
                    true,
                    true,
                    "AUTHENTICATED_BROWSER_WORKER"
            ))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Cross-profile receipt access rejected")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("CROSS_PROFILE_RECEIPT_REJECTED"));
        }
    }

    @Nested
    @DisplayName("Sprint 2.1 Adversarial Trust Boundary & Security Gate Tests")
    class AdversarialTrustBoundaryAndSecurityTests {

        @Test
        @DisplayName("1. Fabricated evidence digest mismatch is rejected")
        void testFabricatedEvidenceDigestMismatch() {
            String fakeDigest = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
            var cmd = validCommandBuilder()
                    .rawEvidencePayload(CANONICAL_RAW_EVIDENCE)
                    .receiptSha256(fakeDigest)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Caller-supplied receiptSha256 does not match canonical evidence digest")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("SHA256_DIGEST_MISMATCH"));
        }

        @Test
        @DisplayName("2. Forged approval without audit record is rejected")
        void testForgedApprovalWithoutAuditRecordIsRejected() {
            UUID forgedAppId = UUID.randomUUID();
            captureService.recordApplicationOwnership(forgedAppId, PROFILE_ID);
            // Notice: recordAuditAttestation is intentionally NOT called for forgedAppId

            var cmd = validCommandBuilder()
                    .applicationId(forgedAppId)
                    .attestationConfirmed(true) // Caller claims true!
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Candidate attestation record is missing or not confirmed in audit log")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_CANDIDATE_ATTESTATION"));
        }

        @Test
        @DisplayName("3. Missing audit record rejects receipt even if caller claims true")
        void testMissingAuditRecordRejectsReceiptEvenIfCallerClaimsTrue() {
            UUID unapprovedApp = UUID.randomUUID();
            captureService.recordApplicationOwnership(unapprovedApp, PROFILE_ID);

            var cmd = validCommandBuilder()
                    .applicationId(unapprovedApp)
                    .attestationConfirmed(true)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_CANDIDATE_ATTESTATION"));
        }

        @Test
        @DisplayName("4. Cross-profile application ownership mismatch is rejected")
        void testCrossProfileApplicationOwnershipMismatchIsRejected() {
            UUID foreignProfile = UUID.randomUUID();
            UUID foreignApp = UUID.randomUUID();
            captureService.recordApplicationOwnership(foreignApp, foreignProfile);
            captureService.recordAuditAttestation(foreignApp, foreignProfile, "foreign@example.com");

            // Caller with PROFILE_ID attempts to claim receipt for foreignApp
            var cmd = validCommandBuilder()
                    .applicationId(foreignApp)
                    .profileId(PROFILE_ID)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Cross-profile receipt injection rejected")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("CROSS_PROFILE_RECEIPT_REJECTED"));
        }

        @Test
        @DisplayName("5. Summary substitution is rejected")
        void testSummarySubstitutionIsRejected() {
            String summaryText = "Application submitted successfully on Workday for candidate Ada Lovelace.";
            var cmd = validCommandBuilder()
                    .rawEvidencePayload(summaryText)
                    .receiptSha256(ReceiptVerificationPolicy.computeEvidenceSha256(summaryText))
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Raw evidence payload cannot be substituted with a summary text")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("SUMMARY_SUBSTITUTION_REJECTED"));
        }

        @Test
        @DisplayName("6. Empty or missing confirmation signals are rejected")
        void testEmptyConfirmationSignalsRejected() {
            var cmdEmpty = validCommandBuilder()
                    .observedSignals(List.of())
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmdEmpty))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Observed confirmation signals are required and cannot be empty or manufactured")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_CONFIRMATION_SIGNALS"));

            var cmdNull = validCommandBuilder()
                    .observedSignals(null)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmdNull))
                    .isInstanceOf(InvalidReceiptException.class)
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_CONFIRMATION_SIGNALS"));
        }

        @Test
        @DisplayName("7. Unauthenticated evidence provenance is rejected")
        void testUnauthenticatedProvenanceRejected() {
            var cmd = validCommandBuilder()
                    .evidenceProvenance("EXTERNAL_UNVERIFIED_CLIENT")
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Unauthenticated evidence provenance rejected")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("UNAUTHENTICATED_EVIDENCE_PROVENANCE"));
        }

        @Test
        @DisplayName("7b. Missing evidence provenance fails closed")
        void testMissingEvidenceProvenanceFailsClosed() {
            var cmd = validCommandBuilder()
                    .evidenceProvenance(null)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Evidence provenance is required")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_EVIDENCE_PROVENANCE"));
        }

        @Test
        @DisplayName("7c. Request-supplied provenance without worker execution verification fails closed")
        void testRequestSuppliedProvenanceWithoutWorkerVerificationFailsClosed() {
            UUID unverifiedApp = UUID.randomUUID();
            captureService.recordApplicationOwnership(unverifiedApp, PROFILE_ID);
            captureService.recordAuditAttestation(unverifiedApp, PROFILE_ID, "ada@example.com");
            // Intentionally do NOT call recordVerifiedProvenance for unverifiedApp

            var cmd = validCommandBuilder()
                    .applicationId(unverifiedApp)
                    .evidenceProvenance("AUTHENTICATED_BROWSER_WORKER")
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Request-supplied provenance rejected")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("UNAUTHENTICATED_EVIDENCE_PROVENANCE"));
        }

        @Test
        @DisplayName("7d. REAL_SUBMISSION_PREPARED cannot be substituted for candidate approval")
        void testRealSubmissionPreparedCannotBeSubstitutedForCandidateApproval() {
            UUID preparedApp = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            captureService.recordApplicationOwnership(preparedApp, PROFILE_ID);
            captureService.recordVerifiedProvenance(planId, preparedApp, "AUTHENTICATED_BROWSER_WORKER");
            // Record REAL_SUBMISSION_PREPARED instead of a _SUBMIT_APPROVED action
            captureService.recordAuditAttestation(preparedApp, PROFILE_ID, planId, "ada@example.com", "REAL_SUBMISSION_PREPARED", true);

            var cmd = validCommandBuilder()
                    .applicationId(preparedApp)
                    .planId(planId)
                    .attestationConfirmed(true)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Candidate attestation record is missing or not confirmed in audit log")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_CANDIDATE_ATTESTATION"));
        }

        @Test
        @DisplayName("7e. Unapproved after_state (approvedForSubmission=false) rejects attestation")
        void testUnapprovedAfterStateRejectsAttestation() {
            UUID unapprovedApp = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            captureService.recordApplicationOwnership(unapprovedApp, PROFILE_ID);
            captureService.recordVerifiedProvenance(planId, unapprovedApp, "AUTHENTICATED_BROWSER_WORKER");
            // approvedForSubmission = false
            captureService.recordAuditAttestation(unapprovedApp, PROFILE_ID, planId, "ada@example.com", "WORKDAY_SUBMIT_APPROVED", false);

            var cmd = validCommandBuilder()
                    .applicationId(unapprovedApp)
                    .planId(planId)
                    .attestationConfirmed(true)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_CANDIDATE_ATTESTATION"));
        }

        @Test
        @DisplayName("7f. Worker or system actor cannot approve candidate submission")
        void testWorkerOrSystemActorCannotApproveCandidateSubmission() {
            UUID workerApp = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            captureService.recordApplicationOwnership(workerApp, PROFILE_ID);
            captureService.recordVerifiedProvenance(planId, workerApp, "AUTHENTICATED_BROWSER_WORKER");
            // Actor is worker or system
            captureService.recordAuditAttestation(workerApp, PROFILE_ID, planId, "worker", "WORKDAY_SUBMIT_APPROVED", true);

            var cmd = validCommandBuilder()
                    .applicationId(workerApp)
                    .planId(planId)
                    .attestationConfirmed(true)
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd))
                    .isInstanceOf(InvalidReceiptException.class)
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("MISSING_CANDIDATE_ATTESTATION"));
        }

        @Test
        @DisplayName("7g. AutomationController platform approval actions are properly verified")
        void testPlatformSpecificSubmitApprovalAccepted() {
            for (String platform : List.of("GREENHOUSE", "ASHBY", "WORKDAY", "LEVER", "ZOHO_RECRUIT")) {
                UUID app = UUID.randomUUID();
                UUID plan = UUID.randomUUID();
                captureService.recordApplicationOwnership(app, PROFILE_ID);
                captureService.recordVerifiedProvenance(plan, app, "AUTHENTICATED_BROWSER_WORKER");
                captureService.recordAuditAttestation(app, PROFILE_ID, plan, "ada@example.com", platform + "_SUBMIT_APPROVED", true);

                var cmd = validCommandBuilder()
                        .applicationId(app)
                        .planId(plan)
                        .atsPlatform(platform)
                        .attestationConfirmed(true)
                        .build();

                SubmissionReceipt receipt = captureService.captureReceipt(cmd);
                assertThat(receipt).isNotNull();
                assertThat(receipt.authorization().approved()).isTrue();
            }
        }

        @Test
        @DisplayName("8. Conflicting receipt replay is rejected")
        void testConflictingReplayRejected() {
            var cmd1 = validCommandBuilder()
                    .employerConfirmationId("WD-CONF-987654")
                    .build();
            captureService.captureReceipt(cmd1);

            // Same app and receipt digest, but conflicting employerConfirmationId
            var cmd2 = validCommandBuilder()
                    .employerConfirmationId("WD-CONF-DIFFERENT-111")
                    .build();

            assertThatThrownBy(() -> captureService.captureReceipt(cmd2))
                    .isInstanceOf(InvalidReceiptException.class)
                    .hasMessageContaining("Replay conflict detected")
                    .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("RECEIPT_REPLAY_REJECTED"));
        }

        @Test
        @DisplayName("9. Genuine receipt record requires all verifications and persistence")
        void testGenuineReceiptRecordRequiresAllVerifications() {
            var cmd = validCommandBuilder().build();
            var record = captureService.captureGenuineReceipt(cmd);

            assertThat(record.genuine()).isTrue();
            assertThat(record.employerConfirmationId()).isEqualTo("WD-CONF-987654");
            assertThat(record.atsPlatform()).isEqualTo("WORKDAY");
            assertThat(record.verifiedAt()).isNotNull();
            assertThat(record.submissionReceipt()).isNotNull();
            assertThat(record.submissionReceipt().confirmation().confirmed()).isTrue();
            assertThat(record.submissionReceipt().authorization().approved()).isTrue();
        }
    }
}
