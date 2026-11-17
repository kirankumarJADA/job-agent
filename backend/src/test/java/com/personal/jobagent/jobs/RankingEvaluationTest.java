package com.personal.jobagent.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class RankingEvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void benchmarkSyntheticRankingDatasetQuality() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/ranking/synthetic_ranking_dataset.json");
        assertThat(stream).isNotNull();

        JsonNode root = mapper.readTree(stream);
        JsonNode scenarios = root.get("scenarios");
        assertThat(scenarios.isArray()).isTrue();

        for (JsonNode scenario : scenarios) {
            JsonNode candidate = scenario.get("candidate");
            Set<String> candidateSkills = new HashSet<>();
            for (JsonNode s : candidate.get("skills")) {
                candidateSkills.add(SkillSynonymRegistry.canonicalise(s.asText()));
            }
            String candidateTitle = candidate.get("title").asText();
            int candidateMinSalary = candidate.get("desiredSalaryMin").asInt();

            JsonNode jobsNode = scenario.get("jobs");
            List<ScoredJob> scoredJobs = new ArrayList<>();

            for (JsonNode jNode : jobsNode) {
                String id = jNode.get("id").asText();
                String title = jNode.get("title").asText();
                double grade = jNode.get("groundTruthGrade").asDouble();

                Set<String> jobSkills = new HashSet<>();
                for (JsonNode s : jNode.get("skills")) {
                    jobSkills.add(SkillSynonymRegistry.canonicalise(s.asText()));
                }

                // Deterministic relevance scoring simulating matching pipeline
                double titleScore = 0.0;
                if (TitleSynonymRegistry.areSynonyms(candidateTitle, title)
                        || HardFilterService.matchesAnyTitle(title, List.of(candidateTitle))) {
                    titleScore = 40.0;
                } else if (title.toLowerCase().contains("developer") || title.toLowerCase().contains("engineer")) {
                    titleScore = 20.0;
                }

                // Skill overlap score
                Set<String> overlap = new HashSet<>(candidateSkills);
                overlap.retainAll(jobSkills);
                double skillScore = candidateSkills.isEmpty() ? 0.0 : (double) overlap.size() / candidateSkills.size() * 40.0;

                // Salary fit
                double salaryScore = (jNode.get("salaryMax").asInt() >= candidateMinSalary) ? 20.0 : 5.0;

                double totalScore = titleScore + skillScore + salaryScore;
                scoredJobs.add(new ScoredJob(id, totalScore, grade));
            }

            // Rank descending by predicted score
            scoredJobs.sort(Comparator.comparingDouble(ScoredJob::score).reversed());

            List<Double> gradedList = scoredJobs.stream().map(ScoredJob::groundTruth).toList();
            List<Boolean> binaryRelevance = scoredJobs.stream().map(j -> j.groundTruth() >= 2.0).toList();
            long totalRelevant = scoredJobs.stream().filter(j -> j.groundTruth() >= 2.0).count();

            double ndcg = RankingMetrics.ndcgAtK(gradedList, 10);
            double precision = RankingMetrics.precisionAtK(binaryRelevance, 5);
            double recall = RankingMetrics.recallAtK(binaryRelevance, (int) totalRelevant, 5);

            // Assert baseline ranking quality
            assertThat(ndcg).as("nDCG@10 for scenario " + scenario.get("scenarioId").asText())
                    .isGreaterThanOrEqualTo(0.85);
            assertThat(precision).as("Precision@5 for scenario " + scenario.get("scenarioId").asText())
                    .isGreaterThanOrEqualTo(0.80);
            assertThat(recall).as("Recall@5 for scenario " + scenario.get("scenarioId").asText())
                    .isGreaterThanOrEqualTo(0.75);
        }
    }

    private record ScoredJob(String id, double score, double groundTruth) {}
}
