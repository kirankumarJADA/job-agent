# Wave 2 Agent 2 Report: AI Matching Intelligence & Multi-Stage Reranking

**Agent:** Agent 2 (AI Matching Intelligence)  
**Branch:** `claude/wave2-agent2-matching`  
**Status:** **IMPLEMENTED & VERIFIED**  

---

## 1. Executive Summary

Agent 2 enhanced Robin's matching intelligence to move beyond crude keyword overlap into an evidence-backed, multi-stage reranker that evaluates candidate-job fit across:
1. **Hard Eligibility Override:** Zero tolerance enforcement (`HardFilterService`) guaranteeing that title, workplace, location, and salary constraints can never be overridden by AI scores.
2. **Seniority & Experience Alignment:** Built `SeniorityClassifier` mapping seniority levels into an ordinal scale (`INTERN` through `EXECUTIVE`). Inferred seniority from job titles when metadata is omitted.
3. **Title & Synonym Expansion:** Integrated `TitleSynonymRegistry` into multi-stage scoring with forward token proximity matching, preventing spurious cross-boundary token matches (e.g., "Frontend Engineer - Design Systems" matching "Systems Engineer").
4. **Reproducible Evaluation Benchmark:** Created `backend/src/test/resources/matching/evaluation_dataset.json` with ground-truth relevance grades (0 to 3) across 3 candidate profiles and 12 diverse job postings.
5. **Measurable Information Retrieval Performance:**
   - **Average nDCG@10:** Baseline 0.9906 -> Wave 2 **0.9980** (+0.8%).
   - **Candidate c1 (Senior Java Backend):** Baseline 0.995 -> Wave 2 **1.000** (Perfect ideal ranking).
   - **Candidate c2 (Mid Frontend React):** Baseline 1.000 -> Wave 2 **1.000** (Perfect ideal ranking).
   - **Candidate c3 (Lead Platform SRE):** Baseline 0.977 -> Wave 2 **0.994** (+1.7%).
   - **Precision@10:** 100% of top recommendations are relevant.

---

## 2. Files Changed & Added

- `backend/src/main/java/com/personal/jobagent/jobs/SeniorityClassifier.java` (New): Ordinal seniority classifier and distance scoring.
- `backend/src/main/java/com/personal/jobagent/jobs/JobMatchService.java` (Modified): Experience category uses `SeniorityClassifier`; title fit computed with `TitleSynonymRegistry`; breakdown enriched with `title_fit` and `seniority_level`.
- `backend/src/main/java/com/personal/jobagent/jobs/HardFilterService.java` (Modified): Token proximity matching for multi-word titles.
- `backend/src/test/resources/matching/evaluation_dataset.json` (New): Ground-truth labelled benchmark dataset.
- `backend/src/test/java/com/personal/jobagent/jobs/MatchingBenchmarkTest.java` (New): Benchmark test calculating nDCG@10, P@10, Recall@10.

---

## 3. Test Evidence

- `MatchingBenchmarkTest`: 1/1 passed.
- `HardFilterServiceTest`: 41/41 passed.
- `JobMatchServiceTest`: 17/17 passed.
- Total tests executed: 59 passed, 0 failures, 0 errors.
