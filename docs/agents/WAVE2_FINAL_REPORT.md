# Robin Wave 2: Competitive Product Development — Final Acceptance Report

**Role:** Agent 0 — Principal Architect & Multi-Agent Orchestrator  
**Branch:** `integration/wave-2`  
**Code Commit (Wave 2 integration):** `51961ba`  
**Documentation Commit at the 19 November gate:** `01c78fa` — verified documentation-only relative to `51961ba` (one file, `docs/agents/WAVE2_FINAL_REPORT.md`, +119/−218)  
**C1 + C2 resolution:** `f609e9fe8cfa726a14d0e809fc003c7160d4f553`; QA F1 test correction `7108e49748c1e86135d11e72928d0ce72cc79b04`; release candidate `ed01408..7108e49`; independent QA PASS  
**Review Status:** **UPDATED 20 NOVEMBER 2026 — C1 AND C2 RESOLVED; FULL VERIFICATION AND INDEPENDENT QA PASSED. NOT RELEASED: F9 IS AN OPEN RELEASE RISK. NOT MERGED, NOT PUSHED, NOT DEPLOYED.**  
**Verdict of record:** see `docs/ROBIN_WAVE2_RELEASE_READINESS.md`  

---

## 1. Executive Summary & Acceptance Scope

Robin Wave 2 transitioned the platform into a customer-facing, high-reliability AI job application product with deterministic ranking, comprehensive ATS execution plans, recruiter follow-up intelligence, and safe deletion guards.

This document records the Wave 2 acceptance evidence: reconciled test metrics (§2), the information-retrieval
benchmark including the Junior QA regression (§3), ATS verification evidence (§4), database compatibility,
measured locking behaviour and the recovery procedure (§5), and the independent QA sign-off with its scope
(§6).

Three claims from the previous revision were **withdrawn or corrected** at the final approval gate, each
documented in place:

- **PostgreSQL 17.11 compatibility** was previously inferred from an 18.4 rehearsal plus syntax inspection.
  `V001`–`V063` have now actually been executed against a 17.11 instance (§5.1).
- **Greenhouse was recorded as externally confirmed.** Nothing in this repository supports that, and the
  repository's own gap register says the opposite. The claim is withdrawn (§4).
- **The destructive down-migration script was removed** and replaced with a forward-fix recovery procedure
  (§5.3). The published script would have destroyed candidate data and named an index this release never
  creates.

One scope limit was also found: Agent 6's sign-off predates a production change to `BoardDiscoveryService`
that relaxes the safe-deletion guard in `MANUAL` mode and is not covered by any test (§6.1).

**Update, 20 November 2026.** Both conditions the gate set have since been met, in `f609e9f`. C1 reverted that
relaxation (Option B). C2 moved the `applications_follow_up_idx` build out of V063 into V064, a separate,
non-transactional `CREATE INDEX CONCURRENTLY` migration (Option C). A test-only follow-up, `7108e49`, corrected an
integration test that independent QA found had stopped testing anything. The candidate `ed01408..7108e49` passed
a full `mvn -B clean verify` (exit code 0), read-only validation against PostgreSQL 17.11, and two rounds of
independent QA (PASS); see §2.2. The recovery guidance in §5.3 has been corrected. One new risk, F9, remains
open, so this report does not record a release approval; see `docs/ROBIN_WAVE2_RELEASE_READINESS.md`.

---

## 2. Reconciled Unique Test Count (No Double-Counting)

To avoid double-counting benchmark or adversarial tests that execute as part of Maven Surefire, tests are broken down strictly by unique runner invocation:

| Runner / Test Suite | Scope & Inclusions | Unique Run | Passed | Failed | Errors | Skipped | Time |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Backend Surefire** | Unit, Adversarial (22) & Benchmarks (2) | 1,636 | **1,630** | **0** | **0** | 6 *(no local Redis — see §2.1)* | 2m 12s |
| **Backend Failsafe** | Integration tests (52 IT classes) | 327 | **320** | **0** | **0** | 7 *(external gates — see §2.1)* | 12m 02s |
| **Frontend Vitest** | UI components, state, hooks (44 files) | 439 | **439** | **0** | **0** | 0 | 23.6s |
| **Worker Suite** | Playwright ATS execution & runner | 262 | **262** | **0** | **0** | 0 | 46.96s |
| **Cloudflare Email Worker** | Inbound email parsing & webhooks | 17 | **17** | **0** | **0** | 0 | 0.18s |
| **TOTAL UNIQUE AUTOMATED TESTS** | **Exhaustive Unique Executions** | **2,681** | **2,668** | **0** | **0** | **13** | **~15m** |

**Independently re-executed at the final approval gate, 19 November 2026.** `mvn -B verify` on this tree
returned `BUILD SUCCESS`, exit code **0**, in 14:54. Surefire reported `tests=1636 failures=0 errors=0
skipped=6` and Failsafe `tests=327 failures=0 errors=0 skipped=7`, reproducing the table above exactly
(1636 − 6 = 1630 passed; 327 − 7 = 320 passed). The frontend, worker and Cloudflare email-worker suites were
re-run separately: 439/439 in 44 files, 262/262 and 17/17, each with 0 skipped. Grand total 2,681 unique
tests, 2,668 passed, 13 skipped, 0 failures, 0 errors.

*Reconciling "Adversarial (22)" with the 24 of §6:* the `com.personal.jobagent.adversarial` package holds 6
classes and **22** `@Test` methods. The seventh adversarial class, `HardEligibilityAdversarialTest`, lives in
`com.personal.jobagent.jobs` and adds **2**, giving the 24 that §6 reports. Both numbers are correct; they
count different scopes. `Benchmarks (2)` are the two matching benchmarks (`MatchingBenchmarkTest`,
`HeldoutMatchingBenchmarkTest`), one `@Test` each. The 52 IT classes were confirmed by file count.

### 2.1 The 13 skipped tests, named with their actual reasons

**Correction.** The previous revision described the 6 Surefire skips as "unit-isolated tests that explicitly
require external Docker/Testcontainers context". That is not the condition they test. All six are gated on a
**local Redis being reachable on `localhost:6379`**, and all six come from three classes:

| # | Skipped test | Reported reason |
| :--- | :--- | :--- |
| 1 | `RedisLettuceAbExperimentTest#abMatrixAgainstRealRedis` | `local Docker Redis on localhost:6379 is not reachable` |
| 2 | `RedisLettuceAbExperimentTest#failureControlsOnUnreachableTargets` | `local Docker Redis on localhost:6379 is not reachable` |
| 3 | `RedisConnectivityDiagnosticsTest#authenticatedProtocolComparisonRunsAllThreeModesAgainstDisposableRedis` | assumption not met (no local Redis) |
| 4 | `RedisConnectivityDiagnosticsTest#credentiallessFailureDoesNotPreventPasswordOnlyModeFromRunning` | assumption not met (no local Redis) |
| 5 | `RedisTlsBindingTest#sharedSpringFactoryAcquiresAndPingsAgainstLocalRedis` | assumption not met (no local Redis) |
| 6 | `RedisTlsBindingTest#customFactoryAcquiresAndPingsAgainstLocalRedis` | assumption not met (no local Redis) |

The 7 Failsafe skips are all class-level `@EnabledIfSystemProperty` gates. The previous revision attributed
them to "live API credentials (e.g. live Adzuna API keys)", which describes only four of them; the other three
are operator tools needing an **external database URL**, not an API key:

| # | Skipped test | Gate |
| :--- | :--- | :--- |
| 1 | `ExternalDatabaseActualReviewIT#processesActualRetainedElevenJobMatchedEventsUnderManualReviewGuard` | `-Drobin.migration.verify-actual-events.url` (operator tool) |
| 2 | `ExternalDatabaseCompatibilityIT#applicationDatabaseFeaturesWorkAgainstTheRestoredOracleCopy` | `-Drobin.compat.url` (operator tool) |
| 3 | `ExternalDatabaseFlywayIT#runsTheRequestedFlywayActionAgainstTheExternalDatabase` | `-Drobin.flyway.external.url` (operator tool) |
| 4 | `AdzunaLiveIngestionIT#aBoundedRealRunPersistsGenuineAdzunaRecordsThroughTheRealPipelineWithoutDuplicationOnRepeat` | `-Dlive.discovery=true` |
| 5 | `ArbeitnowLiveIngestionIT#aBoundedRealRunPersistsGenuineArbeitnowRecordsThroughTheRealPipelineWithoutDuplicationOnRepeat` | `-Dlive.discovery=true` |
| 6 | `FreeHireLiveIngestionIT#aBoundedRealRunPersistsGenuineFreeHireRecordsThroughTheRealPipelineWithoutDuplicationOnRepeat` | `-Dlive.discovery=true` |
| 7 | `PlatformLiveIT#liveBoardAnswersAndParses(String, String)` | `-Dlive.discovery=true` |

Every one of the 13 is an **environment gate, not a defect**: none is `@Disabled`, and none was skipped
because it fails. Note that the Failsafe skip count depends on Docker being available — with the Docker
daemon running, the Testcontainers integration tests execute and only these 7 externally gated tests skip.

### 2.2 Final verification of the release candidate (`7108e49`, 20 November 2026)

The tables above describe the Wave 2 code at `51961ba`. The release candidate `7108e49` was verified with one
complete `mvn -B clean verify` of the exact committed tree (commit `7108e49`, tree
`c60a3d3d3c6e50a2e32b9b2d4569da9dfa64876e`, clean working tree before and after the run):

| Runner | Tests | Failures | Errors | Skipped |
| :--- | :--- | :--- | :--- | :--- |
| Backend Surefire | 1,644 | 0 | 0 | 6 |
| Backend Failsafe | 327 | 0 | 0 | 7 |

- Maven exit code **0**, `BUILD SUCCESS`, total time **16m33s**. All **52 of 52** integration classes completed,
  and every report was written by that run.
- Surefire grew by eight tests: four C1 regression tests and four Flyway lock-guard tests. The skipped tests are
  the same 13 environment gates listed in §2.1.
- The read-only validation against disposable PostgreSQL 17.11 then passed 1/1 (`applied=63`, `current=064`,
  `pending=0`, `invalid=0`).
- The frontend, worker and email-worker suites (439, 262 and 17 tests) passed on 19 November against the same
  frontend and worker code; neither later commit touches them.
- Independent QA reviewed both commits and returned PASS.

---

## 3. Information Retrieval Benchmark & Ranking Analysis

Robin's multi-stage ranking pipeline combines:
1. `HardFilterService`: Word-boundary regex title matching (`\b<title>\b`), location bounds, and hard salary bounds.
2. `SeniorityClassifier`: 7-tier seniority hierarchy (`INTERN` through `EXECUTIVE`) with ordinal distance scoring.
3. `TitleSynonymRegistry`: Controlled synonym expansion and forward token proximity with max 1-token gap.
4. Weighted multi-stage scoring: Skills 45%, Title 25%, Seniority 20%, Salary Fit 10%.

### 3.1 Held-Out Dataset Performance (6 Personas, 20 Noisy Vacancies)
- Dataset: `backend/src/test/resources/matching/heldout_evaluation_dataset.json`
- Test: `backend/src/test/java/com/personal/jobagent/jobs/HeldoutMatchingBenchmarkTest.java`

| Persona ID | Candidate Profile | Baseline nDCG@10 | Wave 2 nDCG@10 | Δ nDCG | Baseline P@10 | Wave 2 P@10 | Δ P@10 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `c1` | Senior Java Backend Engineer | 0.9415 | **0.9756** | +3.6% | 0.3000 | 0.3000 | 0.0% |
| `c2` | Mid-Level Frontend React Developer | 1.0000 | **1.0000** | 0.0% | 0.2000 | 0.2000 | 0.0% |
| `c3` | Lead Platform / SRE Engineer | 1.0000 | **1.0000** | 0.0% | 0.3000 | 0.3000 | 0.0% |
| `c4` | Senior ML / Data Scientist | 0.9937 | **0.9971** | +0.3% | 0.3000 | 0.3000 | 0.0% |
| `c5` | Junior QA Automation Engineer | 1.0000 | **0.9719** | -2.8% | 0.3000 | 0.3000 | 0.0% |
| `c6` | Engineering Manager / Director | 0.8839 | **0.9000** | +1.8% | 0.4000 | **0.5000** | **+25.0%** |
| **OVERALL** | **Macro Average across 6 Personas** | **0.9698** | **0.9741** | **+0.4%** | **0.3000** | **0.3167** | **+5.6%** |

### 3.2 Junior QA Regression Analysis (`c5-junior-qa-engineer`)
- **Metric Delta:** Baseline nDCG@10 = `1.0000` → Wave 2 nDCG@10 = `0.9719` (-2.8%).
- **Root Cause:**
  - Candidate `c5` has `salaryMin: £32,000` and `experienceLevels: ["ENTRY", "JUNIOR", "MID"]`.
  - Two relevant vacancies exist: `hjob-09` ("Junior Test Automation Engineer", £30k-£38k, `ENTRY`) and `hjob-05` ("QA Automation Engineer (Cypress / JavaScript)", £45k-£55k, `MID`).
  - In Baseline (unfiltered keyword count): Both jobs matched Cypress/JS keywords equally and were ranked without seniority or salary calibration, coincidentally producing ideal ordering on this single slice (nDCG = 1.0000).
  - In Wave 2: `hjob-05` has a higher salary fit (1.0 vs 0.95) and matches 3 out of 4 required skills (75%), giving it a score of 0.940, while `hjob-09` scored 0.895. Placing `hjob-05` ahead of `hjob-09` causes a minor logarithmic discount factor difference on graded relevance, resulting in 0.9719.
- **Hard Eligibility Invariants Intact:**
  - `HardFilterService` word-boundary regex (`\b<title>\b`) strictly blocked all non-QA vacancies (e.g. `International Trade Specialist`, `Software Engineer`, `Nurse`).
  - Candidate salary floor filtering remains strictly enforced.
  - Zero-hallucination fact checking against the candidate profile is completely unaffected by scoring weights.
- **No Superiority Claims from Synthetic Data:**
  - Controlled synthetic and held-out benchmarks test mathematical properties of ranking algorithms under isolated conditions.
  - **They do NOT establish real-world product superiority over commercial competitors like Tsenta or AIHawk**, which operate in unconstrained environments with noisy HTML, scraping dropouts, and untyped vacancies. Commercial superiority requires sustained measurement of candidate interview conversion in production.

---

## 4. ATS Verification: Mocked Interception vs. External Confirmation

**Correction applied at the final approval gate (19 November 2026).** The previous revision of this section
recorded Greenhouse as `EXTERNALLY CONFIRMED (HTTP 200/302 + DOM receipt)` on the strength of an "authorised
sandbox verified in Phase 19/20". Nothing in this repository supports that claim, and it is withdrawn:

- `REAL_SUBMIT` is hard-stopped repository-wide — "No endpoint submits an application, no browser automation"
  (`docs/PHASE8_FIND_WORKFLOW.md` §11.11).
- `docs/ROBIN_GAP_REGISTER.md` records that **no ATS adapter is assigned `SUBMISSION_VERIFIED` by design**,
  that the registry's rules forbid real-employer test submissions, and that Greenhouse's guarded submit path
  is tested against fixtures only: any messaging implying submission has been independently confirmed
  "would be false".
- There is no Phase 19 document in `docs/`. `docs/ROBIN_PHASE20_VERIFICATION.md` does not record a Greenhouse
  sandbox submission; it lists an authentic candidate sign-in on a supported ATS as an **outstanding** step.
- `docs/ROBIN_BUILD_ROADMAP.md` still carries "Greenhouse/Ashby must first be tested only against a controlled
  sandbox/test posting the candidate owns" as a goal, not as completed work.

Every row below is automated-test evidence executed in CI at this commit. **No ATS submission has been
externally confirmed, and none is claimed.**

| ATS Platform | Automated evidence (executed at this commit) | Evidence type | Authorised sandbox / live submission | Recorded status |
| :--- | :--- | :--- | :--- | :--- |
| **Greenhouse** | `worker/test/greenhouse.test.js` (5 `route()` interceptions), `worker/test/confirmation.test.js`, backend fixture `ats/greenhouse-apply-form-fixture.html` | Mocked route interception + captured form fixtures | **None** | `SUBMISSION_AVAILABLE` — not verified |
| **Ashby** | `worker/test/ashby.test.js` (2 `route()` interceptions; `jobs.ashbyhq.com`, `applicationForm.submit`) | Mocked route interception | **None** | Contract verified against mocks only |
| **Workday** | `worker/test/workday.test.js` (5 `route()` interceptions), `worker/src/workday.js` multi-step stepper | Simulated multi-step fill; halts at unauthenticated login walls | **None** | Credential-gated; never submitted |
| **Lever** | `worker/test/lever.test.js` | Mocked form and file-upload automation | **None** | Contract verified against mocks only |
| **Zoho Recruit** | `worker/test/zoho_recruit.test.js` | Mocked form and payload automation | **None** | Contract verified against mocks only |

The worker suite passed in full at this commit (262/262, 0 skipped — §2), which establishes that the adapters
behave correctly **against mocks**. It establishes nothing about employer-side acceptance.

---

## 5. Database Compatibility, Locking and Recovery

### 5.1 Executed Migration Rehearsal on PostgreSQL 17.11

The previous revision inferred 17.11 compatibility from a rehearsal on PostgreSQL **18.4** plus syntax
inspection. `V001`–`V063` have now been **executed** against a disposable PostgreSQL 17.11 container matching
the target Oracle cluster's minor version.

| Property | Measured value |
| :--- | :--- |
| Server version reported by the instance | `17.11 (Debian 17.11-1.pgdg13+2)` |
| Flyway | 10.20.1 (the version pinned in `backend/pom.xml`) |
| Migrations applied | **62** — `V001`–`V063`; `V058` does not exist in the repository and is an expected version gap |
| Result | `Successfully applied 62 migrations ... now at version v063`; exit code **0** |
| Execution time | 0.711 s against an empty schema |
| `flyway_schema_history` | 62 rows, 62 successful, **0 failed**, min version `001`, max version `063` |
| Placeholder supplied | `remove_seed_dev_account=true` (required by `V024`) |
| Scope | Disposable local container only. The Oracle production cluster was **not** contacted. |

Post-migration object verification on the same instance:
- `job_sources` — `health_status` NOT NULL DEFAULT `'HEALTHY'::text`, `consecutive_failures` NOT NULL
  DEFAULT `0`, `last_successful_run_at` and `error_summary` nullable.
- `applications` — `follow_up_status` NOT NULL DEFAULT `'NONE'::text`, `follow_up_due_at` nullable.
- `job_bookmarks` present; `job_sources_health_idx`, `applications_follow_up_idx` and
  `job_bookmarks_profile_idx` all created.

**Update, 20 November 2026.** After C2 the chain is V001–V064: 63 migrations, with V058 still a gap. It was
rehearsed again on PostgreSQL 17.11 — fresh install, upgrade from the Wave 1 schema at V060, recovery from an
interrupted index build and from a failed non-transactional migration — and the candidate was validated
read-only against it (`applied=63`, `current=064`). The results are in §3 of the readiness report.

### 5.2 Measured Migration Locking Behaviour

Flyway runs each migration inside a single transaction — no `*.sql.conf` file disables it, so
`executeInTransaction` is at its default. **Every lock a script takes is therefore held until that script
commits.** The locks below were measured by replaying `V061`–`V063` against a 17.11 database held at `V060`
and reading `pg_locks` immediately before each `COMMIT`.

| Migration | Table | Lock modes actually held at commit | Practical effect |
| :--- | :--- | :--- | :--- |
| `V061` | `job_sources` | `AccessExclusiveLock`, `ShareLock` | Blocks **all** access to `job_sources`, reads included, for the whole script |
| `V062` | `profiles`, `jobs` | `ShareRowExclusiveLock` | Blocks writes (not reads) to both FK parents while the new table is created |
| `V062` | `job_bookmarks` | `AccessExclusiveLock` | No impact — the table does not exist until this script runs |
| `V063` | `applications` | `AccessExclusiveLock`, `ShareLock` | Blocks **all** access to `applications`, reads included, for the whole script |

Consequences, stated plainly:

1. **`ADD COLUMN ... NOT NULL DEFAULT <constant>` does not rewrite the table.** PostgreSQL 11+ stores the
   default in `pg_attribute`, so the operation is fast. It is **not** "non-blocking", as the previous revision
   stated: it takes `ACCESS EXCLUSIVE`.
2. **Indexes on existing tables are the real exposure.** `job_sources_health_idx` and
   `applications_follow_up_idx` are plain `CREATE INDEX`; `CONCURRENTLY` appears nowhere in the migration set.
   Because the `ALTER TABLE` earlier in the same transaction already holds `ACCESS EXCLUSIVE`, the index build
   time becomes a full read-and-write lockout of that table, rather than the write-only block a bare
   `CREATE INDEX` would cause on its own.
3. `job_sources` is a small seeded catalogue (rows added by `V002`, `V028`, `V044`, `V045`, `V048`), so
   `V061`'s lockout is negligible. `applications` grows per candidate, so `V063`'s lockout scales with its row
   count. **Production row counts were not measured — the production database was not contacted**, so the
   real-world duration of that lockout is unknown.
4. **No `lock_timeout` or `statement_timeout` is configured for migrations.** If a long-running transaction
   holds a conflicting lock, the migration waits indefinitely and queues ahead of every subsequent request to
   that table, turning a slow query into a full table stall.

Recommendations — deliberately **not** applied, because this gate is documentation-only and does not modify
SQL, migrations or configuration:

- Set a short `lock_timeout` for the migration session so a blocked migration fails fast instead of queueing.
- If `applications` is large in production, move `applications_follow_up_idx` into its own migration using
  `CREATE INDEX CONCURRENTLY` with `executeInTransaction=false`.
- Apply the migrations inside a maintenance window. §7 records maintenance mode as active at the time Wave 2
  was integrated; that is a historical observation from this branch's integration, not a live check of
  production.

**Update, 20 November 2026 (C2).** The second recommendation was implemented in `f609e9f`. V063 now contains only
its two `ADD COLUMN` statements, and V064 builds `applications_follow_up_idx` with `CREATE INDEX CONCURRENTLY`
outside a transaction. That also required `spring.flyway.postgresql.transactional-lock=false`: without it,
Flyway's own idle-in-transaction lock connection blocks the concurrent build indefinitely. Locks measured after
the change:

| Migration | Lock held | Effect |
| :--- | :--- | :--- |
| `V063` | `AccessExclusiveLock` only | Brief, with no rewrite — but acquiring it waits behind in-flight transactions, and later queries on `applications` queue behind it (F5) |
| `V064` | `ShareUpdateExclusiveLock` | Reads and writes continue; a concurrent `INSERT` succeeded during a 3,000,000-row build. The build waits for every older transaction in the database (F3) |

`lock_timeout` remains unapplied. The operational handling for F3 and F5 is in §6 of the readiness report.

### 5.3 Recovery Procedure: Forward-Fix Only

*Corrected 20 November 2026: items 1, 3 and 4 below were revised. The earlier wording overlooked the
application's startup migration check and predated V064.*

The destructive down-migration script published in the previous revision has been **removed**. It dropped
`job_bookmarks`, the `applications.follow_up_*` columns and the `job_sources` health columns, and deleted rows
from `flyway_schema_history`. It would have destroyed candidate bookmarks, follow-up state and circuit-breaker
history — and it named an index this release never creates (`idx_applications_follow_up_due`; the real name is
`applications_follow_up_idx`), so it would not even have dropped the index it claimed to.

Recovery for this release is **forward-fix**:

1. **Redeploying the previous build is not a recovery step for this application** (corrected 20 November 2026).
   This item previously said to roll back the application rather than the schema, because "the previous build
   runs unchanged against a `V063` schema". That is wrong. `V061`–`V064` are purely additive, so the earlier
   *code* would cope with the new schema, but at startup `FlywayBootstrapRecoveryPolicy` (`isUnbrokenPrefix`)
   refuses to run against a database whose applied migrations are not a prefix of the ones the jar ships. A
   build that ships only up to V060 therefore will not start once V061–V064 are applied. A fail-safe rollback
   build — the earlier code packaged with the V061–V064 files — should start, but it has never been built or
   tested (readiness report, §5).
2. **Disable the feature in the application layer, not with DDL,** if a Wave 2 feature misbehaves while the
   schema stays in place.
3. **Correct forward.** A wrong column, default or index is fixed by a new migration, numbered **V065 or later**
   (V064 is taken).
   `flyway_schema_history` stays append-only; never `DELETE` from it.
4. **A harmful index** can be removed by a new migration numbered **V065 or later** (V064 now builds
   `applications_follow_up_idx`), using `DROP INDEX CONCURRENTLY` with `executeInTransaction=false` and the
   session-level Flyway lock. An index is a derived object, so dropping it loses no candidate data.
5. **Candidate and operator data must survive recovery.** `job_bookmarks` rows, `applications.follow_up_*`
   state and the `job_sources` health counters are user-visible data, not disposable scratch.
6. **Confirm a current verified backup exists before applying** the migrations, under the project's existing
   backup procedure. No backup or export was run as part of this gate.

No schema-reversal procedure is published here. If a true reversal is ever required it must be a separately
reviewed change with its own operator approval, a fresh verified backup, and an explicit written acceptance of
the data loss involved. It is out of scope for this release.

---

## 6. Independent QA Sign-Off (Agent 6)

Agent 6 independently reviewed and executed the Wave 2 adversarial test suite:
- `AtsExecutionAdversarialTest`: 4/4 passed (SHA-256 PDF digest mismatch, tampered artifacts, corrupted files).
- `CandidateDataIntegrityAdversarialTest`: 5/5 passed (unverified degrees, skills, and dates rejected).
- `ConcurrencyAndDuplicateAdversarialTest`: 2/2 passed (concurrent outbox delivery, race conditions).
- `DiscoveryAdversarialTest`: 2/2 passed (JobIdentity URL query differentiation, aggregator ranking authority).
- `MatchingIntegrityAdversarialTest`: 5/5 passed (seniority inversion, inverted tokens, substring bleed).
- `RecruiterTrackingAdversarialTest`: 4/4 passed (cross-tenant isolation, thread grouping, 14-day inactivity).
- `HardEligibilityAdversarialTest`: 2/2 passed (hard filtering rejection).
- **Total Adversarial Tests:** **24/24 passed** (0 failures, 0 errors).
- **Agent 6 Independent Verdict:** **APPROVED WITHOUT RESERVATIONS**.

### 6.1 Scope of that sign-off (approval gate 8)

Agent 6's sign-off commit is `51bdf15` on `claude/wave2-agent6-qa`. It **is** an ancestor of the final code
commit `51961ba`, and the seven adversarial test classes are unchanged between the two commits — their
`@Test` method counts in the tree at `51961ba` are exactly the 4 / 5 / 2 / 2 / 5 / 4 / 2 above, totalling 24.
The adversarial results therefore still describe the final tree.

**But Agent 6 did not review the final code tree in full.** Five files changed after `51bdf15`:

| File | Nature |
| :--- | :--- |
| `backend/.../discovery/platform/BoardDiscoveryService.java` | **Production code**, +30/−25 |
| `backend/.../discovery/platform/BoardDiscoveryServiceTest.java` | Mock arity updated to match |
| `backend/.../jobs/HeldoutMatchingBenchmarkTest.java` | New benchmark (150 lines) |
| `backend/src/test/resources/matching/heldout_evaluation_dataset.json` | New dataset (488 lines) |
| `docs/agents/WAVE2_FINAL_REPORT.md` | This document |

The production change does two distinct things, neither covered by Agent 6's verdict:

1. **It fixes a latent bug.** The failure-path `job_sources` update previously carried four placeholders but
   bound only three arguments (`error_summary = (?::jsonb)->>'error'` was left unbound). The statement is now
   split into explicit success/failure branches with the error summary bound directly; the unit test's mock
   arity was updated to match.
2. **It relaxes the safe-deletion guard.** Previously an empty-but-complete listing never marked jobs as
   removed in any mode. Now an empty listing is treated as authoritative in `MANUAL` mode and calls
   `markUnlisted`, so an admin-triggered or integration-test sync that returns zero jobs can mark active
   inventory as deleted. `anEmptyCompleteListingNeverRemovesExistingJobsSafeDeletionGuard` exercises
   `SCHEDULED` mode only, so **the relaxed `MANUAL` path has no test coverage in either commit**, and the
   benchmark added after sign-off was never run by Agent 6.

This is recorded as an open item in the release-readiness report
(`docs/ROBIN_WAVE2_RELEASE_READINESS.md`), not as a defect fixed here: this gate is documentation-only.

**Update, 20 November 2026.** Resolved. C1 (Option B) reverted the relaxation in `f609e9f` and added four
regression tests. Independent QA reviewed `f609e9f` and `7108e49`, including this `BoardDiscoveryService`
change, and returned PASS.

---

## 7. Branch Status and Production Guards

**Update, 20 November 2026.** `integration/wave-2` now carries `f609e9f` (C1 + C2) and `7108e49` (QA F1) on top
of `ed01408`, followed by the documentation commit that carries this update. It exists only locally: no upstream
is configured, and nothing has been pushed or merged. It is 0 commits behind the locally known `origin/master`
(`779a6be2`); no fetch was made, so newer remote commits would not show here. The bullets below are as recorded
on 19 November.

- **Active branch:** `integration/wave-2`
- **Code commit:** `51961ba` — **documentation commit:** `01c78fa` (docs-only, verified; §Gate 1 of the
  readiness report)
- **Branch currency:** `integration/wave-2` contains all of `origin/master` (0 commits behind, 31 ahead;
  merge-base `779a6be2`, which is the `origin/master` tip). It is current with `master` as of 19 November 2026.
- **Worker trees:** the `integration/wave-2` worktree was clean at the start of this gate. The documentation
  changes made by this gate are the only modifications.
- **Actions taken by this gate:** documentation edits, a `mvn -B verify` run, and a Flyway rehearsal against a
  disposable local PostgreSQL 17.11 container (since removed). No source, test, SQL, migration or
  configuration file was changed.

**Production guards — what this work did:**
- Production Oracle database (`132.226.133.162`): **not contacted.** No Flyway run, no query, no change.
- `robin_restore` database: **not contacted.** No backup or export was run.
- Render service `job-agent`: **not changed.** No deployment, no environment-variable change, no
  maintenance-mode change.
- `master`: **not changed.** Nothing merged, no repository setting altered.

**Historical observation, not a live check.** Earlier revisions of this report recorded that discovery workers
were disabled and maintenance mode was active on Render. That was the state observed when it was written. This
gate did not query production, so it makes no claim about current production health, deployment state or
maintenance mode. Phase 20 also remains **OPEN**; its live-production verification items are untouched by
Wave 2 and must not be inferred from these results.

**Verdict of record.** The previous closing line ("Agent 0 confirms Wave 2 is fully hardened, verified, and
ready for user merge approval") is superseded. The gate found that three of its supporting claims were wrong
or unsupported, and that one safety-relevant production change post-dates independent QA sign-off. The
standing verdict is in **`docs/ROBIN_WAVE2_RELEASE_READINESS.md`**. The two conditions it set, C1 and C2, are
now cleared (`f609e9f`, `7108e49`, independent QA PASS), but Wave 2 is **not approved for release**: F9 is an
open release risk that needs its own investigation and decision.
