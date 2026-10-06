package com.personal.jobagent.application;

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
 * Phase 6: the human review queue over NEEDS_REVIEW decisions.
 *
 * <p>Proves: owner-scoped listing, approve → application created through the
 * idempotent pipeline, idempotent re-approve, reject keeps the gate closed,
 * pause/resume, lazy expiry, and cross-user isolation.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewQueueIT {

    private static final UUID USER_A = UUID.fromString("00000000-0000-7000-8000-00000000ee10");
    private static final UUID USER_B = UUID.fromString("00000000-0000-7000-8000-00000000ee20");
    private static final UUID PROFILE_A = UUID.fromString("00000000-0000-7000-8000-00000000ee11");
    private static final UUID PROFILE_B = UUID.fromString("00000000-0000-7000-8000-00000000ee21");
    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000ee40");

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

    private final Map<String, MockHttpSession> sessions = new ConcurrentHashMap<>();

    private MockHttpSession login(String email, String password) throws Exception {
        return sessions.computeIfAbsent(email, ignored -> {
            try {
                MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
                return (MockHttpSession) login.getRequest().getSession(false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void seedUser(UUID userId, UUID profileId, String email, String password) {
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, ?, ?, 'LOCAL') on conflict (email) do nothing
                """, userId, email, passwordEncoder.encode(password), "Review IT");
        jdbc.update("insert into profiles (id, user_id) values (?, ?) on conflict do nothing", profileId, userId);
        // Both users run CONTROLLED_AUTO so only the decision column separates
        // the flows under test.
        jdbc.update("""
                insert into preference_sets (id, profile_id, application_mode, scoring_weights, salary_min_gbp)
                values (?, ?, 'CONTROLLED_AUTO', '{}', 40000) on conflict do nothing
                """, UUID.randomUUID(), profileId);
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'review-org', 'Review Source', '{}', 'DISCOVERY_ONLY')
                on conflict do nothing
                """, SOURCE);
    }

    /** Seeds one job + one NEEDS_REVIEW decision owned by the profile. */
    private record DecisionSeed(UUID decisionId, UUID jobId) {}

    private DecisionSeed seedDecision(UUID profileId, String title, String company, String decision) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, remote_type, description_text, skills_extracted,
                                  application_url, canonical_url, content_hash, status)
                values (?, ?, ?, ?, ?, ?, 'London', 'HYBRID', 'A role.', array['Java'],
                        'https://boards.greenhouse.io/' || ?, 'https://boards.greenhouse.io/' || ?,
                        ?, 'SCORED')
                """, jobId, SOURCE, "review-" + title + "-" + company, title + "-" + company,
                company, title, company.toLowerCase(), "https://boards.greenhouse.io/" + company.toLowerCase(),
                "hash-" + title + company);
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
                insert into application_decisions (id, profile_id, job_id, match_score, recommendation,
                                                   application_mode, decision, reason, created_at)
                values (?, ?, ?, 78, 'APPLY', 'ASSISTED', ?, 'Score 78 in review range', now())
                """, decisionId, profileId, jobId, decision);
        return new DecisionSeed(decisionId, jobId);
    }

    @Test
    @Order(1)
    void seedUsers() {
        seedUser(USER_A, PROFILE_A, "review-a@example.com", "PasswordA1!");
        seedUser(USER_B, PROFILE_B, "review-b@example.com", "PasswordB1!");
    }

    @Test
    @Order(2)
    void listingIsOwnerScopedAndShowsOnlyOpenItems() throws Exception {
        var own = seedDecision(PROFILE_A, "Backend Engineer", "AcmeA", "NEEDS_REVIEW");
        seedDecision(PROFILE_B, "Platform Engineer", "AcmeB", "NEEDS_REVIEW");

        MvcResult result = mockMvc.perform(get("/api/v1/review-queue")
                        .session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].decisionId").value(own.decisionId().toString()))
                .andExpect(jsonPath("$.items[0].jobTitle").value("Backend Engineer"))
                .andExpect(jsonPath("$.items[0].companyName").value("AcmeA"))
                .andExpect(jsonPath("$.items[0].matchScore").value(78))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Platform Engineer");
        assertThat(body).doesNotContain("Platform Engineer");

        // Detail is also owner-scoped:
        mockMvc.perform(get("/api/v1/review-queue/{id}", own.decisionId())
                        .session(login("review-b@example.com", "PasswordB1!")))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(3)
    void approveCreatesTheApplicationIdempotently() throws Exception {
        var seed = seedDecision(PROFILE_A, "Data Engineer", "AcmeC", "NEEDS_REVIEW");
        UUID decisionId = seed.decisionId();
        UUID jobId = seed.jobId();

        mockMvc.perform(post("/api/v1/review-queue/{id}/approve", decisionId)
                        .with(csrf()).session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.status").value("READY_TO_APPLY"));

        // Decision recorded as APPROVED and linked:
        mockMvc.perform(get("/api/v1/review-queue/{id}", decisionId)
                        .session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.application_id").isNotEmpty());

        // Idempotent replay: same application, created=false, no duplicate row.
        mockMvc.perform(post("/api/v1/review-queue/{id}/approve", decisionId)
                        .with(csrf()).session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
        Integer applications = jdbc.queryForObject(
                "select count(*) from applications where profile_id = ? and job_id = ?",
                Integer.class, PROFILE_A, jobId);
        assertThat(applications).isEqualTo(1);
    }

    @Test
    @Order(4)
    void approveAfterRejectionIsRefusedAndNothingIsCreated() throws Exception {
        var seed = seedDecision(PROFILE_A, "Security Engineer", "AcmeD", "NEEDS_REVIEW");
        UUID decisionId = seed.decisionId();
        UUID jobId = seed.jobId();
        mockMvc.perform(post("/api/v1/review-queue/{id}/reject", decisionId)
                        .with(csrf()).session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/review-queue/{id}/approve", decisionId)
                        .with(csrf()).session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isConflict());

        Integer applications = jdbc.queryForObject(
                "select count(*) from applications where profile_id = ? and job_id = ?",
                Integer.class, PROFILE_A, jobId);
        assertThat(applications).isEqualTo(0);
    }

    @Test
    @Order(5)
    void pauseHidesFromQueueAndResumeReturnsIt() throws Exception {
        var seed = seedDecision(PROFILE_A, "QA Engineer", "AcmeE", "NEEDS_REVIEW");
        UUID decisionId = seed.decisionId();

        mockMvc.perform(post("/api/v1/review-queue/{id}/pause", decisionId)
                        .with(csrf()).session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isNoContent());

        MvcResult paused = mockMvc.perform(get("/api/v1/review-queue")
                        .session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(paused.getResponse().getContentAsString()).doesNotContain("QA Engineer");

        mockMvc.perform(post("/api/v1/review-queue/{id}/resume", decisionId)
                        .with(csrf()).session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isNoContent());

        MvcResult resumed = mockMvc.perform(get("/api/v1/review-queue")
                        .session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(resumed.getResponse().getContentAsString()).contains("QA Engineer");
    }

    @Test
    @Order(6)
    void staleNeedsReviewItemsAreLazilyExpired() throws Exception {
        var seed = seedDecision(PROFILE_A, "Old Role", "AcmeF", "NEEDS_REVIEW");
        UUID decisionId = seed.decisionId();
        jdbc.update("update application_decisions set created_at = now() - interval '30 days' where id = ?",
                decisionId);

        MvcResult result = mockMvc.perform(get("/api/v1/review-queue")
                        .session(login("review-a@example.com", "PasswordA1!")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Old Role");

        String decision = jdbc.queryForObject(
                "select decision from application_decisions where id = ?", String.class, decisionId);
        assertThat(decision).isEqualTo("EXPIRED");
    }

    @Test
    @Order(7)
    void crossUserApprovalIsRefused() throws Exception {
        var seed = seedDecision(PROFILE_A, "Cross User", "AcmeG", "NEEDS_REVIEW");
        UUID decisionId = seed.decisionId();

        // User B does not own user A's decision: 409/404 semantics without
        // any state change (the guarded transition can never fire).
        mockMvc.perform(post("/api/v1/review-queue/{id}/approve", decisionId)
                        .with(csrf()).session(login("review-b@example.com", "PasswordB1!")))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(404, 409));
        String decision = jdbc.queryForObject(
                "select decision from application_decisions where id = ?", String.class, decisionId);
        assertThat(decision).isEqualTo("NEEDS_REVIEW");
    }
}
