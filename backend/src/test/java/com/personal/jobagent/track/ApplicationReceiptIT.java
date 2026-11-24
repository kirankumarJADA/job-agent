package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.track.receipt.InvalidReceiptException;
import com.personal.jobagent.track.receipt.JdbcReceiptTrustStore;
import com.personal.jobagent.track.receipt.ReceiptCaptureService;
import com.personal.jobagent.track.receipt.ReceiptCaptureService.CaptureReceiptCommand;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wave 3 Sprint 2.2.1 — the production receipt path, end to end, against a real
 * PostgreSQL (Testcontainers) with the full Flyway chain (V001–V069).
 *
 * <p>The route-level test drives the real endpoints in order: candidate
 * approval → candidate prepare-submit → worker claim → worker completion
 * (bearer-token authenticated) → candidate receipt → candidate raw-evidence
 * read. Only the initial preparation state (job, application, CV, the
 * AWAITING_APPROVAL plan) and the candidate-owned sandbox registration are
 * seeded with JDBC. Nothing after the first route call is written directly:
 * the plan's SUBMITTED status, its result, the worker evidence, the receipt
 * and every audit row come from the production code paths.
 *
 * <p>No employer is contacted. The "worker" is this test, holding the worker
 * token, reporting synthetic-but-well-formed evidence for a candidate-owned
 * sandbox posting URL that is never fetched.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.worker-event-token=" + ApplicationReceiptIT.WORKER_TOKEN,
        // 32 ASCII bytes, base64 — a test-only AES-256 key for raw evidence at rest.
        "app.secrets.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
@Testcontainers
class ApplicationReceiptIT {

    static final String WORKER_TOKEN = "receipt-it-worker-token";

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
    @Autowired private JdbcReceiptTrustStore trustStore;
    @Autowired private ReceiptCaptureService captureService;
    @Autowired private com.personal.jobagent.security.SecretCipher cipher;

    private static final UUID SOURCE = UUID.randomUUID();
    private static final String WORKDAY_JOB_URL =
            "https://acme.wd5.myworkdayjobs.com/en-US/RobinCandidateSandbox/job/Remote/Robin-Sandbox-Posting_JR0001";
    private static final String SANDBOX_DOMAIN = "myworkdayjobs.com";

    private MockHttpSession devSession;
    private UUID devProfileId;
    private MockHttpSession otherSession;
    private UUID otherProfileId;

    @BeforeEach
    void setUp() throws Exception {
        devSession = login("dev@example.local", "DevPassword123!");
        devProfileId = profileOf("dev@example.local");

        String devHash = jdbc.queryForObject("select password_hash from users where email = 'dev@example.local'", String.class);
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

    // ── fixtures (initial state only) ───────────────────────────────────

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
                values (?, ?, ?, ?, ?, ?, 'Remote', 'Role desc', '{}'::text[], 'DISCOVERED', ?, ?)
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

    private UUID seedCv(UUID profileId, UUID jobId, UUID applicationId) throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID cvVersionId = UUID.randomUUID();
        byte[] bytes = ("PDF-ADA-CV-" + cvVersionId).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(bytes);
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvVersionId + ".pdf", sha256, "application/pdf", (long) bytes.length, "TAILORED_CV", bytes);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Ada Lovelace Systems CV', 'CV markdown', '{"passed":true}', true, 1, 'snap', ?, true, ?, now())
                """, cvVersionId, jobId, applicationId, profileId, sha256, fileId);
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

    private void registerSandbox(UUID profileId, String postingUrl) {
        jdbc.update("""
                insert into candidate_sandbox_registrations (id, profile_id, ats_platform, posting_url, permitted_domain, approved_by)
                values (?, ?, 'WORKDAY', ?, ?, 'dev@example.local')
                on conflict (profile_id, ats_platform, posting_url) do nothing
                """, UUID.randomUUID(), profileId, postingUrl, SANDBOX_DOMAIN);
    }

    /** A prepared WORKDAY application awaiting the candidate's approval — the state preparation leaves behind. */
    private record Prepared(UUID jobId, UUID applicationId, UUID planId, String targetUrl) {}

    private Prepared preparedWorkdayApplication(String title) throws Exception {
        UUID jobId = seedJob(title, "Acme Robotics", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        UUID cvId = seedCv(devProfileId, jobId, applicationId);
        String cvSha = jdbc.queryForObject("select content_sha256 from cv_versions where id = ?", String.class, cvId);
        String targetUrl = WORKDAY_JOB_URL + "/apply/" + applicationId;
        Map<String, Object> plan = Map.of(
                "planType", "WORKDAY",
                "correlation", Map.of("applicationId", applicationId.toString(), "jobId", jobId.toString()),
                "package", Map.of("cv", Map.of("versionId", cvId.toString(), "sha256", cvSha, "fileName", "ada-cv.pdf", "byteSize", 1024)),
                "steps", List.of(Map.of("id", "apply", "type", "WORKDAY_APPLY", "params", Map.of(
                        "identity", Map.of("name", "Ada Lovelace", "firstName", "Ada", "lastName", "Lovelace",
                                "email", "dev@example.local", "phone", "+44 7700 900123",
                                "addressLine1", "London, UK", "linkedIn", "https://linkedin.com/in/ada")))));
        UUID planId = seedPlan(devProfileId, applicationId, targetUrl, "AWAITING_APPROVAL", false,
                "workday:route:" + applicationId, plan,
                List.of(Map.of("type", "WORKDAY_APPLICATION_OUTCOME", "requiredGaps", List.of())));
        return new Prepared(jobId, applicationId, planId, targetUrl);
    }

    // ── production routes ───────────────────────────────────────────────

    /** Candidate approval → candidate prepare-submit → worker claim. Leaves the plan RUNNING. */
    private void approveQueueAndClaim(UUID planId) throws Exception {
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/approve-submit").session(devSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_TO_SUBMIT"));
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/prepare-submit").session(devSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionQueued").value(true));
        mvc.perform(post("/api/v1/automation/plans/" + planId + "/claim").header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    private static Map<String, Object> submissionEvidence(String confirmationNumber, String rawEvidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "SUBMISSION_EVIDENCE");
        m.put("planType", "WORKDAY");
        m.put("category", "CONFIRMED");
        m.put("confirmed", true);
        m.put("evidence", "a 2xx response to this attempt's submission request was observed and the confirmation page rendered");
        m.put("confirmationNumber", confirmationNumber);
        m.put("signals", List.of("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED"));
        m.put("rawEvidence", rawEvidence);
        m.put("summary", "Workday confirmation page rendered for the candidate-owned sandbox posting.");
        return m;
    }

    private MvcResult workerComplete(UUID planId, Object body) throws Exception {
        return mvc.perform(post("/api/v1/automation/plans/" + planId + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> submittedBody(Object... resultEntries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", "SUBMITTED");
        body.put("detail", "Submission confirmed: confirmation page rendered");
        body.put("result", resultEntries.length == 0 ? null : List.of(resultEntries));
        return body;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ── E: the genuine route-level test ─────────────────────────────────

    @Test
    void productionRouteFromApprovalThroughWorkerCompletionProducesAVerifiedDurableReceipt() throws Exception {
        Prepared p = preparedWorkdayApplication("Principal Distributed Architect");
        registerSandbox(devProfileId, p.targetUrl());
        approveQueueAndClaim(p.planId());

        String raw = "<div id='confirmation'>Thank you for applying. Confirmation: WD-2026-ACME-9999</div>";
        MvcResult completion = workerComplete(p.planId(), submittedBody(submissionEvidence("WD-2026-ACME-9999", raw)));
        assertThat(completion.getResponse().getStatus()).isEqualTo(204);

        // SUBMITTED and its structured result were persisted together by the route
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, p.planId()))
                .isEqualTo("SUBMITTED");
        String storedResult = jdbc.queryForObject("select result::text from automation_plans where id = ?", String.class, p.planId());
        assertThat(storedResult).contains("SUBMISSION_EVIDENCE", "evidenceId", "rawEvidenceSha256", "WORKER_COMPLETE_ENDPOINT");
        assertThat(storedResult).as("raw evidence never lands in the plan JSON").doesNotContain("Thank you for applying");

        // Worker evidence row: server-set principal and provenance, encrypted raw evidence, server digest
        Map<String, Object> ev = jdbc.queryForMap("""
                select worker_principal, recorded_via, raw_evidence_status, raw_evidence_sha256, raw_evidence_ciphertext
                from submission_evidence where plan_id = ?""", p.planId());
        assertThat(ev.get("worker_principal")).isEqualTo("worker");
        assertThat(ev.get("recorded_via")).isEqualTo("WORKER_COMPLETE_ENDPOINT");
        assertThat(ev.get("raw_evidence_status")).isEqualTo("STORED_ENCRYPTED");
        assertThat(String.valueOf(ev.get("raw_evidence_sha256")).trim()).isEqualTo(sha256Hex(raw.getBytes(StandardCharsets.UTF_8)));
        assertThat(String.valueOf(ev.get("raw_evidence_ciphertext"))).doesNotContain("Thank you");

        // The receipt was captured durably at completion time, exactly once, with its audit row
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isEqualTo(1);
        assertThat(count("""
                select count(*) from audit_logs where entity_id = ? and action = 'SUBMISSION_RECEIPT_CAPTURED'
                  and entity_type = 'APPLICATION_RECEIPT'""", p.applicationId())).isEqualTo(1);
        assertThat(count("""
                select count(*) from audit_logs where entity_id = ? and action = 'AUTOMATION_PLAN_SUBMITTED' and actor = 'worker'
                """, p.planId())).isEqualTo(1);
        Map<String, Object> approval = jdbc.queryForMap("""
                select id, created_at from audit_logs where entity_id = ? and action = 'WORKDAY_SUBMIT_APPROVED'""", p.planId());
        assertThat(jdbc.queryForObject("select approval_audit_id from submission_receipts where plan_id = ?", UUID.class, p.planId()))
                .isEqualTo(approval.get("id"));

        // The candidate's receipt endpoint shows it as genuinely confirmed
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.authorization.approved").value(true))
                .andExpect(jsonPath("$.authorization.actor").value("dev@example.local"))
                .andExpect(jsonPath("$.confirmation.confirmed").value(true))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("WD-2026-ACME-9999"))
                .andExpect(jsonPath("$.reconciliation.required").value(false))
                .andExpect(jsonPath("$.submittedIdentity.name").value("Ada Lovelace"));

        // The tracking view agrees: verified only because a durable receipt exists
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.basis").value("VERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("CONFIRMED"));

        // An attempt id that is not this application's is 404, never another attempt's receipt or evidence
        UUID unknownAttemptId = UUID.randomUUID();
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipts/" + unknownAttemptId).session(devSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipts/" + unknownAttemptId + "/evidence").session(devSession))
                .andExpect(status().isNotFound());

        // Reading it again does not create anything new
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession)).andExpect(status().isOk());
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isEqualTo(1);

        // Raw evidence is recoverable only through the owner's authorized path
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipts/" + p.planId() + "/evidence").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value(raw))
                .andExpect(jsonPath("$.sha256").value(sha256Hex(raw.getBytes(StandardCharsets.UTF_8))));
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipts/" + p.planId() + "/evidence").session(otherSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipts/" + p.planId() + "/evidence"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipts/" + p.planId() + "/evidence")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isUnauthorized());

        // A repeated worker completion cannot record a second submission
        MvcResult replay = workerComplete(p.planId(), submittedBody(submissionEvidence("WD-2026-ACME-9999", raw)));
        assertThat(replay.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("select count(*) from submission_evidence where plan_id = ?", p.planId())).isEqualTo(1);

        // Stored receipt and evidence rows are immutable
        assertThatThrownBy(() -> jdbc.update("update submission_receipts set employer_confirmation_id = 'X' where plan_id = ?", p.planId()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update submission_evidence set evidence_text = 'X' where plan_id = ?", p.planId()))
                .hasMessageContaining("immutable");
    }

    @Test
    void conflictingReplayAgainstACapturedReceiptIsRejectedDurably() throws Exception {
        Prepared p = preparedWorkdayApplication("Replay Engineer");
        registerSandbox(devProfileId, p.targetUrl());
        approveQueueAndClaim(p.planId());
        String raw = "<div id='confirmation'>Confirmation: WD-REPLAY-1</div>";
        assertThat(workerComplete(p.planId(), submittedBody(submissionEvidence("WD-REPLAY-1", raw))).getResponse().getStatus())
                .isEqualTo(204);

        String forged = "<div id='confirmation'>Confirmation: WD-REPLAY-2</div>";
        CaptureReceiptCommand conflicting = CaptureReceiptCommand.builder()
                .profileId(devProfileId).applicationId(p.applicationId()).planId(p.planId())
                .atsPlatform("WORKDAY").employerConfirmationId("WD-REPLAY-2")
                .rawEvidencePayload(forged).receiptSha256(ReceiptVerificationPolicy.computeEvidenceSha256(forged))
                .observedSignals(List.of("PLATFORM_SUBMISSION_CONFIRMED", "FORM_DISAPPEARED")).build();
        assertThatThrownBy(() -> captureService.captureReceipt(conflicting))
                .isInstanceOf(InvalidReceiptException.class)
                .satisfies(e -> assertThat(((InvalidReceiptException) e).getReasonCode()).isEqualTo("RECEIPT_REPLAY_REJECTED"));
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select employer_confirmation_id from submission_receipts where plan_id = ?",
                String.class, p.planId())).isEqualTo("WD-REPLAY-1");
    }

    @Test
    void concurrentDuplicateReceiptCapturesStoreExactlyOneReceipt() throws Exception {
        Prepared p = preparedWorkdayApplication("Concurrency Engineer");
        approveQueueAndClaim(p.planId());
        // No sandbox registration yet: the completion records SUBMITTED but cannot verify the receipt.
        String raw = "<div id='confirmation'>Confirmation: WD-CONCURRENT-1</div>";
        assertThat(workerComplete(p.planId(), submittedBody(submissionEvidence("WD-CONCURRENT-1", raw))).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isZero();

        registerSandbox(devProfileId, p.targetUrl());
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Integer> task = () -> {
                start.await();
                return mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdownNow();

        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isEqualTo(1);
        assertThat(count("""
                select count(*) from audit_logs where entity_id = ? and action = 'SUBMISSION_RECEIPT_CAPTURED'""",
                p.applicationId())).isEqualTo(1);
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(jsonPath("$.confirmation.confirmed").value(true));
    }

    @Test
    void databaseFailureDuringReceiptPersistenceCannotProduceAGenuineReceipt() throws Exception {
        Prepared p = preparedWorkdayApplication("Persistence Failure Engineer");
        approveQueueAndClaim(p.planId());
        String raw = "<div id='confirmation'>Confirmation: WD-DBFAIL-1</div>";
        assertThat(workerComplete(p.planId(), submittedBody(submissionEvidence("WD-DBFAIL-1", raw))).getResponse().getStatus())
                .isEqualTo(204);
        registerSandbox(devProfileId, p.targetUrl());

        jdbc.execute("""
                create or replace function receipt_it_fail_insert() returns trigger language plpgsql as $$
                begin raise exception 'simulated receipt store outage'; end; $$""");
        jdbc.execute("create trigger receipt_it_fail before insert on submission_receipts for each row execute function receipt_it_fail_insert()");
        try {
            mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                    .andExpect(jsonPath("$.reconciliation.required").value(true));
            assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isZero();
            assertThat(count("""
                    select count(*) from audit_logs where entity_id = ? and action = 'SUBMISSION_RECEIPT_CAPTURED'""",
                    p.applicationId())).isZero();
        } finally {
            jdbc.execute("drop trigger if exists receipt_it_fail on submission_receipts");
            jdbc.execute("drop function if exists receipt_it_fail_insert()");
        }
        // Once the store recovers, the same worker evidence verifies normally.
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(jsonPath("$.confirmation.confirmed").value(true));
    }

    // ── fail-closed completion and receipt behaviour ────────────────────

    @Test
    void submittedWithoutStructuredEvidenceRequiresReconciliationAndNeverReachesSubmitted() throws Exception {
        Prepared p = preparedWorkdayApplication("Missing Evidence Engineer");
        registerSandbox(devProfileId, p.targetUrl());
        approveQueueAndClaim(p.planId());

        MvcResult res = workerComplete(p.planId(), submittedBody());
        assertThat(res.getResponse().getStatus()).isEqualTo(422);
        assertThat(res.getResponse().getContentAsString()).contains("SUBMISSION_EVIDENCE_REQUIRED");
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, p.planId()))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(count("select count(*) from submission_evidence where plan_id = ?", p.planId())).isZero();

        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(jsonPath("$.submitted").value(false))
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));
        // And it is a dead end for automatic retry: the category is not a safe-retry category.
        mvc.perform(post("/api/v1/automation/plans/" + p.planId() + "/reconcile").session(devSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_EVIDENCE_INSUFFICIENT"));
    }

    @Test
    void workerEvidenceWithoutRawDomEvidenceIsRecordedButNeverConfirmed() throws Exception {
        Prepared p = preparedWorkdayApplication("No Raw Evidence Engineer");
        registerSandbox(devProfileId, p.targetUrl());
        approveQueueAndClaim(p.planId());

        Map<String, Object> entry = submissionEvidence("WD-NORAW-1", null);
        entry.remove("rawEvidence");
        assertThat(workerComplete(p.planId(), submittedBody(entry)).getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select raw_evidence_status from submission_evidence where plan_id = ?",
                String.class, p.planId())).isEqualTo("NOT_PROVIDED");

        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("WD-NORAW-1"))
                .andExpect(jsonPath("$.reconciliation.required").value(true))
                .andExpect(jsonPath("$.reconciliation.reason").value("UNVERIFIED_RECEIPT_EVIDENCE"));
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isZero();

        // The tracking view must not contradict the receipt: applied, but NOT verified
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"));
    }

    @Test
    void completionIsAtomicWhenTheAuditWriteFailsNothingIsRecorded() throws Exception {
        Prepared p = preparedWorkdayApplication("Atomic Completion Engineer");
        registerSandbox(devProfileId, p.targetUrl());
        approveQueueAndClaim(p.planId());
        String raw = "<div id='confirmation'>Confirmation: WD-ATOMIC-1</div>";

        jdbc.execute("""
                create or replace function receipt_it_fail_submitted_audit() returns trigger language plpgsql as $$
                begin
                  if new.action = 'AUTOMATION_PLAN_SUBMITTED' then raise exception 'simulated audit outage'; end if;
                  return new;
                end; $$""");
        jdbc.execute("create trigger receipt_it_fail_submitted_audit before insert on audit_logs "
                + "for each row execute function receipt_it_fail_submitted_audit()");
        try {
            MvcResult failed = workerComplete(p.planId(), submittedBody(submissionEvidence("WD-ATOMIC-1", raw)));
            assertThat(failed.getResponse().getStatus()).isEqualTo(503);
            assertThat(failed.getResponse().getContentAsString()).contains("SUBMISSION_RECORDING_UNAVAILABLE");
            // The evidence insert and the SUBMITTED transition rolled back with the failed audit write
            assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, p.planId()))
                    .isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("select result::text from automation_plans where id = ?", String.class, p.planId()))
                    .doesNotContain("SUBMISSION_EVIDENCE");
            assertThat(count("select count(*) from submission_evidence where plan_id = ?", p.planId())).isZero();
            assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isZero();
        } finally {
            jdbc.execute("drop trigger if exists receipt_it_fail_submitted_audit on audit_logs");
            jdbc.execute("drop function if exists receipt_it_fail_submitted_audit()");
        }
        // After recovery the same completion records normally, exactly once
        assertThat(workerComplete(p.planId(), submittedBody(submissionEvidence("WD-ATOMIC-1", raw))).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(count("select count(*) from submission_evidence where plan_id = ?", p.planId())).isEqualTo(1);
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isEqualTo(1);
    }

    @Test
    void tamperedStoredEvidenceIsDetectedAndNeverConfirmed() throws Exception {
        Prepared p = preparedWorkdayApplication("Tamper Detection Engineer");
        approveQueueAndClaim(p.planId());
        String raw = "<div id='confirmation'>Confirmation: WD-TAMPER-1</div>";
        assertThat(workerComplete(p.planId(), submittedBody(submissionEvidence("WD-TAMPER-1", raw))).getResponse().getStatus())
                .isEqualTo(204);

        // Simulate at-rest tampering: swap the ciphertext for a validly-encrypted different payload.
        jdbc.execute("alter table submission_evidence disable trigger submission_evidence_immutable");
        try {
            jdbc.update("update submission_evidence set raw_evidence_ciphertext = ? where plan_id = ?",
                    cipher.encrypt("<div id='confirmation'>Confirmation: WD-TAMPER-1 (edited)</div>"), p.planId());
        } finally {
            jdbc.execute("alter table submission_evidence enable trigger submission_evidence_immutable");
        }
        registerSandbox(devProfileId, p.targetUrl());

        MvcResult receiptRes = mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true))
                .andReturn();
        assertThat(receiptRes.getResponse().getContentAsString()).doesNotContain("(edited)");

        MvcResult evidenceRes = mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipts/" + p.planId() + "/evidence").session(devSession))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(evidenceRes.getResponse().getContentAsString())
                .contains("integrity check")
                .doesNotContain("WD-TAMPER-1", "(edited)", "payload", "submission_receipts", "receiptId");
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isZero();
    }

    @Test
    void verifiedReceiptsCannotBeDeletedAsASideEffectButProfilePurgeStillCascades() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, 'x', 'Purge Candidate', 'LOCAL')",
                userId, "purge-" + userId + "@example.local");
        jdbc.update("insert into profiles (id, user_id, setup_status, links) values (?, ?, 'READY', '{}')", profileId, userId);
        UUID jobId = seedJob("Purge Engineer", "Acme Robotics", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(profileId, jobId);
        UUID planId = seedPlan(profileId, applicationId, WORKDAY_JOB_URL, "SUBMITTED", true, "purge:" + applicationId,
                Map.of("planType", "WORKDAY", "steps", List.of()), List.of());
        UUID evidenceId = UUID.randomUUID();
        jdbc.update("""
                insert into submission_evidence (id, plan_id, application_id, profile_id, worker_principal, recorded_via,
                    ats_platform, target_url, confirmation_category, evidence_text, raw_evidence_status)
                values (?, ?, ?, ?, 'worker', 'WORKER_COMPLETE_ENDPOINT', 'WORKDAY', ?, 'CONFIRMED', 'schema test', 'NOT_PROVIDED')
                """, evidenceId, planId, applicationId, profileId, WORKDAY_JOB_URL);
        UUID sandboxId = UUID.randomUUID();
        jdbc.update("""
                insert into candidate_sandbox_registrations (id, profile_id, ats_platform, posting_url, permitted_domain, approved_by)
                values (?, ?, 'WORKDAY', ?, ?, 'purge')""", sandboxId, profileId, WORKDAY_JOB_URL, SANDBOX_DOMAIN);
        jdbc.update("""
                insert into submission_receipts (id, idempotency_key, plan_id, application_id, profile_id, attempt_number,
                    evidence_id, evidence_sha256, ats_platform, employer_confirmation_id, observed_signals, approval_audit_id,
                    approval_actor, approved_at, worker_principal, sandbox_registration_id, policy_version)
                values (?, ?, ?, ?, ?, 1, ?, ?, 'WORKDAY', 'WD-PURGE-1', '[]'::jsonb, ?, 'purge', now(), 'worker', ?, 'v1')
                """, UUID.randomUUID(), "receipt:plan:" + planId, planId, applicationId, profileId, evidenceId,
                "e".repeat(64), UUID.randomUUID(), sandboxId);

        assertThatThrownBy(() -> jdbc.update("delete from candidate_sandbox_registrations where id = ?", sandboxId))
                .hasMessageContaining("violates foreign key constraint");
        assertThatThrownBy(() -> jdbc.update("delete from submission_evidence where id = ?", evidenceId))
                .hasMessageContaining("violates foreign key constraint");
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", planId)).isEqualTo(1);

        // The profile purge (PurgeService deletes the profile row) still removes everything in one statement.
        jdbc.update("delete from profiles where user_id = ?", userId);
        assertThat(count("select count(*) from submission_receipts where profile_id = ?", profileId)).isZero();
        assertThat(count("select count(*) from submission_evidence where profile_id = ?", profileId)).isZero();
        assertThat(count("select count(*) from candidate_sandbox_registrations where profile_id = ?", profileId)).isZero();
    }

    @Test
    void callerForgedSandboxAndProvenanceFieldsAreStrippedAndCannotConfirmAReceipt() throws Exception {
        Prepared p = preparedWorkdayApplication("Forged Flags Engineer");
        // Deliberately NO sandbox registration.
        approveQueueAndClaim(p.planId());

        String raw = "<div id='confirmation'>Confirmation: WD-FORGED-1</div>";
        Map<String, Object> entry = submissionEvidence("WD-FORGED-1", raw);
        entry.put("sandboxPosting", true);
        entry.put("candidateAuthorizedSandbox", true);
        entry.put("evidenceProvenance", "AUTHENTICATED_BROWSER_WORKER");
        entry.put("receiptSha256", "f".repeat(64));
        assertThat(workerComplete(p.planId(), submittedBody(entry)).getResponse().getStatus()).isEqualTo(204);

        String storedResult = jdbc.queryForObject("select result::text from automation_plans where id = ?", String.class, p.planId());
        assertThat(storedResult).doesNotContain("sandboxPosting", "candidateAuthorizedSandbox", "evidenceProvenance", "ffffffff");

        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isZero();
    }

    @Test
    void candidateSessionCannotReportWorkerCompletionAndWorkerCannotApprove() throws Exception {
        Prepared p = preparedWorkdayApplication("Role Separation Engineer");

        // The worker token cannot approve a candidate's submission.
        mvc.perform(post("/api/v1/automation/plans/" + p.planId() + "/approve-submit")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        assertThat(jdbc.queryForObject("select submit_approved from automation_plans where id = ?", Boolean.class, p.planId()))
                .isFalse();

        approveQueueAndClaim(p.planId());

        // The candidate cannot report a real submission for their own plan.
        mvc.perform(post("/api/v1/automation/plans/" + p.planId() + "/complete").session(devSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submittedBody(submissionEvidence("WD-SELF-1",
                                "<div>Confirmation: WD-SELF-1</div>")))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, p.planId()))
                .isEqualTo("RUNNING");
        assertThat(count("select count(*) from submission_evidence where plan_id = ?", p.planId())).isZero();

        // Another candidate cannot see or act on it either.
        mvc.perform(post("/api/v1/automation/plans/" + p.planId() + "/complete").session(otherSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(submittedBody())))
                .andExpect(status().isNotFound());
    }

    // ── approval binding, against the real audit schema ─────────────────

    @Test
    void approvalBindingRejectsWrongActionActorStateCandidateAndPlan() throws Exception {
        UUID jobId = seedJob("Approval Binding Engineer", "Acme Robotics", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        Map<String, Object> plan = Map.of("planType", "WORKDAY", "steps", List.of());

        UUID workerActor = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL, "SUBMITTED", true, "ab-1:" + applicationId, plan, List.of());
        audit.write(new AuditEntry("worker", "WORKDAY_SUBMIT_APPROVED", "AUTOMATION_PLAN", workerActor, null,
                Map.of("approvedForSubmission", true), null, UuidV7.generate()));

        UUID notApproved = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL, "READY_TO_SUBMIT", true, "ab-2:" + applicationId, plan, List.of());
        audit.write(new AuditEntry("dev@example.local", "WORKDAY_SUBMIT_APPROVED", "AUTOMATION_PLAN", notApproved, null,
                Map.of("approvedForSubmission", false), null, UuidV7.generate()));

        UUID wrongAction = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL, "READY_TO_SUBMIT", true, "ab-3:" + applicationId, plan, List.of());
        audit.write(new AuditEntry("dev@example.local", "REAL_SUBMISSION_PREPARED", "AUTOMATION_PLAN", wrongAction, null,
                Map.of("approvedForSubmission", true), null, UuidV7.generate()));

        UUID wrongPlatform = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL, "READY_TO_SUBMIT", true, "ab-4:" + applicationId, plan, List.of());
        audit.write(new AuditEntry("dev@example.local", "GREENHOUSE_SUBMIT_APPROVED", "AUTOMATION_PLAN", wrongPlatform, null,
                Map.of("approvedForSubmission", true), null, UuidV7.generate()));

        UUID otherCandidateActor = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL, "READY_TO_SUBMIT", true, "ab-5:" + applicationId, plan, List.of());
        audit.write(new AuditEntry("other-receipt@example.local", "WORKDAY_SUBMIT_APPROVED", "AUTOMATION_PLAN", otherCandidateActor, null,
                Map.of("approvedForSubmission", true), null, UuidV7.generate()));

        UUID genuine = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL, "READY_TO_SUBMIT", true, "ab-6:" + applicationId, plan, List.of());
        audit.write(new AuditEntry("dev@example.local", "WORKDAY_SUBMIT_APPROVED", "AUTOMATION_PLAN", genuine, null,
                Map.of("approvedForSubmission", true), null, UuidV7.generate()));

        assertThat(trustStore.verifiedApproval(devProfileId, applicationId, workerActor)).isEmpty();
        assertThat(trustStore.verifiedApproval(devProfileId, applicationId, notApproved)).isEmpty();
        assertThat(trustStore.verifiedApproval(devProfileId, applicationId, wrongAction)).isEmpty();
        assertThat(trustStore.verifiedApproval(devProfileId, applicationId, wrongPlatform)).isEmpty();
        assertThat(trustStore.verifiedApproval(devProfileId, applicationId, otherCandidateActor)).isEmpty();
        assertThat(trustStore.verifiedApproval(devProfileId, applicationId, genuine)).isPresent()
                .get().satisfies(a -> assertThat(a.actor()).isEqualTo("dev@example.local"));
        // The genuine approval does not carry over to another plan, application or candidate.
        assertThat(trustStore.verifiedApproval(devProfileId, applicationId, UUID.randomUUID())).isEmpty();
        assertThat(trustStore.verifiedApproval(devProfileId, UUID.randomUUID(), genuine)).isEmpty();
        assertThat(trustStore.verifiedApproval(otherProfileId, applicationId, genuine)).isEmpty();
        assertThat(trustStore.ownsPlan(otherProfileId, applicationId, genuine)).isFalse();
    }

    // ── preserved behaviour: history, isolation, redaction ──────────────

    @Test
    void jdbcSeededSubmittedPlanWithSelfAssertedEvidenceIsNeverShownAsConfirmed() throws Exception {
        UUID jobId = seedJob("Staff Platform Engineer", "Acme Robotics", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        String raw = "<div id='confirmation'>Confirmation: WD-SEEDED-7788</div>";
        Map<String, Object> selfAsserted = new LinkedHashMap<>();
        selfAsserted.put("type", "WORKDAY_APPLICATION_OUTCOME");
        selfAsserted.put("submitted", true);
        selfAsserted.put("confirmationNumber", "WD-SEEDED-7788");
        selfAsserted.put("rawEvidence", raw);
        selfAsserted.put("receiptSha256", sha256Hex(raw.getBytes(StandardCharsets.UTF_8)));
        selfAsserted.put("evidenceProvenance", "AUTHENTICATED_BROWSER_WORKER");
        selfAsserted.put("sandboxPosting", true);
        selfAsserted.put("candidateAuthorizedSandbox", true);
        selfAsserted.put("signals", List.of("PLATFORM_SUBMISSION_CONFIRMED"));
        UUID planId = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL, "SUBMITTED", true,
                "seeded:" + applicationId, Map.of("planType", "WORKDAY", "steps", List.of()), List.of(selfAsserted));
        registerSandbox(devProfileId, WORKDAY_JOB_URL);
        audit.write(new AuditEntry("dev@example.local", "WORKDAY_SUBMIT_APPROVED", "AUTOMATION_PLAN",
                planId, null, Map.of("approvedForSubmission", true), null, UuidV7.generate()));
        audit.write(new AuditEntry("worker", "AUTOMATION_PLAN_SUBMITTED", "AUTOMATION_PLAN",
                planId, null, Map.of("detail", "self-asserted"), null, UuidV7.generate()));

        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.authorization.approved").value(true))
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("WD-SEEDED-7788"))
                .andExpect(jsonPath("$.reconciliation.required").value(true));
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", planId)).isZero();
    }

    @Test
    void preservesMultipleAttemptHistoryAndPermitsNavigatingToEarlierAttempt() throws Exception {
        UUID jobId = seedJob("Senior Engineer", "Acme Robotics", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        Map<String, Object> plan = Map.of("planType", "WORKDAY", "steps", List.of(
                Map.of("id", "apply", "type", "WORKDAY_APPLY", "params", Map.of(
                        "identity", Map.of("name", "Ada Lovelace", "email", "dev@example.local")))));
        UUID planId1 = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "RECONCILIATION_REQUIRED", true,
                "workday:" + applicationId, plan,
                List.of(Map.of("type", "SUBMISSION_RECONCILIATION", "reason", "Server response timeout after 30000ms")));
        UUID planId2 = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "SUBMITTED", true,
                "workday-retry:" + planId1, plan,
                List.of(Map.of("type", "WORKDAY_APPLICATION_OUTCOME", "submitted", true, "confirmationNumber", "WD-RETRY-SUCCESS-123")));

        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNumber").value(2))
                .andExpect(jsonPath("$.isLatest").value(true))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("WD-RETRY-SUCCESS-123"))
                .andExpect(jsonPath("$.attemptHistory.length()").value(2))
                .andExpect(jsonPath("$.attemptHistory[0].status").value("RECONCILIATION_REQUIRED"))
                .andExpect(jsonPath("$.attemptHistory[1].status").value("SUBMITTED"));

        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipts/" + planId1).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNumber").value(1))
                .andExpect(jsonPath("$.isLatest").value(false))
                .andExpect(jsonPath("$.submitted").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true))
                .andExpect(jsonPath("$.reconciliation.reason").value("Server response timeout after 30000ms"));

        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipts").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].attemptId").value(planId1.toString()))
                .andExpect(jsonPath("$.items[1].attemptId").value(planId2.toString()));
    }

    @Test
    void strictlyIsolatesReceiptsBetweenDifferentCandidateAccounts() throws Exception {
        UUID jobId = seedJob("Confidential Architect", "Acme Robotics", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        UUID planId = seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "SUBMITTED", true,
                "workday:" + applicationId, Map.of("planType", "WORKDAY"), List.of());

        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(otherSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipts/" + planId).session(otherSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipts").session(otherSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt")).andExpect(status().isUnauthorized());
    }

    @Test
    void redactsSensitiveTokensAndPasswordsInReceiptEvidence() throws Exception {
        UUID jobId = seedJob("Principal Security Engineer", "Acme Corp", WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(devProfileId, jobId);
        seedPlan(devProfileId, applicationId, WORKDAY_JOB_URL + "/apply", "SUBMITTED", true,
                "workday-leak:" + applicationId, Map.of("planType", "WORKDAY", "steps", List.of()),
                List.of(Map.of("type", "WORKDAY_APPLICATION_OUTCOME", "submitted", true,
                        "confirmationNumber", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sensitive_payload",
                        "summary", "Submitted successfully. Internal debug: password=SuperSecretToken123&session=999")));

        MvcResult res = mvc.perform(get("/api/v1/applications/" + applicationId + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.confirmation.confirmationId").value("Bearer [REDACTED]"))
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain("SuperSecretToken123", "sensitive_payload");
    }

    @Test
    void postCaptureTamperingInvalidatesBothStoredReceiptAndTrackingStatus() throws Exception {
        Prepared p = preparedWorkdayApplication("Post-Capture Integrity Engineer");
        registerSandbox(devProfileId, p.targetUrl());
        approveQueueAndClaim(p.planId());
        String raw = "<div id='confirmation'>Confirmation: WD-POST-1</div>";
        assertThat(workerComplete(p.planId(), submittedBody(submissionEvidence("WD-POST-1", raw))).getResponse().getStatus())
                .isEqualTo(204);

        // Before tampering: receipt is confirmed, 1 receipt row exists, and /track is VERIFIED.
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(true))
                .andExpect(jsonPath("$.reconciliation.required").value(false));
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isEqualTo(1);
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.basis").value("VERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("CONFIRMED"));

        // Post-capture tampering of encrypted evidence AFTER submission_receipts was written.
        jdbc.execute("alter table submission_evidence disable trigger submission_evidence_immutable");
        try {
            jdbc.update("update submission_evidence set raw_evidence_ciphertext = ? where plan_id = ?",
                    cipher.encrypt("<div id='confirmation'>Confirmation: WD-POST-1 (tampered)</div>"), p.planId());
        } finally {
            jdbc.execute("alter table submission_evidence enable trigger submission_evidence_immutable");
        }

        // Receipt endpoint must revalidate and refuse confirmed=true without leaking tampered text.
        MvcResult receiptRes = mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true))
                .andReturn();
        assertThat(receiptRes.getResponse().getContentAsString()).doesNotContain("(tampered)");

        // Tracking view must drop to UNVERIFIED and never trust submission_receipts row existence alone.
        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"))
                .andExpect(jsonPath("$.acknowledgement.state").value(org.hamcrest.Matchers.not("VERIFIED")));

        // Direct evidence retrieval must reject tampered ciphertext with 409 Conflict and zero leakage.
        MvcResult evidenceRes = mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipts/" + p.planId() + "/evidence").session(devSession))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(evidenceRes.getResponse().getContentAsString())
                .contains("integrity check")
                .doesNotContain("WD-POST-1", "(tampered)", "payload");
    }

    @Test
    void postCaptureReceiptDigestMismatchFailsClosedOnReceiptAndTracking() throws Exception {
        Prepared p = preparedWorkdayApplication("Digest Mismatch Engineer");
        registerSandbox(devProfileId, p.targetUrl());
        approveQueueAndClaim(p.planId());
        String raw = "<div id='confirmation'>Confirmation: WD-DIGEST-1</div>";
        assertThat(workerComplete(p.planId(), submittedBody(submissionEvidence("WD-DIGEST-1", raw))).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isEqualTo(1);

        jdbc.execute("alter table submission_receipts disable trigger submission_receipts_immutable");
        try {
            jdbc.update("update submission_receipts set evidence_sha256 = ? where plan_id = ?",
                    "f".repeat(64), p.planId());
        } finally {
            jdbc.execute("alter table submission_receipts enable trigger submission_receipts_immutable");
        }

        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));

        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"))
                .andExpect(jsonPath("$.acknowledgement.state").value(org.hamcrest.Matchers.not("VERIFIED")));
    }

    @Test
    void postCaptureMissingLinkedEvidenceOrMissingKeyFailsClosedOnReceiptAndTracking() throws Exception {
        Prepared p = preparedWorkdayApplication("Missing Evidence Engineer");
        registerSandbox(devProfileId, p.targetUrl());
        approveQueueAndClaim(p.planId());
        String raw = "<div id='confirmation'>Confirmation: WD-MISSING-1</div>";
        assertThat(workerComplete(p.planId(), submittedBody(submissionEvidence("WD-MISSING-1", raw))).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(count("select count(*) from submission_receipts where plan_id = ?", p.planId())).isEqualTo(1);

        // Missing key: real DB rows exist, but cipher has no decryption key.
        var noKeyEvidenceRepo = new com.personal.jobagent.track.receipt.SubmissionEvidenceRepository(
                jdbc, json, new com.personal.jobagent.security.SecretCipher(""));
        var noKeyCapture = new ReceiptCaptureService(
                new com.personal.jobagent.track.receipt.ReceiptVerificationPolicy(),
                trustStore,
                new com.personal.jobagent.track.receipt.JdbcReceiptStore(jdbc, json, audit, null),
                noKeyEvidenceRepo);
        assertThat(noKeyCapture.revalidateStoredReceipt(devProfileId, p.applicationId(), p.planId()).isInvalid()).isTrue();
        assertThat(noKeyCapture.isStoredReceiptIntegrityVerified(devProfileId, p.applicationId(), p.planId())).isFalse();

        // Point stored receipt at another plan's evidence_id (no receipt row on otherPlan -> satisfies FK & unique key, wrong plan scope).
        Prepared otherPlan = preparedWorkdayApplication("Other Plan Evidence");
        approveQueueAndClaim(otherPlan.planId());
        String otherRaw = "<div id='confirmation'>Confirmation: WD-OTHER-1</div>";
        assertThat(workerComplete(otherPlan.planId(), submittedBody(submissionEvidence("WD-OTHER-1", otherRaw)))
                .getResponse().getStatus()).isEqualTo(204);
        UUID foreignEvidenceId = jdbc.queryForObject(
                "select id from submission_evidence where plan_id = ?", UUID.class, otherPlan.planId());

        jdbc.execute("alter table submission_receipts disable trigger submission_receipts_immutable");
        try {
            jdbc.update("update submission_receipts set evidence_id = ? where plan_id = ?",
                    foreignEvidenceId, p.planId());
        } finally {
            jdbc.execute("alter table submission_receipts enable trigger submission_receipts_immutable");
        }

        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/receipt").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmation.confirmed").value(false))
                .andExpect(jsonPath("$.reconciliation.required").value(true));

        mvc.perform(get("/api/v1/applications/" + p.applicationId() + "/track").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"))
                .andExpect(jsonPath("$.acknowledgement.state").value(org.hamcrest.Matchers.not("VERIFIED")));
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
