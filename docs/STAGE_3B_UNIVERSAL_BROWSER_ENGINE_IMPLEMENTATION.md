# ROBIN — STAGE 3B: UNIVERSAL BROWSER APPLICATION ENGINE — IMPLEMENTATION REPORT

**Final Verdict:** `STAGE 3B COMPLETE — LOCAL UNIVERSAL VERTICAL SLICE PROVEN`

**Repository:** `kirankumarJADA/job-agent`  
**Branch:** `integration/wave-3`  
**Base Commit (Stage 3B start):** `fc2dcc71f60b68a6170451912440ec5f4a3de812`  
**PR Boundary:** [#21](https://github.com/kirankumarJADA/job-agent/pull/21) remains **OPEN** and **DRAFT** (`V001`–`V070` completely unmodified)  

---

## 1. Original Architecture Reused

Stage 3B extends Robin's existing automation pipeline, document governance, Q&A store, worker queue, and evidence/tracking stack rather than creating a parallel worker, queue, controller, repository, or tracking model:

- **Plan dispatch & worker polling loop:** Extended the existing `automation_plans` + `automation_steps` persistence (`AutomationPlanRepository`), `/api/v1/automation/*` REST API (`AutomationController`, `WorkerEventTokenFilter`), `worker/src/worker.js` polling loop (`executePollCycle`), `worker/src/plan.js` plan allowlist validation, `worker/src/browser_worker.js` Playwright runner, and `worker/src/outcome.js` outcome/reconciliation builders.
- **Document governance:** Reused `ApplyDocumentSelector` (failing closed when reviewed/approved tailored CV or cover letter metadata or SHA-256 checksums are missing), `cv_version_reviews`, `/api/v1/automation/plans/{id}/artifacts/{kind}` byte streaming with SHA-256 verification, and `ApplyPackageGuard` invalidation rules.
- **Readiness & factual integrity:** Extended existing `ApplyReadinessService`, `ApplicationAnswerService`, `ApplicationAnswerRepository`, `ApplicationAnswerController`, and `DocumentFactValidator` instead of adding a parallel Q&A store or validator.
- **Network & URL safety:** Reused `AddressGuard` on the backend alongside explicit `allowLocalTestHosts` gating (`app.sandbox.allow-local-test-hosts`).
- **Evidence, audit, & tracking:** Reused `SubmissionCompletionService`, `submission_evidence` encrypted raw evidence persistence, `TrackTimelineMapper`, `ApplicationReconciler`, `TrackController` (`/api/v1/applications/{id}/track`), and `AtsCapabilityRegistry` while preserving dedicated ATS adapters (`GREENHOUSE`, `ASHBY`, `LEVER`, `ZOHO_RECRUIT`, `WORKDAY`) and `INSPECTION` intact.

---

## 2. New or Modified Components

### New Backend Components
- [`backend/src/main/java/com/personal/jobagent/automation/UniversalExecutionPhase.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/wave3-integration/backend/src/main/java/com/personal/jobagent/automation/UniversalExecutionPhase.java): Canonical execution phase enum (`INSPECTING`, `FILLING`, `UPLOADING`, `AWAITING_CANDIDATE_INPUT`, `PAUSED`, `READY_TO_CONTINUE`, `PRE_SUBMIT_ATTEMPTING`, `COMPLETED`, `FAILED`) persisted into `automation_steps.result->>'phase'`.
- [`backend/src/main/java/com/personal/jobagent/automation/UniversalBrowserPlanService.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/wave3-integration/backend/src/main/java/com/personal/jobagent/automation/UniversalBrowserPlanService.java): Validates non-ATS application URLs via `AddressGuard` (permitting `127.0.0.1`/`localhost`/`[::1]` only when `app.sandbox.allow-local-test-hosts=true`), refuses dedicated ATS domains and unsafe schemes/IPs, selects reviewed documents fail-closed via `ApplyDocumentSelector`, resolves contact alias via `RegistrationIdentityService`, builds `planType = "UNIVERSAL"` plans with `NAVIGATE` + `SCREENSHOT` + `UNIVERSAL_APPLY` (never submit steps), and rebuilds updated packages cleanly on `rebuildForResume(...)`.
- [`backend/src/main/java/com/personal/jobagent/automation/UniversalFieldMapper.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/wave3-integration/backend/src/main/java/com/personal/jobagent/automation/UniversalFieldMapper.java): Canonical `CandidateFactSnapshot`, `VerifiedAnswer` (`trustedForAutofill()` requiring `ANSWERED`, `humanConfirmed=true`, and `answerOrigin` in `CANDIDATE_CONFIRMED`/`CANDIDATE_EDITED`), `buildProfileFactsPayload`, `buildConfirmedAnswersPayload` (filtering out `MODEL_DRAFT` and protected/EEO/sponsorship questions), and `classifyControl`.

### Modified Backend Components
- `AutomationController.java` & `AutomationPlanRepository.java`: Added `/plans/{id}/discovered-questions`, `/plans/{id}/resume`, `prepareUniversalSubmission` (`UNIVERSAL_SUBMIT_CLICK`), `hasPreSubmitAttemptCheckpoint` (`409` duplicate fence), `completedSteps` hydration on claim, re-validation of `applyReadiness.evaluate` before `prepareSubmit`, and fixed `invalidateApproval` to strip queued `*_SUBMIT_CLICK` steps and withdraw `submit_approved` even after transition to `PREPARED` — plus fixed `resumeUniversalPlan` to reset `result='[]'::jsonb` safely against `V009`'s `NOT NULL` constraint.
- `WorkerEventTokenFilter.java`: Authorized worker bearer requests on `/api/v1/automation/plans/{id}/discovered-questions`.
- `SubmissionCompletionService.java`: Recognized `"UNIVERSAL_SUBMIT_CLICK"` in `isRealSubmitAttempt`.
- `ApplicationAnswerService.java`, `ApplicationAnswerRepository.java`, `ApplicationAnswerController.java`, `ApplyPackageGuard.java`, `ApplyReadinessService.java`: Fixed `findById(profileId)` lookup bug, enforced sensitive legal / demographic / `DocumentFactValidator` guards on answer confirmation and edits, scoped `findByJobAndProfileScoped` + `findReusableConfirmedAnswers` + `upsertUniversalDiscoveredQuestion` (`NEEDS_USER_INPUT` + `MODEL_DRAFT` draft state + `UNIVERSAL_LIVE_DOM`), fixed job-scoped `answerChanged` invalidation, and surfaced `UNIVERSAL_LIVE_DOM` items in readiness responses.
- `ExecutionPackageService.java` & `LeverExecutionPlanService.java`: Fixed Q&A loading to include job-scoped (`application_id IS NULL`) rows while preferring application-scoped rows without hiding same-scope contradictions.
- `ApplyController.java`, `ApplicationPipelineEventHandler.java`, `TrackTimelineMapper.java`, `AtsCapabilityRegistry.java`, `application.yml`: Wired `app.automation.universal-enabled` (`true` default, `false` in `ApplicationPipelineIT` to preserve legacy `INSPECTION` test behaviour + optional `?planType=INSPECTION`), and added an honest `UNIVERSAL` capability entry (`FORM_FILL_AVAILABLE` with `PARTIAL` submission/confirmation so ATS capability claims remain conservative).

### New & Modified Worker Components
- [`worker/src/universal_form.js`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/wave3-integration/worker/src/universal_form.js): Origin/SSRF navigation guard (`universalPageIdentityMatches`, `installUniversalNavigationGuard`), passive semantic live-DOM + same-origin iframe inspection tagging `data-robin-control-id` (`inspectLiveFormDom`), honeypot/EEO/password exclusions, deterministic fact & confirmed-answer planning (`planUniversalControls`), and Playwright interaction + SHA-256 verified file uploads (`applyUniversalControlActions`).
- [`worker/src/universal_confirmation.js`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/wave3-integration/worker/src/universal_confirmation.js): Independently verifies real submit execution (`executeAndVerifyUniversalSubmit`, `classifyUniversalSubmitOutcome`) requiring matching non-analytics `POST`/`PUT`/`PATCH` 2xx response + DOM confirmation signal + form disappearance, rejecting HTTP `422`, anti-bot/CAPTCHA/MFA walls, and fake DOM banners with no submission request, emitting `robin-submission-evidence-v1`.
- `worker/src/plan.js`, `worker/src/browser_worker.js`, `worker/src/outcome.js`, `worker/src/worker.js`: Added `UNIVERSAL_APPLY` / `UNIVERSAL_SUBMIT_CLICK`, `#validateUniversalPlan`, `#validateUniversalArtifacts`, `#runUniversalApply`, synchronous `PRE_SUBMIT_ATTEMPTING` fencing in `StateStore` + `/steps`, `replayUniversalPause` loop handling for safe resume without re-filling completed pages, and `/discovered-questions` reporting in `executePollCycle`.

### Frontend Type & Display Alignment
- `frontend/src/types.ts` & `frontend/src/pages/ApplicationsPage.tsx`: Added `"UNIVERSAL"` to `SubmissionReconciliationResult.planType`, `UniversalOutcome`, and `isSubmissionCapablePlan`.

---

## 3. Execution Lifecycle & State Transitions

1. **Plan Preparation (`PREPARED`):** Candidate triggers `/api/v1/apply/applications/{id}/package`. When `universal-enabled=true` and URL is a safe non-ATS URL, `UniversalBrowserPlanService` creates a `UNIVERSAL` plan containing `[NAVIGATE, SCREENSHOT, UNIVERSAL_APPLY]` and NO submit step (`submit_approved = false`).
2. **Pass 1 Execution (`RUNNING` -> `AWAITING_APPROVAL`):** Worker claims plan via `/plans/claim-next`, validates plan and SHA-256 document artifacts, runs navigation guard + `inspectLiveFormDom`, reports step checkpoints (`INSPECTING`, `FILLING`, `UPLOADING`), uploads tailored CV, fills verified profile facts & confirmed answers across steps, and detects any unknown mandatory field (e.g. Page 2 mandatory input).
3. **Exception Escalation (`AWAITING_CANDIDATE_INPUT` / `PAUSED`):** Worker posts unknown mandatory field metadata via `POST /api/v1/automation/plans/{id}/discovered-questions` -> backend persists `NEEDS_USER_INPUT` rows into `application_answers`, and worker completes Pass 1 as `HUMAN_REQUIRED`. Plan moves to `AWAITING_APPROVAL`. Readiness (`GET /api/v1/apply/applications/{id}/readiness`) reports `packageReady=false`, blocking both `/resume` and `/approve-submit` with `409 Conflict` (`APPLY_NOT_READY`).
4. **Candidate Resolution & Safe Resume (`PREPARED`):** Candidate updates and confirms the discovered answer (`PUT /api/v1/application-answers/{id}` -> fact-checked by `DocumentFactValidator`), then calls `POST /api/v1/automation/plans/{id}/resume`. Backend rebuilds confirmed answers & document hashes, resets `status = 'PREPARED'` and `result = '[]'::jsonb`, records `phase-ready-to-continue` (`READY_TO_CONTINUE`), and hydrates `completedSteps` on next claim so the worker does not unnecessarily re-fill completed pages.
5. **Two-Step Explicit Submission Gate (`READY_TO_SUBMIT` -> `PREPARED` -> `PRE_SUBMIT_ATTEMPTING` -> `SUBMITTED`):** Once all blockers clear and Pass 2 reaches review (`AWAITING_APPROVAL`), candidate explicitly clicks approve (`POST /plans/{id}/approve-submit` -> `READY_TO_SUBMIT`) and queue submit (`POST /plans/{id}/prepare-submit` -> appends single `UNIVERSAL_SUBMIT_CLICK`, returns to `PREPARED`). Before clicking submit, the worker persists synchronous `PRE_SUBMIT_ATTEMPTING` locally and via `POST /plans/{id}/steps`. Confirmed local fixture submission transitions atomically inside `SubmissionCompletionService` to `SUBMITTED` + encrypted `submission_evidence` + `APPLICATION_SUBMITTED`.

---

## 4. Candidate Fact & Answer Provenance

- **Zero Fabrication & `MODEL_DRAFT` Isolation:** `UniversalFieldMapper.VerifiedAnswer#trustedForAutofill()` strictly requires `status = "ANSWERED"`, `humanConfirmed = true`, and `answerOrigin` in `{"CANDIDATE_CONFIRMED", "CANDIDATE_EDITED"}`. `MODEL_DRAFT` rows created during `/discovered-questions` (`NEEDS_USER_INPUT`) can never be injected into autofill payloads until the candidate explicitly edits/confirms them.
- **Fact Validation on Edits:** `ApplicationAnswerController#updateAnswer` runs `DocumentFactValidator` checks before accepting confirmed edits and rejects fabricated historical claims (e.g., an invented university/PhD/year) with `400 Bad Request`.
- **Sensitive & Protected Attributes Refused:** Protected demographic/EEO questions and work authorization/sponsorship assumptions remain excluded from automatic inference in both Java (`ApplicationAnswerService`, `UniversalFieldMapper`) and JavaScript (`universal_form.js`).
- **Scoped vs. Reusable Precedence:** `ApplicationAnswerRepository.findByJobAndProfileScoped` and `ExecutionPackageService` / `LeverExecutionPlanService` resolve application-specific answers ahead of job-scoped (`application_id IS NULL`) answers without overwriting same-scope contradictions, while `findReusableConfirmedAnswers` excludes non-reusable `WHY_COMPANY` / `WHY_ROLE` questions.

---

## 5. Pause/Resume & Recovery Design

- **Backend Step Hydration:** `/api/v1/automation/plans/{id}/steps` persists checkpoints per `(plan_id, step_key)` in `automation_steps`, and `/plans/claim-next` returns `completedSteps` alongside `plan` so restarted or resumed workers observe prior progress durably.
- **Main Loop Replay on Resume:** `worker/src/browser_worker.js` and `worker/src/worker.js` mark and skip completed steps cleanly (`replayUniversalPause`) while allowing the paused `UNIVERSAL_APPLY` step to re-run from the updated `package` after `/resume` so newly confirmed Page 2+ answers fill cleanly without re-executing earlier completed actions unnecessarily.
- **Package Drift Invalidation After `prepare-submit`:** Updating answers (`ApplyPackageGuard`) or revoking reviewed CV approval (`cv/review`) while a plan is `READY_TO_SUBMIT` or already returned to `PREPARED` with `submit_approved=true` strips any `*_SUBMIT_CLICK` steps from JSONB and returns the plan to `AWAITING_APPROVAL` with `submit_approved=false`.

---

## 6. Submission Idempotency & Crash Protection

- **Synchronous Pre-Click Fence (`PRE_SUBMIT_ATTEMPTING`):** Before clicking the submit control in `UNIVERSAL_SUBMIT_CLICK`, `browser_worker.js` writes a synchronous local `StateStore` fence (`pre-submit-attempting`) and posts `phase=PRE_SUBMIT_ATTEMPTING` to `/plans/{id}/steps`.
- **Duplicate Fence Enforcement (`409`):** `AutomationController` and `AutomationPlanRepository.hasPreSubmitAttemptCheckpoint` reject any second `PRE_SUBMIT_ATTEMPTING` checkpoint, `/resume` call, or `prepareUniversalSubmission` with `409 Conflict`.
- **Crash Quarantine (`RECONCILIATION_REQUIRED`):** On worker restart in `universal_engine.test.js` or stale heartbeat recovery via `/plans/recover-stale` in `UniversalBrowserE2EIT`, any submit-capable or `PRE_SUBMIT_ATTEMPTING` plan is quarantined to `RECONCILIATION_REQUIRED` and never auto-retried.
- **Database Single-Submit Guard:** Preserves `V043` (`automation_plans_one_submission_per_application`) unchanged.

---

## 7. Evidence & Tracking Behaviour

- **Independent Signal Verification:** `universal_confirmation.js` refuses to equate clicking a button with submission. Confirmed outcome requires both a non-analytics `POST`/`PUT`/`PATCH` 2xx response and post-submit DOM confirmation with form disappearance (`robin-submission-evidence-v1`).
- **Encrypted Evidence Persistence:** `SubmissionCompletionService` stores and encrypts raw evidence into `submission_evidence` (`STORED_ENCRYPTED`).
- **Truthful V069 Sandbox Boundary:** Ordinary non-sandbox career websites (including the local Universal fixture) do **not** write into V069 `submission_receipts` (`receiptCount = 0`). As verified against PostgreSQL 16 via `GET /api/v1/applications/{id}/track`, `TrackController` / `ApplicationReconciler` honestly show `status = "APPLIED"`, `recordedStatus = "APPLICATION_SUBMITTED"`, `robinSubmission.submitted = true`, and `submission.state = "UNVERIFIED"` / `basis = "UNVERIFIED"` instead of forging a sandbox verified receipt.

---

## 8. Security Boundaries

- **SSRF & Redirect Prevention:** Backend `UniversalBrowserPlanService` uses `AddressGuard` (refusing metadata `169.254.169.254`, private networks like `10.0.0.1`, non-HTTP schemes, userinfo) and only permits loopback hosts (`localhost`/`127.0.0.1`/`[::1]`) when `app.sandbox.allow-local-test-hosts=true`. Worker `installUniversalNavigationGuard` & `universalPageIdentityMatches` abort cross-origin redirects, private IPs, and disallowed hosts before any browser fill or submit click.
- **Anti-Bot & MFA Hard Stops:** Visible or mid-flow CAPTCHA challenges (`ANTI_BOT`), login/MFA/OTP gates (`LOGIN_WALL`), `422` validation errors, and fake confirmation banners with no HTTP submission request all fail closed or pause safely without submission confirmation.
- **Cross-Tenant Isolation & Auth Split:** Worker bearer tokens cannot call candidate `/resume` or `/approve-submit` (`403`), candidate sessions cannot call `/discovered-questions` or `/complete` (`403`), and Candidate B receives `404 Not Found` on Candidate A plans/answers.
- **Artifact Checksum Binding:** Reviewed CV downloads verify `X-Artifact-Sha256` and exact `(planId, jobId, applicationId, versionId)` binding; tampered or corrupted artifacts abort before Playwright `setInputFiles`.
- **Database Schema Freeze:** `git diff --name-only origin/integration/wave-3 -- backend/src/main/resources/db/migration/` is empty — `V001`–`V070` remain untouched.

---

## 9. Exact Modified-File Inventory

### Modified Files (25 files)
- `backend/src/main/java/com/personal/jobagent/application/ApplicationPipelineEventHandler.java`
- `backend/src/main/java/com/personal/jobagent/apply/ApplyController.java`
- `backend/src/main/java/com/personal/jobagent/apply/ApplyPackageGuard.java`
- `backend/src/main/java/com/personal/jobagent/apply/ApplyReadinessService.java`
- `backend/src/main/java/com/personal/jobagent/automation/AtsCapabilityRegistry.java`
- `backend/src/main/java/com/personal/jobagent/automation/AutomationController.java`
- `backend/src/main/java/com/personal/jobagent/automation/AutomationPlanRepository.java`
- `backend/src/main/java/com/personal/jobagent/automation/ExecutionPackageService.java`
- `backend/src/main/java/com/personal/jobagent/automation/LeverExecutionPlanService.java`
- `backend/src/main/java/com/personal/jobagent/automation/SubmissionCompletionService.java`
- `backend/src/main/java/com/personal/jobagent/qa/ApplicationAnswerController.java`
- `backend/src/main/java/com/personal/jobagent/qa/ApplicationAnswerRepository.java`
- `backend/src/main/java/com/personal/jobagent/qa/ApplicationAnswerService.java`
- `backend/src/main/java/com/personal/jobagent/security/WorkerEventTokenFilter.java`
- `backend/src/main/java/com/personal/jobagent/track/TrackTimelineMapper.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/personal/jobagent/application/ApplicationPipelineIT.java`
- `backend/src/test/java/com/personal/jobagent/automation/AtsCapabilityRegistryTest.java`
- `backend/src/test/java/com/personal/jobagent/qa/ApplicationAnswerServiceTest.java`
- `frontend/src/pages/ApplicationsPage.tsx`
- `frontend/src/types.ts`
- `worker/src/browser_worker.js`
- `worker/src/outcome.js`
- `worker/src/plan.js`
- `worker/src/worker.js`

### Added Files (14 files, including Stage 3A audit and Stage 3B report)
- `backend/src/main/java/com/personal/jobagent/automation/UniversalBrowserPlanService.java`
- `backend/src/main/java/com/personal/jobagent/automation/UniversalExecutionPhase.java`
- `backend/src/main/java/com/personal/jobagent/automation/UniversalFieldMapper.java`
- `backend/src/test/java/com/personal/jobagent/automation/UniversalAutomationControllerTest.java`
- `backend/src/test/java/com/personal/jobagent/automation/UniversalBrowserE2EIT.java`
- `backend/src/test/java/com/personal/jobagent/automation/UniversalBrowserPlanServiceTest.java`
- `backend/src/test/java/com/personal/jobagent/automation/UniversalFieldMapperTest.java`
- `backend/src/test/java/com/personal/jobagent/qa/ApplicationAnswerControllerFactTest.java`
- `docs/STAGE_3A_UNIVERSAL_BROWSER_ENGINE_AUDIT.md`
- `docs/STAGE_3B_UNIVERSAL_BROWSER_ENGINE_IMPLEMENTATION.md`
- `worker/src/universal_confirmation.js`
- `worker/src/universal_form.js`
- `worker/test/fixtures/universal_employer_fixture.js`
- `worker/test/universal_engine.test.js`

---

## 10. Tests Run & Actual Observed Results

All checks below ran after the final code edit with zero failures and zero newly added skips:

1. **Backend Full Build & Unit + Integration Suite (`mvn verify` in `backend/` with Docker Engine + Testcontainers PostgreSQL 16):**
   - **Surefire (Unit & MockMvc tests):** `Tests run: 1957, Failures: 0, Errors: 0, Skipped: 6` (the 6 pre-existing skipped tests are environment-gated Redis tests: `RedisConnectivityDiagnosticsTest` [2], `RedisLettuceAbExperimentTest` [2], `RedisTlsBindingTest` [2]).
     - Included Stage 3B targeted unit suites: `UniversalFieldMapperTest` (`3/3`), `UniversalBrowserPlanServiceTest` (`2/2`), `UniversalAutomationControllerTest` (`1/1`), `ApplicationAnswerControllerFactTest` (`1/1`), `ApplicationAnswerServiceTest` (`3/3`), `AtsCapabilityRegistryTest` (`12/12`), `AutomationControllerReviewTest` (`68/68`), `UserDataIsolationTest` (`17/17`), `ApplicationPipelineEventHandlerTest` (`20/20`).
     - Included ATS regression unit tests: `GreenhouseExecutionPlanServiceTest` (`5/5`), `AshbyExecutionPlanServiceTest` (`15/15`), `LeverExecutionPlanServiceTest` (`7/7`), `WorkdayExecutionPlanServiceTest` (`24/24`), `ZohoRecruitExecutionPlanServiceTest` (`16/16`).
   - **Failsafe (PostgreSQL 16 + Flyway `V001`–`V070` ITs):** `Tests run: 434, Failures: 0, Errors: 0, Skipped: 7` (`BUILD SUCCESS`; the 7 pre-existing skipped ITs are env-gated external DB / live board tests: `ExternalDatabaseActualReviewIT`, `ExternalDatabaseCompatibilityIT`, `ExternalDatabaseFlywayIT`, `AdzunaLiveIngestionIT`, `ArbeitnowLiveIngestionIT`, `FreeHireLiveIngestionIT`, `PlatformLiveIT`).
     - Included Stage 3B E2E IT: `UniversalBrowserE2EIT` (`2/2` passed, `0` failed, `0` skipped, `9.408 s`).
     - Included ATS & receipt/tracking regression ITs: `GreenhouseSandboxE2EIT` (`8/8`), `SandboxRegistrationIT` (`5/5`), `ApplicationReceiptIT` (`21/21`), `ApplicationTrackingIT` (`19/19`), `ApplicationPipelineIT` (`9/9`), `ApplyWorkflowIT` (`11/11`), `AshbyExecutionPlanServiceIT` (`7/7`), `WorkdayExecutionPlanServiceIT` (`6/6`), `ZohoRecruitExecutionPlanServiceIT` (`5/5`), `UserDataIsolationIT` (`21/21`).
2. **Worker Playwright & Execution Suite (`npm test` in `worker/`):**
   - `325 passed, 0 failed, 0 cancelled, 0 skipped` across `3` suites (`53579.4024 ms`), including `5` new Chromium + HTTP poll-cycle Universal tests in `worker/test/universal_engine.test.js` plus all Greenhouse, Ashby, Lever, Workday, Zoho Recruit, and account lifecycle regression suites.
3. **Frontend Type Check, Tests & Production Bundle (`npx tsc -b && npm test && npm run build` in `frontend/`):**
   - `Test Files: 48 passed (48)`, `Tests: 474 passed (474)`; `tsc -b && vite build` succeeded cleanly in `2.93s`.
4. **Cloudflare Email Worker Suite (`npm test` in `cloudflare-email-worker/`):**
   - `17 passed, 0 failed, 0 skipped` across `3` suites.

---

## 11. Remaining Limitations

- **Proven on a Controlled Local Multi-Step Fixture Only:** End-to-end Universal browser execution has only been executed against `worker/test/fixtures/universal_employer_fixture.js` (HTTP loopback server), **not** against live third-party employer career websites. Arbitrary real-world sites with bespoke custom shadow-DOM widgets, non-standard multi-file dropzones, dynamic bot-protection interstitials, or proprietary iframe sandboxes are not claimed as production-ready.
- **No Standing Auto-Submit:** `UNIVERSAL` execution preserves Robin's mandatory two-step human confirmation (`approve-submit` + `prepare-submit`) per application.
- **No External Employer Account Signup Automation Integrated Yet Inside `UNIVERSAL`:** Encountering an unexpected login/MFA wall triggers a safe hard stop/pause (`LOGIN_WALL`) rather than autonomous external credential registration or bypassing MFA.
- **Cross-Origin Third-Party Embed Iframes Excluded:** DOM inspection operates across the main document and same-origin iframes only; cross-origin embedded application iframes remain out of scope under SSRF/origin isolation boundaries.

---

## 12. Explicit Evidence Classification

| Evidence Tier | What Is Proven | Source of Truth |
| --- | --- | --- |
| **1. Unit Tests** | Field classification, SSRF URL rules (`allowLocalTestHosts` vs `AddressGuard`), `MODEL_DRAFT`/EEO/sponsorship refusal, `DocumentFactValidator` answer confirmation gate, capability honesty, duplicate `PRE_SUBMIT_ATTEMPTING` `409` fence. | Surefire (`1957` run, `0` fail, `6` env-skipped) + Worker unit checks inside `325/325` |
| **2. Local Browser Fixture (Playwright Chromium)** | Multi-step semantic inspection (`Page 1` -> `Page 2`), identity fill, SHA-256 CV upload, confirmed answer reuse, mandatory gap pause & resume without re-filling Page 1, crash-after-`PRE_SUBMIT_ATTEMPTING` non-retry, CAPTCHA/MFA/SSRF/redirect/`422`/fake-banner rejection, full `executePollCycle` HTTP round-trip. | `worker/test/universal_engine.test.js` + `worker/test/fixtures/universal_employer_fixture.js` (`5/5` tests inside `325/325` green run) |
| **3. Real Database Integration Tests (PostgreSQL 16)** | Full API -> PostgreSQL `V001`–`V070` -> `application_answers` -> `/resume` -> `approve-submit` -> `prepare-submit` -> post-prepare drift revocation -> `PRE_SUBMIT_ATTEMPTING` -> encrypted `submission_evidence` -> `APPLICATION_SUBMITTED` -> `/track` (`APPLIED` + `UNVERIFIED` keeping `submission_receipts = 0`), plus stale crash `RECONCILIATION_REQUIRED` and cross-user `404`. | Failsafe `UniversalBrowserE2EIT` (`2/2`) inside full `434`-test PostgreSQL 16 IT run (`0` fail, `7` env-skipped) |
| **4. Real Employer Submissions** | **Explicitly Out of Scope / Not Executed.** Zero requests made to real external employer ATS/career portals; production readiness across arbitrary live websites is **not** claimed. | `N/A (0 real submissions)` |
