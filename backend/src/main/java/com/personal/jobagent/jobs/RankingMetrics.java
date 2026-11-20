package com.personal.jobagent.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, deterministic information retrieval evaluation metrics (nDCG@K, Precision@K, Recall@K).
 * Used for offline and synthetic evaluation of ranking and matching pipelines.
 */
public final class RankingMetrics {

    private RankingMetrics() {
    }

    /**
     * Normalized Discounted Cumulative Gain at K (nDCG@K).
     *
     * @param gradedRelevance list of graded relevance scores (e.g. 0 to 3) in ranked order
     * @param k rank cutoff (default typically 10)
     * @return nDCG@K between 0.0 and 1.0 (1.0 if ideal ranking or empty)
     */
    public static double ndcgAtK(List<Double> gradedRelevance, int k) {
        if (gradedRelevance == null || gradedRelevance.isEmpty() || k <= 0) {
            return 1.0;
        }
        int effectiveK = Math.min(k, gradedRelevance.size());
        double dcg = dcg(gradedRelevance, effectiveK);

        List<Double> ideal = new ArrayList<>(gradedRelevance);
        ideal.sort(Collections.reverseOrder());
        double idcg = dcg(ideal, effectiveK);

        if (idcg == 0.0) {
            return dcg == 0.0 ? 1.0 : 0.0;
        }
        return dcg / idcg;
    }

    private static double dcg(List<Double> scores, int k) {
        double dcg = 0.0;
        for (int i = 0; i < k; i++) {
            double rel = scores.get(i);
            if (rel > 0) {
                // (2^rel - 1) / log2(i + 2)
                dcg += (Math.pow(2.0, rel) - 1.0) / (Math.log(i + 2.0) / Math.log(2.0));
            }
        }
        return dcg;
    }

    /**
     * Precision@K: fraction of top-K recommendations that are relevant.
     *
     * @param relevance binary relevance indicator for each ranked item
     * @param k rank cutoff
     * @return precision at K between 0.0 and 1.0
     */
    public static double precisionAtK(List<Boolean> relevance, int k) {
        if (relevance == null || relevance.isEmpty() || k <= 0) {
            return 0.0;
        }
        int effectiveK = Math.min(k, relevance.size());
        long relevantCount = 0;
        for (int i = 0; i < effectiveK; i++) {
            if (Boolean.TRUE.equals(relevance.get(i))) {
                relevantCount++;
            }
        }
        return (double) relevantCount / k;
    }

    /**
     * Recall@K: fraction of all relevant items found in the top-K.
     *
     * @param relevance binary relevance indicator for each ranked item
     * @param totalRelevant total number of relevant items in the candidate pool
     * @param k rank cutoff
     * @return recall at K between 0.0 and 1.0
     */
    public static double recallAtK(List<Boolean> relevance, int totalRelevant, int k) {
        if (relevance == null || relevance.isEmpty() || totalRelevant <= 0 || k <= 0) {
            return 0.0;
        }
        int effectiveK = Math.min(k, relevance.size());
        long relevantFound = 0;
        for (int i = 0; i < effectiveK; i++) {
            if (Boolean.TRUE.equals(relevance.get(i))) {
                relevantFound++;
            }
        }
        return Math.min(1.0, (double) relevantFound / totalRelevant);
    }

    /**
     * Mean Reciprocal Rank (MRR): 1 / rank of the first relevant item (1-indexed).
     *
     * @param relevance binary relevance indicator for ranked items
     * @return reciprocal rank (0.0 if no item is relevant)
     */
    public static double reciprocalRank(List<Boolean> relevance) {
        if (relevance == null || relevance.isEmpty()) {
            return 0.0;
        }
        for (int i = 0; i < relevance.size(); i++) {
            if (Boolean.TRUE.equals(relevance.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
