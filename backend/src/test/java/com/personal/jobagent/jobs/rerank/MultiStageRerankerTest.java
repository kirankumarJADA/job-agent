package com.personal.jobagent.jobs.rerank;

import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.SemanticSkillMatcher;
import com.personal.jobagent.jobs.SeniorityClassifier;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.profile.SkillRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("Agent 1: MultiStageReranker Unit Tests")
class MultiStageRerankerTest {

    private ProfileRepository profileRepo;
    private SemanticSkillMatcher semanticMatcher;
    private JdbcTemplate jdbcTemplate;
    private MultiStageReranker reranker;

    private final UUID profileId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        profileRepo = mock(ProfileRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        semanticMatcher = new SemanticSkillMatcher(jdbcTemplate, null);
        reranker = new MultiStageReranker(profileRepo, semanticMatcher, jdbcTemplate);
    }

    @Test
    @DisplayName("Stage 1: Disqualified when visa sponsorship REQUIRED but employer has NO_LICENCE in sponsor_records")
    void stage1_visaRequiredWithNoLicence_disqualified() {
        UUID companyId = UUID.randomUUID();
        JobRecord job = jobWithCompany(companyId, "Software Engineer", "MID", 60000, List.of("Java"));
        PreferenceSetRecord prefs = new PreferenceSetRecord(
                UUID.randomUUID(), profileId, List.of("Software Engineer"), List.of(), List.of(),
                List.of(), List.of(), List.of("HYBRID"), List.of(), List.of("MID"),
                50000L, "REQUIRED", List.of(), List.of(), "MANUAL",
                Map.of("skill", 35, "experience", 20, "visa", 10, "location", 10, "salary", 15, "career", 5, "difficulty", 5),
                Map.of(), true
        );

        when(jdbcTemplate.queryForList(anyString(), eq(companyId)))
                .thenReturn(List.of(Map.of("licence_indicator", "NO_LICENCE", "confidence", 1.0)));

        RerankResult result = reranker.rerank(job, profileId, prefs);

        assertThat(result.eligible()).isFalse();
        assertThat(result.overallScore()).isEqualTo(0);
        assertThat(result.recommendation()).isEqualTo("SKIP");
        assertThat(result.concerns()).anyMatch(c -> c.contains("NO_LICENCE"));
    }

    @Test
    @DisplayName("Stage 1: Disqualified by excluded keywords in EffectiveMatchingCriteria")
    void stage1_excludedKeywords_disqualified() {
        JobRecord job = job("Senior WordPress Developer", "SENIOR", 70000, List.of("PHP"));
        com.personal.jobagent.jobs.EffectiveMatchingCriteria criteria = new com.personal.jobagent.jobs.EffectiveMatchingCriteria(
                profileId,
                List.of("WordPress Developer"),
                List.of("PHP"),
                List.of("wordpress"),
                List.of("London"),
                List.of("HYBRID"),
                List.of("SENIOR"),
                50000L,
                "NONE",
                List.of(),
                List.of(),
                Map.of()
        );

        RerankResult result = reranker.rerank(job, profileId, criteria);

        assertThat(result.eligible()).isFalse();
        assertThat(result.overallScore()).isEqualTo(0);
        assertThat(result.recommendation()).isEqualTo("SKIP");
        assertThat(result.concerns()).anyMatch(c -> c.contains("excluded keyword"));
    }

    @Test
    @DisplayName("Stage 1: Disqualified by salary floor returns eligible=false, overall=0, recommendation=SKIP")
    void stage1_salaryDisqualified() {
        JobRecord job = job("Software Engineer", "MID", 30000, List.of("Java"));
        PreferenceSetRecord prefs = prefs(List.of("Software Engineer"), List.of("MID"), 50000L);

        RerankResult result = reranker.rerank(job, profileId, prefs);

        assertThat(result.eligible()).isFalse();
        assertThat(result.overallScore()).isEqualTo(0);
        assertThat(result.recommendation()).isEqualTo("SKIP");
        assertThat(result.concerns()).anyMatch(c -> c.contains("80% of minimum threshold"));
    }

    @Test
    @DisplayName("Stage 1: Word-boundary title regex eliminates substring bleed ('International' != 'Intern')")
    void stage1_wordBoundaryEliminatesBleed() {
        JobRecord job = job("International Relations Manager", "MID", 60000, List.of("Management"));
        PreferenceSetRecord prefs = prefs(List.of("Intern"), List.of("INTERN"), 30000L);

        RerankResult result = reranker.rerank(job, profileId, prefs);

        assertThat(result.eligible()).isFalse();
        assertThat(result.overallScore()).isEqualTo(0);
        assertThat(result.recommendation()).isEqualTo("SKIP");
    }

    @Test
    @DisplayName("Stage 2: Skill matching semantically captures synonyms (ReactJS -> React)")
    void stage2_semanticSkillMatching() {
        JobRecord job = job("Frontend Developer", "MID", 60000, List.of("React"));
        when(profileRepo.findSkills(profileId)).thenReturn(List.of(
                new SkillRecord(UUID.randomUUID(), profileId, "ReactJS", "CORE", 4, null, null)
        ));
        PreferenceSetRecord prefs = prefs(List.of("Frontend Developer"), List.of("MID"), 50000L);

        RerankResult result = reranker.rerank(job, profileId, prefs);

        assertThat(result.eligible()).isTrue();
        assertThat(result.matchedSkills()).contains("ReactJS");
        assertThat(result.stageScores().get("skills_overlap")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Stage 3: Calibrated seniority awards 1.0 to exact ENTRY role for junior QA profile")
    void stage3_calibratedSeniorityForJuniorQA() {
        double entryScore = MultiStageReranker.computeCalibratedSeniority(
                SeniorityClassifier.Level.ENTRY, List.of("ENTRY", "JUNIOR", "MID"));
        double midScore = MultiStageReranker.computeCalibratedSeniority(
                SeniorityClassifier.Level.MID, List.of("ENTRY", "JUNIOR", "MID"));

        // Entry role receives 1.0; Mid role receives 0.92
        assertThat(entryScore).isEqualTo(1.0);
        assertThat(midScore).isEqualTo(0.92);
        assertThat(entryScore).isGreaterThan(midScore);
    }

    @Test
    @DisplayName("Stage 5: Holistic synthesis produces rich stage breakdown and narrative")
    void stage5_holisticBreakdown() {
        JobRecord job = job("Senior Backend Engineer", "SENIOR", 85000, List.of("Java", "Spring Boot", "PostgreSQL"));
        when(profileRepo.findSkills(profileId)).thenReturn(List.of(
                new SkillRecord(UUID.randomUUID(), profileId, "Java", "CORE", 5, null, null),
                new SkillRecord(UUID.randomUUID(), profileId, "Spring Boot", "CORE", 4, null, null)
        ));
        PreferenceSetRecord prefs = prefs(List.of("Backend Engineer"), List.of("SENIOR"), 75000L);

        RerankResult result = reranker.rerank(job, profileId, prefs);

        assertThat(result.eligible()).isTrue();
        assertThat(result.overallScore()).isGreaterThanOrEqualTo(70);
        assertThat(result.recommendation()).isEqualTo("APPLY");
        assertThat(result.stageScores()).containsKeys("hard_eligibility", "skills_overlap", "seniority_alignment", "title_fit", "salary_fit");
        assertThat(result.narrativeWhy()).contains("Multi-stage reranking scored");
        assertThat(result.decisionSummary()).contains("APPLY");
    }

    private JobRecord job(String title, String level, int salaryMax, List<String> skills) {
        return jobWithCompany(null, title, level, salaryMax, skills);
    }

    private JobRecord jobWithCompany(UUID companyId, String title, String level, int salaryMax, List<String> skills) {
        return new JobRecord(
                jobId, UUID.randomUUID(), "ext-1", companyId, "Acme Corp", title,
                "London", "London", "UK", "HYBRID", "FULL_TIME", level,
                BigDecimal.valueOf(salaryMax - 5000), BigDecimal.valueOf(salaryMax),
                "GBP", "Description", skills, "https://example.com/apply", "https://example.com/job",
                Instant.now(), "DISCOVERED"
        );
    }

    private PreferenceSetRecord prefs(List<String> titles, List<String> levels, Long salaryMin) {
        return new PreferenceSetRecord(
                UUID.randomUUID(), profileId, titles, List.of(), List.of(),
                List.of(), List.of(), List.of("HYBRID"), List.of(), levels,
                salaryMin, "NONE", List.of(), List.of(), "MANUAL",
                Map.of("skill", 35, "experience", 20, "visa", 10, "location", 10, "salary", 15, "career", 5, "difficulty", 5),
                Map.of(), true
        );
    }
}
