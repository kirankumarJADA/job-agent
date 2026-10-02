package com.personal.jobagent.application;

import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.llm.LlmCompletion;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.llm.RoutingTrace;
import com.personal.jobagent.llm.TaskType;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the production wiring that earlier phases only exercised through
 * tests: a job arriving through the REAL discovery ingest path
 * ({@code JobDiscoveryService.ingestJob}) reaches the pipeline via the
 * {@code job.discovered} outbox event — matching, application creation,
 * preparation and the automation plan all fire — a re-ingestion of the same
 * job creates no duplicate application, and an owner-requested re-preparation
 * does NOT duplicate any artifact.
 */
@SpringBootTest(properties = "spring.flyway.placeholders.remove_seed_dev_account=false")
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiscoveryToApplicationIT {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-00000000bb10");
    private static final UUID PROFILE = UUID.fromString("00000000-0000-7000-8000-00000000bb11");
    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000bb30");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean ModelRouter modelRouter;

    @Autowired private JobDiscoveryService discovery;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockHttpSession session;

    private JobDiscoveryService.IngestJobCommand command() {
        return new JobDiscoveryService.IngestJobCommand(
                SOURCE, "it-1", null, "Pipeline Corp", "Platform Engineer", "London",
                "London", "GB", "HYBRID", "FULL_TIME", "MID", 70000, 90000, "GBP",
                "Build Java platform services with Spring Boot.",
                List.of("Java", "Spring"),
                "https://jobs.example.org/platform-engineer",
                "https://jobs.example.org/platform-engineer");
    }

    @BeforeEach
    void seedAndLogin() throws Exception {
        org.mockito.Mockito.reset(modelRouter);
        when(modelRouter.execute(eq(TaskType.COVER_LETTER), any(), any(Duration.class)))
                .thenAnswer(inv -> new ModelRouter.ExecutionResult(
                        new LlmCompletion("Deterministic test completion — verified experience only.",
                                new LlmCompletion.TokenUsage(10, 20), 5, LlmCompletion.FinishReason.STOP),
                        new RoutingTrace(TaskType.COVER_LETTER, "mock", "test", List.of())));
        when(modelRouter.execute(eq(TaskType.APPLICATION_QA), any(), any(Duration.class)))
                .thenAnswer(inv -> new ModelRouter.ExecutionResult(
                        new LlmCompletion("Verified answer.",
                                new LlmCompletion.TokenUsage(10, 20), 5, LlmCompletion.FinishReason.STOP),
                        new RoutingTrace(TaskType.APPLICATION_QA, "mock", "test", List.of())));

        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, ?, ?, 'LOCAL') on conflict (email) do nothing
                """, USER, "pipeline-wiring@example.com", passwordEncoder.encode("PasswordA1!"), "Wiring IT");
        jdbc.update("insert into profiles (id, user_id) values (?, ?) on conflict do nothing", PROFILE, USER);
        jdbc.update("""
                insert into skills (id, profile_id, name, category, mastery)
                values (?, ?, 'Java', 'Test', 4) on conflict do nothing
                """, UUID.randomUUID(), PROFILE);
        jdbc.update("""
                insert into skills (id, profile_id, name, category, mastery)
                values (?, ?, 'Spring', 'Test', 4) on conflict do nothing
                """, UUID.randomUUID(), PROFILE);
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'wiring-org', 'Wiring Source', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE);

        if (session == null) {
            MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"pipeline-wiring@example.com\",\"password\":\"PasswordA1!\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            session = (MockHttpSession) login.getRequest().getSession(false);
        }
    }

    @Test
    @Order(1)
    void ingestedJobFlowsThroughMatchingApplicationAndPreparation() {
        JobDiscoveryService.IngestResult result = discovery.ingestJob(command());
        UUID jobId = result.jobId();

        // Everything downstream of ingestion now happens through the outbox:
        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            Integer applications = jdbc.queryForObject(
                    "select count(*) from applications where profile_id = ? and job_id = ?",
                    Integer.class, PROFILE, jobId);
            assertThat(applications).isEqualTo(1);
            Integer plans = jdbc.queryForObject(
                    "select count(*) from automation_plans p join applications a on a.id = p.application_id "
                            + "where a.profile_id = ? and a.job_id = ?",
                    Integer.class, PROFILE, jobId);
            assertThat(plans).isEqualTo(1);
            Integer cover = jdbc.queryForObject(
                    "select count(*) from cover_letters where profile_id = ? and job_id = ?",
                    Integer.class, PROFILE, jobId);
            assertThat(cover).isEqualTo(1);
        });
    }

    @Test
    @Order(2)
    void reIngestingTheSameJobCreatesNoDuplicateApplication() {
        JobDiscoveryService.IngestResult replay = discovery.ingestJob(command());

        assertThat(replay.action()).isEqualTo("TOUCHED");
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer published = jdbc.queryForObject("""
                    select count(*) from outbox_events
                    where event_type = 'job.discovered' and payload->>'action' = 'TOUCHED'
                      and published_at is not null
                    """, Integer.class);
            assertThat(published).isGreaterThanOrEqualTo(1);
        });
        Integer applications = jdbc.queryForObject(
                "select count(*) from applications where profile_id = ?", Integer.class, PROFILE);
        assertThat(applications).isEqualTo(1);
    }

    @Test
    @Order(3)
    void reprepareDoesNotDuplicateArtifacts() throws Exception {
        Map<String, Object> application = jdbc.queryForMap(
                "select id, cv_version_id from applications where profile_id = ? limit 1", PROFILE);
        UUID applicationId = (UUID) application.get("id");

        mockMvc.perform(post("/api/v1/applications/{id}/re-prepare", applicationId).with(csrf()).session(session))
                .andExpect(status().isAccepted());

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer published = jdbc.queryForObject("""
                    select count(*) from outbox_events
                    where event_type = 'application.repreparation_requested'
                      and payload->>'application_id' = ? and published_at is not null
                    """, Integer.class, applicationId.toString());
            assertThat(published).isGreaterThanOrEqualTo(1);
        });
        // The already-succeeded steps were skipped, not re-run:
        Integer covers = jdbc.queryForObject(
                "select count(*) from cover_letters where profile_id = ?", Integer.class, PROFILE);
        assertThat(covers).isEqualTo(1);
        Object cvVersion = jdbc.queryForObject(
                "select cv_version_id from applications where id = ?", UUID.class, applicationId);
        assertThat(cvVersion).isEqualTo(application.get("cv_version_id"));
    }
}
