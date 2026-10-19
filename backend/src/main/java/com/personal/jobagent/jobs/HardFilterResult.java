package com.personal.jobagent.jobs;

import java.util.List;

/**
 * Outcome of running every hard filter against a single job.
 *
 * <p>When {@code passed} is false, {@code reasons} contains one entry per
 * criterion that rejected the job — each with a machine-readable
 * {@code criterion} tag, what the user asked for ({@code expected}),
 * what the job offered ({@code actual}), and a short human sentence
 * ({@code message}). The list is deterministic (alphabetical by criterion)
 * so tests and logs stay stable.
 *
 * <p>Hard filters are ABSOLUTE: if any criterion rejects, the job is
 * filtered out before scoring. Semantic matching must never override
 * a hard rejection.
 */
public record HardFilterResult(boolean passed, List<FilterReason> reasons) {

    public static HardFilterResult pass() {
        return new HardFilterResult(true, List.of());
    }

    public static HardFilterResult reject(List<FilterReason> reasons) {
        return new HardFilterResult(false, List.copyOf(reasons));
    }

    /**
     * One reason a hard filter rejected a job.
     *
     * @param criterion  machine-readable tag, e.g. "LOCATION", "SALARY_MIN"
     * @param expected   what the user's preference demanded (display string)
     * @param actual     what the job offered (display string, may be "unknown")
     * @param message    short human-readable explanation
     */
    public record FilterReason(String criterion, String expected, String actual, String message) {}
}
