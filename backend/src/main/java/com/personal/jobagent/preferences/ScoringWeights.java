package com.personal.jobagent.preferences;

import java.util.Map;
import java.util.Set;

/**
 * Validates the scoring_weights jsonb shape per architecture doc §A3.5:
 * "weights are a single configurable vector that must sum to 100,
 * validated at load-time." V002's seed data already satisfies this
 * (skill 30 / experience 15 / visa 20 / location 10 / salary 10 /
 * career 10 / difficulty 5 = 100) — this class is what makes that
 * guarantee enforced at the API layer too, not just true by accident of
 * the seed data.
 */
public final class ScoringWeights {

    public static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "skill", "experience", "visa", "location", "salary", "career", "difficulty");

    private ScoringWeights() {
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    /**
     * @throws ValidationException with a specific, user-facing message if
     *         the weights are missing a category, have an unknown extra
     *         category, contain a negative value, or don't sum to 100.
     */
    public static void validate(Map<String, Object> weights) {
        if (weights == null) {
            throw new ValidationException("scoringWeights is required");
        }
        if (!weights.keySet().equals(REQUIRED_CATEGORIES)) {
            throw new ValidationException(
                    "scoringWeights must contain exactly these categories: " + REQUIRED_CATEGORIES
                            + ", got: " + weights.keySet());
        }

        int sum = 0;
        for (String category : REQUIRED_CATEGORIES) {
            Object value = weights.get(category);
            if (!(value instanceof Number number)) {
                throw new ValidationException("scoringWeights." + category + " must be a number");
            }
            int intValue = number.intValue();
            if (intValue < 0) {
                throw new ValidationException("scoringWeights." + category + " must not be negative");
            }
            sum += intValue;
        }

        if (sum != 100) {
            throw new ValidationException("scoringWeights must sum to 100, got " + sum);
        }
    }
}
