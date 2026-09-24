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
- **Amendment (Firebase accounts):** an account whose credential lives in Firebase has no local password hash (`password_hash` is nullable as of V020). Confirmation by password is therefore impossible for those accounts and returns `403` with `"This account has no local password to confirm with."` — deliberately, rather than letting a null hash reach the encoder.

---

## Firebase Authentication — **BUILT**

Firebase owns credential verification: password storage and hashing, password reset,
and email delivery. This backend never sees a password, never issues a reset token,
and never stores one. Its only job is to verify Firebase ID tokens and map the
resulting identity onto a local account.

### Identity model

- The backend trusts exactly one client-supplied identifier: the **verified** `uid`
  inside a Firebase ID token. No endpoint accepts a user id in a request body, and
  adding one would be ignored.
- `users.firebase_uid` (V020, unique where not null) links a local account to its
  Firebase identity. `users.auth_provider` records `LOCAL` or `FIREBASE`.
- Sign-in resolution order: match on `firebase_uid`, then link by email to an
  existing local account (preserving its id, and therefore all of its profile data),
  then — only for a genuinely new account — consult the registration gate.
- Session behaviour is unchanged: requests are authorised by the existing
  `HttpSession` and the `X-XSRF-TOKEN` / `XSRF-TOKEN` pair, so every pre-existing
  endpoint works identically regardless of how the user signed in. A
  `Authorization: Bearer <idToken>` header additionally authenticates a request
  directly, but **only** for an account that is already linked.

### `POST /api/v1/auth/firebase/session` — **BUILT**

- **Auth:** none required to call it (this is what creates the session). CSRF-exempt,
  like `/auth/login`, because there is no session to protect yet and possession of the
  credential in the body is the authentication.
- **Request:**
  ```json
  { "idToken": "string", "inviteCode": "string (optional)" }
  ```
- **Response 200:** same body shape as login — `{ userId, email, displayName }` — and
  sets the session plus CSRF cookies. This is the only place a local account can be
  created from a Firebase identity, and the only place the invite code is checked.
- **Response 401:** the ID token is invalid, expired, or has a bad signature/audience.
  Deliberately the same shape as a failed password login.
- **Response 400:** the token is valid but carries no email, so there is nothing to
  link an account to.
- **Response 403:** either the token's email is not verified yet — Firebase's
  verification email has not been followed, so no account may be claimed or
  created; the body says so and the response is identical whether or not a local
  account exists — or registration was refused by the invite gate (missing,
  wrong, or unconfigured code). Already-linked accounts are never refused this
  way: they sign in through their UID link regardless of verification state.
- **Response 503:** the server has no usable Firebase credentials. The message names
  the missing environment variables.
- **Audit:** `SIGNUP_COMPLETED` when the call created the account, otherwise
  `LOGIN_SUCCESS`; `SIGNUP_REFUSED` on a gate refusal; `FIREBASE_LOGIN_FAILURE` on a
  bad token.

### `GET /api/v1/auth/registration-policy` — **BUILT**

- **Auth:** none. Exposes no credential material and no account data.
- **Response 200:** `{ "inviteCodeRequired": boolean, "registrationAvailable": boolean }`.
  Lets the sign-up form label its invite-code field honestly instead of showing one
  the server ignores, or hiding one it requires.

### Registration gating — **BUILT**

The application's `jobs`, `applications`, `notifications` and `audit_logs` tables are
still globally scoped (see below), so on a public URL open registration would expose
one account's data to anyone with the link. Registration is therefore gated and the
gate **fails closed**:

| `require-invite-code` | code configured | outcome for a new account |
| --- | --- | --- |
| `false` (dev default) | — | allowed |
| `true` (prod default) | yes | allowed only if the supplied code matches |
| `true` | **no** | **refused** — never falls open to open registration |

Signing in to an existing account never consults the gate.

### Password reset — **delegated to Firebase, no endpoint here**

There is no `/auth/forgot-password` or `/auth/reset-password` on this backend, and
none should be added. The frontend calls Firebase's own `sendPasswordResetEmail`, and
Firebase sends the email and owns the token. This application generates no reset
token, stores none, accepts none, and therefore cannot leak one.

### Data isolation status

Authentication alone does not create isolation. Ownership root is **`profiles.id`**
— not a second concept bolted on beside `users.id`: V001 already makes `profiles`
the one row per user that every other user-owned table hangs off.

**Profile-owned and scoped.** Every read, write and delete filters on the owner
resolved from the authenticated principal: `profiles`, `work_experiences`,
`education`, `skills`, `projects`, `certifications`, `preference_sets`,
`profile_evidence`, `applicant_identities`, `cover_letters`,
`resume_ats_analyses`, `cv_versions`, and as of V022 `applications`,
`notifications`, `emails`, `automation_plans`, `worker_events`,
`account_sessions`, `audit_logs`, `job_matches` — each of these carries its own
owner column. `application_events` and `verification_extractions` have no owner
column of their own and are scoped by joining their parent (`applications`,
`emails` respectively), which is why the ownership probe for them
(`OwnerContext.ownerOfVerificationExtraction`, the MCP timeline tool) is a join
rather than a column test. A foreign id is answered with `404` (never `403`,
which would confirm the row exists) and every mutation carries the owner predicate
in the statement itself, so there is no check-then-write window.

**`jobs` stays a shared catalogue, deliberately.** Postings are deduped globally
(`unique(source_id, external_id)`, `jobs.dedup_key`) and sourced from
platform-configured connectors, so two candidates who discover the same posting
share one row. Making it per-user would break dedup, source-failure tracking and
catalogue browsing. What *was* wrongly shared is now fixed: V007 had put the
per-candidate match decision (`match_score`, `match_recommendation`,
`match_breakdown`) on that shared row, so two users scoring the same posting
overwrote each other and flipped the row's `status` to `SCORED`. V022 moved it to
`job_matches(profile_id, job_id)` and dropped the three columns from `jobs`.

**Also global by design, and not user data:** `job_sources`, `companies`,
`sponsor_records`, `job_snapshots`, `job_source_observations`,
`discovery_extractions`, `job_analyses`, `job_scores`, `llm_*`, `prompts`,
`routing_policies`, `model_benchmark_runs`, `benchmark_results`, `outbox_events`,
`consumed_events`, `autonomy_policies`, `users`.

**Legacy rows fail closed.** V022 attributes what it can prove and no more:
rows reachable from an application inherit that application's owner; and when the
database holds exactly one profile (i.e. the single-user Phase 1 deployment the
data came from) that profile is provably the owner. With more than one profile,
anything still unattributable keeps `profile_id = NULL` and is excluded from every
user-facing read rather than shown to whoever asks first. `audit_logs` is never
backfilled — it is append-only (V008), so an `update` in the migration would abort
the statement — and its legacy rows are attributed at **read** time from `actor`,
which already holds the acting account's email.

**Regression suites for this contract:** `UserDataScopingTest` (asserts the
ownership predicate is present in the statement that reaches the database —
mocks cannot prove more), `UserDataIsolationTest` (controller-level refusals),
and `UserDataIsolationIT` (both guarantees end-to-end against real PostgreSQL,
plus the per-owner unique indexes and foreign keys that a mocked template cannot
see). Run the last one with a database URL; without it, it is skipped:

```
mvn -f backend/pom.xml verify -Dit.postgres.url=jdbc:postgresql://127.0.0.1:5432/jobagent
```

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
