# Robin — Production Redis / Spring Lettuce Connection Timeout Investigation

**Date:** 2026-11-16
**Service:** Render `job-agent` (`srv-dan7t1oae00c73dqu1d0`), Render **Free** plan (0.1 CPU, 512 MB)
**Redis provider:** Upstash (TLS, port 6379), credentials unchanged
**Stack:** Spring Boot 3.3.4 · Spring Data Redis 3.3.4 · Lettuce 6.3.2.RELEASE · Netty 4.1.113.Final · Java 21
**Branch:** `fix/redis-diagnostic-cold-start`, not merged and not deployed

## Classification

**ROOT CAUSE CONFIRMED** and **FIX IMPLEMENTED AND TESTED** (locally). Nothing has been deployed to production.

## 1. Summary

Spring Data Redis / Lettuce **does** establish the application's Redis connection in production. On both production startups recorded on 2026-11-16, the application's shared native connection opened, authenticated and answered `INFO`:

| Instance | Shared connection opened | `INFO` | Outcome logged by the same class |
|---|---|---|---|
| `z2j2b` (19:44 UTC) | 9,299 ms | 697 ms | `result=UP` |
| `j292j` (19:48 UTC) | 11,700 ms | 314 ms | `result=UP` |

Both reported failure symptoms come from `RedisConnectivityDiagnostics` itself, not from Redis or Lettuce:

1. **The ~3 s "acquisition timeout"** is the diagnostic's own fixed observation budget (`SPRING_FACTORY_INFO_TIMEOUT = 3s`). The application's first Lettuce TLS connection needs 9–12 s on this host. The headline verdict (`result=DOWN, errorCategory=TIMEOUT`) was taken from that 3 s timer, not from the connection's real outcome, which the same class logged as `UP` about a second earlier.
2. **`RedisConnectionException: Connection closed prematurely`** is self-inflicted. The diagnostic's `spring-equivalent` probe watched its own connection for only 2 s plus a 1.5 s grace period. Its `finally { client.shutdown(); }` then closed the channel 0.9 s after its TLS handshake had completed, before the Redis `HELLO` reply arrived. Lettuce reports any channel close before the Redis handshake finishes as "Connection closed prematurely", whichever side closed it.

The slowness itself is CPU starvation on the Render Free instance (0.1 CPU) immediately after a 217-second application startup. The diagnostic made it worse by starting three additional Lettuce clients, each with its own event loops and TLS context, while the application's connection was still being established.

No credential, provider, environment variable or Lettuce client timeout was wrong, and none was changed.

## 2. Exact root cause

`backend/src/main/java/com/personal/jobagent/system/RedisConnectivityDiagnostics.java` (before this fix):

1. `diagnose()` observed the real Spring path (`LettuceConnectionFactory.getConnection()` + `INFO`) for a fixed `SPRING_FACTORY_INFO_TIMEOUT = 3s`. On expiry it recorded `springSharedNativeInfo:FAIL` and the final verdict was `DOWN/TIMEOUT`. The connection attempt itself continued and succeeded. Its real result was only written to a separate, later log line (`Redis Spring shared-native comparison completed: ... result=UP`).
2. After that 3 s expiry, `diagnose()` always ran `rawLettuceProbe()`. That method builds three more complete Lettuce clients (`spring-equivalent`, `raw-resp2`, `raw-auto-2s`), each with fresh `DefaultClientResources`, so each gets new Netty event loops, a computation pool, a timer and an SslContext. They ran **while the application's connection was still in flight**, on a 0.1 CPU container.
3. Each comparison mode waited `RAW_PHASE_TIMEOUT` (2 s) plus `LATE_EVENT_GRACE` (1.5 s), then always called `client.shutdown()` and `resources.shutdown()`. `AbstractRedisClient.shutdown()` closes every channel in the client's `ChannelGroup` (Lettuce `AbstractRedisClient.closeResources()`, `channels.close()`). A channel joins that group in `ChannelGroupListener.channelActive`, which runs at TCP connect, before TLS or `HELLO` complete. Closing it then fires `RedisHandshakeHandler.channelInactive()`, which fails the connection with `new RedisConnectionException("Connection closed prematurely")`.

Without the diagnostic, the same cold connection on the same host is slow (about 9–12 s) but succeeds within the client's real budgets: 2 s TCP connect, then 10 s connection initialization measured from channel registration. The 10 s initialization timeout added earlier (commit `329ed0a`) is correct and necessary. The observed cold handshake used about 2–3 s of it, while the old 2 s budget would have failed.

## 3. Evidence

### 3.1 Production logs (Render, read-only)

Host, IP addresses and credential-derived fields are redacted below.

**Instance `j292j`**: the "Spring-equivalent" lifecycle on the probe's own clock:

| ms | Event | Meaning |
|---|---|---|
| 3,604 | `bootstrapPrepared` | 3.6 s of purely local work (new client resources, bootstrap) |
| 4,302 | `connectionFutureCreated` | `connectAsync` returns; the 2 s observation starts |
| 5,100 | `channelInitialized` | SslContext and SSLEngine built locally |
| 5,805 | `channelActive` | TCP connected; `HELLO` queued behind TLS |
| 7,301 | `sslHandshakeComplete` | TLS 1.3 complete; `HELLO` now sent |
| ~7,800 | `lateEventGraceComplete/1500ms` | 2 s observation + 1.5 s grace expire; `finally` → `client.shutdown()` |
| 8,206 | `channelInactive` | Channel closed by the client's own shutdown |
| 8,401 | `connectionFutureFailed[CONNECTION]` | `RedisConnectionException[Unable to connect to <upstash-host>:6379] <- CompletionException <- RedisConnectionException[Connection closed prematurely]` |

```
Redis Spring shared-native comparison completed: elapsedMs=12111, result=UP, errorCategory=none,
  phases=factoryAcquire:FAIL/3095ms[observationTimeout,inFlight=true,completionTracked=true],
         factoryAcquire:OK/11700ms,factoryInfo:OK/314ms,factoryRelease:OK/0ms        (19:48:23.713Z)

Redis connectivity diagnosis: client=Lettuce, host=<upstash-host>, port=6379, sslEnabled=true,
  authenticationConfigured=true, effectiveClient=shareNativeConnection=true,commandTimeout=2000ms,
  connectTimeout=2000ms,applyConnectionTimeout=true,dnsResolver=JVM_DEFAULT, result=DOWN,
  errorCategory=TIMEOUT, phases=dns:OK/299ms;tcp:OK/100ms;tls:OK/1702ms[protocol=TLSv1.3];
  springSharedNativeInfo:FAIL/3095ms[...observationTimeout...];protocolComparison:OK/10102ms[
  spring-equivalent.FAIL[... Connection closed prematurely ...];
  raw-resp2{...connect:OK/500ms,auth:OK/500ms,ping:OK/112ms};
  raw-auto-2s{...connect:OK/398ms,auth:OK/398ms,ping:OK/99ms}]                       (19:48:24.803Z)
```

**Instance `z2j2b`** shows the same false verdict, and also shows the `spring-equivalent` probe **succeeding** and still being reported as a failure:

```
Redis Spring shared-native comparison completed: elapsedMs=9997, result=UP,
  phases=...,factoryAcquire:OK/9299ms,factoryInfo:OK/697ms,...                       (19:44:26.587Z)

Redis connectivity diagnosis: ... result=DOWN, errorCategory=TIMEOUT, phases=dns:OK/10ms;tcp:OK/4ms;
  tls:OK/894ms;springSharedNativeInfo:FAIL/3097ms[...observationTimeout...];
  protocolComparison:OK/8000ms[spring-equivalent.FAIL[...eventualResult=completed,...
  sslHandshakeComplete/5202ms,ConnectionActivatedEvent/5801ms,connectionFutureCompleted/5802ms,...];
  raw-resp2{...connect:OK/495ms,...ping:OK/16ms}; raw-auto-2s{...connect:OK/300ms,...ping:OK/14ms}]
```

Searches of the last 7 days of production application logs:

- `RedisConnectionFailureException` returned **no matches**. Spring Data translates every failed connection attempt into this exception, so no connection attempt by the application's own Redis path failed within the retained 7-day log window.
- `health check failed` returned **no matches**, so the Actuator Redis health indicator never logged a failure.

### 3.2 CPU starvation on the Render Free instance

- The Render Compute page shows the `job-agent` instance type as **Free: 0.1 CPU, 512 MB RAM**.
- `Started JobAgentApplication in 217.407 seconds (process running for 229.604)`.
- A plain JDK TLS handshake to Upstash took 894 ms on `z2j2b` and 1,702 ms on `j292j`, while the network round trip is small (TCP connect 4 ms on `z2j2b`, `PING` 14–16 ms).
- Lifecycle timestamps cluster on ~100 ms boundaries (3,399 · 3,502 · 4,000 · 5,202 · 5,801 · 6,000 ms). That is the signature of a CFS CPU quota with a 100 ms period: 10 ms of CPU per period at 0.1 CPU.
- `PING` measured 99–112 ms on `j292j` versus 14–16 ms on `z2j2b`. That difference is scheduler delay, not network latency.

### 3.3 Lettuce 6.3.2 source (read from the published sources jar)

- `io.lettuce.core.protocol.RedisHandshakeHandler#channelInactive` fails with `"Connection closed prematurely"` whenever the channel goes inactive before the handshake completes and before the initialization timer fires. It does not distinguish a client-side close from a server-side close.
- `io.lettuce.core.AbstractRedisClient#closeResources` calls `channels.close()` on every channel the client owns.
- `io.lettuce.core.ChannelGroupListener#channelActive` adds the channel to that group at TCP-connect time.
- `io.lettuce.core.ConnectionBuilder#apply` copies `RedisURI.getTimeout()` into `RedisHandshakeHandler`'s initialization timeout, which starts at channel registration.
- `io.lettuce.core.SslConnectionBuilder` uses Netty's `SslHandler` (handshake timeout 10 s by default). The `HELLO` command is written at `channelActive` and buffered until TLS completes.

### 3.4 Local reproduction, using no production resources

The new `RedisLettuceConnectionLifecycleIT` runs the application's own `RedisLettuceConfiguration` against a disposable Redis 7.4 container. The container serves plain and TLS listeners and requires a password. A loopback proxy delays Redis's first reply by 4 s to model the slow cold handshake. Results against the **unchanged** code:

- The diagnostic reported `springSharedNativeInfo:FAIL/3014ms[...observationTimeout,inFlight=true...]` and `result=DOWN` against a Redis that was healthy, only slow.
- The `spring-equivalent` probe produced the production chain exactly: `lateEventGraceComplete/1506ms`, then `channelInactive/3567ms`, then `RedisConnectionException[Unable to connect to /127.0.0.1:<port>] <- CompletionException[...Connection closed prematurely] <- RedisConnectionException[Connection closed prematurely]`. The proxy never closed any connection; the diagnostic's own teardown did.
- The other seven lifecycle tests passed on the unchanged code: TLS + AUTH + PING, reuse, recovery, restart, slow handshake and command timeout. The application's Redis client was already correct.

A new unit test, `RedisTlsBindingTest.clientSideCloseDuringTheRedisHandshakeIsReportedAsConnectionClosedPrematurely`, proves the Lettuce behaviour directly. A local server accepts the connection and never closes it, the **client** calls `shutdown()` mid-handshake, and the future fails with exactly `Connection closed prematurely`.

## 4. Why raw Redis works while Spring/Lettuce appears to fail

Order of execution and CPU contention explain it. Protocol, credentials, DNS and TLS configuration do not:

| Order | Attempt | JVM state when it ran | Real outcome | What the diagnostic reported |
|---|---|---|---|---|
| 1 | Application's shared connection (Spring factory) | Cold: first Lettuce/Netty TLS connection, right after a 217 s startup | **UP** after 9.3 s / 11.7 s | FAIL at 3 s |
| 2 | `spring-equivalent` (new client) | Cold, and concurrent with #1 for the same 0.1 CPU | Connected at 5.8 s (`z2j2b`) / killed by its own teardown (`j292j`) | FAIL |
| 3 | `raw-resp2` | Warm: classes loaded and JIT-compiled, #1 finished | OK, ~0.5 s | OK |
| 4 | `raw-auto-2s` | Warm | OK, ~0.3–0.4 s | OK |

`raw-auto-2s` uses the same automatic protocol negotiation as Spring (`HELLO 3 AUTH default …`) and succeeded, so RESP2 vs RESP3 is irrelevant. The "raw" probes were not taking a different path. They simply ran last, on a warmed-up JVM, after the competing work had finished.

## 5. Answers to the investigation checklist

| # | Question | Finding |
|---|---|---|
| 1 | Versions and compatibility | Spring Boot 3.3.4 → Spring Data Redis 3.3.4 + Lettuce 6.3.2.RELEASE (+ Reactor 3.6.10, Netty 4.1.113.Final). This is the Boot-managed, compatible set; no defect is implicated. |
| 2 | Redis URI and TLS | `rediss`, verify-peer FULL, SNI = host, password attached to the URI (AUTH during `HELLO`). The TLS 1.3 handshake succeeds in every production attempt. Correct. |
| 3 | Connect vs command timeout | 2 s TCP connect and 2 s command timeout are separate from the 10 s connection-initialization timeout (`RedisURI` timeout, set by `InitializationTimeoutLettuceConnectionFactory`). Correct, and verified by tests. |
| 4 | Shared native connection lifecycle | `shareNativeConnection=true`, created lazily under Spring Data's lock by the first caller and then reused. The new IT shows the same native connection reused across 25 acquire/release cycles with no client-count growth. |
| 5 | Factory init and shutdown | The factory starts without connecting (`eagerInitialization=false`). `stop()`/`start()` and full context restart reconnect cleanly (new IT). |
| 6 | Connections closed prematurely? | Only the diagnostic's own probe connections, closed by its own `client.shutdown()`. The application's shared connection was never closed prematurely. |
| 7 | Diagnostic interference | Yes: three extra Lettuce clients built concurrently with the in-flight application connection on a 0.1 CPU host, plus a false verdict. **Fixed.** |
| 8 | Is the 2 s timeout too aggressive? | Not for the client. Command and TCP connect use ~4–112 ms in steady state, and connection establishment is bounded by the separate 10 s initialization budget. The aggressive budgets were the diagnostic's fixed 3 s and 2 s + 1.5 s timers. **Fixed.** |
| 9 | Pooling, DNS, keepalive, protocol | No pooling (single multiplexed connection, recommended for Lettuce). JVM DNS resolver: 5 IPv4 addresses, 10–299 ms. Keepalive: Lettuce default. Protocol: AUTO works (see §4). None are implicated. |
| 10 | Diagnostic only, or real operations too? | **Diagnostic only.** The real Spring path (`getConnection()` + `INFO`) succeeded on both instances, and there were no Redis failure logs in 7 days. |

## 6. Files and settings involved

| File | Role |
|---|---|
| [`RedisConnectivityDiagnostics.java`](../backend/src/main/java/com/personal/jobagent/system/RedisConnectivityDiagnostics.java) | **Defect location.** Post-startup diagnostic (`ApplicationReadyEvent`, background daemon thread). |
| [`RedisLettuceConfiguration.java`](../backend/src/main/java/com/personal/jobagent/config/RedisLettuceConfiguration.java) | Application `LettuceConnectionFactory` + `ClientResources`. Correct, unchanged. |
| [`application.yml`](../backend/src/main/resources/application.yml), [`application-prod.yml`](../backend/src/main/resources/application-prod.yml) | `spring.data.redis.timeout=2000ms`, `connect-timeout=2s`, `app.redis.connection-initialization-timeout=10s`. Values unchanged; comments corrected. |
| [`pom.xml`](../backend/pom.xml) | Spring Boot 3.3.4 parent. Unchanged. |

Effective production settings, taken from the diagnostic log (non-secret): SSL on, authentication configured, `shareNativeConnection=true`, command timeout 2,000 ms, connect timeout 2,000 ms, `applyConnectionTimeout=true`, DNS resolver `JVM_DEFAULT`, initialization timeout 10,000 ms. The Redis-related Render variables are `SPRING_REDIS_HOST`, `SPRING_REDIS_PORT`, `SPRING_REDIS_PASSWORD` and `SPRING_REDIS_SSL`; none were read, changed or needed changing.

## 7. Changes made (branch `fix/redis-diagnostic-cold-start`)

**`RedisConnectivityDiagnostics.java`**, the only production class changed:

1. **Observation budget derived from the client's own configuration.** The fixed 3 s budget is replaced by connect timeout + initialization timeout + command timeout + a 10 s cold-bootstrap allowance. In production that is 2 + 10 + 2 + 10 = **24 s**, about 2× the slowest observed successful connection (11.7 s).
2. **Truthful verdicts.** `UP` and `DOWN` now come from the connection's terminal outcome. If the budget elapses while the attempt is still legitimately running, the result is `INCONCLUSIVE` / `OBSERVATION_TIMEOUT` with the phase marked `PENDING`, never a false `DOWN`. The late-completion log line is unchanged.
3. **No interference.** The protocol comparison runs only after the Spring path has **terminally failed**. It is skipped, with the reason logged, when the Spring path is `UP` or still in flight. In production's normal case, no extra Lettuce clients are created.
4. **No self-inflicted teardown.** Each comparison mode now waits for its own connect + handshake budget before shutting its client down, so it reports the real outcome instead of "Connection closed prematurely". `raw-resp2` now uses the same initialization budget as `spring-equivalent` (previously the 60 s Lettuce default), so the two differ only by protocol.
5. The diagnosis log line now also prints `initializationTimeout` and `observationBudget`.

**`application.yml` / `application-prod.yml`:** comments only. The old comments claimed the client timeouts "must stay below the diagnostic's 3s budget", which was the false premise behind this misreport.

**Tests:**

- New `RedisLettuceConnectionLifecycleIT` (Testcontainers Redis 7.4, TLS + password, delaying/stalling/rejecting proxy) with 9 tests: TLS + AUTH + PING + INFO; wrong and missing credentials → `AUTHENTICATION`; shared-connection release and reuse over 25 cycles with no connection growth; recovery after failed initial attempts; factory `stop()`/`start()` and full application restart; slow handshake inside the initialization budget; 2 s command timeout still enforced and the shared connection reusable afterwards; diagnostic reports a slow but healthy Redis as `UP`; protocol comparison reports real per-mode outcomes.
- `RedisConnectivityDiagnosticsTest`: the old observation-timeout test now asserts `INCONCLUSIVE`/`PENDING` with no comparison started. New tests cover slow-but-successful → `UP` with no comparison, the comparison running only after a terminal failure, and budget derivation (Lettuce factory and properties fallback).
- `RedisTlsBindingTest`: a client-side close during the handshake yields "Connection closed prematurely" (no Docker needed). One test was renamed to drop the obsolete 3 s rationale.

## 8. Test results

| Run | Result |
|---|---|
| New IT against **unchanged** code | 7/9 pass. The 2 diagnostic tests fail with production's exact signature (§3.4). |
| New IT after the fix | **9/9 pass** (34 s) |
| Redis unit tests after the fix | **51/51 pass**. With disposable local Redis fixtures on 6379/6380, 0 skipped, including the A/B experiment and the authenticated three-mode comparison. |
| Full backend unit suite (`mvn verify`, surefire) | **1,526 run, 0 failures, 0 errors**, 6 skipped (the six Redis tests that need manually started local fixtures; passed separately above) |
| Full backend integration suite (`mvn verify`, failsafe: Spring Boot contexts on Testcontainers PostgreSQL covering startup, Flyway, auth, CSRF, CORS, seeded-credential guards, outbox, discovery, track, mail) | **303 run: 274 passed, 1 failed, 28 skipped.** The failure, `CrossSourceDiscoveryIT.twoDifferentRolesWithTheSameEmployerTitleAndLocationStaySeparate` (a discovery dedup-key assertion), **fails identically on untouched `master` (`c2ffe8d`)** in a separate checkout, so it predates this work and is unrelated to Redis. The 28 skips are opt-in suites: 21 `UserDataIsolationIT`, 3 external-database ITs, 4 live-ingestion ITs. |
| `UserDataIsolationIT`, run opt-in against a disposable PostgreSQL 17.11 | **21/21 pass.** Flyway migrated an empty database to V052, the full application context started, and the diagnostic reported the deliberately absent Redis correctly as `DOWN/CONNECTION` (`tcp:FAIL ... ConnectException`, with `observationBudget=24000ms` logged). |

## 9. Production risk assessment

- **Scope:** one diagnostic class that runs once per startup on a background daemon thread, plus YAML comments. It does not touch `LettuceConnectionFactory`, `ClientResources`, client timeouts, credentials, the Redis provider, the datasource, Flyway, security, schedulers, the outbox or maintenance mode. Maintenance mode and paused workers are environment-driven (`SPRING_APPLICATION_JSON` / `APP_MAINTENANCE_ENABLED`) and unaffected by a code deploy.
- **Expected production effect:** the startup log reports `result=UP` with the real connection time instead of a false `DOWN/TIMEOUT`. On success, the three comparison clients are no longer created, so right after startup there is less CPU contention on the 0.1 CPU instance and the application's first Redis connection should get faster, not slower.
- **Risks:**
  - Log-format change (`PENDING`, `SKIPPED[reason]`, `INCONCLUSIVE`, two new fields) affects anyone grepping for the old strings.
  - If Redis is genuinely down, the diagnosis line appears up to ~24 s later, plus up to ~30 s for the comparison, all in the background.
  - The deploy itself restarts the service. On this plan that is a ~4-minute cold start, but the previous deploy shows Render's zero-downtime swap: old instance `z2j2b` closed at 19:48:12, one second before new instance `j292j` went live.
- **Rollback:** revert the merge commit on `master` (auto-deploys), or use Render's rollback to the previous deploy. No data or schema is involved.

## 10. Recommended controlled deployment steps

**STOPPED here. Nothing has been merged, pushed or deployed.** Render's `job-agent` service builds `kirankumarJADA/job-agent@master` with **Auto-Deploy: On Commit**, so **merging or pushing to `master` is a production deploy**.

1. Review the branch diff (`git diff master...fix/redis-diagnostic-cold-start`). Optionally open a PR without merging.
2. Choose a deploy window. Avoid Render's announced infrastructure maintenance (2026-11-18 02:00 UTC). Confirm with the operator that no Render environment changes are pending; this release needs none.
3. On approval, merge to `master`. Render auto-builds `backend/Dockerfile` and deploys.
4. Watch the deploy logs for:
   - `Started JobAgentApplication`, Flyway at V052 up to date, the maintenance-mode / outbox-paused / scheduler-paused lines (unchanged behaviour).
   - The new diagnosis line: `result=UP`, `springSharedNativeInfo:OK/<connect time>`, `protocolComparison:SKIPPED[springSharedNativeInfo=UP]`, `observationBudget=24000ms`, and **no** `Connection closed prematurely`.
5. Verify `GET /api/v1/system/health` returns 200 (the Render health check, DB only). Optionally check `GET /actuator/health` (component status only; it runs one `INFO` over Redis).
6. Rollback criteria: startup failure, health check not 200, or any new error unrelated to the diagnostic. Roll back with Render "Rollback" or `git revert` on `master`.

## 11. Safety compliance during this investigation

- Upstash credentials were not read, changed or regenerated. No secret values appear in this report; host, IPs and credential-derived fields are redacted.
- Production Redis was not replaced. No Render environment variables or settings were changed. Dashboard pages (logs, compute, settings) were only read.
- The production service was not restarted, redeployed or woken. No requests were made to the production app.
- Oracle PostgreSQL was not touched. Maintenance mode, schedulers and outbox dispatch remain as configured.
- No paid infrastructure was created. All testing used disposable local Docker containers.
- Nothing was pushed, and nothing was committed to `master`.

## 12. Further observations (not changed)

- **The Render Free plan is why cold connections are slow.** It caused the 217 s startup, ~1–2 s TLS handshakes and a ~10 s first Redis connection, and a free instance spins down after inactivity, so every wake-up repeats the cold start. Only a larger instance (for example 0.5 CPU) would make the first connection fast. That is a paid change and the owner's decision.
- **The protocol comparison was investigation scaffolding.** It is now dormant whenever Redis is healthy. Once the fixed diagnostic has been verified in production, consider deleting it (`rawLettuceProbe` and its lifecycle instrumentation) to shrink the class.
- **Pre-existing failing test on `master`:** `CrossSourceDiscoveryIT.twoDifferentRolesWithTheSameEmployerTitleAndLocationStaySeparate` expects two distinct roles with the same employer, title and location to keep sharing the old title-based dedup key (line 151). Since the dedup-hierarchy change in `35a1f0a` they no longer do. Either the test is stale or that change regressed. Until it is resolved, `mvn verify` is not green on `master` and cannot serve as a release gate.
- **Test-only dependency clash:** Testcontainers 2.0.5's file copy (`withCopyFileToContainer`) fails under Spring Boot 3.3.4's managed commons-lang3 (`NoSuchMethodError: SystemProperties.getUserName`). The new IT works around it. Future tests that copy files into containers will need commons-lang3 overridden in test scope.
