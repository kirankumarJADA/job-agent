package com.personal.jobagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapter;
import com.personal.jobagent.ats.AtsKind;
import com.personal.jobagent.ats.AtsAdapter.SubmissionPayload;
import com.personal.jobagent.ats.AtsAdapter.SubmissionResult;
import com.personal.jobagent.ats.AtsTestSubmitController;
import com.personal.jobagent.ats.BaseAtsAdapter;
import com.personal.jobagent.automation.ApplyDocumentSelector;
import com.personal.jobagent.automation.ExecutionPackageBlockedException;
import com.personal.jobagent.automation.ExecutionPackageService;
import com.personal.jobagent.coverletter.CoverLetterRecord;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.ats.JobFormQuestionService;
import com.personal.jobagent.resume.ResumeAtsAnalysis;
import com.personal.jobagent.resume.ResumeAtsRepository;
import com.personal.jobagent.track.ApplicationReceiptService;
import com.personal.jobagent.track.ApplicationReceiptService.SubmissionReceipt;
import com.personal.jobagent.track.ReceiptVerificationPolicy;
import com.personal.jobagent.track.ReceiptVerificationPolicy.Evidence;
import com.personal.jobagent.track.ReceiptVerificationPolicy.Outcome;
import com.personal.jobagent.track.ReceiptVerificationPolicy.SenderAuthentication;
import com.personal.jobagent.track.TrackText;
import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.EvidenceType;
import com.personal.jobagent.track.TrackVocabulary.Verification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Adversarial Security & Release Gate Suite: Application Receipt Integrity,
 * Digest Tamper Rejection, Cross-Tenant Isolation, and Authorization Gate Enforcement.
 */
@DisplayName("Adversarial QA Suite: Application Receipt Security & Integrity Invariants")
public class ApplicationReceiptSecurityTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final SenderAuthentication PASSING_AUTH =
            new SenderAuthentication("pass", "careers.targetemployer.com", "pass", "pass");

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Synthetic / Mock Receipt Rejection in Production Paths
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("1. Synthetic / Mock Receipt Rejection in Production Paths")
    class SyntheticAndMockReceiptRejection {

        private final ReceiptVerificationPolicy policy = new ReceiptVerificationPolicy();

        @Test
        @DisplayName("Adversarial: Candidate self-statement is strictly UNVERIFIED and never verified")
        void testCandidateSelfStatementNeverVerified() {
            Evidence candidateClaim = new Evidence(
                    EvidenceType.CANDIDATE_STATEMENT,
                    Channel.MAILBOX_CONNECTOR,
                    true,
                    false,
                    true,
                    "targetemployer.com",
                    PASSING_AUTH,
                    true,
                    NOW,
                    NOW
            );

            Outcome outcome = policy.evaluate(candidateClaim);

            assertThat(outcome.status()).isEqualTo(Verification.UNVERIFIED);
            assertThat(outcome.reasons()).extracting(r -> r.get("code"))
                    .containsExactly("CANDIDATE_STATEMENT");
        }

        @Test
        @DisplayName("Adversarial: Non-connector channel (CANDIDATE_FORWARDED / CANDIDATE_ENTERED) is unverified")
        void testUnauthenticatedChannelsCannotProduceVerifiedReceipt() {
            Evidence forwarded = new Evidence(
                    EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                    Channel.CANDIDATE_FORWARDED,
                    true,
                    false,
                    true,
                    "targetemployer.com",
                    PASSING_AUTH,
                    true,
                    NOW,
                    NOW
            );

            Outcome forwardedOutcome = policy.evaluate(forwarded);
            assertThat(forwardedOutcome.status()).isEqualTo(Verification.UNVERIFIED);
            assertThat(forwardedOutcome.reasons()).extracting(r -> r.get("code"))
                    .contains("CHANNEL_NOT_AUTHENTICATED");

            Evidence manualEntered = new Evidence(
                    EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                    Channel.CANDIDATE_ENTERED,
                    true,
                    false,
                    true,
                    "targetemployer.com",
                    PASSING_AUTH,
                    true,
                    NOW,
                    NOW
            );

            Outcome enteredOutcome = policy.evaluate(manualEntered);
            assertThat(enteredOutcome.status()).isEqualTo(Verification.UNVERIFIED);
            assertThat(enteredOutcome.reasons()).extracting(r -> r.get("code"))
                    .contains("CHANNEL_NOT_AUTHENTICATED");
        }

        @Test
        @DisplayName("Adversarial: Forged, misaligned, or missing sender authentication fails receipt verification")
        void testForgedOrMisalignedSenderAuthenticationFails() {
            // DKIM failure
            Evidence dkimFail = new Evidence(
                    EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                    Channel.MAILBOX_CONNECTOR,
                    true,
                    false,
                    true,
                    "targetemployer.com",
                    new SenderAuthentication("fail", "targetemployer.com", "pass", "pass"),
                    true,
                    NOW,
                    NOW
            );
            assertThat(policy.evaluate(dkimFail).status()).isEqualTo(Verification.UNVERIFIED);

            // Domain spoofing (DKIM signed for attacker domain, claiming from targetemployer.com)
            Evidence spoofed = new Evidence(
                    EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                    Channel.MAILBOX_CONNECTOR,
                    true,
                    false,
                    true,
                    "targetemployer.com",
                    new SenderAuthentication("pass", "malicious-spoofer.org", "pass", "pass"),
                    true,
                    NOW,
                    NOW
            );
            Outcome spoofedOutcome = policy.evaluate(spoofed);
            assertThat(spoofedOutcome.status()).isEqualTo(Verification.UNVERIFIED);
            assertThat(spoofedOutcome.reasons()).extracting(r -> r.get("code"))
                    .contains("SENDER_NOT_AUTHENTICATED");

            // SPF / DMARC failure
            Evidence dmarcFail = new Evidence(
                    EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                    Channel.MAILBOX_CONNECTOR,
                    true,
                    false,
                    true,
                    "targetemployer.com",
                    new SenderAuthentication("pass", "targetemployer.com", "pass", "fail"),
                    true,
                    NOW,
                    NOW
            );
            assertThat(policy.evaluate(dmarcFail).status()).isEqualTo(Verification.UNVERIFIED);
        }

        @Test
        @DisplayName("Adversarial: Future-dated or cross-application evidence is strictly REJECTED")
        void testFutureDatedOrCrossApplicationEvidenceIsRejected() {
            // Future-dated evidence (+3 days)
            Evidence futureDated = new Evidence(
                    EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                    Channel.MAILBOX_CONNECTOR,
                    true,
                    false,
                    true,
                    "targetemployer.com",
                    PASSING_AUTH,
                    true,
                    NOW.plus(Duration.ofDays(3)),
                    NOW
            );
            Outcome futureOutcome = policy.evaluate(futureDated);
            assertThat(futureOutcome.status()).isEqualTo(Verification.REJECTED);
            assertThat(futureOutcome.reasons()).extracting(r -> r.get("code")).contains("FUTURE_DATED");

            // Evidence identifying a different requisition/application
            Evidence wrongApp = new Evidence(
                    EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL,
                    Channel.MAILBOX_CONNECTOR,
                    false,
                    true,
                    true,
                    "targetemployer.com",
                    PASSING_AUTH,
                    true,
                    NOW,
                    NOW
            );
            Outcome wrongAppOutcome = policy.evaluate(wrongApp);
            assertThat(wrongAppOutcome.status()).isEqualTo(Verification.REJECTED);
            assertThat(wrongAppOutcome.reasons()).extracting(r -> r.get("code"))
                    .contains("IDENTIFIES_A_DIFFERENT_APPLICATION");
        }

        @Test
        @DisplayName("Adversarial: AtsTestSubmitController is strictly gated by @Profile('local')")
        void testAtsTestSubmitControllerLocalProfileGated() {
            Profile profileAnn = AtsTestSubmitController.class.getAnnotation(Profile.class);
            assertThat(profileAnn)
                    .as("AtsTestSubmitController must be marked with @Profile")
                    .isNotNull();
            assertThat(profileAnn.value())
                    .as("AtsTestSubmitController must be active only in 'local' profile")
                    .containsExactly("local");
        }

        @Test
        @DisplayName("Adversarial: BaseAtsAdapter unconditionally refuses real submission for non-dry-run calls")
        void testBaseAtsAdapterRefusesRealSubmissionExecution() {
            BaseAtsAdapter adapter = new BaseAtsAdapter(AtsKind.WORKDAY, Pattern.compile("workday\\.com"), false, false) {};
            SubmissionPayload payload = new SubmissionPayload(
                    "Adversarial Candidate", "adv@example.local", "+123456789", "Location",
                    "BASE64_DATA", "# Cover letter", Map.of()
            );

            // Attempting real submission through base adapter must fail deterministically
            SubmissionResult realAttempt = adapter.submitApplication("https://company.workday.com/apply", payload, false);
            assertThat(realAttempt.success()).isFalse();
            assertThat(realAttempt.status()).isEqualTo("REAL_SUBMIT_NOT_IMPLEMENTED");
            assertThat(realAttempt.confirmationId()).isNull();
            assertThat(realAttempt.details().get("simulated")).isEqualTo(false);

            // Dry-run produces MOCK- simulation tag
            SubmissionResult dryRunAttempt = adapter.submitApplication("https://company.workday.com/apply", payload, true);
            assertThat(dryRunAttempt.success()).isTrue();
            assertThat(dryRunAttempt.status()).isEqualTo("DRY_RUN_PASSED");
            assertThat(dryRunAttempt.confirmationId()).startsWith("MOCK-WORKDAY-");
            assertThat(dryRunAttempt.details().get("simulated")).isEqualTo(true);
        }

        @Test
        @DisplayName("Adversarial: Unconfirmed/unsubmitted plan status yields unconfirmed receipt evidence")
        void testUnsubmittedPlanCannotProduceConfirmedReceipt() throws Exception {
            JdbcTemplate db = mock(JdbcTemplate.class);
            ObjectMapper json = new ObjectMapper();
            ApplicationReceiptService service = new ApplicationReceiptService(db, json);

            UUID profileId = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();

            stubApplication(db, profileId, appId, jobId, "READY_TO_SUBMIT");
            stubPlans(db, profileId, appId, List.of(
                    new PlanData(planId, "READY_TO_SUBMIT", false, "idemp-1",
                            "{\"planType\":\"WORKDAY\"}",
                            "[{\"type\":\"WORKDAY_APPLY\",\"confirmationNumber\":\"MOCK-SIMULATED-123\"}]",
                            NOW, NOW)
            ));

            Optional<SubmissionReceipt> receipt = service.getReceipt(profileId, appId, null);

            assertThat(receipt).isPresent();
            assertThat(receipt.get().submitted()).isFalse();
            assertThat(receipt.get().status()).isEqualTo("READY_TO_SUBMIT");
            assertThat(receipt.get().confirmation().confirmed()).isFalse();
            assertThat(receipt.get().confirmation().confirmationId()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. SHA-256 Digest Tamper Rejection (Corrupted Receipt Digest)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("2. SHA-256 Digest Tamper Rejection (Corrupted Receipt Digest)")
    class Sha256DigestTamperRejection {

        @Test
        @DisplayName("Adversarial: CV review digest mismatch halts ApplyDocumentSelector packaging")
        void testTamperedCvReviewDigestHaltsPackaging() {
            ResumeAtsRepository cvs = mock(ResumeAtsRepository.class);
            CoverLetterRepository letters = mock(CoverLetterRepository.class);
            JobFormQuestionService formQuestions = mock(JobFormQuestionService.class);
            ApplyDocumentSelector selector = new ApplyDocumentSelector(cvs, letters, formQuestions);

            UUID profileId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            UUID cvId = UUID.randomUUID();

            when(formQuestions.coverLetterRequirement(jobId)).thenReturn(JobFormQuestionService.CoverLetterRequirement.OPTIONAL);
            when(letters.findByJobIdForProfile(jobId, profileId)).thenReturn(List.of());

            Map<String, Object> validation = Map.of("passed", true, "validator_version", DocumentFactValidator.VERSION);
            Map<String, Object> atsReport = Map.of("validation", validation);
            ResumeAtsAnalysis analysis = new ResumeAtsAnalysis(
                    UUID.randomUUID(), profileId, jobId, appId, "hash", "Staff Engineer", "Cloud",
                    List.of(), List.of(), Map.of(), List.of(), List.of(),
                    atsReport, cvId, "cv text", 1, "snapshot", "tampered-content-sha256"
            );
            byte[] bytes = new byte[]{10, 20, 30};
            ResumeAtsRepository.ArtifactCheck artifactCheck = new ResumeAtsRepository.ArtifactCheck(
                    bytes, "tampered-content-sha256", "tampered-content-sha256", "tampered-content-sha256"
            );

            when(cvs.findForApplication(profileId, jobId, appId)).thenReturn(List.of(analysis));
            when(cvs.checkedArtifact(profileId, cvId)).thenReturn(Optional.of(artifactCheck));
            // Review was approved for original digest, but stored hash changed to 'tampered-content-sha256'
            when(cvs.review(profileId, cvId)).thenReturn(Optional.of(
                    new ResumeAtsRepository.Review(true, "owner", NOW, "original-candidate-approved-sha256")));

            var selection = selector.select(profileId, jobId, appId);

            assertThat(selection.blocked()).isTrue();
            assertThat(selection.cv()).isNull();
            assertThat(selection.blockers()).anyMatch(b -> "CV_REVIEW_DIGEST_MISMATCH".equals(b.get("code")));
        }

        @Test
        @DisplayName("Adversarial: Corrupted disk artifact triggers CV_ARTIFACT_INTEGRITY_FAILED")
        void testCorruptedArtifactBytesTriggersIntegrityFailure() {
            ResumeAtsRepository cvs = mock(ResumeAtsRepository.class);
            CoverLetterRepository letters = mock(CoverLetterRepository.class);
            JobFormQuestionService formQuestions = mock(JobFormQuestionService.class);
            ApplyDocumentSelector selector = new ApplyDocumentSelector(cvs, letters, formQuestions);

            UUID profileId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            UUID cvId = UUID.randomUUID();

            when(formQuestions.coverLetterRequirement(jobId)).thenReturn(JobFormQuestionService.CoverLetterRequirement.OPTIONAL);
            when(letters.findByJobIdForProfile(jobId, profileId)).thenReturn(List.of());

            Map<String, Object> validation = Map.of("passed", true, "validator_version", DocumentFactValidator.VERSION);
            Map<String, Object> atsReport = Map.of("validation", validation);
            ResumeAtsAnalysis analysis = new ResumeAtsAnalysis(
                    UUID.randomUUID(), profileId, jobId, appId, "hash", "Architect", "Platform",
                    List.of(), List.of(), Map.of(), List.of(), List.of(),
                    atsReport, cvId, "cv text", 1, "snapshot", "valid-recorded-sha256"
            );
            byte[] corruptedBytes = new byte[]{99, 98, 97};
            ResumeAtsRepository.ArtifactCheck corruptedCheck = new ResumeAtsRepository.ArtifactCheck(
                    corruptedBytes, "corrupted-actual-sha256", "valid-recorded-sha256", "corrupted-sha256"
            );

            when(cvs.findForApplication(profileId, jobId, appId)).thenReturn(List.of(analysis));
            when(cvs.checkedArtifact(profileId, cvId)).thenReturn(Optional.of(corruptedCheck));
            when(cvs.review(profileId, cvId)).thenReturn(Optional.of(
                    new ResumeAtsRepository.Review(true, "owner", NOW, "valid-recorded-sha256")));

            var selection = selector.select(profileId, jobId, appId);

            assertThat(selection.blocked()).isTrue();
            assertThat(selection.cv()).isNull();
            assertThat(selection.blockers()).anyMatch(b -> "CV_ARTIFACT_INTEGRITY_FAILED".equals(b.get("code")));
        }

        @Test
        @DisplayName("Adversarial: Tampered cover letter markdown fails bodyIntact verification")
        void testTamperedCoverLetterBodyIntactVerification() {
            UUID profileId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            UUID letterId = UUID.randomUUID();

            String authenticMarkdown = "Dear Hiring Committee, I am pleased to submit my application for this role.";
            String recordedSha256 = TrackText.sha256(authenticMarkdown);

            CoverLetterRecord authenticRecord = new CoverLetterRecord(
                    letterId, profileId, jobId, appId, 1, "Staff Engineer Cover Letter",
                    authenticMarkdown, Map.of("passed", true), true, NOW, NOW,
                    recordedSha256, null, "GENERATED", false
            );
            assertThat(CoverLetterService.bodyIntact(authenticRecord)).isTrue();

            CoverLetterRecord tamperedRecord = new CoverLetterRecord(
                    letterId, profileId, jobId, appId, 1, "Staff Engineer Cover Letter",
                    authenticMarkdown + "\n\n[TAMPERED: Injected malicious qualification]",
                    Map.of("passed", true), true, NOW, NOW,
                    recordedSha256, null, "GENERATED", false
            );
            assertThat(CoverLetterService.bodyIntact(tamperedRecord)).isFalse();
        }

        @Test
        @DisplayName("Adversarial: ExecutionPackageService.Sha256 computes deterministic SHA-256 for artifacts")
        void testExecutionPackageSha256DigestIntegrity() {
            byte[] content = "immutable-cv-binary-content-v1".getBytes(StandardCharsets.UTF_8);
            String calculatedSha = ExecutionPackageService.Sha256.of(content);
            String referenceSha = TrackText.sha256(content);

            assertThat(calculatedSha)
                    .isEqualTo(referenceSha)
                    .matches("^[a-f0-9]{64}$");

            byte[] tamperedContent = "immutable-cv-binary-content-v1-TAMPERED".getBytes(StandardCharsets.UTF_8);
            String tamperedSha = ExecutionPackageService.Sha256.of(tamperedContent);

            assertThat(tamperedSha).isNotEqualTo(calculatedSha);
        }

        @Test
        @DisplayName("Adversarial: Deduplication fingerprint prevents collision and substitution attacks")
        void testReceiptEvidenceFingerprintTamperDetection() {
            String mid = "12345678-receipt-message-id";
            String fp1 = TrackText.sha256("EMAIL|mid:" + mid);
            String fp2 = TrackText.sha256("EMAIL|mid:" + mid + "-corrupted");

            assertThat(fp1).isNotEqualTo(fp2);
            assertThat(fp1).matches("^[a-f0-9]{64}$");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Cross-Tenant Receipt Isolation (Candidate B cannot view Candidate A's receipt)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("3. Cross-Tenant Receipt Isolation")
    class CrossTenantReceiptIsolation {

        private JdbcTemplate db;
        private ApplicationReceiptService service;

        @BeforeEach
        void setUp() {
            db = mock(JdbcTemplate.class);
            service = new ApplicationReceiptService(db, new ObjectMapper());
        }

        @Test
        @DisplayName("Adversarial: Candidate B cannot view Candidate A's submission receipt (returns empty)")
        void testCandidateBCannotViewCandidateAReceipt() {
            UUID profileA = UUID.randomUUID();
            UUID profileB = UUID.randomUUID();
            UUID appA = UUID.randomUUID();

            // Candidate B requests receipt for Candidate A's application -> DB query scopes by profile_id
            when(db.queryForObject(contains("from applications a"), any(RowMapper.class), eq(appA), eq(profileB)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            Optional<SubmissionReceipt> receipt = service.getReceipt(profileB, appA, null);

            assertThat(receipt).isEmpty();
        }

        @Test
        @DisplayName("Adversarial: Candidate B cannot view Candidate A's specific attempt receipt (returns empty)")
        void testCandidateBCannotViewCandidateASpecificAttempt() {
            UUID profileB = UUID.randomUUID();
            UUID appA = UUID.randomUUID();
            UUID attemptA = UUID.randomUUID();

            when(db.queryForObject(contains("from applications a"), any(RowMapper.class), eq(appA), eq(profileB)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            Optional<SubmissionReceipt> receipt = service.getReceipt(profileB, appA, attemptA);

            assertThat(receipt).isEmpty();
        }

        @Test
        @DisplayName("Adversarial: Candidate B cannot list Candidate A's attempt history (returns empty)")
        void testCandidateBCannotListCandidateAAttemptHistory() {
            UUID profileB = UUID.randomUUID();
            UUID appA = UUID.randomUUID();

            when(db.queryForObject(
                    eq("select count(*) from applications where id = ? and profile_id = ?"),
                    eq(Integer.class), eq(appA), eq(profileB)
            )).thenReturn(0);

            var attempts = service.getAttempts(profileB, appA);

            assertThat(attempts).isEmpty();
        }

        @Test
        @DisplayName("Adversarial: Cross-tenant document reference in receipt payload is masked/rejected")
        void testCrossTenantDocumentReferenceInReceiptIsMasked() throws Exception {
            UUID profileA = UUID.randomUUID();
            UUID profileB = UUID.randomUUID();
            UUID appA = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            UUID foreignCvVersionId = UUID.randomUUID();

            stubApplication(db, profileA, appA, jobId, "SUBMITTED");

            // Plan references Candidate B's CV ID
            String packageWithForeignCv = "{\"planType\":\"WORKDAY\",\"package\":{\"cv\":{\"versionId\":\""
                    + foreignCvVersionId + "\",\"sha256\":\"foreign-sha\",\"fileName\":\"cv-b.pdf\"}},\"steps\":[]}";

            stubPlans(db, profileA, appA, List.of(
                    new PlanData(planId, "SUBMITTED", true, "idemp-cross", packageWithForeignCv, "[]", NOW, NOW)
            ));

            // Candidate B's CV does not exist under Candidate A's profile
            when(db.queryForMap(
                    eq("select title, claims_validation::text as val from cv_versions where id = ? and profile_id = ?"),
                    eq(foreignCvVersionId), eq(profileA)
            )).thenThrow(new EmptyResultDataAccessException(1));

            Optional<SubmissionReceipt> receipt = service.getReceipt(profileA, appA, null);

            assertThat(receipt).isPresent();
            // Document title and validationPassed must remain null because it does not belong to profileA
            assertThat(receipt.get().cv()).isNotNull();
            assertThat(receipt.get().cv().title()).isNull();
            assertThat(receipt.get().cv().validationPassed()).isNull();
        }

        @Test
        @DisplayName("Adversarial: Null profileId or applicationId safely returns empty without error")
        void testNullProfileOrApplicationGuards() {
            assertThat(service.getReceipt(null, UUID.randomUUID(), null)).isEmpty();
            assertThat(service.getReceipt(UUID.randomUUID(), null, null)).isEmpty();
            assertThat(service.getAttempts(null, UUID.randomUUID())).isEmpty();
            assertThat(service.getAttempts(UUID.randomUUID(), null)).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Candidate Authorization Gate Enforcement
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("4. Candidate Authorization Gate Enforcement")
    class CandidateAuthorizationGateEnforcement {

        private JdbcTemplate db;
        private ApplicationReceiptService service;

        @BeforeEach
        void setUp() {
            db = mock(JdbcTemplate.class);
            service = new ApplicationReceiptService(db, new ObjectMapper());
        }

        @Test
        @DisplayName("Adversarial: Submission receipt without affirmative candidate approval has approved = false")
        void testUnapprovedPlanReceiptExplicitlyMarksAuthorizationFalse() throws Exception {
            UUID profileId = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();

            stubApplication(db, profileId, appId, jobId, "READY_TO_SUBMIT");
            // submit_approved is false
            stubPlans(db, profileId, appId, List.of(
                    new PlanData(planId, "READY_TO_SUBMIT", false, "idemp-unapproved",
                            "{\"planType\":\"WORKDAY\",\"steps\":[]}", "[]", NOW, NOW)
            ));

            Optional<SubmissionReceipt> receipt = service.getReceipt(profileId, appId, null);

            assertThat(receipt).isPresent();
            assertThat(receipt.get().authorization()).isNotNull();
            assertThat(receipt.get().authorization().approved()).isFalse();
            assertThat(receipt.get().authorization().policy()).isEqualTo("EXPLICIT_CANDIDATE_APPROVAL");
            assertThat(receipt.get().authorization().approvedAt()).isNull();
            assertThat(receipt.get().authorization().actor()).isNull();
        }

        @Test
        @DisplayName("Adversarial: Approved submission captures affirmative audit log attestation")
        void testApprovedSubmissionCapturesAuditLogAttestation() throws Exception {
            UUID profileId = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            Instant approvalTime = NOW.minus(Duration.ofMinutes(10));

            stubApplication(db, profileId, appId, jobId, "SUBMITTED");
            // submit_approved is true
            stubPlans(db, profileId, appId, List.of(
                    new PlanData(planId, "SUBMITTED", true, "idemp-approved",
                            "{\"planType\":\"WORKDAY\",\"steps\":[]}",
                            "[{\"type\":\"WORKDAY_APPLICATION_OUTCOME\",\"submitted\":true,\"confirmationNumber\":\"WD-CONF-7788\"}]",
                            NOW, approvalTime)
            ));

            // The strict approval query (plan + application + profile) returns the candidate's record
            when(db.queryForList(
                    contains("from audit_logs al"),
                    eq(planId), eq(appId), eq(profileId)
            )).thenReturn(List.of(
                    Map.of("actor", "verified-candidate@example.local", "created_at", Timestamp.from(approvalTime))
            ));

            Optional<SubmissionReceipt> receipt = service.getReceipt(profileId, appId, null);

            assertThat(receipt).isPresent();
            assertThat(receipt.get().authorization().approved()).isTrue();
            assertThat(receipt.get().authorization().policy()).isEqualTo("EXPLICIT_CANDIDATE_APPROVAL");
            assertThat(receipt.get().authorization().actor()).isEqualTo("verified-candidate@example.local");
            assertThat(receipt.get().authorization().approvedAt()).isEqualTo(approvalTime);
            assertThat(receipt.get().submitted()).isTrue();
            // Approval alone is not confirmation: no verified worker evidence, so never confirmed.
            assertThat(receipt.get().confirmation().confirmed()).isFalse();
            assertThat(receipt.get().confirmation().confirmationId()).isEqualTo("WD-CONF-7788");
            assertThat(receipt.get().reconciliation().required()).isTrue();
        }

        @Test
        @DisplayName("Adversarial: a loose audit row matched only by entity id (old fallback) is never authorization")
        void testLooseFallbackAuditRowDoesNotAuthorize() throws Exception {
            UUID profileId = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();

            stubApplication(db, profileId, appId, jobId, "SUBMITTED");
            stubPlans(db, profileId, appId, List.of(
                    new PlanData(planId, "SUBMITTED", true, "idemp-loose",
                            "{\"planType\":\"WORKDAY\",\"steps\":[]}", "[]", NOW, NOW)));
            // Only the removed two-parameter fallback shape would have matched this row.
            when(db.queryForList(contains("from audit_logs"), eq(planId), eq(appId)))
                    .thenReturn(List.of(Map.of("actor", "anyone@example.local", "created_at", Timestamp.from(NOW))));

            Optional<SubmissionReceipt> receipt = service.getReceipt(profileId, appId, null);

            assertThat(receipt).isPresent();
            assertThat(receipt.get().authorization().approved()).isFalse();
            assertThat(receipt.get().authorization().actor()).isNull();
            assertThat(receipt.get().confirmation().confirmed()).isFalse();
        }

        @Test
        @DisplayName("Adversarial: Unapproved empty receipt preserves non-approved authorization state")
        void testEmptyReceiptWithoutExecutionPreservesNonApprovedAuthorization() {
            UUID profileId = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();

            stubApplication(db, profileId, appId, jobId, "READY_TO_APPLY");
            // No plans exist
            when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(appId), eq(profileId)))
                    .thenReturn(List.of());

            Optional<SubmissionReceipt> receipt = service.getReceipt(profileId, appId, null);

            assertThat(receipt).isPresent();
            assertThat(receipt.get().authorization().approved()).isFalse();
            assertThat(receipt.get().authorization().policy()).isEqualTo("EXPLICIT_CANDIDATE_APPROVAL");
            assertThat(receipt.get().submitted()).isFalse();
            assertThat(receipt.get().atsKind()).isEqualTo("NOT_ATTEMPTED");
            assertThat(receipt.get().confirmation().confirmed()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper plumbing for mocked JDBC queries
    // ─────────────────────────────────────────────────────────────────────────

    private record PlanData(UUID id, String status, boolean submitApproved, String idempotencyKey,
                            String planJson, String resultJson, Instant createdAt, Instant updatedAt) {}

    @SuppressWarnings("unchecked")
    private static void stubApplication(JdbcTemplate db, UUID profileId, UUID appId, UUID jobId, String status) {
        when(db.queryForObject(contains("from applications a"), any(RowMapper.class), eq(appId), eq(profileId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("application_id")).thenReturn(appId);
                    when(rs.getObject("job_id")).thenReturn(jobId);
                    when(rs.getString("job_title")).thenReturn("Staff Systems Engineer");
                    when(rs.getString("company")).thenReturn("Target Employer Corp");
                    when(rs.getString("application_url")).thenReturn("https://target.workday.com/apply");
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getObject("cv_version_id")).thenReturn(null);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
                    return mapper.mapRow(rs, 1);
                });
    }

    @SuppressWarnings("unchecked")
    private static void stubPlans(JdbcTemplate db, UUID profileId, UUID appId, List<PlanData> planDataList) {
        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(appId), eq(profileId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    List<Object> results = new java.util.ArrayList<>();
                    for (int i = 0; i < planDataList.size(); i++) {
                        PlanData p = planDataList.get(i);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getObject("id")).thenReturn(p.id());
                        when(rs.getObject("application_id")).thenReturn(appId);
                        when(rs.getString("target_url")).thenReturn("https://target.workday.com/apply");
                        when(rs.getString("status")).thenReturn(p.status());
                        when(rs.getString("plan_json")).thenReturn(p.planJson());
                        when(rs.getString("result_json")).thenReturn(p.resultJson());
                        when(rs.getBoolean("submit_approved")).thenReturn(p.submitApproved());
                        when(rs.getString("idempotency_key")).thenReturn(p.idempotencyKey());
                        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(p.createdAt()));
                        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(p.updatedAt()));
                        results.add(mapper.mapRow(rs, i + 1));
                    }
                    return results;
                });
    }
}
