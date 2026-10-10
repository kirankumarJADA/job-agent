package com.personal.jobagent.jobs;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.profile.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.jobagent.llm.ModelRouter;

/**
 * Decision transparency: the recommendation thresholds are unchanged and the
 * scoring is untouched — what this locks in is that every decision persists
 * an honest, deterministic explanation of WHY, including neutral and missing
 * inputs, and that replays stay idempotent.
 */
class JobMatchServiceTest {

    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private JobMatchService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // Use a real SemanticSkillMatcher with mock deps — exact+synonym matching
        // works; LLM path fails gracefully (mock ModelRouter not healthy).
        JdbcTemplate cacheDb = mock(JdbcTemplate.class);
        ModelRouter router = mock(ModelRouter.class);
        SemanticSkillMatcher semanticMatcher = new SemanticSkillMatcher(cacheDb, router);
        service = new JobMatchService(jobRepository, semanticMatcher, profileRepository, jdbc, notifications, tm, new AutomationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    private JobRecord job(List<String> skills, Integer salaryMax) {
        return new JobRecord(JOB, UUID.randomUUID(), "ext-1", null, "Acme", "Engineer",
                "London", null, null, null, null, null,
                salaryMax == null ? null : BigDecimal.valueOf(salaryMax), salaryMax == null ? null : BigDecimal.valueOf(salaryMax),
                "GBP", "A role.", skills, "https://jobs.example.org/1", "https://jobs.example.org/1",
                Instant.now(), "DISCOVERED");
    }

    private void profileSkills(String... names) {
        when(profileRepository.findSkills(PROFILE)).thenReturn(java.util.Arrays.stream(names)
                .map(n -> new com.personal.jobagent.profile.SkillRecord(UUID.randomUUID(), PROFILE, n, "Test", 4, null, null))
                .toList());
    }

    @Test
    void highMatchExplainsWhyAndDefersToTheDecisionRulesWithoutClaimingAnApplication() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java", "Spring"), 60000)));
        profileSkills("Java", "Spring");

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, 50000, null);

        assertThat(result.recommendation()).isEqualTo("APPLY");
        assertThat(result.overall()).isEqualTo(90);
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), eq(90),
                eq("APPLY"), breakdown.capture());
        assertThat(breakdown.getValue())
                .contains("\"matched_skills\":[\"Java\",\"Spring\"]")
                .contains("Robin semantically matched 2 of the 2 skills")
                .contains("The offered salary meets your stated minimum.")
                // Since Phase 5 the decision engine (mode + approval rules) decides
                // what happens next; the match record must not claim an
                // application exists or was submitted.
                .contains("passed to your application decision rules")
                .contains("A match alone never submits an application.")
                .doesNotContain("was created automatically");
        verify(notifications).emit(any(NotificationService.NotificationCommand.class));
    }

    @Test
    void reviewMatchIsExplainedAsAHumanDecision() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java", "Spring"), 60000)));
        profileSkills("Java");

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, 50000, null);

        assertThat(result.recommendation()).isEqualTo("REVIEW");
        assertThat(result.overall()).isEqualTo(60);
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), eq(60),
                eq("REVIEW"), breakdown.capture());
        assertThat(breakdown.getValue())
                .contains("Robin semantically matched 1 of the 2 skills")
                .contains("below the APPLY threshold of 70")
                .contains("you decide from here");
        verify(notifications, never()).emit(any(NotificationService.NotificationCommand.class));
    }

    @Test
    void aRejectionExplainsThatNothingWasCreated() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Go", "Rust"), 60000)));
        profileSkills("Java", "Spring");

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, 50000, null);

        assertThat(result.recommendation()).isEqualTo("SKIP");
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), eq(30),
                eq("SKIP"), breakdown.capture());
        assertThat(breakdown.getValue())
                .contains("None of the 2 skills")
                .contains("no application was created");
        verify(notifications, never()).emit(any(NotificationService.NotificationCommand.class));
    }

    @Test
    void missingCandidateInformationIsExplainedHonestly() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java", "Spring"), null)));
        profileSkills(); // profile with no skills at all

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, null, null);

        assertThat(result.recommendation()).isEqualTo("SKIP");
        assertThat(result.overall()).isEqualTo(20);
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), eq(20),
                eq("SKIP"), breakdown.capture());
        assertThat(breakdown.getValue())
                .contains("Your profile lists no skills yet")
                .contains("No salary minimum is set — salary fit was scored neutral")
                .contains("No remote preference is set");
    }

    @Test
    void aJobWithoutDeclaredSkillsIsScoredNeutralNotZero() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of(), null)));
        profileSkills("Java");

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, 50000, "REMOTE");

        assertThat(result.skillOverlap()).isEqualTo(0.5);
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), any(Object.class),
                any(String.class), breakdown.capture());
        assertThat(breakdown.getValue()).contains("The job lists no skills");
                // and the honest branch for a salary the job never declares:
                
    }

    @Test
    void repeatedIngestionUpsertsTheSameDecisionWithoutSideEffects() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java", "Spring"), 60000)));
        profileSkills("Java", "Spring");

        service.evaluateMatch(JOB, PROFILE, 50000, null);
        service.evaluateMatch(JOB, PROFILE, 50000, null);

        // Both evaluations target the same (profile, job) row via the
        // on-conflict upsert — never a second row, never a second application.
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(contains("on conflict (profile_id, job_id)"), eq(PROFILE), eq(JOB),
                        eq(90), eq("APPLY"), any(String.class));
    }
}
