# Phase 1 Dependency Map

Status legend: **[BUILT]** exists in the repo today · **[SCHEMA-READY]** table exists (V001) but unused by app code yet · **[PLANNED]** not yet built, described here so each slice knows exactly what it inherits and what it must not duplicate.

This map is derived by cross-referencing the architecture doc's phase table (§B3), build order (§D), repo structure (§C1), DB schema (§C2), API contract (§C3), and event registry (§C4) against the actual P1-a codebase as it exists right now (verified by direct inspection, not memory).

---

## P1-a — Foundation & skeleton [BUILT]

| Kind | Introduced |
|---|---|
| Modules (Java packages) | `common` (UuidV7, ApiError, CorrelationIdFilter, LogScrubber), `security` (placeholder SecurityConfig), `system` (SystemHealthController) |
| DB tables created | All 29 Phase-1 tables from §C2, in `V001__core.sql` — see full list below. This is intentional: the architecture specifies the *authoritative* Phase 1 schema up front so later slices never need a "create table" migration, only seed/data migrations or (rarely) corrective `ALTER`s. |
| Seed data | `V002__seed.sql` — 1 user, 1 profile, 1 preference_set (weights sum to 100), 2 companies, 2 job_sources, 2 jobs |
| APIs | `GET /api/v1/system/health` only |
| Events | None (outbox/consumed_events tables exist, schema-ready, no producer/consumer code yet) |
| Infra | docker-compose (postgres, redis, backend, frontend), CI skeleton, JSON logging + correlation_id MDC |
| Explicitly NOT built yet | Auth, any business-domain endpoint, event dispatch, LLM router, scheduler, discovery connectors |

**All 29 tables created in P1-a (owned by no later "create" migration — later slices only add rows or, rarely, corrective ALTERs):**
users, profiles, work_experiences, education, projects, certifications, skills, preference_sets, companies, job_sources, sponsor_records, jobs, job_snapshots, job_analyses, job_scores, approvals, applications, application_events, files, cv_versions, outbox_events, consumed_events, llm_providers, llm_models, prompts, llm_calls, model_benchmark_runs, benchmark_results, routing_policies, notifications, audit_logs.

---

## P1-b — Auth + audit [PLANNED — awaiting your verification of P1-a first]

| Kind | Introduced |
|---|---|
| Modules | Replaces `security.SecurityConfig` wholesale; adds `security.AuthController`, `security.UserDetailsServiceImpl` (or equivalent), `audit.AuditLogWriter` (port + impl) |
| DB tables | **None new.** Uses existing `users` and `audit_logs` (both already in V001). |
| APIs | `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` (per §C3's core resource table) + a "delete my data" purge endpoint not yet named in §C3 (flagged in the conflict review — needs a contract decision) |
| Events | None planned. Login/logout are not in the §C4 event registry — they're audit-logged, not event-emitted. Worth confirming that's intentional (see conflict review). |
| Depends on | P1-a's `users`/`audit_logs` tables, `CorrelationIdFilter` (audit rows should capture `correlation_id`), placeholder `SecurityConfig` being fully replaced |
| New requirement surfaced by review | CORS + CSRF-for-SPA configuration — not explicit in the architecture doc, but required the moment session-cookie auth exists behind a locked-down filter chain and the frontend runs on a different port. See conflict review §1. |

---

## P1-c — Events core [PLANNED]

| Kind | Introduced |
|---|---|
| Modules | `events` package: `Envelope`, `OutboxWriter`, `EventPublisher` SPI, `InProcessDispatcher`, `Reconciler` |
| DB tables | **None new.** Uses existing `outbox_events` and `consumed_events` (both in V001). |
| APIs | None directly (internal infrastructure) |
| Events | Defines the envelope shape and dispatch mechanics per §C4, but the *first real event types* (`job.discovered`, etc.) aren't emitted until P1-g (discovery). P1-c itself can be validated with a synthetic/test event type. |
| Depends on | P1-a's `outbox_events`/`consumed_events` schema |
| Gap surfaced by review | The **Scheduler** component (§B1 topology, §C7) — cron-triggered discovery runs, nightly reconciler sweep, weekly benchmark replay — is architecturally described as "the missing piece, Phase 1" but is **not explicitly assigned to any lettered P1 sub-slice** in §D's build order. P1-c is the natural owner (it already owns Reconciler), but this should be confirmed with you rather than assumed. See conflict review §2. |

---

## P1-d — Profile & preferences [PLANNED]

| Kind | Introduced |
|---|---|
| Modules | `profile` (CRUD for profile, work_experiences, education, projects, certifications, skills), `preferences` (CRUD for preference_sets) |
| DB tables | **None new.** All target tables (`profiles`, `work_experiences`, `education`, `projects`, `certifications`, `skills`, `preference_sets`) already exist in V001. |
| APIs | `GET/PUT /profile`, `CRUD /profile/experiences`, `/education`, `/projects`, `/certifications`, `/skills`, `GET/PUT /preferences` — all bolded-as-Phase-1 in §C3 |
| Events | None per §C4 registry (profile/preference changes aren't in the event registry — they're read by later stages via direct query, not event) |
| Depends on | P1-b's auth (all these endpoints require an authenticated session), P1-c's audit-writer pattern if profile edits should be audited (architecture doc implies "audit trail" for preferences in §D build order bullet 4 — confirm this reuses P1-b's `AuditLogWriter`) |
| First real risk-test for JPA | This is the first slice writing to columns with `jsonb`, `text[]`, and `citext` types via JPA. See conflict review §3 — recommend building **one** entity (e.g. `Skill`) end-to-end first as a spike before committing to JPA for the rest of this slice. |

---

## P1-e — Multi-LLM [PLANNED]

| Kind | Introduced |
|---|---|
| Modules | `llm` package: `LlmProvider` SPI, `NimProvider`, `GeminiProvider`, `ModelRouter`, registry admin logic, `PromptStore` |
| DB tables | **None new for schema** — `llm_providers`, `llm_models`, `prompts`, `llm_calls` already exist in V001. **New seed data migration recommended**: `V003__llm_provider_seed.sql` inserting the two provider rows (`nim`, `gemini`) and their known models, since V002 didn't seed these. See DB evolution plan. |
| APIs | `GET /models`, `PUT /models/{id}`, `GET /routing`, `PUT /routing/{taskType}`, `GET /llm-calls/stats`, `GET /system/llm/ping` |
| Events | None per §C4 (LLM calls are ledgered, not event-emitted) |
| Depends on | P1-c's events core is NOT a hard dependency for P1-e itself (the router doesn't need to emit events), but the *ledger write* should probably share the same transactional-write discipline P1-c establishes for the outbox, for consistency. Depends on P1-b only insofar as `/models`/`/routing` endpoints require auth. |
| Explicitly deferred | `resilience4j` circuit breaker wiring needs confirming `spring-boot-starter-aop` is present transitively (see conflict review §4) before annotation-driven `@CircuitBreaker` usage is written. |

---

## P1-f — Benchmark mini-harness [PLANNED]

| Kind | Introduced |
|---|---|
| Modules | `benchmark` package: suite loader, runner, graders, promotion logic |
| DB tables | **None new.** `model_benchmark_runs`, `benchmark_results`, `routing_policies` already exist in V001. |
| APIs | `POST /benchmarks/runs`, `GET /benchmarks/runs[/{id}]`, `POST /benchmarks/runs/{id}/promote` |
| Events | None per §C4 |
| Non-DB artifacts | The actual golden-set JSONL file (`datasets/benchmarks/job_classification/v1.jsonl`, 20 cases) — currently only a `.gitkeep` placeholder exists. This is data curation work, not code. |
| Depends on | P1-e's `ModelRouter` (the runner must call the *real* router seam per §C6, not bypass it) |

---

## P1-g — Jobs slice (read-side) [PLANNED]

| Kind | Introduced |
|---|---|
| Modules | `jobs` (normalisation, dedup, FTS query), a minimal seed discovery connector (Greenhouse/Lever), frontend Jobs Feed + Job Detail pages |
| DB tables | **None new for schema** — `jobs`, `job_sources`, `companies`, `sponsor_records`, `job_snapshots`, `job_analyses`, `job_scores` already exist in V001. |
| APIs | `GET /jobs`, `GET /jobs/{id}`, `POST /jobs/import-url` (stub — full resolver is Phase 2) |
| Events | **First real event types are emitted here**: `job.discovered`, `job.normalized` at minimum (the rest of the pipeline — filter/analyse/score/decide — isn't built until Phase 3/4 per §B3, so P1-g's own events dead-end at "normalized" for now; that's expected and fine for a read-side slice). |
| Depends on | P1-c's `OutboxWriter`/dispatcher (to actually emit `job.discovered`), P1-d's `preference_sets` (to filter/scope what's shown, if the feed endpoint applies preferences — confirm this is in scope for P1-g or deferred to Phase 3's filter-engine) |
| Data caution | Real scraped job postings should **not** be added via a Flyway migration (see DB evolution plan) — use an application-level import path instead. |

---

## Cross-slice dependency graph (topological)

```
P1-a (schema + skeleton)
  ├─► P1-b (auth + audit)          — needs users, audit_logs
  │     └─► P1-d (profile/prefs)   — needs auth on every endpoint
  │     └─► P1-g (jobs read API)   — needs auth on every endpoint
  ├─► P1-c (events core)           — needs outbox_events, consumed_events
  │     └─► P1-g (job.discovered emission)
  ├─► P1-e (LLM router)            — needs llm_* tables; needs auth (P1-b) for admin endpoints
  │     └─► P1-f (benchmarks)      — needs the real router seam
  └─► P1-g depends on P1-b (auth) + P1-c (events) both being done first
```

This confirms the existing P1-a→b→c→d→e→f→g ordering in the architecture doc is topologically valid — nothing in P1-d through P1-g needs something from a *later* letter. The one soft dependency worth flagging: **P1-g's event emission needs P1-c**, and **P1-g's endpoints need P1-b's auth**, so P1-g genuinely cannot start before both are done, even though it's scoped last, which the existing order already respects.
