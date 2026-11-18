# Agent 2 (Matching & Ranking Intelligence) Deliverable Report

## Executive Summary
Agent 2 has completed all Wave 1 deliverables for Matching & Ranking Intelligence:
1. **Title Synonym Registry & Positive Expansion:** Curated, deterministic title equivalence across 10 core engineering and tech families, wired into `HardFilterService` (positive matching) and `JobRepository.findFeed` (feed query expansion). Synonyms never widen hard exclusions.
2. **Deterministic Evaluation Harness & Synthetic Dataset:** Added `RankingMetrics` (nDCG@K, Precision@K, Recall@K), synthetic ranking benchmark dataset with 10 graded jobs across software archetypes, and automated quality gates in `RankingEvaluationTest` and `RankingMetricsTest`.
3. **P2 Fix — Shared Catalogue Row Mutation:** Added migration `V057__job_matches_filtered_out.sql` allowing `'FILTERED_OUT'` recommendation in `job_matches`. Refactored `ApplicationPipelineService.persistFilterRejection` to store candidate-scoped rejections without mutating the shared `jobs` catalogue row.
4. **Phase 23 Exit Criterion — Hard Eligibility Bypass Prevention:** Added hard filter re-evaluation inside `ApplicationPipelineService.createApplicationFromMatch`. Stale or replayed events cannot bypass active candidate preferences to create unauthorized applications.

---

## Changed Files & Deliverables
- [`backend/src/main/resources/db/migration/V057__job_matches_filtered_out.sql`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/main/resources/db/migration/V057__job_matches_filtered_out.sql)
- [`backend/src/main/java/com/personal/jobagent/jobs/TitleSynonymRegistry.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/main/java/com/personal/jobagent/jobs/TitleSynonymRegistry.java)
- [`backend/src/main/java/com/personal/jobagent/jobs/RankingMetrics.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/main/java/com/personal/jobagent/jobs/RankingMetrics.java)
- [`backend/src/main/java/com/personal/jobagent/jobs/HardFilterService.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/main/java/com/personal/jobagent/jobs/HardFilterService.java)
- [`backend/src/main/java/com/personal/jobagent/jobs/JobRepository.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/main/java/com/personal/jobagent/jobs/JobRepository.java)
- [`backend/src/main/java/com/personal/jobagent/application/ApplicationPipelineService.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/main/java/com/personal/jobagent/application/ApplicationPipelineService.java)
- [`backend/src/test/resources/ranking/synthetic_ranking_dataset.json`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/test/resources/ranking/synthetic_ranking_dataset.json)
- [`backend/src/test/java/com/personal/jobagent/jobs/TitleSynonymRegistryTest.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/test/java/com/personal/jobagent/jobs/TitleSynonymRegistryTest.java)
- [`backend/src/test/java/com/personal/jobagent/jobs/RankingMetricsTest.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/test/java/com/personal/jobagent/jobs/RankingMetricsTest.java)
- [`backend/src/test/java/com/personal/jobagent/jobs/RankingEvaluationTest.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/test/java/com/personal/jobagent/jobs/RankingEvaluationTest.java)
- [`backend/src/test/java/com/personal/jobagent/jobs/HardEligibilityAdversarialTest.java`](file:///C:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/agent2-matching-44fa55/backend/src/test/java/com/personal/jobagent/jobs/HardEligibilityAdversarialTest.java)

---

## Verification Evidence
- **Jobs & Preferences Unit Suites:** 127 tests run, 0 failures, 0 errors.
- **Agent 2 Specific Tests:** 54 tests run (`TitleSynonymRegistryTest`, `HardFilterServiceTest`, `RankingMetricsTest`, `RankingEvaluationTest`, `HardEligibilityAdversarialTest`), 0 failures.
- **Pipeline Integration Regression:** `ApplicationPipelineIT` 9 tests run, 0 failures.
