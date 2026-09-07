# Phase 1 API Contract

Conventions (from architecture §C3, confirmed unchanged): prefix `/api/v1`; session cookie + CSRF token auth (see conflict review §1 for the CORS/CSRF addition this requires); errors as RFC 9457 `application/problem+json` (see `common.ApiError`, already built in P1-a); list endpoints return `{ "items": [...], "next_cursor": "...|null" }`; mutations require `X-Request-ID` for idempotency; every response carries `X-Correlation-ID` (already implemented in P1-a's `CorrelationIdFilter`).

Status column: **BUILT** (P1-a) / **PLANNED-P1-b** / **PLANNED-P1-d** / etc. / **PROPOSED** (not in the original architecture doc, drafted here for sign-off).

---

## System

### `GET /api/v1/system/health` — **BUILT**

- **Auth:** none (public)
- **Request:** no body, no params
- **Response 200:**
  ```json
  { "status": "UP", "timestamp": "2026-08-27T00:00:00Z", "components": { "database": "UP" } }
  ```
- **Response 200 (degraded):** `status: "DEGRADED"`, `components.database: "DOWN"` — still HTTP 200 by design (this is a liveness/diagnostic endpoint, not a strict readiness gate; Docker healthcheck treats non-2xx as the failure signal, and DEGRADED is deliberately not surfaced as a non-2xx so the container isn't killed just because the DB blipped for one check).
- **Errors:** none defined — this endpoint doesn't throw.
- **Idempotency:** N/A (GET, no side effects)

---

## Auth (P1-b)

### `POST /api/v1/auth/login` — **BUILT (P1-b)**

- **Auth:** none required to call it (this *is* the auth endpoint); rate-limiting recommended but not yet specified
- **Request:**
  ```json
  { "email": "dev@example.local", "password": "string" }
  ```
- **Validation:** `email` non-blank, valid format; `password` non-blank. No password complexity rule enforced server-side in Phase 1 (single-user tool; complexity is the user's own problem, but this should be an explicit decision, not an oversight).
- **Response 200:**
  ```json
  { "userId": "uuid", "email": "dev@example.local", "displayName": "Dev User" }
  ```
  Sets a session cookie (`Set-Cookie`) and (once CSRF-for-SPA is wired per conflict review §1) an `XSRF-TOKEN` cookie.
- **Response 401:** `application/problem+json`, `title: "Invalid credentials"` — deliberately does not distinguish "no such user" from "wrong password" (standard practice, avoids user enumeration).
- **Response 429:** reserved for future rate-limiting; not implemented in Phase 1.
- **Idempotency:** not applicable in the "safe to retry" sense (each call re-authenticates), but should NOT require an `X-Request-ID` header — login isn't a mutating business action in the sense the idempotency convention targets.
- **Audit:** writes one `audit_logs` row (`action: "LOGIN_SUCCESS"` or `"LOGIN_FAILURE"`) per attempt.

### `POST /api/v1/auth/logout` — **BUILT (P1-b)**

- **Auth:** requires an active session
- **Request:** no body
- **Response 204:** no content; invalidates the session server-side, clears cookies.
- **Errors:** `401` if no active session.
- **Audit:** writes `audit_logs` row (`action: "LOGOUT"`).

### `GET /api/v1/auth/me` — **BUILT (P1-b)**

- **Auth:** requires an active session
- **Response 200:** same shape as login's success body.
- **Response 401:** no active session.

### `POST /api/v1/auth/purge-my-data` — **DECIDED, built in P1-b**

Final contract (resolved from the PROPOSED draft — see below for what was decided and why):

- **Auth:** requires an active session
- **Request:**
  ```json
  { "confirmationPassword": "string" }
  ```
- **Validation:** `confirmationPassword` must match the current session's user's password hash (via the same `PasswordEncoder` used for login).
- **Response 204:** No Content. **Synchronous**, not async-accepted — Phase 1's single-user, low-data-volume scope doesn't justify background-job infrastructure for this; revisit if that changes.
- **Response 403:** confirmation password mismatch. Data is NOT deleted.
- **Response 401:** no active session.
- **Scope — what's deleted vs. retained:**
  - **Deleted:** the `profiles` row for the current user, and everything cascaded under it via existing `on delete cascade` FKs from V001 — `work_experiences`, `education`, `projects`, `certifications`, `skills`, `preference_sets`. Verified for real against live Postgres: deleting one `profiles` row correctly cascaded to a `skills` row and a `preference_sets` row in the same operation.
  - **Retained — `users` row.** This is a data purge, not account deletion. Login still works afterward; the account just has no profile data. Rationale: separates "clear my career data" from "delete my account," which are different user intents, and avoids irreversibly destroying login credentials for what's often a "start over" action.
  - **Retained — `audit_logs`, unconditionally, including the `DATA_PURGE_REQUESTED` row this endpoint itself writes.** Two independent reasons this must remain: (1) legal/operational — you cannot meaningfully audit or investigate a data-deletion event if the record of that deletion is itself deletable; (2) technical — V001 already enforces this at the database level (`revoke update, delete on audit_logs from backend_role`), so retaining it isn't a policy choice the application layer could override even if it wanted to.
- **Audit:** writes one `audit_logs` row (`action: "DATA_PURGE_REQUESTED"`) **before** the delete executes, in the same database transaction — if the delete fails, the audit row rolls back with it, so there's no "we tried to purge but only the audit trail survived" inconsistent state. `before_state` captures a snapshot (profile id + child-row counts) of what's about to be removed.
- **Idempotency:** requires `X-Request-ID`. A retried purge request after the profile is already gone should not error — the endpoint treats "no profile exists" as a valid (already-satisfied) outcome, not a failure.

---

## Profile & Preferences (P1-d)

All endpoints below require an active session (P1-b dependency).

### `GET /api/v1/profile` — **PLANNED-P1-d**

- **Response 200:** the `profiles` row plus nested `work_experiences[]`, `education[]`, `projects[]`, `certifications[]`, `skills[]` for the current user.
- **Response 404:** no profile yet (shouldn't happen post-seed, but a fresh non-seeded account would hit this before first `PUT`).

### `PUT /api/v1/profile` — **PLANNED-P1-d**

- **Request:** `headline`, `phone`, `location`, `workEligibility` (object matching the `work_eligibility` jsonb shape), `careerGoals` (object).
- **Validation:** all fields optional (partial update semantics) except that if provided, `workEligibility.visaStatus` must be one of a fixed enum (needs the actual enum values confirmed against the sponsorship engine's expectations before P1-d locks this down — currently only implied by V002's seed value `"requires_sponsorship"`, not formally enumerated anywhere in the architecture doc).
- **Response 200:** updated profile.
- **Idempotency:** requires `X-Request-ID`.
- **Audit:** writes `audit_logs` (`action: "PROFILE_UPDATED"`, `before_state`/`after_state` populated).

### `CRUD /api/v1/profile/experiences`, `/education`, `/projects`, `/certifications`, `/skills` — **PLANNED-P1-d**

Standard REST CRUD per resource, all under the active profile:

- `GET /api/v1/profile/{resource}` — list
- `POST /api/v1/profile/{resource}` — create; requires `X-Request-ID`
- `PUT /api/v1/profile/{resource}/{id}` — update; requires `X-Request-ID`
- `DELETE /api/v1/profile/{resource}/{id}` — delete; requires `X-Request-ID`, returns 204

Validation specifics per resource (drawn directly from V001's `check` constraints, already fixed):
- `skills.mastery` must be 1–5 (DB-enforced; API should return 400 before hitting the DB constraint, not surface a raw DB error)
- `skills.name` unique per profile (DB-enforced via `unique(profile_id, name)`; API should return 409 Conflict, not a raw DB error)
- `work_experiences.start_month` required; `end_month` null = current role

**Response 409 (all CRUD endpoints):** on unique-constraint violations (e.g., duplicate skill name) — must be caught and translated to `ApiError`, not leaked as a raw Postgres exception.

### `GET /api/v1/preferences` / `PUT /api/v1/preferences` — **PLANNED-P1-d**

- **Response 200 (`GET`):** the active `preference_sets` row.
- **Request (`PUT`):** full replacement of the preference set fields; `scoringWeights` object.
- **Validation — the one already-known bug class to guard against:** `scoringWeights` values must sum to exactly 100 (already a documented architecture requirement, §A3.5, and already correctly done in `V002__seed.sql`'s seed data) — this must be validated at the API layer with a clear 400, not left to fail silently or only be caught later during scoring.
- **Response 400:** `{"detail": "scoringWeights must sum to 100, got 97"}` style message.
- **Idempotency:** requires `X-Request-ID`.
- **Audit:** `audit_logs` (`action: "PREFERENCES_UPDATED"`).

---

## Jobs (P1-g)

### `GET /api/v1/jobs` — **PLANNED-P1-g**

- **Auth:** requires active session
- **Query params:** `status`, `minScore`, `q` (full-text via the existing `search_vector` column), `postedAfter`, `cursor`
- **Response 200:** `{ "items": [ {jobSummary} ], "next_cursor": "..." | null }`
- **Validation:** `status` must be one of the DB enum values (`DISCOVERED|FILTERED_OUT|ANALYSED|SCORED|DECIDED|ARCHIVED|PIPELINE_ERROR`) — invalid value → 400, not silently ignored.

### `GET /api/v1/jobs/{id}` — **PLANNED-P1-g**

- **Response 200:** full aggregate per the architecture's §C3 sample response (job + analysis + score + decision_trace + available actions). In P1-g specifically, `analysis`/`score`/`decision_trace` will legitimately be null/empty for freshly-discovered jobs, since the analysis/scoring engines aren't built until Phase 3 — this is expected, not a bug, but the response shape should tolerate it gracefully (nullable fields, not required ones).
- **Response 404:** job not found.

### `POST /api/v1/jobs/import-url` — **PLANNED-P1-g (stub)**

- **Request:** `{ "url": "https://linkedin.com/jobs/view/..." }`
- **Response 202:** `{ "status": "RESOLUTION_PENDING" }` — full URL-resolution logic (LinkedIn → underlying ATS board detection) is Phase 2 per the architecture; P1-g's stub can accept the URL and record intent without resolving it yet.
- **Validation:** URL must be well-formed; no further validation until Phase 2's resolver exists.

---

## Models & Routing (P1-e)

### `GET /api/v1/models` — **PLANNED-P1-e**

- **Response 200:** list of `llm_models` joined with `llm_providers`, including `enabled` flag.

### `PUT /api/v1/models/{id}` — **PLANNED-P1-e**

- **Request:** `{ "enabled": true|false }`
- **Response 200:** updated model row.
- **Idempotency:** requires `X-Request-ID`.

### `GET /api/v1/routing` / `PUT /api/v1/routing/{taskType}` — **PLANNED-P1-e**

- **Request (`PUT`):** `{ "primaryModelId": "uuid", "fallbackModelIds": ["uuid"], "rationale": "string" }` — sets `basis: 'MANUAL'`.
- **Validation:** `taskType` must be a recognized value (needs the actual enum confirmed — the architecture doc references `TaskType` in Java code (§C5) but never enumerates its values in the doc itself; this should be pinned down before P1-e starts, not discovered mid-implementation).

### `GET /api/v1/llm-calls/stats` — **PLANNED-P1-e**

- **Query params:** `taskType`, `since`
- **Response 200:** aggregate stats (mean latency, json_valid_rate, retry_rate, cost) per model — matches the composite-ranking inputs described in §C6.

### `GET /api/v1/system/llm/ping?task=&forceFallback=true` — **PLANNED-P1-e**

- **Response 200:** `RoutingTrace` JSON (per §C5's Java record shape) showing which model was attempted, in what order, and why.
- This is the endpoint the P1-a Phase 1 DoD explicitly calls out for the forced-failover demo — should be built to actually exercise the circuit breaker, not just simulate a trace.

---

## Benchmarks (P1-f)

### `POST /api/v1/benchmarks/runs` — **PLANNED-P1-f**

- **Request:** `{ "suite": "job_classification@v1", "modelIds": ["uuid", ...] }`
- **Response 202:** `{ "runId": "uuid", "status": "RUNNING" }` — this is genuinely async (fans out case×model calls through the real router), so 202 + poll is correct, not a design gap.
- **Idempotency:** requires `X-Request-ID` (retrying a run-start shouldn't spawn a duplicate run).

### `GET /api/v1/benchmarks/runs` / `GET /api/v1/benchmarks/runs/{id}` — **PLANNED-P1-f**

- **Response 200:** run status + results once `COMPLETED`.

### `POST /api/v1/benchmarks/runs/{id}/promote` — **PLANNED-P1-f**

- **Response 200:** updated `routing_policies` row, `basis: 'BENCHMARK'`.
- **Response 409:** promotion criteria not met (per §C6's promotion gate — quality gap ≥ ε, two consecutive runs within δ) — must return a clear reason, not a generic failure.

---

## Cross-cutting rules confirmed unchanged from the architecture doc

- Every mutating endpoint (`POST`/`PUT`/`DELETE`) listed above requires `X-Request-ID` for idempotency, per §C3's stated convention — called out per-endpoint above so it isn't missed during implementation.
- Every response, success or error, carries `X-Correlation-ID` — already guaranteed globally by P1-a's `CorrelationIdFilter`; no per-endpoint work needed for this.
- All error bodies use the RFC 9457 shape already implemented in `common.ApiError` (P1-a) — no endpoint should hand-roll its own error JSON shape.
