package com.personal.jobagent.automation.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.automation.AutomationPlanRepository;
import org.junit.jupiter.api.*;
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

import java.time.Instant;
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
        "app.worker-event-token=test-worker-token-phase3"
})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 3: ATS Handoff Integration Suite")
class AtsHandoffIntegrationIT {

    private static final String WORKER_TOKEN = "test-worker-token-phase3";

    private static final UUID USER_A = UUID.fromString("00000000-0000-7000-8000-00000000aa10");
    private static final UUID PROFILE_A = UUID.fromString("00000000-0000-7000-8000-00000000aa11");

    private static final UUID USER_B = UUID.fromString("00000000-0000-7000-8000-00000000bb10");
    private static final UUID PROFILE_B = UUID.fromString("00000000-0000-7000-8000-00000000bb11");

    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-7000-8000-00000000aa30");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper json;
    @Autowired private AutomationPlanRepository planRepository;
    @Autowired private HandoffSessionRepository handoffRepository;

    private MockHttpSession sessionA;
    private MockHttpSession sessionB;

    @BeforeEach
    void setUpUsers() throws Exception {
        // Candidate A
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, display_name, auth_provider)
                VALUES (?, 'candidateA@example.com', ?, 'Candidate A', 'LOCAL')
                ON CONFLICT (email) DO NOTHING
                """, USER_A, passwordEncoder.encode("PasswordA1!"));
        jdbc.update("INSERT INTO profiles (id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", PROFILE_A, USER_A);

        // Candidate B
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, display_name, auth_provider)
                VALUES (?, 'candidateB@example.com', ?, 'Candidate B', 'LOCAL')
                ON CONFLICT (email) DO NOTHING
                """, USER_B, passwordEncoder.encode("PasswordB1!"));
        jdbc.update("INSERT INTO profiles (id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", PROFILE_B, USER_B);

        // Job Source
        jdbc.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                VALUES (?, 'LEVER', 'handoff-org', 'Handoff Source', '{}', 'DISCOVERY_ONLY')
                ON CONFLICT (kind, org_identifier) DO NOTHING
                """, SOURCE_ID);

        // Login sessions
        if (sessionA == null) {
            MvcResult loginA = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"candidateA@example.com\",\"password\":\"PasswordA1!\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            sessionA = (MockHttpSession) loginA.getRequest().getSession(false);
        }

        if (sessionB == null) {
            MvcResult loginB = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"candidateB@example.com\",\"password\":\"PasswordB1!\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            sessionB = (MockHttpSession) loginB.getRequest().getSession(false);
        }
    }

    private record SeededApplication(UUID jobId, UUID applicationId, UUID planId) {}

    private SeededApplication seedPlan(UUID profileId, String atsKind, String externalId) throws Exception {
        UUID jobId = UUID.randomUUID();
        String applyUrl = "https://jobs." + atsKind.toLowerCase() + ".co/org/" + externalId;
        jdbc.update("""
                INSERT INTO jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, status, content_hash, application_url)
                VALUES (?, ?, ?, ?, 'TechCorp', 'Software Engineer', 'London', 'Build systems', 'DISCOVERED', ?, ?)
                """, jobId, SOURCE_ID, externalId, "dedup-" + externalId, "hash-" + externalId, applyUrl);

        UUID applicationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO applications (id, job_id, profile_id, mode, identity_key, status)
                VALUES (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')
                """, applicationId, jobId, profileId, "identity-" + applicationId);

        UUID planId = UUID.randomUUID();
        Map<String, Object> planBody = Map.of(
                "applicationId", applicationId.toString(),
                "jobId", jobId.toString(),
                "targetUrl", applyUrl,
                "planType", atsKind.toUpperCase(),
                "correlation", Map.of("applicationId", applicationId.toString(), "jobId", jobId.toString()),
                "steps", java.util.List.of()
        );
        jdbc.update("""
                INSERT INTO automation_plans (id, application_id, target_url, plan, idempotency_key, profile_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, 'RUNNING', now(), now())
                """, planId, applicationId, applyUrl, json.writeValueAsString(planBody), "key-" + planId, profileId);

        return new SeededApplication(jobId, applicationId, planId);
    }

    @Test
    @Order(1)
    @DisplayName("Lever CAPTCHA handoff flow: initiate -> pending -> confirm -> ready_to_submit")
    void testLeverCaptchaHandoffFlow() throws Exception {
        SeededApplication seeded = seedPlan(PROFILE_A, "LEVER", "lever-cap-1");

        // 1. Worker encounters CAPTCHA and calls /initiate
        var initiateReq = new HandoffController.InitiateRequest(
                seeded.planId(), seeded.applicationId(), "LEVER",
                "https://jobs.lever.co/org/lever-cap-1", "LEVER_CAPTCHA_PRESENT",
                "evidence/cap1.png", 15
        );

        MvcResult initResult = mockMvc.perform(post("/api/v1/handoff/initiate")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(initiateReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.blockReason").value("CAPTCHA"))
                .andExpect(jsonPath("$.applicationId").value(seeded.applicationId().toString()))
                .andReturn();

        HandoffSessionRecord session = json.readValue(initResult.getResponse().getContentAsString(), HandoffSessionRecord.class);
        UUID sessionId = session.id();

        // Verify plan transitioned to HANDOFF_REQUIRED
        var planOpt = planRepository.findById(seeded.planId());
        assertThat(planOpt).isPresent();
        assertThat(planOpt.get().status()).isEqualTo("HANDOFF_REQUIRED");

        // 2. Candidate queries active handoff
        mockMvc.perform(get("/api/v1/handoff/applications/{id}", seeded.applicationId())
                        .session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 3. Candidate resolves challenge and clicks Confirm & Resume
        mockMvc.perform(post("/api/v1/handoff/{id}/complete", sessionId)
                        .session(sessionA).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 4. Verify plan is now READY_TO_SUBMIT
        var updatedPlan = planRepository.findById(seeded.planId());
        assertThat(updatedPlan).isPresent();
        assertThat(updatedPlan.get().status()).isEqualTo("READY_TO_SUBMIT");
    }

    @Test
    @Order(2)
    @DisplayName("Workday MFA handoff flow: initiate -> candidate enters MFA -> complete")
    void testWorkdayMfaHandoffFlow() throws Exception {
        SeededApplication seeded = seedPlan(PROFILE_A, "WORKDAY", "workday-mfa-1");

        var initiateReq = new HandoffController.InitiateRequest(
                seeded.planId(), seeded.applicationId(), "WORKDAY",
                "https://jobs.workday.co/org/workday-mfa-1", "WORKDAY_MFA_REQUIRED",
                null, 15
        );

        MvcResult initResult = mockMvc.perform(post("/api/v1/handoff/initiate")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(initiateReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.blockReason").value("MFA"))
                .andReturn();

        HandoffSessionRecord session = json.readValue(initResult.getResponse().getContentAsString(), HandoffSessionRecord.class);

        // Candidate completes MFA verification
        mockMvc.perform(post("/api/v1/handoff/{id}/complete", session.id())
                        .session(sessionA).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(planRepository.findById(seeded.planId()).get().status()).isEqualTo("READY_TO_SUBMIT");
    }

    @Test
    @Order(3)
    @DisplayName("Expiry handling: expired handoff session rejects completion with 409 Conflict")
    void testHandoffSessionExpiration() throws Exception {
        SeededApplication seeded = seedPlan(PROFILE_A, "LEVER", "lever-exp-1");

        var initiateReq = new HandoffController.InitiateRequest(
                seeded.planId(), seeded.applicationId(), "LEVER",
                "https://jobs.lever.co/org/lever-exp-1", "LEVER_CAPTCHA_PRESENT", null, 15
        );

        MvcResult initResult = mockMvc.perform(post("/api/v1/handoff/initiate")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(initiateReq)))
                .andExpect(status().isCreated())
                .andReturn();

        HandoffSessionRecord session = json.readValue(initResult.getResponse().getContentAsString(), HandoffSessionRecord.class);

        // Force session expiration in database
        jdbc.update("UPDATE automation_handoff_sessions SET expires_at = clock_timestamp() - interval '1 minute' WHERE id = ?", session.id());

        // Attempting to complete expired session must be rejected with 409
        mockMvc.perform(post("/api/v1/handoff/{id}/complete", session.id())
                        .session(sessionA).with(csrf()))
                .andExpect(status().isConflict());

        // Plan remains in HANDOFF_REQUIRED, not resumed
        assertThat(planRepository.findById(seeded.planId()).get().status()).isEqualTo("HANDOFF_REQUIRED");
    }

    @Test
    @Order(4)
    @DisplayName("Cancellation flow: candidate cancels handoff -> CANCELLED -> plan FAILED")
    void testHandoffSessionCancellation() throws Exception {
        SeededApplication seeded = seedPlan(PROFILE_A, "LEVER", "lever-cancel-1");

        var initiateReq = new HandoffController.InitiateRequest(
                seeded.planId(), seeded.applicationId(), "LEVER",
                "https://jobs.lever.co/org/lever-cancel-1", "LEVER_CAPTCHA_PRESENT", null, 15
        );

        MvcResult initResult = mockMvc.perform(post("/api/v1/handoff/initiate")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(initiateReq)))
                .andExpect(status().isCreated())
                .andReturn();

        HandoffSessionRecord session = json.readValue(initResult.getResponse().getContentAsString(), HandoffSessionRecord.class);

        // Candidate cancels handoff
        mockMvc.perform(post("/api/v1/handoff/{id}/cancel", session.id())
                        .session(sessionA).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Verify plan transitioned to FAILED
        assertThat(planRepository.findById(seeded.planId()).get().status()).isEqualTo("FAILED");
    }

    @Test
    @Order(5)
    @DisplayName("Duplicate completion prevention: second complete returns 409 Conflict")
    void testDuplicateCompletionPrevented() throws Exception {
        SeededApplication seeded = seedPlan(PROFILE_A, "LEVER", "lever-dup-1");

        var initiateReq = new HandoffController.InitiateRequest(
                seeded.planId(), seeded.applicationId(), "LEVER",
                "https://jobs.lever.co/org/lever-dup-1", "LEVER_CAPTCHA_PRESENT", null, 15
        );

        MvcResult initResult = mockMvc.perform(post("/api/v1/handoff/initiate")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(initiateReq)))
                .andExpect(status().isCreated())
                .andReturn();

        HandoffSessionRecord session = json.readValue(initResult.getResponse().getContentAsString(), HandoffSessionRecord.class);

        // First completion succeeds
        mockMvc.perform(post("/api/v1/handoff/{id}/complete", session.id())
                        .session(sessionA).with(csrf()))
                .andExpect(status().isOk());

        // Second completion is rejected with 409
        mockMvc.perform(post("/api/v1/handoff/{id}/complete", session.id())
                        .session(sessionA).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(6)
    @DisplayName("IDOR protection: Candidate B cannot read, complete, or cancel Candidate A's session")
    void testIdorCrossProfileProtection() throws Exception {
        SeededApplication seededA = seedPlan(PROFILE_A, "LEVER", "lever-idor-1");

        var initiateReq = new HandoffController.InitiateRequest(
                seededA.planId(), seededA.applicationId(), "LEVER",
                "https://jobs.lever.co/org/lever-idor-1", "LEVER_CAPTCHA_PRESENT", null, 15
        );

        MvcResult initResult = mockMvc.perform(post("/api/v1/handoff/initiate")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(initiateReq)))
                .andExpect(status().isCreated())
                .andReturn();

        HandoffSessionRecord sessionA = json.readValue(initResult.getResponse().getContentAsString(), HandoffSessionRecord.class);

        // Candidate B tries to read Candidate A's active handoff -> 404
        mockMvc.perform(get("/api/v1/handoff/applications/{id}", seededA.applicationId())
                        .session(sessionB))
                .andExpect(status().isNotFound());

        // Candidate B tries to complete Candidate A's session -> 403 Forbidden
        mockMvc.perform(post("/api/v1/handoff/{id}/complete", sessionA.id())
                        .session(sessionB).with(csrf()))
                .andExpect(status().isForbidden());

        // Candidate B tries to cancel Candidate A's session -> 403 Forbidden
        mockMvc.perform(post("/api/v1/handoff/{id}/cancel", sessionA.id())
                        .session(sessionB).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(7)
    @DisplayName("Worker crash recovery: session preserved in PostgreSQL and recoverable")
    void testCrashRecoveryDuringHandoff() throws Exception {
        SeededApplication seeded = seedPlan(PROFILE_A, "WORKDAY", "workday-crash-1");

        var initiateReq = new HandoffController.InitiateRequest(
                seeded.planId(), seeded.applicationId(), "WORKDAY",
                "https://jobs.workday.co/org/workday-crash-1", "WORKDAY_MFA_REQUIRED", null, 15
        );

        MvcResult initResult = mockMvc.perform(post("/api/v1/handoff/initiate")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(initiateReq)))
                .andExpect(status().isCreated())
                .andReturn();

        HandoffSessionRecord session = json.readValue(initResult.getResponse().getContentAsString(), HandoffSessionRecord.class);

        // Simulate worker process exit (nothing active)
        // Verify state is fully preserved in Postgres:
        var persisted = handoffRepository.findById(PROFILE_A, session.id());
        assertThat(persisted).isPresent();
        assertThat(persisted.get().status()).isEqualTo(HandoffStatus.PENDING);

        // Candidate can still resolve handoff successfully:
        mockMvc.perform(post("/api/v1/handoff/{id}/complete", session.id())
                        .session(sessionA).with(csrf()))
                .andExpect(status().isOk());

        assertThat(planRepository.findById(seeded.planId()).get().status()).isEqualTo("READY_TO_SUBMIT");
    }
}
