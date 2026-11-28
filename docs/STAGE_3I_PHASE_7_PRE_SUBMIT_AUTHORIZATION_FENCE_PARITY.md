# Stage 3I Phase 7 — Universal Pre-Submit Authorization Fence Parity

**Date:** 2026-11-28 · **Branch:** `integration/wave-3` · **Baseline HEAD:** `ab854de9fc25eeff3442fa8d6e0a09af1a698470` · **PR #21:** `OPEN`, `isDraft: true`, unmerged targeting `master`

> **Safety & truthfulness notice:** No real employer application was submitted, no external system or production database was accessed, and nothing was deployed. All tests run on local fixtures and PostgreSQL Testcontainers only.

---

## 1. Workstream 0 — Preflight & Execution Path Trace

### 1.1 Preflight baseline
- **Repository:** `kirankumarJADA/job-agent`
- **Branch & HEAD:** `integration/wave-3` at `ab854de9fc25eeff3442fa8d6e0a09af1a698470`, aligned with `origin/integration/wave-3`, clean working tree.
- **PR #21:** `OPEN`, `isDraft: true`, targeting `master`, `mergedAt: null`, `headRefOid: ab854de9fc25eeff3442fa8d6e0a09af1a698470`.
- **Baseline CI (`46046248127`):** completed / `success` across all 4 workflows (`cloudflare-email-worker`, `frontend`, `backend`, `worker`).
- **Migrations baseline:** `V001`–`V073`. Existing `automation_plans` and `automation_steps` schemas already store `submit_approved`, `plan` JSONB, `result` JSONB, and unique `(plan_id, step_key)` rows for `'pre-submit-attempting'`. No schema migration (`V074`) is needed for Phase 7.

### 1.2 Submit-capable vs non-submit ATS adapters

| Adapter (`planType`) | Submit-capable? | Prep/Inspect Step(s) | Final Submit Step | Boundary Before Irreversible Action | Baseline Pre-Phase-7 Fence Status |
|---|---|---|---|---|---|
| `UNIVERSAL` | Yes (gated) | `NAVIGATE`, `UNIVERSAL_APPLY` | `UNIVERSAL_SUBMIT_CLICK` | `BrowserWorker.#runUniversalSubmitClick` before `submitBtn.first().click()` | **Partial:** Worker calls `stepsReporter` (`POST /plans/{id}/steps`, `PRE_SUBMIT_ATTEMPTING`); backend checks `RUNNING + submit_approved=true` under `FOR UPDATE` + no prior fence; `invalidateApproval` locks `RUNNING` `UNIVERSAL` plans only. Missing full shared readiness/binding/domain/standing-permission checks at the fence. |
| `GREENHOUSE` | Yes (gated) | `NAVIGATE`, `FILL_FIELD`, `UPLOAD_FILE`, `VALIDATE` | `REAL_SUBMIT_CLICK` | `BrowserWorker.#runRealSubmitClick` before `#captureGreenhouseSubmissionDuringClick(...)` / `submitButton.first().click()` | **GAP (P1):** Worker only wrote local `StateStore.markStep(..., ATTEMPTING)`. Zero backend `PRE_SUBMIT_ATTEMPTING` call, zero check of `#hasBackendPreSubmitAttempt`, and `invalidateApproval` ignored `RUNNING` `GREENHOUSE` plans (`planType = 'UNIVERSAL'` filter). |
| `ASHBY` | Yes (gated) | `NAVIGATE`, `ASHBY_APPLY` | `ASHBY_SUBMIT_CLICK` | `BrowserWorker.#runAshbySubmit` before `#captureAshbySubmissionDuringClick(...)` / `submitButton.first().click()` | **GAP (P1):** Worker only wrote local `StateStore.markStep(..., ATTEMPTING)`. Zero backend `PRE_SUBMIT_ATTEMPTING` call, zero check of `#hasBackendPreSubmitAttempt`, and `invalidateApproval` ignored `RUNNING` `ASHBY` plans. |
| `WORKDAY` | Yes (gated) | `NAVIGATE`, `WORKDAY_LOGIN` / `WORKDAY_CREATE_ACCOUNT` / `WORKDAY_APPLY` | `WORKDAY_SUBMIT_CLICK` | `BrowserWorker.#runWorkdaySubmitClick` before `submitButton.first().click()` | **GAP (P1):** Worker only wrote local `StateStore.markStep(..., ATTEMPTING)`. Zero backend `PRE_SUBMIT_ATTEMPTING` call, zero check of `#hasBackendPreSubmitAttempt`, and `invalidateApproval` ignored `RUNNING` `WORKDAY` plans. |
| `ZOHO_RECRUIT`, `LEVER`, `INSPECTION` | No (`NO_SUBMIT`) | `ZOHO_RECRUIT_APPLY` / `LEVER_APPLY` / inspect | None (forbidden by `plan.js` & `AutomationPlanRepository`) | N/A — submission steps blocked | Blocked across planner, controller, worker, and completion service. |

### 1.3 End-to-end execution path per adapter (baseline trace)

1. **Plan creation & package assembly:**
   - `ApplicationPipelineEventHandler#handleCreated` or `ApplyController#createPackage` creates an `automation_plans` row in `PREPARED` (`submit_approved = false`, `safetyContract = NO_SUBMIT` / `STEP_ALLOWLIST_NO_AUTO_SUBMIT`).
2. **Candidate review & explicit two-step submit gating (shared backend):**
   - Worker runs preparation steps and calls `POST /api/v1/automation/plans/{id}/complete` with `HUMAN_REQUIRED`, moving `automation_plans` -> `AWAITING_APPROVAL` (`submit_approved = false`).
   - Candidate calls `POST /api/v1/automation/plans/{id}/approve-submit` (`AutomationController#approve`): re-runs `ApplyReadinessService#evaluate` + `packageDrift` + `approvalPrecondition`, transitions `AWAITING_APPROVAL` -> `READY_TO_SUBMIT` (`submit_approved = true`) via `AutomationPlanRepository#approveForSubmission`.
   - Candidate calls `POST /api/v1/automation/plans/{id}/prepare-submit` (`AutomationController#prepareSubmit`): re-runs `ApplyReadinessService#evaluate` + `packageDrift` and atomically transitions `READY_TO_SUBMIT` -> `PREPARED` while appending the adapter's trailing submit step (`prepareRealSubmission` -> `REAL_SUBMIT_CLICK`, `prepareAshbySubmission` -> `ASHBY_SUBMIT_CLICK`, `prepareWorkdaySubmission` -> `WORKDAY_SUBMIT_CLICK`, `prepareUniversalSubmission` -> `UNIVERSAL_SUBMIT_CLICK`).
3. **Worker claim (`RUNNING`):**
   - Worker polls `POST /api/v1/automation/plans/claim-next` (`AutomationPlanRepository#claimNext` using `FOR UPDATE SKIP LOCKED`) transitioning `PREPARED` -> `RUNNING` while preserving `submit_approved = true`.
4. **Browser execution & exact irreversible boundary:**
   - `worker.js` instantiates `BrowserWorker` with `stepsReporter` wired to `POST /api/v1/automation/plans/{id}/steps`.
   - **`GREENHOUSE`** (`#runRealSubmitClick`): re-verifies host identity + form readiness -> **BOUNDARY** -> `#acquireBackendPreSubmitFence` (missing pre-Phase-7) -> `failAfterAttemptMarker` -> `#captureGreenhouseSubmissionDuringClick` -> `submitButton.first().click()`.
   - **`ASHBY`** (`#runAshbySubmit`): re-verifies Ashby host identity -> **BOUNDARY** -> `#acquireBackendPreSubmitFence` (missing pre-Phase-7) -> `failAfterAttemptMarker` -> `#captureAshbySubmissionDuringClick` -> `submitButton.first().click()`.
   - **`WORKDAY`** (`#runWorkdaySubmitClick`): re-verifies Workday tenant identity + page state -> **BOUNDARY** -> `#acquireBackendPreSubmitFence` (missing pre-Phase-7) -> `failAfterAttemptMarker` -> `submitButton.first().click()`.
   - **`UNIVERSAL`** (`#runUniversalSubmitClick`): checks `#hasBackendPreSubmitAttempt` + page identity + honeypot/CAPTCHA + live submit button -> **BOUNDARY** -> `#acquireBackendPreSubmitFence` (`stepsReporter` -> `PRE_SUBMIT_ATTEMPTING`) -> `failAfterAttemptMarker` -> `#captureUniversalSubmission` -> `submitBtn.first().click()`.
5. **Completion & durable recording:**
   - Worker calls `POST /api/v1/automation/plans/{id}/complete` -> `AutomationController#completeRealSubmission` -> `SubmissionCompletionService#completeWorkerSubmission` (requires `RUNNING` + `submit_approved = true` + trailing submit step + verified `SUBMISSION_EVIDENCE` for `SUBMITTED`, else `RECONCILIATION_REQUIRED`).

### 1.4 Shared Phase 7 Target Architecture
- Extract **one shared backend authority** (`PreSubmitAuthorizationService`) invoked by `AutomationController#step` when `isPreSubmitFence` (`PRE_SUBMIT_ATTEMPTING` / `'pre-submit-attempting'`) is requested across **all** submit-capable adapters (`GREENHOUSE`, `ASHBY`, `WORKDAY`, `UNIVERSAL`).
- Remove the `planType = 'UNIVERSAL'` restriction from `AutomationPlanRepository#invalidateApproval` so running pre-fence plans across all 4 adapters participate identically in package/approval/eligibility invalidation.
- Add explicit approval invalidation hooks on candidate withdrawal (`POST /api/v1/automation/plans/{id}/withdraw-approval`), application withdrawal/failure (`ApplicationStatusService`), preference changes (`PreferencesController`), approval-rule changes (`ApprovalRulesController`), and work-eligibility changes (`ProfileController`).
- Extract **one shared worker fence helper** (`BrowserWorker.#acquireBackendPreSubmitFence`) invoked at the exact pre-click boundary in `#runRealSubmitClick`, `#runAshbySubmit`, `#runWorkdaySubmitClick`, and `#runUniversalSubmitClick`, with fail-closed behavior whenever `stepsReporter` is absent, unreachable (`503`/network error), or refuses authorization (`409`), plus loop/startup `#hasBackendPreSubmitAttempt` protection across all 4 submit step types.

---

## 2. Workstream A — Shared Backend Authorization & Parity Implementation

### 2.1 Shared backend pre-submit gate (`PreSubmitAuthorizationService`)
Implemented in `backend/src/main/java/com/personal/jobagent/automation/PreSubmitAuthorizationService.java` and invoked from `AutomationController#step` (fails closed `503 PRE_SUBMIT_AUTH_UNAVAILABLE` if unwired):

1. **Worker-only authority:** `AutomationController#step` rejects non-worker/session requests with `403`, never allowing browser or candidate self-authorization.
2. **Row-locked correlation & binding (`for update of p`):** Verifies plan existence (`404`), `hasPreSubmitAttemptCheckpoint` (`409 DUPLICATE_PRE_SUBMIT_ATTEMPT`), `automation_plans.profile_id == applications.profile_id` (`409 BINDING_MISMATCH`), `jobs.deleted_at IS NULL` & not `ARCHIVED` (`409 JOB_DELETED`), active application status (`409 APPLICATION_NOT_ELIGIBLE`), zero sibling `SUBMITTED` plans (`409 APPLICATION_ALREADY_SUBMITTED`), and `plan.correlation` parity.
3. **Adapter & submit step enforcement:** Admits only `{GREENHOUSE, ASHBY, WORKDAY, UNIVERSAL}` paired with exact trailing step `{REAL_SUBMIT_CLICK, ASHBY_SUBMIT_CLICK, WORKDAY_SUBMIT_CLICK, UNIVERSAL_SUBMIT_CLICK}` (`409 ADAPTER_SUBMIT_UNSUPPORTED`, `409 SUBMIT_STEP_MISSING`, `409 ADAPTER_MISMATCH`, `409 SUBMIT_STEP_MISMATCH`).
4. **Employer domain & live URL binding:** Validates `jobs.application_url`, `automation_plans.target_url`, `correlation.employerDomain`, and worker-reported `currentUrl` (HTTPS required; loopback gated on `app.sandbox.allow-local-test-hosts`; board/tenant/registrable-domain matching via `EmployerDomainPolicy.registrableDomain`).
5. **Standing permission, eligibility, and package integrity:** Enforces `submit_approved = true` (`409 SUBMIT_APPROVAL_WITHDRAWN`) and `status = 'RUNNING'` (`409 PLAN_NOT_RUNNING`), checks `work_eligibility`, standing `user_approval_rules` / `application_mode` / `HardFilterService` constraints (full `HardFilterService` + standing score rule on `CONTROLLED_AUTO`; salary floor / excluded keywords / sponsorship / company filter on candidate-queued flows), re-runs `ApplyReadinessService#evaluate` and `PreSubmitAuthorizationService#detectPackageDrift` (`409 PACKAGE_NOT_READY` / `409 PACKAGE_DRIFT`), and atomically inserts `'pre-submit-attempting'` via `AutomationPlanRepository#recordPreSubmitAttemptIfAbsent`.
6. **Fail closed:** `MaintenanceModeService.isMaintenanceMode()`, missing collaborators, `ReadinessUnavailableException`, `DataAccessException`, and `RuleAvailability.UNREADABLE` all refuse with `503`.

### 2.2 Symmetric invalidation & candidate withdrawal hooks
- `AutomationPlanRepository#invalidateApproval`: removed the `plan->>'planType' = 'UNIVERSAL'` predicate so running pre-fence approvals across `GREENHOUSE`, `ASHBY`, `WORKDAY`, and `UNIVERSAL` all clear `submit_approved = false` atomically under row lock (`invalidateApprovalsForProfile` and `withdrawPlanApproval` added cleanly with the same lock/fence discipline).
- `AutomationController#withdrawApproval` (`POST /api/v1/automation/plans/{id}/withdraw-approval`): explicit candidate withdrawal endpoint returning `409 PRE_SUBMIT_ATTEMPT_ALREADY_STARTED` once fenced.
- `AutomationController#approvalPrecondition`: checks `hasPreSubmitAttemptCheckpoint(plan.id())` unconditionally across all adapters.
- `ApplyPackageGuard#invalidateForProfile` wired optionally (`ObjectProvider<ApplyPackageGuard>`) into `ApplicationStatusService#apply` (`WITHDRAWN`/`FAILED`), `PreferencesController#update`, `ApprovalRulesController#put` (disabled/raised `minScore`), and `ProfileController#updateProfile` (`workEligibility` changes).

### 2.3 Worker pre-submit fence parity
- `BrowserWorker.#acquireBackendPreSubmitFence(page, plan, step, duplicateCategory)` called right before irreversible click across `#runRealSubmitClick` (`GREENHOUSE`), `#runAshbySubmit` (`ASHBY`), `#runWorkdaySubmitClick` (`WORKDAY`), and `#runUniversalSubmitClick` (`UNIVERSAL`), sending `atsPlatform`, `stepType`, and live `currentUrl`.
- `BrowserWorker#execute` checks both `prior?.status === REAL_SUBMIT_ATTEMPTING` and `#hasBackendPreSubmitAttempt(plan)` for all four submit step types.
- Missing `stepsReporter`, non-`ok` responses, HTTP `503`/network errors, and `409` refusals all fail closed with **0 browser submit clicks**.
- `worker.js` `reportPlanStep` parses structured `409` error `{ code, error }` payloads and `executePollCycle` attaches `activeStepsReporter` automatically when an external `runner` lacks `stepsReporter`.

---

## 3. Workstream B & C — Adapter Matrix & Scenario Coverage

| Scenario | `GREENHOUSE` (`REAL_SUBMIT_CLICK`) | `ASHBY` (`ASHBY_SUBMIT_CLICK`) | `WORKDAY` (`WORKDAY_SUBMIT_CLICK`) | `UNIVERSAL` (`UNIVERSAL_SUBMIT_CLICK`) |
|---|---|---|---|---|
| Happy path: `/steps` (`PRE_SUBMIT_ATTEMPTING`) precedes click & `SUBMITTED` | Covered (`PreSubmitFenceParityIT`, `pre_submit_fence_parity.test.js`, `greenhouse_sandbox_e2e.test.js`) | Covered (`PreSubmitFenceParityIT`, `pre_submit_fence_parity.test.js`) | Covered (`PreSubmitFenceParityIT`, `pre_submit_fence_parity.test.js`) | Covered (`PreSubmitFenceParityIT`, `pre_submit_fence_parity.test.js`, `UniversalBrowserE2EIT`) |
| 1. Approval withdrawn before pre-submit fence | Covered (`withdraw-approval`, `409`, 0 clicks) | Covered (`withdraw-approval`, `409`, 0 clicks) | Covered (`withdraw-approval`, `409`, 0 clicks) | Covered (`withdraw-approval`, `409`, 0 clicks) |
| 2. Approval withdrawn while `RUNNING` pre-fence | Covered (`PreSubmitFenceParityIT`, `invalidateApproval`, `409 SUBMIT_APPROVAL_WITHDRAWN`) | Covered (`PreSubmitFenceParityIT`, `invalidateApproval`, `409 SUBMIT_APPROVAL_WITHDRAWN`) | Covered (`PreSubmitFenceParityIT`, `invalidateApproval`, `409 SUBMIT_APPROVAL_WITHDRAWN`) | Covered (`PreSubmitFenceParityIT`, `UniversalBrowserE2EIT`, `409 SUBMIT_APPROVAL_WITHDRAWN`) |
| 3. Concurrent workers race fence (4 threads) | Covered (`PreSubmitFenceParityIT`: 1×`204`, 3×`409`) | Covered (`PreSubmitFenceParityIT`: 1×`204`, 3×`409`) | Covered (`PreSubmitFenceParityIT`: 1×`204`, 3×`409`) | Covered (`PreSubmitFenceParityIT`, `UniversalBrowserE2EIT`: 1×`204`, N×`409`) |
| 4. Crash before fence | Covered (`PreSubmitFenceParityIT` + `greenhouse.test.js`) | Covered (`PreSubmitFenceParityIT` + `ashby.test.js`) | Covered (`PreSubmitFenceParityIT` + `workday.test.js`) | Covered (`PreSubmitFenceParityIT` + `universal_engine.test.js`) |
| 5. Crash after fence / stale quarantine | Covered (`PreSubmitFenceParityIT`, `pre_submit_fence_parity.test.js`) | Covered (`PreSubmitFenceParityIT`, `pre_submit_fence_parity.test.js`) | Covered (`PreSubmitFenceParityIT`, `pre_submit_fence_parity.test.js`) | Covered (`PreSubmitFenceParityIT`, `pre_submit_fence_parity.test.js`) |
| 6. Backend auth unavailable (`503` / missing reporter) | Covered (`PreSubmitAuthorizationServiceTest`, `pre_submit_fence_parity.test.js`) | Covered (`PreSubmitAuthorizationServiceTest`, `pre_submit_fence_parity.test.js`) | Covered (`PreSubmitAuthorizationServiceTest`, `pre_submit_fence_parity.test.js`) | Covered (`PreSubmitAuthorizationServiceTest`, `pre_submit_fence_parity.test.js`) |
| 7. Eligibility / standing permission / domain / binding / package drift | Covered (`PreSubmitFenceParityIT`, `PreSubmitAuthorizationServiceTest`) | Covered (`PreSubmitFenceParityIT`, `PreSubmitAuthorizationServiceTest`) | Covered (`PreSubmitFenceParityIT`, `PreSubmitAuthorizationServiceTest`) | Covered (`PreSubmitFenceParityIT`, `PreSubmitAuthorizationServiceTest`) |
| 8. Stale worker / session self-auth / cross-candidate / non-submit adapters | Covered (`PreSubmitFenceParityIT`, `UniversalAutomationControllerTest`) | Covered (`PreSubmitFenceParityIT`) | Covered (`PreSubmitFenceParityIT`) | Covered (`PreSubmitFenceParityIT`) |

---

## 4. Workstream D — Verification Results & Migrations

### 4.1 Local Verification (Windows 11)

| Suite | Command | Result |
|---|---|---|
| Migrations immutability | `git diff ab854de -- backend/src/main/resources/db/migration` | **0 diff** (`V001`–`V073` byte-identical; no `V074`) |
| Backend unit & ArchUnit | `mvn -f backend/pom.xml -B test` | **2,141 run, 0 failures, 0 errors, 6 skipped** (baseline Redis-env skips) |
| Backend PostgreSQL ITs | `mvn -B verify` | **Not run locally** (Docker Desktop Linux Engine unavailable on host); executed in GitHub Actions CI |
| Worker Playwright & unit | `npm --prefix worker test` | **410 total: 409 passed, 0 failed, 1 skipped** (`POSIX SIGTERM` test skipped on Windows; runs on Linux CI) |
| Worker 4-adapter fence parity | `node --test worker/test/pre_submit_fence_parity.test.js` | **4/4 passed** (covering 24 adapter × scenario checks) |
| Frontend build & Vitest | `npm --prefix frontend run build` + `cd frontend && npm test` | **49 files, 492/492 passed**, `tsc -b && vite build` clean |
| Cloudflare email worker | `npm --prefix cloudflare-email-worker test` | **25/25 passed** |
| Phase 20 probe tests | `node --test scripts/phase20/*.test.mjs` | **8/8 passed** |

### 4.2 External Validation & Gate Status — **NO-GO**
- External validation, production database access, and real-employer submissions remain **blocked / `NO-GO`** pending Stage 3G preconditions.
- PR `#21` remains **`OPEN` and `DRAFT`**, unmerged into `master`.

