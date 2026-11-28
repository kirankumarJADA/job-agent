package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.documents.DocumentFactValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3I Phase 7: End-to-end PostgreSQL 16 + Flyway V001-V073 parity proof for the
 * shared backend pre-submit authorization fence across all four submit-capable ATS
 * adapters ({@code GREENHOUSE}, {@code ASHBY}, {@code WORKDAY}, {@code UNIVERSAL}) and
 * non-submit refusal ({@code ZOHO_RECRUIT}, {@code LEVER}).
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.worker-event-token=" + PreSubmitFenceParityIT.WORKER_TOKEN,
        "app.secrets.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.sandbox.allow-local-test-hosts=true",
        "app.automation.universal-enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
class PreSubmitFenceParityIT {

    static final String WORKER_TOKEN = "phase7-parity-worker-token";
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-7000-8000-00000000f701");

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

    private record SeededPlan(
            UUID userId,
            UUID profileId,
            UUID jobId,
            UUID applicationId,
            UUID planId,
            UUID cvVersionId,
            String adapter,
            String expectedSubmitStep,
            String targetUrl,
            MockHttpSession session
    ) {}

    @BeforeEach
    void ensureSource() {
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'parity-board', 'Parity Board', '{}', 'DISCOVERY_ONLY')
                on conflict do nothing
                """, SOURCE_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GREENHOUSE", "ASHBY", "WORKDAY", "UNIVERSAL"})
    void authorizedSubmitFlowAndVerifiedCompletionAcrossAllFourAdapters(String adapter) throws Exception {
        SeededPlan s = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");

        mvc.perform(post("/api/v1/automation/plans/{id}/steps", s.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, s.expectedSubmitStep(), s.targetUrl()))))
                .andExpect(status().isNoContent());

        assertThat(fenceCount(s.planId())).isEqualTo(1);

        mvc.perform(post("/api/v1/automation/plans/{id}/complete", s.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "SUBMITTED",
                                "detail", "Confirmed by parity test",
                                "result", List.of(Map.of(
                                        "type", "SUBMISSION_EVIDENCE",
                                        "planType", adapter,
                                        "confirmed", true,
                                        "category", "CONFIRMED",
                                        "evidence", "Confirmed submission " + adapter,
                                        "observedAt", Instant.now().toString()))))))
                .andExpect(status().isNoContent());

        assertThat(planStatus(s.planId())).isEqualTo("SUBMITTED");
        assertThat(applicationStatus(s.applicationId())).isEqualTo("APPLICATION_SUBMITTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GREENHOUSE", "ASHBY", "WORKDAY", "UNIVERSAL"})
    void approvalWithdrawnBeforePrepareOrWhileRunningStopsPreSubmitFenceAndNeverUnwindsPostFence(String adapter) throws Exception {
        // Case A: approval withdrawn while queued (READY_TO_SUBMIT / PREPARED) -> withdraw-approval returns to AWAITING_APPROVAL
        SeededPlan queued = seedAwaitingApprovalPlan(adapter, "ASSISTED");
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", queued.planId())
                        .with(csrf()).session(queued.session()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", queued.planId())
                        .with(csrf()).session(queued.session()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/withdraw-approval", queued.planId())
                        .with(csrf()).session(queued.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn").value(true));
        assertThat(planStatus(queued.planId())).isEqualTo("AWAITING_APPROVAL");
        assertThat(submitApproved(queued.planId())).isFalse();

        // Even if a stale worker attempts the pre-submit fence against that withdrawn plan, it is refused 409
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", queued.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, queued.expectedSubmitStep(), queued.targetUrl()))))
                .andExpect(status().isConflict());
        assertThat(fenceCount(queued.planId())).isZero();

        // Case B: approval withdrawn AFTER claim (plan is RUNNING) before fence (both explicit withdraw and package invalidation)
        SeededPlan runningWithdraw = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        mvc.perform(post("/api/v1/automation/plans/{id}/withdraw-approval", runningWithdraw.planId())
                        .with(csrf()).session(runningWithdraw.session()))
                .andExpect(status().isOk());
        assertThat(submitApproved(runningWithdraw.planId())).isFalse();

        mvc.perform(post("/api/v1/automation/plans/{id}/steps", runningWithdraw.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, runningWithdraw.expectedSubmitStep(), runningWithdraw.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMIT_APPROVAL_WITHDRAWN"));
        assertThat(fenceCount(runningWithdraw.planId())).isZero();

        mvc.perform(post("/api/v1/automation/plans/{id}/complete", runningWithdraw.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "SUBMITTED",
                                "detail", "rogue submit report",
                                "result", List.of(Map.of(
                                        "type", "SUBMISSION_EVIDENCE",
                                        "planType", adapter,
                                        "confirmed", true,
                                        "category", "CONFIRMED",
                                        "evidence", "rogue",
                                        "observedAt", Instant.now().toString()))))))
                .andExpect(status().isConflict());
        assertThat(applicationStatus(runningWithdraw.applicationId())).isNotEqualTo("APPLICATION_SUBMITTED");

        // Case C: CV review revocation while RUNNING invalidates approval across ALL 4 adapters symmetrically
        SeededPlan runningRevoked = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        int invalidated = planRepository.invalidateApproval(runningRevoked.applicationId());
        assertThat(invalidated).isEqualTo(1);
        assertThat(submitApproved(runningRevoked.planId())).isFalse();
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", runningRevoked.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, runningRevoked.expectedSubmitStep(), runningRevoked.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMIT_APPROVAL_WITHDRAWN"));

        // Case D: Once fence IS crossed, explicit withdrawal is refused 409 PRE_SUBMIT_ATTEMPT_ALREADY_STARTED
        SeededPlan alreadyFenced = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", alreadyFenced.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, alreadyFenced.expectedSubmitStep(), alreadyFenced.targetUrl()))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/automation/plans/{id}/withdraw-approval", alreadyFenced.planId())
                        .with(csrf()).session(alreadyFenced.session()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRE_SUBMIT_ATTEMPT_ALREADY_STARTED"));
        assertThat(planRepository.invalidateApproval(alreadyFenced.applicationId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GREENHOUSE", "ASHBY", "WORKDAY", "UNIVERSAL"})
    void concurrentWorkersRacePreSubmitFenceExactlyOncePerAdapter(String adapter) throws Exception {
        SeededPlan s = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        String body = json.writeValueAsString(fenceStepBody(adapter, s.expectedSubmitStep(), s.targetUrl()));
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/automation/plans/{id}/steps", s.planId())
                                .header("X-Worker-Token", WORKER_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> t : tasks) futures.add(pool.submit(t));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        for (Future<Integer> f : futures) statuses.add(f.get(15, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertThat(statuses.stream().filter(c -> c == 204).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(c -> c == 409).count()).isEqualTo(threads - 1);
        assertThat(fenceCount(s.planId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GREENHOUSE", "ASHBY", "WORKDAY", "UNIVERSAL"})
    void crashBeforeVsAfterFenceEnforcesSingleAttemptQuarantine(String adapter) throws Exception {
        // Crash BEFORE fence -> FAILED leaves zero checkpoint
        SeededPlan crashBefore = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        mvc.perform(post("/api/v1/automation/plans/{id}/complete", crashBefore.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "FAILED", "detail", "crashed prior to pre-submit fence"))))
                .andExpect(status().isNoContent());
        assertThat(fenceCount(crashBefore.planId())).isZero();
        assertThat(planStatus(crashBefore.planId())).isEqualTo("FAILED");
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", crashBefore.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, crashBefore.expectedSubmitStep(), crashBefore.targetUrl()))))
                .andExpect(status().isConflict());

        // Crash AFTER fence -> stale recovery quarantines into RECONCILIATION_REQUIRED, never auto-requeued
        SeededPlan crashAfter = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", crashAfter.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, crashAfter.expectedSubmitStep(), crashAfter.targetUrl()))))
                .andExpect(status().isNoContent());

        jdbc.update("update automation_plans set heartbeat_at = now() - interval '60 minutes' where id = ?",
                crashAfter.planId());
        var recovery = planRepository.recoverStaleDetailed(Instant.now().minusSeconds(15 * 60));
        assertThat(recovery.quarantined()).isGreaterThanOrEqualTo(1);
        assertThat(planStatus(crashAfter.planId())).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(submitApproved(crashAfter.planId())).isFalse();

        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", crashAfter.planId())
                        .with(csrf()).session(crashAfter.session()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", crashAfter.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, crashAfter.expectedSubmitStep(), crashAfter.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PRE_SUBMIT_ATTEMPT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GREENHOUSE", "ASHBY", "WORKDAY", "UNIVERSAL"})
    void standingPermissionEligibilityPackageAndBindingDriftRefuseFenceAcrossAllAdapters(String adapter) throws Exception {
        // 1. Standing auto-apply rule raised above match score / disabled directly in DB -> refused
        SeededPlan scoreDrift = seedApprovedPreparedRunningPlan(adapter, "CONTROLLED_AUTO");
        jdbc.update("update user_approval_rules set min_score = 98 where profile_id = ?", scoreDrift.profileId());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", scoreDrift.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, scoreDrift.expectedSubmitStep(), scoreDrift.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STANDING_PERMISSION_WITHDRAWN"));
        assertThat(fenceCount(scoreDrift.planId())).isZero();

        // 2. Salary floor raised above job.salary_max -> ELIGIBILITY_CONSTRAINT_FAILED
        SeededPlan salaryDrift = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        jdbc.update("update preference_sets set salary_min_gbp = 150000 where profile_id = ?", salaryDrift.profileId());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", salaryDrift.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, salaryDrift.expectedSubmitStep(), salaryDrift.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY_CONSTRAINT_FAILED"));
        assertThat(fenceCount(salaryDrift.planId())).isZero();

        // 3. Work eligibility revoked directly in DB -> ELIGIBILITY_CONSTRAINT_FAILED
        SeededPlan visaDrift = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        jdbc.update("update profiles set work_eligibility = '{\"rightToWork\":false}'::jsonb where id = ?", visaDrift.profileId());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", visaDrift.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, visaDrift.expectedSubmitStep(), visaDrift.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY_CONSTRAINT_FAILED"));

        // 4. Job soft-deleted before click -> JOB_DELETED
        SeededPlan jobDeleted = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        jdbc.update("update jobs set deleted_at = now() where id = ?", jobDeleted.jobId());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", jobDeleted.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, jobDeleted.expectedSubmitStep(), jobDeleted.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_DELETED"));

        // 5. Live browser URL drifted to attacker domain -> TARGET_DOMAIN_MISMATCH
        SeededPlan domainMismatch = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", domainMismatch.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(
                                adapter, domainMismatch.expectedSubmitStep(), "https://attacker.evil.example/submit"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARGET_DOMAIN_MISMATCH"));

        // 6. CV review revoked directly in DB bypassing guard -> PACKAGE_NOT_READY
        SeededPlan cvRevoked = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        jdbc.update("update cv_version_reviews set approved = false where cv_version_id = ?", cvRevoked.cvVersionId());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", cvRevoked.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, cvRevoked.expectedSubmitStep(), cvRevoked.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_NOT_READY"));

        // 7. Application withdrawn -> APPLICATION_NOT_ELIGIBLE
        SeededPlan appWithdrawn = seedApprovedPreparedRunningPlan(adapter, "ASSISTED");
        jdbc.update("update applications set status = 'WITHDRAWN' where id = ?", appWithdrawn.applicationId());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", appWithdrawn.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody(adapter, appWithdrawn.expectedSubmitStep(), appWithdrawn.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_ELIGIBLE"));
    }

    @Test
    void sessionSelfAuthCrossCandidateIsolationAndNonSubmitAdaptersRefused() throws Exception {
        SeededPlan gh = seedApprovedPreparedRunningPlan("GREENHOUSE", "ASSISTED");
        SeededPlan other = seedApprovedPreparedRunningPlan("UNIVERSAL", "ASSISTED");

        // Candidate browser session cannot call /steps PRE_SUBMIT_ATTEMPTING (403)
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", gh.planId())
                        .with(csrf()).session(gh.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody("GREENHOUSE", "REAL_SUBMIT_CLICK", gh.targetUrl()))))
                .andExpect(status().isForbidden());

        // Candidate B cannot withdraw Candidate A's plan (404)
        mvc.perform(post("/api/v1/automation/plans/{id}/withdraw-approval", gh.planId())
                        .with(csrf()).session(other.session()))
                .andExpect(status().isNotFound());

        // Cross-adapter spoofing on worker fence request is refused (409 ADAPTER_MISMATCH)
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", gh.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody("UNIVERSAL", "UNIVERSAL_SUBMIT_CLICK", gh.targetUrl()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADAPTER_MISMATCH"));

        // Non-submit adapter (ZOHO_RECRUIT) cannot cross pre-submit fence even if plan flags forced in DB
        SeededPlan nonSubmit = seedApprovedPreparedRunningPlan("UNIVERSAL", "ASSISTED");
        jdbc.update("""
                update automation_plans
                set plan = jsonb_set(plan, '{planType}', '"ZOHO_RECRUIT"')
                where id = ?
                """, nonSubmit.planId());
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", nonSubmit.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "index", 99, "stepId", "pre-submit-attempting", "status", "RUNNING",
                                "result", Map.of("phase", "PRE_SUBMIT_ATTEMPTING")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADAPTER_SUBMIT_UNSUPPORTED"));
        assertThat(fenceCount(nonSubmit.planId())).isZero();
    }

    @Test
    void candidateControllerPreferenceRuleEligibilityAndWithdrawalEndpointsInvalidateRunningApprovals() throws Exception {
        // 1. Preferences update invalidates RUNNING GREENHOUSE plan approval
        SeededPlan gh = seedApprovedPreparedRunningPlan("GREENHOUSE", "ASSISTED");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/preferences")
                        .with(csrf()).session(gh.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.ofEntries(
                                Map.entry("titles", List.of("Platform Engineer")),
                                Map.entry("keywordsInclude", List.of()),
                                Map.entry("keywordsExclude", List.of("ForbiddenTerm")),
                                Map.entry("requiredSkills", List.of("Java")),
                                Map.entry("locationsAllowed", List.of("London")),
                                Map.entry("remoteTypes", List.of("REMOTE")),
                                Map.entry("employmentTypes", List.of("FULL_TIME")),
                                Map.entry("salaryMinGbp", 120000),
                                Map.entry("sponsorshipPolicy", "SHOW_ALL"),
                                Map.entry("applicationMode", "MANUAL"),
                                Map.entry("scoringWeights", Map.of(
                                        "skill", 30, "experience", 15, "visa", 20, "location", 10,
                                        "salary", 10, "career", 10, "difficulty", 5))))))
                .andExpect(status().isOk());
        assertThat(submitApproved(gh.planId())).isFalse();
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", gh.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody("GREENHOUSE", "REAL_SUBMIT_CLICK", gh.targetUrl()))))
                .andExpect(status().isConflict());

        // 2. Disabling user approval rule invalidates RUNNING CONTROLLED_AUTO ASHBY plan
        SeededPlan ashby = seedApprovedPreparedRunningPlan("ASHBY", "CONTROLLED_AUTO");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/approval-rules")
                        .with(csrf()).session(ashby.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoApproveEnabled\":false,\"minScore\":85}"))
                .andExpect(status().isOk());
        assertThat(submitApproved(ashby.planId())).isFalse();
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", ashby.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody("ASHBY", "ASHBY_SUBMIT_CLICK", ashby.targetUrl()))))
                .andExpect(status().isConflict());

        // 3. Updating profile workEligibility invalidates RUNNING WORKDAY plan
        SeededPlan workday = seedApprovedPreparedRunningPlan("WORKDAY", "ASSISTED");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/profile")
                        .with(csrf()).session(workday.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"headline\":\"Platform Engineer\",\"workEligibility\":{\"visaStatus\":\"REQUIRES_SPONSORSHIP\",\"rightToWork\":false}}"))
                .andExpect(status().isOk());
        assertThat(submitApproved(workday.planId())).isFalse();
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", workday.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody("WORKDAY", "WORKDAY_SUBMIT_CLICK", workday.targetUrl()))))
                .andExpect(status().isConflict());

        // 4. Transitioning application to WITHDRAWN via API invalidates RUNNING UNIVERSAL plan
        SeededPlan univ = seedApprovedPreparedRunningPlan("UNIVERSAL", "ASSISTED");
        mvc.perform(post("/api/v1/applications/{id}/status", univ.applicationId())
                        .with(csrf()).session(univ.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "targetStatus", "WITHDRAWN",
                                "eventKey", "withdraw-" + univ.applicationId()))))
                .andExpect(status().isOk());
        assertThat(submitApproved(univ.planId())).isFalse();
        mvc.perform(post("/api/v1/automation/plans/{id}/steps", univ.planId())
                        .header("X-Worker-Token", WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fenceStepBody("UNIVERSAL", "UNIVERSAL_SUBMIT_CLICK", univ.targetUrl()))))
                .andExpect(status().isConflict());
    }

    private SeededPlan seedApprovedPreparedRunningPlan(String adapter, String appMode) throws Exception {
        SeededPlan s = seedAwaitingApprovalPlan(adapter, appMode);
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", s.planId())
                        .with(csrf()).session(s.session()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/prepare-submit", s.planId())
                        .with(csrf()).session(s.session()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/claim", s.planId())
                        .header("X-Worker-Token", WORKER_TOKEN))
                .andExpect(status().isOk());
        assertThat(planStatus(s.planId())).isEqualTo("RUNNING");
        assertThat(submitApproved(s.planId())).isTrue();
        return s;
    }

    private SeededPlan seedAwaitingApprovalPlan(String adapter, String appMode) throws Exception {
        String tag = adapter.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "parity-" + tag + "@example.test";
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, ?, ?, 'LOCAL')
                """, userId, email, passwordEncoder.encode("PasswordA1!"), "Parity " + tag);
        jdbc.update("""
                insert into profiles (id, user_id, headline, work_eligibility)
                values (?, ?, 'Platform Engineer', '{"visaStatus":"SETTLED_OR_CITIZEN","rightToWork":true}'::jsonb)
                """, profileId, userId);

        UUID prefId = UUID.randomUUID();
        jdbc.update("""
                insert into preference_sets (
                    id, profile_id, titles, keywords_include, keywords_exclude, required_skills,
                    locations_allowed, remote_types, employment_types, experience_levels,
                    salary_min_gbp, sponsorship_policy, application_mode, scoring_weights, is_active
                ) values (
                    ?, ?, array['Platform Engineer','Software Engineer']::text[], array[]::text[], array[]::text[],
                    array['Java']::text[], array['London','UK']::text[], array['REMOTE','HYBRID']::text[],
                    array['FULL_TIME']::text[], array[]::text[],
                    60000, 'SHOW_ALL', ?, '{}'::jsonb, true
                )
                """, prefId, profileId, appMode);

        if ("CONTROLLED_AUTO".equals(appMode)) {
            jdbc.update("""
                    insert into user_approval_rules (id, profile_id, auto_approve_enabled, min_score)
                    values (?, ?, true, 80)
                    on conflict (profile_id) do update set auto_approve_enabled = true, min_score = 80
                    """, UUID.randomUUID(), profileId);
        }

        String targetUrl = switch (adapter) {
            case "GREENHOUSE" -> "https://boards.greenhouse.io/acme/jobs/" + tag;
            case "ASHBY" -> "https://jobs.ashbyhq.com/ramp/" + tag + "/application";
            case "WORKDAY" -> "https://acme.wd5.myworkdayjobs.com/en-US/Ext/job/" + tag + "/apply";
            default -> "https://careers.acme-" + tag + ".example.com/jobs/" + tag;
        };
        String expectedSubmitStep = switch (adapter) {
            case "GREENHOUSE" -> "REAL_SUBMIT_CLICK";
            case "ASHBY" -> "ASHBY_SUBMIT_CLICK";
            case "WORKDAY" -> "WORKDAY_SUBMIT_CLICK";
            default -> "UNIVERSAL_SUBMIT_CLICK";
        };

        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, salary_max, status,
                                  content_hash, application_url)
                values (?, ?, ?, ?, 'Parity Corp', 'Platform Engineer', 'London',
                        'Build Java services.', '{Java}'::text[], 100000, 'SCORED', ?, ?)
                """, jobId, SOURCE_ID, tag, "dedup-" + tag, "hash-" + tag, targetUrl);

        if ("CONTROLLED_AUTO".equals(appMode)) {
            jdbc.update("""
                    insert into application_decisions (
                        id, profile_id, job_id, match_score, recommendation, application_mode, decision, reason
                    ) values (?, ?, ?, 90, 'APPLY', 'CONTROLLED_AUTO', 'AUTO_APPLY', 'Standing rule enabled')
                    """, UUID.randomUUID(), profileId, jobId);
        }

        UUID applicationId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, ?, ?, 'READY_TO_APPLY')
                """, applicationId, jobId, profileId, appMode, "identity-" + applicationId);

        byte[] pdf = ("parity-cv-" + applicationId).getBytes(StandardCharsets.UTF_8);
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
                """, cvVersionId, jobId, applicationId, profileId, pdfSha, fileId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvVersionId, applicationId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UUID.randomUUID(), profileId, jobId, applicationId, "hash-" + applicationId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", DocumentFactValidator.VERSION))), cvVersionId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'owner', ?)
                on conflict (cv_version_id) do nothing
                """, cvVersionId, profileId, pdfSha);

        UUID planId = UUID.randomUUID();
        Map<String, Object> planMap;
        List<Map<String, Object>> resultList;
        if ("GREENHOUSE".equals(adapter)) {
            Map<String, Object> pkg = Map.of(
                    "planId", planId.toString(),
                    "applicationId", applicationId.toString(),
                    "jobId", jobId.toString(),
                    "expectedUrl", targetUrl,
                    "cv", Map.of("versionId", cvVersionId.toString(), "sha256", pdfSha),
                    "answers", List.of(),
                    "requiredGaps", List.of());
            planMap = Map.of(
                    "planType", "GREENHOUSE",
                    "version", 1,
                    "correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()),
                    "targetUrl", targetUrl,
                    "package", pkg,
                    "steps", List.of(
                            Map.of("id", "navigate-application", "type", "NAVIGATE", "policy", "AUTO", "params", Map.of("url", targetUrl)),
                            Map.of("id", "validate-form", "type", "VALIDATE", "policy", "AUTO", "params", Map.of())),
                    "safetyContract", "NO_SUBMIT");
            resultList = List.of(Map.of("type", "GREENHOUSE_PREP", "planType", "GREENHOUSE"));
        } else if ("ASHBY".equals(adapter)) {
            planMap = Map.of(
                    "planType", "ASHBY",
                    "version", 1,
                    "correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()),
                    "targetUrl", targetUrl,
                    "steps", List.of(
                            Map.of("id", "navigate-application", "type", "NAVIGATE", "policy", "AUTO", "params", Map.of("url", targetUrl)),
                            Map.of("id", "ashby-apply", "type", "ASHBY_APPLY", "policy", "AUTO", "params", Map.of("identity", Map.of()))),
                    "safetyContract", "NO_SUBMIT");
            resultList = List.of(Map.of(
                    "type", "ASHBY_OUTCOME", "planType", "ASHBY",
                    "filled", List.of(Map.of("label", "Legal Name")),
                    "requiredGaps", List.of(), "resumeUploadState", "NO_FIELD"));
        } else if ("WORKDAY".equals(adapter)) {
            planMap = Map.of(
                    "planType", "WORKDAY",
                    "version", 1,
                    "correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()),
                    "targetUrl", targetUrl,
                    "steps", List.of(
                            Map.of("id", "navigate-application", "type", "NAVIGATE", "policy", "AUTO", "params", Map.of("url", targetUrl)),
                            Map.of("id", "workday-apply", "type", "WORKDAY_APPLY", "policy", "AUTO", "params", Map.of("identity", Map.of()))),
                    "safetyContract", "NO_SUBMIT");
            resultList = List.of(Map.of(
                    "type", "WORKDAY_APPLICATION_OUTCOME", "planType", "WORKDAY",
                    "filled", List.of(Map.of("label", "Legal Name")),
                    "requiredGaps", List.of(), "resumeUploadState", "NO_FIELD"));
        } else {
            Map<String, Object> pkg = Map.of(
                    "applicationId", applicationId.toString(),
                    "jobId", jobId.toString(),
                    "expectedUrl", targetUrl,
                    "cv", Map.of("versionId", cvVersionId.toString(), "sha256", pdfSha),
                    "answers", List.of());
            planMap = Map.of(
                    "planType", "UNIVERSAL",
                    "version", 1,
                    "correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()),
                    "targetUrl", targetUrl,
                    "package", pkg,
                    "steps", List.of(
                            Map.of("id", "navigate-application", "type", "NAVIGATE", "policy", "AUTO", "params", Map.of("url", targetUrl)),
                            Map.of("id", "universal-apply", "type", "UNIVERSAL_APPLY", "policy", "AUTO", "params", Map.of())),
                    "safetyContract", "NO_SUBMIT");
            resultList = List.of(Map.of(
                    "type", "UNIVERSAL_OUTCOME", "planType", "UNIVERSAL",
                    "filled", List.of(Map.of("label", "Name")),
                    "requiredGaps", List.of(), "resumeUploadState", "NO_FIELD"));
        }

        jdbc.update("""
                insert into automation_plans (id, application_id, profile_id, target_url, status, plan, result,
                                              submit_approved, heartbeat_at, idempotency_key, created_at, updated_at)
                values (?, ?, ?, ?, 'AWAITING_APPROVAL', ?::jsonb, ?::jsonb, false, now(), ?, now(), now())
                """, planId, applicationId, profileId, targetUrl,
                json.writeValueAsString(planMap), json.writeValueAsString(resultList),
                "parity:" + planId);

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"PasswordA1!\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        return new SeededPlan(userId, profileId, jobId, applicationId, planId, cvVersionId,
                adapter, expectedSubmitStep, targetUrl, session);
    }

    private static Map<String, Object> fenceStepBody(String adapter, String expectedStep, String currentUrl) {
        return Map.of(
                "index", 99,
                "stepId", "pre-submit-attempting",
                "status", "RUNNING",
                "result", Map.of(
                        "phase", "PRE_SUBMIT_ATTEMPTING",
                        "atsPlatform", adapter,
                        "stepType", expectedStep,
                        "currentUrl", currentUrl));
    }

    private int fenceCount(UUID planId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from automation_steps
                where plan_id = ? and (step_key = 'pre-submit-attempting' or coalesce(result->>'phase', '') = 'PRE_SUBMIT_ATTEMPTING')
                """, Integer.class, planId);
        return n == null ? 0 : n;
    }

    private String planStatus(UUID planId) {
        return jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId);
    }

    private boolean submitApproved(UUID planId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId));
    }

    private String applicationStatus(UUID applicationId) {
        return jdbc.queryForObject("select status from applications where id = ?", String.class, applicationId);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }
}
