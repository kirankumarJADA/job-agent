# Phase 1 Per-Slice Verification Checklists

Each slice's checklist is meant to be run before moving to the next slice — consistent with how P1-a itself is being verified before P1-b starts. None of these are executed yet; they're the definition of "done" for slices that don't exist yet.

## P1-a — Foundation & skeleton (in progress — your verification pending)

- [ ] `mvn verify` → `BUILD SUCCESS`
- [ ] ArchUnit test class runs and passes (not skipped)
- [ ] `docker compose up --build` → all 4 services healthy
- [ ] `GET /api/v1/system/health` → `UP`, `database: UP`
- [ ] Seed data present and correct (2 jobs, weights sum to 100)
- [ ] Frontend loads and shows backend health JSON
- [ ] `docker compose down && up` → data persists, clean reconnect

*(Full detail already delivered separately as "P1-a Verification Checklist" — this entry exists so the per-slice pattern is consistent across the whole document.)*

## P1-b — Auth + audit

- [ ] Login with seeded dev user succeeds, session cookie set
- [ ] Login with wrong password fails with 401, no user-enumeration difference in response
- [ ] `GET /auth/me` returns correct user when authenticated, 401 when not
- [ ] Logout invalidates session (`/auth/me` returns 401 immediately after)
- [ ] Every login/logout attempt produces exactly one `audit_logs` row with correct `action` and `correlation_id`
- [ ] CORS: a request from `http://localhost:5173` with `credentials: include` succeeds; a request from an unlisted origin is rejected
- [ ] CSRF: a mutating request without the CSRF header is rejected (403); with it, succeeds
- [ ] Purge endpoint (once contract is signed off): wrong confirmation → 403, no data deleted; correct confirmation → cascade deletes all child rows, audit row written first
- [ ] `password_hash` never appears in any response body (grep API responses for it as a regression check)
- [ ] Testcontainers integration test suite green in CI

## P1-c — Events core

- [ ] Writing an aggregate + emitting an event happens in exactly one DB transaction — verified by forcing a rollback mid-way and confirming no outbox row was written
- [ ] A published event is recorded in `consumed_events` per consumer, preventing duplicate handling on redelivery
- [ ] Reconciler sweep picks up a stuck (`published_at IS NULL`, old `created_at`) outbox row and dispatches it
- [ ] DLQ notification fires after N failed dispatch attempts (confirm the exact N against whatever gets implemented)
- [ ] If Scheduler is built here (pending your decision per conflict review §2): a declared cron job actually fires on schedule in a test environment, and `@EnableScheduling` is present on the application
- [ ] Kill the backend process mid-dispatch (simulated in a Testcontainers test), restart, confirm zero lost events — this is the literal Phase 1 DoD item, should be automated here rather than only manually spot-checked

## P1-d — Profile & preferences

- [ ] Full CRUD round-trip for each of: experiences, education, projects, certifications, skills — create, read back, update, delete, confirm gone
- [ ] `skills.mastery` outside 1–5 → 400, not a raw DB constraint error
- [ ] Duplicate skill name for the same profile → 409, not a raw DB error
- [ ] Preferences: `scoringWeights` not summing to 100 → 400 with the specific message
- [ ] Every profile/preference mutation writes an `audit_logs` row with correct before/after state
- [ ] The JPA/jsonb/array spike entity (recommended: `Skill`) round-trips correctly against Testcontainers Postgres with `ddl-auto: validate` — if this fails, STOP and revisit the persistence approach before building the remaining six entities the same way
- [ ] Frontend Profile and Preferences pages render real data and successfully submit edits

## P1-e — Multi-LLM

- [ ] `GET /api/v1/models` lists both NIM and Gemini models (post-`V003` seed)
- [ ] `/system/llm/ping?forceFailPrimary=true` shows primary attempt fail, fallback succeed, both attempts recorded in `llm_calls` with latency/tokens/cost
- [ ] Structured-output validation failure triggers exactly one repair round-trip (not zero, not unbounded) before falling back
- [ ] Circuit breaker actually opens after repeated failures (not just a mocked assertion — verify against `resilience4j`'s real state, confirming the `spring-boot-starter-aop` question from the conflict review is resolved)
- [ ] Cost/token ceiling: exceeding the configured per-day budget for a task class refuses further calls and notifies, rather than silently degrading
- [ ] `LogScrubber` (or an LLM-specific equivalent) confirmed to redact API keys and any PII from `llm_calls.request_redacted`/`response_excerpt` before they're persisted

## P1-f — Benchmark mini-harness

- [ ] `POST /benchmarks/runs` with the seeded 20-case job-classification suite completes and produces 20×N `benchmark_results` rows (N = number of models tested)
- [ ] Aggregate `rank` calculation matches the §C6 formula by hand-checking one case
- [ ] `POST .../promote` succeeds only when the promotion gate criteria are met (≥ min cases, quality gap ≥ ε, two consecutive runs within δ) — test both the success and the "criteria not met → 409" paths
- [ ] Post-promotion, `/system/llm/ping` actually routes to the newly-promoted model
- [ ] Golden-set fixtures are confirmed frozen (not modified) even after a benchmark run reveals failures — per the "golden-set honesty rule" in §C6

## P1-g — Jobs slice (read-side)

- [ ] `GET /jobs` returns seeded + any newly-imported jobs, filterable by `status`
- [ ] FTS search (`?q=...`) returns correct matches (already proven at the SQL level in P1-a; this re-proves it through the actual API layer)
- [ ] `GET /jobs/{id}` renders correctly even when `analysis`/`score`/`decision_trace` are all null (since those pipelines don't exist until Phase 3/4)
- [ ] `POST /jobs/import-url` stub accepts a URL and returns 202 without attempting real resolution (Phase 2 scope)
- [ ] Real job import (Greenhouse/Lever connector): imported jobs correctly deduplicated by `content_hash`/`dedup_key`, `first_seen_at`/`last_seen_at` populated correctly on a repeat import of the same posting
- [ ] `job.discovered` and `job.normalized` events actually flow through P1-c's outbox/dispatcher for each imported job (not bypassed)
- [ ] Frontend Jobs Feed and Job Detail pages render real imported data correctly
- [ ] Confirmed real job data was NOT introduced via a Flyway migration (per conflict review §7) — check the migration directory has no new `V00X__*jobs*.sql` file with hardcoded scraped content
