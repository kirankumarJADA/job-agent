# Phase 1 Testing Strategy

Builds on what P1-a already has in place (verified): `ArchModuleBoundaryTest` (ArchUnit), and test dependencies already in `pom.xml` (`spring-boot-starter-test`, `spring-security-test`, `archunit-junit5`, `testcontainers` + `postgresql` + `junit-jupiter`). No test code exists yet beyond the ArchUnit suite — everything below is planned, not built.

## 1. Unit tests

Scope: pure logic, no Spring context, no database, fast (<1s per test class).

| Target | What to test |
|---|---|
| `UuidV7` (P1-a, already manually verified standalone — should still get a formal test) | version()==7, variant()==2, monotonically increasing across rapid successive calls, uniqueness across a large batch (e.g. 100k generated, zero collisions) |
| `LogScrubber` (P1-a, already manually verified — formalize it) | Each sensitive pattern (password/otp/api_key/authorization/token/secret) is redacted; case-insensitivity; null input; a string with no sensitive fields is returned unchanged; nested/multiple occurrences in one string |
| `ApiError.of(...)` (P1-a) | Correct field population, timestamp is recent, `type` defaults sensibly |
| Argon2id verification (P1-b) | A known password verifies against the seeded hash; a wrong password fails; hash format mismatches fail gracefully rather than throwing an unhandled exception |
| Scoring-weights-sum-to-100 validator (P1-d) | Exactly 100 passes; 99/101 fail with the specific message; missing category fails; negative weight fails |
| `OutboxWriter` (P1-c) | Given an aggregate write + event, produces exactly one `outbox_events` row with correct `correlation_id`/`causation_id` propagation — this can be tested against a fake/mock repository without a real DB for the pure logic, separate from the transactional-integrity test below which does need a real DB |
| ArchUnit rules themselves (P1-a, exists) | No change needed here — this document tracks *new* tests, and the existing suite is out of scope for further unit testing (it tests the codebase, not itself) |

## 2. Integration tests (Spring context, mocked/faked external boundaries)

Scope: `@SpringBootTest` or slice tests (`@WebMvcTest`, `@DataJpaTest`) with a real Spring context but external systems (LLM providers) mocked.

| Target | What to test |
|---|---|
| `SecurityConfig` (P1-b) | Unauthenticated request to a protected endpoint → 401; authenticated session → 200; CSRF-protected mutating endpoint without token → 403; with correct token → passes |
| `AuthController` (P1-b) | Login success sets a session cookie; login failure doesn't; logout invalidates the session (subsequent `/me` call returns 401) |
| Profile/preferences CRUD (P1-d) | Full request/response cycle through `@WebMvcTest` with the service layer mocked — validates request/response DTO shapes match the API contract document exactly |
| `ModelRouter` fallback logic (P1-e) | Primary provider mocked to fail → router falls back to secondary; both mocked to fail → `AllProvidersExhaustedException`; structured-output validation failure triggers the repair round-trip (max 2 attempts) before falling back — this is the single most important integration test in P1-e, since it's the exact behavior the architecture doc's Phase 1 DoD demo depends on |
| Benchmark runner (P1-f) | Given a small fixed suite and a mocked router returning known outputs, produces correct `benchmark_results` rows and correct aggregate `rank` calculation per the §C6 formula |

## 3. Testcontainers tests (real Postgres, real Redis where relevant)

Scope: the tests that actually prove the system works against real infrastructure, not mocks. This is where P1-a's Dockerfile/migration correctness gets proven in CI (per the CI workflow already built, which runs `mvn verify` on `ubuntu-latest` with Docker available).

| Target | What to test |
|---|---|
| Flyway migration application (all slices, but especially the first `@SpringBootTest` written, likely in P1-b) | `V001`+`V002` (+`V003` once it exists) apply cleanly against a fresh Testcontainers Postgres — this is the automated version of what was manually verified once already in this sandbox; from P1-b onward it should never again be manually re-verified, only CI-verified |
| JPA entity round-trips (P1-d) | Each entity's create/read/update/delete against real Postgres, specifically covering the `jsonb`/`text[]`/`citext` columns flagged as a risk in the conflict review — this is where that risk gets resolved empirically, not theoretically |
| Outbox transactional integrity (P1-c) | Write an aggregate + emit an event in one `@Transactional` method; roll back mid-way (simulate a failure) → assert the outbox row was NOT persisted (proving atomicity); a successful call → assert exactly one outbox row, `published_at` initially null |
| Reconciler crash-recovery (P1-c) | Insert an outbox row with `published_at = null` and an old `created_at`, run the Reconciler sweep, assert it gets dispatched — this is the automated version of the "kill mid-dispatch, verify zero lost events" DoD item |
| Full stack health (all slices, ongoing) | A test that boots the full Spring context against Testcontainers Postgres+Redis and hits `/api/v1/system/health`, asserting `UP` — cheap regression guard against future config drift |

## 4. Security tests

| Target | What to test |
|---|---|
| Session fixation | Login rotates the session ID (Spring Security does this by default — confirm it's not disabled) |
| CSRF bypass attempts (once CORS/CSRF is built per conflict review §1) | A mutating request from a disallowed origin is rejected; a mutating request with a stale/missing CSRF token is rejected |
| Password hash storage | Confirm `password_hash` is never returned in any API response (login, `/me`, or otherwise) — a simple but easy-to-regress check |
| Log scrubbing under load | Feed `LogScrubber` a realistic LLM request/response payload (once P1-e exists) containing an API key mid-string, not just at a clean field boundary — the current regex-based approach (P1-a) is pattern-based and could miss creatively-formatted secrets; this test should specifically try to break it, not just confirm the happy path |
| Purge endpoint confirmation (P1-b, pending your sign-off on the contract) | Wrong confirmation password → 403, data NOT deleted; correct confirmation → cascade actually removes all child rows (`work_experiences`, `education`, etc.) |
| SQL injection smoke tests | Especially for the FTS query endpoint (`GET /jobs?q=...`) — confirm parameterized queries throughout, no raw string concatenation into `tsquery` |

## 5. API tests (contract-level, black-box)

Scope: tests that verify the *shape* of requests/responses against the API contract document, independent of internal implementation — these are what would catch a future refactor accidentally breaking the contract.

| Target | What to test |
|---|---|
| Every endpoint in the API contract document | Response schema matches exactly (field names, types, nullability) — recommend generating these from the springdoc-openapi spec (`/v3/api-docs`) already wired in P1-a's `pom.xml`, comparing against a checked-in expected schema snapshot, so contract drift is caught automatically rather than requiring someone to remember to check by hand |
| Idempotency (`X-Request-ID`) | Send the same mutating request twice with the same `X-Request-ID` → second response is identical to the first, no duplicate side effect (e.g., no duplicate skill row, no duplicate benchmark run) |
| Correlation ID propagation | Every response (success and error) carries `X-Correlation-ID`; if the client supplies one, it's echoed back unchanged rather than replaced |
| Error shape consistency | Every 4xx/5xx response across every endpoint conforms to the RFC 9457 shape — one parametrized test iterating over a table of (endpoint, expected-error-trigger) pairs rather than duplicating assertions per endpoint |

## 6. Failure/recovery tests

Scope: the scenarios the architecture doc explicitly calls "crash recovery" and "reliability" — these are the tests most likely to be skipped under time pressure, so calling them out explicitly.

| Target | What to test |
|---|---|
| Backend killed mid-request (P1-c onward) | Simulate by killing the process between the aggregate write and the outbox publish step (achievable in a Testcontainers test by throwing after the DB commit but before the in-process dispatcher runs) → Reconciler sweep on restart picks it up |
| Circuit breaker open state (P1-e) | Force the primary provider to fail enough times to open the breaker → subsequent calls fail fast without waiting for a timeout, then recover once the breaker half-opens and a call succeeds |
| Database connection loss (all slices, general resilience check) | `/api/v1/system/health` correctly reports `DEGRADED` when Postgres is unreachable (this is directly testable against the existing P1-a `SystemHealthController` code today, even before any other slice exists — recommend this be one of the very first tests written, since it validates already-built code) |
| Docker Compose restart (manual, not automatable in CI easily) | `docker compose down && docker compose up` — confirm Postgres volume persists data, backend reconnects without manual intervention — this is already on your P1-a verification checklist and should be repeated after each slice that changes startup behavior |
| Duplicate event delivery (P1-c) | Manually re-deliver an already-`consumed_events`-recorded event to a handler → handler is a no-op (idempotency check), not a duplicate side effect |

## Test execution cadence recommendation

- **Every slice**: unit tests for that slice's new logic, run in `mvn verify` (already wired in CI).
- **P1-b, P1-c, P1-d**: at least one Testcontainers test each, since these are the slices most likely to reveal the JPA/jsonb risk and the outbox atomicity guarantees — don't defer these to "later."
- **P1-e, P1-f**: integration tests with mocked providers are the priority (real API calls to NIM/Gemini shouldn't run in CI by default — cost and flakiness — but should be runnable manually via a tagged/excluded test group for pre-release sanity checks).
- **Security tests**: written alongside P1-b (session/CSRF) and revisited once the purge endpoint contract is confirmed.
- **Failure/recovery tests**: written alongside P1-c (this is literally what P1-c exists to make testable) rather than deferred to a later "hardening" pass.
