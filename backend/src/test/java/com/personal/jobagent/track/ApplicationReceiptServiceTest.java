package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.receipt.ReceiptCaptureService;
import com.personal.jobagent.track.receipt.ReceiptStore;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationReceiptServiceTest {

    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID PLAN_ID_1 = UUID.randomUUID();
    private static final UUID PLAN_ID_2 = UUID.randomUUID();
    private static final UUID CV_VERSION_ID = UUID.randomUUID();

    private JdbcTemplate db;
    private ObjectMapper json;
    private ApplicationReceiptService service;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        json = new ObjectMapper()
                .findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new ApplicationReceiptService(db, json);
    }

    @SuppressWarnings("unchecked")
    private void stubApplication() {
        when(db.queryForObject(contains("from applications a"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("application_id")).thenReturn(APP_ID);
                    when(rs.getObject("job_id")).thenReturn(JOB_ID);
                    when(rs.getString("job_title")).thenReturn("Principal Systems Architect");
                    when(rs.getString("company")).thenReturn("NVIDIA Corp");
                    when(rs.getString("application_url")).thenReturn("https://nvidia.wd5.myworkdayjobs.com/apply");
                    when(rs.getString("status")).thenReturn("SUBMITTED");
                    when(rs.getObject("cv_version_id")).thenReturn(CV_VERSION_ID);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:00:00Z")));
                    return mapper.mapRow(rs, 1);
                });
    }

    @Test
    void returnsEmptyWhenProfileOrApplicationNotFound() {
        when(db.queryForObject(contains("from applications a"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenThrow(new EmptyResultDataAccessException(1));

        var result = service.getReceipt(PROFILE_ID, APP_ID, null);

        assertThat(result).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsCompleteReceiptButNeverConfirmsWithoutVerifiedWorkerEvidence() throws Exception {
        stubApplication();

        String planJson = json.writeValueAsString(Map.of(
                "planType", "WORKDAY",
                "package", Map.of(
                        "cv", Map.of("versionId", CV_VERSION_ID.toString(), "sha256", "sha-cv-12345", "fileName", "cv.pdf", "byteSize", 54321),
                        "coverLetter", Map.of("versionId", UUID.randomUUID().toString(), "sha256", "sha-cl-67890", "fileName", "cl.pdf", "byteSize", 12345)
                ),
                "steps", List.of(
                        Map.of("id", "nav", "type", "NAVIGATE", "params", Map.of("url", "https://nvidia.wd5.myworkdayjobs.com/apply")),
                        Map.of("id", "apply", "type", "WORKDAY_APPLY", "params", Map.of(
                                "identity", Map.of(
                                        "name", "Ada Lovelace",
                                        "firstName", "Ada",
                                        "lastName", "Lovelace",
                                        "email", "ada@example.com",
                                        "phone", "+44 7700 900123",
                                        "addressLine1", "London, UK",
                                        "linkedIn", "https://linkedin.com/in/ada-lovelace"
                                )
                        )),
                        Map.of("id", "submit", "type", "WORKDAY_SUBMIT_CLICK", "params", Map.of())
                )
        ));

        String resultJson = json.writeValueAsString(List.of(
                Map.of(
                        "type", "WORKDAY_APPLICATION_OUTCOME",
                        "submitted", true,
                        "requisitionId", "JR2024946",
                        "confirmationNumber", "WD-CONF-987654",
                        "signals", List.of("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED"),
                        "summary", "Application confirmed by Workday requisition acknowledgment."
                )
        ));

        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id")).thenReturn(PLAN_ID_1);
                    when(rs.getObject("application_id")).thenReturn(APP_ID);
                    when(rs.getString("target_url")).thenReturn("https://nvidia.wd5.myworkdayjobs.com/apply");
                    when(rs.getString("status")).thenReturn("SUBMITTED");
                    when(rs.getString("plan_json")).thenReturn(planJson);
                    when(rs.getString("result_json")).thenReturn(resultJson);
                    when(rs.getBoolean("submit_approved")).thenReturn(true);
                    when(rs.getString("idempotency_key")).thenReturn("workday:" + APP_ID);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:05:00Z")));
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:07:00Z")));
                    return List.of(mapper.mapRow(rs, 1));
                });

        when(db.queryForMap(contains("from cv_versions where id"), eq(CV_VERSION_ID), eq(PROFILE_ID)))
                .thenReturn(Map.of("title", "Tailored Systems CV", "val", "{\"passed\":true}"));

        // The strict approval query is keyed by plan, application AND profile; there is no looser fallback.
        when(db.queryForList(contains("from audit_logs al"), eq(PLAN_ID_1), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(List.of(Map.of("actor", "ada@example.com", "created_at", Timestamp.from(Instant.parse("2026-10-01T10:06:00Z")))));

        var receiptOpt = service.getReceipt(PROFILE_ID, APP_ID, null);

        assertThat(receiptOpt).isPresent();
        var receipt = receiptOpt.get();

        assertThat(receipt.applicationId()).isEqualTo(APP_ID);
        assertThat(receipt.jobTitle()).isEqualTo("Principal Systems Architect");
        assertThat(receipt.company()).isEqualTo("NVIDIA Corp");
        assertThat(receipt.atsKind()).isEqualTo("WORKDAY");
        assertThat(receipt.status()).isEqualTo("SUBMITTED");
        assertThat(receipt.submitted()).isTrue();
        assertThat(receipt.submittedAt()).isEqualTo(Instant.parse("2026-10-01T10:07:00Z"));

        // Submitted candidate identity
        assertThat(receipt.submittedIdentity().name()).isEqualTo("Ada Lovelace");
        assertThat(receipt.submittedIdentity().firstName()).isEqualTo("Ada");
        assertThat(receipt.submittedIdentity().lastName()).isEqualTo("Lovelace");
        assertThat(receipt.submittedIdentity().email()).isEqualTo("ada@example.com");
        assertThat(receipt.submittedIdentity().phone()).isEqualTo("+44 7700 900123");
        assertThat(receipt.submittedIdentity().addressLine1()).isEqualTo("London, UK");
        assertThat(receipt.submittedIdentity().linkedIn()).isEqualTo("https://linkedin.com/in/ada-lovelace");

        // Document provenance
        assertThat(receipt.cv()).isNotNull();
        assertThat(receipt.cv().versionId()).isEqualTo(CV_VERSION_ID);
        assertThat(receipt.cv().sha256()).isEqualTo("sha-cv-12345");
        assertThat(receipt.cv().title()).isEqualTo("Tailored Systems CV");
        assertThat(receipt.cv().validationPassed()).isTrue();
        assertThat(receipt.coverLetter().sha256()).isEqualTo("sha-cl-67890");

        // Authorization record
        assertThat(receipt.authorization().approved()).isTrue();
        assertThat(receipt.authorization().policy()).isEqualTo("EXPLICIT_CANDIDATE_APPROVAL");
        assertThat(receipt.authorization().actor()).isEqualTo("ada@example.com");
        assertThat(receipt.authorization().approvedAt()).isEqualTo(Instant.parse("2026-10-01T10:06:00Z"));

        // Status SUBMITTED and a confirmation number in the result JSON are NOT proof: without the
        // receipt capture service and worker-recorded evidence, the submission is never shown as confirmed.
        assertThat(receipt.confirmation().confirmed()).isFalse();
        assertThat(receipt.confirmation().confirmationId()).isEqualTo("WD-CONF-987654");
        assertThat(receipt.confirmation().observedSignals()).containsExactly("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED");
        assertThat(receipt.confirmation().summary()).startsWith("Unverified submission:");

        // Unverified submission therefore requires reconciliation
        assertThat(receipt.reconciliation().required()).isTrue();
        assertThat(receipt.reconciliation().reason()).isEqualTo("UNVERIFIED_RECEIPT_EVIDENCE");
        assertThat(receipt.failure().failed()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsReconciliationReceiptWithSuggestedActionWhenUnconfirmed() throws Exception {
        stubApplication();

        String planJson = json.writeValueAsString(Map.of(
                "planType", "WORKDAY",
                "steps", List.of(
                        Map.of("id", "apply", "type", "WORKDAY_APPLY", "params", Map.of(
                                "identity", Map.of("name", "Ada Lovelace", "email", "ada@example.com")
                        ))
                )
        ));

        String resultJson = json.writeValueAsString(List.of(
                Map.of(
                        "type", "SUBMISSION_RECONCILIATION",
                        "reason", "Apply form submit button clicked but post-submit response timed out after 30000ms"
                )
        ));

        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id")).thenReturn(PLAN_ID_1);
                    when(rs.getObject("application_id")).thenReturn(APP_ID);
                    when(rs.getString("target_url")).thenReturn("https://nvidia.wd5.myworkdayjobs.com/apply");
                    when(rs.getString("status")).thenReturn("RECONCILIATION_REQUIRED");
                    when(rs.getString("plan_json")).thenReturn(planJson);
                    when(rs.getString("result_json")).thenReturn(resultJson);
                    when(rs.getBoolean("submit_approved")).thenReturn(true);
                    when(rs.getString("idempotency_key")).thenReturn("workday:" + APP_ID);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:05:00Z")));
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:07:00Z")));
                    return List.of(mapper.mapRow(rs, 1));
                });

        var receiptOpt = service.getReceipt(PROFILE_ID, APP_ID, null);

        assertThat(receiptOpt).isPresent();
        var receipt = receiptOpt.get();

        assertThat(receipt.status()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(receipt.submitted()).isFalse();
        assertThat(receipt.confirmation().confirmed()).isFalse();

        // Reconciliation fields populated
        assertThat(receipt.reconciliation().required()).isTrue();
        assertThat(receipt.reconciliation().reason()).contains("timed out after 30000ms");
        assertThat(receipt.reconciliation().suggestedAction()).contains("Reconcile to trigger a fresh independent submission attempt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesMultipleAttemptHistoryAndPermitsSelectingEarlierAttempt() throws Exception {
        stubApplication();

        String planJson1 = json.writeValueAsString(Map.of("planType", "WORKDAY", "steps", List.of()));
        String resultJson1 = json.writeValueAsString(List.of(Map.of("type", "SUBMISSION_RECONCILIATION", "reason", "timeout")));

        String planJson2 = json.writeValueAsString(Map.of("planType", "WORKDAY", "steps", List.of()));
        String resultJson2 = json.writeValueAsString(List.of(Map.of("confirmationNumber", "CONF-2026", "submitted", true)));

        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs1 = mock(java.sql.ResultSet.class);
                    when(rs1.getObject("id")).thenReturn(PLAN_ID_1);
                    when(rs1.getObject("application_id")).thenReturn(APP_ID);
                    when(rs1.getString("target_url")).thenReturn("https://nvidia.wd5.myworkdayjobs.com/apply");
                    when(rs1.getString("status")).thenReturn("RECONCILIATION_REQUIRED");
                    when(rs1.getString("plan_json")).thenReturn(planJson1);
                    when(rs1.getString("result_json")).thenReturn(resultJson1);
                    when(rs1.getBoolean("submit_approved")).thenReturn(true);
                    when(rs1.getString("idempotency_key")).thenReturn("workday:" + APP_ID);
                    when(rs1.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:00:00Z")));
                    when(rs1.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:05:00Z")));

                    java.sql.ResultSet rs2 = mock(java.sql.ResultSet.class);
                    when(rs2.getObject("id")).thenReturn(PLAN_ID_2);
                    when(rs2.getObject("application_id")).thenReturn(APP_ID);
                    when(rs2.getString("target_url")).thenReturn("https://nvidia.wd5.myworkdayjobs.com/apply");
                    when(rs2.getString("status")).thenReturn("SUBMITTED");
                    when(rs2.getString("plan_json")).thenReturn(planJson2);
                    when(rs2.getString("result_json")).thenReturn(resultJson2);
                    when(rs2.getBoolean("submit_approved")).thenReturn(true);
                    when(rs2.getString("idempotency_key")).thenReturn("workday-retry:" + PLAN_ID_1);
                    when(rs2.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:15:00Z")));
                    when(rs2.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:17:00Z")));

                    return List.of(mapper.mapRow(rs1, 1), mapper.mapRow(rs2, 2));
                });

        // 1. Without attemptId: returns latest attempt (attempt 2)
        var latestReceipt = service.getReceipt(PROFILE_ID, APP_ID, null).orElseThrow();
        assertThat(latestReceipt.attemptNumber()).isEqualTo(2);
        assertThat(latestReceipt.isLatest()).isTrue();
        assertThat(latestReceipt.status()).isEqualTo("SUBMITTED");
        assertThat(latestReceipt.attemptHistory()).hasSize(2);
        assertThat(latestReceipt.attemptHistory().get(0).attemptNumber()).isEqualTo(1);
        assertThat(latestReceipt.attemptHistory().get(0).status()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(latestReceipt.attemptHistory().get(1).attemptNumber()).isEqualTo(2);
        assertThat(latestReceipt.attemptHistory().get(1).status()).isEqualTo("SUBMITTED");

        // 2. With attemptId = PLAN_ID_1: returns earlier attempt 1
        var attempt1Receipt = service.getReceipt(PROFILE_ID, APP_ID, PLAN_ID_1).orElseThrow();
        assertThat(attempt1Receipt.attemptNumber()).isEqualTo(1);
        assertThat(attempt1Receipt.isLatest()).isFalse();
        assertThat(attempt1Receipt.status()).isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void redactSensitiveSanitizesBearerTokensPasswordsAndTrimsLongStrings() {
        assertThat(ApplicationReceiptService.redactSensitive(null)).isNull();
        assertThat(ApplicationReceiptService.redactSensitive("Normal string")).isEqualTo("Normal string");
        assertThat(ApplicationReceiptService.redactSensitive("Bearer eyJhbGciOiJIUzI1NiJ9.test-token"))
                .isEqualTo("Bearer [REDACTED]");
        assertThat(ApplicationReceiptService.redactSensitive("Error during auth: password=SecretPass123! and login failed"))
                .isEqualTo("Error during auth: password=[REDACTED] and login failed");
        assertThat(ApplicationReceiptService.redactSensitive("Cookie: session_id=abc123\nAuthorization: Basic dXNlcjpwYXNz\nhttps://alice:s3cret@host.test/cb?api_key=k123"))
                .doesNotContain("abc123", "dXNlcjpwYXNz", "alice:s3cret", "k123")
                .contains("Cookie: [REDACTED]", "Authorization: [REDACTED]", "https://[REDACTED]@host.test/cb?api_key=[REDACTED]");

        String longPrefixPlusSecret = "A".repeat(250) + "Bearer super-secret-tail-token";
        assertThat(ApplicationReceiptService.redactSensitive(longPrefixPlusSecret))
                .doesNotContain("super-secret-tail-token");

        String longStr = "A".repeat(300);
        String redacted = ApplicationReceiptService.redactSensitive(longStr);
        assertThat(redacted).hasSize(256);
        assertThat(redacted).endsWith("...");

        String rawEvidence = "{\"cookie\":\"Cookie: sid=topsecret\",\"url\":\"https://u:p@example.com/ok\",\"token\":\"eyJ.tok\",\"html\":\"" + "B".repeat(500) + "\"}";
        String redactedRaw = ApplicationReceiptService.redactRawEvidenceSecrets(rawEvidence);
        assertThat(redactedRaw).doesNotContain("topsecret", "u:p@", "eyJ.tok").contains("B".repeat(500));
    }

    @Test
    void getAttemptsReturnsEmptyWhenApplicationBelongsToAnotherProfileOrNotFound() {
        UUID foreignAppId = UUID.randomUUID();
        when(db.queryForObject(contains("select count(*) from applications"), eq(Integer.class), eq(foreignAppId), eq(PROFILE_ID)))
                .thenReturn(0);

        var attempts = service.getAttempts(PROFILE_ID, foreignAppId);
        assertThat(attempts).isEmpty();
    }

    @Test
    void getAttemptsReturnsListWhenApplicationOwned() {
        when(db.queryForObject(contains("select count(*) from applications"), eq(Integer.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(1);
        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(List.of());

        var attempts = service.getAttempts(PROFILE_ID, APP_ID);
        assertThat(attempts).isPresent();
        assertThat(attempts.get()).isEmpty();
    }

    @Test
    void serializesReceiptDtoWithExactFrontendContractFieldNames() throws Exception {
        stubApplication();
        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(List.of());

        var receipt = service.getReceipt(PROFILE_ID, APP_ID, null).orElseThrow();
        var serialized = json.readTree(json.writeValueAsString(receipt));

        assertThat(serialized.has("company")).isTrue();
        assertThat(serialized.has("updatedAt")).isTrue();
        assertThat(serialized.has("submittedIdentity")).isTrue();
        assertThat(serialized.has("submittedAnswers")).isTrue();
        assertThat(serialized.has("cv")).isTrue();
        assertThat(serialized.has("coverLetter")).isTrue();
        assertThat(serialized.has("attemptHistory")).isTrue();
        assertThat(serialized.path("authorization").has("approved")).isTrue();
        assertThat(serialized.path("confirmation").has("confirmationId")).isTrue();
        assertThat(serialized.path("reconciliation").has("required")).isTrue();
        // Legacy mismatched frontend names must never appear
        assertThat(serialized.has("companyName")).isFalse();
        assertThat(serialized.has("generatedAt")).isFalse();
        assertThat(serialized.has("identity")).isFalse();
        assertThat(serialized.has("documents")).isFalse();
        assertThat(serialized.has("answers")).isFalse();
        assertThat(serialized.has("attempts")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractsWorkerReconciliationCategoryEvidenceAndPlanErrorAndCoverLetterMetadata() throws Exception {
        stubApplication();

        UUID coverLetterId = UUID.randomUUID();
        UUID reconcilPlanId = UUID.randomUUID();
        UUID failedPlanId = UUID.randomUUID();

        String planJson = json.writeValueAsString(Map.of(
                "planType", "WORKDAY",
                "package", Map.of(
                        "cv", Map.of("versionId", CV_VERSION_ID.toString(), "sha256", "sha-cv-1", "fileName", "cv-" + CV_VERSION_ID + ".pdf", "byteSize", 42000),
                        "coverLetter", Map.of("versionId", coverLetterId.toString(), "sha256", "sha-cl-body", "fileName", "cover-letter-" + coverLetterId + ".txt")
                ),
                "steps", List.of()
        ));

        String reconciliationResult = json.writeValueAsString(List.of(
                Map.of(
                        "type", "SUBMISSION_RECONCILIATION",
                        "planType", "WORKDAY",
                        "category", "UNKNOWN",
                        "evidence", "Submit click ran but employer acknowledgment response was not observed"
                )
        ));
        String failedResult = json.writeValueAsString(List.of(
                Map.of(
                        "type", "PLAN_ERROR",
                        "outcome", "FAILED",
                        "reason", "DEAD_LETTER_POISON_PILL",
                        "message", "Repeated crashes (3/3): Wait timeout Authorization: Bearer super-secret-jwt-1",
                        "deadLetter", true
                )
        ));

        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs1 = mock(java.sql.ResultSet.class);
                    when(rs1.getObject("id")).thenReturn(reconcilPlanId);
                    when(rs1.getObject("application_id")).thenReturn(APP_ID);
                    when(rs1.getString("target_url")).thenReturn("https://nvidia.wd5.myworkdayjobs.com/apply");
                    when(rs1.getString("status")).thenReturn("RECONCILIATION_REQUIRED");
                    when(rs1.getString("plan_json")).thenReturn(planJson);
                    when(rs1.getString("result_json")).thenReturn(reconciliationResult);
                    when(rs1.getBoolean("submit_approved")).thenReturn(true);
                    when(rs1.getString("idempotency_key")).thenReturn("workday:" + APP_ID);
                    when(rs1.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:00:00Z")));
                    when(rs1.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:02:00Z")));

                    java.sql.ResultSet rs2 = mock(java.sql.ResultSet.class);
                    when(rs2.getObject("id")).thenReturn(failedPlanId);
                    when(rs2.getObject("application_id")).thenReturn(APP_ID);
                    when(rs2.getString("target_url")).thenReturn("https://nvidia.wd5.myworkdayjobs.com/apply");
                    when(rs2.getString("status")).thenReturn("FAILED");
                    when(rs2.getString("plan_json")).thenReturn(planJson);
                    when(rs2.getString("result_json")).thenReturn(failedResult);
                    when(rs2.getBoolean("submit_approved")).thenReturn(false);
                    when(rs2.getString("idempotency_key")).thenReturn("workday-retry:" + reconcilPlanId);
                    when(rs2.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:10:00Z")));
                    when(rs2.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:12:00Z")));

                    return List.of(mapper.mapRow(rs1, 1), mapper.mapRow(rs2, 2));
                });

        when(db.queryForMap(contains("from cv_versions where id"), eq(CV_VERSION_ID), eq(PROFILE_ID)))
                .thenReturn(Map.of("title", "Tailored Systems CV", "val", "{\"passed\":true}"));

        Map<String, Object> coverRow = new java.util.HashMap<>();
        coverRow.put("title", "NVIDIA Principal Systems Cover Letter");
        coverRow.put("val", "{\"passed\":true}");
        coverRow.put("body_markdown", "Dear Hiring Team, I built distributed systems.");
        coverRow.put("content_sha256", "sha-cl-body");
        coverRow.put("pdf_byte_size", null);
        coverRow.put("pdf_sha256", null);
        when(db.queryForMap(contains("from cover_letters"), eq(coverLetterId), eq(PROFILE_ID)))
                .thenReturn(coverRow);

        var recReceipt = service.getReceipt(PROFILE_ID, APP_ID, reconcilPlanId).orElseThrow();
        assertThat(recReceipt.reconciliation().required()).isTrue();
        assertThat(recReceipt.reconciliation().reason())
                .isEqualTo("[UNKNOWN] Submit click ran but employer acknowledgment response was not observed");
        assertThat(recReceipt.coverLetter()).isNotNull();
        assertThat(recReceipt.coverLetter().title()).isEqualTo("NVIDIA Principal Systems Cover Letter");
        assertThat(recReceipt.coverLetter().kind()).isEqualTo("TXT");
        assertThat(recReceipt.coverLetter().byteSize()).isEqualTo(46L);
        assertThat(recReceipt.coverLetter().validationPassed()).isTrue();
        assertThat(recReceipt.cv().kind()).isEqualTo("PDF");

        var failReceipt = service.getReceipt(PROFILE_ID, APP_ID, failedPlanId).orElseThrow();
        assertThat(failReceipt.failure().failed()).isTrue();
        assertThat(failReceipt.failure().code()).isEqualTo("DEAD_LETTER_POISON_PILL");
        assertThat(failReceipt.failure().reason())
                .contains("Repeated crashes (3/3): Wait timeout")
                .contains("Authorization: [REDACTED]")
                .doesNotContain("super-secret-jwt-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractsPdfCoverLetterMetadataAndHandlesMissingOptionalMetadata() throws Exception {
        stubApplication();
        UUID coverLetterId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        String planJson = json.writeValueAsString(Map.of(
                "planType", "WORKDAY",
                "package", Map.of(
                        "coverLetter", Map.of(
                                "versionId", coverLetterId.toString(),
                                "sha256", "sha-cl-pdf-999",
                                "fileName", "cover-letter-" + coverLetterId + ".pdf",
                                "pdfStored", true
                        )
                ),
                "steps", List.of()
        ));

        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id")).thenReturn(planId);
                    when(rs.getObject("application_id")).thenReturn(APP_ID);
                    when(rs.getString("target_url")).thenReturn(null);
                    when(rs.getString("status")).thenReturn("PREPARED");
                    when(rs.getString("plan_json")).thenReturn(planJson);
                    when(rs.getString("result_json")).thenReturn("[]");
                    when(rs.getBoolean("submit_approved")).thenReturn(false);
                    when(rs.getString("idempotency_key")).thenReturn("workday:" + APP_ID);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:00:00Z")));
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:00:00Z")));
                    return List.of(mapper.mapRow(rs, 1));
                });

        Map<String, Object> coverRow = new java.util.HashMap<>();
        coverRow.put("title", "PDF Cover Letter Title");
        coverRow.put("val", "{}");
        coverRow.put("body_markdown", "Ignored markdown length when PDF exists");
        coverRow.put("content_sha256", "sha-md");
        coverRow.put("pdf_byte_size", 18432L);
        coverRow.put("pdf_sha256", "sha-cl-pdf-999");
        when(db.queryForMap(contains("from cover_letters"), eq(coverLetterId), eq(PROFILE_ID)))
                .thenReturn(coverRow);

        var receipt = service.getReceipt(PROFILE_ID, APP_ID, null).orElseThrow();
        assertThat(receipt.cv()).isNull();
        assertThat(receipt.submittedAnswers()).isEmpty();
        assertThat(receipt.coverLetter()).isNotNull();
        assertThat(receipt.coverLetter().title()).isEqualTo("PDF Cover Letter Title");
        assertThat(receipt.coverLetter().kind()).isEqualTo("PDF");
        assertThat(receipt.coverLetter().byteSize()).isEqualTo(18432L);
        assertThat(receipt.coverLetter().validationPassed()).isNull();
        assertThat(receipt.applicationUrl()).isEqualTo("https://nvidia.wd5.myworkdayjobs.com/apply");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyReceiptReadsCoverLetterPdfOrTxtAndHandlesMissingOptionalProfileMetadata() {
        when(db.queryForObject(contains("from applications a"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("application_id")).thenReturn(APP_ID);
                    when(rs.getObject("job_id")).thenReturn(JOB_ID);
                    when(rs.getString("job_title")).thenReturn("Principal Systems Architect");
                    when(rs.getString("company")).thenReturn("Acme Corp");
                    when(rs.getString("application_url")).thenReturn("https://boards.greenhouse.io/acme/jobs/42");
                    when(rs.getString("status")).thenReturn("READY_TO_APPLY");
                    when(rs.getObject("cv_version_id")).thenReturn(null);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:00:00Z")));
                    return mapper.mapRow(rs, 1);
                });
        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(List.of());
        when(db.queryForObject(contains("from cover_letters cl"), any(RowMapper.class), eq(PROFILE_ID), eq(APP_ID), eq(JOB_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id")).thenReturn(UUID.fromString("00000000-0000-7000-8000-000000000301"));
                    when(rs.getString("title")).thenReturn("Empty App Cover Letter");
                    when(rs.getString("val")).thenReturn("{\"passed\":true}");
                    when(rs.getString("body_markdown")).thenReturn("Hello Acme team");
                    when(rs.getString("content_sha256")).thenReturn("sha-empty-cl");
                    when(rs.getObject("pdf_file_id")).thenReturn(null);
                    when(rs.getString("pdf_sha256")).thenReturn(null);
                    when(rs.getObject("pdf_byte_size")).thenReturn(null);
                    return mapper.mapRow(rs, 1);
                });
        when(db.queryForObject(contains("from profiles p"), any(RowMapper.class), eq(PROFILE_ID)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(db.query(contains("from application_answers"), any(RowMapper.class), eq(APP_ID)))
                .thenReturn(List.of());

        var emptyReceipt = service.getReceipt(PROFILE_ID, APP_ID, null).orElseThrow();
        assertThat(emptyReceipt.cv()).isNull();
        assertThat(emptyReceipt.coverLetter()).isNotNull();
        assertThat(emptyReceipt.coverLetter().kind()).isEqualTo("TXT");
        assertThat(emptyReceipt.coverLetter().title()).isEqualTo("Empty App Cover Letter");
        assertThat(emptyReceipt.coverLetter().byteSize()).isEqualTo(15L);
        assertThat(emptyReceipt.coverLetter().validationPassed()).isTrue();
        assertThat(emptyReceipt.submittedIdentity().name()).isNull();
        assertThat(emptyReceipt.attemptHistory()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializesRealBackendDtosMatchingSharedFrontendContractFixture() throws Exception {
        UUID fixedProfile = UUID.fromString("11111111-1111-7111-8111-111111111111");
        UUID fixedApp = UUID.fromString("22222222-2222-7222-8222-222222222222");
        UUID fixedJob = UUID.fromString("33333333-3333-7333-8333-333333333333");
        UUID fixedCv = UUID.fromString("44444444-4444-7444-8444-444444444444");
        UUID fixedCl = UUID.fromString("55555555-5555-7555-8555-555555555555");
        UUID plan1Id = UUID.fromString("66666666-6666-7666-8666-666666666661");
        UUID plan2Id = UUID.fromString("66666666-6666-7666-8666-666666666662");
        UUID plan3Id = UUID.fromString("66666666-6666-7666-8666-666666666663");
        UUID storedReceiptId = UUID.fromString("77777777-7777-7777-8777-777777777777");
        UUID evidenceId = UUID.fromString("88888888-8888-7888-8888-888888888888");
        UUID auditId = UUID.fromString("99999999-9999-7999-8999-999999999999");
        UUID sandboxId = UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa");

        JdbcTemplate contractDb = mock(JdbcTemplate.class);
        ReceiptCaptureService capture = mock(ReceiptCaptureService.class);
        SubmissionEvidenceRepository evRepo = mock(SubmissionEvidenceRepository.class);
        ApplicationReceiptService contractService = new ApplicationReceiptService(contractDb, json, capture, evRepo);

        when(contractDb.queryForObject(contains("from applications a"), any(RowMapper.class), eq(fixedApp), eq(fixedProfile)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("application_id")).thenReturn(fixedApp);
                    when(rs.getObject("job_id")).thenReturn(fixedJob);
                    when(rs.getString("job_title")).thenReturn("Senior Platform Engineer");
                    when(rs.getString("company")).thenReturn("Acme Corp");
                    when(rs.getString("application_url")).thenReturn("https://boards.greenhouse.io/robin-sandbox/jobs/42");
                    when(rs.getString("status")).thenReturn("SUBMITTED");
                    when(rs.getObject("cv_version_id")).thenReturn(null);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T10:00:00Z")));
                    return mapper.mapRow(rs, 1);
                });

        String pkgPlanJson = json.writeValueAsString(Map.of(
                "planType", "GREENHOUSE",
                "package", Map.of(
                        "cv", Map.of("versionId", fixedCv.toString(), "sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                                "fileName", "cv-" + fixedCv + ".pdf", "byteSize", 54321),
                        "coverLetter", Map.of("versionId", fixedCl.toString(), "sha256", "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
                                "fileName", "cover-letter-" + fixedCl + ".txt")
                ),
                "steps", List.of(
                        Map.of("id", "fill-first_name", "type", "FILL_FIELD", "params", Map.of("selector", "#first_name", "value", "Jane")),
                        Map.of("id", "fill-last_name", "type", "FILL_FIELD", "params", Map.of("selector", "#last_name", "value", "Doe")),
                        Map.of("id", "fill-email", "type", "FILL_FIELD", "params", Map.of("selector", "#email", "value", "jane@example.com")),
                        Map.of("id", "fill-phone", "type", "FILL_FIELD", "params", Map.of("selector", "#phone", "value", "+44 7700 900123")),
                        Map.of("id", "fill-candidate-location", "type", "FILL_FIELD", "params", Map.of("selector", "#candidate-location", "value", "London, UK"))
                )
        ));

        String recResultJson = json.writeValueAsString(List.of(Map.of(
                "type", "SUBMISSION_RECONCILIATION",
                "planType", "GREENHOUSE",
                "category", "UNKNOWN",
                "evidence", "Submit click ran but employer confirmation response timed out."
        )));
        String confResultJson = json.writeValueAsString(List.of(Map.of(
                "type", "SUBMISSION_EVIDENCE",
                "planType", "GREENHOUSE",
                "confirmationNumber", "GH-CONF-98765",
                "signals", List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX", "GREENHOUSE_CONFIRMATION_URL_MATCHED"),
                "summary", "Application confirmation verified against stored worker evidence."
        )));
        String failResultJson = json.writeValueAsString(List.of(Map.of(
                "type", "PLAN_ERROR",
                "outcome", "FAILED",
                "reason", "DEAD_LETTER_POISON_PILL",
                "message", "Repeated crashes (3/3): Wait timeout Authorization: [REDACTED]"
        )));

        when(contractDb.query(contains("from automation_plans p"), any(RowMapper.class), eq(fixedApp), eq(fixedProfile)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs1 = mock(java.sql.ResultSet.class);
                    when(rs1.getObject("id")).thenReturn(plan1Id);
                    when(rs1.getObject("application_id")).thenReturn(fixedApp);
                    when(rs1.getString("target_url")).thenReturn("https://boards.greenhouse.io/robin-sandbox/jobs/42");
                    when(rs1.getString("status")).thenReturn("RECONCILIATION_REQUIRED");
                    when(rs1.getString("plan_json")).thenReturn(pkgPlanJson);
                    when(rs1.getString("result_json")).thenReturn(recResultJson);
                    when(rs1.getBoolean("submit_approved")).thenReturn(true);
                    when(rs1.getString("idempotency_key")).thenReturn("greenhouse:" + fixedApp);
                    when(rs1.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T10:00:00Z")));
                    when(rs1.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T10:02:00Z")));

                    java.sql.ResultSet rs2 = mock(java.sql.ResultSet.class);
                    when(rs2.getObject("id")).thenReturn(plan2Id);
                    when(rs2.getObject("application_id")).thenReturn(fixedApp);
                    when(rs2.getString("target_url")).thenReturn("https://boards.greenhouse.io/robin-sandbox/jobs/42");
                    when(rs2.getString("status")).thenReturn("SUBMITTED");
                    when(rs2.getString("plan_json")).thenReturn(pkgPlanJson);
                    when(rs2.getString("result_json")).thenReturn(confResultJson);
                    when(rs2.getBoolean("submit_approved")).thenReturn(true);
                    when(rs2.getString("idempotency_key")).thenReturn("greenhouse-retry:" + plan1Id);
                    when(rs2.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T11:58:00Z")));
                    when(rs2.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T11:59:00Z")));

                    return List.of(mapper.mapRow(rs1, 1), mapper.mapRow(rs2, 2));
                });

        when(contractDb.queryForMap(contains("from cv_versions where id"), eq(fixedCv), eq(fixedProfile)))
                .thenReturn(Map.of("title", "Tailored Platform CV", "val", "{\"passed\":true}"));
        Map<String, Object> fixedCoverRow = new java.util.HashMap<>();
        fixedCoverRow.put("title", "Acme Senior Platform Engineer Cover Letter");
        fixedCoverRow.put("val", "{\"passed\":true}");
        fixedCoverRow.put("body_markdown", "Dear Acme Hiring Team, building reliable platforms.");
        fixedCoverRow.put("content_sha256", "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb");
        fixedCoverRow.put("pdf_byte_size", null);
        fixedCoverRow.put("pdf_sha256", null);
        when(contractDb.queryForMap(contains("from cover_letters"), eq(fixedCl), eq(fixedProfile)))
                .thenReturn(fixedCoverRow);
        when(contractDb.query(contains("from application_answers"), any(RowMapper.class), eq(fixedApp)))
                .thenReturn(List.of(new ApplicationReceiptService.AnswerReceipt(
                        "How many years of experience do you have with Kubernetes?",
                        "7 years managing enterprise production clusters")));
        when(contractDb.queryForList(contains("from audit_logs al"), eq(plan1Id), eq(fixedApp), eq(fixedProfile)))
                .thenReturn(List.of(Map.of("actor", "Jane Doe", "created_at", Timestamp.from(Instant.parse("2026-10-22T09:55:00Z")))));
        when(contractDb.queryForList(contains("from audit_logs al"), eq(plan2Id), eq(fixedApp), eq(fixedProfile)))
                .thenReturn(List.of(Map.of("actor", "Jane Doe", "created_at", Timestamp.from(Instant.parse("2026-10-22T11:55:00Z")))));

        ReceiptStore.StoredReceipt verified = new ReceiptStore.StoredReceipt(
                storedReceiptId,
                "receipt:plan:" + plan2Id,
                fixedProfile,
                fixedApp,
                plan2Id,
                2,
                evidenceId,
                "f".repeat(64),
                "GREENHOUSE",
                "GH-CONF-98765",
                List.of("GREENHOUSE_SUBMISSION_RESPONSE_2XX", "GREENHOUSE_CONFIRMATION_URL_MATCHED"),
                auditId,
                "Jane Doe",
                Instant.parse("2026-10-22T11:55:00Z"),
                "worker",
                sandboxId,
                "genuine-ats-receipt-policy-v1",
                Instant.parse("2026-10-22T11:59:00Z"));
        when(capture.revalidateStoredReceipt(fixedProfile, fixedApp, plan2Id))
                .thenReturn(ReceiptCaptureService.StoredReceiptVerification.verified(verified));

        var confirmed = contractService.getReceipt(fixedProfile, fixedApp, null).orElseThrow();
        var reconciliation = contractService.getReceipt(fixedProfile, fixedApp, plan1Id).orElseThrow();

        // Build failed attempt & submitted-unverified receipts cleanly from service too
        JdbcTemplate unverifiedDb = mock(JdbcTemplate.class);
        ApplicationReceiptService unverifiedService = new ApplicationReceiptService(unverifiedDb, json);
        when(unverifiedDb.queryForObject(contains("from applications a"), any(RowMapper.class), eq(fixedApp), eq(fixedProfile)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("application_id")).thenReturn(fixedApp);
                    when(rs.getObject("job_id")).thenReturn(fixedJob);
                    when(rs.getString("job_title")).thenReturn("Senior Platform Engineer");
                    when(rs.getString("company")).thenReturn("Acme Corp");
                    when(rs.getString("application_url")).thenReturn("https://boards.greenhouse.io/robin-sandbox/jobs/42");
                    when(rs.getString("status")).thenReturn("SUBMITTED");
                    when(rs.getObject("cv_version_id")).thenReturn(null);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T10:00:00Z")));
                    return mapper.mapRow(rs, 1);
                });
        when(unverifiedDb.query(contains("from automation_plans p"), any(RowMapper.class), eq(fixedApp), eq(fixedProfile)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs2 = mock(java.sql.ResultSet.class);
                    when(rs2.getObject("id")).thenReturn(plan2Id);
                    when(rs2.getObject("application_id")).thenReturn(fixedApp);
                    when(rs2.getString("target_url")).thenReturn("https://boards.greenhouse.io/robin-sandbox/jobs/42");
                    when(rs2.getString("status")).thenReturn("SUBMITTED");
                    when(rs2.getString("plan_json")).thenReturn(pkgPlanJson);
                    when(rs2.getString("result_json")).thenReturn(json.writeValueAsString(List.of(Map.of(
                            "type", "SUBMISSION_EVIDENCE",
                            "planType", "GREENHOUSE",
                            "confirmationNumber", "CLAIMED-ONLY-999",
                            "signals", List.of(),
                            "summary", "Claimed only"
                    ))));
                    when(rs2.getBoolean("submit_approved")).thenReturn(true);
                    when(rs2.getString("idempotency_key")).thenReturn("greenhouse:" + fixedApp);
                    when(rs2.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T11:58:00Z")));
                    when(rs2.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T11:59:00Z")));

                    java.sql.ResultSet rs3 = mock(java.sql.ResultSet.class);
                    when(rs3.getObject("id")).thenReturn(plan3Id);
                    when(rs3.getObject("application_id")).thenReturn(fixedApp);
                    when(rs3.getString("target_url")).thenReturn("https://boards.greenhouse.io/robin-sandbox/jobs/42");
                    when(rs3.getString("status")).thenReturn("FAILED");
                    when(rs3.getString("plan_json")).thenReturn(pkgPlanJson);
                    when(rs3.getString("result_json")).thenReturn(failResultJson);
                    when(rs3.getBoolean("submit_approved")).thenReturn(false);
                    when(rs3.getString("idempotency_key")).thenReturn("greenhouse:" + fixedApp);
                    when(rs3.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T12:00:00Z")));
                    when(rs3.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T12:01:00Z")));
                    return List.of(mapper.mapRow(rs2, 1), mapper.mapRow(rs3, 2));
                });
        when(unverifiedDb.queryForMap(contains("from cv_versions where id"), eq(fixedCv), eq(fixedProfile)))
                .thenReturn(Map.of("title", "Tailored Platform CV", "val", "{\"passed\":true}"));
        when(unverifiedDb.queryForMap(contains("from cover_letters"), eq(fixedCl), eq(fixedProfile)))
                .thenReturn(fixedCoverRow);
        when(unverifiedDb.queryForList(contains("from audit_logs al"), eq(plan2Id), eq(fixedApp), eq(fixedProfile)))
                .thenReturn(List.of(Map.of("actor", "Jane Doe", "created_at", Timestamp.from(Instant.parse("2026-10-22T11:55:00Z")))));

        var submittedUnverified = unverifiedService.getReceipt(fixedProfile, fixedApp, plan2Id).orElseThrow();
        var failed = unverifiedService.getReceipt(fixedProfile, fixedApp, plan3Id).orElseThrow();

        // Empty unattempted receipt
        JdbcTemplate emptyDb = mock(JdbcTemplate.class);
        ApplicationReceiptService emptyService = new ApplicationReceiptService(emptyDb, json);
        when(emptyDb.queryForObject(contains("from applications a"), any(RowMapper.class), eq(fixedApp), eq(fixedProfile)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("application_id")).thenReturn(fixedApp);
                    when(rs.getObject("job_id")).thenReturn(fixedJob);
                    when(rs.getString("job_title")).thenReturn("Senior Platform Engineer");
                    when(rs.getString("company")).thenReturn("Acme Corp");
                    when(rs.getString("application_url")).thenReturn("https://boards.greenhouse.io/robin-sandbox/jobs/42");
                    when(rs.getString("status")).thenReturn("PREPARED");
                    when(rs.getObject("cv_version_id")).thenReturn(null);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T10:00:00Z")));
                    return mapper.mapRow(rs, 1);
                });
        when(emptyDb.query(contains("from automation_plans p"), any(RowMapper.class), eq(fixedApp), eq(fixedProfile)))
                .thenReturn(List.of());
        when(emptyDb.queryForObject(contains("from cover_letters cl"), any(RowMapper.class), eq(fixedProfile), eq(fixedApp), eq(fixedJob)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(emptyDb.queryForObject(contains("from profiles p"), any(RowMapper.class), eq(fixedProfile)))
                .thenThrow(new EmptyResultDataAccessException(1));
        var empty = emptyService.getReceipt(fixedProfile, fixedApp, null).orElseThrow();

        // Clean UNIVERSAL browser-observed receipt (no reconciliation error, unconfirmed by employer email)
        UUID plan4Id = UUID.fromString("66666666-6666-7666-8666-666666666664");
        JdbcTemplate browserDb = mock(JdbcTemplate.class);
        ReceiptCaptureService browserCapture = mock(ReceiptCaptureService.class);
        SubmissionEvidenceRepository browserEvRepo = mock(SubmissionEvidenceRepository.class);
        ApplicationReceiptService browserService = new ApplicationReceiptService(browserDb, json, browserCapture, browserEvRepo);
        when(browserDb.queryForObject(contains("from applications a"), any(RowMapper.class), eq(fixedApp), eq(fixedProfile)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("application_id")).thenReturn(fixedApp);
                    when(rs.getObject("job_id")).thenReturn(fixedJob);
                    when(rs.getString("job_title")).thenReturn("Senior Platform Engineer");
                    when(rs.getString("company")).thenReturn("Acme Corp");
                    when(rs.getString("application_url")).thenReturn("https://careers.acme.example/apply");
                    when(rs.getString("status")).thenReturn("SUBMITTED");
                    when(rs.getObject("cv_version_id")).thenReturn(fixedCv);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T11:55:00Z")));
                    return mapper.mapRow(rs, 1);
                });
        String univPlanJson = json.writeValueAsString(Map.of(
                "planType", "UNIVERSAL",
                "package", Map.of(
                        "cv", Map.of("versionId", fixedCv.toString(), "sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                                "fileName", "cv-" + fixedCv + ".pdf", "byteSize", 54321)
                ),
                "steps", List.of(
                        Map.of("id", "apply", "type", "UNIVERSAL_APPLY", "params", Map.of(
                                "identity", Map.of(
                                        "name", "Jane Doe",
                                        "firstName", "Jane",
                                        "lastName", "Doe",
                                        "email", "jane@example.com",
                                        "phone", "+44 7700 900123",
                                        "addressLine1", "London, UK"
                                )
                        ))
                )
        ));
        String univResultJson = json.writeValueAsString(List.of(Map.of(
                "type", "SUBMISSION_EVIDENCE",
                "planType", "UNIVERSAL",
                "confirmationNumber", "APP-98765",
                "signals", List.of("CONFIRMATION_PAGE_URL_CHANGED", "EMPLOYER_CONFIRMATION_ID_OBSERVED"),
                "summary", "Browser observed submission on employer form. Employer receipt has not yet been independently verified."
        )));
        when(browserDb.query(contains("from automation_plans p"), any(RowMapper.class), eq(fixedApp), eq(fixedProfile)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs4 = mock(java.sql.ResultSet.class);
                    when(rs4.getObject("id")).thenReturn(plan4Id);
                    when(rs4.getObject("application_id")).thenReturn(fixedApp);
                    when(rs4.getString("target_url")).thenReturn("https://careers.acme.example/apply");
                    when(rs4.getString("status")).thenReturn("SUBMITTED");
                    when(rs4.getString("plan_json")).thenReturn(univPlanJson);
                    when(rs4.getString("result_json")).thenReturn(univResultJson);
                    when(rs4.getBoolean("submit_approved")).thenReturn(true);
                    when(rs4.getString("idempotency_key")).thenReturn("universal:" + fixedApp);
                    when(rs4.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T11:55:00Z")));
                    when(rs4.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-22T12:00:00Z")));
                    return List.of(mapper.mapRow(rs4, 1));
                });
        when(browserDb.queryForMap(contains("from cv_versions where id"), eq(fixedCv), eq(fixedProfile)))
                .thenReturn(Map.of("title", "Tailored Platform CV", "val", "{\"passed\":true}"));
        when(browserDb.queryForObject(contains("from cover_letters cl"), any(RowMapper.class), eq(fixedProfile), eq(fixedApp), eq(fixedJob)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(browserDb.queryForObject(contains("from profiles p"), any(RowMapper.class), eq(fixedProfile)))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(browserDb.queryForList(contains("from audit_logs al"), eq(plan4Id), eq(fixedApp), eq(fixedProfile)))
                .thenReturn(List.of(Map.of("actor", "Jane Doe", "created_at", Timestamp.from(Instant.parse("2026-10-22T11:54:00Z")))));
        SubmissionEvidenceRepository.StoredEvidence storedUnivEvidence = new SubmissionEvidenceRepository.StoredEvidence(
                evidenceId, plan4Id, fixedApp, fixedProfile, "worker",
                SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE, "UNIVERSAL",
                "https://careers.acme.example/apply", "CONFIRMED", "APP-98765",
                List.of("CONFIRMATION_PAGE_URL_CHANGED", "EMPLOYER_CONFIRMATION_ID_OBSERVED"),
                "Browser observed submission on employer form.",
                "e".repeat(64), 120, "STORED_ENCRYPTED",
                Instant.parse("2026-10-22T12:00:00Z"), null);
        when(browserEvRepo.findForPlan(fixedProfile, fixedApp, plan4Id)).thenReturn(Optional.of(storedUnivEvidence));
        when(browserEvRepo.decryptRawEvidence(storedUnivEvidence))
                .thenReturn(Optional.of("{\"atsPlatform\":\"UNIVERSAL\",\"schemaVersion\":\"robin-submission-evidence-v1\"}"));
        var browserObserved = browserService.getReceipt(fixedProfile, fixedApp, plan4Id).orElseThrow();

        Map<String, Object> actualFixtures = new java.util.LinkedHashMap<>();
        actualFixtures.put("confirmed", confirmed);
        actualFixtures.put("reconciliation", reconciliation);
        actualFixtures.put("submittedUnverified", submittedUnverified);
        actualFixtures.put("failed", failed);
        actualFixtures.put("empty", empty);
        actualFixtures.put("browserObserved", browserObserved);

        String actualJson = json.writerWithDefaultPrettyPrinter().writeValueAsString(actualFixtures);
        Path backendFixture = Path.of("src/test/resources/contracts/submissionReceiptFixtures.json");
        assertThat(Files.exists(backendFixture)).isTrue();
        assertThat(json.readTree(actualJson))
                .isEqualTo(json.readTree(Files.readString(backendFixture, StandardCharsets.UTF_8)));

        Path frontendFixture = Path.of("../frontend/src/test/fixtures/submissionReceiptFixtures.json");
        assertThat(Files.exists(frontendFixture)).isTrue();
        assertThat(json.readTree(actualJson))
                .isEqualTo(json.readTree(Files.readString(frontendFixture, StandardCharsets.UTF_8)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void universalTamperedOrUnencryptedEvidenceFallsBackToReconciliationRequired() throws Exception {
        stubApplication();
        String univPlanJson = json.writeValueAsString(Map.of(
                "planType", "UNIVERSAL",
                "steps", List.of(Map.of("id", "apply", "type", "UNIVERSAL_APPLY", "params", Map.of()))
        ));
        when(db.query(contains("from automation_plans p"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("id")).thenReturn(PLAN_ID_1);
                    when(rs.getObject("application_id")).thenReturn(APP_ID);
                    when(rs.getString("target_url")).thenReturn("https://careers.acme.example/apply");
                    when(rs.getString("status")).thenReturn("SUBMITTED");
                    when(rs.getString("plan_json")).thenReturn(univPlanJson);
                    when(rs.getString("result_json")).thenReturn("[]");
                    when(rs.getBoolean("submit_approved")).thenReturn(true);
                    when(rs.getString("idempotency_key")).thenReturn("universal:" + APP_ID);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:05:00Z")));
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-01T10:07:00Z")));
                    return List.of(mapper.mapRow(rs, 1));
                });
        when(db.queryForMap(contains("from cv_versions where id"), eq(CV_VERSION_ID), eq(PROFILE_ID)))
                .thenReturn(Map.of("title", "Tailored Systems CV", "val", "{\"passed\":true}"));
        when(db.queryForList(contains("from audit_logs al"), eq(PLAN_ID_1), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(List.of(Map.of("actor", "ada@example.com", "created_at", Timestamp.from(Instant.parse("2026-10-01T10:06:00Z")))));

        SubmissionEvidenceRepository evRepo = mock(SubmissionEvidenceRepository.class);
        ApplicationReceiptService svc = new ApplicationReceiptService(db, json, mock(ReceiptCaptureService.class), evRepo);
        SubmissionEvidenceRepository.StoredEvidence tamperedEvidence = new SubmissionEvidenceRepository.StoredEvidence(
                UUID.randomUUID(), PLAN_ID_1, APP_ID, PROFILE_ID, "worker",
                SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE, "UNIVERSAL",
                "https://careers.acme.example/apply", "CONFIRMED", "EMP-1",
                List.of("UNIVERSAL_CONFIRMATION_DOM_MATCHED"), "obs",
                "a".repeat(64), 90, "STORED_ENCRYPTED", Instant.now(), null);
        when(evRepo.findForPlan(PROFILE_ID, APP_ID, PLAN_ID_1)).thenReturn(Optional.of(tamperedEvidence));
        when(evRepo.decryptRawEvidence(tamperedEvidence)).thenThrow(new com.personal.jobagent.track.receipt.EvidenceIntegrityException("tampered"));

        var receipt = svc.getReceipt(PROFILE_ID, APP_ID, PLAN_ID_1).orElseThrow();
        assertThat(receipt.verificationLevel()).isEqualTo("ATTEMPT_RECORDED");
        assertThat(receipt.confirmation().confirmed()).isFalse();
        assertThat(receipt.reconciliation().required()).isTrue();
        assertThat(receipt.reconciliation().reason()).isEqualTo("UNVERIFIED_RECEIPT_EVIDENCE");
    }
}
