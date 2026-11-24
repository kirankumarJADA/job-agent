# Phase 2B: Execution Lease Renewal Design

## 1. Executive Summary

In Phase 2A, search plan execution coordination was protected by a coarse 300-second fixed lease without background heartbeat renewal. Under sustained network delay or heavy provider paging, active executions risked lease expiration, exposing the system to concurrent execution takeover, duplicate ingestion, and uncoordinated state mutation.

Phase 2B introduces **atomic, heartbeat-driven execution lease renewal** with fail-closed cooperative cancellation, bounded HTTP provider timeouts, in-flight discard semantics, and clean daemon lifecycle management.

---

## 2. Lease Lifecycle Architecture

### 2.1 Lease Acquisition
When `SearchPlanExecutionCoordinator` or `SearchPlanScheduler` claims a due search plan, it acquires an execution lease via `SearchPlanRepository.acquireExecutionLease(planId, ownerToken, leaseDurationMs)`:
```sql
UPDATE search_plans
SET last_execution_status = 'RUNNING',
    execution_owner_token = :ownerToken,
    execution_lease_expires_at = now() + (:leaseDurationMs || ' milliseconds')::interval,
    updated_at = now()
WHERE id = :planId
  AND (execution_lease_expires_at IS NULL OR execution_lease_expires_at < now());
```
- **Owner Token**: Cryptographically random `UUID` (`UUID.randomUUID()`) unique to the specific execution run.
- **Lease Duration**: Configurable via `app.discovery.search-plan.lease-duration-ms` (default: `60,000 ms` / 60 seconds).
- **Lease Expiry**: Evaluated strictly using PostgreSQL server-side time (`now()`), eliminating host clock skew vulnerabilities.

### 2.2 Heartbeat Renewal Cadence
A dedicated background daemon periodically renews the lease via `SearchPlanRepository.renewExecutionLease(planId, ownerToken, leaseDurationMs)`:
```sql
UPDATE search_plans
SET execution_lease_expires_at = now() + (:leaseDurationMs || ' milliseconds')::interval,
    updated_at = now()
WHERE id = :planId
  AND execution_owner_token = :ownerToken
  AND execution_lease_expires_at > now();
```
- **Interval**: Configurable via `app.discovery.search-plan.heartbeat-interval-ms` (default: `15,000 ms` / 15 seconds).
- **Renewal Ratio**: `Heartbeat Interval (15s) = 0.25 × Lease Duration (60s)`. A worker attempts renewal up to 4 times within a single lease window before lease expiration.
- **Atomic Precondition**: Renewal updates exactly 1 row if and only if the current database row holds the exact `execution_owner_token` and the lease has not expired. If a lease was stolen or expired, 0 rows are updated.

### 2.3 Thread Model & Daemon Lifecycle
- **Executor**: Daemon thread pool configured as `Executors.newScheduledThreadPool(corePoolSize, threadFactory)` with `thread.setDaemon(true)` and naming format `search-plan-heartbeat-%d`.
- **Spring Lifecycle Integration**: Implements `org.springframework.beans.factory.DisposableBean`. On application context shutdown, `destroy()` terminates active tasks, initiates graceful shutdown (`shutdown()`), and forces termination (`shutdownNow()`) after a 5-second grace period.
- **Per-Execution Cancellation**: The per-run heartbeat task (`ScheduledFuture<?>`) is explicitly cancelled with interruption in the execution `finally` block:
  ```java
  if (heartbeatFuture != null) {
      heartbeatFuture.cancel(true);
  }
  ```

---

## 3. Cooperative Cancellation & Fail-Closed Semantics

### 3.1 State Representation
Each execution coordinates cancellation via a thread-safe `AtomicBoolean ownershipLost = new AtomicBoolean(false)`.

### 3.2 Heartbeat Loss Trigger
If the heartbeat execution receives:
1. `renewExecutionLease(...) == false` (0 rows updated), or
2. An unrecoverable database communication error,
it immediately invokes:
```java
ownershipLost.set(true);
log.error("Failed to renew lease for search plan {}; marking ownership lost", planId);
```

### 3.3 Cooperative Checkpoints
The worker cooperatively checks `ownershipLost.get()` across all execution phases:
1. **Pre-Provider Call**: Before querying any external provider adapter (`BoardDiscoveryService.run`).
2. **Post-Provider Call**: Immediately after `BoardDiscoveryService.run` returns, before inspecting or parsing provider output.
3. **Pre-Ingestion**: In `BoardDiscoveryService.run` prior to iterating or writing listing entries to `jobs`.
4. **Pre-Matching**: In `SearchPlanExecutionCoordinator` prior to invoking `JobMatchService.evaluateMatch`.
5. **Within Match Iteration**: Before each candidate job evaluation.

If `ownershipLost.get()` evaluates to `true`:
- All subsequent provider queries are skipped.
- All candidate evaluations are aborted.
- Any in-flight results are discarded.
- Execution terminates with status `FAILED` and records: `"Execution lease lost or expired during processing"`.

---

## 4. Provider Timeout Bounds & In-Flight Discard Semantics

### 4.1 Bounded Network Latency
External provider HTTP calls execute through `SafeHttpClient`, which enforces strict network socket and connect timeouts (typically 5–10 seconds per request).
- Bound relationship: `HTTP_TIMEOUT (5-10s) < HEARTBEAT_INTERVAL (15s) < LEASE_TTL (60s)`.
- Even under worst-case network stalls, HTTP calls return or time out before the lease TTL expires, ensuring heartbeats can proceed or timeouts are caught cleanly.

### 4.2 In-Flight Discard Guarantees
If a JVM pause, OS scheduling anomaly, or network freeze causes an HTTP call to span past lease expiry:
1. The heartbeat will have failed to renew or the lease will have expired.
2. A new worker (Worker B) may have claimed the plan.
3. Upon returning from the HTTP call, Worker A checks `ownershipLost.get()`. If true, it logs:
   ```
   Discarded provider results for source <Name>: lease lost in-flight
   ```
4. Worker A skips `report.ingested()` and does not enter any ingestion or matching code.
5. In addition, even if Worker A attempted to write, the row-level fencing lock (`SELECT ... FOR NO KEY UPDATE`) in `SearchPlanRepository.assertLeaseOwnership` immediately rejects the write with `ExecutionFencedException`.

---

## 5. Failure Handling Analysis

| Failure Scenario | Mechanism | Result |
| :--- | :--- | :--- |
| **Transient DB Hiccup (1-5s)** | Heartbeat catches error, next tick in 15s retries. | Lease has 60s TTL; subsequent heartbeat succeeds without dropping lease. |
| **Prolonged DB Partition (>60s)** | Renewal fails repeatedly; TTL expires on DB. | Worker sets `ownershipLost = true`, halts immediately, records `FAILED`. |
| **JVM GC Freeze (>60s)** | Heartbeat pauses; PostgreSQL lease expires. | Another worker can claim plan. When frozen JVM resumes, subsequent write throws `ExecutionFencedException`. |
| **Concurrent Scheduler Race** | Atomic `UPDATE ... WHERE lease_expires < now()` | Exactly one worker succeeds (`rows == 1`); competing scheduler receives 0 rows and exits. |
| **Crash / Hard Kill** | No process running; lease expires in 60s. | Stale recovery automatically frees or claims plan on the next scheduler sweep. |
