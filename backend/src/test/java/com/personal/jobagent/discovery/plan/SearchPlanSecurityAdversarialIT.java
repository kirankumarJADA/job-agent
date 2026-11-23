package com.personal.jobagent.discovery.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.awaitility.Awaitility;
import java.time.Duration;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.security.operator-emails=dev@example.local"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Agent 4: Search Plan Security & Cross-Tenant Adversarial IT")
public class SearchPlanSecurityAdversarialIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SearchPlanRepository searchPlanRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Adversarial: SQL Injection payloads in search plan titles and facets are safely escaped")
    void testSqlInjectionResistance() {
        UUID profileId = createTestProfile("sqli-test@example.com");

        String sqlInjectionTitle = "Senior Engineer'; DROP TABLE search_plans CASCADE; --";
        String sqlInjectionFacetKey = "facet'; SELECT pg_sleep(5); --";

        SearchPlanRecord maliciousPlan = new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                "SQLi Resistance Test",
                List.of(sqlInjectionTitle),
                Map.of(sqlInjectionFacetKey, "injected_value' OR '1'='1"),
                List.of("ADZUNA"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        );

        SearchPlanRecord created = searchPlanRepository.create(maliciousPlan);
        assertThat(created).isNotNull();

        // Verify table still exists and data was safely parameterized
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM search_plans WHERE id = ?", Integer.class, created.id());
        assertThat(count).isEqualTo(1);

        SearchPlanRecord retrieved = searchPlanRepository.findByIdAndProfileId(created.id(), profileId).orElseThrow();
        assertThat(retrieved.targetTitles()).contains(sqlInjectionTitle);
        assertThat(retrieved.queryFacets()).containsKey(sqlInjectionFacetKey);
    }

    @Test
    @DisplayName("Cross-Tenant: Candidate B cannot access or modify Candidate A's search plan")
    void testCrossTenantIsolation() {
        UUID candidateA = createTestProfile("candidate-a@example.com");
        UUID candidateB = createTestProfile("candidate-b@example.com");

        SearchPlanRecord planA = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                candidateA,
                "Candidate A Private Search Plan",
                List.of("Staff Engineer"),
                Map.of("skills", List.of("Rust")),
                List.of("GREENHOUSE"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        ));

        // Direct repository verification
        assertThat(searchPlanRepository.findByIdAndProfileId(planA.id(), candidateA)).isPresent();
        assertThat(searchPlanRepository.findByIdAndProfileId(planA.id(), candidateB)).isEmpty();

        // Candidate B attempt to delete Candidate A's plan returns false
        boolean deletedByB = searchPlanRepository.delete(planA.id(), candidateB);
        assertThat(deletedByB).isFalse();

        // Candidate A's plan remains intact
        assertThat(searchPlanRepository.findByIdAndProfileId(planA.id(), candidateA)).isPresent();
    }

    @Test
    @DisplayName("Security: Unauthenticated requests receive 401 across search plan endpoints")
    void testUnauthenticatedRequestsReceive401() throws Exception {
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/search-plans"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/search-plans/" + randomId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/search-plans").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/search-plans/" + randomId).with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/search-plans/" + randomId + "/run").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Security: HTTP Cross-tenant IDOR returns 404 for unauthorized candidate")
    void testHttpCrossTenantIdorReturns404() throws Exception {
        String pass = "Password123!";
        UUID userA = createTestUser("http-user-a@example.com", pass);
        UUID profileA = createProfileForUser(userA);

        UUID userB = createTestUser("http-user-b@example.com", pass);
        UUID profileB = createProfileForUser(userB);

        MockHttpSession sessionA = login("http-user-a@example.com", pass);
        MockHttpSession sessionB = login("http-user-b@example.com", pass);

        SearchPlanRecord planA = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profileA,
                "Candidate A Private Search Plan",
                List.of("Principal Architect"),
                Map.of("skills", List.of("Java")),
                List.of("GREENHOUSE"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        ));

        // Candidate A can get the plan
        mockMvc.perform(get("/api/v1/search-plans/" + planA.id()).session(sessionA))
                .andExpect(status().isOk());

        // Candidate B attempt to GET Candidate A's plan returns 404
        mockMvc.perform(get("/api/v1/search-plans/" + planA.id()).session(sessionB))
                .andExpect(status().isNotFound());

        // Candidate B attempt to DELETE Candidate A's plan returns 404
        mockMvc.perform(delete("/api/v1/search-plans/" + planA.id()).session(sessionB).with(csrf()))
                .andExpect(status().isNotFound());

        // Candidate B attempt to run Candidate A's plan returns 404
        mockMvc.perform(post("/api/v1/search-plans/" + planA.id() + "/run").session(sessionB).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Security: CSRF is enforced on mutating search plan endpoints")
    void testCsrfProtectionEnforcedOnMutations() throws Exception {
        String pass = "Password123!";
        UUID user = createTestUser("csrf-test@example.com", pass);
        createProfileForUser(user);
        MockHttpSession session = login("csrf-test@example.com", pass);

        // POST without CSRF -> 403 Forbidden
        mockMvc.perform(post("/api/v1/search-plans")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isForbidden());

        // DELETE without CSRF -> 403 Forbidden
        mockMvc.perform(delete("/api/v1/search-plans/" + UUID.randomUUID())
                        .session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Public response DTO sanitizes internal lease tokens and expiration")
    void testPublicDtoSanitizesInternalLeaseFields() throws Exception {
        String pass = "Password123!";
        UUID user = createTestUser("dto-test@example.com", pass);
        UUID profile = createProfileForUser(user);
        MockHttpSession session = login("dto-test@example.com", pass);

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profile,
                "DTO Sanitization Plan",
                List.of("Software Engineer"),
                Map.of(),
                List.of("ADZUNA"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        ));

        // Claim lease directly to set execution_owner_token in database
        UUID token = UUID.randomUUID();
        searchPlanRepository.claimExecutionLease(plan.id(), token, 60000L);

        // Fetch via API
        mockMvc.perform(get("/api/v1/search-plans/" + plan.id()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(plan.id().toString()))
                .andExpect(jsonPath("$.name").value("DTO Sanitization Plan"))
                .andExpect(jsonPath("$.executionOwnerToken").doesNotExist())
                .andExpect(jsonPath("$.executionLeaseExpiresAt").doesNotExist());
    }

    @Test
    @DisplayName("Semantics: Async execution returns 202 ACCEPTED with QUEUED status, executionId, and statusUrl")
    void testAsyncExecutionReturnsQueuedWithExecutionIdAndStatusUrl() throws Exception {
        String email = "async-run@example.com";
        String pass = "Password123!";
        UUID user = createTestUser(email, pass);
        UUID profile = createProfileForUser(user);
        MockHttpSession session = login(email, pass);

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profile,
                "Async Queue Test Plan",
                List.of("Staff Engineer"),
                Map.of(),
                List.of("ADZUNA"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        ));

        mockMvc.perform(post("/api/v1/search-plans/" + plan.id() + "/run")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.executionId").isNotEmpty())
                .andExpect(jsonPath("$.statusUrl").value("/api/v1/search-plans/" + plan.id()))
                .andExpect(jsonPath("$.searchPlanId").value(plan.id().toString()))
                .andExpect(jsonPath("$.planName").value("Async Queue Test Plan"));
    }

    @Test
    @DisplayName("Audit Attribution: Asynchronous run preserves authenticated actor, IP, and request URI in audit logs")
    void testAsynchronousAuditAttributionPreservesActorAndMetadata() throws Exception {
        String email = "audit-actor@example.com";
        String pass = "Password123!";
        UUID user = createTestUser(email, pass);
        UUID profile = createProfileForUser(user);
        MockHttpSession session = login(email, pass);

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profile,
                "Audit Attribution Plan",
                List.of("Lead Architect"),
                Map.of(),
                List.of("ADZUNA"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        ));

        mockMvc.perform(post("/api/v1/search-plans/" + plan.id() + "/run")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isAccepted());

        // Verify SEARCH_PLAN_RUN_QUEUED entry was recorded with the authenticated user email and metadata
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT actor, action, entity_id, ip, after_state::text as after_state
                    FROM audit_logs
                    WHERE entity_type = 'SEARCH_PLAN'
                      AND entity_id = ?
                      AND action = 'SEARCH_PLAN_RUN_QUEUED'
                    """, plan.id());
            assertThat(rows).isNotEmpty();
            Map<String, Object> queueAudit = rows.get(0);
            assertThat(queueAudit.get("actor")).isEqualTo(email);
            assertThat(queueAudit.get("after_state").toString()).contains("/api/v1/search-plans/" + plan.id() + "/run");
            assertThat(queueAudit.get("after_state").toString()).contains("executionId");
        });
    }

    @Test
    @DisplayName("Concurrency: Database-backed preflight rejects duplicate run with 409 Conflict when lease is active")
    void testPreflightRejectsDuplicateExecutionWhenLeaseActive() throws Exception {
        String email = "conflict-test@example.com";
        String pass = "Password123!";
        UUID user = createTestUser(email, pass);
        UUID profile = createProfileForUser(user);
        MockHttpSession session = login(email, pass);

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profile,
                "Conflict Test Plan",
                List.of("Platform Engineer"),
                Map.of(),
                List.of("ADZUNA"),
                "DAILY",
                50,
                true,
                null,
                null,
                null
        ));

        // Claim lease directly in database
        UUID workerToken = UUID.randomUUID();
        boolean claimed = searchPlanRepository.claimExecutionLease(plan.id(), workerToken, 60000L);
        assertThat(claimed).isTrue();

        // Calling run must return 409 Conflict
        mockMvc.perform(post("/api/v1/search-plans/" + plan.id() + "/run")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Search plan is already executing with an active lease"));
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    private UUID createTestUser(String email, String rawPassword) {
        UUID userId = UuidV7.generate();
        String hash = passwordEncoder.encode(rawPassword);
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, display_name, auth_provider, role, created_at)
                VALUES (?, ?, ?, 'Test Candidate', 'LOCAL', 'CANDIDATE', now())
                ON CONFLICT (email) DO UPDATE SET password_hash = excluded.password_hash
                """, userId, email, hash);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private UUID createProfileForUser(UUID userId) {
        UUID profileId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO profiles (id, user_id, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (user_id) DO NOTHING
                """, profileId, userId);
        return jdbcTemplate.queryForObject("SELECT id FROM profiles WHERE user_id = ?", UUID.class, userId);
    }

    private UUID createTestProfile(String email) {
        UUID userId = createTestUser(email, "Password123!");
        return createProfileForUser(userId);
    }
}
