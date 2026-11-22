# Phase 2B: Side-Effect Fencing Architecture

## 1. Executive Summary

A critical vulnerability in distributed batch processing is the **split-brain write race**: when Worker A suffers a network delay or long-running processing loop, its lease may expire and be acquired by Worker B. If Worker A continues processing and performs database writes without fencing, it can overwrite fresh data produced by Worker B, insert duplicate job records, or emit duplicate notification events.

Phase 2B implements **authoritative side-effect fencing** using PostgreSQL row-level locks (`FOR NO KEY UPDATE`) inside short write transactions. Every state-mutating boundary—`jobs`, `job_source_observations`, `job_matches`, and `outbox_events`—is strictly fenced.

---

## 2. Fencing Mechanism: `FOR NO KEY UPDATE`

In `SearchPlanRepository`:
```java
public void assertLeaseOwnership(UUID planId, UUID ownerToken) {
    if (planId == null || ownerToken == null) {
        return; // Non-search-plan operations are unbounded
    }
    String sql = """
            SELECT 1 FROM search_plans
            WHERE id = ?
              AND execution_owner_token = ?
              AND execution_lease_expires_at > now()
            FOR NO KEY UPDATE
            """;
    List<Integer> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getInt(1), planId, ownerToken);
    if (rows.isEmpty()) {
        throw new ExecutionFencedException(
                "Search plan execution lease lost or expired for plan " + planId + " with token " + ownerToken);
    }
}
```

### Why `FOR NO KEY UPDATE`?
1. **Serializes Concurrent Mutations**: Acquires an exclusive row lock on the `search_plans` row for the duration of the short write transaction.
2. **Eliminates Check-Then-Write Races**: While Worker A holds the row lock in its commit transaction, Worker B cannot acquire the lease or mutate the owner token until Worker A's transaction finishes.
3. **Fail-Closed on Lease Steal**: If Worker B has already acquired the lease, the `execution_owner_token` or `execution_lease_expires_at` condition fails immediately, returning 0 rows and throwing `ExecutionFencedException` before any write can occur.
4. **No Foreign Key Blocking**: Unlike `FOR UPDATE`, `FOR NO KEY UPDATE` does not block foreign key checks from concurrent readers.

---

## 3. Protected Write Boundaries

### 3.1 Job Ingestion & Observations (`jobs`, `job_source_observations`)
- **Boundary**: `JobDiscoveryService.ingestJob(command, searchPlanId, executionOwnerToken)`
- **Transaction**: Annotated with `@Transactional`.
- **Fencing Action**: Invokes `searchPlanRepository.assertLeaseOwnership(searchPlanId, executionOwnerToken)` as the very first operation inside the transaction.
- **Outcome**: If the lease is lost, the entire ingestion transaction rolls back. Neither `jobs` nor `job_source_observations` receives stale writes.

### 3.2 Job Matching & Scores (`job_matches`)
- **Boundary**: `JobMatchService.evaluateMatch(jobId, profileId, preferences, searchPlanId, executionOwnerToken)`
- **Transaction**: Executed inside `transactionTemplate.execute(...)`.
- **Fencing Action**: Invokes `searchPlanRepository.assertLeaseOwnership(searchPlanId, executionOwnerToken)` prior to persisting match evaluation scores or updating `job_matches`.
- **Outcome**: If the lease is lost, no stale scores, rankings, or match records are written.

### 3.3 Outbox Events & Notifications (`outbox_events`)
- **Boundary**: Outbox events emitted during job discovery (`JOB_DISCOVERED`) and job matching (`JOB_MATCHED`).
- **Transactional Integration**: In Robin architecture, `outbox_events` are inserted within the same database transaction as the entity mutation.
- **Fencing Action**: Because `assertLeaseOwnership` guards the enclosing transaction, an expired worker cannot insert records into `outbox_events`.
- **Outcome**: Prevents duplicate email, push, or Slack notifications caused by duplicate or zombie worker executions.

---

## 4. Transaction Scoping & Performance

### 4.1 Strict Separation of I/O and DB Transactions
Database transactions are **never** held open across external network calls.
```
Worker Timeline:
[HTTP Call to Provider (5-10s)]  --> No DB transaction held
       │
[Check isLeaseOwner (pre-flight)]
       │
[Begin Transaction (0.5ms)]      --> Short DB write transaction
   ├── SELECT ... FOR NO KEY UPDATE
   ├── INSERT INTO jobs
   ├── INSERT INTO job_source_observations
   └── INSERT INTO outbox_events
[Commit Transaction (1.5ms)]
```
- Network latency has zero effect on database connection hold times or lock contention.
- Database write transactions last single-digit milliseconds (typically 1–3 ms).

### 4.2 Non-Search-Plan Operations Remain Unaffected
All original single-argument and overloaded methods without lease parameters continue to function identically:
```java
// Manual or direct ingestion
public IngestResult ingestJob(IngestJobCommand command) {
    return ingestJob(command, null, null);
}

// Standard match evaluation
public Optional<JobMatchResult> evaluateMatch(UUID jobId, UUID profileId, JobMatchPreference preferences) {
    return evaluateMatch(jobId, profileId, preferences, null, null);
}
```
When `searchPlanId == null` or `executionOwnerToken == null`, `assertLeaseOwnership` immediately returns with zero SQL execution, ensuring zero performance overhead for manual imports, direct board sweeps, and system maintenance tasks.

---

## 5. Exception Handling & Rollback Protocol

When `ExecutionFencedException` is thrown:
1. The active transaction immediately rolls back completely.
2. `BoardDiscoveryService` catches the exception, halts further listing iteration, and rethrows to `SearchPlanExecutionCoordinator`.
3. `SearchPlanExecutionCoordinator` catches `ExecutionFencedException`:
   - Sets `ownershipLost.set(true)`.
   - Halts all remaining provider queries and match evaluations.
   - Logs: `log.warn("Execution fenced out for search plan {}...: {}", plan.id(), e.getMessage())`.
   - Records failure status in execution history without overwriting newer state set by Worker B.
