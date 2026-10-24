package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        json = new ObjectMapper();
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
    void buildsCompleteReceiptForConfirmedSubmissionWithDocumentsAndIdentity() throws Exception {
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

        when(db.queryForList(contains("from audit_logs"), eq(PLAN_ID_1), eq(APP_ID)))
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

        // Platform confirmation evidence
        assertThat(receipt.confirmation().confirmed()).isTrue();
        assertThat(receipt.confirmation().confirmationId()).isEqualTo("WD-CONF-987654");
        assertThat(receipt.confirmation().observedSignals()).contains("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED");

        // Reconciliation & failure
        assertThat(receipt.reconciliation().required()).isFalse();
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

        String longStr = "A".repeat(300);
        String redacted = ApplicationReceiptService.redactSensitive(longStr);
        assertThat(redacted).hasSize(256);
        assertThat(redacted).endsWith("...");
    }
}
