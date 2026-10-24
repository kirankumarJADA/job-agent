package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.GreenhouseAdapter;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /**
     * Phase 15: every prior scenario here seeds its Greenhouse plan directly
     * via SQL, never actually calling {@code GreenhouseExecutionPlanService},
     * so the real {@code GreenhouseAdapter} was never previously exercised in
     * this class. {@code createRetryExecutionPlan} (the one path {@code
     * /reconcile} drives) DOES call it — a fixture is required for the same
     * reason {@code ApplicationTrackingIT} already carries one, mirrored here
     * verbatim rather than inventing a second shape.
     */
    static final String FORM_HTML = """
            <html><body><form id="application_form">
            <div class="field"><input class="requiredInput" required="" aria-hidden="true" tabindex="-1" value=""/>
              <label for="first_name">First name<span>*</span></label><input id="first_name" type="text"/></div>
            <div class="field"><label for="email">Email<span>*</span></label><input id="email" type="email"/></div>
            <div class="field upload"><label for="resume">Resume/CV</label><input id="resume" type="file" required/></div>
            </form></body></html>
            """;

    @TestConfiguration
    static class FixtureForm {
        @Bean
        @Primary
        GreenhouseAdapter fixtureGreenhouse() {
            return new GreenhouseAdapter(url -> FORM_HTML);
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

    /**
     * One job + application + bound CV + a RUNNING greenhouse plan, seeded
     * directly.
     *
     * <p>Phase 15: seeds the job/application directly via SQL rather than
     * through {@code discovery.ingestJob(...)} — a real-Postgres run
     * surfaced exactly why this matters once {@link FixtureForm}'s
     * {@code GreenhouseAdapter} bean was added (needed so {@code
     * createRetryExecutionPlan} has a real form to inspect for the new
     * {@code /reconcile} scenarios): {@code discovery.ingestJob} fires the
     * real {@code application.created} event, which the real, async {@code
     * ApplicationPipelineEventHandler} dispatches to {@code
     * GreenhouseExecutionPlanService#createExecutionPlan} — and with a
     * working fixture adapter, that auto-dispatch now genuinely succeeds and
     * creates a SECOND, independently PREPARED plan for the same
     * application, racing this test's own manually-seeded one for {@code
     * claim-next}'s oldest-first pickup. Bypassing the event pipeline
     * entirely removes that race at its root — the exact same fix already
     * applied to {@code seedRunningAshbyPlan} below, for the identical
     * reason.
     */
    private UUID seedRunningPlan(String externalId, String title) throws Exception {
        String applyUrl = scenarioUrl(externalId);
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, status, content_hash,
                                  application_url)
                values (?, ?, ?, ?, 'Lifecycle Corp', ?, 'London', 'Build Java services.', '{Java}'::text[],
                        'DISCOVERED', ?, ?)
                """, jobId, SOURCE, externalId, "dedup-" + externalId, title, "hash-" + externalId, applyUrl);
        UUID applicationId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')
                """, applicationId, jobId, PROFILE, "identity-" + applicationId);

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
     * Phase 15: an uncertain real-submission attempt (the worker clicked but
     * found no verified confirmation signal — classified
     * AMBIGUOUS/FORM_STILL_PRESENT/VALIDATION_ERROR by confirmation.js) now
     * resolves to the dedicated {@code RECONCILIATION_REQUIRED} status, never
     * {@code AWAITING_APPROVAL} — so it can never be retried automatically
     * (claim-next only ever serves PREPARED plans) AND can never be
     * re-approved through the normal review flow either (unlike the
     * pre-Phase-15 behaviour this test used to assert): {@code
     * approvalPrecondition}'s fixed forbidden-step check now refuses it
     * outright, since the plan still carries its stale {@code
     * REAL_SUBMIT_CLICK} step. The worker's own real report of this outcome
     * is simulated directly through the repository (the same reason
     * Scenario G's own comment gives for not configuring worker-bearer auth
     * in this shared IT class) — the worker-authenticated HTTP path itself
     * is covered separately in {@code AutomationControllerReviewTest} and in
     * {@code scenarioR_...} below.
     */
    @Test
    @Order(7)
    void scenarioH_anUncertainSubmitOutcomeReachesReconciliationRequiredAndCanNeverBeReApproved() throws Exception {
        UUID planId = seedApprovedPlan("life-6", "Platform Reliability Engineer");
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk());
        assertThat(planStatus(planId)).isEqualTo("RUNNING");

        // The worker reports the uncertain outcome a real
        // AMBIGUOUS/FORM_STILL_PRESENT/VALIDATION_ERROR classification maps
        // to — RECONCILIATION_REQUIRED, never SUBMITTED, never a silent retry.
        boolean reported = planRepository.transitionWithResult(planId, "RUNNING", "RECONCILIATION_REQUIRED",
                List.of(Map.of("type", "SUBMISSION_RECONCILIATION", "planType", "GREENHOUSE",
                        "category", "FORM_STILL_PRESENT", "evidence", "the application form is still present after the click")));
        assertThat(reported).isTrue();
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");
        Boolean approvedAfterUncertainAttempt = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(approvedAfterUncertainAttempt).as("approval must be withdrawn, not carried forward").isFalse();

        // No automatic retry: claim-next has nothing to serve (the plan sits
        // in RECONCILIATION_REQUIRED, not PREPARED) — confirmed by repeating
        // the call three times, not once, to rule out an eventual/delayed retry.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                    .andExpect(status().isNoContent());
        }
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");

        // Never re-approvable, since approve-submit's own top-level gate
        // requires AWAITING_APPROVAL — a status this plan can never return to:
        mockMvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict());
        // Never re-queueable either, for the same reason (prepare-submit
        // requires READY_TO_SUBMIT + submit_approved):
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict());
        // And an ambiguous category (FORM_STILL_PRESENT is not one of the
        // narrow platform-confirmed-non-acceptance shapes) means reconcile
        // itself refuses to create a new attempt too — see scenarioS_ below
        // for the one category that IS permitted.
        mockMvc.perform(post("/api/v1/automation/plans/{id}/reconcile", planId).with(csrf()).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_EVIDENCE_INSUFFICIENT"));
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");
    }

    /**
     * Phase 15: the one category {@code approvalPrecondition}'s
     * GREENHOUSE-side defect fix (§6) and the new {@code /reconcile} endpoint
     * both exist to handle safely — a real submit click whose outcome is a
     * platform-confirmed non-acceptance (Greenhouse's own employer-side form
     * validation refused the data; nothing was ever accepted server-side).
     * Unlike Scenario H's genuinely ambiguous category, this one IS
     * permitted to produce a brand-new, independent plan — never a mutation
     * of the superseded row, which stays in RECONCILIATION_REQUIRED forever
     * as the permanent record of the attempt that was never accepted.
     */
    @Test
    @Order(8)
    void scenarioS_aPlatformConfirmedRejectionPermitsANewIndependentGreenhouseAttempt() throws Exception {
        UUID planId = seedApprovedPlan("life-6b", "Validation-Rejected Engineer");
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk());
        boolean reported = planRepository.transitionWithResult(planId, "RUNNING", "RECONCILIATION_REQUIRED",
                List.of(Map.of("type", "SUBMISSION_RECONCILIATION", "planType", "GREENHOUSE",
                        "category", "VALIDATION_ERROR", "evidence", "2 invalid form field(s) remain on the page")));
        assertThat(reported).isTrue();

        MvcResult reconcile = mockMvc.perform(post("/api/v1/automation/plans/{id}/reconcile", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"checked the employer page, nothing was received\"}"))
                .andReturn();
        assertThat(reconcile.getResponse().getStatus())
                .as("reconcile response body: %s", reconcile.getResponse().getContentAsString())
                .isEqualTo(200);
        Map<String, Object> body = json.readValue(reconcile.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("status")).isEqualTo("RECONCILIATION_RETRY_CREATED");
        UUID newPlanId = UUID.fromString((String) body.get("newPlanId"));
        assertThat(newPlanId).isNotEqualTo(planId);

        // The superseded plan is left exactly as it was — never mutated, never deleted:
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");
        // The new plan is a genuinely fresh, independent PREPARED row with no submit step:
        assertThat(planStatus(newPlanId)).isEqualTo("PREPARED");
        String newPlanJson = jdbc.queryForObject("select plan::text from automation_plans where id = ?", String.class, newPlanId);
        assertThat(newPlanJson).doesNotContain("REAL_SUBMIT_CLICK");

        // Idempotent: calling reconcile again for the SAME superseded plan
        // returns the SAME new plan, never a third one.
        MvcResult again = mockMvc.perform(post("/api/v1/automation/plans/{id}/reconcile", planId).with(csrf()).session(session))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> againBody = json.readValue(again.getResponse().getContentAsString(), Map.class);
        assertThat(againBody.get("newPlanId")).isEqualTo(newPlanId.toString());
        Integer plansForApplication = jdbc.queryForObject(
                "select count(*) from automation_plans where application_id = (select application_id from automation_plans where id = ?)",
                Integer.class, planId);
        assertThat(plansForApplication).isEqualTo(2);

        // Claim the new plan by its own id (not the generic claim-next queue)
        // purely to drain it so it cannot race a LATER scenario's own
        // claim-next call — this test is not about queue ordering.
        mockMvc.perform(post("/api/v1/automation/plans/{id}/claim", newPlanId).with(csrf()).session(session))
                .andExpect(status().isOk());
    }

    /**
     * Phase 15: a different account can never read, reconcile, or otherwise
     * act on a plan it does not own — reconcile is a candidate-facing action
     * and must enforce the exact same ownership rule every other plan
     * endpoint already does ({@code mayActOnPlan}).
     */
    @Test
    @Order(9)
    void scenarioT_reconcileEnforcesOwnershipLikeEveryOtherPlanEndpoint() throws Exception {
        UUID planId = seedApprovedPlan("life-6c", "Ownership-Isolated Engineer");
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        // Claims THIS plan specifically — the generic claim-next queue is not
        // what this test is about, and using it here would risk claiming a
        // different PREPARED plan if one happened to be older in the queue.
        mockMvc.perform(post("/api/v1/automation/plans/{id}/claim", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        planRepository.transitionWithResult(planId, "RUNNING", "RECONCILIATION_REQUIRED",
                List.of(Map.of("type", "SUBMISSION_RECONCILIATION", "planType", "GREENHOUSE",
                        "category", "VALIDATION_ERROR", "evidence", "invalid field")));

        UUID otherUser = UUID.fromString("00000000-0000-7000-8000-00000000cc99");
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, ?, ?, 'LOCAL') on conflict (email) do nothing
                """, otherUser, "lifecycle-other@example.com", passwordEncoder.encode("PasswordA1!"), "Other IT");
        jdbc.update("insert into profiles (id, user_id) values (?, ?) on conflict do nothing", UUID.randomUUID(), otherUser);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"lifecycle-other@example.com\",\"password\":\"PasswordA1!\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession otherSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/api/v1/automation/plans/{id}/reconcile", planId).with(csrf()).session(otherSession))
                .andExpect(status().isNotFound());
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");
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
    @Order(10)
    void scenarioI_zohoRecruitHumanReviewResultRoundTripsThroughRealPostgres() throws Exception {
        String externalId = "life-7";
        String applyUrl = "https://acme.zohorecruit.com/jobs/Careers/1/life-7";
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, status, content_hash,
                                  application_url)
                values (?, ?, ?, ?, 'Lifecycle Corp', 'Zoho Review Engineer', 'London', 'Build Java services.', '{Java}'::text[],
                        'DISCOVERED', ?, ?)
                """, jobId, SOURCE, externalId, "dedup-" + externalId, "hash-" + externalId, applyUrl);
        UUID applicationId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')
                """, applicationId, jobId, PROFILE, "identity-" + applicationId);

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
     *
     * <p>Phase 14: moved to run LAST ({@code @Order(19)}, was originally 9).
     * This scenario calls {@code planRepository.claimNext()} directly and
     * deliberately leaves ONE of its two requeued PREPARED plans unclaimed —
     * a stray row that would otherwise sit in the claim queue indefinitely.
     * {@code claim-next} serves the OLDEST PREPARED plan first, so any later
     * scenario relying on claim-next to re-serve ITS OWN freshly-prepared
     * plan (Scenarios M/N/O below) would nondeterministically receive this
     * stray row instead — exactly what a real-Postgres run caught that no
     * mocked-repository test ever could. Running this scenario last removes
     * that risk for every scenario before it.
     */
    @Test
    @Order(19)
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

    // ── Phase 14: ASHBY submission lifecycle on real Postgres ──────────────
    // Mirrors Scenarios A/F/G/H/I above exactly, but for the first plan type
    // besides GREENHOUSE whose approval/prepare-submit path can genuinely
    // reach SUBMITTED. Proves AutomationController#ashbyApprovalPrecondition
    // (reads the ASHBY_OUTCOME result row, not a static package) and
    // AutomationPlanRepository#prepareAshbySubmission (mirrors
    // #prepareRealSubmission's exact atomic shape) against the real schema,
    // not a mocked repository.

    /**
     * Phase 14: seeds the job and application directly via SQL, deliberately
     * NOT through {@code discovery.ingestJob(...)} the way {@code
     * seedRunningPlan} does for GREENHOUSE. A real-Postgres run (not a mocked
     * repository) exposed exactly why: {@code discovery.ingestJob} fires the
     * real {@code application.created} event, which the real, async
     * {@code ApplicationPipelineEventHandler} dispatches to {@code
     * AshbyExecutionPlanService#createExecutionPlan} — and unlike Greenhouse
     * (whose own auto-dispatch is harmlessly blocked by the missing-CV
     * precondition at that point), Ashby's own service never blocks on a
     * missing CV, so a SECOND, genuinely PREPARED plan row is auto-created
     * for the same application, racing this test's own manually-seeded one.
     * {@code claim-next} serves the OLDEST PREPARED plan first, so a later
     * scenario's claim-next call could silently receive that stray
     * auto-created plan instead of the one THIS scenario expects — exactly
     * the class of bug a mocked-repository test can never surface. Seeding
     * the job/application directly, bypassing the event pipeline entirely,
     * removes the race at its root rather than papering over it with cleanup.
     */
    private UUID seedRunningAshbyPlan(String externalId, String title) throws Exception {
        String applyUrl = "https://jobs.ashbyhq.com/ramp/" + externalId + "/application";
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, status, content_hash,
                                  application_url)
                values (?, ?, ?, ?, 'Lifecycle Corp', ?, 'London', 'Build Java services.', '{Java}'::text[],
                        'DISCOVERED', ?, ?)
                """, jobId, SOURCE, externalId, "dedup-" + externalId, title,
                "hash-" + externalId, applyUrl);
        UUID applicationId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')
                """, applicationId, jobId, PROFILE, "identity-" + applicationId);

        // Phase 14: ApplyReadinessService#evaluate — the SAME shared,
        // ATS-agnostic gate approve-submit re-runs server-side — requires a
        // reviewed CV to exist for ANY plan type, not just GREENHOUSE. Omitting
        // this (as a first draft of this fixture did) fails approve-submit
        // with CV_MISSING before ever reaching the ASHBY-specific precondition
        // logic — exactly the kind of gap a mocked-repository unit test can
        // never catch. Mirrors seedRunningPlan's own CV-binding block exactly.
        byte[] pdf = ("ashby-pdf-bytes-" + applicationId).getBytes(StandardCharsets.UTF_8);
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

        UUID planId = UUID.randomUUID();
        Map<String, Object> plan = Map.of(
                "planType", "ASHBY", "version", 1,
                "correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()),
                "targetUrl", applyUrl,
                "steps", List.of(
                        Map.of("id", "navigate-application", "type", "NAVIGATE", "policy", "AUTO", "params", Map.of("url", applyUrl)),
                        Map.of("id", "screenshot-landing", "type", "SCREENSHOT", "policy", "AUTO", "params", Map.of()),
                        Map.of("id", "ashby-apply", "type", "ASHBY_APPLY", "policy", "AUTO", "params", Map.of("identity", Map.of()))),
                "safetyContract", "NO_SUBMIT");
        jdbc.update("""
                insert into automation_plans (id, application_id, profile_id, target_url, status, plan, submit_approved,
                                              heartbeat_at, idempotency_key, created_at, updated_at)
                values (?, ?, ?, ?, 'RUNNING', ?::jsonb, false, now(), ?, now(), now())
                """, planId, applicationId, PROFILE, applyUrl, json.writeValueAsString(plan),
                "ashby-lifecycle:" + applicationId);
        return planId;
    }

    /** Completes an ASHBY plan HUMAN_REQUIRED with a gap-free ASHBY_OUTCOME result, then approves it. */
    private UUID seedApprovedAshbyPlan(String externalId, String title) throws Exception {
        UUID planId = seedRunningAshbyPlan(externalId, title);
        Map<String, Object> completeBody = Map.of(
                "outcome", "HUMAN_REQUIRED", "detail", "ready for review",
                "result", List.of(Map.of(
                        "type", "ASHBY_OUTCOME", "planType", "ASHBY",
                        "filled", List.of(Map.of("label", "Legal Name", "category", "IDENTITY_NAME")),
                        "requiredGaps", List.of(), "resumeUploadState", "NO_FIELD")));
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completeBody)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        assertThat(planStatus(planId)).isEqualTo("READY_TO_SUBMIT");
        return planId;
    }

    @Test
    @Order(11)
    void scenarioK_ashbyValidationThenReviewThenExplicitApprovalReachesReadyToSubmit() throws Exception {
        UUID planId = seedRunningAshbyPlan("life-ashby-1", "Ashby Platform Engineer");
        Map<String, Object> completeBody = Map.of(
                "outcome", "HUMAN_REQUIRED", "detail", "1 field filled",
                "result", List.of(Map.of(
                        "type", "ASHBY_OUTCOME", "planType", "ASHBY",
                        "filled", List.of(Map.of("label", "Legal Name", "category", "IDENTITY_NAME")),
                        "requiredGaps", List.of(), "resumeUploadState", "NO_FIELD")));
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completeBody)))
                .andExpect(status().isNoContent());
        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");

        // Review alone does NOT complete or ready the plan — same contract as every other plan type:
        mockMvc.perform(post("/api/v1/automation/plans/{id}/review", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledge\":true}"))
                .andExpect(status().isNoContent());
        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");

        MvcResult approval = mockMvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId)
                        .with(csrf()).session(session)).andReturn();
        assertThat(approval.getResponse().getStatus())
                .as("approve-submit response body: %s", approval.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(planStatus(planId)).isEqualTo("READY_TO_SUBMIT");
        Boolean approved = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(approved).isTrue();

        // The audit trail truthfully names ASHBY, not GREENHOUSE, for this plan's actions.
        List<String> auditActions = jdbc.queryForList(
                "select action from audit_logs where entity_id = ? order by created_at", String.class, planId);
        assertThat(auditActions).contains("ASHBY_SUBMIT_APPROVED").doesNotContain("GREENHOUSE_SUBMIT_APPROVED");
    }

    @Test
    @Order(12)
    void scenarioL_anAshbyPlanWithUnresolvedRequiredGapsCannotBeApproved() throws Exception {
        UUID planId = seedRunningAshbyPlan("life-ashby-2", "Ashby Backend Engineer");
        Map<String, Object> completeBody = Map.of(
                "outcome", "HUMAN_REQUIRED", "detail", "1 gap remains",
                "result", List.of(Map.of(
                        "type", "ASHBY_OUTCOME", "planType", "ASHBY", "filled", List.of(),
                        "requiredGaps", List.of(Map.of("label", "Where do you plan on working from?", "category", "REQUIRES_HUMAN")),
                        "resumeUploadState", "NO_FIELD")));
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completeBody)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("unresolved human-required fields remain")));
        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");
        Boolean approved = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(approved).isFalse();
    }

    @Test
    @Order(13)
    void scenarioM_ashbyPrepareSubmitQueuesTheRealClickAndClaimNextReServesIt() throws Exception {
        UUID planId = seedApprovedAshbyPlan("life-ashby-3", "Ashby SRE");

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
        assertThat(storedPlan).contains("ASHBY_SUBMIT_CLICK").contains("ashby-submit-click");
        // Never the Greenhouse step shape on an ASHBY plan:
        assertThat(storedPlan).doesNotContain("REAL_SUBMIT_CLICK");

        MvcResult claim = mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(claim.getResponse().getContentAsString()).contains(planId.toString());
        assertThat(planStatus(planId)).isEqualTo("RUNNING");

        // The atomic guard, not merely a UI gate — re-running prepare-submit
        // on a plan no longer READY_TO_SUBMIT must not succeed a second time.
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict());

        List<String> auditActions = jdbc.queryForList(
                "select action from audit_logs where entity_id = ? order by created_at", String.class, planId);
        assertThat(auditActions).contains("ASHBY_SUBMIT_QUEUED");
    }

    @Test
    @Order(14)
    void scenarioN_aSessionCanNeverSelfReportAnAshbySubmission() throws Exception {
        UUID planId = seedApprovedAshbyPlan("life-ashby-4", "Ashby Infrastructure Engineer");
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
     * Phase 15: the Ashby counterpart of Scenario H — a genuinely ambiguous
     * Ashby submit outcome (no matching {@code applicationForm.submit}
     * response was ever observed — {@code classifyAshbySubmission}'s own
     * honest {@code UNKNOWN} category) now resolves to {@code
     * RECONCILIATION_REQUIRED}, never a re-approvable {@code
     * AWAITING_APPROVAL}, and {@code /reconcile} itself refuses to create a
     * new attempt either, since {@code UNKNOWN} is not one of the narrow
     * platform-confirmed-non-acceptance categories — this remains a
     * permanent, disclosed dead end, exactly as it was before this phase,
     * only now expressed through the dedicated status and the reconcile
     * endpoint's own evidence check, rather than through
     * {@code approvalPrecondition} refusing a stale step.
     */
    @Test
    @Order(15)
    void scenarioO_anUncertainAshbySubmitOutcomeReachesReconciliationRequiredAndStaysPermanentlyUnresolved() throws Exception {
        UUID planId = seedApprovedAshbyPlan("life-ashby-5", "Ashby Reliability Engineer");
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk());
        assertThat(planStatus(planId)).isEqualTo("RUNNING");

        // The worker clicked but observed no matching applicationForm.submit
        // response — classifyAshbySubmission's own honest UNKNOWN category,
        // reported to the backend as RECONCILIATION_REQUIRED, never SUBMITTED.
        boolean reported = planRepository.transitionWithResult(planId, "RUNNING", "RECONCILIATION_REQUIRED",
                List.of(Map.of("type", "SUBMISSION_RECONCILIATION", "planType", "ASHBY",
                        "category", "UNKNOWN", "evidence", "no matching applicationForm.submit response was observed for this click")));
        assertThat(reported).isTrue();
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");
        Boolean approvedAfterUncertainAttempt = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(approvedAfterUncertainAttempt).as("approval must be withdrawn, not carried forward").isFalse();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                    .andExpect(status().isNoContent());
        }
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");

        mockMvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/automation/plans/{id}/reconcile", planId).with(csrf()).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_EVIDENCE_INSUFFICIENT"))
                .andExpect(jsonPath("$.category").value("UNKNOWN"));
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");
    }

    /**
     * Phase 15: the Ashby counterpart of Scenario S — Ashby's own
     * {@code applicationForm.submit} response reporting {@code
     * success:false} ({@code SUBMISSION_REJECTED}) is a platform-confirmed
     * non-acceptance, so {@code /reconcile} creates a brand-new, independent
     * plan for a fresh attempt, never mutating the superseded row.
     */
    @Test
    @Order(16)
    void scenarioU_aPlatformConfirmedRejectionPermitsANewIndependentAshbyAttempt() throws Exception {
        UUID planId = seedApprovedAshbyPlan("life-ashby-8", "Ashby Rejected-Submission Engineer");
        mockMvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/automation/plans/claim-next").with(csrf()).session(session))
                .andExpect(status().isOk());
        planRepository.transitionWithResult(planId, "RUNNING", "RECONCILIATION_REQUIRED",
                List.of(Map.of("type", "SUBMISSION_RECONCILIATION", "planType", "ASHBY",
                        "category", "SUBMISSION_REJECTED", "evidence", "Ashby's own submission response reported success:false")));

        MvcResult reconcile = mockMvc.perform(post("/api/v1/automation/plans/{id}/reconcile", planId).with(csrf()).session(session))
                .andReturn();
        assertThat(reconcile.getResponse().getStatus())
                .as("reconcile response body: %s", reconcile.getResponse().getContentAsString())
                .isEqualTo(200);
        Map<String, Object> body = json.readValue(reconcile.getResponse().getContentAsString(), Map.class);
        UUID newPlanId = UUID.fromString((String) body.get("newPlanId"));
        assertThat(newPlanId).isNotEqualTo(planId);
        assertThat(planStatus(planId)).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(planStatus(newPlanId)).isEqualTo("PREPARED");
        String newPlanJson = jdbc.queryForObject("select plan::text from automation_plans where id = ?", String.class, newPlanId);
        assertThat(newPlanJson).doesNotContain("ASHBY_SUBMIT_CLICK");
        Map<String, Object> newPlanBody = json.readValue(newPlanJson, Map.class);
        assertThat(newPlanBody.get("planType")).isEqualTo("ASHBY");

        // Drain the new plan by its own id so it cannot race a later
        // scenario's own claim-next/claimNext() call.
        mockMvc.perform(post("/api/v1/automation/plans/{id}/claim", newPlanId).with(csrf()).session(session))
                .andExpect(status().isOk());
    }

    @Test
    @Order(17)
    void scenarioP_ashbyOutcomeRoundTripsThroughRealPostgres() throws Exception {
        UUID planId = seedRunningAshbyPlan("life-ashby-6", "Ashby Review Engineer");
        Map<String, Object> outcomeResult = Map.of(
                "type", "ASHBY_OUTCOME", "planType", "ASHBY",
                "filled", List.of(Map.of("label", "Legal Name", "category", "IDENTITY_NAME")),
                "requiredGaps", List.of(Map.of("label", "Where do you plan on working from?", "category", "REQUIRES_HUMAN")),
                "resumeUploadState", "NO_ARTIFACT",
                "resumeUploadReason", "no reviewed résumé is available for this application");
        Map<String, Object> completeBody = Map.of(
                "outcome", "HUMAN_REQUIRED", "detail", "1 field filled, 1 needs review",
                "result", List.of(outcomeResult));
        mockMvc.perform(post("/api/v1/automation/plans/{id}/complete", planId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(completeBody)))
                .andExpect(status().isNoContent());

        assertThat(planStatus(planId)).isEqualTo("AWAITING_APPROVAL");

        mockMvc.perform(get("/api/v1/automation/plans/{id}", planId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].type").value("ASHBY_OUTCOME"))
                .andExpect(jsonPath("$.result[0].resumeUploadState").value("NO_ARTIFACT"))
                .andExpect(jsonPath("$.result[0].filled[0].category").value("IDENTITY_NAME"))
                .andExpect(jsonPath("$.result[0].requiredGaps[0].category").value("REQUIRES_HUMAN"));
    }

    /**
     * Phase 14: the V043 single-submission-per-application unique index is
     * already ATS-agnostic (no {@code planType} column reference at all —
     * confirmed by reading the migration), so this proves it directly
     * against the real schema for an ASHBY-correlated pair of plans rather
     * than re-deriving it through a full worker-authenticated HTTP
     * completion cycle (which {@code AutomationLifecycleIT} deliberately
     * does not configure a worker token for — see Scenario G's own comment).
     * A second plan for the SAME application can never also reach SUBMITTED
     * once one already has, regardless of which ATS either plan is for.
     */
    @Test
    @Order(18)
    void scenarioQ_theSingleSubmissionPerApplicationIndexBlocksASecondAshbyPlanForTheSameApplication() throws Exception {
        UUID planId = seedRunningAshbyPlan("life-ashby-7", "Ashby Duplicate-Prevention Engineer");
        UUID applicationId = jdbc.queryForObject(
                "select application_id from automation_plans where id = ?", UUID.class, planId);

        jdbc.update("update automation_plans set status = 'SUBMITTED', submit_approved = true where id = ?", planId);
        assertThat(planStatus(planId)).isEqualTo("SUBMITTED");

        // A second, independent plan row for the SAME application — exactly
        // the shape a retried or duplicated preparation attempt could create.
        UUID secondPlanId = UUID.randomUUID();
        jdbc.update("""
                insert into automation_plans (id, application_id, profile_id, target_url, status, plan, submit_approved,
                                              heartbeat_at, idempotency_key, created_at, updated_at)
                values (?, ?, ?, 'https://jobs.ashbyhq.com/ramp/life-ashby-7b/application', 'RUNNING', '{}'::jsonb, true,
                        now(), ?, now(), now())
                """, secondPlanId, applicationId, PROFILE, "ashby-lifecycle-dup:" + applicationId);

        assertThatThrownBy(() -> jdbc.update(
                "update automation_plans set status = 'SUBMITTED' where id = ?", secondPlanId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(planStatus(secondPlanId)).isEqualTo("RUNNING");
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
