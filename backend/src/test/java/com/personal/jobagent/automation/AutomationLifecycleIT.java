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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end automation lifecycle on real PostgreSQL, driven through the HTTP
 * contract only (worker outcomes + owner actions):
 *
 * Scenario A — a validated plan goes HUMAN_REQUIRED → owner review (no state
 *   change) → explicit approval → READY_TO_SUBMIT with submit_approved, and
 *   submission stays impossible through the automatic path alone (claim-next
 *   never serves a READY_TO_SUBMIT plan, and completing it SUBMITTED without
 *   first queuing a real submission is rejected). Scenario C — a CAPTCHA/
 *   anti-bot stop is terminal. Scenario E — a crashed worker's stale plan is
 *   reclaimed and re-servable.
 * Scenario F (Phase 4) — the one, deliberate path that CAN reach SUBMITTED:
 *   an explicit prepare-submit call appends the real-submit-click step and
 *   returns the plan to PREPARED, where claim-next genuinely re-serves it.
 * Scenario G (Phase 4) — a session (not the worker identity) can never
 *   self-report SUBMITTED, even for a plan already queued for submission.
 * Scenario H (Phase 4 hardening) — an uncertain submit outcome is never
 *   automatically retried; only a fresh, full human approval cycle can try
 *   again.
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
    @Autowired private AutomationPlanRepository planRepository;

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

        // And completing it SUBMITTED is rejected — as a session (Phase 4's
        // own identity guard: only the worker may ever report SUBMITTED, so
        // this is 403, not a state-conflict 409) and, independently, because
        // this plan never left READY_TO_SUBMIT (no prepare-submit call was
        // made, so even a worker-authenticated attempt would still fail —
        // see Scenario F for the one path that CAN succeed, and Scenario G
        // for the identity guard proven on a plan that DID reach RUNNING).
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUBMITTED\",\"detail\":\"attempt\"}"))
                .andExpect(status().isForbidden());
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

    /** Approves a freshly-seeded plan via the real HTTP contract, returning its id. */
    private UUID seedApprovedPlan(String externalId, String title) throws Exception {
        UUID planId = seedRunningPlan(externalId, title);
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"HUMAN_REQUIRED\",\"detail\":\"ready\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        assertThat(planStatus(planId)).isEqualTo("READY_TO_SUBMIT");
        return planId;
    }

    /**
     * Phase 4: prepare-submit is the one method that can introduce the real
     * submission step, and only from an already-approved plan. Proves, end
     * to end against real Postgres and the real HTTP contract, that it (a)
     * requires READY_TO_SUBMIT + submit_approved, (b) appends exactly the
     * expected step, (c) returns the plan to PREPARED, and (d) that
     * claim-next — which Scenario A2 proved never serves a READY_TO_SUBMIT
     * plan — genuinely re-serves THIS plan once prepared, carrying the
     * approval flag forward.
     */
    @Test
    @Order(5)
    void scenarioF_prepareSubmitQueuesTheRealClickAndClaimNextReServesIt() throws Exception {
        UUID planId = seedApprovedPlan("life-4", "Site Reliability Engineer");

        MvcResult prepare = mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId)
                        .with(csrf()).session(session)).andReturn();
        assertThat(prepare.getResponse().getStatus())
                .as("prepare-submit response body: %s", prepare.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(planStatus(planId)).isEqualTo("PREPARED");
        Boolean stillApproved = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(stillApproved).isTrue();
        String storedPlan = jdbc.queryForObject("select plan::text from automation_plans where id = ?", String.class, planId);
        assertThat(storedPlan).contains("REAL_SUBMIT_CLICK").contains("real-submit-click");

        // The plan genuinely goes back into the claimable queue — unlike a
        // bare READY_TO_SUBMIT plan (Scenario A2), which claim-next skips.
        MvcResult claim = mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(claim.getResponse().getContentAsString()).contains(planId.toString());
        assertThat(planStatus(planId)).isEqualTo("RUNNING");

        // Re-running prepare-submit on a plan no longer READY_TO_SUBMIT must
        // not succeed a second time (the atomic guard, not merely a UI gate).
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict());
    }

    /**
     * Phase 4: only the worker identity may report SUBMITTED — a human
     * session can never self-certify its own submission, even for a plan it
     * just queued via prepare-submit and even while that plan sits RUNNING.
     * The worker-authenticated success path itself is covered at the unit
     * level in {@code AutomationControllerReviewTest} (mocked
     * OwnerContext/repository); exercising it here would additionally
     * require configuring {@code app.worker-event-token} and a bearer
     * header for this shared IT class, which this task deliberately left
     * out of scope rather than widen this fixture's authentication surface.
     */
    @Test
    @Order(6)
    void scenarioG_aSessionCanNeverSelfReportSubmitted() throws Exception {
        UUID planId = seedApprovedPlan("life-5", "Infrastructure Engineer");
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk());
        assertThat(planStatus(planId)).isEqualTo("RUNNING");

        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUBMITTED\",\"detail\":\"I clicked it myself\"}"))
                .andExpect(status().isForbidden());
        assertThat(planStatus(planId)).isEqualTo("RUNNING");
    }

    /**
     * Phase 4 hardening: an uncertain real-submission attempt (the worker
     * clicked but found no verified confirmation signal — classified
     * AMBIGUOUS/FORM_STILL_PRESENT/VALIDATION_ERROR by confirmation.js, all
     * reported to the backend as HUMAN_REQUIRED) must never be retried
     * automatically. claim-next only ever serves PREPARED plans; an
     * AWAITING_APPROVAL plan — which is exactly where an uncertain outcome
     * lands (§AutomationPlanRepository#transition) — is invisible to it.
     * The only way forward is a fresh, deliberate human approve-submit +
     * prepare-submit cycle, re-validated from scratch.
     */
    @Test
    @Order(7)
    void scenarioH_anUncertainSubmitOutcomeIsNeverAutomaticallyRetried() throws Exception {
        UUID planId = seedApprovedPlan("life-6", "Platform Reliability Engineer");
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk());
        assertThat(planStatus(planId)).isEqualTo("RUNNING");

        // The worker reports the uncertain outcome a real
        // AMBIGUOUS/FORM_STILL_PRESENT/VALIDATION_ERROR classification maps
        // to — HUMAN_REQUIRED, never SUBMITTED, never a silent retry.
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"HUMAN_REQUIRED\",\"detail\":\"Submit click completed but was not confirmed [FORM_STILL_PRESENT]\"}"))
                .andExpect(status().isNoContent());
        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");
        Boolean approvedAfterUncertainAttempt = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(approvedAfterUncertainAttempt).as("approval must be withdrawn, not carried forward").isFalse();

        // No automatic retry: claim-next has nothing to serve (the plan sits
        // in AWAITING_APPROVAL, not PREPARED) — confirmed by repeating the
        // call three times, not once, to rule out an eventual/delayed retry.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                    .andExpect(status().isNoContent());
        }
        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");

        // A second prepare-submit without a fresh approve-submit is refused —
        // approval, not merely "was once approved," is what prepare-submit requires.
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict());

        // The only way forward is the full cycle again, from the top:
        mockMvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        assertThat(planStatus(planId)).isEqualTo("READY_TO_SUBMIT");
    }

    /**
     * Robin x Tsenta continuation: a ZOHO_RECRUIT-shaped plan's HUMAN_REQUIRED
     * completion carries a structured result (what the worker actually filled
     * and what still needs the candidate) — proves it round-trips through the
     * real {@code automation_plans.result} JSONB column, not just a mocked
     * repository call, and that the plan correctly reaches AWAITING_APPROVAL
     * (never COMPLETED — COMPLETED must never read as "ready to submit" or
     * "submitted" for an adapter that offers no submission at all).
     */
    @Test
    @Order(8)
    void scenarioI_zohoRecruitHumanReviewResultRoundTripsThroughRealPostgres() throws Exception {
        String externalId = "life-7";
        String applyUrl = "https://acme.zohorecruit.com/jobs/Careers/1/life-7";
        JobDiscoveryService.IngestResult ingest = discovery.ingestJob(new JobDiscoveryService.IngestJobCommand(
                SOURCE, externalId, null, "Lifecycle Corp", "Zoho Review Engineer", "London", "London", "GB",
                "HYBRID", "FULL_TIME", "MID", 70000, 90000, "GBP",
                "Build Java services.", List.of("Java"), applyUrl, applyUrl));
        UUID jobId = ingest.jobId();
        Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer applications = jdbc.queryForObject(
                    "select count(*) from applications where profile_id = ? and job_id = ?", Integer.class, PROFILE, jobId);
            assertThat(applications).isEqualTo(1);
        });
        UUID applicationId = jdbc.queryForObject(
                "select id from applications where profile_id = ? and job_id = ?", UUID.class, PROFILE, jobId);

        UUID planId = UUID.randomUUID();
        Map<String, Object> plan = Map.of(
                "planType", "ZOHO_RECRUIT", "version", 1,
                "correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()),
                "targetUrl", applyUrl,
                "steps", List.of(
                        Map.of("id", "navigate-application", "type", "NAVIGATE", "policy", "AUTO", "params", Map.of("url", applyUrl)),
                        Map.of("id", "zoho-recruit-apply", "type", "ZOHO_RECRUIT_APPLY", "policy", "AUTO", "params", Map.of("identity", Map.of()))),
                "safetyContract", "NO_SUBMIT");
        jdbc.update("""
                insert into automation_plans (id, application_id, profile_id, target_url, status, plan, submit_approved,
                                              heartbeat_at, idempotency_key, created_at, updated_at)
                values (?, ?, ?, ?, 'RUNNING', ?::jsonb, false, now(), ?, now(), now())
                """, planId, applicationId, PROFILE, applyUrl, json.writeValueAsString(plan),
                "zoho-lifecycle:" + applicationId);

        Map<String, Object> outcomeResult = Map.of(
                "type", "ZOHO_RECRUIT_OUTCOME", "planType", "ZOHO_RECRUIT",
                "filled", List.of(Map.of("label", "First Name *", "category", "IDENTITY_FIRST_NAME")),
                "requiredGaps", List.of(Map.of("label", "Resume *", "category", "RESUME_UPLOAD")),
                "resumeUploadState", "NO_ARTIFACT",
                "resumeUploadReason", "no reviewed résumé is available for this application");
        Map<String, Object> completeBody = Map.of(
                "outcome", "HUMAN_REQUIRED", "detail", "1 field filled, 1 needs review",
                "result", List.of(outcomeResult));
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completeBody)))
                .andExpect(status().isNoContent());

        // Never COMPLETED: this adapter offers no submission at all, and
        // AWAITING_APPROVAL (not a bespoke status) is the reused, existing
        // "prepared for human review" state — see AutomationController#complete.
        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");

        mockMvc.perform(get("/api/v1/automation/plans/{id}", planId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].type").value("ZOHO_RECRUIT_OUTCOME"))
                .andExpect(jsonPath("$.result[0].resumeUploadState").value("NO_ARTIFACT"))
                .andExpect(jsonPath("$.result[0].filled[0].category").value("IDENTITY_FIRST_NAME"))
                .andExpect(jsonPath("$.result[0].requiredGaps[0].category").value("RESUME_UPLOAD"));
    }

    /**
     * Robin x Tsenta continuation: review-queue recovery. Without
     * {@code AutomationPlanRepository#requeueFailedPlan}, a plan that reaches
     * a terminal failure state is stuck there forever — {@code claimNext}
     * only ever serves {@code PREPARED} rows, and nothing else transitions a
     * plan out of {@code FAILED}/{@code BLOCKED_ANTI_BOT}. Proves the real,
     * atomic SQL transition against real Postgres: a FAILED/BLOCKED_ANTI_BOT
     * plan is requeued to PREPARED (and so becomes claimable again), while a
     * plan in any OTHER status — AWAITING_APPROVAL here — is left completely
     * untouched, so the requeue path can never be used to bypass human
     * review or re-run an application that is already mid-flight.
     *
     * <p>Robin x Tsenta hardening: also proves the previous attempt's
     * persisted {@code result} is cleared in the SAME atomic update — a
     * candidate who refreshes the review panel between requeue and the
     * retried worker's next completion must never see a leftover outcome
     * from the run that just failed. The in-flight plan's own seeded result
     * is the control: left completely untouched, since {@code
     * requeueFailedPlan} never matches its status.
     */
    @Test
    @Order(9)
    void scenarioJ_aFailedOrBlockedPlanIsRequeuedForRetryButAnInFlightPlanIsNever() throws Exception {
        // automation_plans.application_id is a real foreign key — reuse any
        // already-seeded application for this profile (an earlier scenario's)
        // rather than inventing one; this test only exercises the plan's OWN
        // status transitions, so which application it points to is irrelevant.
        UUID anyApplicationId = jdbc.queryForObject(
                "select id from applications where profile_id = ? limit 1", UUID.class, PROFILE);
        UUID failedPlanId = UUID.randomUUID();
        UUID blockedPlanId = UUID.randomUUID();
        UUID inFlightPlanId = UUID.randomUUID();
        String staleResult = json.writeValueAsString(List.of(Map.of(
                "type", "PLAN_ERROR", "outcome", "FAILED", "message", "the previous attempt's stale result")));
        for (UUID id : List.of(failedPlanId, blockedPlanId, inFlightPlanId)) {
            jdbc.update("""
                    insert into automation_plans (id, application_id, profile_id, target_url, status, plan, result,
                                                  submit_approved, heartbeat_at, idempotency_key, created_at, updated_at)
                    values (?, ?, ?, 'https://acme.zohorecruit.com/jobs/Careers/1/x', 'RUNNING', '{}'::jsonb, ?::jsonb,
                            false, now(), ?, now(), now())
                    """, id, anyApplicationId, PROFILE, staleResult, "requeue-fixture:" + id);
        }
        jdbc.update("update automation_plans set status = 'FAILED' where id = ?", failedPlanId);
        jdbc.update("update automation_plans set status = 'BLOCKED_ANTI_BOT' where id = ?", blockedPlanId);
        jdbc.update("update automation_plans set status = 'AWAITING_APPROVAL' where id = ?", inFlightPlanId);

        assertThat(planRepository.requeueFailedPlan(failedPlanId)).isTrue();
        assertThat(planStatus(failedPlanId)).isEqualTo("PREPARED");
        Boolean failedHeartbeatCleared = jdbc.queryForObject(
                "select heartbeat_at is null from automation_plans where id = ?", Boolean.class, failedPlanId);
        assertThat(failedHeartbeatCleared).as("a stale lease must not linger on a requeued plan").isTrue();
        assertThat(resultOf(failedPlanId)).as("the previous attempt's result must not survive a requeue").isEqualTo("[]");

        assertThat(planRepository.requeueFailedPlan(blockedPlanId)).isTrue();
        assertThat(planStatus(blockedPlanId)).isEqualTo("PREPARED");
        assertThat(resultOf(blockedPlanId)).isEqualTo("[]");

        // The one status this must never touch: a plan already mid-flight
        // (human review in progress) is left exactly as it was — status AND
        // its own result, which requeueFailedPlan must never have reached.
        assertThat(planRepository.requeueFailedPlan(inFlightPlanId)).isFalse();
        assertThat(planStatus(inFlightPlanId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(resultOf(inFlightPlanId)).contains("the previous attempt's stale result");

        // A requeued plan is genuinely claimable again — proves the end of
        // the recovery path, not just the status column in isolation.
        var claimed = planRepository.claimNext();
        assertThat(claimed).isPresent();
        assertThat(List.of(failedPlanId, blockedPlanId)).contains(claimed.get().id());
        assertThat(claimed.get().status()).isEqualTo("RUNNING");
    }

    private String resultOf(UUID planId) {
        return jdbc.queryForObject("select result::text from automation_plans where id = ?", String.class, planId);
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
