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

    private ReceiptVerificationPolicy policy;
    private ReceiptCaptureService captureService;

    @BeforeEach
    void setUp() {
        policy = new ReceiptVerificationPolicy();
        captureService = new ReceiptCaptureService(policy);
    }

    private static String validSha256() {
        return ReceiptCaptureService.computeReceiptSha256(
                "WD-CONF-987654",
                "WORKDAY",
                SUBMITTED_AT,
                PROFILE_ID,
                APPLICATION_ID,
                true,
                List.of("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED")
        );
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
                .sandboxPosting(false)
                .candidateAuthorizedSandbox(true);
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
                    .hasMessageContaining("receiptSha256 does not match canonical evidence payload");
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
            String sha = ReceiptCaptureService.computeReceiptSha256(
                    "CONF-123456",
                    platform,
                    SUBMITTED_AT,
                    PROFILE_ID,
                    APPLICATION_ID,
                    true,
                    List.of("PLATFORM_SUBMISSION_CONFIRMED")
            );

            var cmd = validCommandBuilder()
                    .atsPlatform(platform)
                    .employerConfirmationId("CONF-123456")
                    .receiptSha256(sha)
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
                    .hasMessageContaining("Candidate attestation must be confirmed");
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
                    .hasMessageContaining("real submissions must be verified only in candidate-owned sandbox/test posting");
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
                    false,
                    true,
                    List.of("PLATFORM_SUBMISSION_CONFIRMED"),
                    null
            );

            VerificationOutcome outcome = policy.evaluate(evidence);
            assertThat(outcome.verified()).isTrue();
            assertThat(outcome.receiptSha256()).isEqualTo(sha);
            assertThat(outcome.reasons()).extracting(r -> r.get("code"))
                    .contains("ALL_7_INVARIANTS_VERIFIED", "CANDIDATE_ATTESTATION_CONFIRMED", "ZERO_MOCK_PREFIXES_VERIFIED");
        }

        @Test
        @DisplayName("Verifies existing SubmissionReceipt against policy")
        void verifiesExistingSubmissionReceipt() {
            CaptureReceiptCommand cmd = validCommandBuilder().build();
            SubmissionReceipt receipt = captureService.captureReceipt(cmd);

            var outcome = captureService.verifyExistingReceipt(receipt, cmd.receiptSha256(), PROFILE_ID, false, true);
            assertThat(outcome.verified()).isTrue();
        }
    }
}
