# Phase 2A Remediation: Multi-Stage Ranking Persistence & Notification Gate Verification

## 1. Executive Summary & Root Cause Analysis

### 1.1 The Baseline Defect (P1-1)
In the Phase 2 baseline ([`fb3f606`](https://github.com/kirankumarJADA/job-agent/commit/fb3f606)), `JobMatchService.evaluateMatch()` invoked `multiStageReranker.rerank()`, but only attached the result to the diagnostic `breakdown` JSON. The columns that actually govern candidate actions and database state (`job_matches.score` and `job_matches.recommendation`), as well as outbound notification triggers, relied entirely on the legacy 1-stage calculation:

```java
// DEFECTIVE BASELINE IMPLEMENTATION (fb3f606)
int overall = weightedOverall(categories);
String recommendation = overall >= APPLY_THRESHOLD ? "APPLY"
        : overall >= REVIEW_THRESHOLD ? "REVIEW" : "SKIP";

RerankResult rerankResult = multiStageReranker != null
        ? multiStageReranker.rerank(job, profileId, preferences)
        : null;

// Reranker results were IGNORED for the actual columns:
jdbcTemplate.update("""
        insert into job_matches (profile_id, job_id, score, recommendation, breakdown, scored_at)
        values (?, ?, ?, ?, ?::jsonb, now())
        """, profileId, jobId, overall, recommendation, breakdownJson(...));

if (!"APPLY".equals(recommendation)) { // Using legacy recommendation!
    return false;
}
```

### 1.2 Consequence
A job that was completely disqualified by Stage 1 of the `MultiStageReranker` (such as a "Registered General Nurse" position evaluated against a Software Engineer candidate with matching secondary keywords) was still assigned a high score and `APPLY` recommendation, triggering an errant `JOB_MATCHED` notification event.

---

## 2. Remediated Architecture & Implementation

### 2.1 Authoritative Score & Gate Enforcement ([`JobMatchService.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/main/java/com/personal/jobagent/jobs/JobMatchService.java))
The multi-stage reranker is now established as the authoritative decision-maker:

```java
// Authoritative decision: when MultiStageReranker is available, its multi-stage
// evaluation is authoritative for score, recommendation, and eligibility.
// An absolutely ineligible job (Stage 1 failed) is strictly forced to score 0 and SKIP.
final int finalScore;
final String finalRecommendation;
if (rerankResult != null) {
    if (!rerankResult.eligible()) {
        finalScore = 0;
        finalRecommendation = "SKIP";
    } else {
        finalScore = rerankResult.overallScore();
        finalRecommendation = rerankResult.recommendation();
    }
} else {
    finalScore = overall;
    finalRecommendation = recommendation;
}
```

### 2.2 Strict Outbox & Notification Gating
The transactional outbox emission is tied directly to `finalRecommendation`:
```java
// Persist authoritative score and recommendation
jdbcTemplate.update("""
        insert into job_matches (profile_id, job_id, score, recommendation, breakdown, scored_at)
        values (?, ?, ?, ?, ?::jsonb, now())
        on conflict (profile_id, job_id) do update set
            score = excluded.score,
            recommendation = excluded.recommendation,
            breakdown = excluded.breakdown,
            scored_at = now()
        """,
        profileId, jobId, finalScore, finalRecommendation,
        breakdownJson(job, finalScore, finalRecommendation, ...));

// Notification ONLY if the authoritative recommendation is APPLY
if (!"APPLY".equals(finalRecommendation)) {
    return false;
}

Map<String, Object> payload = new HashMap<>();
payload.put("job_id", jobId.toString());
payload.put("profile_id", profileId.toString());
payload.put("job_title", job.title());
payload.put("company", job.companyNameRaw() != null ? job.companyNameRaw() : "");
payload.put("score", finalScore);
payload.put("recommendation", finalRecommendation);
...
notificationService.emit(new NotificationService.NotificationCommand(
        NotificationEvents.JOB_MATCHED, "JOB", jobId, payload, UuidV7.generate(), null));
```

---

## 3. Verification & Test Evidence

### 3.1 Integration Tests with Testcontainers PostgreSQL 16 ([`JobMatchServiceRankingPersistenceIT.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/test/java/com/personal/jobagent/jobs/JobMatchServiceRankingPersistenceIT.java))

1. **`testIneligibleJobPersistsZeroScoreAndSkip`**:
   - Candidate has profile skills "Java" and "PostgreSQL", with target role "Senior Backend Engineer".
   - Job is posted as "Registered General Nurse" at "NHS Trust" mentioning "Java" and "PostgreSQL".
   - Multi-stage reranker Stage 1 Hard Eligibility Gate disqualifies the posting (`eligible = false`).
   - **Verification:**
     - `result.overall() == 0`
     - `result.recommendation() == "SKIP"`
     - `result.notified() == false`
     - Database assertion on `job_matches`: `score == 0` and `recommendation == "SKIP"`.
     - `verify(notificationService, never()).emit(any())` confirms zero notifications emitted.

2. **`testEligibleJobPersistsAuthoritativeScoreAndNotifies`**:
   - Candidate has skills "Java" and "Spring Boot", target role "Senior Backend Engineer".
   - Job is posted as "Senior Backend Engineer" at "Stripe" with matching criteria.
   - Multi-stage reranker evaluates job as eligible and scores >= 70.
   - **Verification:**
     - `result.overall() >= 70`
     - `result.recommendation() == "APPLY"`
     - `result.notified() == true`
     - Database assertion on `job_matches`: `score == result.overall()` and `recommendation == "APPLY"`.
     - `verify(notificationService).emit(...)` confirms `JOB_MATCHED` event emitted with payload containing authoritative score and recommendation.

### 3.2 Unit Tests ([`JobMatchServiceTest.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/test/java/com/personal/jobagent/jobs/JobMatchServiceTest.java))
1. **`multiStageRerankerDisqualifiesIneligibleJobToScoreZeroAndSkip`**:
   - Mocks `MultiStageReranker` returning `eligible = false`.
   - Confirms `evaluateMatch` forces `score = 0`, `recommendation = "SKIP"`, and suppresses notification.
2. **`multiStageRerankerOverridesWithAuthoritativeScoreAndRecommendation`**:
   - Mocks `MultiStageReranker` returning `eligible = true`, `overall = 88`, `recommendation = "APPLY"`.
   - Confirms `evaluateMatch` returns `88`, `"APPLY"`, emits notification, and writes `88` to `job_matches`.
