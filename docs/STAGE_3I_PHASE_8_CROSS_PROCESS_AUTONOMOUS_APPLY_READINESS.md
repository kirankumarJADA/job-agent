# Stage 3I Phase 8 — Cross-Process Integration, Standing Authorization & Autonomous Apply Readiness

**Date:** 2026-11-28 · **Branch:** `integration/wave-3` · **Baseline HEAD:** `b265a8187564a53a1a38685abb3f42b56648aad2` · **Verified Code HEAD:** `52fd00dd91ec40831dc2e0f8e08d9c3189c394e4` · **PR #21:** `OPEN`, `isDraft: true`, unmerged targeting `master`

> **Safety & truthfulness notice:** All execution tests ran against local controlled HTTP fixtures (`cross_process_fixture_server.mjs` + `universal-employer-fixture`), PostgreSQL 16 Testcontainers, spawned Node `worker.js` processes, and headless Playwright Chromium. No real employer portal, live production database, or external candidate account was contacted or modified. PR `#21` remains `OPEN` and `DRAFT`.

---

## 1. Objectives & Summary of Delivered Architecture

Stage 3I Phase 8 closes the remaining autonomy, matching, liveness, withdrawal-classification, declaration-safety, evidence-verification, and cross-process verification gaps on `integration/wave-3` with **zero database migrations** (`V001`–`V073` byte-identical):

1. **Real Cross-Process End-to-End Integration (`CrossProcessAutonomousApplyE2EIT`):**
   - Spawns a real Spring Boot HTTP server (`RANDOM_PORT`), PostgreSQL 16 Testcontainers instance, controlled Node employer fixture server(s) (`worker/test/fixtures/cross_process_fixture_server.mjs`), and real OS child `node src/worker.js` processes running real headless Playwright Chromium (with optional intercept proxy `ProxyServer` for deterministic crash/network-drop/revocation timing).
   - Wired directly into CI's `backend` job (`.github/workflows/ci.yml` installs Node 22 and `worker` Playwright Chromium before `mvn -B verify`) and executed both locally (`DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`) and on `ubuntu-latest` CI.
2. **Explicit Opt-In Standing Autonomous Submission Path:**
   - Disabled by default (`StandingAutoApplyPolicy` stored in `preference_sets.extra_filters["standingAutoSubmit"]` with `enabled=false`).
   - Managed via `ApprovalRulesController` (`GET`/`PUT /api/v1/approval-rules` supporting optional `standingAutoSubmitEnabled`, `standingMinScore`, `dailySubmitCap`, `allowedCountries`, `permittedRoles`) and preserved on `PreferencesController` preference updates.
   - `StandingAuthorizationService` promotes a freshly prepared `AWAITING_APPROVAL` Pass-1 plan to `PREPARED` (`submit_approved=true`, `authorizationMode="STANDING_AUTO_SUBMIT"`) only when every gate passes: explicit opt-in, active `CONTROLLED_AUTO` mode, standing approval rule enabled, `apply-time` liveness clean, strict eligibility (`HardFilterService#evaluateStrict`) + permitted role + standing score threshold met, package ready with zero unresolved questions and zero package drift, no duplicate active/submitted application/plan for the job, and daily standing cap reserved (`standing_auto_submit_daily`).
   - `JdbcReceiptTrustStore.STRICT_APPROVAL_SQL` and `TrackRepository` bind standing authorizations strictly to BOTH the plan's `STANDING_SUBMIT_AUTHORIZED` row AND a prior candidate-actor `APPROVAL_RULE_UPDATED` opt-in row (`standingAutoSubmitEnabled = true`) — a bare `SYSTEM` or `worker` audit row without candidate opt-in fails closed.
3. **Closed Residual Lifecycle & Trust Gaps:**
   - **Forged / Off-Origin Evidence Refusal at Completion (`SubmissionCompletionService`):** Rejects `UNPARSEABLE` / unknown `schemaVersion` payloads, `robin-submission-evidence-v2` payloads missing `evidenceSha256`, mismatched `attemptCorrelation` (`planId`/`applicationId`/`jobId`), and missing/off-origin `finalOrigin` before `submission_evidence` insertion or `SUBMITTED` / `APPLICATION_SUBMITTED` transitions (returning `422 RECONCILIATION_REQUIRED` with `0` `submission_evidence` rows).
   - **SSRF-Safe Apply-Time Posting Liveness (`PostingLivenessService` + `SafeHttpClient.forGuard` + `AddressGuard.allowingLoopbackForTesting`):** Evaluates `jobs.deleted_at` / `ARCHIVED` always and probes `coalesce(nullif(p.target_url, ''), j.application_url, j.canonical_url)` when `app.automation.liveness-check-enabled=true`. HTTP 404/410 or explicit closed copy returns `CLOSED` (plan fails `409 JOB_POSTING_CLOSED`); SSRF blocks return `UNSAFE_TARGET`; auth walls (`401`/`403`/login forms), `429`, `5xx`, and timeouts conservatively return `INCONCLUSIVE` with audit telemetry.
   - **Strict Matching & Auto-Apply Consistency (`HardFilterService#evaluateStrict`, `JobMatchService`, `AutoApplyBatchService`, `ApplicationDecisionService`):** Fixed `"REQUIRED"` / `"SPONSORSHIP_REQUIRED"` string mismatch and added strict missing-field rejection (`salary`, `country`/`location`, `workplaceType`, `employmentType`, `experienceLevel`, unconfirmed sponsorship) across auto-apply routing (`NEEDS_REVIEW`), batch runs, standing promotion, and the pre-submit fence.
   - **Pre-Fence Withdrawal Classification (`worker/src/outcome.js`, `AutomationPlanRepository#returnUnfencedSubmitToAwaitingApproval`, `AutomationController#complete`):** Pre-fence authorization denials (`AUTHORIZATION_REFUSED`, `clicked: false`, no checkpoint) route to `HUMAN_REQUIRED` / return cleanly to `AWAITING_APPROVAL` instead of misleadingly landing in `RECONCILIATION_REQUIRED`.
   - **Prohibited Declaration Guard (`worker/src/universal_form.js`):** `isProhibitedDeclaration` classifies mandatory perjury/certification/legal declarations, background checks, and terms/privacy acceptance checkboxes as `PROHIBITED_DECLARATION` required gaps, stopping autonomous progression cleanly in `AWAITING_APPROVAL`.
   - **TRACK Scope Honesty (`ApplicationTrackingService`):** Responses now carry explicit `reconciliationScope` (`employerStatusSource: "EMAIL_AND_RECEIPT_ONLY"`, `employerPortalPollingSupported: false`).
   - **Loopback Test Harness Compatibility on Handoff (`AtsHandoffService` & `HandoffController`):** Gated behind `app.sandbox.allow-local-test-hosts` (`false` by default; `true` only in test harness) and restricted to explicit loopback hosts while keeping all non-loopback HTTP, `file://`, `data:`, `blob:`, `javascript:`, and credentialed URLs refused.

---

## 2. Cross-Process E2E Scenarios (`CrossProcessAutonomousApplyE2EIT`)

All 9 methods in `CrossProcessAutonomousApplyE2EIT` executed and passed against real Spring Boot HTTP (`RANDOM_PORT`), PostgreSQL 16 Testcontainers, spawned `node src/worker.js`, and headless Playwright Chromium:

| Scenario / Method | Verified Behavior |
|---|---|
| **A** (`scenarioA_standingAutoApply...`) | Mandatory prohibited declaration stops cleanly in `AWAITING_APPROVAL` (`STANDING_SUBMIT_DEFERRED` / `PROHIBITED_DECLARATION`, 0 clicks); unknown required question stops with `NEEDS_USER_INPUT` (0 clicks); fully pre-confirmed eligible role auto-promotes Pass 1 → `PREPARED` (`STANDING_AUTO_SUBMIT`) → Pass 2 → 1 click → `SUBMITTED` / `APPLICATION_SUBMITTED`, `submission.verificationLevel = BROWSER_OBSERVED`, honest `reconciliationScope`. |
| **B** (`scenarioB_revokedAuthorizationPreFence...`) | Intercepted `PRE_SUBMIT_ATTEMPTING` fence revokes standing opt-in right before fence evaluation; backend returns `409 STANDING_PERMISSION_WITHDRAWN`, worker sends `AUTHORIZATION_REFUSED` (`clicked: false`), backend returns plan to `AWAITING_APPROVAL` (`AUTOMATION_PLAN_PRE_FENCE_WITHDRAWN`, 0 clicks, 0 fences, not `RECONCILIATION_REQUIRED`). |
| **C** (`scenarioC_duplicateWorkerProcesses...`) | Two concurrent `worker.js` subprocesses submit `planId` once only (`acceptedSubmissions = 1`, `fenceCount = 1`); database unique index `applications_profile_job_live_uq` rejects a second live application for `(profile_id, job_id)`; second `RUNNING` plan for same application is blocked `409` at fence (`fenceCount = 0`). |
| **D** (`scenarioD_workerCrashAfterFence...`) | Child worker killed on `/complete` after `PRE_SUBMIT_ATTEMPTING` fence commits: `recoverStaleDetailed` quarantines to `RECONCILIATION_REQUIRED` (`submit_approved = false`), and restarted worker sharing state never clicks a second time. |
| **E** (`scenarioE_backendUnreachableForFence...`) | Proxy drops connection on `PRE_SUBMIT_ATTEMPTING` fence request; worker fails closed with `0` fences and `0` employer clicks. |
| **F** (`scenarioF_wrongEmployerDomainOrOffOrigin...`) | Pre-submit fence reporting `currentUrl` on `fixtureB` (different origin/port) while bound to `fixtureA` fails `409 TARGET_DOMAIN_MISMATCH` with `0` clicks. |
| **G** (`scenarioG_captchaPausesWithHandoff...`) | `captchaMode: "page2"` pauses on `.g-recaptcha` via `/api/v1/handoff/initiate` -> `HANDOFF_REQUIRED` (`0` clicks, `0` fences); `/api/v1/handoff/{id}/resume` returns plan to `PREPARED`. |
| **H** (`scenarioH_tamperedAndOffOrigin...`) | Self-signed v2 evidence claiming off-origin `finalOrigin` (`fixtureB`), cross-app/job `attemptCorrelation` replay, and unsealed v2 missing `evidenceSha256` all refuse with `422 RECONCILIATION_REQUIRED` (`applications.status != APPLICATION_SUBMITTED`, `0 submission_evidence`); candidate CSRF session self-submit refuses `403`. (`ReceiptReconciliationIT` separately confirms read-time `EVIDENCE_DIGEST_MISMATCH` / `REDIRECT_OUTSIDE_TARGET_HOST` -> `ATTEMPT_RECORDED`). |
| **Liveness & Concurrency** (`applyTimeLivenessAndLockConcurrency...`) | HTTP `404` posting fails claim `204` + plan `FAILED` (`AUTOMATION_LIVENESS_FAILED`); login-wall `200` logs `AUTOMATION_LIVENESS_INCONCLUSIVE` and claims cleanly; concurrent `/withdraw-approval` vs `/steps` (`PRE_SUBMIT_ATTEMPTING`) enforces strict XOR mutual exclusion (`200+409` or `204+409`). |

---

## 3. Verification & Migration Evidence

- **Schema Migrations:** `git diff ab854de9fc25eeff3442fa8d6e0a09af1a698470 -- backend/src/main/resources/db/migration` is empty (`V001`–`V073` byte-identical; zero new migrations).
- **Local Verification on Commit `52fd00dd91ec40831dc2e0f8e08d9c3189c394e4` (Windows 11 + Docker Desktop 29.0.1):**
  - `backend` (`DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine mvn -B verify`): Surefire **`2,151` run, `0` failures, `0` errors, `6` skipped** (`ArchModuleBoundaryTest` `4/4`) + Failsafe **`514` run, `0` failures, `0` errors, `7` skipped** (`CrossProcessAutonomousApplyE2EIT` `9/9` in `54.8s`, `ReceiptReconciliationIT` `14/14`, `ApplicationReceiptIT` `22/22`); `BUILD SUCCESS` in `23:03 min`.
  - `worker` (`npm test`): **`413` total — `412` pass, `0` fail, `1` skipped** (Linux-only POSIX `SIGTERM` signal test).
  - `cloudflare-email-worker` (`npm test`): **`25/25` pass, `0` fail**.
  - Phase 20 probes (`node --test scripts/phase20/*.test.mjs`): **`8/8` pass, `0` fail**.
  - `frontend`: `npx tsc -b`, `npm test` (**`49` files / `492` tests pass**), and `vite build` all passed cleanly.
- **GitHub Actions CI Run `46159971337` on Commit `52fd00dd91ec40831dc2e0f8e08d9c3189c394e4` (`ubuntu-latest`):**
  - `cloudflare-email-worker` (`141024900320`): **`success`** (`25/25` + `8/8`)
  - `worker` (`141024900473`): **`success`** (**`413/413` pass, `0` fail, `0` skipped** — including Linux POSIX `SIGTERM` test)
  - `frontend` (`141024900504`): **`success`** (**`49` files / `492` tests pass** + `tsc -b` + `vite build`)
  - `backend` (`141024900633`): **`success`** in `11:18 min` (Surefire **`2,151` run, `0` failures, `0` errors, `6` skipped** + Failsafe **`514` run, `0` failures, `0` errors, `7` skipped**; `CrossProcessAutonomousApplyE2EIT` **`9/9` in `24.05 s`**, `PreSubmitFenceParityIT` **`22/22`**, `UniversalBrowserE2EIT` **`9/9`**, `AutomationLifecycleIT` **`22/22`**, `ApplicationReceiptIT` **`22/22`**, `ReceiptReconciliationIT` **`14/14`**, `ApplicationTrackingIT` **`20/20`**)
- **PR State:** PR `#21` remains **`OPEN` and `isDraft: true`**, unmerged into `master`.

---

## 4. Remaining Operational & External Validation Gate (`NO-GO`)

- Live employer portals, production Cloudflare Email Routing (`TRUSTED_AUTHSERV_IDS`), Redis-backed deployments, and external staging remain **`BLOCKED / NO-GO`** until Stage 3G operator authorization, staging credentials (`ROBIN_WORKER_SESSION_KEY`), and deployment environment checks are completed.
- `app.automation.liveness-check-enabled` defaults to `false` safely in existing deployments unless explicitly enabled alongside outbound egress policy.
