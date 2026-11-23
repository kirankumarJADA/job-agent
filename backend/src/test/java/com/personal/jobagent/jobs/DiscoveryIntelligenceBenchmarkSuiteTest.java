package com.personal.jobagent.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.jobs.rerank.MultiStageReranker;
import com.personal.jobagent.jobs.rerank.RerankResult;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.profile.SkillRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("Agent 3: Discovery Intelligence Reproducible Ranking Benchmark Framework")
public class DiscoveryIntelligenceBenchmarkSuiteTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    record Candidate(String id, String name, List<String> skills, List<String> titles,
                     List<String> locations, List<String> remoteTypes, int salaryMin, List<String> experienceLevels) {}

    record ScoredEvaluation(String jobId, String title, double score, double groundTruthRelevance, boolean eligible) {}

    @Test
    @DisplayName("Evaluate Multi-Stage Reranker against Baseline and Wave 2 on Held-Out Evaluation Dataset")
    void evaluateMultiStageRerankerOnHeldOutDataset() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/matching/heldout_evaluation_dataset.json");
        assertThat(stream).isNotNull();
        JsonNode root = objectMapper.readTree(stream);

        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode c : root.get("candidates")) {
            candidates.add(new Candidate(
                    c.get("id").asText(),
                    c.get("name").asText(),
                    toStringList(c.get("skills")),
                    toStringList(c.get("titles")),
                    toStringList(c.get("locations")),
                    toStringList(c.get("remoteTypes")),
                    c.get("salaryMin").asInt(),
                    toStringList(c.get("experienceLevels"))
            ));
        }

        JsonNode jobsNode = root.get("jobs");
        assertThat(jobsNode.isArray()).isTrue();

        ProfileRepository profileRepo = mock(ProfileRepository.class);
        JdbcTemplate cacheDb = mock(JdbcTemplate.class);
        SemanticSkillMatcher semanticMatcher = new SemanticSkillMatcher(cacheDb, null);
        MultiStageReranker reranker = new MultiStageReranker(profileRepo, semanticMatcher);

        double totalBaselineNdcg10 = 0.0;
        double totalWave2Ndcg10 = 0.0;
        double totalMultiStageNdcg10 = 0.0;

        double totalBaselineNdcg5 = 0.0;
        double totalMultiStageNdcg5 = 0.0;

        double totalBaselinePrecision10 = 0.0;
        double totalMultiStagePrecision10 = 0.0;

        double totalBaselinePrecision5 = 0.0;
        double totalMultiStagePrecision5 = 0.0;

        double totalMultiStageRecall10 = 0.0;
        double totalMultiStageRecall5 = 0.0;

        double totalBaselineMrr = 0.0;
        double totalMultiStageMrr = 0.0;

        Map<String, Double> perCandidateMultiStageNdcg = new LinkedHashMap<>();

        for (Candidate candidate : candidates) {
            UUID profileId = UUID.randomUUID();
            List<SkillRecord> skillRecords = candidate.skills().stream()
                    .map(s -> new SkillRecord(UUID.randomUUID(), profileId, s, "CORE", 4, null, null))
                    .toList();
            when(profileRepo.findSkills(profileId)).thenReturn(skillRecords);

            PreferenceSetRecord prefs = new PreferenceSetRecord(
                    UUID.randomUUID(),
                    profileId,
                    candidate.titles(),
                    List.of(),
                    List.of(),
                    candidate.skills(),
                    candidate.locations(),
                    candidate.remoteTypes(),
                    List.of(),
                    candidate.experienceLevels(),
                    (long) candidate.salaryMin(),
                    "NONE",
                    List.of(),
                    List.of(),
                    "MANUAL",
                    Map.of("skill", 35, "experience", 20, "visa", 10, "location", 10, "salary", 15, "career", 5, "difficulty", 5),
                    Map.of(),
                    true
            );

            List<ScoredEvaluation> baselineRanked = new ArrayList<>();
            List<ScoredEvaluation> wave2Ranked = new ArrayList<>();
            List<ScoredEvaluation> multiStageRanked = new ArrayList<>();

            int totalRelevantInPool = 0;

            for (JsonNode jobNode : jobsNode) {
                String jobId = jobNode.get("id").asText();
                String title = jobNode.get("title").asText();
                List<String> jobSkills = toStringList(jobNode.get("skills"));
                String experienceLevel = jobNode.has("experienceLevel") ? jobNode.get("experienceLevel").asText() : null;
                int salaryMax = jobNode.has("salaryMax") ? jobNode.get("salaryMax").asInt() : 0;
                double groundTruth = jobNode.get("relevance").has(candidate.id())
                        ? jobNode.get("relevance").get(candidate.id()).asDouble()
                        : 0.0;

                if (groundTruth >= 2.0) {
                    totalRelevantInPool++;
                }

                // 1. Baseline scoring (unweighted keyword overlap)
                long matchedSkillsBaseline = jobSkills.stream().filter(candidate.skills()::contains).count();
                double baselineScore = jobSkills.isEmpty() ? 0.5 : (double) matchedSkillsBaseline / jobSkills.size();
                baselineRanked.add(new ScoredEvaluation(jobId, title, baselineScore, groundTruth, true));

                // 2. Wave 2 scoring
                boolean titleHardMatch = HardFilterService.matchesAnyTitle(title, candidate.titles());
                boolean salaryHardMatch = salaryMax == 0 || salaryMax >= (candidate.salaryMin() * 0.80);
                if (!titleHardMatch || !salaryHardMatch) {
                    wave2Ranked.add(new ScoredEvaluation(jobId, title, 0.0, groundTruth, false));
                } else {
                    long matchedSkills = jobSkills.stream().filter(s -> candidate.skills().stream().anyMatch(cs -> cs.equalsIgnoreCase(s))).count();
                    double skillScore = jobSkills.isEmpty() ? 0.5 : (double) matchedSkills / jobSkills.size();
                    SeniorityClassifier.Level jobSeniority = SeniorityClassifier.classifyJob(experienceLevel, title);
                    double seniorityScore = SeniorityClassifier.computeAlignmentScore(jobSeniority, candidate.experienceLevels());
                    boolean exactTitle = candidate.titles().stream().anyMatch(t -> title.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT)));
                    boolean synonymTitle = candidate.titles().stream().anyMatch(t -> TitleSynonymRegistry.areSynonyms(t, title));
                    double titleScore = exactTitle ? 1.0 : (synonymTitle ? 0.85 : 0.60);
                    double salaryFit = salaryMax >= candidate.salaryMin() ? 1.0 : (salaryMax > 0 ? (double) salaryMax / candidate.salaryMin() : 0.5);
                    double wave2Score = (skillScore * 0.45) + (titleScore * 0.25) + (seniorityScore * 0.20) + (salaryFit * 0.10);
                    wave2Ranked.add(new ScoredEvaluation(jobId, title, wave2Score, groundTruth, true));
                }

                // 3. Multi-Stage Intelligent Reranker
                JobRecord jobRecord = new JobRecord(
                        UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", Math.abs(jobId.hashCode()))),
                        UUID.randomUUID(),
                        jobId,
                        null,
                        "Company",
                        title,
                        "London",
                        "London",
                        "UK",
                        "HYBRID",
                        "FULL_TIME",
                        experienceLevel,
                        BigDecimal.valueOf(Math.max(0, salaryMax - 5000)),
                        BigDecimal.valueOf(salaryMax),
                        "GBP",
                        "Job description",
                        jobSkills,
                        "https://example.com/apply/" + jobId,
                        "https://example.com/jobs/" + jobId,
                        Instant.now(),
                        "DISCOVERED"
                );

                RerankResult rerankResult = reranker.rerank(jobRecord, profileId, prefs);
                multiStageRanked.add(new ScoredEvaluation(jobId, title, rerankResult.overallScore(), groundTruth, rerankResult.eligible()));
            }

            baselineRanked.sort((a, b) -> Double.compare(b.score(), a.score()));
            wave2Ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
            multiStageRanked.sort((a, b) -> Double.compare(b.score(), a.score()));

            List<Double> baselineGrades = baselineRanked.stream().map(ScoredEvaluation::groundTruthRelevance).toList();
            List<Double> wave2Grades = wave2Ranked.stream().map(ScoredEvaluation::groundTruthRelevance).toList();
            List<Double> multiStageGrades = multiStageRanked.stream().map(ScoredEvaluation::groundTruthRelevance).toList();

            double bNdcg10 = RankingMetrics.ndcgAtK(baselineGrades, 10);
            double wNdcg10 = RankingMetrics.ndcgAtK(wave2Grades, 10);
            double mNdcg10 = RankingMetrics.ndcgAtK(multiStageGrades, 10);

            double bNdcg5 = RankingMetrics.ndcgAtK(baselineGrades, 5);
            double mNdcg5 = RankingMetrics.ndcgAtK(multiStageGrades, 5);

            List<Boolean> baselineRel = baselineRanked.stream().map(j -> j.groundTruthRelevance() >= 2.0).toList();
            List<Boolean> multiStageRel = multiStageRanked.stream().map(j -> j.groundTruthRelevance() >= 2.0).toList();

            double bPrec10 = RankingMetrics.precisionAtK(baselineRel, 10);
            double mPrec10 = RankingMetrics.precisionAtK(multiStageRel, 10);

            double bPrec5 = RankingMetrics.precisionAtK(baselineRel, 5);
            double mPrec5 = RankingMetrics.precisionAtK(multiStageRel, 5);

            double mRecall10 = RankingMetrics.recallAtK(multiStageRel, totalRelevantInPool, 10);
            double mRecall5 = RankingMetrics.recallAtK(multiStageRel, totalRelevantInPool, 5);

            double bMrr = RankingMetrics.reciprocalRank(baselineRel);
            double mMrr = RankingMetrics.reciprocalRank(multiStageRel);

            totalBaselineNdcg10 += bNdcg10;
            totalWave2Ndcg10 += wNdcg10;
            totalMultiStageNdcg10 += mNdcg10;

            totalBaselineNdcg5 += bNdcg5;
            totalMultiStageNdcg5 += mNdcg5;

            totalBaselinePrecision10 += bPrec10;
            totalMultiStagePrecision10 += mPrec10;

            totalBaselinePrecision5 += bPrec5;
            totalMultiStagePrecision5 += mPrec5;

            totalMultiStageRecall10 += mRecall10;
            totalMultiStageRecall5 += mRecall5;

            totalBaselineMrr += bMrr;
            totalMultiStageMrr += mMrr;

            perCandidateMultiStageNdcg.put(candidate.id(), mNdcg10);

            System.out.printf("Candidate %s [%s]: Baseline nDCG@10=%.4f | Wave2=%.4f | MultiStage=%.4f | P@10=%.4f | R@10=%.4f | MRR=%.4f%n",
                    candidate.id(), candidate.name(), bNdcg10, wNdcg10, mNdcg10, mPrec10, mRecall10, mMrr);
        }

        int n = candidates.size();
        double avgBaselineNdcg10 = totalBaselineNdcg10 / n;
        double avgWave2Ndcg10 = totalWave2Ndcg10 / n;
        double avgMultiStageNdcg10 = totalMultiStageNdcg10 / n;

        double avgBaselineNdcg5 = totalBaselineNdcg5 / n;
        double avgMultiStageNdcg5 = totalMultiStageNdcg5 / n;

        double avgBaselinePrec10 = totalBaselinePrecision10 / n;
        double avgMultiStagePrec10 = totalMultiStagePrecision10 / n;

        double avgBaselinePrec5 = totalBaselinePrecision5 / n;
        double avgMultiStagePrec5 = totalMultiStagePrecision5 / n;

        double avgMultiStageRecall10 = totalMultiStageRecall10 / n;
        double avgMultiStageRecall5 = totalMultiStageRecall5 / n;

        double avgBaselineMrr = totalBaselineMrr / n;
        double avgMultiStageMrr = totalMultiStageMrr / n;

        System.out.println("================= MULTI-STAGE DISCOVERY BENCHMARK SUMMARY =================");
        System.out.printf("nDCG@10:    Baseline = %.4f -> Wave 2 = %.4f -> Multi-Stage = %.4f%n",
                avgBaselineNdcg10, avgWave2Ndcg10, avgMultiStageNdcg10);
        System.out.printf("nDCG@5:     Baseline = %.4f -> Multi-Stage = %.4f%n",
                avgBaselineNdcg5, avgMultiStageNdcg5);
        System.out.printf("P@10:       Baseline = %.4f -> Multi-Stage = %.4f%n",
                avgBaselinePrec10, avgMultiStagePrec10);
        System.out.printf("P@5:        Baseline = %.4f -> Multi-Stage = %.4f%n",
                avgBaselinePrec5, avgMultiStagePrec5);
        System.out.printf("Recall@10:  Multi-Stage = %.4f%n", avgMultiStageRecall10);
        System.out.printf("Recall@5:   Multi-Stage = %.4f%n", avgMultiStageRecall5);
        System.out.printf("MRR:        Baseline = %.4f -> Multi-Stage = %.4f%n",
                avgBaselineMrr, avgMultiStageMrr);
        System.out.println("===========================================================================");

        // Invariants:
        // 1. MultiStage nDCG@10 must outperform baseline
        assertThat(avgMultiStageNdcg10).isGreaterThan(avgBaselineNdcg10);
        // 2. MultiStage nDCG@5 must be strong (>= 0.95)
        assertThat(avgMultiStageNdcg5).isGreaterThanOrEqualTo(0.95);
        // 3. Precision@10 must not regress below baseline
        assertThat(avgMultiStagePrec10).isGreaterThanOrEqualTo(avgBaselinePrec10);
        // 4. Precision@5 must be strong
        assertThat(avgMultiStagePrec5).isGreaterThanOrEqualTo(0.40);
        // 5. MRR must be 1.0 (top ranked item is always relevant)
        assertThat(avgMultiStageMrr).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Validation: Junior career trajectory protection prevents mid/senior roles from leapfrogging entry roles")
    void assertJuniorCareerTrajectoryProtection() {
        // Candidate targeting entry-level QA roles
        double entryAlignment = MultiStageReranker.computeCalibratedSeniority(
                SeniorityClassifier.Level.ENTRY, List.of("ENTRY", "JUNIOR"));
        double midAlignment = MultiStageReranker.computeCalibratedSeniority(
                SeniorityClassifier.Level.MID, List.of("ENTRY", "JUNIOR"));
        double seniorAlignment = MultiStageReranker.computeCalibratedSeniority(
                SeniorityClassifier.Level.SENIOR, List.of("ENTRY", "JUNIOR"));

        // Exact ENTRY foundation receives 1.0
        assertThat(entryAlignment).isEqualTo(1.0);
        // MID receives distance penalty (0.70 because distance is 1 step from preferred set)
        assertThat(midAlignment).isEqualTo(0.70);
        // SENIOR receives deeper distance penalty (0.40)
        assertThat(seniorAlignment).isEqualTo(0.40);

        assertThat(entryAlignment).isGreaterThan(midAlignment);
        assertThat(midAlignment).isGreaterThan(seniorAlignment);
    }

    @Test
    @DisplayName("Validation: Hard eligibility filters cannot be overridden by high skill match")
    void assertEligibilityFiltersAreAbsolute() {
        ProfileRepository profileRepo = mock(ProfileRepository.class);
        SemanticSkillMatcher semanticMatcher = new SemanticSkillMatcher(mock(JdbcTemplate.class), null);
        MultiStageReranker reranker = new MultiStageReranker(profileRepo, semanticMatcher);

        UUID profileId = UUID.randomUUID();
        // Candidate has perfect skills for Java
        when(profileRepo.findSkills(profileId)).thenReturn(List.of(
                new SkillRecord(UUID.randomUUID(), profileId, "Java", "CORE", 5, null, null),
                new SkillRecord(UUID.randomUUID(), profileId, "Spring Boot", "CORE", 5, null, null),
                new SkillRecord(UUID.randomUUID(), profileId, "Postgres", "CORE", 5, null, null)
        ));

        // Job has 100% skill match, but title is "Nurse Practitioner" (violates title filter)
        JobRecord disqualifiedJob = new JobRecord(
                UUID.randomUUID(), UUID.randomUUID(), "ext-1", null, "Hospital",
                "Nurse Practitioner", "London", "London", "UK", "ONSITE", "FULL_TIME",
                "SENIOR", BigDecimal.valueOf(80000), BigDecimal.valueOf(90000), "GBP",
                "Nursing care", List.of("Java", "Spring Boot", "Postgres"),
                "https://example.com/apply", "https://example.com/job", Instant.now(), "DISCOVERED"
        );

        PreferenceSetRecord prefs = new PreferenceSetRecord(
                UUID.randomUUID(), profileId, List.of("Backend Engineer", "Software Developer"),
                List.of(), List.of(), List.of("Java"), List.of("London"), List.of("HYBRID"),
                List.of(), List.of("SENIOR"), 70000L, "NONE", List.of(), List.of(), "MANUAL",
                Map.of(), Map.of(), true
        );

        RerankResult result = reranker.rerank(disqualifiedJob, profileId, prefs);

        // Verification: Hard filter MUST disqualify regardless of 100% skills overlap
        assertThat(result.eligible()).isFalse();
        assertThat(result.overallScore()).isEqualTo(0);
        assertThat(result.recommendation()).isEqualTo("SKIP");
        assertThat(result.concerns()).anyMatch(c -> c.contains("does not match target roles"));
    }

    private static List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        node.forEach(item -> list.add(item.asText()));
        return list;
    }
}
