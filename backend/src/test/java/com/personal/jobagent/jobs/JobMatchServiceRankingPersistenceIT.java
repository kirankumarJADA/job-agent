package com.personal.jobagent.jobs;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.profile.SkillRecord;
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
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.security.operator-emails=dev@example.local"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Phase 2A Hardened: JobMatchService Ranking Persistence IT")
public class JobMatchServiceRankingPersistenceIT {

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
    private JobDiscoveryService jobDiscoveryService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private PreferenceSetRepository preferenceSetRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @MockBean
    private NotificationService notificationService;

    @Test
    @DisplayName("Ineligible job: Disqualified by MultiStageReranker Stage 1 persists score 0, recommendation SKIP, and no notification")
    void testIneligibleJobPersistsZeroScoreAndSkip() {
        UUID profileId = createTestProfile("candidate-ineligible@example.com");

        // Candidate wants "Backend Engineer"
        PreferenceSetRecord prefs = new PreferenceSetRecord(
                UuidV7.generate(), profileId,
                List.of("Backend Engineer"), List.of(), List.of(),
                List.of("Java", "PostgreSQL"), List.of("London"), List.of("HYBRID"),
                List.of(), List.of("SENIOR"), 70000L, "NONE", List.of(), List.of(), "MANUAL",
                Map.of("skill", 35, "experience", 20, "visa", 10, "location", 10, "salary", 15, "career", 5, "difficulty", 5),
                Map.of(), true
        );

        // Candidate has 100% skill match
        jdbcTemplate.update("""
                INSERT INTO skills (id, profile_id, name, mastery)
                VALUES (?, ?, 'Java', 4),
                       (?, ?, 'PostgreSQL', 4)
                """, UuidV7.generate(), profileId, UuidV7.generate(), profileId);

        // Job has Java and PostgreSQL, but title is "Registered General Nurse" (violates title hard filter)
        UUID sourceId = getOrCreateSource();
        JobDiscoveryService.IngestResult ingest = jobDiscoveryService.ingestJob(new JobDiscoveryService.IngestJobCommand(
                sourceId, "nurse-job-99", null, "NHS Trust", "Registered General Nurse",
                "London", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                BigDecimal.valueOf(80000), BigDecimal.valueOf(90000), "GBP",
                "Nursing care requiring Java and PostgreSQL database administration.",
                List.of("Java", "PostgreSQL"),
                "https://example.com/apply/nurse-99", "https://example.com/jobs/nurse-99", Instant.now()
        ));

        // Evaluate match
        JobMatchService.MatchResult result = jobMatchService.evaluateMatch(ingest.jobId(), profileId, prefs);

        // VERIFICATION: Multi-Stage Reranker Hard Eligibility Gate strictly enforces SKIP and 0 score
        assertThat(result.overall()).isEqualTo(0);
        assertThat(result.recommendation()).isEqualTo("SKIP");
        assertThat(result.notified()).isFalse();

        // Database row verification
        Map<String, Object> matchRow = jdbcTemplate.queryForMap(
                "SELECT score, recommendation FROM job_matches WHERE job_id = ? AND profile_id = ?",
                ingest.jobId(), profileId);
        assertThat(((Number) matchRow.get("score")).intValue()).isEqualTo(0);
        assertThat(matchRow.get("recommendation")).isEqualTo("SKIP");

        // Verify NO notification emitted for disqualified job
        verify(notificationService, never()).emit(any());
    }

    @Test
    @DisplayName("Eligible job: Persists authoritative MultiStageReranker score, recommendation, and notification")
    void testEligibleJobPersistsAuthoritativeScoreAndNotifies() {
        UUID profileId = createTestProfile("candidate-eligible@example.com");

        PreferenceSetRecord prefs = new PreferenceSetRecord(
                UuidV7.generate(), profileId,
                List.of("Senior Backend Engineer"), List.of(), List.of(),
                List.of("Java", "Spring Boot"), List.of("London"), List.of("HYBRID"),
                List.of(), List.of("SENIOR"), 70000L, "NONE", List.of(), List.of(), "MANUAL",
                Map.of("skill", 35, "experience", 20, "visa", 10, "location", 10, "salary", 15, "career", 5, "difficulty", 5),
                Map.of(), true
        );

        jdbcTemplate.update("""
                INSERT INTO skills (id, profile_id, name, mastery)
                VALUES (?, ?, 'Java', 4),
                       (? ,?, 'Spring Boot', 4)
                """, UuidV7.generate(), profileId, UuidV7.generate(), profileId);

        UUID sourceId = getOrCreateSource();
        JobDiscoveryService.IngestResult ingest = jobDiscoveryService.ingestJob(new JobDiscoveryService.IngestJobCommand(
                sourceId, "stripe-dev-88", null, "Stripe", "Senior Backend Engineer",
                "London", "London", "GB", "HYBRID", "FULL_TIME", "SENIOR",
                BigDecimal.valueOf(85000), BigDecimal.valueOf(95000), "GBP",
                "Building payment backend with Java and Spring Boot.",
                List.of("Java", "Spring Boot"),
                "https://example.com/apply/88", "https://example.com/jobs/88", Instant.now()
        ));

        JobMatchService.MatchResult result = jobMatchService.evaluateMatch(ingest.jobId(), profileId, prefs);

        // Authoritative score verification (at or above APPLY threshold of 70)
        assertThat(result.overall()).isGreaterThanOrEqualTo(70);
        assertThat(result.recommendation()).isEqualTo("APPLY");
        assertThat(result.notified()).isTrue();

        Map<String, Object> matchRow = jdbcTemplate.queryForMap(
                "SELECT score, recommendation FROM job_matches WHERE job_id = ? AND profile_id = ?",
                ingest.jobId(), profileId);
        assertThat(((Number) matchRow.get("score")).intValue()).isEqualTo(result.overall());
        assertThat(matchRow.get("recommendation")).isEqualTo("APPLY");

        verify(notificationService).emit(argThat(cmd -> {
            if (cmd.payload() instanceof Map<?, ?> p) {
                return "APPLY".equals(p.get("recommendation"))
                        && ((Number) p.get("score")).intValue() == result.overall();
            }
            return false;
        }));
    }

    private UUID getOrCreateSource() {
        UUID sourceId = UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                VALUES (?, 'GREENHOUSE', 'stripe-test', 'Stripe Careers', '{}'::jsonb, 'DISCOVERY_ONLY', true)
                ON CONFLICT (kind, org_identifier) DO UPDATE SET enabled = true
                """, sourceId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM job_sources WHERE kind = 'GREENHOUSE' AND org_identifier = 'stripe-test'",
                UUID.class);
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
