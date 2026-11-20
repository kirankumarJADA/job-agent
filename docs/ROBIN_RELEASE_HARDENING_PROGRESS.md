# Robin Wave 2 — Release Hardening Progress & Verification Log

**Date:** 20 November 2026  
**Target Branch:** `hardening/wave2-release`  
**Current Branch HEAD:** `593e96e` (documentation reconciliation)  
**Final F9 Code Commit:** `1878c67e05321a127f54660098a640b21bc8124c`  
**Rollback Candidate Branch:** `rollback/wave2-v054-compat` (`b59137d` / `2dec301`)  
**Status:** **CODE, MIGRATION & ROLLBACK HARDENING COMPLETE — ZERO BLOCKERS REMAIN**

---

## 1. Executive Summary

This document logs the comprehensive hardening and empirical verification performed to clear the Wave 2 release blockers:
1. **Condition C1 (Safe-Deletion Guard):** Reverted the unreviewed `MANUAL`-mode empty listing bypass in `BoardDiscoveryService` and established 4 new regression tests (`f609e9f`, `7108e49`).
2. **Condition C2 (V063 Index Lockout):** Split `V063` into metadata alteration and `V064` non-transactional concurrent index build (`applications_follow_up_idx`), configured Flyway session-level lock, and established ArchUnit lock guards (`f609e9f`).
3. **Migration Work Complete (F2, F3, F5, F6):** Database migration architecture is completely finished and verified across 52 integration tests. Removed as outstanding release blockers.
4. **Rollback Capability:** Built and functionally rehearsed the rollback-compatible application against PostgreSQL 17.11 pre-migrated to V064 (`b59137d`, `2dec301`).
5. **Issue F9 (Hourly Cleanup Collateral Deletion & Lock Inversion):**
   - Scoped soft-deletions strictly to jobs with expired observations in that sweep.
   - Fixed lock ordering: locked jobs ascending by ID in a single statement (`FOR NO KEY UPDATE`) before touching observations.
   - Guarded overlapping sweeps with transaction-scoped PostgreSQL advisory locks (`pg_try_advisory_xact_lock`).
   - Pinned `READ COMMITTED` isolation to guarantee statement snapshot freshness.
   - Verified via lock-mode discrimination testing, 600 rounds of concurrency soak testing across 3 runs of 200 rounds, full 1,980-test Maven verification, and independent QA review.

---

## 2. Commit Provenance & Verification

| Commit SHA | Branch | Summary | Role |
| :--- | :--- | :--- | :--- |
| `f609e9fe8cfa726a14d0e809fc003c7160d4f553` | `integration/wave-2` / `hardening` | C1 + C2 resolution (safe deletion, V063/V064 split, session lock) | Hardening baseline |
| `7108e49748c1e86135d11e72928d0ce72cc79b04` | `integration/wave-2` / `hardening` | QA F1 correction in `CrossSourceDiscoveryIT` | Hardening baseline |
| `b59137d0c3454b5ea5b6cbe19e7100b522bf57b8` | `rollback/wave2-v054-compat` | Pre-Wave-2 application packaging V055–V064 migrations & session lock | Rollback candidate build |
| `2dec3014a45e7f1dfa3ce4e09cb14e21a4f0ce6b` | `rollback/wave2-v054-compat` | Rollback rehearsal test `RollbackAgainstWave2SchemaIT` against V064 database | Rollback rehearsal test |
| `ca487b47dd31583cd7d1fd846542a57a9940139d` | `hardening/wave2-release` | F9 initial fix: lock jobs before observations, advisory lock, scoped deletion | Initial F9 fix |
| `1878c67e05321a127f54660098a640b21bc8124c` | `hardening/wave2-release` | F9 QA follow-up: `FOR NO KEY UPDATE`, pin `READ COMMITTED`, discrimination test | Final F9 code commit |
| `593e96e` | `hardening/wave2-release` | Docs: record F9 resolution, soak verification, and rollback rehearsal | Documentation commit |

---

## 3. F9 Architecture & Lock Hardening

### 3.1 Defect Mechanism (Pre-Hardening)
- **Collateral Deletion:** In `JobDiscoveryService.scheduledDiscoveryMaintenance()`, expiring any aggregator observation executed a global `update jobs j set deleted_at = now() where not exists (select 1 from job_source_observations ... where source_status = 'SEEN')`. This inadvertently marked deleted legacy jobs, manually created postings, or closed postings whose observations were already missing/closed.
- **Lock Inversion (Deadlock 40P01):** Ingestion (`ingestJob`) locks `jobs` first and then `job_source_observations`. The maintenance sweep locked `job_source_observations` first during observation expiry, then locked `jobs` during deletion, producing AB-BA lock inversion deadlocks under concurrent execution.
- **Stale Snapshots:** A concurrent ingestion committing an ATS observation during sweep execution was invisible to a long-running snapshot, risking soft-deletion of an active job.

### 3.2 Implemented Resolution
- **Advisory Lock Concurrency Control:**
  ```sql
  select pg_try_advisory_xact_lock(hashtextextended('job-discovery-maintenance', 0))
  ```
  Prevents overlapping runs across replicas; a concurrent run skips immediately.
- **Ascending Single-Statement Row Locking:**
  Step 1 locks candidate jobs in ascending primary key order in a single statement into temporary table `maintenance_locked_job_ids`:
  ```sql
  order by j.id
  for no key update
  ```
  Because both ingestion and sweep acquire locks in the order `jobs` -> `job_source_observations`, deadlock cannot occur.
- **Lock Strength (`FOR NO KEY UPDATE`):**
  Corrected from `FOR UPDATE` in `1878c67`. `FOR UPDATE` conflicted with `KEY SHARE` locks acquired by foreign-key checks during concurrent inserts into `job_bookmarks`, `applications`, `matches`, or `notifications`. `FOR NO KEY UPDATE` conflicts with ingestion updates on the job row, but allows non-key foreign-key inserts to proceed without blocking.
- **Scoped Expiry & Deletion:**
  Step 2 expires observations only for jobs in `maintenance_locked_job_ids`, writing affected IDs to `expired_job_ids`.
  Step 3 soft-deletes only jobs present in both `expired_job_ids` and `maintenance_locked_job_ids` that have no remaining live `SEEN` observation.
- **Isolation Pinning:**
  Method annotated with `@Transactional(isolation = Isolation.READ_COMMITTED)`. In `READ COMMITTED`, Step 3 uses a fresh snapshot seeing any newly committed ATS observation, ensuring live postings are preserved.

---

## 4. Discrimination Testing

To prove that the lock-mode correction in `1878c67` directly resolves foreign-key concurrency contention:

- **Target Test:** `DiscoveryMaintenanceConcurrencyIT.anInsertThatOnlyReferencesALockedJobIsNotHeldUpByTheCleanup`
  Tests whether an insert into `job_bookmarks` referencing a job currently locked by the maintenance sweep succeeds within a 3-second `lock_timeout`.

| Lock Mode Tested | Implementation Commit | Test Execution Result | Failure / Success Detail |
| :--- | :--- | :--- | :--- |
| `FOR UPDATE` | `ca487b4` (simulated in tree) | **FAILED (Exit code 1)** | `PSQLException: ERROR: canceling statement due to lock timeout` while locking tuple `(0,3)` in relation `jobs` for `KEY SHARE`. Proves `FOR UPDATE` blocks foreign-key inserts. |
| `FOR NO KEY UPDATE` | `1878c67` (actual HEAD) | **PASSED (Exit code 0)** | Test executed in 30.10s. Bookmark insert succeeded immediately while sweep held job; sweep completed without error. |

---

## 5. Concurrency Soak Testing

Three consecutive runs of 200 rounds each (600 rounds total) were executed using `DiscoveryMaintenanceConcurrencyIT.repeatedConcurrentReIngestionAndCleanupNeverDeadlockAndLoseNoJob` with `-Df9.rounds=200`:

| Soak Run | Rounds | Test Execution Time | Total Maven Time | Deadlocks (40P01) | Lost Jobs | Status | Log File Preserved |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Run 1** | 200 | 63.31 s | 1m 34s | **0** | **0** | **SUCCESS** | `f9-soak-run1.log` (234,298 bytes) |
| **Run 2** | 200 | 66.18 s | 1m 40s | **0** | **0** | **SUCCESS** | `f9-soak-run2.log` (234,302 bytes) |
| **Run 3** | 200 | 74.30 s | 1m 46s | **0** | **0** | **SUCCESS** | `f9-soak-run3.log` (234,302 bytes) |
| **Aggregate** | **600** | **203.79 s** | **5m 00s** | **0** | **0** | **100% PASS** | Complete logs retained |

Each round concurrently executed:
1. `JobDiscoveryService.scheduledDiscoveryMaintenance()` on thread pool worker 1.
2. Ingestion of 4 stale aggregator postings re-ingested concurrently on thread pool worker 2.
3. 1 live ATS control posting verified unaffected.
In all 600 rounds, zero deadlocks occurred, all re-ingested jobs remained live (`deleted_at is null`), their observations remained `SEEN`, and the control ATS job was never touched.

---

## 6. Fresh Full Maven Verification

Executed fresh `mvn -B clean verify` against HEAD commit `1878c67`:

- **Execution Timestamp:** 20 November 2026 15:15–15:31 UTC
- **Duration:** 15 minutes 29 seconds
- **Exit Code:** `0` (`BUILD SUCCESS`)
- **Surefire Test Count:** **1,645 tests, 0 failures, 0 errors, 6 skipped**
- **Failsafe Test Count:** **335 tests, 0 failures, 0 errors, 7 skipped**
- **Total Tests:** **1,980 test executions, 0 failures, 0 errors, 13 skipped**
- **Integration Test Databases:** 52 of 52 completed cleanly
- **Skipped Tests Analysis:** Exactly 13 environment-gated tests (6 Redis on localhost:6379, 4 live discovery web requests, 3 external DB URLs).
- **Log File Preserved:** `verify-f9-final.log` (5,872,054 bytes)

---

## 7. Independent QA Evaluation

An independent security and concurrency review was conducted against follow-up commit `1878c67`:

| Verification Dimension | Assessment Criteria | Verified Finding | Status |
| :--- | :--- | :--- | :--- |
| **1. SQL Lock Ordering** | Are `jobs` and `observations` locked in identical order across all code paths? | Both `ingestJob` and `scheduledDiscoveryMaintenance` lock `jobs` first, then `job_source_observations`. Multi-row locks in maintenance sweep are ordered deterministically by `j.id ASC`. | **PASS** |
| **2. Concurrent Cleanup & Ingestion** | Do concurrent transactions deadlock or fail under load? | Confirmed across 600 soak rounds. Zero deadlock exceptions (40P01), zero statement timeouts under `FOR NO KEY UPDATE`. | **PASS** |
| **3. Active ATS Preservation** | Are active ATS observations protected when aggregator observations expire? | Step 3 checks `not exists (select 1 from job_source_observations where job_id = j.id and source_status = 'SEEN')`. `Isolation.READ_COMMITTED` guarantees observation of concurrently committed ATS rows. Verified in `CrossSourceDiscoveryIT`. | **PASS** |
| **4. Unrelated Job Deletion** | Are legacy jobs or jobs from other sources preserved? | Deletion query is strictly scoped to `j.id in (select job_id from expired_job_ids)` and `j.id in (select job_id from maintenance_locked_job_ids)`. Legacy jobs with no observations and closed historical jobs remain untouched. | **PASS** |
| **5. Overlapping Execution** | Does multi-instance execution queue or corrupt state? | `pg_try_advisory_xact_lock` ensures subsequent concurrent instances log and return immediately. Verified in `aSecondConcurrentCleanupSkipsInsteadOfQueueingBehindTheFirst`. | **PASS** |
| **6. Transaction Safety** | Are partial operations rolled back on unexpected failure? | Method is `@Transactional`. Injected failure in Step 4 rolls back expiry, deletion, and temporary table allocations cleanly. Verified in `aFailedCleanupRollsBackTheExpiryAndTheDeletion`. | **PASS** |
| **7. New Deadlock Risks** | Does `FOR NO KEY UPDATE` introduce new locking hazards? | `FOR NO KEY UPDATE` releases foreign-key checks (`KEY SHARE`) from blocking. Verified via discrimination test. | **PASS** |

**QA Verdict:** **PASS — ZERO UNRESOLVED CRITICAL OR HIGH-SEVERITY FINDINGS.**

---

## 8. Rollback Rehearsal Evidence

- **Branch:** `rollback/wave2-v054-compat`
- **Commits:** `b59137d` (rollback candidate build) and `2dec301` (rehearsal test suite)
- **Artifact Details:**
  - **Filename:** `jobagent-0.1.0-SNAPSHOT.jar`
  - **Location:** `.claude/worktrees/rollback-candidate/backend/target/jobagent-0.1.0-SNAPSHOT.jar`
  - **Size:** 155,820,077 bytes
  - **Built Timestamp:** 2026-11-20 14:24:37 UTC
  - **Build Commit:** `b59137d0c3454b5ea5b6cbe19e7100b522bf57b8`
  - **SHA-256 Checksum:** `B0D1BEA812FCF320B41D1FD4B0472E3FF987E9BD87DF9A7DDCBE9330FE8F548E`
- **Rehearsal Analysis:**
  - The original pre-Wave-2 JAR (`master` up to V054) fails safely against a V064 database: `FlywayBootstrapRecoveryPolicy` enforces `isUnbrokenPrefix` and fails closed on unknown migrations.
  - The rollback-compatible candidate JAR incorporates the ten additive migrations (V055–V064) and session lock configuration with the pre-Wave-2 application codebase.
  - Tested via `RollbackAgainstWave2SchemaIT` against a real PostgreSQL 17.11 container pre-migrated to V064 with session-level lock and seeded Wave 2 rows.
- **Results:**
  - 2 of 2 integration tests passed in 53.38s (0 failures, 0 errors).
  - Pre-Wave-2 application boots cleanly on V064 database; startup classifies schema history as `MIGRATED` with 0 pending and 0 failed migrations.
  - Read endpoints (`jobs`, `sources`, `profile`, `applications`) function normally.
  - Write pipelines (`POST /api/v1/applications`) create and transition applications cleanly, inheriting V063 column defaults.
  - Hourly maintenance, old ingestion, and outbox processor run without schema incompatibilities.
  - Post-Wave-2 data rows (e.g. bookmarks, source health records) survive untouched.
- **Production Status:** Tested recovery candidate artifact. Not published to production artifact registries; do not treat an unbuilt or unmanaged package as production-ready.

---

## 9. Release Directives & Operational Checklist

Migration work is completely finished. None of F2, F3, F5, or F6 remain as open release blockers.
When production release is authorised:

1. **Direct Connection for Migration (F2):** Ensure Flyway migration connects directly to PostgreSQL port 5432 or a session-mode pooler. Do not migrate over transaction-mode poolers (port 6543) where session advisory locks can leak.
2. **Maintenance Window & Active Transaction Monitoring (F3 / F5):** Deploy during a scheduled maintenance window outside automated backup windows. Inspect `pg_stat_activity` to ensure no transactions are idle in transaction prior to running V064's `CREATE INDEX CONCURRENTLY`.
3. **External Tool Configuration (F6):** If executing manual migrations via Flyway CLI or external tools, confirm `postgresql.transactional.lock=false` is set in the configuration.
4. **Emergency Rollback Candidate Staged:** Artifact `jobagent-0.1.0-SNAPSHOT.jar` (SHA-256 `B0D1BEA812FCF320B41D1FD4B0472E3FF987E9BD87DF9A7DDCBE9330FE8F548E`) on branch `rollback/wave2-v054-compat` is verified and ready for deployment should application rollback on a V064 database be needed.
