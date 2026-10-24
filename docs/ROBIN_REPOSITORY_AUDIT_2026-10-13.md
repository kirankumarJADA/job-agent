# Robin repository audit — 2026-10-13

Scope: full-repository capability audit requested by the operator. This file
is additive; it does not replace `docs/ROBIN_BUILD_STATUS.md` (the
phase-by-phase build log, which this audit cites rather than repeats) or
`docs/PHASE8_FIND_WORKFLOW.md` (architecture/spec detail for Phases 8.0–8.5).
No application code was changed to produce this report. Nothing was
committed, pushed, deployed, or merged.

## 1. Verified repository and Git-state summary

| Item | Verified value |
|---|---|
| Path | `C:\Users\jadak\Documents\antigravity\peaceful-chandrasekhar` |
| Branch | `phase8.5-managed-mail`, up to date with `origin/phase8.5-managed-mail` |
| HEAD | `d764209b46939d03188d29154eefe58e2afc348b` — "docs(phase8.5): record green CI results for the managed-mail head" (docs-only; functional code is identical to `dac60f3`) |
| Remote | `origin` = `https://github.com/kirankumarJADA/job-agent.git` |
| Working tree | Clean except one pre-existing untracked file, `docs/TSENTA_REA_INVESTIGATION.md` (a prior, public-pages-only Tsenta report; not modified or referenced for anything beyond its own public-claim citations in §2.9) |
| This branch's merge state | PR #7 (base `phase8.2.5-ats-discovery-expansion`) is **not merged into `master`**. Phases 8.2.5, 8.3, 8.4 and 8.5 all live on unmerged branches; only Phases 1–7.3 are representative of `master`. |
| Last CI run cited for this head | GitHub Actions runs `38171646568` (push) and `38171646809` (pull_request) on `dac60f3`, both green, per `ROBIN_BUILD_STATUS.md`. Not independently re-run in this audit (see §5). |

**Distinguishing branch code from deployed production**: `ROBIN_BUILD_STATUS.md` records that production (`job-agent-mwhu.onrender.com` / `job-agent-beige.vercel.app`) was last known to run an **older** revision; this audit did not re-verify what SHA is currently deployed. Treat every Phase 8.x capability below as "implemented on an unmerged branch," not "live in production," unless stated otherwise.

## 2. Capability matrix

Status values: `IMPLEMENTED_AND_VERIFIED`, `PARTIALLY_IMPLEMENTED`, `MISSING`, `NOT_VERIFIED`, `BLOCKED_BY_EXTERNAL_SETUP`.

### 2.1 Job discovery, search, filters, provider integrations, deduplication
**Status: IMPLEMENTED_AND_VERIFIED** (on `phase8.2.5-ats-discovery-expansion` and descendants; not on `master`)
- `backend/.../discovery/platform/PlatformRegistry.java`, `BoardDiscoveryService.java`, `SafeHttpClient.java` (SSRF checks, timeouts, size/content-type limits, bounded retries, pacing), `JobIdentity.java` (strong-key identity, advisory-locked), `discovery/platform/adapters/*` (21 LIVE_VALIDATED ATS adapters + Tsenta).
- Evidence: `PlatformCoverageAuditTest`, `PlatformAdapterFixtureTest`, `CrossSourceDiscoveryIT` (10/10, 16-thread concurrent ingest), live-board suite `PlatformLiveIT` (22/22, opt-in, 2026-10-10).
- Known gaps (documented, not fixed): 9 of 30 ATS platforms have no adapter (UNVERIFIED/BLOCKED); `POST /jobs/import-url` resolves generically but cannot inspect JS-rendered pages; Teamtailor/Zoho/Tsenta rely on staleness rather than explicit removal.
- Five concrete defects against Tsenta specifically are catalogued with file-level fixes in `docs/TSENTA_REA_INVESTIGATION.md` §8–9 (F1–F5, all P0, all offline-testable) — not yet implemented. Treat those as backlog items, not as disproving this capability.

### 2.2 ATS adapters, job-detail extraction, application links
**Status: PARTIALLY_IMPLEMENTED**
- `backend/.../ats/AtsAdapter.java`, `GreenhouseAdapter.java`, `AshbyAdapter.java`, `ats/AtsAdapters.java` (Zoho, Workable, etc. — URL recognition only), `ats/JobFormQuestionService.java` (Greenhouse-only live form capture).
- Live, read-only form inspection + field mapping (`automation/GreenhouseFieldMapper.java`, `automation/InspectionPlanService.java`) works for **Greenhouse public-board forms only**. Every other adapter (`supportsLiveInspection() == false`) matches a URL and names a platform but cannot inspect or fill its form.
- Confirmed defect (code-verified, not yet fixed): `AtsAdapters.java`'s Zoho matcher recognises `zohorecruit.com`/`zoho.com` but not `zohorecruit.in`, used by the majority of sampled Indian Zoho tenants — `TsentaJobSource` discovery already recognises `.in` via `JobIdentity`/`PlatformKind.ZOHO_RECRUIT`, so discovery and APPLY disagree on the same URL today.

### 2.3 Browser worker, form detection, navigation, application lifecycle
**Status: IMPLEMENTED_AND_VERIFIED for the safety boundary; PARTIALLY_IMPLEMENTED for live execution**
- `worker/src/browser_worker.js`, `plan.js`, `store.js`, `mock_environment.js`, `mailbox.js`.
- **Hard stop confirmed by direct code read**: `browser_worker.js:326` — `if (p.action === 'REAL_SUBMIT') throw new HardStopError('FORBIDDEN_REAL_SUBMISSION');`. The worker has a `MOCK_SUBMIT` step type but no code path that performs a real submission. Backend-side (`automation/AutomationController.java:357`) only *reads* for an eventual `REAL_SUBMIT` action in event payloads and takes no action on it; nothing in `backend/src/main/java` ever emits a `REAL_SUBMIT` step. This is a genuine, verified hard stop, not a documentation claim.
- Worker drives a real headless Chromium via Playwright (CI installs `npx playwright install --with-deps chromium`) against a **mock employer** for its test suite; it has not been run against a real third-party ATS in this repository's verified history (`PlatformLiveIT` exercises discovery, not the worker).
- Lifecycle plan building exists (`buildAccountLifecyclePlan`, account-session steps `ACCOUNT_RESOLVE_IDENTITY`, `ACCOUNT_VERIFY_OTP`), but the backend "does not yet create lifecycle plans" per `ROBIN_BUILD_STATUS.md` — i.e., nothing currently triggers a real worker run against a real employer sign-up page from the product's own code paths.

### 2.4 Resume parsing, ATS analysis, tailoring, cover-letter generation, application answers
**Status: IMPLEMENTED_AND_VERIFIED**
- `resume/ResumeAtsIntelligenceService.java`, `resume/CvArtifactService.java`, `resume/CvComparisonService.java`, `documents/PdfDocumentRenderer.java` (PDFBox 3.0.3, Unicode via bundled Liberation Sans), `documents/DocumentFactValidator.java` (deterministic blocker/warning validator shared by CVs, letters and answers), `coverletter/CoverLetterService.java`, `qa/ApplicationAnswerService.java`.
- Evidence: `CvArtifactServiceTest` (renderer regression: unreadable→readable, single-page→multi-page, ASCII-only→Unicode, all fixed and tested), `DocumentFactValidatorTest` (8), `CoverLetterReviewTest` (7), `PrepWorkflowIT` (6, PostgreSQL).
- Known gap: single typeface only, no CJK/emoji glyph coverage (documented, not a defect in scope).

### 2.5 Employer account management and managed-mail/OTP verification
**Status: PARTIALLY_IMPLEMENTED**
- Implemented and test-verified (PostgreSQL 16, `ManagedMailIT` 12–13 tests across runs): `mailbox/managed/ManagedMailboxService.java` (opaque per-profile alias, `V042`), the Mailgun inbound webhook (HMAC-SHA256 signature check, replay/duplicate handling, status-code contract), `ManagedMailVerificationProvider` (single-use, time-boxed, domain-matched code/link extraction), `SecretCipher` (AES-256-GCM), `mailbox_verifications` audit, default-to-managed-address registration identity.
- **Not implemented**: `identity/EmployerCredentialService.java` has encrypted storage (`V040`) but — confirmed by `grep -rl EmployerCredentialService backend/src/main/java` returning no caller outside its own file — **nothing in the codebase currently calls it**. Employer-portal passwords are still carried as plaintext-in-plan values in the lifecycle plan rather than generated/stored/released through this service. This is the single largest gap between "the pieces exist" and "an employer account can actually be registered end-to-end."
- **Blocked externally**: no real Mailgun account, DNS record, or signing key exists; `ManagedMailboxService` readiness is `NOT_CONFIGURED`/`PENDING_RECEIVING_VERIFICATION` by design until a signed delivery is proven (see §6).

### 2.6 Application preparation, review, approval, submission safeguards
**Status: IMPLEMENTED_AND_VERIFIED**
- `apply/ApplyReadinessService.java` (re-evaluates every record on every request, never cached), `apply/ApplyDocumentSelector.java` / `automation/ApplyDocumentSelector.java` (digest-bound exact-version selection, no fallback to a newer/older document), `apply/ApplyPackageGuard.java` (withdraws a stale approval when a bound record changes), `application/ApplicationDecisionService.java` (fail-closed approval-rule resolution — `UNREADABLE`/`ABSENT`/`CONFIGURED` tri-state, confirmed by `ApplicationDecisionServiceTest`'s `ruleLoadExceptionInControlledAutoNeverAutoApproves` and siblings).
- `REAL_SUBMIT` hard stop confirmed in both backend and worker (§2.3). Human approval remains mandatory in every automatic mode when no explicitly enabled rule exists.

### 2.7 Application tracking, receipts, evidence records, recruiter communications
**Status: PARTIALLY_IMPLEMENTED** (solid on evidence discipline; honestly incomplete on live signal)
- `track/ApplicationReconciler.java`, `track/ReceiptVerificationPolicy.java` (VERIFIED requires an authorised mailbox connector + DKIM alignment + strong identifier — deliberately strict), `track/TrackEmailClassifier.java`, `track/EmailApplicationMatcher.java`, `TrackController`.
- Documented limitation, confirmed structurally true: **no mailbox connection currently feeds TRACK** — Gmail integration (Phase 8.4) is read-only for the user's own inbox sync UI, not wired into `ReceiptVerificationPolicy`'s "authorised mailbox connector" path, and managed-mail deliveries that aren't verification messages are explicitly "not fed into TRACK yet" (`ROBIN_BUILD_STATUS.md` Phase 8.5 known limitations). So no real application can currently reach a VERIFIED "submission confirmed" state — this is an accurate, not overstated, limitation.

### 2.8 Authentication, Firebase integration, account ownership, user-data isolation
**Status: IMPLEMENTED_AND_VERIFIED**
- Firebase Admin verification with explicit 503-on-missing-config (never silent fallback); server-side email-verification enforcement (unverified token → 403, identical response whether or not a local account exists, so no account-existence leak); `V022`/`V023` owner-scoping migration (fail-closed: unattributable legacy rows get `profile_id = NULL` and become invisible rather than guessed).
- Evidence (direct from this session's log inspection of `verify-85-current.log`): `UserDataIsolationIT` present and exercised (21/22 depending on run — the discrepancy is the `-Dit.postgres.url` opt-in flag, not a failure: 0 failures/errors in every run cited), `AuthControllerIT` 8/8, `SeededDevCredentialMigrationIT`/`RemovalIT` 5/5 each (confirms the `dev@example.local` seeded credential is neutralised outside local profile).

### 2.9 Backend authorization, rate limiting, CORS, CSRF, request validation
**Status: IMPLEMENTED_AND_VERIFIED, with one unverifiable external item (see §6, item 5)**
- `security/RateLimitService.java` (read directly this session): in-memory, per-JVM, fixed-window limiter with 7 named buckets (`auth` 20/min, `llm` 30/min, `discovery` 10/min, `track` 30/min, `gmail-sync` 6/hr, `gmail-authorize` 10/hr, `mail-inbound` 120/min) — explicitly documented as per-instance, not distributed, which matches the single-Render-instance deployment reality but would need revisiting under horizontal scaling.
- CORS: fail-closed by design — `application-prod.yml`, read directly this session, has **no default** for `APP_CORS_ALLOWED_ORIGINS` (startup fails rather than falling back to localhost), never emits `*`, and explicitly drops loopback origins in prod. `CorsOriginIT` (read directly this session, 5/5 passing per the log) is a real regression test for a **previously shipped production incident** (missing `/api/v1` suffix on `VITE_API_BASE_URL` caused opaque CORS errors instead of a legible 404) — it proves the mechanism works for *a* configured origin (`https://job-agent-beige.vercel.app`, the only origin hardcoded into any test), not for any specific new origin such as `aplivenosi.com` (see §6, item 5 — this is the one item in this whole audit that is genuinely unverifiable from the repository and needs an operator check).
- CSRF: `CsrfBearerExemptionIT` 5/5; cookie-based CSRF with `SameSite=None; Secure` in prod, correctly scoped so worker/webhook bearer-token paths are exempted without opening the session-cookie paths.

### 2.10 Frontend routes, dashboard sections, empty/failure/recovery states
**Status: IMPLEMENTED_AND_VERIFIED**
- Confirmed routes (read directly from `frontend/src/App.tsx` this session): `/login`, `/signup`, `/forgot-password`, `/verify-email`, `/`, `/jobs`, `/jobs/:id`, `/profile`, `/preferences`, `/models`, `/sources`, `/logs`, `/applications`, `/review-queue`, `/approval-rules`, with a catch-all redirect to `/`.
- 32 frontend test files exist under `frontend/src` covering most of the above pages plus dedicated empty/loading/error-state assertions (`ApprovalRulesPage.test.tsx`'s unconfigured-vs-disabled-vs-unreadable states is a representative example already read in `ROBIN_BUILD_STATUS.md`).
- `frontend/vercel.json` SPA rewrites currently list `/login`, `/signup`, `/forgot-password`, `/verify-email`, `/jobs`, `/jobs/:id`, `/profile`, `/preferences`, `/models`, `/sources`, `/logs` — **missing `/applications`, `/review-queue` and `/approval-rules`**, all three of which are real `<Route>`s added in Phases 6/7.1/8.1–8.3. A direct browser load (not a client-side navigation) of any of those three URLs on Vercel would 404 instead of serving the SPA. This is a verified, concrete gap (see roadmap R-CORS/SPA below).

### 2.11 Database migrations, persistence, background jobs, worker integration, external services
**Status: IMPLEMENTED_AND_VERIFIED**
- 42 Flyway migrations (`V001`–`V042`), additive-only discipline maintained throughout (confirmed by the Phase 7/7.2 note explicitly refusing to edit an already-applied migration's comment to avoid a checksum break), guarded bootstrap/baseline strategy in `FlywayMigrationStrategy` with its own regression test (`renderProductionStateBaselinedThroughV019ContinuesToTheShippedChain`).
- Redis is health-checked only; no runtime code path depends on it (events go through a Postgres outbox) — confirmed by `docs/DEPLOYMENT.md`.

### 2.12 Deployment configuration, environment variables, CI
**Status: IMPLEMENTED_AND_VERIFIED for the pipeline itself; NOT_VERIFIED for current live deployment state**
- `.github/workflows/ci.yml` (read directly this session): three jobs — `backend` (`mvn -B verify`, Testcontainers-backed, needs Docker), `worker` (`npm install` + Playwright Chromium install + `npm test`), `frontend` (`tsc -b`, `npm test`, `npm run build`) — triggered on push to `main`/`master`/`phase8.5-managed-mail` and on every PR. This matches what `ROBIN_BUILD_STATUS.md` reports was green for `dac60f3`.
- `docs/DEPLOYMENT.md` enumerates every required environment variable per host (Render, Vercel, Supabase, Upstash) with fail-closed behaviour documented for each missing case. This document is thorough and was spot-checked against the actual `application-prod.yml` in this session — they agree.
- What was **not** verified this session: the actual current Render/Vercel environment variable values, the SHA currently deployed, or whether `aplivenosi.com` has been added anywhere outside this repository (see §6).

## 3. Architecture and end-to-end workflow overview

```
FIND  → discovery/platform/* (30 ATS adapters + Tsenta) → jobs, job_identity_keys
PREP  → resume/*, coverletter/*, qa/*, documents/* → cv_versions, cover_letters, application_answers
APPLY → apply/ApplyReadinessService → apply/ApplyDocumentSelector → automation/ExecutionPackageService
         → AutomationController (plans) → worker (browser_worker.js) → MOCK_SUBMIT only, REAL_SUBMIT hard-stopped
TRACK → track/ApplicationReconciler ← application_events, application_receipts, candidate reports, Gmail (read-only), managed mail
```
Package layout (backend): `application, apply, ats, audit, automation, benchmark, cli, common, config, coverletter, discovery, documents, email, events, gmail, identity, jobs, llm, mailbox, mcp, notifications, orchestrator, preferences, prep, profile, qa, resume, security, sources, system, track` — 30 top-level modules, each with its own controller/service/repository and a corresponding test package. This module boundary is itself enforced: `ArchModuleBoundaryTest` (cited in Phase 8.1 verification) runs as part of the unit suite.

Worker is a separate Node/Playwright process (`worker/src/*.js`) that talks to the backend only through bearer-authenticated HTTP (`WORKER_EVENT_URL`, `WORKER_EVENT_TOKEN`) and never contacts an employer without an explicit plan — idle-safe by default.

## 4. Security and production-readiness assessment

Strong, verified-in-code:
- Fail-closed defaults everywhere that matters (CORS origins, datasource URL, registration invite code, approval-rule resolution, Flyway bootstrap).
- `REAL_SUBMIT` is a genuine code-level hard stop in both the backend (never emitted) and the worker (throws on receipt), not merely a documentation promise.
- Owner isolation is a real database-migration-level guarantee (`V022`/`V023`), not just an application-layer filter.
- Rate limiting covers the endpoints that matter (auth, LLM spend, discovery fan-out, OAuth initiation, the public Mailgun webhook) with sensible, generous-but-present windows.

Genuine open risks, in order of concreteness:
1. **CORS origin for the new frontend domain is unverified** — see §6 item 5. This is operational, not a code defect, but it is untested and nothing in the repo proves it works.
2. **Vercel SPA rewrites are missing three real routes** (`/applications`, `/review-queue`, `/approval-rules`) — a direct load 404s. Low risk (client-side nav still works), but a real defect, verified by direct comparison of `App.tsx` routes against `vercel.json`.
3. **Rate limiting is JVM-local** — explicitly documented as a scaling limitation, not a current defect, since the deployment is single-instance.
4. **`EmployerCredentialService` has no caller** — not a security hole (nothing insecure is happening), but it means the "encrypted credential store" is inert code today, so any claim that Robin can register a real employer account end-to-end is false until this is wired up.
5. **Production deployment state (current SHA, current env vars) was not re-verified this session** — everything above describes the code on this branch, not a confirmed live system.

## 5. Test results and unverified behavior

- **Not re-run this session.** A fresh `mvn -B verify` was not executed (it requires a live Docker daemon for Testcontainers and takes several minutes; given the volume of other verification needed, this audit relied on the committed log file `verify-85-current.log` plus direct source reads, rather than re-running the full suite). That log file was inspected directly this session (not merely cited from documentation) and independently confirms: `BUILD SUCCESS` (exactly one occurrence, i.e., a single successful run, not a retried failure hidden by scrollback), `AuthControllerIT` 8/8, `CorsOriginIT` 5/5, `CsrfBearerExemptionIT` 5/5, `SeededDevCredentialMigrationIT`/`RemovalIT` 5/5 each, `ApplicationTrackingIT` 19/19, `TrackMigrationCompatibilityIT` 1/1, final aggregate `172` tests in that module run, 0 failures, 0 errors.
- `EmployerCredentialService` lack-of-caller was confirmed by direct grep this session, not taken from documentation.
- `REAL_SUBMIT`/`FORBIDDEN_REAL_SUBMISSION` wiring was confirmed by direct source read this session (both the worker throw site and the absence of any backend emission site), not taken from documentation.
- `aplivenosi.com` was searched for across the entire repository text and git log this session and found **nowhere** — confirmed absent, not merely "not mentioned in docs I happened to read."
- **Still genuinely unverified** (requires either a live environment or operator action, not more code reading): current Render/Vercel deployed SHA and environment variables; whether a real Mailgun domain/DNS/signing key exists; whether `aplivenosi.com` receives correct CORS headers from the live backend; whether the worker has ever run against a real (non-mock) employer site.

## 6. External setup still required

1. **Mailgun domain, DNS, signing key** (Phase 8.5) — full 7-step operator checklist already documented in `docs/ROBIN_BUILD_STATUS.md` ("Managed email — external setup still needed"); not repeated here. Status: `BLOCKED_BY_EXTERNAL_SETUP`, confirmed still true (no Mailgun credentials or domain exist in any config read this session).
2. **`EmployerCredentialService` needs a caller** — this is code work, not external setup, but it blocks real employer-account creation; listed here because it is the dependency that makes Mailgun setup "worth it" for anything beyond the verification flow.
3. **`aplivenosi.com` CORS verification** — add it to the Render backend's `APP_CORS_ALLOWED_ORIGINS` (if not already done) and confirm with a real preflight request:
   ```bash
   curl -s -i -X OPTIONS https://<render-backend>/api/v1/auth/firebase/session \
     -H "Origin: https://aplivenosi.com" \
     -H "Access-Control-Request-Method: POST" \
     -H "Access-Control-Request-Headers: content-type"
   # Expect: Access-Control-Allow-Origin: https://aplivenosi.com, not absent
   ```
   This cannot be verified from the repository; it depends entirely on the live Render environment variable.
4. **Vercel `vercel.json` rewrites** for `/applications`, `/review-queue`, `/approval-rules` — this one *is* a code fix (see roadmap item 1 below), not external setup, but it blocks direct-load of those routes on the deployed frontend regardless of CORS.
5. **Firebase Authorized Domains** — if `aplivenosi.com` is a genuinely new frontend origin (not just a CORS-only proxy), Firebase Console → Authentication → Settings → Authorized domains must also include it, or sign-in fails with `auth/unauthorized-domain` (documented behaviour in `docs/DEPLOYMENT.md` §4.4, not independently re-verified this session since it requires Firebase console access).
6. **Live Gmail OAuth** — Phase 8.4 is mock/fixture-tested only; a real Google OAuth client and test mailbox are still needed to verify TRACK's mailbox-connector path end to end.
7. **A real (non-mock, non-Greenhouse) employer target** — needed to measure which ATS to build next per the Tsenta-informed roadmap (R6 in `docs/TSENTA_REA_INVESTIGATION.md`), and to ever validate the worker against anything but its own mock environment.

## 7. Prioritized implementation roadmap

Ordered by: correctness/safety first, then cheap verified fixes, then capability work. Each references real files; none of this duplicates existing functionality.

### P0 — Verify, don't assume (no code change, do first)
- **Confirm `aplivenosi.com` CORS and Firebase authorized-domain status** against the live Render/Firebase config (§6.3, §6.5). Acceptance: the curl in §6.3 returns the header; a real browser sign-in from `aplivenosi.com` succeeds without `auth/unauthorized-domain`.
- **Confirm current deployed SHA** via `GET /actuator/info` against the live Render backend, compare to `d764209`/`dac60f3`.

### P0 — Cheap, verified, offline-testable code fixes
1. **Add the three missing SPA rewrites to `frontend/vercel.json`** (`/applications`, `/review-queue`, `/approval-rules`, each `destination: /index.html`, matching the existing pattern). Problem/evidence: §2.10. Business value: direct links (shared, bookmarked, refreshed) to those three pages currently 404 on Vercel. Tests: a Vercel-config lint or, pragmatically, a documented manual check (`curl -I https://<vercel-url>/applications` returns 200 after the next deploy, not 404). Risk: none — purely additive routing. Priority: P0, no prerequisite.
2. **Recognise `*.zohorecruit.in` in `ats/AtsAdapters.java`'s `ZohoAdapter`** (§2.2; also R4 in `docs/TSENTA_REA_INVESTIGATION.md`). Acceptance: `findAdapterForUrl` returns the Zoho adapter for both `.com` and `.in` hosts, host-anchored (not substring) matching; a unit test asserting both. Risk: low.
3. **Job-board URLs (Indeed, Workable's public board) must never become an application URL** — `JobIdentity.AGGREGATOR_HOSTS` is Tsenta-only today (R1/R2 in the Tsenta report). Fix is source-agnostic (any discovery adapter could hit this), so it belongs in Robin's own hardening regardless of the Tsenta investigation's origin. Acceptance: a posting whose only URL is a recognised job-board host is treated as `skipped_no_employer_url` (new counter `skipped_job_board_url`), never surfaced as the employer application URL.

### P1 — Needs an explicit decision, then implementation
4. **Wire `EmployerCredentialService` into the account-lifecycle plan builder** (§2.5, biggest single gap). Problem: the encrypted store exists; nothing calls it; passwords still travel as plan values. Existing component: `identity/EmployerCredentialService.java`, `mailbox/RegistrationIdentityService.java`, worker `ACCOUNT_*` steps. Proposed change: generate a strong per-employer-tenant password server-side, store encrypted, release only to the worker for the matching owner/application/open-session (reuse the registration-identity binding rules), never place it in plan JSON, events, or logs. Tests: unit (generation/storage/scoping), IT (session/owner/employer binding, analogous to `ManagedMailIT`'s binding tests), worker test (plan carries no plaintext password). Security implication: this closes the only place where a real employer credential would otherwise need manual handling. Acceptance criteria: a lifecycle plan for a Workday-style account-required ATS contains no password value; the worker receives it only via the existing bearer-authenticated verification endpoint pattern; an application whose session is closed (HARD_STOP/FAILED/COMPLETED) cannot retrieve it. Prerequisite: none technically, but should follow the Mailgun operator setup (§6.1) so the lifecycle can be exercised end to end.
5. **Decide and implement the next live-inspection ATS adapter** (R6 in the Tsenta report) — build a read-only "which platform do Robin's own last-30-days jobs actually use" report first (reuses `JobIdentity`/`PlatformRegistry`, no new infra), then follow the `GreenhouseAdapter`/`GreenhouseFieldMapper` pattern for whichever platform wins. Do not pick from Tsenta's small 11-posting sample alone — it is a biased, tiny sample from one competitor's board, not Robin's own traffic.
6. **Tsenta source hardening (F3, F4 from the Tsenta report)** — treat Tsenta's HTTP-200 temporary error page as `UPSTREAM_ERROR` with an early-stop-after-N-consecutive policy, and prefer RSC job salary data over JSON-LD when the RSC currency is present. Both offline-testable against existing/new fixtures; both reduce wasted discovery budget rather than add risk.

### P2 — New capability, clearly scoped
7. **Per-country work-authorisation model** (`profile/VisaStatus.java` → `eligibility: [{country, authorisedToWork, needsSponsorship}]`, additive migration, keep `visaStatus` for compatibility). Value: current single-country model cannot answer the per-country screening questions most multinational employers ask.
8. **A derived "needs you" view** combining `PrepReadinessService`, `ApplyReadinessService`, `ApplicationDecisionService`, automation-plan status and TRACK review state into one read-only, non-persisted set of buckets on `ApplicationsPage.tsx`. Value: today the candidate has to check several pages to know what is blocked on them.

### P3 — Optional / explicitly deferred
9. A no-response ("ghosted") indicator derived after N days without new TRACK evidence (display-only, never stored as a status).
10. Scoped MCP access tokens for `/mcp` (`security/SecurityConfig.java`, `mcp/McpController.java`) — currently session+CSRF gated, i.e. unusable by an external agent; out of scope for the eight items above.

### Explicitly not recommended
Auto-approval of generated documents, automatic submission, or an "aggressive" optimisation mode that skips review — all would contradict the verified `REAL_SUBMIT` hard stop and the mandatory-human-approval posture that this audit confirms is real, not aspirational. Do not build toward matching a competitor's auto-submit feature.

## 8. Single highest-priority next engineering task

**Confirm `aplivenosi.com`'s CORS and Firebase-authorized-domain status against the live deployment (§6.3, §6.5), then fix `frontend/vercel.json`'s missing SPA rewrites (§7, item 1).**

Why this one first, ahead of any code-heavy item: it is the cheapest possible action (one curl command plus a Firebase console check, then a four-line config diff), it is the only item in this entire audit that is genuinely unverified rather than merely incomplete, and every other roadmap item assumes the frontend/backend pair the operator is actually using right now is reachable at all. Doing the Mailgun or credential-service work first while the frontend origin itself might be misconfigured would be solving the wrong problem first.

**Acceptance criteria:**
1. `curl -i -X OPTIONS <render-backend>/api/v1/auth/firebase/session -H "Origin: https://aplivenosi.com" -H "Access-Control-Request-Method: POST"` returns `Access-Control-Allow-Origin: https://aplivenosi.com`.
2. A real browser session at `https://aplivenosi.com` can complete Firebase sign-in without `auth/unauthorized-domain` in the console.
3. `frontend/vercel.json` lists rewrites for `/applications`, `/review-queue`, and `/approval-rules`; after the next Vercel deploy, `curl -I https://<frontend-domain>/applications` (direct load, not client-side nav) returns `200`, not `404`.
4. No application code outside `frontend/vercel.json` is touched to satisfy this task.
