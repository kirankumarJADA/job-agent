package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3B End-to-End Integration Test on real PostgreSQL 16 + Flyway V001–V070 + MockMvc:
 *
 * Proves the complete durable Universal Browser Engine HTTP/DB lifecycle:
 * - Ordinary non-ATS career URL produces {@code planType = "UNIVERSAL"} via {@code /api/v1/apply/applications/{id}/package}
 * - Worker claims plan, reports phase checkpoints ({@code INSPECTING}, {@code FILLING}, {@code UPLOADING})
 * - Discovered mandatory live-DOM question persisted into {@code application_answers} ({@code NEEDS_USER_INPUT})
 * - Readiness & approval & resume all fail closed until candidate answers & confirms (including fact-validator refusal)
 * - {@code POST /plans/{id}/resume} rebuilds confirmed Q&As and restores {@code PREPARED} with step checkpoints
 * - Two-step approval + {@code prepare-submit}, post-{@code prepare-submit} drift invalidation, duplicate pre-submit
 *   attempt fence ({@code 409}), stale crash quarantine ({@code RECONCILIATION_REQUIRED}), and verified
 *   submission recording encrypted {@code submission_evidence}, {@code applications.APPLICATION_SUBMITTED},
 *   and V043 single-submission enforcement while keeping V069 sandbox receipts distinct.
 * - Cross-candidate isolation and SSRF refusal.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.worker-event-token=" + UniversalBrowserE2EIT.WORKER_TOKEN,
        "app.secrets.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.sandbox.allow-local-test-hosts=true",
        "app.automation.universal-enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
class UniversalBrowserE2EIT {

    static final String WORKER_TOKEN = "universal-e2e-worker-token";
    private static final UUID GENERIC_SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000aa91");
    private static final UUID USER_B = UUID.fromString("00000000-0000-7000-8000-00000000aa98");
    private static final UUID PROFILE_B = UUID.fromString("00000000-0000-7000-8000-00000000aa99");

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
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AutomationPlanRepository planRepository;
    @Autowired private UniversalBrowserPlanService universalPlans;
    @Autowired private SubmissionEvidenceRepository evidenceRepository;

    private MockHttpSession devSession;
    private MockHttpSession sessionB;
    private UUID devProfileId;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult loginDev = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk()).andReturn();
        devSession = (MockHttpSession) loginDev.getRequest().getSession(false);
        devProfileId = jdbc.queryForObject(
                "select p.id from profiles p join users u on u.id = p.user_id where u.email = 'dev@example.local'",
                UUID.class);
        jdbc.update("""
                update profiles set phone = '+44 7700 900077', location = 'London, UK',
                    links = '{"linkedin":"https://linkedin.com/in/dev-candidate"}'::jsonb
                where id = ?
                """, devProfileId);

        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, ?, 'Candidate B', 'LOCAL') on conflict (email) do nothing
                """, USER_B, "univ-b@example.local", passwordEncoder.encode("PasswordB1!"));
        jdbc.update("insert into profiles (id, user_id) values (?, ?) on conflict do nothing", PROFILE_B, USER_B);
        MvcResult loginB = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"univ-b@example.local\",\"password\":\"PasswordB1!\"}"))
                .andExpect(status().isOk()).andReturn();
        sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'COMPANY_SITE', 'acme-universal', 'Acme Careers', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, GENERIC_SOURCE);
    }

    @Test
    void fullUniversalApplicationLifecycle_pauseResumeApprovePrepareSubmitAndEvidence() throws Exception {
        String targetUrl = "http://127.0.0.1:45555/careers/jobs/stage3-role/apply";
        var seeded = seedReadyApplication("univ-e2e-1", targetUrl);

        // 1. Build UNIVERSAL package through public /api/v1/apply endpoint
        MvcResult pkgRes = mvc.perform(post("/api/v1/apply/applications/{id}/package", seeded.appId())
                        .with(csrf()).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andReturn();
        UUID planId = UUID.fromString(String.valueOf(json.readValue(pkgRes.getResponse().getContentAsString(), Map.class).get("planId")));

        Map<String, Object> stored = json.readValue(
                jdbc.queryForObject("select plan::text from automation_plans where id = ?", String.class, planId), Map.class);
        assertThat(stored.get("planType")).isEqualTo("UNIVERSAL");
        assertThat(String.valueOf(stored.get("steps"))).contains("UNIVERSAL_APPLY").doesNotContain("SUBMIT");

        // 2. Worker claims plan -> RUNNING & reports page 1 phases + discovers unknown mandatory Page 2 question
        mvc.perform(post("/api/v1/automation/plans/claim-next")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId.toString()))
                .andExpect(jsonPath("$.plan.planType").value("UNIVERSAL"));

        // Verify artifact download streams verified SHA-256 CV bytes
        MvcResult cvDownload = mvc.perform(get("/api/v1/automation/plans/{id}/artifacts/cv", planId)
                        .param("versionId", seeded.cvVersionId().toString())
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cvDownload.getResponse().getHeader("X-Artifact-Sha256")).isEqualTo(seeded.cvSha());

        mvc.perform(post("/api/v1/automation/plans/{id}/steps", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "index", 10, "stepId", "phase-filling-p1", "status", "COMPLETED",
                                "result", Map.of("phase", "FILLING", "completedPage", 1, "currentUrl", targetUrl)))))
                .andExpect(status().isNoContent());

        // Candidate session cannot call worker-only /discovered-questions
        mvc.perform(post("/api/v1/automation/plans/{id}/discovered-questions", planId)
                        .with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageIndex\":2,\"questions\":[]}"))
                .andExpect(status().isForbidden());

        MvcResult discRes = mvc.perform(post("/api/v1/automation/plans/{id}/discovered-questions", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "formUrl", "http://127.0.0.1:45555/careers/jobs/stage3-role/apply/step-2",
                                "pageIndex", 2,
                                "questions", List.of(Map.of(
                                        "questionKey", "notice_period",
                                        "questionText", "What is your current notice period?",
                                        "htmlType", "text",
                                        "requiredState", "REQUIRED",
                                        "options", List.of()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persistedCount").value(1))
                .andReturn();
        UUID answerId = UUID.fromString(String.valueOf(((List<?>) json.readValue(
                discRes.getResponse().getContentAsString(), Map.class).get("answerIds")).get(0)));

        // Complete Pass 1 with HUMAN_REQUIRED & gap -> plan moves to AWAITING_APPROVAL
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "HUMAN_REQUIRED",
                                "detail", "Mandatory question on page 2 needs input",
                                "result", List.of(Map.of(
                                        "type", "UNIVERSAL_OUTCOME",
                                        "planType", "UNIVERSAL",
                                        "filled", List.of("First Name", "Last Name", "Email"),
                                        "requiredGaps", List.of(Map.of("questionKey", "notice_period", "questionText", "What is your current notice period?"))
                                ))))))
                .andExpect(status().isNoContent());

        // 3. Verify readiness shows surfaced question & blocker; resume & approve MUST BOTH FAIL CLOSED
        mvc.perform(get("/api/v1/apply/applications/{id}/readiness", seeded.appId()).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageReady").value(false))
                .andExpect(jsonPath("$.questions.items[0].answerId").value(answerId.toString()))
                .andExpect(jsonPath("$.questions.items[0].answerState").value("NEEDS_USER_INPUT"));

        mvc.perform(post("/api/v1/automation/plans/{id}/resume", planId).with(csrf()).session(devSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLY_NOT_READY"));
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(devSession))
                .andExpect(status().isConflict());

        // 4. Cross-user access fails closed (404), and fabricated factual claim on answer update fails validation (400)
        mvc.perform(post("/api/v1/automation/plans/{id}/resume", planId).with(csrf()).session(sessionB))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/application-answers/{id}", answerId).with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ANSWERED\",\"answerText\":\"I completed a PhD at MIT in 1998.\",\"confirmForAutofill\":true}"))
                .andExpect(status().isBadRequest());

        // 5. Candidate enters honest answer & confirms -> calls /resume -> plan becomes PREPARED with completedSteps
        mvc.perform(put("/api/v1/application-answers/{id}", answerId).with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ANSWERED\",\"answerText\":\"4 weeks\",\"confirmForAutofill\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.humanConfirmed").value(true));

        mvc.perform(post("/api/v1/automation/plans/{id}/resume", planId).with(csrf()).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.completedSteps").isArray());

        // Pass 2 worker claims resumed plan (carrying completedSteps + new confirmed answer) -> finishes review cleanly
        mvc.perform(post("/api/v1/automation/plans/claim-next")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId.toString()))
                .andExpect(jsonPath("$.completedSteps[0].stepKey").value("phase-filling-p1"))
                .andExpect(jsonPath("$.plan.package.confirmedAnswers[?(@.answerText=='4 weeks')]").isNotEmpty());

        mvc.perform(post("/api/v1/automation/plans/{id}/complete", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "HUMAN_REQUIRED",
                                "detail", "All fields filled and ready for approval",
                                "result", List.of(Map.of(
                                        "type", "UNIVERSAL_OUTCOME",
                                        "planType", "UNIVERSAL",
                                        "filled", List.of("First Name", "What is your current notice period?"),
                                        "requiredGaps", List.of()))))))
                .andExpect(status().isNoContent());

        // 6. Approve -> Prepare-submit -> Post-prepare-submit drift invalidation closes bypass window!
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_TO_SUBMIT"));
        mvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.submissionQueued").value(true));

        // Revoking CV review while PREPARED with submit_approved=true MUST strip UNIVERSAL_SUBMIT_CLICK & invalidate approval
        mvc.perform(put("/api/v1/resume-intelligence/cv/{id}/review", seeded.cvVersionId()).with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":false}"))
                .andExpect(status().isOk());
        Map<String, Object> invalidatedRow = jdbc.queryForMap(
                "select status, submit_approved, plan::text as plan_json from automation_plans where id = ?", planId);
        assertThat(invalidatedRow.get("status")).isEqualTo("AWAITING_APPROVAL");
        assertThat(invalidatedRow.get("submit_approved")).isEqualTo(false);
        assertThat(String.valueOf(invalidatedRow.get("plan_json"))).doesNotContain("UNIVERSAL_SUBMIT_CLICK");

        // Re-approve CV review -> approve-submit -> prepare-submit again
        mvc.perform(put("/api/v1/resume-intelligence/cv/{id}/review", seeded.cvVersionId()).with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(devSession))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(devSession))
                .andExpect(status().isOk());

        // 7. Pass 3 worker claims -> writes PRE_SUBMIT_ATTEMPTING fence (duplicate fence 409!) -> records SUBMITTED with encrypted evidence
        mvc.perform(post("/api/v1/automation/plans/{id}/claim", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "index", 99, "stepId", "pre-submit-attempting", "status", "RUNNING",
                                "result", Map.of("phase", "PRE_SUBMIT_ATTEMPTING")))))
                .andExpect(status().isNoContent());

        // Second attempt to record PRE_SUBMIT_ATTEMPTING on same plan MUST FAIL (409)
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "index", 99, "stepId", "pre-submit-attempting", "status", "RUNNING",
                                "result", Map.of("phase", "PRE_SUBMIT_ATTEMPTING")))))
                .andExpect(status().isConflict());

        // Candidate session cannot self-report SUBMITTED
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUBMITTED\",\"detail\":\"fake\"}"))
                .andExpect(status().isForbidden());

        // Authenticated worker completes with structured SUBMISSION_EVIDENCE
        String rawEv = json.writeValueAsString(Map.of(
                "schema", "robin-submission-evidence-v1",
                "planType", "UNIVERSAL",
                "confirmationNumber", "EMP-2026-8841",
                "httpStatus", 200));
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "SUBMITTED",
                                "detail", "Confirmed Universal submission (EMP-2026-8841)",
                                "result", List.of(Map.of(
                                        "type", "SUBMISSION_EVIDENCE",
                                        "planType", "UNIVERSAL",
                                        "category", "CONFIRMED",
                                        "confirmed", true,
                                        "confirmationNumber", "EMP-2026-8841",
                                        "signals", List.of("UNIVERSAL_SUBMISSION_RESPONSE_2XX", "UNIVERSAL_CONFIRMATION_DOM_MATCHED"),
                                        "evidence", "Confirmed HTTP 200 and confirmation page.",
                                        "rawEvidence", rawEv))))))
                .andExpect(status().isNoContent());

        // Assert PostgreSQL durability: SUBMITTED plan, APPLICATION_SUBMITTED app, encrypted submission_evidence, and separate non-sandbox receipt tier!
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId))
                .isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, seeded.appId()))
                .isEqualTo("APPLICATION_SUBMITTED");
        var storedEvidence = evidenceRepository.findForPlan(devProfileId, seeded.appId(), planId).orElseThrow();
        assertThat(storedEvidence.rawEvidenceStatus()).isEqualTo("STORED_ENCRYPTED");
        assertThat(evidenceRepository.decryptRawEvidence(storedEvidence).orElseThrow()).contains("EMP-2026-8841");

        // Tracking view shows APPLIED (unverified basis, since V069 sandbox receipts remain separate for non-sandbox sites)
        Integer receiptCount = jdbc.queryForObject("select count(*) from submission_receipts where plan_id = ?", Integer.class, planId);
        assertThat(receiptCount).isZero();
        mvc.perform(get("/api/v1/applications/{id}/track", seeded.appId()).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.basis").value("UNVERIFIED"))
                .andExpect(jsonPath("$.recordedStatus").value("APPLICATION_SUBMITTED"))
                .andExpect(jsonPath("$.submission.state").value("UNVERIFIED"))
                .andExpect(jsonPath("$.robinSubmission.submitted").value(true));
    }

    @Test
    void negativeBoundaries_staleCrashQuarantineSsrfAndDuplicateProtection() throws Exception {
        // 1. SSRF URL refused even with allowLocalTestHosts=true for metadata & private IP
        assertThat(universalPlans.handles("http://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(universalPlans.handles("https://10.0.0.1/careers/apply")).isFalse();

        // 2. Stale submit-capable RUNNING plan crashes -> recover-stale quarantines to RECONCILIATION_REQUIRED
        String url = "http://127.0.0.1:45556/careers/jobs/stage3-crash/apply";
        var crashSeed = seedReadyApplication("univ-crash-2", url);
        MvcResult res = mvc.perform(post("/api/v1/apply/applications/{id}/package", crashSeed.appId())
                        .with(csrf()).session(devSession))
                .andExpect(status().isOk()).andReturn();
        UUID crashPlanId = UUID.fromString(String.valueOf(json.readValue(res.getResponse().getContentAsString(), Map.class).get("planId")));
        mvc.perform(post("/api/v1/automation/plans/{id}/claim", crashPlanId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", crashPlanId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "HUMAN_REQUIRED", "detail", "Ready",
                                "result", List.of(Map.of("type", "UNIVERSAL_OUTCOME", "planType", "UNIVERSAL", "requiredGaps", List.of()))))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", crashPlanId).with(csrf()).session(devSession))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", crashPlanId).with(csrf()).session(devSession))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/claim", crashPlanId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());

        // Simulate stale worker crash by expiring heartbeat_at
        jdbc.update("update automation_plans set heartbeat_at = now() - interval '30 minutes' where id = ?", crashPlanId);
        mvc.perform(post("/api/v1/automation/plans/recover-stale")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, crashPlanId))
                .isEqualTo("RECONCILIATION_REQUIRED");
    }

    private record SeededApp(UUID jobId, UUID appId, UUID cvVersionId, String cvSha) {}

    private SeededApp seedReadyApplication(String tag, String applyUrl) throws Exception {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, status, content_hash,
                                  application_url)
                values (?, ?, ?, ?, 'Acme Corp', ?, 'London', 'Senior backend engineer role.', '{Java}'::text[],
                        'DISCOVERED', ?, ?)
                """, jobId, GENERIC_SOURCE, tag, "dedup-" + tag, "Role " + tag, "hash-" + tag, applyUrl);
        UUID appId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')
                """, appId, jobId, devProfileId, "identity-" + appId);

        byte[] pdf = ("%PDF-1.4\n%% cv " + tag).getBytes(StandardCharsets.UTF_8);
        String pdfSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        UUID fileId = UuidV7.generate();
        UUID cvId = UuidV7.generate();
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvId + ".pdf", pdfSha, "application/pdf", (long) pdf.length, "TAILORED_CV", pdf);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Tailored CV', 'CV text', '{}', false, 1, 'snap', ?, true, ?, now())
                """, cvId, jobId, appId, devProfileId, pdfSha, fileId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvId, appId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UuidV7.generate(), devProfileId, jobId, appId, "hash-" + cvId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", DocumentFactValidator.VERSION))), cvId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'owner', ?)
                on conflict (cv_version_id) do nothing
                """, cvId, devProfileId, pdfSha);

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("reusable", false);
        jdbc.update("""
                insert into application_answers (id, profile_id, job_id, application_id, question_text, question_type,
                                                 answer_text, confidence, status, validation_notes, human_confirmed,
                                                 answer_origin, created_at, updated_at)
                values (?, ?, ?, ?, 'Are you legally authorized to work in the United Kingdom?', 'STRUCTURED_FIELD',
                        'Yes', 1.0, 'ANSWERED', ?::jsonb, true, 'CANDIDATE_CONFIRMED', now(), now())
                """, UuidV7.generate(), devProfileId, jobId, appId, json.writeValueAsString(notes));

        return new SeededApp(jobId, appId, cvId, pdfSha);
    }
}
