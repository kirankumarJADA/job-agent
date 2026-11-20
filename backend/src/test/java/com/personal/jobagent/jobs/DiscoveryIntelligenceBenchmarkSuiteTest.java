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

        double totalBaselinePrecision = 0.0;
        double totalMultiStagePrecision = 0.0;

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

            for (JsonNode jobNode : jobsNode) {
                String jobId = jobNode.get("id").asText();
                String title = jobNode.get("title").asText();
                List<String> jobSkills = toStringList(jobNode.get("skills"));
                String experienceLevel = jobNode.has("experienceLevel") ? jobNode.get("experienceLevel").asText() : null;
                int salaryMax = jobNode.has("salaryMax") ? jobNode.get("salaryMax").asInt() : 0;
                double groundTruth = jobNode.get("relevance").has(candidate.id())
                        ? jobNode.get("relevance").get(candidate.id()).asDouble()
                        : 0.0;

                // 1. Baseline scoring
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

            double bPrec = RankingMetrics.precisionAtK(baselineRel, 10);
            double mPrec = RankingMetrics.precisionAtK(multiStageRel, 10);

            double bMrr = RankingMetrics.reciprocalRank(baselineRel);
            double mMrr = RankingMetrics.reciprocalRank(multiStageRel);

            totalBaselineNdcg10 += bNdcg10;
            totalWave2Ndcg10 += wNdcg10;
            totalMultiStageNdcg10 += mNdcg10;

            totalBaselineNdcg5 += bNdcg5;
            totalMultiStageNdcg5 += mNdcg5;

            totalBaselinePrecision += bPrec;
            totalMultiStagePrecision += mPrec;

            totalBaselineMrr += bMrr;
            totalMultiStageMrr += mMrr;

            perCandidateMultiStageNdcg.put(candidate.id(), mNdcg10);

            System.out.printf("Candidate %s [%s]: Baseline nDCG@10=%.4f | Wave2=%.4f | MultiStage=%.4f | P@10=%.4f | MRR=%.4f%n",
                    candidate.id(), candidate.name(), bNdcg10, wNdcg10, mNdcg10, mPrec, mMrr);
        }

        int n = candidates.size();
        double avgBaselineNdcg10 = totalBaselineNdcg10 / n;
        double avgWave2Ndcg10 = totalWave2Ndcg10 / n;
        double avgMultiStageNdcg10 = totalMultiStageNdcg10 / n;

        double avgBaselineNdcg5 = totalBaselineNdcg5 / n;
        double avgMultiStageNdcg5 = totalMultiStageNdcg5 / n;

        double avgBaselinePrec = totalBaselinePrecision / n;
        double avgMultiStagePrec = totalMultiStagePrecision / n;

        double avgBaselineMrr = totalBaselineMrr / n;
        double avgMultiStageMrr = totalMultiStageMrr / n;

        System.out.println("================= MULTI-STAGE DISCOVERY BENCHMARK SUMMARY =================");
        System.out.printf("nDCG@10: Baseline = %.4f -> Wave 2 = %.4f -> Multi-Stage = %.4f%n",
                avgBaselineNdcg10, avgWave2Ndcg10, avgMultiStageNdcg10);
        System.out.printf("nDCG@5:  Baseline = %.4f -> Multi-Stage = %.4f%n",
                avgBaselineNdcg5, avgMultiStageNdcg5);
        System.out.printf("P@10:    Baseline = %.4f -> Multi-Stage = %.4f%n",
                avgBaselinePrec, avgMultiStagePrec);
        System.out.printf("MRR:     Baseline = %.4f -> Multi-Stage = %.4f%n",
                avgBaselineMrr, avgMultiStageMrr);
        System.out.println("===========================================================================");

        // Invariants:
        // 1. MultiStage nDCG@10 must outperform baseline
        assertThat(avgMultiStageNdcg10).isGreaterThan(avgBaselineNdcg10);
        // 2. MultiStage nDCG@5 must be strong (>= 0.95)
        assertThat(avgMultiStageNdcg5).isGreaterThanOrEqualTo(0.95);
        // 3. Precision@10 must not regress below baseline
        assertThat(avgMultiStagePrec).isGreaterThanOrEqualTo(avgBaselinePrec);
        // 4. MRR must be 1.0 (top ranked item is always relevant)
        assertThat(avgMultiStageMrr).isEqualTo(1.0);
    }

    private static List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        node.forEach(item -> list.add(item.asText()));
        return list;
    }
}
