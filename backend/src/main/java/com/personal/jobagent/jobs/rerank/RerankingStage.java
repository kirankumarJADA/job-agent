package com.personal.jobagent.jobs.rerank;

/**
 * Stages in the multi-stage job reranking pipeline.
 */
public enum RerankingStage {
    STAGE_1_HARD_ELIGIBILITY,
    STAGE_2_SKILLS_OVERLAP,
    STAGE_3_SENIORITY_ALIGNMENT,
    STAGE_4_TITLE_FIT,
    STAGE_5_HOLISTIC_SYNTHESIS
}
