package com.personal.jobagent.jobs.rerank;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of the multi-stage reranking pipeline.
 */
public record RerankResult(
        UUID jobId,
        String title,
        boolean eligible,
        int overallScore,
        String recommendation,
        Map<String, Double> stageScores,
        List<String> matchedSkills,
        List<String> missingSkills,
        List<String> highlights,
        List<String> concerns,
        String narrativeWhy,
        String decisionSummary
) {}
