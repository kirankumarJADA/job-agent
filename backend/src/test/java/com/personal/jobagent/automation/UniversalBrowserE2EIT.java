package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

import java.net.InetAddress;
import java.net.UnknownHostException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @TestConfiguration
    static class OfflineAddressGuardConfig {
        @Bean
        @Primary
        AddressGuard universalE2eAddressGuard() {
            return new AddressGuard(host -> {
                if ("portal.example.com".equalsIgnoreCase(host) || "careers.example.com".equalsIgnoreCase(host)) {
                    return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                }
                throw new UnknownHostException(host);
            });
        }
    }

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
    @Autowired private javax.sql.DataSource dataSource;

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

    @Test
    void stage3d_handoffResumeUpdatesSavedCredentialAndRefusesDuplicateOrPostSubmitResume() throws Exception {
        String targetUrl = "https://portal.example.com/careers/jobs/login-apply";
        var seeded = seedReadyApplication("univ-handoff-resume", targetUrl);

        // 1. Package initially has hasSavedCredential = false
        MvcResult pkgRes = mvc.perform(post("/api/v1/apply/applications/{id}/package", seeded.appId())
                        .with(csrf()).session(devSession))
                .andExpect(status().isOk())
                .andReturn();
        UUID planId = UUID.fromString(String.valueOf(json.readValue(pkgRes.getResponse().getContentAsString(), Map.class).get("planId")));
        Map<String, Object> initialPlan = json.readValue(
                jdbc.queryForObject("select plan::text from automation_plans where id = ?", String.class, planId), Map.class);
        assertThat(String.valueOf(initialPlan.get("steps"))).contains("hasSavedCredential=false");

        // 2. Worker claims plan and hits unresolved login/security gate -> initiates handoff
        mvc.perform(post("/api/v1/automation/plans/claim-next")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());

        MvcResult initRes = mvc.perform(post("/api/v1/handoff/initiate")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "planId", planId,
                                "applicationId", seeded.appId(),
                                "atsPlatform", "UNIVERSAL",
                                "targetUrl", targetUrl,
                                "blockReason", "UNIVERSAL_LOGIN_REQUIRED",
                                "ttlMinutes", 15))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blockReason").value("EMPLOYER_SECURITY_CHECK"))
                .andReturn();
        UUID sessionId = UUID.fromString(String.valueOf(json.readValue(initRes.getResponse().getContentAsString(), Map.class).get("id")));
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId))
                .isEqualTo("HANDOFF_REQUIRED");

        // 3. Cross-candidate and worker tokens cannot call candidate /handoff/{id}/resume
        mvc.perform(post("/api/v1/handoff/{id}/resume", sessionId).with(csrf()).session(sessionB))
                .andExpect(status().isForbidden());
        // WorkerEventTokenFilter does not authenticate worker tokens on candidate /handoff/{id}/resume -> 401
        mvc.perform(post("/api/v1/handoff/{id}/resume", sessionId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isUnauthorized());

        // 4. Candidate saves encrypted credential via API -> password never echoed on GET
        mvc.perform(post("/api/v1/employer-credentials").with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerDomain\":\"portal.example.com\",\"username\":\"ada@inbox.example\",\"password\":\"PortalSecret!2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true));
        String credListBody = mvc.perform(get("/api/v1/employer-credentials").session(devSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(credListBody).contains("portal.example.com").contains("ada@inbox.example").doesNotContain("PortalSecret!2026");

        // 5. Candidate resumes automation after resolving blocker -> PREPARED, hasSavedCredential=true, NOT APPLICATION_SUBMITTED
        mvc.perform(post("/api/v1/handoff/{id}/resume", sessionId).with(csrf()).session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planStatus").value("PREPARED"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, seeded.appId()))
                .isEqualTo("READY_TO_APPLY");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ATS_HANDOFF_RESUMED' and entity_id = ?",
                Integer.class, planId)).isEqualTo(1);

        // Second /resume call on already-completed session fails closed with 409
        mvc.perform(post("/api/v1/handoff/{id}/resume", sessionId).with(csrf()).session(devSession))
                .andExpect(status().isConflict());

        // Worker claims resumed plan -> hasSavedCredential is now true, and worker /resolve decrypts secret
        MvcResult claimedResumed = mvc.perform(post("/api/v1/automation/plans/claim-next")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(claimedResumed.getResponse().getContentAsString()).contains("\"hasSavedCredential\":true");
        mvc.perform(post("/api/v1/employer-credentials/resolve")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("applicationId", seeded.appId(), "employerDomain", "portal.example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ada@inbox.example"))
                .andExpect(jsonPath("$.password").value("PortalSecret!2026"));

        // 6. Safety boundary: handoff AFTER PRE_SUBMIT_ATTEMPTING cannot be resumed back to PREPARED
        String fencedUrl = "https://careers.example.com/jobs/fenced";
        var fenced = seedReadyApplication("univ-handoff-fence", fencedUrl);
        UUID fencedPlanId = UUID.fromString(String.valueOf(json.readValue(
                mvc.perform(post("/api/v1/apply/applications/{id}/package", fenced.appId()).with(csrf()).session(devSession))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Map.class).get("planId")));
        mvc.perform(post("/api/v1/automation/plans/claim-next").header("Authorization", "Bearer " + WORKER_TOKEN)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", fencedPlanId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("outcome", "HUMAN_REQUIRED", "detail", "Ready",
                                "result", List.of(Map.of("type", "UNIVERSAL_OUTCOME", "planType", "UNIVERSAL", "requiredGaps", List.of()))))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", fencedPlanId).with(csrf()).session(devSession)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", fencedPlanId).with(csrf()).session(devSession)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/claim-next").header("Authorization", "Bearer " + WORKER_TOKEN)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", fencedPlanId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("index", 99, "stepId", "pre-submit-attempting", "status", "RUNNING",
                                "result", Map.of("phase", "PRE_SUBMIT_ATTEMPTING")))))
                .andExpect(status().isNoContent());
        UUID fencedSessionId = UUID.fromString(String.valueOf(json.readValue(
                mvc.perform(post("/api/v1/handoff/initiate")
                                .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "planId", fencedPlanId, "applicationId", fenced.appId(), "atsPlatform", "UNIVERSAL",
                                        "targetUrl", fencedUrl, "blockReason", "MFA", "ttlMinutes", 15))))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), Map.class).get("id")));
        mvc.perform(post("/api/v1/handoff/{id}/resume", fencedSessionId).with(csrf()).session(devSession))
                .andExpect(status().isConflict());
    }

    // ── Stage 3F: security hardening against real PostgreSQL ─────────────────

    private UUID packageAndClaim(SeededApp seeded) throws Exception {
        MvcResult pkgRes = mvc.perform(post("/api/v1/apply/applications/{id}/package", seeded.appId())
                        .with(csrf()).session(devSession))
                .andExpect(status().isOk()).andReturn();
        UUID planId = UUID.fromString(String.valueOf(json.readValue(pkgRes.getResponse().getContentAsString(), Map.class).get("planId")));
        mvc.perform(post("/api/v1/automation/plans/{id}/claim", planId).header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());
        return planId;
    }

    /** A UNIVERSAL submit pass as the real flow leaves it: reviewed, approved, queued and claimed (RUNNING, approved). */
    private UUID approvedSubmitPass(SeededApp seeded) throws Exception {
        UUID planId = packageAndClaim(seeded);
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", planId)
                        .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("outcome", "HUMAN_REQUIRED", "detail", "Ready",
                                "result", List.of(Map.of("type", "UNIVERSAL_OUTCOME", "planType", "UNIVERSAL", "requiredGaps", List.of()))))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(devSession)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(devSession)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/claim", planId).header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());
        Map<String, Object> row = jdbc.queryForMap("select status, submit_approved from automation_plans where id = ?", planId);
        assertThat(row.get("status")).isEqualTo("RUNNING");
        assertThat(row.get("submit_approved")).isEqualTo(true);
        return planId;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder fenceRequest(UUID planId) throws Exception {
        return post("/api/v1/automation/plans/{id}/steps", planId)
                .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("index", 99, "stepId", "pre-submit-attempting", "status", "RUNNING",
                        "result", Map.of("phase", "PRE_SUBMIT_ATTEMPTING"))));
    }

    private int fenceCount(UUID planId) {
        return jdbc.queryForObject("""
                select count(*) from automation_steps where plan_id = ?
                  and (step_key = 'pre-submit-attempting' or result->>'phase' = 'PRE_SUBMIT_ATTEMPTING')
                """, Integer.class, planId);
    }

    private int invalidationAudits(UUID appId) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'PACKAGE_APPROVAL_INVALIDATED' and entity_id = ?",
                Integer.class, appId);
    }

    /** Waits until some backend statement matching {@code fragment} is blocked on a lock (real PostgreSQL row lock). */
    private void awaitLockWait(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where wait_event_type = 'Lock' and query like ? and pid <> pg_backend_pid()
                    """, Integer.class, "%" + fragment + "%");
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("no statement containing '" + fragment + "' ever waited on the plan-row lock");
    }

    /** Test isolation: never leave a PREPARED/RUNNING plan behind for another test's claim-next. */
    private void retire(UUID... planIds) {
        for (UUID id : planIds) jdbc.update("update automation_plans set status = 'ABANDONED' where id = ?", id);
    }

    private int resolveStatus(UUID appId, String domain) throws Exception {
        return mvc.perform(post("/api/v1/employer-credentials/resolve")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("applicationId", appId, "employerDomain", domain))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void stage3f_credentialResolveIsBoundToTheApplicationsEmployerDomainAndActiveRun() throws Exception {
        var seeded = seedReadyApplication("univ-3f-cred", "https://careers.example.com/jobs/cred-binding");
        for (String domain : List.of("careers.example.com", "jobs.other-employer.example")) {
            mvc.perform(post("/api/v1/employer-credentials").with(csrf()).session(devSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("employerDomain", domain,
                                    "username", "ada@" + domain, "password", "Secret-" + domain))))
                    .andExpect(status().isOk());
        }
        MvcResult pkgRes = mvc.perform(post("/api/v1/apply/applications/{id}/package", seeded.appId())
                        .with(csrf()).session(devSession))
                .andExpect(status().isOk()).andReturn();
        UUID planId = UUID.fromString(String.valueOf(json.readValue(pkgRes.getResponse().getContentAsString(), Map.class).get("planId")));

        // Not running yet: no credential leaves the database.
        assertThat(resolveStatus(seeded.appId(), "careers.example.com")).isEqualTo(409);

        mvc.perform(post("/api/v1/automation/plans/{id}/claim", planId).header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/employer-credentials/resolve")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("applicationId", seeded.appId(), "employerDomain", "careers.example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ada@careers.example.com"));
        // The candidate DOES have a credential for that other employer, but this application is not that employer.
        assertThat(resolveStatus(seeded.appId(), "jobs.other-employer.example")).isEqualTo(403);
        for (String lookalike : List.of("careers.example.com.evil.net", "evilcareers.example.com", "login.careers.example.com", "example.com")) {
            assertThat(resolveStatus(seeded.appId(), lookalike)).as(lookalike).isEqualTo(403);
        }
        assertThat(resolveStatus(UUID.randomUUID(), "careers.example.com")).isEqualTo(404);
        // Candidate sessions can never resolve (worker-only boundary).
        mvc.perform(post("/api/v1/employer-credentials/resolve").with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("applicationId", seeded.appId(), "employerDomain", "careers.example.com"))))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        // Disabled credential: 404, never a stale secret.
        mvc.perform(delete("/api/v1/employer-credentials/{d}", "careers.example.com").with(csrf()).session(devSession))
                .andExpect(status().isOk());
        assertThat(resolveStatus(seeded.appId(), "careers.example.com")).isEqualTo(404);
        mvc.perform(delete("/api/v1/employer-credentials/{d}", "jobs.other-employer.example").with(csrf()).session(devSession))
                .andExpect(status().isOk());
        retire(planId);
    }

    @Test
    void stage3f_concurrentPreSubmitFenceAcceptsExactlyOneAttempt() throws Exception {
        var seeded = seedReadyApplication("univ-3f-fence", "https://careers.example.com/jobs/fence-race");
        // Stage 3I Phase 6: the fence needs a standing human approval, so race on a real approved submit pass.
        UUID planId = approvedSubmitPass(seeded);
        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/automation/plans/{id}/steps", planId)
                                .header("Authorization", "Bearer " + WORKER_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "index", 90 + n, "stepId", n % 2 == 0 ? "pre-submit-attempting" : "racer-" + n,
                                        "status", "COMPLETED", "result", Map.of("phase", "PRE_SUBMIT_ATTEMPTING")))))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new java.util.ArrayList<>();
        for (var f : results) statuses.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();
        assertThat(statuses.stream().filter(s -> s == 204).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("""
                select count(*) from automation_steps where plan_id = ?
                  and (step_key = 'pre-submit-attempting' or result->>'phase' = 'PRE_SUBMIT_ATTEMPTING')
                """, Integer.class, planId)).isEqualTo(1);
        retire(planId);
    }

    @Test
    void stage3f_resumeIsRefusedOnceTheApplicationWasSubmittedByAnyAttempt() throws Exception {
        var control = seedReadyApplication("univ-3f-resume-ok", "https://careers.example.com/jobs/resume-ok");
        var submitted = seedReadyApplication("univ-3f-resume-sub", "https://careers.example.com/jobs/resume-sub");
        UUID controlPlan = packageAndClaim(control);
        UUID submittedPlan = packageAndClaim(submitted);
        for (UUID planId : List.of(controlPlan, submittedPlan)) {
            mvc.perform(post("/api/v1/automation/plans/{id}/complete", planId)
                            .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("outcome", "HUMAN_REQUIRED", "detail", "paused",
                                    "result", List.of(Map.of("type", "UNIVERSAL_OUTCOME", "planType", "UNIVERSAL", "requiredGaps", List.of()))))))
                    .andExpect(status().isNoContent());
        }
        // Fixture: another attempt for this application already reached the employer.
        jdbc.update("update applications set status = 'APPLICATION_SUBMITTED' where id = ?", submitted.appId());

        Map<String, Object> rebuilt = Map.of("planType", "UNIVERSAL", "steps", List.of());
        assertThat(planRepository.resumeUniversalPlan(submittedPlan, rebuilt)).isFalse();
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, submittedPlan))
                .isEqualTo("AWAITING_APPROVAL");
        jdbc.update("update automation_plans set status = 'HANDOFF_REQUIRED' where id = ?", submittedPlan);
        assertThat(planRepository.resumeFromHandoff(submittedPlan, rebuilt)).isFalse();
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, submittedPlan))
                .isEqualTo("HANDOFF_REQUIRED");
        // Control: the same guarded update still works for an application nobody has submitted.
        assertThat(planRepository.resumeUniversalPlan(controlPlan, rebuilt)).isTrue();
        retire(controlPlan, submittedPlan);
    }

    @Test
    void stage3f_handoffLinkStaysOnTheEmployerHostAndConcurrentResumeSucceedsOnce() throws Exception {
        String targetUrl = "https://portal.example.com/careers/jobs/race-resume";
        var seeded = seedReadyApplication("univ-3f-handoff", targetUrl);
        UUID planId = packageAndClaim(seeded);
        Map<String, Object> base = Map.of("planId", planId, "applicationId", seeded.appId(), "atsPlatform", "UNIVERSAL",
                "blockReason", "UNIVERSAL_LOGIN_REQUIRED", "ttlMinutes", 15);
        Map<String, Object> phishing = new LinkedHashMap<>(base);
        phishing.put("targetUrl", "https://portal-example.login-check.net/careers/jobs/race-resume");
        int refused = mvc.perform(post("/api/v1/handoff/initiate").header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(phishing)))
                .andReturn().getResponse().getStatus();
        assertThat(refused).isBetween(400, 499);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId)).isEqualTo("RUNNING");

        Map<String, Object> genuine = new LinkedHashMap<>(base);
        genuine.put("targetUrl", targetUrl);
        MvcResult initRes = mvc.perform(post("/api/v1/handoff/initiate").header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(genuine)))
                .andExpect(status().isCreated()).andReturn();
        UUID sessionId = UUID.fromString(String.valueOf(json.readValue(initRes.getResponse().getContentAsString(), Map.class).get("id")));

        int threads = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/handoff/{id}/resume", sessionId).with(csrf()).session(devSession))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new java.util.ArrayList<>();
        for (var f : results) statuses.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();
        assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(statuses.stream().allMatch(s -> s == 200 || s == 409)).as(statuses.toString()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ATS_HANDOFF_RESUMED' and entity_id = ?",
                Integer.class, planId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId)).isEqualTo("PREPARED");
        retire(planId);
    }

    @Test
    void stage3f_applyPackageAuditStoresARedactedApplicationUrl() throws Exception {
        var seeded = seedReadyApplication("univ-3f-audit",
                "https://careers.example.com/jobs/audit-redact?token=SECRET-TOKEN-123&email=dev@example.local#section");
        MvcResult pkg = mvc.perform(post("/api/v1/apply/applications/{id}/package", seeded.appId()).with(csrf()).session(devSession))
                .andExpect(status().isOk()).andReturn();
        retire(UUID.fromString(String.valueOf(json.readValue(pkg.getResponse().getContentAsString(), Map.class).get("planId"))));
        String after = jdbc.queryForObject("""
                select after_state::text from audit_logs where action = 'APPLY_PACKAGE_CREATED' and entity_id = ?
                order by created_at desc limit 1""", String.class, seeded.appId());
        assertThat(after).contains("https://careers.example.com/jobs/audit-redact")
                .doesNotContain("SECRET-TOKEN-123").doesNotContain("dev@example.local").doesNotContain("#section");
    }

    /**
     * Stage 3I Phase 6 (APPLY audit finding): withdrawing a document or answer approval after the worker
     * claimed the submit pass, but before the PRE_SUBMIT_ATTEMPTING fence, must stop the click. Before this
     * phase only PREPARED/READY_TO_SUBMIT approvals were withdrawn and the fence never re-checked approval.
     */
    @Test
    void stage3iPhase6_approvalWithdrawnBeforeTheFenceRefusesTheClickAndAnAttemptInFlightIsNeverUnwound() throws Exception {
        // A. Withdrawn while RUNNING, before the fence: approval cleared, fence refused, no SUBMITTED, never APPLIED.
        var early = seedReadyApplication("univ-p6-withdraw", "https://careers.example.com/jobs/withdraw-early");
        UUID earlyPlan = approvedSubmitPass(early);
        mvc.perform(put("/api/v1/resume-intelligence/cv/{id}/review", early.cvVersionId()).with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":false}"))
                .andExpect(status().isOk());
        Map<String, Object> row = jdbc.queryForMap("select status, submit_approved from automation_plans where id = ?", earlyPlan);
        assertThat(row.get("status")).isEqualTo("RUNNING");
        assertThat(row.get("submit_approved")).isEqualTo(false);
        assertThat(invalidationAudits(early.appId())).isEqualTo(1);
        mvc.perform(fenceRequest(earlyPlan))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMIT_APPROVAL_WITHDRAWN"));
        assertThat(fenceCount(earlyPlan)).isZero();
        // Even a worker that ignored the 409 and reported SUBMITTED is refused: completion needs the approval.
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", earlyPlan)
                        .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("outcome", "SUBMITTED", "detail", "Confirmed (EMP-2026-0001)",
                                "result", List.of(Map.of("type", "SUBMISSION_EVIDENCE", "planType", "UNIVERSAL",
                                        "category", "CONFIRMED", "confirmed", true, "confirmationNumber", "EMP-2026-0001",
                                        "signals", List.of("UNIVERSAL_SUBMISSION_RESPONSE_2XX"), "evidence", "x",
                                        "rawEvidence", "{\"schema\":\"robin-submission-evidence-v1\"}"))))))
                .andExpect(status().isConflict());
        // What the real worker does on that 409: no click, an honest unverified report for a human to resolve.
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", earlyPlan)
                        .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("outcome", "RECONCILIATION_REQUIRED",
                                "detail", "Backend refused PRE_SUBMIT_ATTEMPTING fence (409 conflict)",
                                "result", List.of(Map.of("type", "SUBMISSION_EVIDENCE", "planType", "UNIVERSAL",
                                        "category", "PRIOR_ATTEMPT_UNVERIFIED", "confirmed", false,
                                        "evidence", "Backend refused PRE_SUBMIT_ATTEMPTING fence (409 conflict)"))))))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, earlyPlan))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, early.appId()))
                .isNotEqualTo("APPLICATION_SUBMITTED");
        Map<?, ?> track = json.readValue(mvc.perform(get("/api/v1/applications/{id}/track", early.appId()).session(devSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Map.class);
        assertThat(track.get("status")).isNotEqualTo("APPLIED");
        assertThat(((Map<?, ?>) track.get("robinSubmission")).get("submitted")).isEqualTo(false);

        // B. Fence first: the attempt is in flight and a later edit never unwinds it (nor claims it did).
        var inflight = seedReadyApplication("univ-p6-inflight", "https://careers.example.com/jobs/withdraw-late");
        UUID inflightPlan = approvedSubmitPass(inflight);
        mvc.perform(fenceRequest(inflightPlan)).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/resume-intelligence/cv/{id}/review", inflight.cvVersionId()).with(csrf()).session(devSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":false}"))
                .andExpect(status().isOk());
        row = jdbc.queryForMap("select status, submit_approved from automation_plans where id = ?", inflightPlan);
        assertThat(row.get("status")).isEqualTo("RUNNING");
        assertThat(row.get("submit_approved")).isEqualTo(true);
        assertThat(invalidationAudits(inflight.appId())).isZero();

        // C. Lock ordering on real PostgreSQL, both directions.
        // C1. A withdrawal holding the plan-row lock is seen by a concurrent fence (re-checked on the updated row).
        var lockedFirst = seedReadyApplication("univ-p6-lock-a", "https://careers.example.com/jobs/lock-a");
        UUID planA = approvedSubmitPass(lockedFirst);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(1);
        try (java.sql.Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement("select id from automation_plans where id = ? for update")) {
                ps.setObject(1, planA);
                ps.executeQuery().close();
            }
            var fenceCall = pool.submit(() -> mvc.perform(fenceRequest(planA)).andReturn().getResponse().getStatus());
            awaitLockWait("pre-submit-attempting");
            try (var ps = c.prepareStatement("update automation_plans set submit_approved = false where id = ?")) {
                ps.setObject(1, planA);
                ps.executeUpdate();
            }
            c.commit();
            assertThat(fenceCall.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(fenceCount(planA)).isZero();

        // C2. A fence committed while the withdrawal waits on the lock is seen by it: the approval stays.
        var fencedFirst = seedReadyApplication("univ-p6-lock-b", "https://careers.example.com/jobs/lock-b");
        UUID planB = approvedSubmitPass(fencedFirst);
        try (java.sql.Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement("select id from automation_plans where id = ? for update")) {
                ps.setObject(1, planB);
                ps.executeQuery().close();
            }
            var withdrawal = pool.submit(() -> planRepository.invalidateApproval(fencedFirst.appId()));
            awaitLockWait("/* invalidate-running */");
            try (var ps = c.prepareStatement("""
                    insert into automation_steps(id, plan_id, step_key, step_index, status, result)
                    values (?, ?, 'pre-submit-attempting', 99, 'RUNNING', '{"phase":"PRE_SUBMIT_ATTEMPTING"}'::jsonb)
                    """)) {
                ps.setObject(1, UuidV7.generate());
                ps.setObject(2, planB);
                ps.executeUpdate();
            }
            c.commit();
            assertThat(withdrawal.get(30, java.util.concurrent.TimeUnit.SECONDS)).isZero();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select submit_approved from automation_plans where id = ?", Boolean.class, planB)).isTrue();
        retire(earlyPlan, inflightPlan, planA, planB);
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
