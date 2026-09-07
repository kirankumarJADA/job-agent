package com.personal.jobagent.preferences;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringWeightsTest {

    @Test
    void validWeightsSummingTo100Passes() {
        Map<String, Object> weights = Map.of(
                "skill", 30,
                "experience", 15,
                "visa", 20,
                "location", 10,
                "salary", 10,
                "career", 10,
                "difficulty", 5
        );

        assertThatCode(() -> ScoringWeights.validate(weights)).doesNotThrowAnyException();
    }

    @Test
    void missingCategoryThrows() {
        Map<String, Object> weights = Map.of(
                "skill", 35,
                "experience", 15,
                "visa", 20,
                "location", 10,
                "salary", 10,
                "career", 10
                // missing difficulty
        );

        assertThatThrownBy(() -> ScoringWeights.validate(weights))
                .isInstanceOf(ScoringWeights.ValidationException.class)
                .hasMessageContaining("must contain exactly these categories");
    }

    @Test
    void sumNot100Throws() {
        Map<String, Object> weights = Map.of(
                "skill", 30,
                "experience", 15,
                "visa", 20,
                "location", 10,
                "salary", 10,
                "career", 10,
                "difficulty", 4 // sum = 99
        );

        assertThatThrownBy(() -> ScoringWeights.validate(weights))
                .isInstanceOf(ScoringWeights.ValidationException.class)
                .hasMessageContaining("must sum to 100, got 99");
    }

    @Test
    void negativeWeightThrows() {
        Map<String, Object> weights = Map.of(
                "skill", 35,
                "experience", 15,
                "visa", 20,
                "location", 10,
                "salary", 15,
                "career", 10,
                "difficulty", -5 // sum = 100 but negative
        );

        assertThatThrownBy(() -> ScoringWeights.validate(weights))
                .isInstanceOf(ScoringWeights.ValidationException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void nullThrows() {
        assertThatThrownBy(() -> ScoringWeights.validate(null))
                .isInstanceOf(ScoringWeights.ValidationException.class)
                .hasMessageContaining("scoringWeights is required");
    }
}
