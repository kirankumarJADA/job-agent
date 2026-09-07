package com.personal.jobagent.benchmark;

/**
 * Phase 1 implements two of the four graders described in architecture
 * doc §C6 (exact_match, contains_any). json_schema_conformant and
 * evidence_grounded are deferred — the former needs a JSON Schema
 * validation library not currently a dependency, the latter needs the
 * sponsorship/analysis evidence-citation machinery that doesn't exist
 * until Phase 3. Both are straightforward additions once needed, not
 * architectural gaps.
 */
public interface Grader {

    String name();

    /** @return a score in [0.0, 1.0]; Phase 1's two graders are binary (0 or 1). */
    double grade(String actualOutput, String expected);

    static Grader forName(String name) {
        return switch (name) {
            case "exact_match" -> new ExactMatchGrader();
            case "contains_any" -> new ContainsAnyGrader();
            default -> throw new IllegalArgumentException("Unknown grader: " + name
                    + " (Phase 1 supports exact_match, contains_any only)");
        };
    }

    class ExactMatchGrader implements Grader {
        @Override
        public String name() {
            return "exact_match";
        }

        @Override
        public double grade(String actualOutput, String expected) {
            if (actualOutput == null || expected == null) {
                return 0.0;
            }
            return actualOutput.trim().equalsIgnoreCase(expected.trim()) ? 1.0 : 0.0;
        }
    }

    /** expected is a comma-separated list of acceptable substrings; any match scores 1.0. */
    class ContainsAnyGrader implements Grader {
        @Override
        public String name() {
            return "contains_any";
        }

        @Override
        public double grade(String actualOutput, String expected) {
            if (actualOutput == null || expected == null) {
                return 0.0;
            }
            String lowerActual = actualOutput.toLowerCase();
            for (String option : expected.split(",")) {
                if (lowerActual.contains(option.trim().toLowerCase())) {
                    return 1.0;
                }
            }
            return 0.0;
        }
    }
}
