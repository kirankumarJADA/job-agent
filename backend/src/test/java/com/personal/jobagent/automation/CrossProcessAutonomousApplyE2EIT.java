package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.documents.DocumentFactValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3I Phase 8: Real cross-process E2E integration test suite.
 *
 * <p>Spawns:
 * <ul>
 *   <li>Real Spring Boot HTTP backend ({@code RANDOM_PORT}) + PostgreSQL 16 Testcontainers</li>
 *   <li>Real Node controlled employer fixture HTTP server ({@code cross_process_fixture_server.mjs})</li>
 *   <li>Real Node worker OS subprocess(es) ({@code worker/src/worker.js}) driving real headless Chromium</li>
 * </ul>
 *
 * <p>Covers Scenarios A–H, apply-time posting liveness checks, prohibited-declaration blocking,
 * unknown-question human resume, pre-fence withdrawal return to {@code AWAITING_APPROVAL}, and
 * lock/withdrawal concurrency without any simulated in-process shortcuts.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.placeholders.remove_seed_dev_account=false",
                "app.discovery.scheduler-enabled=false",
                "app.rate-limit.enabled=false",
                "app.worker-event-token=" + CrossProcessAutonomousApplyE2EIT.WORKER_TOKEN,
                "app.secrets.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "app.sandbox.allow-local-test-hosts=true",
                "app.automation.universal-enabled=true",
                "app.automation.liveness-check-enabled=true"
        }
)
@Testcontainers
class CrossProcessAutonomousApplyE2EIT {

    static final String WORKER_TOKEN = "xproc-stage3i-phase8-worker-token";
    private static final String SESSION_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final UUID GENERIC_SOURCE = UUID.fromString("00000000-0000-7000-8000-000000008801");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static FixtureProcess fixtureA;
    private static FixtureProcess fixtureB;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AutomationPlanRepository planRepository;

    private UUID devProfileId;
    private HttpClient candidateHttp;
    private CookieManager cookieManager;
    private final HttpClient workerHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Process> spawnedWorkers = new ArrayList<>();
    private final List<Path> tempDirs = new ArrayList<>();

    @BeforeAll
    static void startFixtures() throws Exception {
        fixtureA = FixtureProcess.start();
        fixtureB = FixtureProcess.start();
    }

    @AfterAll
    static void stopFixtures() {
        if (fixtureA != null) fixtureA.close();
        if (fixtureB != null) fixtureB.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        fixtureA.reset();
        fixtureA.configure(Map.of(
                "captchaMode", "none",
                "securityMode", "none",
                "includeProhibitedDeclaration", false,
                "includeUnknownQuestion", true,
                "postingClosedHttpStatus", 200,
                "postingClosedBanner", false,
                "loginWallOnGet", false,
                "offOriginRedirect", ""
        ));
        fixtureB.reset();
        fixtureB.configure(Map.of(
                "captchaMode", "none",
                "includeProhibitedDeclaration", false,
                "includeUnknownQuestion", false,
                "postingClosedHttpStatus", 200
        ));

        // Retire any stale PREPARED plans from prior tests before spawning workers
        jdbc.update("update automation_plans set status = 'FAILED', submit_approved = false where status in ('PREPARED','RUNNING')");
        jdbc.update("delete from usage_quotas where quota_key = 'standing_auto_submit_daily'");

        devProfileId = jdbc.queryForObject(
                "select p.id from profiles p join users u on u.id = p.user_id where u.email = 'dev@example.local'",
                UUID.class);
        jdbc.update("update users set display_name = 'Ada Lovelace' where email = 'dev@example.local'");
        jdbc.update("""
                update profiles set phone = '+44 7700 900077', location = 'London, United Kingdom',
                    links = '{"linkedin":"https://linkedin.com/in/adalovelace"}'::jsonb,
                    work_eligibility = '{"rightToWork":true,"eligible":true,"visaStatus":"SETTLED_OR_CITIZEN"}'::jsonb
                where id = ?
                """, devProfileId);

        Integer prefCount = jdbc.queryForObject(
                "select count(*) from preference_sets where profile_id = ? and is_active = true",
                Integer.class, devProfileId);
        if (prefCount == null || prefCount == 0) {
            jdbc.update("""
                    insert into preference_sets (
                        id, profile_id, titles, locations_allowed, remote_types, employment_types,
                        experience_levels, salary_min_gbp, sponsorship_policy, application_mode,
                        scoring_weights, extra_filters, is_active
                    ) values (
                        ?, ?, array['Software Engineer','Platform Engineer']::text[],
                        array['London','United Kingdom','UK']::text[],
                        array['HYBRID','REMOTE']::text[],
                        array['FULL_TIME']::text[],
                        array['MID','SENIOR']::text[],
                        60000, 'SHOW_ALL', 'CONTROLLED_AUTO',
                        '{"skill":70,"experience":10,"visa":5,"location":5,"salary":5,"career":5,"difficulty":0}'::jsonb,
                        '{}'::jsonb, true
                    )
                    """, UUID.randomUUID(), devProfileId);
        } else {
            jdbc.update("""
                    update preference_sets
                    set titles = array['Software Engineer','Platform Engineer']::text[],
                        locations_allowed = array['London','United Kingdom','UK']::text[],
                        remote_types = array['HYBRID','REMOTE']::text[],
                        employment_types = array['FULL_TIME']::text[],
                        experience_levels = array['MID','SENIOR']::text[],
                        salary_min_gbp = 60000,
                        sponsorship_policy = 'SHOW_ALL',
                        application_mode = 'CONTROLLED_AUTO',
                        extra_filters = '{}'::jsonb,
                        updated_at = now()
                    where profile_id = ? and is_active = true
                    """, devProfileId);
        }

        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'COMPANY_SITE', 'xproc-acme', 'XProc Acme Careers', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, GENERIC_SOURCE);

        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        candidateHttp = HttpClient.newBuilder().cookieHandler(cookieManager).connectTimeout(Duration.ofSeconds(5)).build();
        loginAsCandidate();

        // Explicit opt-in for standing autonomous submission via public HTTP API
        HttpResponse<String> ruleRes = candidatePut("/api/v1/approval-rules", Map.of(
                "autoApproveEnabled", true,
                "minScore", 80,
                "standingAutoSubmitEnabled", true,
                "standingMinScore", 85,
                "dailySubmitCap", 10,
                "allowedCountries", List.of("United Kingdom", "UK"),
                "permittedRoles", List.of("Software Engineer", "Platform Engineer")
        ));
        assertThat(ruleRes.statusCode()).isEqualTo(200);
    }

    @AfterEach
    void tearDownWorkers() {
        for (Process p : spawnedWorkers) {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
                try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
        }
        spawnedWorkers.clear();
        jdbc.update("update automation_plans set status = 'FAILED', submit_approved = false where status in ('PREPARED','RUNNING')");
        for (Path dir : tempDirs) {
            try {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            } catch (IOException ignored) {}
        }
        tempDirs.clear();
    }

    @Test
    void scenarioA_standingAutoApplySubmitsOnceWithRealWorkerAndChromiumAndHaltsOnDeclarationOrUnknownQuestion() throws Exception {
        // 1. Prohibited declaration checkbox stops standing automation cleanly in AWAITING_APPROVAL (0 clicks)
        String declUrl = fixtureA.origin + "/careers/apply?role=xproc-decl";
        fixtureA.configure(Map.of("includeProhibitedDeclaration", true, "includeUnknownQuestion", false));
        SeededApp declApp = seedEligibleApplication("xproc-decl", declUrl, true);
        UUID declPlanId = createPackage(declApp.appId());
        Process declWorker = startWorkerProcess(backendBaseUrl() + "/api/v1/automation/plans/claim-next");
        awaitPlanStatus(declPlanId, "AWAITING_APPROVAL", Duration.ofSeconds(25));
        awaitCondition(() -> {
            Integer n = jdbc.queryForObject(
                    "select count(*) from audit_logs where entity_id = ? and action = 'STANDING_SUBMIT_DEFERRED' and details->>'reasonCode' = 'PROHIBITED_DECLARATION'",
                    Integer.class, declPlanId);
            return n != null && n >= 1;
        }, Duration.ofSeconds(5), "STANDING_SUBMIT_DEFERRED audit entry");
        declWorker.destroyForcibly();

        assertThat(submitApproved(declPlanId)).isFalse();
        assertThat(fixtureA.diagnostics().get("acceptedSubmissions")).isEqualTo(0);

        // 2. Unknown mandatory question pauses in AWAITING_APPROVAL until human answers; then pre-confirmed app runs standing Pass1 -> auto-authorize -> Pass2 -> 1 click!
        fixtureA.reset();
        fixtureA.configure(Map.of("includeProhibitedDeclaration", false, "includeUnknownQuestion", true));

        String gapUrl = fixtureA.origin + "/careers/apply?role=xproc-gap";
        SeededApp gapApp = seedEligibleApplication("xproc-gap", gapUrl, false);
        UUID gapPlanId = createPackage(gapApp.appId());
        Process gapWorker = startWorkerProcess(backendBaseUrl() + "/api/v1/automation/plans/claim-next");
        awaitPlanStatus(gapPlanId, "AWAITING_APPROVAL", Duration.ofSeconds(25));
        awaitCondition(() -> {
            Integer n = jdbc.queryForObject(
                    "select count(*) from application_answers where application_id = ? and status = 'NEEDS_USER_INPUT'",
                    Integer.class, gapApp.appId());
            return n != null && n >= 1;
        }, Duration.ofSeconds(5), "NEEDS_USER_INPUT answer recorded");
        gapWorker.destroyForcibly();

        assertThat(submitApproved(gapPlanId)).isFalse();
        assertThat(fixtureA.diagnostics().get("acceptedSubmissions")).isEqualTo(0);
        retirePlan(gapPlanId);

        // 3. Full verified standing autonomous apply: all mandatory Q&As pre-confirmed -> Pass 1 -> auto STANDING_AUTO_SUBMIT -> Pass 2 -> 1 click -> SUBMITTED -> TRACK receipt!
        fixtureA.reset();
        SeededApp happyApp = seedEligibleApplication("xproc-happy", fixtureA.origin + "/careers/apply?role=xproc-happy", true);
        UUID happyPlanId = createPackage(happyApp.appId());
        Process happyWorker = startWorkerProcess(backendBaseUrl() + "/api/v1/automation/plans/claim-next");
        awaitPlanStatus(happyPlanId, "SUBMITTED", Duration.ofSeconds(35));
        happyWorker.destroyForcibly();

        assertThat(fixtureA.diagnostics().get("acceptedSubmissions")).isEqualTo(1);
        assertThat(fenceCount(happyPlanId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, happyApp.appId()))
                .isEqualTo("APPLICATION_SUBMITTED");
        assertThat(jdbc.queryForObject("select count(*) from submission_evidence where application_id = ?", Integer.class, happyApp.appId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where entity_id = ? and action = 'STANDING_SUBMIT_AUTHORIZED'",
                Integer.class, happyPlanId)).isEqualTo(1);

        // Verify TRACK surface preserves trust & honest scope
        HttpResponse<String> trackRes = candidateGet("/api/v1/track/applications/" + happyApp.appId());
        assertThat(trackRes.statusCode()).isEqualTo(200);
        Map<?, ?> track = json.readValue(trackRes.body(), Map.class);
        Map<?, ?> submission = (Map<?, ?>) track.get("submission");
        assertThat(submission.get("state")).isEqualTo("APPLIED");
        assertThat(submission.get("verificationLevel")).isEqualTo("BROWSER_OBSERVED");
        Map<?, ?> scope = (Map<?, ?>) track.get("reconciliationScope");
        assertThat(scope.get("employerStatusSource")).isEqualTo("EMAIL_AND_RECEIPT_ONLY");
        assertThat(scope.get("employerPortalPollingSupported")).isEqualTo(false);
    }

    @Test
    void scenarioB_revokedAuthorizationPreFenceReturnsCleanlyToAwaitingApprovalWithZeroClicksOrReconciliation() throws Exception {
        fixtureA.configure(Map.of("includeProhibitedDeclaration", false, "includeUnknownQuestion", false));
        String targetUrl = fixtureA.origin + "/careers/apply";
        SeededApp app = seedEligibleApplication("xproc-revoke", targetUrl, true);
        UUID planId = createPackage(app.appId());

        // Fast-forward Pass 1 outcome cleanly and standing-authorize Pass 2 so worker picks up UNIVERSAL_SUBMIT_CLICK
        promoteToStandingPass2(planId, app, targetUrl);

        // Intercept the pre-submit fence POST /steps: disable standing auto-submit BEFORE forwarding to backend -> backend returns 409 STANDING_PERMISSION_WITHDRAWN
        AtomicBoolean revokedTriggered = new AtomicBoolean(false);
        try (ProxyServer proxy = ProxyServer.start(serverPort, req -> {
            if (req.method.equals("POST") && req.path.endsWith("/steps")
                    && req.body.contains("PRE_SUBMIT_ATTEMPTING")
                    && revokedTriggered.compareAndSet(false, true)) {
                candidatePut("/api/v1/approval-rules", Map.of(
                        "autoApproveEnabled", true,
                        "minScore", 80,
                        "standingAutoSubmitEnabled", false
                ));
            }
            return ProxyServer.Action.forward();
        })) {
            Process worker = startWorkerProcess("http://127.0.0.1:" + proxy.port() + "/api/v1/automation/plans/claim-next");
            awaitPlanStatus(planId, "AWAITING_APPROVAL", Duration.ofSeconds(30));
            worker.destroyForcibly();
        }

        assertThat(revokedTriggered.get()).isTrue();
        assertThat(fixtureA.diagnostics().get("acceptedSubmissions")).isEqualTo(0);
        assertThat(fenceCount(planId)).isZero();
        assertThat(submitApproved(planId)).isFalse();
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId))
                .isEqualTo("AWAITING_APPROVAL");
        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, app.appId()))
                .isNotEqualTo("APPLICATION_SUBMITTED");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where entity_id = ? and action = 'AUTOMATION_PLAN_PRE_FENCE_WITHDRAWN'",
                Integer.class, planId)).isEqualTo(1);
    }

    @Test
    void scenarioC_duplicateWorkerProcessesAndSecondApplicationForSameJobSubmitOnceOnly() throws Exception {
        fixtureA.configure(Map.of("includeProhibitedDeclaration", false, "includeUnknownQuestion", false));
        String targetUrl = fixtureA.origin + "/careers/apply";
        SeededApp app = seedEligibleApplication("xproc-dup", targetUrl, true);
        UUID planId = createPackage(app.appId());
        promoteToStandingPass2(planId, app, targetUrl);

        Process w1 = startWorkerProcess(backendBaseUrl() + "/api/v1/automation/plans/claim-next");
        Process w2 = startWorkerProcess(backendBaseUrl() + "/api/v1/automation/plans/claim-next");
        awaitPlanStatus(planId, "SUBMITTED", Duration.ofSeconds(35));
        w1.destroyForcibly();
        w2.destroyForcibly();

        assertThat(fixtureA.diagnostics().get("acceptedSubmissions")).isEqualTo(1);
        assertThat(fenceCount(planId)).isEqualTo(1);

        // Even if a second application/plan exists for the SAME job_id, pre-submit fence blocks with 409 DUPLICATE_PRE_SUBMIT_ATTEMPT
        UUID secondAppId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'CONTROLLED_AUTO', ?, 'READY_TO_APPLY')
                """, secondAppId, app.jobId(), devProfileId, "dup-second-" + secondAppId);
        UUID secondPlanId = UuidV7.generate();
        Map<String, Object> dupPlanJson = json.readValue(
                jdbc.queryForObject("select plan::text from automation_plans where id = ?", String.class, planId), Map.class);
        jdbc.update("""
                insert into automation_plans (id, application_id, profile_id, target_url, status, plan, submit_approved, idempotency_key, created_at, updated_at)
                values (?, ?, ?, ?, 'RUNNING', ?::jsonb, true, ?, now(), now())
                """, secondPlanId, secondAppId, devProfileId, targetUrl,
                json.writeValueAsString(dupPlanJson), "dup-plan-" + secondPlanId);

        HttpResponse<String> dupFence = workerPost("/api/v1/automation/plans/" + secondPlanId + "/steps", Map.of(
                "index", 99,
                "stepId", "pre-submit-attempting",
                "status", "RUNNING",
                "result", Map.of(
                        "phase", "PRE_SUBMIT_ATTEMPTING",
                        "atsPlatform", "UNIVERSAL",
                        "stepType", "UNIVERSAL_SUBMIT_CLICK",
                        "currentUrl", targetUrl)));
        assertThat(dupFence.statusCode()).isEqualTo(409);
        assertThat(fenceCount(secondPlanId)).isZero();
    }

    @Test
    void scenarioD_workerCrashAfterFenceQuarantinesSafelyAndRestartNeverClicksSecondTime() throws Exception {
        fixtureA.configure(Map.of("includeProhibitedDeclaration", false, "includeUnknownQuestion", false));
        String targetUrl = fixtureA.origin + "/careers/apply";
        SeededApp app = seedEligibleApplication("xproc-crash", targetUrl, true);
        UUID planId = createPackage(app.appId());
        promoteToStandingPass2(planId, app, targetUrl);

        Path sharedState = Files.createTempDirectory("xproc-crash-state-").resolve("worker-state.json");
        tempDirs.add(sharedState.getParent());

        AtomicReference<Process> activeWorker = new AtomicReference<>();
        AtomicBoolean crashTriggered = new AtomicBoolean(false);
        try (ProxyServer proxy = ProxyServer.start(serverPort, req -> {
            // Once PRE_SUBMIT_ATTEMPTING fence commits to Postgres (when worker next hits /complete), kill worker before /complete commits!
            if (req.method.equals("POST") && req.path.endsWith("/complete") && crashTriggered.compareAndSet(false, true)) {
                Process p = activeWorker.get();
                if (p != null) p.destroyForcibly();
                return ProxyServer.Action.dropConnection();
            }
            return ProxyServer.Action.forward();
        })) {
            Process w1 = startWorkerProcessWithState("http://127.0.0.1:" + proxy.port() + "/api/v1/automation/plans/claim-next", sharedState);
            activeWorker.set(w1);
            awaitCondition(() -> crashTriggered.get(), Duration.ofSeconds(30), "worker crash at /complete after fence");
        }

        assertThat(fenceCount(planId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId))
                .isEqualTo("RUNNING");
        int clicksAfterCrash = (int) fixtureA.diagnostics().get("acceptedSubmissions");

        // Crash recovery quarantines into RECONCILIATION_REQUIRED (never re-clicks, never re-approves)
        jdbc.update("update automation_plans set heartbeat_at = now() - interval '30 minutes' where id = ?", planId);
        var recovery = planRepository.recoverStaleDetailed(Instant.now().minusSeconds(60));
        assertThat(recovery.quarantined()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(submitApproved(planId)).isFalse();

        // Even starting a second fresh worker process pointing at the same state file yields 0 new clicks
        Process w2 = startWorkerProcessWithState(backendBaseUrl() + "/api/v1/automation/plans/claim-next", sharedState);
        Thread.sleep(800);
        w2.destroyForcibly();
        assertThat(fixtureA.diagnostics().get("acceptedSubmissions")).isEqualTo(clicksAfterCrash);
    }

    @Test
    void scenarioE_backendUnreachableForFenceFailsClosedWithZeroClicks() throws Exception {
        fixtureA.configure(Map.of("includeProhibitedDeclaration", false, "includeUnknownQuestion", false));
        String targetUrl = fixtureA.origin + "/careers/apply";
        SeededApp app = seedEligibleApplication("xproc-unavail", targetUrl, true);
        UUID planId = createPackage(app.appId());
        promoteToStandingPass2(planId, app, targetUrl);

        AtomicBoolean droppedFence = new AtomicBoolean(false);
        try (ProxyServer proxy = ProxyServer.start(serverPort, req -> {
            if (req.method.equals("POST") && req.path.endsWith("/steps")
                    && req.body.contains("PRE_SUBMIT_ATTEMPTING")) {
                droppedFence.set(true);
                return ProxyServer.Action.dropConnection();
            }
            return ProxyServer.Action.forward();
        })) {
            Process worker = startWorkerProcess("http://127.0.0.1:" + proxy.port() + "/api/v1/automation/plans/claim-next");
            awaitCondition(droppedFence::get, Duration.ofSeconds(25), "worker fence connection drop");
            // Give worker retries brief window to abort and terminate fail-closed without clicking submit
            Thread.sleep(2500);
            worker.destroyForcibly();
        }

        assertThat(fenceCount(planId)).isZero();
        assertThat(fixtureA.diagnostics().get("acceptedSubmissions")).isEqualTo(0);
    }

    @Test
    void scenarioF_wrongEmployerDomainOrOffOriginTargetRefusesSubmission() throws Exception {
        // Application bound to fixtureA, but pre-submit fence reports currentUrl on fixtureB (different port/origin)
        String targetUrl = fixtureA.origin + "/careers/apply";
        String wrongUrl = fixtureB.origin + "/careers/apply";
        SeededApp app = seedEligibleApplication("xproc-domain", targetUrl, true);
        UUID planId = createPackage(app.appId());
        promoteToStandingPass2(planId, app, targetUrl);
        assertThat(planRepository.claim(planId)).isTrue();

        HttpResponse<String> refused = workerPost("/api/v1/automation/plans/" + planId + "/steps", Map.of(
                "index", 99,
                "stepId", "pre-submit-attempting",
                "status", "RUNNING",
                "result", Map.of(
                        "phase", "PRE_SUBMIT_ATTEMPTING",
                        "atsPlatform", "UNIVERSAL",
                        "stepType", "UNIVERSAL_SUBMIT_CLICK",
                        "currentUrl", wrongUrl)));

        assertThat(refused.statusCode()).isEqualTo(409);
        Map<?, ?> errBody = json.readValue(refused.body(), Map.class);
        assertThat(errBody.get("code")).isEqualTo("TARGET_DOMAIN_MISMATCH");
        assertThat(fenceCount(planId)).isZero();
        assertThat(fixtureB.diagnostics().get("acceptedSubmissions")).isEqualTo(0);
    }

    @Test
    void scenarioG_captchaPausesWithHandoffWithoutBypassAndResumesSafely() throws Exception {
        fixtureA.configure(Map.of("captchaMode", "page2", "includeUnknownQuestion", false));
        String targetUrl = fixtureA.origin + "/careers/apply";
        SeededApp app = seedEligibleApplication("xproc-captcha", targetUrl, true);
        UUID planId = createPackage(app.appId());

        Process worker = startWorkerProcess(backendBaseUrl() + "/api/v1/automation/plans/claim-next");
        awaitPlanStatus(planId, "HANDOFF_REQUIRED", Duration.ofSeconds(30));
        worker.destroyForcibly();

        assertThat(fixtureA.diagnostics().get("acceptedSubmissions")).isEqualTo(0);
        assertThat(fenceCount(planId)).isZero();

        String sessionId = jdbc.queryForObject(
                "select id::text from automation_handoff_sessions where plan_id = ? order by created_at desc limit 1",
                String.class, planId);
        assertThat(sessionId).isNotBlank();

        // Candidate resolves CAPTCHA and clicks resume -> safely returns plan to PREPARED (still requires clean finish / submit)
        fixtureA.configure(Map.of("captchaMode", "none"));
        HttpResponse<String> resumeRes = candidatePost("/api/v1/handoff/" + sessionId + "/resume", Map.of());
        assertThat(resumeRes.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId))
                .isEqualTo("PREPARED");
    }

    @Test
    void scenarioH_tamperedAndOffOriginSubmissionEvidenceIsRefusedNeverMarkingSubmitted() throws Exception {
        String targetUrl = fixtureA.origin + "/careers/apply";
        SeededApp app = seedEligibleApplication("xproc-tamper", targetUrl, true);
        UUID planId = createPackage(app.appId());
        promoteToStandingPass2(planId, app, targetUrl);
        assertThat(planRepository.claim(planId)).isTrue();
        assertThat(planRepository.recordPreSubmitAttemptIfAbsent(
                planId, 99, "RUNNING", Map.of("phase", "PRE_SUBMIT_ATTEMPTING"), null)).isTrue();

        // Forge rawEvidence claiming evil off-origin finalOrigin
        String forgedRaw = json.writeValueAsString(Map.of(
                "schemaVersion", "robin-submission-evidence-v2",
                "digest", "0".repeat(64),
                "attemptCorrelation", Map.of(
                        "planId", planId.toString(),
                        "applicationId", app.appId().toString(),
                        "jobId", app.jobId().toString()),
                "employerVerification", "NOT_VERIFIED_BY_BROWSER",
                "finalOrigin", fixtureB.origin,
                "redirectChain", List.of(Map.of("url", fixtureB.origin + "/confirm", "host", "127.0.0.1")),
                "confirmation", Map.of("confirmationId", "FAKE-123", "signals", List.of("TEXT_ONLY")),
                "documents", Map.of()));

        HttpResponse<String> res = workerPost("/api/v1/automation/plans/" + planId + "/complete", Map.of(
                "outcome", "SUBMITTED",
                "detail", "Spoofed off-origin confirmation",
                "result", List.of(Map.of(
                        "type", "SUBMISSION_EVIDENCE",
                        "planType", "UNIVERSAL",
                        "confirmed", true,
                        "category", "CONFIRMED",
                        "evidence", "Spoofed banner",
                        "rawEvidence", forgedRaw,
                        "observedAt", Instant.now().toString()))));

        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId))
                .isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, app.appId()))
                .isNotEqualTo("APPLICATION_SUBMITTED");
        assertThat(jdbc.queryForObject("select count(*) from submission_evidence where application_id = ?", Integer.class, app.appId()))
                .isZero();
    }

    @Test
    void applyTimeLivenessAndLockConcurrencyBehaveCorrectlyEndToEnd() throws Exception {
        // 1. Apply-time liveness: HTTP 404 closed posting -> claim-next marks FAILED (JOB_POSTING_CLOSED), returns 204
        fixtureA.configure(Map.of("postingClosedHttpStatus", 404));
        SeededApp closedApp = seedEligibleApplication("xproc-404", fixtureA.origin + "/careers/apply", true);
        UUID closedPlanId = createPackage(closedApp.appId());
        HttpResponse<String> claim404 = workerPost("/api/v1/automation/plans/claim-next", Map.of());
        assertThat(claim404.statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, closedPlanId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where entity_id = ? and action = 'AUTOMATION_LIVENESS_FAILED'",
                Integer.class, closedPlanId)).isEqualTo(1);

        // 2. Apply-time liveness: login wall -> INCONCLUSIVE, claims cleanly without marking closed
        fixtureA.configure(Map.of("postingClosedHttpStatus", 200, "loginWallOnGet", true));
        SeededApp loginApp = seedEligibleApplication("xproc-login", fixtureA.origin + "/careers/apply", true);
        UUID loginPlanId = createPackage(loginApp.appId());
        HttpResponse<String> claimLogin = workerPost("/api/v1/automation/plans/claim-next", Map.of());
        assertThat(claimLogin.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, loginPlanId))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where entity_id = ? and action = 'AUTOMATION_LIVENESS_INCONCLUSIVE'",
                Integer.class, loginPlanId)).isEqualTo(1);
        retirePlan(loginPlanId);

        // 3. Deterministic concurrency: withdraw-approval racing pre-submit fence preserves mutual exclusion
        fixtureA.configure(Map.of("loginWallOnGet", false));
        SeededApp raceApp = seedEligibleApplication("xproc-race", fixtureA.origin + "/careers/apply", true);
        UUID racePlanId = createPackage(raceApp.appId());
        promoteToStandingPass2(racePlanId, raceApp, fixtureA.origin + "/careers/apply");
        assertThat(planRepository.claim(racePlanId)).isTrue();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<HttpResponse<String>> withdrawTask = () -> {
            start.await(5, TimeUnit.SECONDS);
            return candidatePost("/api/v1/automation/plans/" + racePlanId + "/withdraw-approval", Map.of());
        };
        Callable<HttpResponse<String>> fenceTask = () -> {
            start.await(5, TimeUnit.SECONDS);
            return workerPost("/api/v1/automation/plans/" + racePlanId + "/steps", Map.of(
                    "index", 99, "stepId", "pre-submit-attempting", "status", "RUNNING",
                    "result", Map.of("phase", "PRE_SUBMIT_ATTEMPTING", "atsPlatform", "UNIVERSAL",
                            "stepType", "UNIVERSAL_SUBMIT_CLICK", "currentUrl", fixtureA.origin + "/careers/apply")));
        };
        Future<HttpResponse<String>> fWithdraw = pool.submit(withdrawTask);
        Future<HttpResponse<String>> fFence = pool.submit(fenceTask);
        start.countDown();
        HttpResponse<String> rWithdraw = fWithdraw.get(15, TimeUnit.SECONDS);
        HttpResponse<String> rFence = fFence.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        // Mutual exclusion: either withdraw won (200 OK & fence got 409 SUBMIT_APPROVAL_WITHDRAWN & 0 fences)
        // or fence won (204 No Content & withdraw got 409 PRE_SUBMIT_ATTEMPT_ALREADY_STARTED & 1 fence).
        boolean withdrawWon = rWithdraw.statusCode() == 200 && rFence.statusCode() == 409 && fenceCount(racePlanId) == 0;
        boolean fenceWon = rFence.statusCode() == 204 && rWithdraw.statusCode() == 409 && fenceCount(racePlanId) == 1;
        assertThat(withdrawWon ^ fenceWon)
                .as("withdraw=%s fence=%s fences=%s", rWithdraw.statusCode(), rFence.statusCode(), fenceCount(racePlanId))
                .isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record SeededApp(UUID jobId, UUID appId, UUID cvVersionId, String cvSha) {}

    private SeededApp seedEligibleApplication(String tag, String applyUrl, boolean preConfirmNotice) throws Exception {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (
                    id, source_id, external_id, dedup_key, company_name_raw, title,
                    location_raw, city, country, remote_type, employment_type, experience_level,
                    salary_min, salary_max, salary_currency, description_text, skills_extracted,
                    status, last_seen_at, content_hash, application_url
                ) values (
                    ?, ?, ?, ?, 'XProc Acme', 'Software Engineer',
                    'London, United Kingdom', 'London', 'United Kingdom', 'HYBRID', 'FULL_TIME', 'MID',
                    75000, 95000, 'GBP', 'Senior backend role.', '{Java}'::text[],
                    'SCORED', now(), ?, ?
                )
                """, jobId, GENERIC_SOURCE, tag, "dedup-" + tag, "hash-" + tag, applyUrl);

        jdbc.update("""
                insert into job_matches (profile_id, job_id, score, recommendation, breakdown, scored_at)
                values (?, ?, 92, 'APPLY', '{}'::jsonb, now())
                on conflict (profile_id, job_id) do update set score = 92, recommendation = 'APPLY', scored_at = now()
                """, devProfileId, jobId);

        jdbc.update("""
                insert into application_decisions (id, profile_id, job_id, match_score, recommendation, application_mode, decision, reason)
                values (?, ?, ?, 92, 'APPLY', 'CONTROLLED_AUTO', 'AUTO_APPLY', 'Standing eligible')
                on conflict (profile_id, job_id) do update set decision = 'AUTO_APPLY', match_score = 92, updated_at = now()
                """, UUID.randomUUID(), devProfileId, jobId);

        UUID appId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'CONTROLLED_AUTO', ?, 'READY_TO_APPLY')
                """, appId, jobId, devProfileId, "identity-" + appId);

        byte[] pdf = ("%PDF-1.4\n% " + tag + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
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
                values (?, ?, ?, ?, ?, 'Software Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UuidV7.generate(), devProfileId, jobId, appId, "hash-" + cvId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", DocumentFactValidator.VERSION))), cvId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'owner', ?)
                on conflict (cv_version_id) do nothing
                """, cvId, devProfileId, pdfSha);

        insertConfirmedAnswer(appId, jobId, "Are you legally authorized to work in the United Kingdom?", "Yes");
        if (preConfirmNotice) {
            insertConfirmedAnswer(appId, jobId, "What is your current notice period?", "4 weeks");
        }
        return new SeededApp(jobId, appId, cvId, pdfSha);
    }

    private void insertConfirmedAnswer(UUID appId, UUID jobId, String qText, String answerText) throws Exception {
        jdbc.update("""
                insert into application_answers (id, profile_id, job_id, application_id, question_text, question_type,
                                                 answer_text, confidence, status, validation_notes, human_confirmed,
                                                 answer_origin, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'STRUCTURED_FIELD', ?, 1.0, 'ANSWERED', ?::jsonb, true, 'CANDIDATE_CONFIRMED', now(), now())
                """, UuidV7.generate(), devProfileId, jobId, appId, qText, answerText,
                json.writeValueAsString(Map.of("reusable", false)));
    }

    private UUID createPackage(UUID appId) throws Exception {
        HttpResponse<String> res = candidatePost("/api/v1/apply/applications/" + appId + "/package", Map.of());
        assertThat(res.statusCode()).as("create package body=%s", res.body()).isEqualTo(200);
        return UUID.fromString(String.valueOf(json.readValue(res.body(), Map.class).get("planId")));
    }

    private void promoteToStandingPass2(UUID planId, SeededApp app, String targetUrl) throws Exception {
        assertThat(planRepository.claim(planId)).isTrue();
        HttpResponse<String> pass1Complete = workerPost("/api/v1/automation/plans/" + planId + "/complete", Map.of(
                "outcome", "HUMAN_REQUIRED",
                "detail", "Pass 1 ready",
                "result", List.of(Map.of(
                        "type", "UNIVERSAL_OUTCOME",
                        "planType", "UNIVERSAL",
                        "filled", List.of("First Name", "Last Name", "Email"),
                        "requiredGaps", List.of(),
                        "currentUrl", targetUrl))));
        assertThat(pass1Complete.statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId))
                .isEqualTo("PREPARED");
        assertThat(submitApproved(planId)).isTrue();
    }

    private Process startWorkerProcess(String pollUrl) throws IOException {
        Path tmp = Files.createTempDirectory("xproc-worker-");
        tempDirs.add(tmp);
        return startWorkerProcessWithState(pollUrl, tmp.resolve("worker-state.json"));
    }

    private Process startWorkerProcessWithState(String pollUrl, Path statePath) throws IOException {
        Path workerDir = resolveWorkerDir();
        Path artifactDir = statePath.getParent().resolve("artifacts");
        Files.createDirectories(artifactDir);
        URI pollUri = URI.create(pollUrl);
        String apiRoot = pollUri.getScheme() + "://" + pollUri.getAuthority() + "/api/v1/automation";

        ProcessBuilder pb = new ProcessBuilder("node", "src/worker.js");
        pb.directory(workerDir.toFile());
        Map<String, String> env = pb.environment();
        env.put("WORKER_POLL_URL", pollUrl);
        env.put("WORKER_COMPLETE_URL", apiRoot + "/plans/{id}/complete");
        env.put("WORKER_HEARTBEAT_URL", apiRoot + "/plans/{id}/heartbeat");
        env.put("WORKER_EVENT_URL", apiRoot + "/events");
        env.put("WORKER_EVENT_TOKEN", WORKER_TOKEN);
        env.put("WORKER_POLL_INTERVAL_MS", "120");
        env.put("WORKER_STATE_PATH", statePath.toAbsolutePath().toString());
        env.put("WORKER_ARTIFACT_DIR", artifactDir.toAbsolutePath().toString());
        env.put("ROBIN_WORKER_SESSION_KEY", SESSION_KEY_B64);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        spawnedWorkers.add(p);
        // Drain stdout asynchronously so OS pipe never blocks worker
        Thread.ofVirtual().start(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (br.readLine() != null) { /* drain */ }
            } catch (IOException ignored) {}
        });
        return p;
    }

    private static Path resolveWorkerDir() {
        Path p1 = Path.of("../worker").toAbsolutePath().normalize();
        if (Files.isDirectory(p1)) return p1;
        Path p2 = Path.of("worker").toAbsolutePath().normalize();
        if (Files.isDirectory(p2)) return p2;
        throw new IllegalStateException("Cannot resolve worker directory from " + Path.of("").toAbsolutePath());
    }

    private String backendBaseUrl() {
        return "http://127.0.0.1:" + serverPort;
    }

    private void loginAsCandidate() throws Exception {
        candidateHttp.send(
                HttpRequest.newBuilder(URI.create(backendBaseUrl() + "/api/v1/profile")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> loginRes = candidatePost("/api/v1/auth/login", Map.of(
                "email", "dev@example.local", "password", "DevPassword123!"));
        assertThat(loginRes.statusCode()).isEqualTo(200);
        // Materialize fresh authenticated XSRF cookie
        candidateGet("/api/v1/profile");
    }

    private HttpResponse<String> candidateGet(String path) throws Exception {
        return candidateHttp.send(
                HttpRequest.newBuilder(URI.create(backendBaseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> candidatePost(String path, Map<String, Object> body) throws Exception {
        return candidateMutate("POST", path, body);
    }

    private HttpResponse<String> candidatePut(String path, Map<String, Object> body) throws Exception {
        return candidateMutate("PUT", path, body);
    }

    private HttpResponse<String> candidateMutate(String method, String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(backendBaseUrl() + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        String xsrf = xsrfToken();
        if (xsrf != null) {
            b.header("X-XSRF-TOKEN", xsrf);
        }
        return candidateHttp.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String xsrfToken() {
        for (HttpCookie c : cookieManager.getCookieStore().getCookies()) {
            if ("XSRF-TOKEN".equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private HttpResponse<String> workerPost(String path, Map<String, Object> body) throws Exception {
        return workerHttp.send(
                HttpRequest.newBuilder(URI.create(backendBaseUrl() + path))
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private boolean submitApproved(UUID planId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId));
    }

    private int fenceCount(UUID planId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from automation_steps
                where plan_id = ? and (step_key = 'pre-submit-attempting' or coalesce(result->>'phase', '') = 'PRE_SUBMIT_ATTEMPTING')
                """, Integer.class, planId);
        return n == null ? 0 : n;
    }

    private void retirePlan(UUID planId) {
        jdbc.update("update automation_plans set status = 'FAILED', submit_approved = false where id = ?", planId);
    }

    private void awaitPlanStatus(UUID planId, String expectedStatus, Duration timeout) throws Exception {
        awaitCondition(() -> {
            String st = jdbc.queryForObject("select status from automation_plans where id = ?", String.class, planId);
            return expectedStatus.equals(st);
        }, timeout, "plan " + planId + " to reach " + expectedStatus);
    }

    private void awaitCondition(Callable<Boolean> check, Duration timeout, String desc) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(check.call())) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + desc);
    }

    // ── Controlled Node Fixture Process Handle ───────────────────────────────

    private static final class FixtureProcess implements AutoCloseable {
        final Process process;
        final String origin;
        private final HttpClient http = HttpClient.newHttpClient();
        private final ObjectMapper json = new ObjectMapper();

        private FixtureProcess(Process process, String origin) {
            this.process = process;
            this.origin = origin;
        }

        static FixtureProcess start() throws Exception {
            Path workerDir = resolveWorkerDir();
            ProcessBuilder pb = new ProcessBuilder("node", "test/fixtures/cross_process_fixture_server.mjs");
            pb.directory(workerDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String origin = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                String line = reader.readLine();
                if (line == null) break;
                if (line.startsWith("READY ")) {
                    origin = line.substring("READY ".length()).trim();
                    break;
                }
            }
            if (origin == null) {
                p.destroyForcibly();
                throw new IllegalStateException("Fixture server did not emit READY origin");
            }
            Thread.ofVirtual().start(() -> {
                try { while (reader.readLine() != null) {} } catch (IOException ignored) {}
            });
            return new FixtureProcess(p, origin);
        }

        void configure(Map<String, Object> cfg) throws Exception {
            http.send(HttpRequest.newBuilder(URI.create(origin + "/__test/config"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cfg)))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }

        void reset() throws Exception {
            http.send(HttpRequest.newBuilder(URI.create(origin + "/__test/reset"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics() throws Exception {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(origin + "/__test/diagnostics"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            return json.readValue(res.body(), Map.class);
        }

        @Override
        public void close() {
            process.destroyForcibly();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
    }

    // ── Deterministic HTTP Intercept Proxy for Process Crash / Network Drop ──

    private static final class ProxyServer implements AutoCloseable {
        enum Type { FORWARD, DROP }
        record Action(Type type) {
            static Action forward() { return new Action(Type.FORWARD); }
            static Action dropConnection() { return new Action(Type.DROP); }
        }
        record RequestInfo(String method, String path, String body) {}
        @FunctionalInterface
        interface Interceptor { Action intercept(RequestInfo req) throws Exception; }

        private final com.sun.net.httpserver.HttpServer server;
        private final HttpClient upstream = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        private ProxyServer(com.sun.net.httpserver.HttpServer server) {
            this.server = server;
        }

        static ProxyServer start(int targetPort, Interceptor interceptor) throws Exception {
            var s = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ProxyServer ps = new ProxyServer(s);
            s.createContext("/", ex -> {
                byte[] reqBytes = ex.getRequestBody().readAllBytes();
                String body = new String(reqBytes, StandardCharsets.UTF_8);
                String path = ex.getRequestURI().getRawPath();
                String query = ex.getRequestURI().getRawQuery();
                String fullPath = query != null ? path + "?" + query : path;
                try {
                    Action action = interceptor.intercept(new RequestInfo(ex.getRequestMethod(), path, body));
                    if (action.type() == Type.DROP) {
                        ex.close();
                        return;
                    }
                    URI targetUri = URI.create("http://127.0.0.1:" + targetPort + fullPath);
                    HttpRequest.Builder rb = HttpRequest.newBuilder(targetUri)
                            .method(ex.getRequestMethod(), reqBytes.length > 0
                                    ? HttpRequest.BodyPublishers.ofByteArray(reqBytes)
                                    : HttpRequest.BodyPublishers.noBody());
                    ex.getRequestHeaders().forEach((k, vals) -> {
                        if (!"host".equalsIgnoreCase(k) && !"content-length".equalsIgnoreCase(k) && !"connection".equalsIgnoreCase(k)) {
                            for (String v : vals) rb.header(k, v);
                        }
                    });
                    HttpResponse<byte[]> upRes = ps.upstream.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                    upRes.headers().map().forEach((k, vals) -> {
                        if (!"transfer-encoding".equalsIgnoreCase(k)
                                && !"content-length".equalsIgnoreCase(k)
                                && !k.startsWith(":")) {
                            ex.getResponseHeaders().put(k, vals);
                        }
                    });
                    byte[] respBytes = upRes.body();
                    ex.sendResponseHeaders(upRes.statusCode(), upRes.statusCode() == 204 ? -1 : respBytes.length);
                    if (upRes.statusCode() != 204 && respBytes.length > 0) {
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(respBytes);
                        }
                    }
                } catch (Exception e) {
                    ex.close();
                } finally {
                    ex.close();
                }
            });
            s.setExecutor(Executors.newCachedThreadPool());
            s.start();
            return ps;
        }

        int port() { return server.getAddress().getPort(); }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
