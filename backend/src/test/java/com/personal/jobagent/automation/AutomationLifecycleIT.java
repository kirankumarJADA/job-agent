package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.documents.DocumentFactValidator;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end automation lifecycle on real PostgreSQL, driven through the HTTP
 * contract only (worker outcomes + owner actions):
 *
 * Scenario A — a validated plan goes HUMAN_REQUIRED → owner review (no state
 *   change) → explicit approval → READY_TO_SUBMIT with submit_approved, and
 *   submission stays impossible (claim-next never serves it, SUBMITTED
 *   outcome rejected). Scenario C — a CAPTCHA/anti-bot stop is terminal.
 * Scenario E — a crashed worker's stale plan is reclaimed and re-servable.
 * Scenario H — no code path ever produces SUBMITTED.
 *
 * The plan rows are seeded directly (the worker's browser execution is
 * covered by the worker suite); every HTTP transition here is the real one.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false", "app.discovery.scheduler-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutomationLifecycleIT {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-00000000cc10");
    private static final UUID PROFILE = UUID.fromString("00000000-0000-7000-8000-00000000cc11");
    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000cc30");
    /**
     * Each scenario is a genuinely different role and gets its own requisition
     * URL. Phase 8.2 duplicate protection treats one board requisition URL as
     * one role (never titles), so a shared URL would correctly refuse the
     * second and third scenario's application as a duplicate of the first.
     */
    private static String scenarioUrl(String externalId) {
        return "https://boards.greenhouse.io/lifecycle/jobs/" + externalId;
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JobDiscoveryService discovery;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper json;

    private MockHttpSession session;
    private static final Map<String, UUID> PLAN_IDS = new ConcurrentHashMap<>();

    @BeforeEach
    void seedAndLogin() throws Exception {
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, ?, ?, 'LOCAL') on conflict (email) do nothing
                """, USER, "lifecycle@example.com", passwordEncoder.encode("PasswordA1!"), "Lifecycle IT");
        jdbc.update("insert into profiles (id, user_id) values (?, ?) on conflict do nothing", PROFILE, USER);
        // This IT rehearses the CONTROLLED_AUTO deployment posture: every
        // APPLY-level match auto-creates the application. Phase 7.2 requires
        // that posture to opt in with an explicitly enabled rule, so seed one
        // at the APPLY floor.
        jdbc.update("""
                insert into preference_sets (id, profile_id, application_mode, scoring_weights, salary_min_gbp)
                values (?, ?, 'CONTROLLED_AUTO', '{"skill":70,"experience":10,"visa":5,"location":5,"salary":5,"career":5,"difficulty":0}', 40000)
                on conflict do nothing
                """, UUID.randomUUID(), PROFILE);
        jdbc.update("""
                insert into user_approval_rules (id, profile_id, auto_approve_enabled, min_score)
                values (?, ?, true, 70)
                on conflict (profile_id) do update set auto_approve_enabled = true,
                    min_score = 70, updated_at = now()
                """, UUID.randomUUID(), PROFILE);
        jdbc.update("insert into skills (id, profile_id, name, category, mastery) values (?, ?, 'Java', 'Test', 4) on conflict do nothing",
                UUID.randomUUID(), PROFILE);
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'lifecycle-org', 'Lifecycle Source', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE);

        if (session == null) {
            MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"lifecycle@example.com\",\"password\":\"PasswordA1!\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            session = (MockHttpSession) login.getRequest().getSession(false);
        }
    }

    /** One job + application + bound CV + a RUNNING greenhouse plan, seeded directly. */
    private UUID seedRunningPlan(String externalId, String title) throws Exception {
        String applyUrl = scenarioUrl(externalId);
        JobDiscoveryService.IngestResult ingest = discovery.ingestJob(new JobDiscoveryService.IngestJobCommand(
                SOURCE, externalId, null, "Lifecycle Corp", title, "London", "London", "GB",
                "HYBRID", "FULL_TIME", "MID", 70000, 90000, "GBP",
                "Build Java services.", List.of("Java"),
                applyUrl, applyUrl));
        UUID jobId = ingest.jobId();

        Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer applications = jdbc.queryForObject(
                    "select count(*) from applications where profile_id = ? and job_id = ?",
                    Integer.class, PROFILE, jobId);
            assertThat(applications).isEqualTo(1);
        });
        UUID applicationId = jdbc.queryForObject(
                "select id from applications where profile_id = ? and job_id = ?",
                UUID.class, PROFILE, jobId);

        // Bind an immutable CV artifact to the application (files → cv_versions).
        byte[] pdf = ("pdf-bytes-" + applicationId).getBytes(StandardCharsets.UTF_8);
        String pdfSha = sha256Hex(pdf);
        UUID fileId = UUID.randomUUID();
        UUID cvVersionId = UUID.randomUUID();
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvVersionId + ".pdf", pdfSha, "application/pdf", (long) pdf.length, "TAILORED_CV", pdf);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Tailored CV', 'CV text', '{}', false, 1, 'snap', ?, true, ?, now())
                """, cvVersionId, jobId, applicationId, PROFILE, pdfSha, fileId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvVersionId, applicationId);

        // Phase 8.2: a bound CV is not enough — it must be validated and its
        // review must be bound to the exact content digest before any approval
        // can be granted. Seed both so the fixture is a genuinely reviewed CV.
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UUID.randomUUID(), PROFILE, jobId, applicationId, "hash-" + applicationId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", DocumentFactValidator.VERSION))), cvVersionId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'owner', ?)
                on conflict (cv_version_id) do nothing
                """, cvVersionId, PROFILE, pdfSha);

        // Seed the RUNNING greenhouse plan with a verified, gap-free package.
        UUID planId = UUID.randomUUID();
        Map<String, Object> cv = Map.of("versionId", cvVersionId.toString(), "sha256", pdfSha,
                "jobId", jobId.toString(), "applicationId", applicationId.toString());
        Map<String, Object> pkg = Map.of(
                "planId", planId.toString(), "applicationId", applicationId.toString(),
                "jobId", jobId.toString(), "expectedUrl", applyUrl,
                "requiredGaps", List.of(), "cv", cv);
        Map<String, Object> plan = Map.of(
                "planType", "GREENHOUSE", "version", 1,
                "correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()),
                "targetUrl", applyUrl,
                "steps", List.of(Map.of("id", "validate-form", "type", "VALIDATE", "policy", "AUTO", "params", Map.of())),
                "package", pkg, "safetyContract", "NO_SUBMIT");
        jdbc.update("""
                insert into automation_plans (id, application_id, profile_id, target_url, status, plan, submit_approved,
                                              heartbeat_at, idempotency_key, created_at, updated_at)
                values (?, ?, ?, ?, 'RUNNING', ?::jsonb, false, now(), ?, now(), now())
                """, planId, applicationId, PROFILE, applyUrl, json.writeValueAsString(plan),
                "lifecycle:" + applicationId);
        PLAN_IDS.put(externalId, planId);
        return planId;
    }

    @Test
    @Order(1)
    void scenarioA_validationThenReviewThenExplicitApprovalReachesReadyToSubmit() throws Exception {
        UUID planId = seedRunningPlan("life-1", "Platform Engineer");

        // The worker finished fills + VALIDATE and reports the human gate:
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"HUMAN_REQUIRED\",\"detail\":\"2 fields verified\"}"))
                .andExpect(status().isNoContent());
        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");

        // Review alone does NOT complete or ready the plan:
        mockMvc.perform(post("/api/v1/automation/plans/{id}/review", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledge\":true}"))
                .andExpect(status().isNoContent());
        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");

        // The explicit approval does:
        MvcResult approval = mockMvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId)
                        .with(csrf()).session(session)).andReturn();
        assertThat(approval.getResponse().getStatus())
                .as("approve-submit response body: %s", approval.getResponse().getContentAsString())
                .isEqualTo(200);
        Map<String, Object> approvalBody = json.readValue(approval.getResponse().getContentAsString(), Map.class);
        assertThat(approvalBody.get("status")).isEqualTo("READY_TO_SUBMIT");
        assertThat(approvalBody.get("submissionEnabled")).isEqualTo(false);
        assertThat(planStatus(planId)).isEqualTo("READY_TO_SUBMIT");
        Boolean approved = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(approved).isTrue();
    }

    @Test
    @Order(2)
    void scenarioA_afterApprovalSubmissionStaysImpossible() throws Exception {
        UUID planId = PLAN_IDS.get("life-1");

        // A READY_TO_SUBMIT plan is never served to a worker again (204 = no work):
        mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isNoContent());

        // And the SUBMITTED outcome is rejected outright:
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUBMITTED\",\"detail\":\"attempt\"}"))
                .andExpect(status().isConflict());
        assertThat(planStatus(planId)).isEqualTo("READY_TO_SUBMIT");
    }

    @Test
    @Order(3)
    void scenarioC_antiBotDetectionIsATerminalHardStop() throws Exception {
        UUID planId = seedRunningPlan("life-2", "Backend Engineer");

        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"BLOCKED_ANTI_BOT\",\"detail\":\"captcha on page\"}"))
                .andExpect(status().isNoContent());
        assertThat(planStatus(planId)).isEqualTo("BLOCKED_ANTI_BOT");
        // Blocked plans are not re-claimed either:
        mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(4)
    void scenarioE_aCrashedWorkersStalePlanIsReclaimedAndReServed() throws Exception {
        UUID planId = seedRunningPlan("life-3", "Data Engineer");
        // Simulate a crashed worker: no heartbeat for over the stale window.
        jdbc.update("update automation_plans set heartbeat_at = now() - interval '1 hour' where id = ?", planId);

        mockMvc.perform(post("/api/v1/automation/plans/recover-stale")
                        .param("minutes", "15").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recovered").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        assertThat(planStatus(planId)).isEqualTo("PREPARED");

        // The reclaimed plan is served to a worker again (execution restarts;
        // durable worker state skips already-completed steps; no plan ever
        // contains a submit step, so a reclaim can never double-submit).
        MvcResult claim = mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(claim.getResponse().getContentAsString()).contains(planId.toString());
    }

    private String planStatus(UUID planId) {
        return jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId);
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
