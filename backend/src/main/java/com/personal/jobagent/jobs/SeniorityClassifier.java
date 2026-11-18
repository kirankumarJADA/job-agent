package com.personal.jobagent.jobs;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic classifier and alignment scorer for candidate and job seniority.
 * <p>
 * Maps standard seniority tiers into an ordinal scale:
 * 0: INTERN
 * 1: ENTRY / JUNIOR / GRADUATE
 * 2: MID / ASSOCIATE
 * 3: SENIOR
 * 4: LEAD / STAFF
 * 5: PRINCIPAL / HEAD
 * 6: EXECUTIVE / DIRECTOR / VP
 */
public final class SeniorityClassifier {

    public enum Level {
        INTERN(0),
        ENTRY(1),
        MID(2),
        SENIOR(3),
        LEAD(4),
        PRINCIPAL(5),
        EXECUTIVE(6);

        private final int rank;

        Level(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    private static final Pattern INTERN_PATTERN = Pattern.compile(
            "\\b(intern|internship|trainee|apprentice)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "\\b(junior|jr|entry|graduate|grad|associate engineer|entry level)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENIOR_PATTERN = Pattern.compile(
            "\\b(senior|sr|snr|experienced)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAD_PATTERN = Pattern.compile(
            "\\b(lead|tech lead|team lead|staff|staff engineer)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRINCIPAL_PATTERN = Pattern.compile(
            "\\b(principal|distinguished|fellow|head of)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXECUTIVE_PATTERN = Pattern.compile(
            "\\b(director|vp|vice president|chief|cto|c-level)\\b", Pattern.CASE_INSENSITIVE);

    private SeniorityClassifier() {}

    /**
     * Resolves the seniority level of a job from its explicit level or title text.
     */
    public static Level classifyJob(String explicitLevel, String title) {
        if (explicitLevel != null && !explicitLevel.isBlank()) {
            Level fromExplicit = parseLevel(explicitLevel);
            if (fromExplicit != null) {
                return fromExplicit;
            }
        }
        if (title != null && !title.isBlank()) {
            Level fromTitle = classifyText(title);
            if (fromTitle != null) {
                return fromTitle;
            }
        }
        return Level.MID; // default neutral assumption
    }

    /**
     * Parses an explicit seniority code string into a Level.
     */
    public static Level parseLevel(String s) {
        if (s == null || s.isBlank()) return null;
        String clean = s.trim().toUpperCase(Locale.ROOT);
        return switch (clean) {
            case "INTERN", "INTERNSHIP" -> Level.INTERN;
            case "ENTRY", "JUNIOR", "GRADUATE", "GRAD", "JR" -> Level.ENTRY;
            case "MID", "MID_LEVEL", "ASSOCIATE", "INTERMEDIATE" -> Level.MID;
            case "SENIOR", "SR", "SNR" -> Level.SENIOR;
            case "LEAD", "STAFF" -> Level.LEAD;
            case "PRINCIPAL", "DISTINGUISHED", "HEAD" -> Level.PRINCIPAL;
            case "EXECUTIVE", "DIRECTOR", "VP" -> Level.EXECUTIVE;
            default -> classifyText(clean);
        };
    }

    /**
     * Classifies free text (like job title) for seniority indicators.
     */
    public static Level classifyText(String text) {
        if (text == null || text.isBlank()) return null;
        if (EXECUTIVE_PATTERN.matcher(text).find()) return Level.EXECUTIVE;
        if (PRINCIPAL_PATTERN.matcher(text).find()) return Level.PRINCIPAL;
        if (LEAD_PATTERN.matcher(text).find()) return Level.LEAD;
        if (SENIOR_PATTERN.matcher(text).find()) return Level.SENIOR;
        if (ENTRY_PATTERN.matcher(text).find()) return Level.ENTRY;
        if (INTERN_PATTERN.matcher(text).find()) return Level.INTERN;
        return null;
    }

    /**
     * Calculates alignment score (0.0 to 1.0) between a job's seniority and candidate's allowed levels.
     * Distance weighting:
     * - 0 distance (exact match): 1.0
     * - 1 distance (e.g. Mid vs Senior): 0.75
     * - 2 distance: 0.35
     * - 3+ distance: 0.05
     */
    public static double computeAlignmentScore(Level jobLevel, List<String> candidateAllowedLevels) {
        if (candidateAllowedLevels == null || candidateAllowedLevels.isEmpty()) {
            return 1.0; // Unconstrained
        }
        int minDistance = Integer.MAX_VALUE;
        for (String allowed : candidateAllowedLevels) {
            Level candidateLevel = parseLevel(allowed);
            if (candidateLevel != null) {
                int dist = Math.abs(jobLevel.rank() - candidateLevel.rank());
                if (dist < minDistance) {
                    minDistance = dist;
                }
            }
        }
        if (minDistance == Integer.MAX_VALUE) {
            return 0.5; // Unknown candidate constraints
        }
        return switch (minDistance) {
            case 0 -> 1.0;
            case 1 -> 0.75;
            case 2 -> 0.35;
            default -> 0.05;
        };
    }
}
