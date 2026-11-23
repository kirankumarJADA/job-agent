# Robin Engineering Safety Investigation Report: F9 — Scheduled Discovery Cleanup

**Date:** 20 November 2026  
**Status:** INVESTIGATION COMPLETE — EMPIRICALLY REPRODUCED & VERIFIED  
**Target Environment:** Disposable PostgreSQL 17.11 (`PostgreSQL 17.11 (Debian 17.11-1.pgdg13+2)`)  
**Scope:** Investigation only. Zero production code, schema migrations, or production databases were modified.

---

## 1. Executive Summary

During release hardening review of Wave 2, finding **F9** was identified as an unresolved release risk in `JobDiscoveryService.scheduledDiscoveryMaintenance()`:
When the hourly cleanup task expires stale aggregator observations (`source_status = 'SEEN'` older than `aggregatorTtlDays`), if `expiredObs > 0`, it executes an unconstrained global update against the `jobs` table:

```sql
update jobs j set deleted_at = now()
where j.deleted_at is null
  and not exists (
      select 1 from job_source_observations o
      where o.job_id = j.id and o.source_status = 'SEEN'
  );
```

This investigation has:
1. **Empirically reproduced the catastrophic failure mode** on a disposable PostgreSQL 17.11 instance matching the production minor version.
2. **Diagnosed the exact root cause** and measured collateral impact across four representative job archetypes.
3. **Formulated and empirically verified the safe correction** on PostgreSQL 17.11, proving 100% protection for legacy and unrelated jobs while preserving exact aggregator cleanup semantics.
4. **Honoured all safety boundaries**: no production database or configuration was contacted; no commits or deployments were executed.

---

## 2. Root Cause Analysis

### 2.1 The Faulty Logic (`JobDiscoveryService.java:418-436`)
The scheduled task operates in two steps:
1. **Step 1:** Expires stale observations from aggregators (`TSENTA`, `FREEHIRE`, `ARBEITNOW`, `ADZUNA`, `WEB` or null `source_id`) older than `aggregatorTtlDays` (default 7 days).
2. **Step 2:** If `expiredObs > 0`, runs `UPDATE jobs j SET deleted_at = now() WHERE j.deleted_at IS NULL AND NOT EXISTS (...)`.

### 2.2 Why This Causes Collateral Soft-Deletions
- **Global Scope:** The predicate in Step 2 is **not correlated** to the observations expired in Step 1.
- **Global Boolean Trigger:** The execution of Step 2 is gated solely on `if (expiredObs > 0)`. If even a single aggregator observation anywhere in the system expires, Step 2 runs across the entire database.
- **False Equivalence:** The subquery `NOT EXISTS (SELECT 1 FROM job_source_observations o WHERE o.job_id = j.id AND o.source_status = 'SEEN')` assumes that any job with zero active `'SEEN'` observation rows must be an expired aggregator posting.
- **Affected Categories:**
  - **Legacy Jobs:** Any job in `jobs` created prior to Wave 2 (before `job_source_observations` existed) has zero rows in `job_source_observations`. It is immediately soft-deleted (`deleted_at = now()`).
  - **Un-observed Ingestions:** Any job ingested through direct APIs, test harnesses, manual postings, or sources that did not insert observation records is immediately soft-deleted.
  - **Historical / Closed Jobs:** Jobs whose observations were closed or missing previously (e.g. retained for candidate application history, reviews, or bookmarks) are immediately soft-deleted.

---

## 3. Empirical Reproduction on Disposable PostgreSQL 17.11

### 3.1 Test Fixture Setup
On disposable container `robin-pg1711` (`f9_investigation` database):
Four distinct job archetypes were seeded with `deleted_at = NULL`:

1. **Job 1 (`Legacy Engineer`):** Pre-Wave 2 legacy job. Exists in `jobs`, but has **0 rows** in `job_source_observations`.
2. **Job 2 (`Aggregator Role`):** Exists in `jobs`. Has 1 observation from `FREEHIRE` with `last_seen_at = now() - interval '10 days'` (older than 7-day TTL).
3. **Job 3 (`Stripe ATS Role`):** Exists in `jobs`. Has 1 active observation from `GREENHOUSE` with `last_seen_at = now()`.
4. **Job 4 (`Historical Closed Role`):** Exists in `jobs`. Observation was already marked `MISSING` 15 days ago; `deleted_at` was `NULL`.

### 3.2 Execution of Existing Code Logic
Executing Step 1 and Step 2:
```text
UPDATE 1   -- Step 1: expired 1 aggregator observation
UPDATE 3   -- Step 2: soft-deleted 3 jobs!
```

### 3.3 Observed State After Cleanup
| Job ID | Job Title | Expected Behaviour | Actual Behaviour | Result |
| :--- | :--- | :--- | :--- | :--- |
| `0000...0001` | **Legacy Engineer** | Retain (`is_deleted = f`) | **`is_deleted = t` (Soft-deleted)** | **COLLATERAL DAMAGE** |
| `0000...0002` | **Aggregator Role** | Soft-delete (`is_deleted = t`) | `is_deleted = t` (Soft-deleted) | Expected |
| `0000...0003` | **Stripe ATS Role** | Retain (`is_deleted = f`) | `is_deleted = f` (Retained) | Expected |
| `0000...0004` | **Historical Closed Role** | Retain (`is_deleted = f`) | **`is_deleted = t` (Soft-deleted)** | **COLLATERAL DAMAGE** |

**Empirical Result:** 2 out of 4 jobs suffered unintended collateral soft-deletion upon the expiry of a single unrelated aggregator observation.

---

## 4. Formulation & Verification of the Safe Correction

### 4.1 Safe Correction Principle
Aggregator expiration must be scoped **strictly** to jobs whose observations were modified during that specific sweep:
$$\text{Candidate Jobs for Deletion} = \{ \text{job\_id} \in \text{Observations Expired in Current Run} \}$$
A job in this candidate set is soft-deleted if and only if it has zero remaining `'SEEN'` observations from any source.

### 4.2 Architectural Nuance in PostgreSQL (Snapshot Isolation in CTEs)
In PostgreSQL, statements within a data-modifying Common Table Expression (`WITH ... UPDATE ... RETURNING`) see the database snapshot from the beginning of the statement. Therefore, a single-statement CTE attempting to check `NOT EXISTS (SELECT 1 FROM job_source_observations ... WHERE source_status = 'SEEN')` on the table modified in the same CTE will still see the un-updated `'SEEN'` status.

Hence, the correct and safe implementation in Java/JDBC is a two-step scoped execution:
1. Update expired observations and collect `List<UUID> affectedJobIds` via `RETURNING job_id`.
2. If `!affectedJobIds.isEmpty()`, update `jobs` strictly filtered by `WHERE j.id = ANY(?::uuid[])` (or `IN (:affectedJobIds)`).

### 4.3 Empirical Verification of the Safe Correction
Executing the scoped update on disposable PostgreSQL 17.11:

```sql
BEGIN;

CREATE TEMP TABLE expired_jobs ON COMMIT DROP AS 
WITH exp AS (
    UPDATE job_source_observations SET source_status = 'MISSING', removed_at = now()
    WHERE source_status = 'SEEN'
      AND last_seen_at < now() - make_interval(days => 7)
      AND (
          source_id IN (SELECT id FROM job_sources WHERE kind IN ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA'))
          OR source_type = 'WEB'
          OR source_id IS NULL
      )
    RETURNING job_id
)
SELECT DISTINCT job_id FROM exp;

UPDATE jobs j SET deleted_at = now()
WHERE j.id IN (SELECT job_id FROM expired_jobs)
  AND j.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM job_source_observations o
      WHERE o.job_id = j.id AND o.source_status = 'SEEN'
  );

COMMIT;
```

### 4.4 Result After Safe Correction
```text
BEGIN
SELECT 1   -- Collected 1 affected job_id
UPDATE 1   -- Soft-deleted exactly 1 job!
COMMIT
```

| Job ID | Job Title | Expected Behaviour | Actual Behaviour | Result |
| :--- | :--- | :--- | :--- | :--- |
| `0000...0001` | **Legacy Engineer** | Retain (`is_deleted = f`) | **`is_deleted = f` (Retained)** | **SAFE** |
| `0000...0002` | **Aggregator Role** | Soft-delete (`is_deleted = t`) | **`is_deleted = t` (Soft-deleted)** | **SAFE** |
| `0000...0003` | **Stripe ATS Role** | Retain (`is_deleted = f`) | **`is_deleted = f` (Retained)** | **SAFE** |
| `0000...0004` | **Historical Closed Role** | Retain (`is_deleted = f`) | **`is_deleted = f` (Retained)** | **SAFE** |

**Verification Outcome:** Exactly 1 job soft-deleted. Zero collateral damage to legacy or unrelated records.

---

## 5. Implementation Recommendation for Java Service

When authorised, `JobDiscoveryService.java` lines 417-438 should be updated as follows:

```java
// 1. Expire stale aggregator observations and collect affected job IDs
List<UUID> affectedJobIds = jdbcTemplate.query("""
        update job_source_observations set source_status = 'MISSING', removed_at = now()
        where source_status = 'SEEN'
          and last_seen_at < now() - make_interval(days => ?)
          and (
              source_id in (select id from job_sources where kind in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA'))
              or source_type = 'WEB'
              or source_id is null
          )
        returning job_id
        """, (rs, rowNum) -> rs.getObject("job_id", UUID.class), aggregatorTtlDays);

// 2. Soft-delete ONLY jobs whose observations were expired in this sweep and have no remaining SEEN observations
if (!affectedJobIds.isEmpty()) {
    UUID[] jobIdsArray = affectedJobIds.toArray(new UUID[0]);
    int softDeleted = jdbcTemplate.update("""
            update jobs j set deleted_at = now()
            where j.id = any(?::uuid[])
              and j.deleted_at is null
              and not exists (
                  select 1 from job_source_observations o
                  where o.job_id = j.id and o.source_status = 'SEEN'
              )
            """, (Object) jobIdsArray);
    log.info("Expired {} stale aggregator observations older than {} days; soft-deleted {} orphaned jobs",
            affectedJobIds.size(), aggregatorTtlDays, softDeleted);
}
```

---

## 6. Safety Compliance Statement
- **No production code modified:** `backend/src/main/java/.../JobDiscoveryService.java` remains untouched on `integration/wave-2`.
- **No production database contacted:** All verification executed on container `robin-pg1711` database `f9_investigation`.
- **Zero commits or deployments executed.**
