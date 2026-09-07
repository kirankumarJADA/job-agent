package com.personal.jobagent.llm;

/**
 * Resolves the PHASE1-BLUEPRINT.md open decision on TaskType values.
 * Matches the four datasets/benchmarks/ folders already scaffolded in
 * P1-a (job_classification, sponsorship_analysis, skill_matching,
 * email_classification) plus CV_TAILORING for Phase 5 — included now so
 * the enum doesn't need a breaking change later, even though nothing
 * routes to it until Phase 5.
 */
public enum TaskType {
    JOB_CLASSIFICATION,
    SPONSORSHIP_ANALYSIS,
    SKILL_MATCHING,
    CV_TAILORING,
    EMAIL_CLASSIFICATION
}
