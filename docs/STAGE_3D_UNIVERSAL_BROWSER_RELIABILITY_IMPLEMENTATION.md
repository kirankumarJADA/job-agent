# Stage 3D: Universal Browser Reliability & Exception Recovery — Implementation Report

**Repository:** `kirankumarJADA/job-agent`  
**Branch:** `integration/wave-3`  
**Starting Baseline Commit:** `937782293e6057629cb8cc52108e9f8f3f790060`  
**Master Baseline:** `a45888d63bd2a893c7373ed44a6d17b930682a36`  
**PR #21 State:** `OPEN` / `DRAFT` (unchanged; never merged, deployed, or marked ready)

---

## 1. Executive Summary & Verdict

Stage 3D closes all six verified Stage 3C reliability, form-diversity, login/handoff recovery, candidate-workflow, and registry-consistency gaps across Robin's Universal Browser Engine (`worker/`, `backend/`, and `frontend/`) while preserving every SSRF boundary, `V001`–`V070` schema invariant, explicit two-step submit approval gate, and dedicated ATS adapter (`GREENHOUSE`, `ASHBY`, `LEVER`, `ZOHO_RECRUIT`, `WORKDAY`).

Notably, real Playwright Chromium testing caught a real network-layer redirect bypass where Chromium could attempt TCP connections on HTTP `302` subresource redirects without re-invoking Playwright's default `route.fallback()` handler; switching `installUniversalNavigationGuard` to `route.fetch({ maxRedirects: 0 })` closes redirect-hop SSRF to internal/metadata endpoints (`169.254.169.254`, `10.0.0.1`, loopback) before any packet is sent.

**Verdict:** `STAGE 3D COMPLETE — UNIVERSAL RELIABILITY GAPS CLOSED IN CONTROLLED TESTS`

---

## 2. Workstream A — Secure Cross-Origin Resource Handling & Redirect Revalidation

- **Files modified:** `worker/src/universal_form.js`, `worker/src/browser_worker.js`, `worker/src/outcome.js`
- **Root cause fixed:** `installUniversalNavigationGuard` previously aborted all cross-origin subresources (`script`, `stylesheet`, `font`, `image`, `fetch`/`xhr`) whenever `allowLocalTestHosts === false`.
- **Implementation:**
  - Added `evaluateUniversalResourceRequest` enforcing explicit resource-origin policy:
    - Top-level `document` navigations remain strictly locked to same-origin HTTPS (or explicit loopback HTTP only when `allowLocalTestHosts === true`) via `universalPageIdentityMatches`; `data:`/`blob:` document navigations fail closed.
    - Non-document subresources allow public `https:` / `wss:` origins (and loopback `http:`/`ws:` strictly when `allowLocalTestHosts && isExplicitLoopbackHost`) while rejecting private/link-local/metadata/RFC1918/special IPs, `.local`/`.internal` hosts, userinfo URLs, and insecure external schemes.
  - Replaced passive `route.fallback()` on HTTP/HTTPS requests with hop-by-hop `route.fetch({ maxRedirects: 0 })` validation (up to 5 hops), aborting any `3xx` `Location` resolving to a disallowed host or insecure scheme before Chromium connects.
  - Added `sanitizeUrlForDiagnostic` (strips query parameters, fragments, and userinfo) and exposed redacted `blockedResources` through `installUniversalNavigationGuard(...).getDiagnostics()`, `browser_worker.js`, and `UNIVERSAL_OUTCOME`.

---

## 3. Workstream B — Safe Custom File-Upload Discovery & Verification

- **Files modified:** `worker/src/universal_form.js`, `worker/src/browser_worker.js`
- **Root cause fixed:** `inspectLiveFormDom` previously discarded every visually hidden `<input type="file">`, breaking real CSS-hidden / dropzone upload widgets.
- **Implementation:**
  - Replaced blanket hiding for file inputs with `hasVisibleFileInteractionPath`: discovers visually hidden `<input type="file">` controls **only** when backed by a visible `<label for>`, enclosing visible `<label>`, or enclosing visible dropzone/upload wrapper with a visible interactive trigger button/link, deriving labels cleanly from the trigger/dropzone text.
  - Added `hasTrapMarkers` so genuine anti-bot honeypots (`aria-hidden="true"`, `[data-honeypot="true"]`, trap `name`/`id`/`class`, and offscreen decoys with no visible interaction path) stay ignored and untouched.
  - Added `isArtifactCompatibleWithAccept` checking artifact MIME type and extension against DOM `accept` attributes (`planUniversalControls` + `applyUniversalControlActions`), failing closed with `REJECTED` + structured `requiredGaps` instead of attempting invalid uploads.
  - Preserved independent CV vs. Cover Letter control mapping and SHA-256 checksum / sandbox-path validation, and added live DOM post-upload verification checking `el.files.length > 0` and exact attached basename.

---

## 4. Workstream C — Dynamic & Modern Form Controls

- **Files modified:** `worker/src/universal_form.js`, `worker/src/browser_worker.js`, `worker/src/outcome.js`
- **Implemented capabilities:**
  - **Open Shadow DOM traversal:** `inspectLiveFormDom` recursively walks open `el.shadowRoot` trees using root-local `resolveLabel(rootNode, el)`. Custom web components without accessible `.shadowRoot` are recorded in `closedShadowHosts` and surfaced in `unsupported` / `UNIVERSAL_OUTCOME` rather than silently ignored.
  - **Dynamic portal `[role="combobox"]`:** Added `hydrateDynamicComboboxOptions` (opens comboboxes missing initial options, inspects `aria-controls`/`aria-owns`/visible `[role="listbox"]` `[role="option"]` nodes, closes cleanly via `Escape`) and exact case-insensitive selection inside `applyUniversalControlActions` (closing popup cleanly when no exact match exists).
  - **Same-page conditional reveal loop:** `#runUniversalApply` executes bounded settle-and-reinspect passes (`maxRevealPassesPerPage = 4`) on each wizard page so newly revealed conditional fields/required questions are detected and filled or surfaced before advancing.
  - **Pre-form "Apply Now" disambiguation:** `inspectLiveFormDom` classifies landing triggers (`Apply`, `Apply Now`, `Apply for this position`, `Start Application`) as `applyRevealButton` rather than `submitButton`. `#runUniversalApply` clicks `applyRevealButton` when zero form inputs exist yet, while final submission remains gated to explicit `UNIVERSAL_SUBMIT_CLICK` steps.
  - **Inline validation guard:** `#runUniversalApply` halts before clicking `Continue`/`Next` whenever visible `[role="alert"]`, `.error`, `.field-error`, `.invalid-feedback`, or `[aria-invalid="true"]` errors appear, surfacing `validationErrors` to candidate review.

---

## 5. Workstream D — Generic Login Walls & Candidate Handoff Resume Lifecycle

- **Files modified:** `worker/src/handoff.js`, `worker/src/browser_worker.js`, `backend/.../HandoffBlockReason.java`, `backend/.../AutomationPlanRepository.java`, `backend/.../AtsHandoffService.java`, `backend/.../HandoffController.java`
- **Implemented capabilities:**
  - **Saved-credential authentication (`#runUniversalApply`):** When `securitySignals.loginWallPresent` is observed after CAPTCHA/MFA priority checks:
    - Refuses guessed selectors unless `inspectLiveFormDom` verifies unambiguous visible username/email, `input[type="password"]`, and submit button (`UNIVERSAL_LOGIN_SELECTOR_UNVERIFIED`).
    - Resolves credentials strictly in-memory via `credentialResolver` when `step.params.hasSavedCredential === true`; never logs or persists passwords in `StateStore`, events, screenshots, or plan outcomes; re-checks post-login page for CAPTCHA/MFA/failed login (`UNIVERSAL_LOGIN_FAILED`).
  - **Handoff classification fix (`worker/src/handoff.js`, `HandoffBlockReason.java`):**
    - `normalizeHandoffReason` maps generic/`UNIVERSAL` MFA to `'MFA'` (never `'WORKDAY_MFA_REQUIRED'`), unresolved login challenges (`UNIVERSAL_LOGIN_REQUIRED`, `UNIVERSAL_LOGIN_FAILED`, `UNIVERSAL_LOGIN_SELECTOR_UNVERIFIED`) to `'EMPLOYER_SECURITY_CHECK'`, and `UNIVERSAL` CAPTCHA to `'CAPTCHA'`.
    - `HandoffBlockReason.parse` maps login/credential/sign-in tokens to `EMPLOYER_SECURITY_CHECK`.
  - **Same-plan pre-submit resume after handoff intervention:** Added `POST /api/v1/handoff/{sessionId}/resume` -> `AtsHandoffService#resumeAfterHandoff` -> `AutomationPlanRepository#resumeFromHandoff`:
    - Owner-scoped, rejects worker tokens (`401` at `WorkerEventTokenFilter` + `403` at `HandoffController`), refuses expired or non-`PENDING` sessions (`409`), refuses any plan with `submit_approved = true`, `PRE_SUBMIT_ATTEMPTING` checkpoint, or existing `*_SUBMIT_CLICK` / `MOCK_SUBMIT` step (`409`).
    - Completes the active handoff session atomically without falsely transitioning the application to `APPLICATION_SUBMITTED`, rebuilds `UNIVERSAL` plan steps/credentials (`hasSavedCredential`, `confirmedAnswers`), returns plan `HANDOFF_REQUIRED -> PREPARED`, and records `ATS_HANDOFF_RESUMED` audit log.

---

## 6. Workstream E — Candidate Exception, Answer-Editing & Credential Workflows (Frontend)

- **Files modified / added:**
  - `frontend/src/api/client.ts` (`resumeHandoff`, `fetchEmployerCredentials`, `saveEmployerCredential`, `deleteEmployerCredential`)
  - `frontend/src/types.ts` (`ApplyQuestionItem.answerText`, `UniversalOutcome` diagnostics, `EmployerCredentialSummary`)
  - `frontend/src/components/ApplyWorkspace.tsx` + `backend/.../ApplyReadinessService.java` (returns `answerText`; renders inline answer input/select editing on `NEEDS_USER_INPUT` / `UNCONFIRMED` / `CONFIRMED` answers; surfaces `DocumentFactValidator` 400 errors cleanly while preserving backward compatibility for unedited draft confirmations)
  - `frontend/src/components/UniversalReviewPanel.tsx` (NEW: displays Universal page progress, `loginSucceeded`, `filled` controls, CV/cover-letter upload states, `requiredGaps`, `validationErrors`, `closedShadowHosts`, redacted `blockedResources`, `"Resume & retry form pass"`, `"Resume automation after verification"`, approval/queue controls with in-flight disabled guards against duplicate clicks, and reconciliation handling)
  - `frontend/src/components/HandoffModal.tsx` (adds `"Resume Automation"` action alongside explicit external-submission attestation)
  - `frontend/src/pages/ApplicationsPage.tsx` (wires `UniversalReviewPanel` cleanly for `planType === 'UNIVERSAL'`)
  - `frontend/src/pages/ProfilePage.tsx` (adds encrypted Employer Site Credentials section using `/employer-credentials` GET/POST/DELETE; clears password input immediately after save and never renders saved passwords)

---

## 7. Workstream F — Capability Registry & URL Matching Consistency

- **Files modified:** `backend/src/main/java/com/personal/jobagent/automation/AtsCapabilityRegistry.java`, `backend/src/test/java/com/personal/jobagent/automation/AtsCapabilityRegistryTest.java`, `backend/src/test/java/com/personal/jobagent/automation/AtsCapabilityControllerTest.java`
- **Changes:**
  - Reconciled `WORKDAY` declaration so `signInReadiness = FULL` (live-verified up to sign-in) and `accountCreationReadiness = PARTIAL` coexist transparently with Phase 19 fixture-verified multi-step apply & approval-gated `WORKDAY_SUBMIT_CLICK` (`PARTIAL` for `formInspection`, `requiredFieldValidation`, `documentAttachment`, `formFilling`, `finalSubmission`, `submissionConfirmation`; `NONE` for `questionAnswering`).
  - Preserved invariant that only `GREENHOUSE` and `ASHBY` hold `finalSubmission = FULL` and `NO` platform claims `SUBMISSION_VERIFIED`.
  - Replaced substring `byApplicationUrl` matching with strict DNS host / subdomain matching (`URI.getHost()`), rejecting path/prefix spoofs (`https://evil.example/myworkdayjobs.com/...`).

---

## 8. Schema & Safety Verification

- **Database migrations:** Zero modifications to `V001`–`V070`.
- **Fail-closed guarantees preserved:** No CAPTCHA/MFA bypass, no plaintext credential logging/persistence, no guessing of ambiguous selectors/answers, no auto-submit outside explicit human approval + `PRE_SUBMIT_ATTEMPTING` single-attempt fence, and V069 sandbox receipts remain strictly separated from ordinary `submission_evidence`.

---

## 9. Deterministic Test Verification Matrix (Observed Post-Edit Runs)

| Suite | Scope Verified | Exact Observed Result |
| --- | --- | --- |
| `worker` (`npm test`) | All Playwright & Node suites including `universal_reliability.test.js` (CDN assets, 302 metadata redirect block, hidden uploads, honeypot refusal, CV vs CL, `accept` mismatch, open/closed Shadow DOM, portal combobox, conditional reveals, `Apply Now`, inline validation, saved-credential login, selector refusal, generic MFA/login handoff), `universal_engine.test.js`, `handoff.test.js`, plus ATS suites | **329 passed, 0 failed, 0 skipped** (`duration_ms 48787.58`) |
| `frontend` (`npx tsc -b && npm test && npm run build`) | `UniversalReviewPanel.test.tsx`, `ApplyWorkspace.test.tsx`, `HandoffModal.test.tsx`, `ApplicationsPage.lifecycle.test.tsx`, `ProfilePage.test.tsx`, plus all full frontend specs & production bundle | **49 files / 482 tests passed, 0 failed, 0 skipped**; `tsc -b` & `vite build` exit `0` |
| `cloudflare-email-worker` (`npm test`) | Webhook signature, idempotency, MIME parser | **17 passed, 0 failed, 0 skipped** |
| `backend` (`mvn verify` Surefire) | Unit & architecture suites (`AtsCapabilityRegistryTest`, `AtsCapabilityControllerTest`, `HandoffControllerTest`, `ApplicationAnswerControllerFactTest`, `UniversalBrowserPlanServiceTest`, `ArchModuleBoundaryTest`, etc.) | **1958 run, 0 failures, 0 errors, 6 skipped** *(pre-existing optional Redis diagnostics/AB tests: `RedisConnectivityDiagnosticsTest` [2], `RedisLettuceAbExperimentTest` [2], `RedisTlsBindingTest` [2])* |
| `backend` (`mvn verify` Failsafe IT + Testcontainers PG 16) | Full IT suite (`UniversalBrowserE2EIT` [3/3], `AtsHandoffIntegrationIT`, `GreenhouseSandboxE2EIT`, `SandboxRegistrationIT`, security/isolation ITs) | **435 run, 0 failures, 0 errors, 7 skipped** *(pre-existing external-DB/live-ingestion ITs: `ExternalDatabaseActualReviewIT`, `ExternalDatabaseCompatibilityIT`, `ExternalDatabaseFlywayIT`, `AdzunaLiveIngestionIT`, `ArbeitnowLiveIngestionIT`, `FreeHireLiveIngestionIT`, `PlatformLiveIT`)* |

---

## 10. Known / Intentional Operational Boundaries

1. **Closed Shadow DOM:** Cannot be automated from page JavaScript; closed hosts are explicitly reported in `closedShadowHosts` and surfaced as unsupported items in `UniversalReviewPanel`.
2. **External Browser Session Cookies on Interactive Handoff:** When a candidate manually resolves a CAPTCHA or interactive challenge in their own external browser rather than saving portal credentials for Robin to log in with, external browser cookies do not transfer back into Robin's isolated headless Chromium context; candidates can either complete and attest externally via `HandoffModal`, or update credentials/answers and re-queue via `Resume Automation`.
3. **Real Employer Sites:** All Stage 3D verifications executed strictly against controlled local Playwright/HTTP fixtures and PostgreSQL Testcontainers with zero real employer submissions or live Workday tenant sign-ins.
