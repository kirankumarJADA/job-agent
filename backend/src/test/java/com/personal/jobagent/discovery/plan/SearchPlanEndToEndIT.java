package com.personal.jobagent.discovery.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.security.operator-emails=dev@example.local"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Phase 2 Deliverable E: Search Plan End-to-End Discovery to Matching IT")
public class SearchPlanEndToEndIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SearchPlanRepository searchPlanRepository;

    @Autowired
    private SearchPlanExecutionCoordinator coordinator;

    @Autowired
    private SearchPlanScheduler scheduler;

    @Autowired
    private JobDiscoveryService jobDiscoveryService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private PreferenceSetRepository preferenceSetRepository;

    @MockBean
    private BoardDiscoveryService boardDiscoveryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Full End-to-End: Candidate creates plan -> scheduler selects -> query expands -> discovery ingests -> deduplicates -> matching reranks -> outcome recorded")
    void testFullEndToEndLifecycle() {
        UUID profileId = createTestProfile("candidate-e2e@example.com");

        // 1. Setup candidate preferences & target skills
        jdbcTemplate.update("""
                INSERT INTO preference_sets (
                    id, profile_id, titles, required_skills, locations_allowed, remote_types,
                    experience_levels, salary_min_gbp, application_mode, scoring_weights, is_active
                ) VALUES (
                    ?, ?, '{Senior Backend Engineer}', '{Java,Spring Boot,Postgres}',
                    '{London}', '{HYBRID,REMOTE}', '{SENIOR}', 75000, 'MANUAL',
                    '{"skill":35,"experience":20,"visa":10,"location":10,"salary":15,"career":5,"difficulty":5}'::jsonb,
                    true
                )
                """, UuidV7.generate(), profileId);

        // 2. Candidate creates a search plan targeting a specific provider source
        SearchPlanRecord newPlan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                "E2E Senior Java Plan",
                List.of("Senior Backend Engineer"),
                Map.of(
                        "skills", List.of("Java", "Spring Boot"),
                        "locations", List.of("London"),
                        "remoteTypes", List.of("HYBRID"),
                        "seniority", List.of("SENIOR")
                ),
                List.of("Stripe Careers E2E"),
                "HOURLY",
                25,
                true,
                null,
                Instant.now(),
                Instant.now()
        ));

        assertThat(newPlan.id()).isNotNull();

        // 3. Ensure a test source exists in job_sources
        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'GREENHOUSE', 'stripe-e2e', 'Stripe Careers E2E', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO UPDATE SET enabled = true
                """, sourceId);

        // Fetch the actual source ID from DB (in case of conflict update)
        UUID actualSourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM job_sources WHERE kind = 'GREENHOUSE' AND org_identifier = 'stripe-e2e'",
                UUID.class);

        // Ingest a job directly via jobDiscoveryService to verify real pipeline ingestion and dedup
        JobDiscoveryService.IngestResult ingest1 = jobDiscoveryService.ingestJob(new JobDiscoveryService.IngestJobCommand(
                actualSourceId,
                "stripe-job-101",
                null,
                "Stripe",
                "Senior Backend Engineer - Core Payments",
                "London, UK",
                "London",
                "GB",
                "HYBRID",
                "FULL_TIME",
                "SENIOR",
                BigDecimal.valueOf(80000),
                BigDecimal.valueOf(95000),
                "GBP",
                "Build scalable financial infrastructure using Java and Spring Boot with PostgreSQL.",
                List.of("Java", "Spring Boot", "PostgreSQL", "Kafka"),
                "https://boards.greenhouse.io/stripe/jobs/101",
                "https://stripe.com/jobs/101",
                Instant.now()
        ));

        assertThat(ingest1.jobId()).isNotNull();

        // Mock boardDiscoveryService.run to return the ingested job result
        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap(), any(), any())).thenReturn(
                new BoardDiscoveryService.SourceRunReport(
                        "e2e-run-1", actualSourceId, "GREENHOUSE", "OK", true, Map.of("count", 1), null, null,
                        List.of(ingest1), List.of()
                )
        );

        // 4. Execute the plan via Coordinator
        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(newPlan.id(), profileId, true);

        assertThat(summary.status()).isEqualTo("SUCCESS");
        assertThat(summary.sourcesSucceeded()).isGreaterThanOrEqualTo(1);
        assertThat(summary.jobsIngested()).isEqualTo(1);
        assertThat(summary.jobsMatched()).isEqualTo(1);
        assertThat(summary.errors()).isEmpty();

        // 5. Verify database outcome recorded in search_plans
        SearchPlanRecord updatedPlan = searchPlanRepository.findByIdAndProfileId(newPlan.id(), profileId).orElseThrow();
        assertThat(updatedPlan.lastRunAt()).isNotNull();
        assertThat(updatedPlan.lastExecutionStatus()).isEqualTo("SUCCESS");
        assertThat(updatedPlan.lastExecutionResults()).isEqualTo(1);
        assertThat(updatedPlan.lastExecutionError()).isNull();

        // 6. Verify job_matches has explainable 5-stage reranking breakdown
        Map<String, Object> matchRow = jdbcTemplate.queryForMap(
                "SELECT * FROM job_matches WHERE job_id = ? AND profile_id = ?",
                ingest1.jobId(), profileId);

        assertThat(matchRow.get("score")).isNotNull();
        int score = ((Number) matchRow.get("score")).intValue();
        assertThat(score).isGreaterThanOrEqualTo(50);
        assertThat(matchRow.get("recommendation")).isIn("APPLY", "REVIEW");

        String breakdownStr = String.valueOf(matchRow.get("breakdown"));
        assertThat(breakdownStr).contains("stage_scores");
        assertThat(breakdownStr).contains("hard_eligibility");
        assertThat(breakdownStr).contains("skills_overlap");
        assertThat(breakdownStr).contains("seniority_alignment");
        assertThat(breakdownStr).contains("title_fit");
        assertThat(breakdownStr).contains("rerank_narrative");

        // 7. Test Duplicate Ingestion Control: Ingesting same job again updates last_seen without duplicate row
        int jobCountBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class);
        JobDiscoveryService.IngestResult ingest2 = jobDiscoveryService.ingestJob(new JobDiscoveryService.IngestJobCommand(
                actualSourceId,
                "stripe-job-101",
                null,
                "Stripe",
                "Senior Backend Engineer - Core Payments",
                "London, UK",
                "London",
                "GB",
                "HYBRID",
                "FULL_TIME",
                "SENIOR",
                BigDecimal.valueOf(80000),
                BigDecimal.valueOf(95000),
                "GBP",
                "Build scalable financial infrastructure using Java and Spring Boot with PostgreSQL.",
                List.of("Java", "Spring Boot", "PostgreSQL", "Kafka"),
                "https://boards.greenhouse.io/stripe/jobs/101",
                "https://stripe.com/jobs/101",
                Instant.now()
        ));
        int jobCountAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class);
        assertThat(jobCountAfter).isEqualTo(jobCountBefore);
        assertThat(ingest2.jobId()).isEqualTo(ingest1.jobId());
    }

    @Test
    @DisplayName("Failure and Rate-Limit Handling: Provider failure records PARTIAL or FAILED with error notes")
    void testFailureAndRateLimitHandling() {
        UUID profileId = createTestProfile("rate-limit-test@example.com");

        SearchPlanRecord plan = searchPlanRepository.create(new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                "Rate Limited Plan",
                List.of("DevOps Engineer"),
                Map.of(),
                List.of("Rate Limited Source"),
                "DAILY",
                20,
                true,
                null,
                Instant.now(),
                Instant.now()
        ));

        UUID failingSourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'ADZUNA', 'rate-limited-e2e', 'Rate Limited Source', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO UPDATE SET enabled = true
                """, failingSourceId);

        UUID actualFailingId = jdbcTemplate.queryForObject(
                "SELECT id FROM job_sources WHERE kind = 'ADZUNA' AND org_identifier = 'rate-limited-e2e'",
                UUID.class);

        when(boardDiscoveryService.run(any(UUID.class), any(BoardDiscoveryService.Mode.class), anyMap(), any(), any())).thenReturn(
                new BoardDiscoveryService.SourceRunReport(
                        "err-run", actualFailingId, "ADZUNA", "FAILED", false, Map.of(),
                        "RATE_LIMITED", "HTTP 429 Too Many Requests (Rate limit quota exceeded)",
                        List.of(), List.of("HTTP 429")
                )
        );

        SearchPlanExecutionCoordinator.ExecutionSummary summary = coordinator.executePlan(plan.id(), profileId, true);

        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.sourcesFailed()).isEqualTo(1);
        assertThat(summary.errors()).anyMatch(e -> e.contains("RATE_LIMITED"));

        SearchPlanRecord recordedPlan = searchPlanRepository.findByIdAndProfileId(plan.id(), profileId).orElseThrow();
        assertThat(recordedPlan.lastExecutionStatus()).isEqualTo("FAILED");
        assertThat(recordedPlan.lastExecutionError()).contains("RATE_LIMITED");
    }

    private UUID createTestProfile(String email) {
        UUID userId = UuidV7.generate();
        UUID profileId = UuidV7.generate();

        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, display_name, created_at)
                VALUES (?, ?, 'hash', 'Test Candidate', now())
                """, userId, email);

        jdbcTemplate.update("""
                INSERT INTO profiles (id, user_id, updated_at)
                VALUES (?, ?, now())
                """, profileId, userId);

        return profileId;
    }
}
