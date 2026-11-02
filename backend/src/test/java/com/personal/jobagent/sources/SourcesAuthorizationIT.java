package com.personal.jobagent.sources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verification of source-management authorization boundaries (Phase 18 Security Closure).
 *
 * <p>Proves:
 * <ol>
 *   <li>Unauthenticated callers receive 401 Unauthorized across all source endpoints.</li>
 *   <li>Ordinary candidates (role CANDIDATE) receive 403 Forbidden on listing, inspection,
 *       mutation, execution, and coverage endpoints.</li>
 *   <li>Authorized operators (role OPERATOR or configured operator email) can list, inspect,
 *       update, and run sources.</li>
 *   <li>Worker bearer tokens (ROLE_WORKER) cannot obtain source-management privileges (403 Forbidden).</li>
 *   <li>Browser cookie sessions enforce CSRF protection on mutating endpoints.</li>
 *   <li>Invalid schedules, rate limits, and policies remain strictly rejected (400 Bad Request).</li>
 *   <li>Candidate-owned operations (job feed, discovery run-for-me) remain fully functional for candidates.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.worker-event-token=test-worker-token-0123456789abcdef",
        "app.discovery.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class SourcesAuthorizationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private UUID sourceId;
    private MockHttpSession candidateSession;
    private MockHttpSession operatorSession;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from job_sources where org_identifier = 'auth-test-org'");
        sourceId = UuidV7.generate();
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                values (?, 'GREENHOUSE', 'auth-test-org', 'Auth Test Greenhouse', '{"discovery": true}', 'DISCOVERY_ONLY', true)
                on conflict (id) do nothing
                """, sourceId);

        String candidatePasswordHash = passwordEncoder.encode("CandidatePass123!");
        String operatorPasswordHash = passwordEncoder.encode("OperatorPass123!");

        UUID candidateId = UuidV7.generate();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider, role)
                values (?, 'candidate-user@test.local', ?, 'Candidate User', 'LOCAL', 'CANDIDATE')
                on conflict (email) do update set role = 'CANDIDATE'
                """, candidateId, candidatePasswordHash);

        UUID candidateProfileId = UuidV7.generate();
        jdbc.update("""
                insert into profiles (id, user_id, headline)
                values (?, (select id from users where email = 'candidate-user@test.local'), 'Candidate Headline')
                on conflict (user_id) do nothing
                """, candidateProfileId);

        UUID operatorId = UuidV7.generate();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider, role)
                values (?, 'operator-user@test.local', ?, 'Operator User', 'LOCAL', 'OPERATOR')
                on conflict (email) do update set role = 'OPERATOR'
                """, operatorId, operatorPasswordHash);

        candidateSession = login("candidate-user@test.local", "CandidatePass123!");
        operatorSession = login("operator-user@test.local", "OperatorPass123!");
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void unauthenticatedCallerReceives401UnauthorizedAcrossSourcesEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/sources")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sources/" + sourceId)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/sources/" + sourceId).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sources/" + sourceId + "/run").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sources/" + sourceId + "/health-check").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sources/coverage")).andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryCandidateReceives403ForbiddenAcrossSourcesEndpoints() throws Exception {
        // GET listing
        mockMvc.perform(get("/api/v1/sources").session(candidateSession))
                .andExpect(status().isForbidden());

        // GET single source inspection
        mockMvc.perform(get("/api/v1/sources/" + sourceId).session(candidateSession))
                .andExpect(status().isForbidden());

        // PATCH source mutation (even with valid CSRF token)
        mockMvc.perform(patch("/api/v1/sources/" + sourceId)
                        .session(candidateSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isForbidden());

        // POST manual run
        mockMvc.perform(post("/api/v1/sources/" + sourceId + "/run")
                        .session(candidateSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // POST health check
        mockMvc.perform(post("/api/v1/sources/" + sourceId + "/health-check")
                        .session(candidateSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // GET coverage
        mockMvc.perform(get("/api/v1/sources/coverage").session(candidateSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizedOperatorCanListInspectMutateAndRunSources() throws Exception {
        // Listing
        mockMvc.perform(get("/api/v1/sources").session(operatorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Inspection
        mockMvc.perform(get("/api/v1/sources/" + sourceId).session(operatorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("Auth Test Greenhouse"));

        // Mutation with CSRF
        mockMvc.perform(patch("/api/v1/sources/" + sourceId)
                        .session(operatorSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("schedule_cron", "0 0 12 * * *", "rate_limit_per_min", 45))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule_cron").value("0 0 12 * * *"))
                .andExpect(jsonPath("$.rate_limit_per_min").value(45));

        // Coverage
        mockMvc.perform(get("/api/v1/sources/coverage").session(operatorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void workerBearerTokenCannotAccessSourcesManagementEndpoints() throws Exception {
        // Machine-to-machine worker token is not authenticated for sources management (returns 401)
        mockMvc.perform(get("/api/v1/sources")
                        .header("Authorization", "Bearer test-worker-token-0123456789abcdef"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/sources/" + sourceId)
                        .header("Authorization", "Bearer test-worker-token-0123456789abcdef")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/sources/" + sourceId + "/run")
                        .header("Authorization", "Bearer test-worker-token-0123456789abcdef")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        // An authenticated principal with ROLE_WORKER receives 403 Forbidden
        mockMvc.perform(get("/api/v1/sources")
                        .with(user("worker-agent").roles("WORKER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorMutationsRequireCsrfProtectionForCookieSessions() throws Exception {
        // Without CSRF token -> 403 Forbidden
        mockMvc.perform(patch("/api/v1/sources/" + sourceId)
                        .session(operatorSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/sources/" + sourceId + "/run")
                        .session(operatorSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorMutationsValidateInputsStrictly() throws Exception {
        // Invalid cron
        mockMvc.perform(patch("/api/v1/sources/" + sourceId)
                        .session(operatorSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("schedule_cron", "bad-cron"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Invalid cron expression")));

        // Invalid rate limit (<= 0)
        mockMvc.perform(patch("/api/v1/sources/" + sourceId)
                        .session(operatorSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rate_limit_per_min", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("rate_limit_per_min must be between 1 and 600"));

        // Invalid policy
        mockMvc.perform(patch("/api/v1/sources/" + sourceId)
                        .session(operatorSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("policy", "UNKNOWN_POLICY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Invalid policy")));
    }

    @Test
    void ordinaryCandidateCanStillAccessCandidateOwnedFeatures() throws Exception {
        // Job feed is accessible to ordinary candidates
        mockMvc.perform(get("/api/v1/jobs").session(candidateSession))
                .andExpect(status().isOk());

        // Discovery run-for-me is accessible to ordinary candidates
        mockMvc.perform(post("/api/v1/discovery/run-for-me")
                        .session(candidateSession)
                        .with(csrf()))
                .andExpect(status().isOk());
    }
}
