package com.personal.jobagent.jobs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankingMetricsTest {

    @Test
    void ndcgAtKWithPerfectRankingYieldsOne() {
        List<Double> perfect = List.of(3.0, 3.0, 2.0, 1.0, 0.0);
        double ndcg = RankingMetrics.ndcgAtK(perfect, 5);
        assertThat(ndcg).isCloseTo(1.0, within(0.001));
    }

    @Test
    void ndcgAtKWithInvertedRankingYieldsSuboptimal() {
        List<Double> inverted = List.of(0.0, 1.0, 2.0, 3.0);
        double ndcg = RankingMetrics.ndcgAtK(inverted, 4);
        assertThat(ndcg).isLessThan(0.70);
        assertThat(ndcg).isGreaterThan(0.0);
    }

    @Test
    void ndcgAtKWithEmptyOrZeroScores() {
        assertThat(RankingMetrics.ndcgAtK(List.of(), 5)).isEqualTo(1.0);
        assertThat(RankingMetrics.ndcgAtK(List.of(0.0, 0.0, 0.0), 3)).isEqualTo(1.0);
    }

    @Test
    void precisionAtKCalculatesCorrectRatio() {
        List<Boolean> relevance = List.of(true, true, false, true, false);
        double p3 = RankingMetrics.precisionAtK(relevance, 3);
        assertThat(p3).isCloseTo(2.0 / 3.0, within(0.001));

        double p5 = RankingMetrics.precisionAtK(relevance, 5);
        assertThat(p5).isCloseTo(3.0 / 5.0, within(0.001));
    }

    @Test
    void recallAtKCalculatesCoverage() {
        List<Boolean> relevance = List.of(true, false, true);
        // 2 found out of 4 total relevant in dataset
        double r3 = RankingMetrics.recallAtK(relevance, 4, 3);
        assertThat(r3).isCloseTo(0.50, within(0.001));
    }
}
