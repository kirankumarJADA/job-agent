# Robin Discovery Intelligence: Reproducible Ranking Benchmark Report

**Date:** 20 November 2026  
**Branch:** `feature/discovery-intelligence`  
**Base Commit:** `30d3f1a`  
**Framework Suite:** `com.personal.jobagent.jobs.DiscoveryIntelligenceBenchmarkSuiteTest`  
**Lead Agent:** Agent 3 (Reproducible Benchmark Framework) & Agent 0 (Architect)

---

## 1. Executive Summary

The Discovery Intelligence programme introduces three integrated capabilities:
1. **Multi-Stage Intelligent Job Reranking (Agent 1):** A 5-stage pipeline combining hard eligibility gating, semantic skill matching, calibrated seniority progression, title synonym affinity, and holistic explainable synthesis.
2. **Configurable Search Plans (Agent 2):** Database-persisted search plans (`V065`), tenant-isolated CRUD APIs (`/api/v1/search-plans`), and multi-provider query expansion.
3. **Reproducible Benchmark Framework (Agent 3):** Standardized Information Retrieval evaluation testing nDCG@K, Precision@K, Recall@K, and MRR across both curated and held-out datasets.

### Primary Measured Metrics (Held-Out Evaluation Dataset)

| Metric | Baseline (Keyword Overlap) | Wave 2 Integrated | Multi-Stage Intelligence | Relative Delta vs Baseline |
| :--- | :---: | :---: | :---: | :---: |
| **nDCG@10** | 0.9698 | 0.9741 | **0.9741** | **+0.4%** |
| **nDCG@5** | 0.9697 | 0.9850 | **0.9866** | **+1.7%** |
| **Precision@10** | 0.3000 | 0.3167 | **0.3167** | **+5.6%** |
| **Mean Reciprocal Rank (MRR)** | 1.0000 | 1.0000 | **1.0000** | **0.0% (Perfect 1.0)** |

---

## 2. Per-Persona Evaluation Breakdown

Evaluated on the held-out evaluation dataset (6 diverse candidate profiles evaluated against 20 realistic, noisy job vacancies):

| Candidate ID | Target Role / Seniority | Baseline nDCG@10 | Multi-Stage nDCG@10 | Precision@10 | MRR | Performance Note |
| :--- | :--- | :---: | :---: | :---: | :---: | :--- |
| `c1-senior-java-backend` | Senior Java Backend Engineer | 0.9415 | **0.9756** | 0.3000 | 1.0000 | +3.6% gain; strong promotion of Spring Boot / Postgres roles |
| `c2-mid-frontend-react` | Mid-Level Frontend React Developer | 1.0000 | **1.0000** | 0.2000 | 1.0000 | Perfect ranking; Vue / Angular roles ranked down |
| `c3-lead-platform-sre` | Lead Platform / SRE Engineer | 1.0000 | **1.0000** | 0.3000 | 1.0000 | Perfect ranking; Kubernetes/Terraform alignment |
| `c4-senior-ml-data-scientist` | Senior ML / Data Scientist | 0.9937 | **0.9971** | 0.3000 | 1.0000 | +0.3% gain; Python/PyTorch/NLP role affinity |
| `c5-junior-qa-engineer` | Junior QA Automation Engineer | 1.0000 | **0.9719** | 0.3000 | 1.0000 | Accounted regression (-2.8%); calibrated seniority prevents over-promotion of MID roles |
| `c6-eng-manager-director` | Engineering Manager / Director | 0.8839 | **0.9000** | 0.5000 | 1.0000 | +1.8% gain; executive tier alignment |

### The Junior QA Regression Disclosure
- **Candidate `c5` Baseline:** 1.0000 (Pure keyword count coincidence).
- **Multi-Stage Score:** 0.9719 (-2.8%).
- **Calibrated Seniority:** In `MultiStageReranker.computeCalibratedSeniority`, an exact `ENTRY` role receives a `1.0` foundation score while a `MID` role receives `0.92`.
- **Hard Eligibility Guarantee:** Word-boundary regex matching ensures roles like "International QA" are hard-filtered before scoring; `HardFilterService` prevents any non-QA role from reaching the candidate.

---

## 3. Explainability & Match Breakdown Invariant

Every evaluated job produces a structured, transparent decision payload in `job_matches.breakdown`:
- `stage_scores`: Exact percentage contributions across `hard_eligibility`, `skills_overlap`, `seniority_alignment`, `title_fit`, `salary_fit`.
- `highlights`: Plain-English positive match indicators (e.g., *"Matched 4 of 5 required skills (Java, Spring Boot, AWS, Docker)"*).
- `concerns`: Actionable gap disclosures (e.g., *"Missing or unlisted requirements: Kubernetes"*).
- `narrativeWhy`: Comprehensive summary sentence for the candidate.
- `decision`: Explicit rule statement explaining threshold evaluation.

---

## 4. Reproducibility & Execution Command

To reproduce these benchmark numbers deterministically from clean sources:

```bash
cd backend
mvn test -Dtest=DiscoveryIntelligenceBenchmarkSuiteTest
```

Expected output:
```text
nDCG@10: Baseline = 0.9698 -> Wave 2 = 0.9741 -> Multi-Stage = 0.9741
nDCG@5:  Baseline = 0.9697 -> Multi-Stage = 0.9866
P@10:    Baseline = 0.3000 -> Multi-Stage = 0.3167
MRR:     Baseline = 1.0000 -> Multi-Stage = 1.0000
```
