package com.personal.jobagent.jobs;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
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

    /**
     * A preference set with only a salary floor set — every other
     * candidate-side constraint (experience/visa/location/remote) stays
     * unset, so those categories are excluded from the weighted score
     * (see JobMatchService.CategoryScore) and only skill+salary (the two
     * categories evaluateMatch always treats as applicable) determine
     * `overall` below, under the documented default weights (skill 30,
     * salary 10 of a 40-point applicable total).
     */
    private static PreferenceSetRecord preferencesWithSalaryMin(Long salaryMinGbp) {
        return new PreferenceSetRecord(UUID.randomUUID(), PROFILE, null, null, null, null, null, null, null, null,
                salaryMinGbp, null, null, null, null, null, null, true);
    }

    @Test
    void highMatchExplainsWhyAndDefersToTheDecisionRulesWithoutClaimingAnApplication() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java", "Spring"), 60000)));
        profileSkills("Java", "Spring");

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, preferencesWithSalaryMin(50000L));

        assertThat(result.recommendation()).isEqualTo("APPLY");
        // Only skill (weight 30) and salary (weight 10) are applicable here
        // (no experience/visa/location preference set) — both score 1.0, so
        // the weighted average over just those two is 100.
        assertThat(result.overall()).isEqualTo(100);
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), eq(100),
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

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, preferencesWithSalaryMin(50000L));

        assertThat(result.recommendation()).isEqualTo("REVIEW");
        // skill=0.5 (1 of 2 matched, weight 30) + salary=1.0 (weight 10):
        // (30*0.5 + 10*1.0) / 40 * 100 = 62.5 -> 63.
        assertThat(result.overall()).isEqualTo(63);
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), eq(63),
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

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, preferencesWithSalaryMin(50000L));

        assertThat(result.recommendation()).isEqualTo("SKIP");
        // skill=0 (weight 30) + salary=1.0 (weight 10): (30*0 + 10*1.0) / 40 * 100 = 25.
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), eq(25),
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

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, null);

        assertThat(result.recommendation()).isEqualTo("SKIP");
        // skill=0 (no candidate skills at all, weight 30) + salary=0.5 (both
        // sides missing, neutral, weight 10): (30*0 + 10*0.5) / 40 * 100 = 12.5 -> 13.
        assertThat(result.overall()).isEqualTo(13);
        ArgumentCaptor<String> breakdown = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("job_matches"), eq(PROFILE), eq(JOB), eq(13),
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

        JobMatchService.MatchResult result = service.evaluateMatch(JOB, PROFILE, preferencesWithSalaryMin(50000L));

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

        service.evaluateMatch(JOB, PROFILE, preferencesWithSalaryMin(50000L));
        service.evaluateMatch(JOB, PROFILE, preferencesWithSalaryMin(50000L));

        // Both evaluations target the same (profile, job) row via the
        // on-conflict upsert — never a second row, never a second application.
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(contains("on conflict (profile_id, job_id)"), eq(PROFILE), eq(JOB),
                        eq(100), eq("APPLY"), any(String.class));
    }

    // ── Robin x Tsenta continuation: the 7-category scoringWeights contract ──

    private static JobRecord jobWithLevelAndLocation(String experienceLevel, String city, List<String> skills) {
        return new JobRecord(JOB, UUID.randomUUID(), "ext-1", null, "Acme", "Engineer",
                city, city, null, null, null, experienceLevel,
                BigDecimal.valueOf(60000), BigDecimal.valueOf(60000), "GBP", "A role.", skills,
                "https://jobs.example.org/1", "https://jobs.example.org/1", Instant.now(), "DISCOVERED");
    }

    private static PreferenceSetRecord preferences(List<String> experienceLevels, List<String> locationsAllowed,
                                                    String sponsorshipPolicy, Long salaryMinGbp,
                                                    Map<String, Object> scoringWeights) {
        return new PreferenceSetRecord(UUID.randomUUID(), PROFILE, null, null, null, null,
                locationsAllowed, null, null, experienceLevels, salaryMinGbp, sponsorshipPolicy, null, null, null,
                scoringWeights, null, true);
    }

    @Test
    void anExperienceLevelMatchIsWeighedInOnlyWhenTheCandidateConfiguredOne() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(jobWithLevelAndLocation("SENIOR", "London", List.of("Java"))));
        profileSkills("Java");

        var result = service.evaluateMatch(JOB, PROFILE, preferences(List.of("SENIOR"), null, null, 50000L, null));

        // Applicable: skill=1.0 (w30), experience=1.0 (w15), salary=1.0 (w10,
        // job max 60000 >= 50000). (30+15+10)/55*100 = 100.
        assertThat(result.overall()).isEqualTo(100);
        assertThat(result.experienceFit()).isEqualTo(1.0);
    }

    @Test
    void anExperienceLevelMismatchLowersTheScoreRatherThanRejectingOutright() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(jobWithLevelAndLocation("JUNIOR", "London", List.of("Java"))));
        profileSkills("Java");

        var result = service.evaluateMatch(JOB, PROFILE, preferences(List.of("SENIOR"), null, null, 50000L, null));

        // skill=1.0 (w30), experience=0.0 (w15), salary=1.0 (w10).
        // (30+0+10)/55*100 = 72.7 -> 73. Still scored (not a hard reject) —
        // hard rejection is HardFilterService's job, not the scorer's.
        assertThat(result.overall()).isEqualTo(73);
        assertThat(result.experienceFit()).isEqualTo(0.0);
    }

    @Test
    void experienceCategoryIsExcludedNotNeutralWhenTheCandidateSetNoPreference() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(jobWithLevelAndLocation("JUNIOR", "London", List.of("Java"))));
        profileSkills("Java");

        var result = service.evaluateMatch(JOB, PROFILE, preferences(null, null, null, 50000L, null));

        // Only skill (w30, 1.0) + salary (w10, 1.0) applicable: (30+10)/40*100
        // = 100 — identical to a perfect-everything score. If the job's
        // JUNIOR level had instead been scored neutral (0.5) and included,
        // this would come out lower; it does not, proving exclusion.
        assertThat(result.overall()).isEqualTo(100);
    }

    @Test
    void aLocationMatchAndMismatchAreWeighedInOnlyWhenConfigured() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(jobWithLevelAndLocation(null, "London", List.of("Java"))));
        profileSkills("Java");

        var matched = service.evaluateMatch(JOB, PROFILE, preferences(null, List.of("London"), null, 50000L, null));
        var mismatched = service.evaluateMatch(JOB, PROFILE, preferences(null, List.of("Manchester"), null, 50000L, null));

        assertThat(matched.locationFit()).isEqualTo(1.0);
        assertThat(mismatched.locationFit()).isEqualTo(0.0);
        // matched: skill=1.0(30)+location=1.0(10)+salary=1.0(10) -> 100.
        // mismatched: skill=1.0(30)+location=0.0(10)+salary=1.0(10) -> 80.
        assertThat(matched.overall()).isEqualTo(100);
        assertThat(mismatched.overall()).isEqualTo(80);
    }

    @Test
    void sponsorshipRequiredUsesTheJobsOwnLlmAssessmentWhenAvailable() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java"), 50000)));
        profileSkills("Java");
        when(jdbc.queryForList(contains("job_analyses"), eq(JOB)))
                .thenReturn(List.of(Map.of("sponsorship_status", "LIKELY_AVAILABLE")));

        var result = service.evaluateMatch(JOB, PROFILE, preferences(null, null, "REQUIRED", 50000L, null));

        // skill=1.0(w30) + visa=1.0(w20) + salary=1.0(w10), total=60 -> 100.
        assertThat(result.overall()).isEqualTo(100);
        assertThat(result.visaFit()).isEqualTo(1.0);
    }

    @Test
    void sponsorshipRequiredButAssessedUnavailableLowersTheScore() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java"), 50000)));
        profileSkills("Java");
        when(jdbc.queryForList(contains("job_analyses"), eq(JOB)))
                .thenReturn(List.of(Map.of("sponsorship_status", "NOT_AVAILABLE")));

        var result = service.evaluateMatch(JOB, PROFILE, preferences(null, null, "REQUIRED", 50000L, null));

        // skill=1.0(w30) + visa=0.0(w20) + salary=1.0(w10), total=60 -> 100*40/60 = 66.6 -> 67.
        assertThat(result.overall()).isEqualTo(67);
        assertThat(result.visaFit()).isEqualTo(0.0);
    }

    @Test
    void sponsorshipPolicySetToShowAllNeverWeighsVisaInAtAll() {
        // SHOW_ALL (and NONE) are non-restrictive per HardFilterService's own
        // precedent — only REQUIRED makes the visa category applicable.
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java"), 50000)));
        profileSkills("Java");
        when(jdbc.queryForList(contains("job_analyses"), eq(JOB)))
                .thenReturn(List.of(Map.of("sponsorship_status", "NOT_AVAILABLE")));

        var result = service.evaluateMatch(JOB, PROFILE, preferences(null, null, "SHOW_ALL", 50000L, null));

        verify(jdbc, never()).queryForList(contains("job_analyses"), any(UUID.class));
        // skill=1.0(w30) + salary=1.0(w10), total=40 -> 100, unaffected by the
        // (irrelevant, never even looked up) sponsorship assessment above.
        assertThat(result.overall()).isEqualTo(100);
    }

    @Test
    void configuredScoringWeightsActuallyChangeTheOverallScoreForTheSameInputs() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java", "Spring"), 60000)));
        profileSkills("Java"); // 1 of 2 matched -> skillOverlap = 0.5
        Map<String, Object> salaryHeavy = Map.of(
                "skill", 10, "experience", 10, "visa", 10, "location", 10, "salary", 50, "career", 5, "difficulty", 5);

        var result = service.evaluateMatch(JOB, PROFILE, preferences(null, null, null, 50000L, salaryHeavy));

        // Applicable: skill=0.5 (w10), salary=1.0 (w50, job max 60000 >= 50000), total=60.
        // (10*0.5 + 50*1.0)/60*100 = 91.67 -> 92 — far higher than the 63 the
        // default weights produced for the identical skill/salary inputs in
        // reviewMatchIsExplainedAsAHumanDecision, proving the configured
        // weight actually moved the score.
        assertThat(result.overall()).isEqualTo(92);
    }

    @Test
    void careerAndDifficultyWeightsNeverAffectTheScoreSinceNeitherHasAComputedSignalYet() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java"), 60000)));
        profileSkills("Java");
        Map<String, Object> weightsA = Map.of(
                "skill", 30, "experience", 15, "visa", 20, "location", 10, "salary", 10, "career", 10, "difficulty", 5);
        Map<String, Object> weightsB = Map.of(
                "skill", 30, "experience", 15, "visa", 20, "location", 10, "salary", 10, "career", 0, "difficulty", 15);

        int overallA = service.evaluateMatch(JOB, PROFILE, preferences(null, null, null, 50000L, weightsA)).overall();
        int overallB = service.evaluateMatch(JOB, PROFILE, preferences(null, null, null, 50000L, weightsB)).overall();

        assertThat(overallA).isEqualTo(overallB);
    }

    @Test
    void invalidScoringWeightsFallBackToTheDocumentedDefaultRatherThanFailing() {
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(job(List.of("Java", "Spring"), 60000)));
        profileSkills("Java", "Spring");
        Map<String, Object> broken = Map.of("skill", 999); // missing categories, does not sum to 100

        var result = service.evaluateMatch(JOB, PROFILE, preferences(null, null, null, 50000L, broken));

        // Falls back to the documented default (skill 30, salary 10 of a
        // 40-point applicable total) — identical to the plain-salary-floor
        // case in highMatchExplainsWhyAndDefersToTheDecisionRulesWithoutClaimingAnApplication.
        assertThat(result.overall()).isEqualTo(100);
    }
}
