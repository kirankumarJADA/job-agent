# Phase 1 Implementation Blueprint

**Status:** Planning document. No implementation code was written or modified to produce this. P1-a remains exactly as delivered, pending your manual `mvn verify` / `docker compose up --build` verification. P1-b implementation has not started.

This is the governing reference for all remaining Phase 1 development (P1-b through P1-g). It consolidates five companion documents, each of which can be read standalone but is summarized here so this file works as the single entry point:

- `docs/planning/phase1-dependency-map.md` — what each slice introduces, and the topological dependency graph between slices
- `docs/planning/architectural-conflict-review.md` — gaps and risks found by re-reading the approved architecture against the real P1-a code
- `docs/contracts/api.md` — full request/response/auth/validation/error/idempotency contract for every Phase 1 endpoint
- `docs/planning/db-evolution-plan.md` — what (if any) new migrations each remaining slice needs
- `docs/planning/testing-strategy.md` — unit/integration/Testcontainers/security/API/failure-recovery test plan
- `docs/planning/verification-checklists.md` — the per-slice Definition of Done

## 1. What's confirmed solid (verified, not assumed)

- The complete Phase-1 database schema (29 tables) already exists in `V001__core.sql`, already applied and tested against a live Postgres 16 instance. No remaining Phase 1 slice needs a new "create table" migration — only P1-e needs one new *seed-data* migration (`V003__llm_provider_seed.sql`), and only under a specific contingency (documented in the conflict review) would a schema-altering migration become necessary.
- The topological order P1-a → b → c → d → e → f → g is valid: nothing in a later-lettered slice depends on something from an earlier-lettered slice that hasn't been built yet, and the two soft cross-dependencies (P1-g needs P1-b's auth and P1-c's events) both resolve correctly under the existing order.
- The event-transport swappability decision (outbox + in-process now, Redis Streams later) is explicitly protected against invalidation by the `EventPublisher` SPI design — this is the one place the architecture already defended itself against a future pivot.
- `UuidV7` and `LogScrubber` (P1-a) are functionally verified (compiled and run standalone in this environment, not just reviewed) and need no further architectural scrutiny — only formal unit tests per the testing strategy.

## 2. Open decisions — RESOLVED (2026-08-27, autonomous build pass)

Per direction to proceed with documented recommendations unless genuinely blocking:

1. **CORS + CSRF-for-SPA** — kept the P1-a implementation (env-driven `app.cors.allowed-origins`, `localhost:5173` dev default). P1-b added real CSRF (`CookieCsrfTokenRepository`, login path exempted — see AuthController javadoc for why). Production origin stays environment-driven, never hardcoded.
2. **Scheduler ownership** — assigned to P1-c, built alongside the Reconciler as originally recommended.
3. **Purge endpoint contract** — decided: cascade-deletes `profiles` + children, retains `users` row and `audit_logs` unconditionally, synchronous 204. Full rationale in `docs/contracts/api.md`.
4. **`TaskType` enum** — decided in P1-e (see that section).
5. **`work_eligibility.visaStatus` enum** — decided in P1-d (see that section).
6. **NIM/Gemini model identifiers** — no real API keys available in this build pass; `/system/llm/ping` simulates providers per the architecture's own allowance for this exact situation (validation gate item 4). Real identifiers remain a placeholder pending real credentials.
7. **DB-backed vs in-memory sessions** — in-memory `HttpSession`, per original recommendation (single instance, single user, Phase 1).
8. **Purge sync vs async** — synchronous 204 (see #3).

## 2a. New architectural decision made during the build pass — JDBC over JPA for all of Phase 1

The conflict review (§3) flagged JPA-vs-jsonb/array/citext as an open risk requiring a spike before committing. Rather than spike one entity and find out, **Phase 1 uses plain JDBC (`JdbcTemplate`) throughout, not Spring Data JPA repositories, for every module** — audit, auth/users, and (as built out) profile/preferences, jobs, and the LLM ledger. This resolves the risk by avoiding it rather than testing through it:

- jsonb, `text[]`, `citext`, and `inet` columns were all independently verified via raw JDBC (audit_logs) and real SQL (cascade deletes, FTS) to behave correctly with plain parameterized SQL and explicit `::jsonb`/`::inet` casts — no ORM mapping question to resolve.
- `spring-boot-starter-data-jpa` remains in `pom.xml` unused rather than removed, since removing a P1-a-verified dependency is out of scope for this pass; flagged as an optional future cleanup, not done here.
- This is a technology-choice decision, not a schema change — the approved DDL (§C2) is completely unaffected.

## 3. The one real risk to the P1-a foundation itself

Everything else in Phase 1 planning is additive and doesn't threaten what's already built. The **one exception** is the JPA-vs-`jsonb`/`text[]`/`citext` impedance question (conflict review §3): if the recommended P1-d spike (one entity, e.g. `Skill`, tested end-to-end against Testcontainers Postgres before building the other six) reveals that full JPA is a poor fit, the fix is removing `spring-boot-starter-data-jpa` and its `hibernate.ddl-auto` config from P1-a's `pom.xml`/`application.yml` — a real but contained change that does **not** touch the schema, the seed data, or any other P1-a file. I'm flagging this now specifically so it's tested at the cheapest possible point (one entity) rather than discovered after seven.

## 4. Non-negotiable carry-forwards from the architecture review (already governing P1-a, still governing everything after it)

- Flyway owns schema; Hibernate never runs in anything but `validate` mode.
- All PKs are app-generated UUIDv7 via `common.UuidV7` — never DB defaults.
- Every mutating endpoint requires `X-Request-ID`; every response carries `X-Correlation-ID` (already global via P1-a's filter).
- All error responses use the RFC 9457 shape from `common.ApiError` — no endpoint hand-rolls its own error format.
- ArchUnit's module-boundary rules (already running in P1-a, one bug fixed) apply to every new package added from P1-b onward — orchestrator stays unreachable except from bootstrap, llm stays a leaf, business modules talk through events/ports, not direct imports.
- Real-world scraped/dynamic content (job postings, benchmark results, etc.) never gets baked into a Flyway migration — only fixed reference/seed data does.
- Any deviation from the approved schema (§C2) discovered during implementation gets flagged for review before being implemented, not silently patched.

## 5. Immediate next steps (in order)

1. **You** run the P1-a verification checklist tomorrow (`mvn verify`, `docker compose up --build`) and report results.
2. **I** fix whatever actually breaks, re-verify what I can in this sandbox, and hand back a corrected P1-a if needed.
3. **You** confirm the decisions in §2 above (or tell me which ones you want to defer/change).
4. **I** begin P1-b implementation, incorporating whatever CORS/CSRF/purge-endpoint decisions you've confirmed, following the P1-b sections of the API contract, DB evolution plan, testing strategy, and verification checklist documents referenced above.

Nothing in this blueprint authorizes starting P1-b — it's the reference the two of us use once you do.

## 6. Phase 1 completion status (autonomous build pass, 2026-08-27)

Steps 1-4 above are now superseded: you confirmed P1-a, gave the "go" decisions, and then changed strategy to "build all remaining slices, verify what the environment allows, don't wait for manual review between slices." All seven P1 sub-slices (a through g) are now code-complete. Summary by slice, with what was actually verified vs. what remains for your manual pass:

| Slice | Status | Verified for real (this environment) | Not verified here |
|---|---|---|---|
| P1-a | Complete, manually verified by you | `mvn verify`, `docker compose up`, browser CORS check — all confirmed by you directly | — |
| P1-b | Code complete | Argon2id hash self-consistency, audit_logs jsonb/inet SQL patterns, profile-cascade-delete-keeps-user SQL | Spring context boot, login/logout/purge HTTP flows, CSRF behavior in a real browser |
| P1-c | Code complete | Every outbox/consumed_events SQL pattern (insert, select, publish, attempt-tracking, idempotent upsert) via raw JDBC | Spring context boot, the actual dispatch loop running, crash-recovery behavior |
| P1-d | Code complete | text[] array read/write, multi-array+jsonb preference UPDATE, bullets-as-JSON-array round-trip, mastery CHECK + skill-uniqueness constraints | Spring context boot, the actual CRUD endpoints end-to-end |
| P1-e | Code complete | llm_calls ledger insert, routing_policies upsert | NimProvider/GeminiProvider against real APIs (no network route, no real keys in this environment) — treat as unverified scaffolding, not working integrations, until tested with real credentials |
| P1-f | Code complete | model_benchmark_runs/benchmark_results insert, promotion-insert-with-empty-array pattern; the 20-case golden dataset is real, curated content | Spring context boot, an actual end-to-end benchmark run (would currently only exercise SimulatedProvider meaningfully, given P1-e's real-provider caveat above) |
| P1-g | Code complete | Cursor pagination (keyset, not offset) and combined FTS+status filtering, both via raw JDBC against the live seeded jobs | Spring context boot, the endpoints end-to-end; no real job-board connector was built (network sandbox blocks arbitrary external domains) — import-url is a stub per the architecture's own Phase 2 deferral |

**Nothing Spring-wired has been compiled anywhere in this build pass** — same standing limitation as every other turn (no Maven Central access in this sandbox). Every piece of business logic that could be verified without a full Spring context was verified via raw JDBC/standalone Java against a live Postgres instance; everything else is static-review-quality confidence, not execution-verified.

### Real bugs caught and fixed during this pass (by careful re-reading, not by execution)

1. **`ProfileRepository`**: the work-experience row mapper tried to parse a JSON array (`bullets`) using a helper meant for JSON objects — would have thrown on every read.
2. **`JobsController`**: used `Map.of()` with a null value (`analysis`/`score` are legitimately null pre-Phase-3) — `Map.of()` throws `NullPointerException` on any null value. Fixed with a mutable map.
3. **`ArchModuleBoundaryTest`**: the P1-a-era `llm_is_a_leaf_module` rule checked the wrong subject entirely (inspected classes outside `llm` rather than inside it) and would have flagged `BenchmarkRunner` as a violation for the exact dependency architecture §C6 requires. Rewritten with a safer positive-list approach and updated to reflect that `benchmark` legitimately calls into `llm`.
4. **`AuthController`**: missing `@Valid` meant the `LoginRequest` DTO's `@NotBlank`/`@Email` constraints never actually triggered; a malformed client-supplied `X-Correlation-ID` header could cause an uncaught 500. Both fixed.
5. **`SecurityConfig`**: without an explicit `AuthenticationEntryPoint`, Spring Security 6 defaults to 403 for unauthenticated access when neither `formLogin` nor `httpBasic` is configured — contract specifies 401. Fixed with `RestAuthenticationEntryPoint`.

### New migrations added during this pass (beyond the one anticipated in the DB evolution plan)

- `V003__fix_seed_user_password.sql` — corrective: replaced a fake placeholder password hash with a real, verified Argon2id hash.
- `V004__outbox_attempt_tracking.sql` — additive: `attempt_count`/`last_error` columns needed for the DLQ mechanic, missing from V001.
- `V005__llm_provider_seed.sql` — the migration anticipated in the DB evolution plan, with clearly-labeled placeholder model identifiers for nim/gemini pending real credentials.

### Known gaps for your stabilization pass to prioritize

- **No test coverage was written for P1-d/e/f/g** (only P1-b/c got integration tests, written before the strategy changed to "build everything, verify at the end"). This is the highest-value thing to add before trusting these slices.
- **NimProvider/GeminiProvider are unverified against real APIs.** Starting scaffolding, not working integrations, until tested with real credentials.
- **No real job-discovery connector exists.** P1-g's read-side (GET /jobs, GET /jobs/{id}) is real; populating the table with live Greenhouse/Lever postings is not built.
- **Full CSRF-for-SPA behavior is unverified in a real browser** — flagged as a risk since P1-b, remains the single most likely thing to need adjustment.
- **`docker-compose.yml` now mounts `../datasets` into the backend container** for P1-f — first `docker compose up --build` after this change should confirm that mount resolves correctly.
