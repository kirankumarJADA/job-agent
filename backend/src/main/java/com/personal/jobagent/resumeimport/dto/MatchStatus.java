package com.personal.jobagent.resumeimport.dto;

/** How an extracted item compares against the candidate's existing Master Profile. */
public enum MatchStatus {
    /** No matching existing entry — a genuinely new record. */
    NEW,
    /** An equivalent entry is already present; importing it again would duplicate it. */
    EXISTING,
    /** Similar to an existing entry but not confidently identical (e.g. same employer, different title). */
    POSSIBLE_DUPLICATE,
    /** Matches an existing entry's identity but at least one field differs. */
    CONFLICT,
    /** Not enough information to classify (e.g. no dates to compare) — needs a human look. */
    NEEDS_REVIEW
}
