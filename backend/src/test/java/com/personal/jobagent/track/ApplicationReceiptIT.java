package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class ApplicationReceiptIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private AuditLogWriter audit;

    private static final UUID SOURCE = UUID.randomUUID();
    private static final String WORKDAY_JOB_URL =
            "https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/Taiwan-Taipei/Senior-Systems-Architect_JR2024946";

    private MockHttpSession devSession;
    private UUID devProfileId;
    private MockHttpSession otherSession;
    private UUID otherProfileId;

    @BeforeEach
    void setUp() throws Exception {
        devSession = login("dev@example.local", "DevPassword123!");
        devProfileId = profileOf("dev@example.local");

        String devHash = jdbc.queryForObject("select password_hash from users where email = 'dev@example.local'", String.class);
        // Seed second candidate for cross-account isolation proof
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values ('00000000-0000-7000-8000-000000000099', 'other-receipt@example.local', ?, 'Other Candidate', 'LOCAL')
                on conflict (email) do update set password_hash = excluded.password_hash
                """, devHash);
        jdbc.update("""
                insert into profiles (id, user_id, setup_status, phone, location, links)
                values ('00000000-0000-7000-8000-000000000098', '00000000-0000-7000-8000-000000000099', 'READY', null, null, '{}')
                on conflict (id) do nothing
                """);
        otherProfileId = UUID.fromString("00000000-0000-7000-8000-000000000098");
        otherSession = login("other-receipt@example.local", "DevPassword123!");

        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'WORKDAY', 'receipt-it-org', 'Receipt IT Source', '{}', 'DISCOVERY_ONLY')
                on conflict (id) do nothing
                """, SOURCE);
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private UUID profileOf(String email) {
        return jdbc.queryForObject(
                "select p.id from profiles p join users u on u.id = p.user_id where u.email = ?", UUID.class, email);
    }

    private UUID seedJob(String title, String company, String applicationUrl) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, status, content_hash,
                                  application_url)
                values (?, ?, ?, ?, ?, ?, 'Taipei', 'Role desc', '{}'::text[], 'DISCOVERED', ?, ?)
                """, jobId, SOURCE, "ext-" + jobId, "dedup-" + jobId, company, title, "hash-" + jobId, applicationUrl);
        return jobId;
    }

    private UUID seedApplication(UUID profileId, UUID jobId) {
        UUID applicationId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')
                """, applicationId, jobId, profileId, "identity-" + applicationId);
        return applicationId;
    }

    private UUID seedCv(UUID profileId, UUID jobId, UUID applicationId, String title, String pdfContent) throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID cvVersionId = UUID.randomUUID();
        byte[] bytes = pdfContent.getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(bytes);

        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvVersionId + ".pdf", sha256, "application/pdf", (long) bytes.length, "TAILORED_CV", bytes);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', ?, 'CV markdown', '{"passed":true}', true, 1, 'snap', ?, true, ?, now())
                """, cvVersionId, jobId, applicationId, profileId, title, sha256, fileId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvVersionId, applicationId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UUID.randomUUID(), profileId, jobId, applicationId, "hash-" + cvVersionId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", com.personal.jobagent.documents.DocumentFactValidator.VERSION))), cvVersionId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'dev@example.local', ?)
                on conflict (cv_version_id) do nothing
                """, cvVersionId, profileId, sha256);
        return cvVersionId;
    }

    private UUID seedPlan(UUID profileId, UUID applicationId, String targetUrl, String status, boolean submitApproved,
                          String idempotencyKey, Map<String, Object> plan, List<Object> result) throws Exception {
        UUID planId = UUID.randomUUID();
        jdbc.update("""
                insert into automation_plans(id, profile_id, application_id, target_url, status, submit_approved,
                                             idempotency_key, plan, result, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, now(), now())
                """, planId, profileId, applicationId, targetUrl, status, submitApproved, idempotencyKey,
                json.writeValueAsString(plan), json.writeValueAsString(result));
        return planId;
    }

    private Map<String, Object> buildWorkdayOutcomeResult(String confirmationNumber, String rawEvidence,
                                                         String receiptSha256, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "WORKDAY_APPLICATION_OUTCOME");
        m.put("submitted", true);
        m.put("confirmationNumber", confirmationNumber);
        m.put("requisitionId", "JR2024946");
        m.put("rawEvidence", rawEvidence);
        m.put("receiptSha256", receiptSha256);
        m.put("evidenceProvenance", "AUTHENTICATED_BROWSER_WORKER");
        m.put("sandboxPosting", true);
        m.put("candidateAuthorizedSandbox", true);
        m.put("signals", List.of("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED"));
        m.put("summary", summary);
        return m;
    }

    @Test
    void returnsDurableReceiptForConfirmedSubmissionWithProvenanceAndEvidence() throws Exception {
        UUID jobId = seedJob("Staff Platform Engineer", "NVIDIA", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        UUID cvId = seedCv(devProfileId, jobId, applicationId, "Ada Lovelace Systems CV", "PDF-ADA-CV-BYTES");
        String cvSha = jdbc.queryForObject("select content_sha256 from cv_versions where id = ?", String.class, cvId);

        Map<String, Object> plan = Map.of(
                "planType", "WORKDAY",
                "package", Map.of(
                        "cv", Map.of("versionId", cvId.toString(), "sha256", cvSha, "fileName", "ada-cv.pdf", "byteSize", 1024),
                        "coverLetter", Map.of("versionId", UUID.randomUUID().toString(), "sha256", "cl-sha-9999", "fileName", "ada-cl.pdf", "byteSize", 512)
                ),
                "steps", List.of(
                        Map.of("id", "apply", "type", "WORKDAY_APPLY", "params", Map.of(
                                "identity", Map.of(
                                        "name", "Ada Lovelace",
                                        "firstName", "Ada",
                                        "lastName", "Lovelace",
                                        "email", "dev@example.local",
                                        "phone", "+44 7700 900123",
                                        "addressLine1", "London, UK",
                                        "linkedIn", "https://linkedin.com/in/ada"
                                )
                        ))
                )
        );

        String rawEvidence = "<div id='confirmation'>Thank you for applying. Confirmation: WD-2026-NVIDIA-7788</div>";
        String sha256 = sha256Hex(rawEvidence.getBytes(StandardCharsets.UTF_8));

        List<Object> result = List.of(
                buildWorkdayOutcomeResult("WD-2026-NVIDIA-7788", rawEvidence, sha256,
                        "Application confirmed by Workday requisition acknowledgment.")
        );

        UUID planId = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "SUBMITTED", true,
                "workday:" + applicationId, plan, result);

        // Record genuine candidate approval audit entry matching AutomationController.approve
        audit.write(new AuditEntry("dev@example.local", "WORKDAY_SUBMIT_APPROVED", "AUTOMATION_PLAN",
                planId, null, Map.of("approvedForSubmission", true, "submissionEnabled", false), null, UuidV7.generate()));
        // Record worker completion audit entry
        audit.write(new AuditEntry("worker", "AUTOMATION_PLAN_SUBMITTED", "AUTOMATION_PLAN",
                planId, null, Map.of("detail", "real submit confirmed"), null, UuidV7.generate()));

        // Fetch receipt via API
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId.toString()))
                .andExpect(jsonPath("$.jobTitle").value("Staff Platform Engineer"))
                .andExpect(jsonPath("$.company").value("NVIDIA"))
                .andExpect(jsonPath("$.atsKind").value("WORKDAY"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.submittedIdentity.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.submittedIdentity.firstName").value("Ada"))
                .andExpect(jsonPath("$.submittedIdentity.lastName").value("Lovelace"))
                .andExpect(jsonPath("$.submittedIdentity.phone").value("+44 7700 900123"))
                .andExpect(jsonPath("$.submittedIdentity.addressLine1").value("London, UK"))
                .andExpect(jsonPath("$.submittedIdentity.linkedIn").value("https://linkedin.com/in/ada"))
                .andExpect(jsonPath("$.cv.versionId").value(cvId.toString()))
                .andExpect(jsonPath("$.cv.sha256").value(cvSha))
                .andExpect(jsonPath("$.cv.title").value("Ada Lovelace Systems CV"))
                .andExpect(jsonPath("$.coverLetter.sha256").value("cl-sha-9999"))
                .andExpect(jsonPath("$.authorization.approved").value(true))
                .andExpect(jsonPath("$.authorization.actor").value("dev@example.local"))
                .andExpect(jsonPath("$.confirmation.confirmed").value(true))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("WD-2026-NVIDIA-7788"))
                .andExpect(jsonPath("$.reconciliation.required").value(false))
                .andExpect(jsonPath("$.failure.failed").value(false));
    }

    @Test
    void endToEndApprovalRouteProducesVerifiedGenuineReceipt() throws Exception {
        UUID jobId = seedJob("Principal Distributed Architect", "NVIDIA", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        UUID cvId = seedCv(devProfileId, jobId, applicationId, "Ada Lovelace Systems CV", "PDF-ADA-CV-BYTES");
        String cvSha = jdbc.queryForObject("select content_sha256 from cv_versions where id = ?", String.class, cvId);

        Map<String, Object> plan = Map.of(
                "planType", "WORKDAY",
                "correlation", Map.of("applicationId", applicationId.toString(), "jobId", jobId.toString()),
                "package", Map.of(
                        "cv", Map.of("versionId", cvId.toString(), "sha256", cvSha, "fileName", "ada-cv.pdf", "byteSize", 1024)
                ),
                "steps", List.of(
                        Map.of("id", "apply", "type", "WORKDAY_APPLY", "params", Map.of(
                                "identity", Map.of(
                                        "name", "Ada Lovelace",
                                        "firstName", "Ada",
                                        "lastName", "Lovelace",
                                        "email", "dev@example.local",
                                        "phone", "+44 7700 900123",
                                        "addressLine1", "London, UK",
                                        "linkedIn", "https://linkedin.com/in/ada"
                                )
                        ))
                )
        );

        // 1. Plan starts in AWAITING_APPROVAL with preparation outcome recorded
        List<Object> prepResult = List.of(Map.of(
                "type", "WORKDAY_APPLICATION_OUTCOME",
                "requiredGaps", List.of()
        ));
        UUID planId = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "AWAITING_APPROVAL", false,
                "workday:real-route:" + applicationId, plan, prepResult);

        // 2. Candidate explicitly approves via the REAL AutomationController approval route
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/approve-submit")
                        .session(devSession)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_TO_SUBMIT"))
                .andExpect(jsonPath("$.submissionEnabled").value(false));

        // Verify the plan has submit_approved = true in the database
        Boolean submitApproved = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(submitApproved).isTrue();

        // Verify audit log has WORKDAY_SUBMIT_APPROVED recorded by AutomationController
        Integer approvalCount = jdbc.queryForObject("""
                select count(*) from audit_logs
                where entity_id = ?
                  and action = 'WORKDAY_SUBMIT_APPROVED'
                  and (after_state ->> 'approvedForSubmission') = 'true'
                  and actor = 'dev@example.local'
                """, Integer.class, planId);
        assertThat(approvalCount).isGreaterThan(0);

        // 3. Worker completes submission with genuine confirmation evidence
        String rawDomEvidence = "<div id='confirmation'>Thank you for applying. Confirmation: WD-2026-NVIDIA-9999</div>";
        String evidenceSha = sha256Hex(rawDomEvidence.getBytes(StandardCharsets.UTF_8));
        List<Object> outcomeResult = List.of(
                buildWorkdayOutcomeResult("WD-2026-NVIDIA-9999", rawDomEvidence, evidenceSha,
                        "Confirmed with genuine Workday employer evidence.")
        );

        // Update plan with worker execution result in SUBMITTED state
        jdbc.update("""
                update automation_plans
                set status = 'SUBMITTED',
                    result = ?::jsonb,
                    updated_at = now()
                where id = ?
                """, json.writeValueAsString(outcomeResult), planId);

        // Record worker completion audit entry
        audit.write(new AuditEntry("worker", "AUTOMATION_PLAN_SUBMITTED", "AUTOMATION_PLAN",
                planId, null, Map.of("detail", "real submit confirmed"), null, UuidV7.generate()));

        // 4. Candidate retrieves the receipt through ApplicationReceiptService endpoint
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId.toString()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.authorization.approved").value(true))
                .andExpect(jsonPath("$.authorization.actor").value("dev@example.local"))
                .andExpect(jsonPath("$.confirmation.confirmed").value(true))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("WD-2026-NVIDIA-9999"))
                .andExpect(jsonPath("$.reconciliation.required").value(false))
                .andExpect(jsonPath("$.failure.failed").value(false));

        // 5. Verify durable receipt was persisted into audit_logs by ReceiptCaptureService
        Integer durableCount = jdbc.queryForObject("""
                select count(*) from audit_logs
                where entity_id = ?
                  and entity_type = 'APPLICATION_RECEIPT'
                  and action = 'SUBMISSION_RECEIPT_CAPTURED'
                """, Integer.class, applicationId);
        assertThat(durableCount).isGreaterThan(0);
    }

    @Test
    void preservesMultipleAttemptHistoryAndPermitsNavigatingToEarlierAttempt() throws Exception {
        UUID jobId = seedJob("Senior Engineer", "NVIDIA", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);

        // Attempt 1: Failed / Reconciliation Required
        Map<String, Object> plan1 = Map.of("planType", "WORKDAY", "steps", List.of(
                Map.of("id", "apply", "type", "WORKDAY_APPLY", "params", Map.of(
                        "identity", Map.of("name", "Ada Lovelace", "email", "dev@example.local")
                ))
        ));
        List<Object> result1 = List.of(Map.of(
                "type", "SUBMISSION_RECONCILIATION",
                "reason", "Server response timeout after 30000ms"
        ));
        UUID planId1 = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "RECONCILIATION_REQUIRED", true,
                "workday:" + applicationId, plan1, result1);

        // Attempt 2 (Retry): Confirmed
        Map<String, Object> plan2 = Map.of("planType", "WORKDAY", "steps", List.of(
                Map.of("id", "apply", "type", "WORKDAY_APPLY", "params", Map.of(
                        "identity", Map.of("name", "Ada Lovelace", "email", "dev@example.local")
                ))
        ));
        List<Object> result2 = List.of(Map.of(
                "type", "WORKDAY_APPLICATION_OUTCOME",
                "submitted", true,
                "confirmationNumber", "WD-RETRY-SUCCESS-123"
        ));
        UUID planId2 = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "SUBMITTED", true,
                "workday-retry:" + planId1, plan2, result2);

        // Default GET /receipt returns the latest attempt (Attempt 2)
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNumber").value(2))
                .andExpect(jsonPath("$.isLatest").value(true))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("WD-RETRY-SUCCESS-123"))
                .andExpect(jsonPath("$.attemptHistory.length()").value(2))
                .andExpect(jsonPath("$.attemptHistory[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.attemptHistory[0].status").value("RECONCILIATION_REQUIRED"))
                .andExpect(jsonPath("$.attemptHistory[1].attemptNumber").value(2))
                .andExpect(jsonPath("$.attemptHistory[1].status").value("SUBMITTED"));

        // GET /receipts/{planId1} returns Attempt 1 with original unconfirmed state intact
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipts/" + planId1).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNumber").value(1))
                .andExpect(jsonPath("$.isLatest").value(false))
                .andExpect(jsonPath("$.status").value("RECONCILIATION_REQUIRED"))
                .andExpect(jsonPath("$.submitted").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true))
                .andExpect(jsonPath("$.reconciliation.reason").value("Server response timeout after 30000ms"));

        // GET /receipts returns attempt list
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipts").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].attemptId").value(planId1.toString()))
                .andExpect(jsonPath("$.items[1].attemptId").value(planId2.toString()));
    }

    @Test
    void strictlyIsolatesReceiptsBetweenDifferentCandidateAccounts() throws Exception {
        UUID jobId = seedJob("Confidential Architect", "NVIDIA", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "SUBMITTED", true,
                "workday:" + applicationId, Map.of("planType", "WORKDAY"), List.of());

        // Owning candidate sees receipt
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession))
                .andExpect(status().isOk());

        // Other candidate receives 404 Not Found
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(otherSession))
                .andExpect(status().isNotFound());

        // Without session -> 401 Unauthorized
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redactsSensitiveTokensAndPasswordsInReceiptEvidence() throws Exception {
        UUID jobId = seedJob("Principal Security Engineer", "Acme Corp", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);

        Map<String, Object> plan = Map.of("planType", "WORKDAY", "steps", List.of());
        List<Object> result = List.of(
                Map.of(
                        "type", "WORKDAY_APPLICATION_OUTCOME",
                        "submitted", true,
                        "confirmationNumber", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sensitive_payload",
                        "summary", "Submitted successfully. Internal debug: password=SuperSecretToken123&session=999"
                )
        );

        seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "SUBMITTED", true,
                "workday-leak:" + applicationId, plan, result);

        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmationId").value("Bearer [REDACTED]"))
                .andExpect(jsonPath("$.confirmation.summary").value("Submitted successfully. Internal debug: password=[REDACTED]&session=999"));
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
