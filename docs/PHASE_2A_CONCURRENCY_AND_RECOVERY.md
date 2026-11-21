# Phase 2A Remediation: Concurrency, Lease Coordination & Stale Recovery Architecture

## 1. Executive Summary & Root Cause Analysis

### 1.1 The Baseline Defect (P0-1)
In the Phase 2 baseline ([`fb3f606`](https://github.com/kirankumarJADA/job-agent/commit/fb3f606)), `SearchPlanExecutionCoordinator` attempted mutual exclusion using PostgreSQL session-level advisory locks:
```java
// VULNERABLE BASELINE IMPLEMENTATION (fb3f606)
boolean acquired = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "SELECT pg_try_advisory_lock(?)", Boolean.class, lockKey));
...
finally {
    jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, lockKey);
}
```

### 1.2 Failure Mechanics in Production Environments
This pattern suffered from fatal architectural flaws in pooled database topologies:
1. **Connection Pool Decoupling (HikariCP):** Each `jdbcTemplate.queryForObject` checkout from HikariCP borrows an arbitrary connection from the pool and immediately returns it upon query completion. The session advisory lock was acquired on Physical Connection $C_1$, which was then placed back into HikariCP's idle pool *while still holding the lock*.
2. **Orphaned Physical Locks:** The `finally` block borrowed a second, arbitrary connection $C_2$ from HikariCP to call `pg_advisory_unlock`. Because advisory locks are session-scoped to the physical TCP backend in Postgres, calling unlock on $C_2$ failed to release the lock held by $C_1$.
3. **Lock Bypass Fallback:** If an exception occurred during acquisition, the baseline caught the exception and returned `true`, actively bypassing concurrency protection.
4. **PgBouncer Incompatibility:** In production environments utilizing PgBouncer or AWS RDS Proxy in `transaction` pooling mode, session-level advisory locks are strictly prohibited, causing unexpected lock leakage across transactions or pooled clients.

---

## 2. Remediated Architecture: Database-Backed Execution Leases

To provide deterministic, fail-safe mutual exclusion that is fully compatible with PgBouncer and multi-instance scheduler clusters, the session advisory locking mechanism was replaced with **Database-Backed Execution Leases with Token Fencing**.

### 2.1 Database Schema Enhancements ([`V065__search_plans.sql`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/main/resources/db/migration/V065__search_plans.sql))
The `search_plans` table was extended with lease metadata:
```sql
ALTER TABLE search_plans
    ADD COLUMN IF NOT EXISTS execution_owner_token UUID,
    ADD COLUMN IF NOT EXISTS execution_lease_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS search_plans_lease_idx
    ON search_plans (active, last_execution_status, execution_lease_expires_at)
    WHERE active = true;
```

### 2.2 Atomic Lease Acquisition ([`SearchPlanRepository.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/main/java/com/personal/jobagent/discovery/plan/SearchPlanRepository.java))
Before executing any plan, the worker generates a cryptographically random `ownerToken = UUID.randomUUID()` and attempts to atomically claim the lease:

```java
public boolean claimExecutionLease(UUID planId, UUID ownerToken, long leaseDurationMs) {
    long duration = leaseDurationMs > 0 ? leaseDurationMs : 600000L; // default 10 minutes
    int updated = jdbcTemplate.update("""
            UPDATE search_plans
            SET last_execution_status = 'RUNNING',
                execution_owner_token = ?,
                execution_lease_expires_at = now() + (INTERVAL '1 millisecond' * ?),
                updated_at = now()
            WHERE id = ?
              AND active = true
              AND (
                  last_execution_status IS NULL
                  OR last_execution_status <> 'RUNNING'
                  OR execution_lease_expires_at IS NULL
                  OR execution_lease_expires_at < now()
              )
            """, ownerToken, duration, planId);
    return updated > 0;
}
```

#### Guarantees:
- **Atomicity:** The `UPDATE ... WHERE` executes under PostgreSQL row-level locking. Only one concurrent thread or cluster node can update the row from non-RUNNING (or expired RUNNING) to RUNNING.
- **Fail-Closed:** If another execution currently holds an active lease where `execution_lease_expires_at >= now()`, the update returns 0 affected rows and the claim fails immediately without waiting or blocking.
- **No Long-Running DB Transaction:** The lease acquisition is a single quick autocommit statement. Physical connections are never held open while external HTTP provider requests execute.

---

### 2.3 Fencing Protection Against Expired / Zombie Workers
If an execution encounters extreme network latency or a prolonged GC pause exceeding the lease duration (e.g., 10 minutes), another worker might reclaim the expired lease. To prevent the original "zombie" worker from overwriting newly committed data, all terminal updates enforce **Owner Token Fencing**:

```java
public boolean completeExecution(UUID planId, UUID ownerToken, String status, String error, int resultsCount) {
    int updated = jdbcTemplate.update("""
            UPDATE search_plans
            SET last_run_at = now(),
                last_execution_status = ?,
                last_execution_error = ?,
                last_execution_results = ?,
                execution_owner_token = NULL,
                execution_lease_expires_at = NULL,
                updated_at = now()
            WHERE id = ?
              AND execution_owner_token = ?
            """, status, error, resultsCount, planId, ownerToken);
    return updated > 0;
}
```

If the original worker was expired and overtaken, `WHERE execution_owner_token = ?` evaluates to 0 rows. The coordinator logs a fencing warning and safely discards the zombie update.

---

### 2.4 Stale Execution Recovery & Scheduler Sweep
If a container crashes, is OOM-killed, or restarts while executing a plan, the plan would historically remain stuck in `RUNNING`.

#### Remediated Recovery:
At the start of every scheduler sweep ([`SearchPlanScheduler.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/main/java/com/personal/jobagent/discovery/plan/SearchPlanScheduler.java)), `recoverStaleExecutions(Instant now)` is called:

```java
public int recoverStaleExecutions(Instant now) {
    Timestamp ts = Timestamp.from(now);
    return jdbcTemplate.update("""
            UPDATE search_plans
            SET last_execution_status = 'FAILED',
                last_execution_error = 'Execution lease expired or worker terminated unexpectedly',
                last_run_at = now(),
                execution_owner_token = NULL,
                execution_lease_expires_at = NULL,
                updated_at = now()
            WHERE active = true
              AND last_execution_status = 'RUNNING'
              AND execution_lease_expires_at IS NOT NULL
              AND execution_lease_expires_at < ?
            """, ts);
}
```

Updating `last_run_at = now()` is critical: it prevents immediate retry storms and enforces the configured plan cadence (HOURLY, DAILY, WEEKLY) before the plan can be picked up again.

---

## 3. Verification & Test Evidence

The lease coordination, fencing, and recovery implementation is verified by integration tests using real PostgreSQL 16 Testcontainers in [`SearchPlanConcurrencyAndRecoveryIT.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/test/java/com/personal/jobagent/discovery/plan/SearchPlanConcurrencyAndRecoveryIT.java):

1. **`testConcurrentWorkersMutualExclusion`**:
   - 10 concurrent threads attempt to execute the exact same search plan simultaneously.
   - **Result:** Exactly 1 worker acquires the lease and executes; 9 workers fail-closed and return 0 results. Database terminal state records `last_execution_status = 'SUCCESS'`.
2. **`testParallelExecutionOfDifferentPlans`**:
   - 2 workers execute 2 different search plans concurrently.
   - **Result:** Both plans execute concurrently and both succeed without cross-plan interference.
3. **`testFencingPreventsExpiredWorkerFromCompleting`**:
   - Worker A claims a lease with a 1-second timeout and enters an artificial delay.
   - Lease expires; Worker B takes over and claims the lease with a new owner token.
   - Worker A attempts `completeExecution`.
   - **Result:** Worker A is fenced out (0 rows updated). Worker B successfully completes the execution.
4. **`testStaleExecutionRecovery`**:
   - A search plan is seeded in `RUNNING` with an expired lease timestamp.
   - `recoverStaleExecutions` is executed.
   - **Result:** The stale plan is transitioned to `FAILED` with descriptive error details and `last_run_at = now()`.
5. **`testManualPlansExcludedFromScheduler`**:
   - Plans with `MANUAL` frequency are ignored by `findDuePlans`, even if active and never run.
