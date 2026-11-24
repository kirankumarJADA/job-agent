package com.personal.jobagent.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 2 / QA: Held-Out Evaluation Dataset Benchmark")
class HeldoutMatchingBenchmarkTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    record Candidate(String id, String name, List<String> skills, List<String> titles,
                     List<String> locations, List<String> remoteTypes, int salaryMin, List<String> experienceLevels) {}

    record ScoredJob(String id, String title, double score, double groundTruthRelevance) {}

    @Test
    @DisplayName("Evaluate Wave 2 Reranking against baseline on held-out evaluation dataset")
    void evaluateHeldoutRankingPerformance() throws Exception {
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

        double totalBaselineNdcg = 0.0;
        double totalWave2Ndcg = 0.0;
        double totalBaselinePrecision = 0.0;
        double totalWave2Precision = 0.0;

        for (Candidate candidate : candidates) {
            List<ScoredJob> baselineRanked = new ArrayList<>();
            List<ScoredJob> wave2Ranked = new ArrayList<>();

            for (JsonNode job : jobsNode) {
                String jobId = job.get("id").asText();
                String title = job.get("title").asText();
                List<String> jobSkills = toStringList(job.get("skills"));
                String experienceLevel = job.has("experienceLevel") ? job.get("experienceLevel").asText() : null;
                int salaryMax = job.has("salaryMax") ? job.get("salaryMax").asInt() : 0;
                double groundTruth = job.get("relevance").has(candidate.id()) ? job.get("relevance").get(candidate.id()).asDouble() : 0.0;

                // Baseline scoring: simple skill overlap ratio only
                long matchedSkillsBaseline = jobSkills.stream().filter(candidate.skills()::contains).count();
                double baselineScore = jobSkills.isEmpty() ? 0.5 : (double) matchedSkillsBaseline / jobSkills.size();
                baselineRanked.add(new ScoredJob(jobId, title, baselineScore, groundTruth));

                // Stage 1: Hard eligibility override (HardFilterService invariant)
                boolean titleHardMatch = HardFilterService.matchesAnyTitle(title, candidate.titles());
                boolean salaryHardMatch = salaryMax == 0 || salaryMax >= (candidate.salaryMin() * 0.80);
                if (!titleHardMatch || !salaryHardMatch) {
                    wave2Ranked.add(new ScoredJob(jobId, title, 0.0, groundTruth));
                    continue;
                }

                // Stage 2: Semantic skills overlap
                long matchedSkills = jobSkills.stream().filter(s -> candidate.skills().stream().anyMatch(cs -> cs.equalsIgnoreCase(s))).count();
                double skillScore = jobSkills.isEmpty() ? 0.5 : (double) matchedSkills / jobSkills.size();

                // Stage 3: Seniority alignment
                SeniorityClassifier.Level jobSeniority = SeniorityClassifier.classifyJob(experienceLevel, title);
                double seniorityScore = SeniorityClassifier.computeAlignmentScore(jobSeniority, candidate.experienceLevels());

                // Stage 4: Title matching with synonyms
                boolean exactTitle = candidate.titles().stream().anyMatch(t -> title.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT)));
                boolean synonymTitle = candidate.titles().stream().anyMatch(t -> TitleSynonymRegistry.areSynonyms(t, title));
                double titleScore = exactTitle ? 1.0 : (synonymTitle ? 0.85 : 0.60);

                // Stage 5: Salary fit
                double salaryFit = salaryMax >= candidate.salaryMin() ? 1.0 : (salaryMax > 0 ? (double) salaryMax / candidate.salaryMin() : 0.5);

                // Wave 2 weighted overall score (skills: 45%, title: 25%, seniority: 20%, salary: 10%)
                double wave2Score = (skillScore * 0.45) + (titleScore * 0.25) + (seniorityScore * 0.20) + (salaryFit * 0.10);
                wave2Ranked.add(new ScoredJob(jobId, title, wave2Score, groundTruth));
            }

            // Sort descending by score
            baselineRanked.sort((a, b) -> Double.compare(b.score(), a.score()));
            wave2Ranked.sort((a, b) -> Double.compare(b.score(), a.score()));

            // Metrics at K=10
            int k = 10;
            List<Double> baselineGrades = baselineRanked.stream().map(ScoredJob::groundTruthRelevance).toList();
            List<Double> wave2Grades = wave2Ranked.stream().map(ScoredJob::groundTruthRelevance).toList();

            double bNdcg = RankingMetrics.ndcgAtK(baselineGrades, k);
            double wNdcg = RankingMetrics.ndcgAtK(wave2Grades, k);

            List<Boolean> baselineBinaryRel = baselineRanked.stream().map(j -> j.groundTruthRelevance() >= 2.0).toList();
            List<Boolean> wave2BinaryRel = wave2Ranked.stream().map(j -> j.groundTruthRelevance() >= 2.0).toList();

            double bPrec = RankingMetrics.precisionAtK(baselineBinaryRel, k);
            double wPrec = RankingMetrics.precisionAtK(wave2BinaryRel, k);

            totalBaselineNdcg += bNdcg;
            totalWave2Ndcg += wNdcg;
            totalBaselinePrecision += bPrec;
            totalWave2Precision += wPrec;

            System.out.printf("Held-Out Candidate %s: Baseline nDCG@10=%.4f, Wave2 nDCG@10=%.4f | Baseline P@10=%.4f, Wave2 P@10=%.4f%n",
                    candidate.id(), bNdcg, wNdcg, bPrec, wPrec);

            // Each candidate must maintain a strong nDCG on held-out data
            assertThat(wNdcg).isGreaterThanOrEqualTo(0.70);
        }

        double avgBaselineNdcg = totalBaselineNdcg / candidates.size();
        double avgWave2Ndcg = totalWave2Ndcg / candidates.size();
        double avgBaselinePrec = totalBaselinePrecision / candidates.size();
        double avgWave2Prec = totalWave2Precision / candidates.size();

        System.out.println("================= HELDOUT EVALUATION SUMMARY =================");
        System.out.printf("AVERAGE: Baseline nDCG@10 = %.4f -> Wave 2 nDCG@10 = %.4f (+%.1f%%)%n",
                avgBaselineNdcg, avgWave2Ndcg, ((avgWave2Ndcg - avgBaselineNdcg) / avgBaselineNdcg) * 100);
        System.out.printf("AVERAGE: Baseline Precision@10 = %.4f -> Wave 2 Precision@10 = %.4f (+%.1f%%)%n",
                avgBaselinePrec, avgWave2Prec, ((avgWave2Prec - avgBaselinePrec) / avgBaselinePrec) * 100);
        System.out.println("==============================================================");

        assertThat(avgWave2Ndcg).isGreaterThan(avgBaselineNdcg);
        assertThat(avgWave2Prec).isGreaterThanOrEqualTo(avgBaselinePrec);
    }

    private static List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        node.forEach(item -> list.add(item.asText()));
        return list;
    }
}
