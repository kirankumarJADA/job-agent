# ROBIN — STAGE 3C: REAL-WORLD UNIVERSAL BROWSER VALIDATION — READ-ONLY READINESS AUDIT

**Final Verdict:** `READY FOR STAGE 3D — CONTROLLED REAL-WORLD VALIDATION PLAN ESTABLISHED`

**Repository:** `kirankumarJADA/job-agent`  
**Branch:** `integration/wave-3`  
**HEAD / Remote Tracking SHA:** `937782293e6057629cb8cc52108e9f8f3f790060` (`origin/master = a45888d63bd2a893c7373ed44a6d17b930682a36`)  
**PR #21:** [`https://github.com/kirankumarJADA/job-agent/pull/21`](https://github.com/kirankumarJADA/job-agent/pull/21) — **OPEN** and **DRAFT**  
**Audit Mode:** Strictly read-only (zero source-code, test-code, or `V001`–`V070` migration modifications; zero real employer interactions).

---

## 1. Stage 3B CI Release Gate Evidence

Verified prior to starting the Stage 3C audit:

- **GitHub Actions Run:** [`45806127601`](https://github.com/kirankumarJADA/job-agent/actions/runs/45806127601)
- **Head SHA:** `937782293e6057629cb8cc52108e9f8f3f790060`
- **Overall Status / Conclusion:** `completed` / **`success`**
- **All 4 CI Jobs Passed:**

| Job Name | Job ID | Window (UTC) | Status / Conclusion | Verified Steps |
| --- | --- | --- | --- | --- |
| `frontend` | `139861746555` | `10:57:39Z` – `10:58:53Z` | `completed` / `success` | Checkout, Node 22, Install dependencies, Type-check, Unit tests, Production build |
| `cloudflare-email-worker` | `139861746995` | `10:57:40Z` – `10:57:48Z` | `completed` / `success` | Checkout, Node 22, Cloudflare email worker tests, Phase 20 operator probe tests |
| `worker` | `139861747090` | `10:57:40Z` – `10:58:44Z` | `completed` / `success` | Checkout, Node 22, Install dependencies, Install Playwright browser, Worker tests |
| `backend` | `139861747029` | `10:57:40Z` – `11:11:47Z` | `completed` / `success` | Checkout, JDK 21, `mvn verify (unit + ArchUnit + Testcontainers ITs)` |

---

## 2. Existing Implementation Assessment

Direct inspection of `worker/src/universal_form.js`, `worker/src/universal_confirmation.js`, `worker/src/browser_worker.js`, `worker/src/worker.js`, `worker/src/plan.js`, `worker/src/outcome.js`, `worker/src/handoff.js`, `worker/src/receipt.js`, backend execution services, and `frontend/src/` confirms the following implementation baseline and concrete real-world defects/gaps:

### 2.1 Solid Architectural Primitives Already Proven
- **Single-Engine Routing & Pre-Submit Controls:** `ApplicationPipelineEventHandler#handleCreated` (`L380-421`), `ApplyController#createPackage` (`L147-161`), and `UniversalBrowserPlanService` (`handles()` / `createExecutionPlan`) cleanly dispatch known ATS URLs to their dedicated adapters and ordinary non-ATS URLs to `planType = "UNIVERSAL"` when `app.automation.universal-enabled=true`. Two-step explicit submission (`approve-submit` → `prepare-submit`), `PRE_SUBMIT_ATTEMPTING` fencing (`AutomationPlanRepository#hasPreSubmitAttemptCheckpoint` + `AutomationController`), drift invalidation, crash quarantine (`RECONCILIATION_REQUIRED`), encrypted `submission_evidence`, and V069 non-sandbox receipt isolation (`submission_receipts = 0`) are all verified on PostgreSQL 16.
- **No Unchecked Selector Interpolation:** `universal_form.js` tags inspected controls with numeric `data-robin-control-id` attributes (`L265-266`) and drives Playwright strictly via `[data-robin-control-id="<n>"]` (`L627, L672`), avoiding CSS selector injection from untrusted third-party DOM text.
- **Truthfulness & Provenance Fences:** `UniversalFieldMapper` + `universal_form.js` enforce `humanConfirmed` (`CANDIDATE_CONFIRMED`/`CANDIDATE_EDITED`), refuse `MODEL_DRAFT` autofill, exclude EEO/demographic/sponsorship guesses, and pass edited answers through `DocumentFactValidator` (`400 Bad Request` on fabricated claims).

### 2.2 Critical Real-World Gaps Discovered During Code Inspection
1. **External Subresource/CDN Requests Aborted in Non-Test Mode (`worker/src/universal_form.js:130`):**
   `installUniversalNavigationGuard` executes:
   ```js
   if (!isDocument && parsed.origin !== expectedOrigin && !allowLocalTestHosts) {
     await route.abort('blockedbyclient');
     return;
   }
   ```
   When `allowLocalTestHosts = false` (required in any real external/staging run), Chromium aborts **every cross-origin JS bundle, stylesheet, font, image, and API/XHR call** (e.g. `cdn.*`, `*.cloudfront.net`, `api.company.com`, `fonts.googleapis.com`). Only the local test fixture (`allowLocalTestHosts = true`) bypassed this line. Most modern real-world career portals will fail to hydrate or submit under this rule.
2. **Custom Hidden `<input type="file">` Dropzones Treated as Honeypots (`worker/src/universal_form.js:160-172, 214`):**
   `isHiddenHoneypot(el)` rejects any element where `display === 'none'`, `visibility === 'hidden'`, `opacity === '0'`, or `rect.width === 0 && rect.height === 0`, and `inspectLiveFormDom` runs `if (isHiddenHoneypot(el)) continue;` unconditionally — **including for `input[type="file"]`**. Real-world Tailwind/React/ATS file dropzones routinely visually hide the native `<input type="file">` behind a visible `<label>` or custom upload button, causing `UNIVERSAL_APPLY` to report `NO_FIELD` instead of attaching the reviewed CV.
3. **Zero Shadow DOM Traversal (`worker/src/universal_form.js:146-213`):**
   `inspectLiveFormDom` queries `doc.querySelectorAll(...)` across `document` and same-origin `iframe.contentDocument`, with no recursion into `el.shadowRoot`. Web-component career portals will yield zero controls or miss shadowed fields.
4. **No Post-Interaction Same-Page Re-Inspection & Static Combobox Option Scraping (`universal_form.js:279-288, 632-640`, `browser_worker.js:2418-2486`):**
   `#runUniversalApply` inspects once per wizard step, runs all actions, then immediately advances (`nextButton`) or pauses. Conditional fields revealed dynamically after choosing `"Yes"` on a same-page radio/select are never inspected before `Next` is clicked; `role="combobox"` only captures options pre-rendered before clicking; `applyUniversalControlActions` queries `[role="option"], li` document-wide without popup scoping.
5. **Login Wall & Saved Credential Dead-End (`browser_worker.js:2387-2507`, `universal_form.js:334`, `handoff.js:30-60`):**
   `UniversalBrowserPlanService:218` persists `step.params.hasSavedCredential` from `employer_credentials`, but `#runUniversalApply` never reads `hasSavedCredential` or `this.credentialResolver`. Any form or portal showing `input[type="password"]` throws `HardStopError('UNIVERSAL_LOGIN_REQUIRED')`, which **does not match `isHandoffEligibleError`** (`handoff.js:33`) and instead enters `DeadLetterRegistry` (`WORKER_FAILED` / `FAILED`) rather than resolving saved credentials or pausing cleanly. Even on CAPTCHA/MFA, `handoff.js:44-60` hardcodes `'WORKDAY_MFA_REQUIRED'` for any `/MFA/i` error regardless of `planType`.
6. **Frontend Missing Universal Resume, Inline Answer Editing, and Employer Credentials UI (`frontend/src/`):**
   `frontend/src` updates are currently limited to `types.ts` (`UniversalOutcome`) and `ApplicationsPage.tsx#isSubmissionCapablePlan` (`L55`). There is no `UniversalReviewPanel`, **zero** calls to `POST /api/v1/automation/plans/{id}/resume`, **zero** calls to `/employer-credentials`, and `ApplyWorkspace.tsx` (`L119-122, L462-470`) only offers a `Confirm this answer` button without a free-text input field to supply missing text for `UNIVERSAL_LIVE_DOM` `NEEDS_USER_INPUT` questions.
7. **Capability Registry Drift (`AtsCapabilityRegistry.java:140-154, 202-211`):**
   `AtsCapabilityRegistry.WORKDAY` still declares `NONE` (`SIGN_IN_READINESS_ONLY`) despite implemented `WORKDAY_APPLY` / `WORKDAY_SUBMIT_CLICK` support across `WorkdayExecutionPlanService`, `AutomationController`, `AutomationPlanRepository#prepareWorkdaySubmission`, and `worker/src/workday.js`. `byApplicationUrl` uses substring `lower.contains(pattern)` rather than parsed host matching.

---

## 3. Real-World Validation Matrix

> **Important Routing Fact (`ApplicationPipelineEventHandler.java:380-421`, `ApplyController.java:147-161`, `UniversalBrowserPlanService.java:43-51`):**
> `UniversalBrowserPlanService.handles()` explicitly returns `false` for URLs matching dedicated ATS host markers (`greenhouse.io`, `zohorecruit.com`, `zohorecruit.in`, `jobs.lever.co`, `jobs.eu.lever.co`, `myworkdayjobs.com`, `jobs.ashbyhq.com`). Therefore, testing those 5 ATS forms via the `UNIVERSAL` engine occurs only on custom-domain embeds/mirrors or explicit non-production fixtures unless routed intentionally. Dedicated ATS adapters are **never** replaced or labeled supported merely by adapter existence.

| Criterion | **1. Greenhouse** | **2. Ashby** | **3. Lever** | **4. Workday** | **5. Zoho Recruit** | **6. Ordinary Employer Websites (Non-ATS / 12 Unimplemented ATS Stubs)** |
| --- | --- | --- | --- | --- | --- | --- |
| **1. Controlled test env exists?** | **Partial (config-only):** `application-local.yml` configures `/robin-sandbox/`, `/sandbox/` prefixes on `greenhouse.io`; repo tests use mock `GreenhouseAdapter` HTML + loopback HTTP fixture (`greenhouse_sandbox_fixture.js`). **No live external sandbox board URL/creds committed.** | **No external sandbox in repo.** Verified previously via read-only GET/live inspection on one public posting (`jobs.ashbyhq.com/ramp`) + local `page.route` fixtures (`ashby.test.js`). | **No external sandbox in repo.** Static HTML fixtures captured from `jobs.lever.co` + `lever.test.js`. | **No external tenant in repo.** Live markup observed up to sign-in (`nvidia.wd5.myworkdayjobs.com` + `workday.test.js` in-memory `page.route` wizard fixture). Multi-page app behind sign-in never verified live. | **No external sandbox in repo.** Live inspection up to non-submit fill on 2 tenants (`fulcrumdigital`, `execo`) + `zoho_recruit.test.js` `page.route` fixtures. | **Local multi-step fixture only** (`universal_employer_fixture.js`). Zero controlled external company websites configured in repo. |
| **2. Test accounts available?** | Public board apply requires no login; official partner/sandbox board access requires operator-configured `APP_SANDBOX_ALLOWED_DOMAINS` + `/sandbox-registrations`. **None provisioned in repo.** | Public apply has no login wall (`jobs.ashbyhq.com`); custom board sandbox requires Ashby customer/partner tenant. **None provisioned.** | Public apply has no login wall (`jobs.lever.co`); no test board provisioned. | **None.** Workday requires per-tenant candidate account (`EmployerCredentialService`); **0 test credentials in repo**; post-account-creation email verification step unverified live (`accountCreationReadiness = PARTIAL`). | Public "I'm interested" reveal form observed without login on 2 sampled tenants; portal trial/sandbox requires Zoho account. **None provisioned.** | **None.** Ordinary company portals requiring candidate accounts depend on `employer_credentials` (`0` external accounts stored; `#runUniversalApply` ignores `hasSavedCredential`). |
| **3. Safe navigation testable without submitting?** | **Yes (via `GREENHOUSE` adapter or `INSPECTION`).** On `UNIVERSAL`, `greenhouse.io` is refused (`DEDICATED_ATS_HOST_MARKERS`); if embedded on custom domain, aborted on cross-origin CDN/iframe. | **Yes (read-only/fill via `ASHBY`).** Refused on `UNIVERSAL` via `DEDICATED_ATS_HOST_MARKERS`; same CDN abort bug (`universal_form.js:130`) would block React chunks if run via `UNIVERSAL`. | **Yes (read-only/fill via `LEVER`).** Refused on `UNIVERSAL` via `DEDICATED_ATS_HOST_MARKERS`; static HTML loads if same-origin but JS assets fail under `L130`. | **Sign-in page only today** via `WORKDAY`; refused on `UNIVERSAL` via `myworkdayjobs.com` marker. Cross-origin Workday CNAME would block static JS bundles under `universal_form.js:130`. | **Yes (read-only/fill via `ZOHO_RECRUIT`).** Refused on `UNIVERSAL` via `zohorecruit.com/.in` marker; Lyte client-rendered assets cross-origin would fail under `universal_form.js:130`. | **Blocked on most live sites today** until `universal_form.js:130` (subresource abort) & cross-origin asset rules are fixed; **safe locally** against loopback fixture. |
| **4. Generic semantic field inspection works?** | **Not tested cleanly:** Greenhouse forms have hidden accessibility inputs (`remix-css-requiredInput` `aria-hidden=true` — filtered by `L163`), `react-select` dynamic comboboxes (missed by `L279-288`), and `css` hidden `<input type="file">`. | **Partial (assumption):** Uses client-side React controls with `ashby-application-form-field-entry` + `select`/pill widgets; hidden file inputs & dynamic listboxes hit `L160` / `L279` gaps. | **Mostly yes for core HTML:** Lever forms use standard visible HTML inputs, though `<input type="file">` styling & dynamic cards vary by posting. | **Partial/Blocked:** Workday heavy custom dropdowns (`data-automation-id` button-listboxes, date spinbuttons) and open/nested Shadow DOM components are not handled by `inspectLiveFormDom`. | **Partial:** Live tenants observed with custom non-native dropdowns (`Current Job Title`), dial-code search inside composite phone wrapper (not filtered generically in `universal_form.js`), and "I'm interested" reveal button (excluded unless matching `nextButton`). | **Works on standard semantic HTML (`label`, `input`, `select`, `textarea`, native `radio`/`checkbox`) in loopback fixture.** Fails on Shadow DOM, hidden file dropzones, portal comboboxes, non-`Next` reveal triggers ("Apply Now" / "I'm interested"). |
| **5. Multi-step navigation works?** | Public apply is typically single-page on `job-boards.greenhouse.io` (separate from multi-page company wrapper). | Typically single-view `/application` sub-route after click. | Typically single-page apply form. | **Not verified live; expected brittle** — Workday uses async multi-page wizard transitions, `data-automation-id` button controls, validation banners, and client-side state. | Often multi-panel or reveal-then-single-form (`I'm interested` trigger not in `nextButton` regex). | **Proven on loopback fixture (`Page 1 -> Page 2`).** Lacks SPA mutation/network settle wait and inline validation-error check before clicking `Next`, plus same-page conditional reveal loop. |
| **6. Document upload verifiable?** | Verified via `GREENHOUSE` (`#resume` / `#cover_letter`); generic `isHiddenHoneypot` risks skipping hidden styled upload inputs. | Verified in `ashby.test.js` via `#runAshbyApply`; custom hidden file-input wrappers risk skipping under `universal_form.js:160-172`. | Verified via `LEVER` (`#resume-upload-input` / `[name="resume"]`). | Verified only on fixture (`workday.test.js`); live Workday upload uses drag-drop button over hidden `<input type="file">`. | Verified in `zoho_recruit.test.js` (`ATTACHED`, never `CONFIRMED`); cover letter absent on 2 sampled tenants. | **Proven `ATTACHED` + SHA-256 verified on visible `<input type="file">` in loopback fixture.** Unverified / likely broken on visually hidden `input[type="file"]` dropzones (`L160-172`). |
| **7. Known & unknown screening questions handled?** | Confirmed Q&A via `ExecutionPackageService` (now fixed for `application_id IS NULL`) + `job_form_questions` for `GREENHOUSE`. | `ASHBY` marks custom selects/radios/checkboxes `REQUIRES_HUMAN` (never consumes `application_answers`). `UNIVERSAL` supports `confirmedAnswers` + `/discovered-questions`. | `LEVER` consumes `cards[uuid][fieldN]` from `application_answers`; missing required custom questions stay gaps in `LEVER_OUTCOME`. | Live multi-step tenant screening questions never observed; `WORKDAY` does not consume `application_answers`. | `ZOHO_RECRUIT` reports all non-identity custom fields as `REQUIRES_HUMAN` without `application_answers` reuse. | **Proven end-to-end for known confirmed answers & unknown mandatory gap -> `/discovered-questions` -> pause -> answer -> `/resume`.** Same-page conditional questions + portal comboboxes unhandled; UI lacks text input/resume buttons. |
| **8. CAPTCHA/MFA boundaries safely observable?** | Detected by DOM/text guards + `AtsHandoffService` (`HANDOFF_REQUIRED`). | No CAPTCHA observed on 1 sampled posting; DOM/text guards active if present. | hCaptcha present behind submit (tested in `lever.test.js`); `LEVER` permanently forbids submit; handoff supported. | Live sign-in honeypot (`beecatcher`), CAPTCHA, & MFA verified on live sign-in markup & fixtures (`workday.test.js`). | Live CAPTCHA observed above Submit on 1 sampled tenant (`zoho_recruit.js`). | **CAPTCHA & MFA detection proven on Playwright fixture (`ANTI_BOT`, `LOGIN_WALL`).** Gap: `handoff.js` mislabels `UNIVERSAL` MFA as `WORKDAY_MFA_REQUIRED`, and `UNIVERSAL_LOGIN_REQUIRED` misses `isHandoffEligibleError`. |
| **9. Pause/resume testable?** | `GREENHOUSE` pauses at `AWAITING_APPROVAL`; no mid-wizard browser step pause because form is single-step static inspect. | Pauses at `AWAITING_APPROVAL` for review (`ashby.test.js`), no `/resume` step-loop endpoint (backend `/resume` currently requires `planType = UNIVERSAL`). | Single pass fill -> `AWAITING_APPROVAL`; permanently never resumes into submit. | No mid-wizard DB resume; Workday login/apply returns single result. | Single pass -> `AWAITING_APPROVAL`. | **Proven via HTTP/PostgreSQL/Playwright fixture** (`page.goto(resumeUrl)`, `completedSteps`, `replayUniversalPause`). Missing candidate UI (`ApplyWorkspace.tsx` + `ApplicationsPage.tsx`) for live manual resume. |
| **10. Confirmation verifiable without genuine submission?** | **Yes — ONLY platform with official Sandbox Registration path (`V069`, `V070`)**, though external sandbox domain requires operator setup (`APP_SANDBOX_ALLOWED_DOMAINS`). Today proven on local HTTP fixture + MockMvc (`GreenhouseSandboxE2EIT`, `greenhouse_sandbox_e2e.test.js`). | **Assumption only:** `ashby.js` checks documented `applicationForm.submit` JSON (`success`/`blocked`) + form removal; **never live-submitted**. | **No (permanently disabled):** hCaptcha blocks submission by policy. | **No (`UNVERIFIED` assumption):** `workday.js#classifyWorkdaySubmission` checks DOM/URL regexes only on local fixture (no network response verification); live tenant unreachable without real applicant session. | **No (permanently disabled):** never clicks Submit. | **Proven ONLY against `universal_employer_fixture.js`** (`POST 2xx` + DOM banner + form removed, storing AES-GCM `submission_evidence` and keeping `submission_receipts = 0`). Cannot verify live non-sandbox external employer without actually submitting — therefore **strictly stop before click (`UNIVERSAL_APPLY` only)** on any live third-party site without an explicit sandbox posting! |

---

## 4. Universal Compatibility Assessment (Code-Level Findings)

Categorized breakdown across all 12 audit dimensions showing **generic fixes** vs **adapter-only concerns**:

| Risk Area | Code Evidence | Impact on Real Websites | Fix Generic vs ATS Adapter |
| --- | --- | --- | --- |
| **Cross-Origin Subresources / CDNs / APIs** | `worker/src/universal_form.js:130` (`!isDocument && parsed.origin !== expectedOrigin && !allowLocalTestHosts`) | **Blocker on ~95%+ live sites** — aborts all CDN JS, CSS, fonts, static assets, and subdomain XHR calls (`api.*`, `cdn.*`). | **Fix Generically:** Enforce `isBlockedHost(...)` + HTTPS-only on all subresources, allow same-site/public HTTPS assets (never blocking CDNs by exact origin equality), while keeping top-level document navigation guarded. |
| **Hidden Custom File Uploaders** | `worker/src/universal_form.js:160-172, 214` | **Very High** — misses stylized drag-drop / Tailwind/React `<input type="file">` controls hidden with `display:none`/`opacity:0`/`0x0`. | **Fix Generically:** Never mark `input[type="file"]` as honeypot solely for `display:none`/`opacity:0`/`0x0` if it is associated with a visible `<label>`, dropzone ancestor, or lacks trap keywords (`honeypot`/`beecatcher`/`website`). |
| **Shadow DOM** | `worker/src/universal_form.js:146-213` | **High** — `doc.querySelectorAll` misses all open Shadow DOM form inputs and buttons. | **Fix Generically:** Add recursive open `shadowRoot` walker inside `inspectLiveFormDom` returning shadow path or tagging host/target elements safely (`evaluateHandle` or shadow traversal map). |
| **Portal / Typeahead Comboboxes (`react-select`, ARIA)** | `worker/src/universal_form.js:279-288, 632-640` | **High** — options absent from DOM prior to click; `[role="option"], li` locator unscoped. | **Fix Generically:** During fill/inspect, click `[role="combobox"]`, inspect `aria-controls`/`aria-owns` popup `[role="listbox"]`, filter options via `resolveOptionValue`, click scoped option (or record live gap if custom). |
| **SPA Hydration, Conditional Reveal & Inline Validation** | `worker/src/browser_worker.js:2402, 2418-2486` | **High** — clicking `Next` immediately after filling misses dynamically revealed required questions or silent validation rejections. | **Fix Generically:** Add bounded DOM mutation settle wait after `goto` / `Next` / radio-or-select edits; re-inspect page for newly mounted required fields before advancing; inspect URL/DOM progression after `Next`. |
| **Initial "Apply Now" / Reveal Triggers & Multi-Word Names** | `worker/src/universal_form.js:315-316, 374-379` | **Medium-High** — `"I'm interested"` / `"Apply for this job"` reveal controls may not match `nextButton` vs `submitButton` cleanly (`apply now` currently matches `isSubmitMatch` at `L315`!). Also >2 token names refuse First/Last split (`L377`). | **Fix Generically:** Only classify `button[type="submit"]` / `"Submit Application"` as final submit when inside an inspected multi-field `<form>` or no form controls exist -> treat standalone `"Apply"`/`"Apply Now"` button on job-detail pages as a form-reveal trigger cleanly without mislabeling as final submit. Support deterministic middle-token split opt-in or profile `firstName`/`lastName`. |
| **Login Walls & Credentials** | `worker/src/browser_worker.js:2387-2410`; `handoff.js:30-60` | **High** — ignores `hasSavedCredential` param; throws `UNIVERSAL_LOGIN_REQUIRED` straight into crash dead-lettering instead of resolving `EmployerCredentialService` or clean handoff/pause. | **Fix Generically:** When `loginWallPresent` + `hasSavedCredential`, run credential resolution (`credentialResolver`) or pause safely (`HUMAN_REQUIRED` / `HANDOFF_REQUIRED` with explicit `EMPLOYER_SECURITY_CHECK` / login pause reason). |
| **Unexpected Cross-Origin / SSO / Embedded Iframes** | `universal_form.js:79-95, 147-158`; `browser_worker.js:2408` | **Medium-High** — legitimate redirects (`careers.co` -> `apply.co` or Okta SSO) & embedded cross-origin ATS iframes fail hard. | **Fix Generically + Reuse Adapters:** Allow Playwright frame inspection on configured public HTTPS origins (`frame.evaluate(...)`), and if redirect lands on a known dedicated ATS domain (`greenhouse.io`, `lever.co`, `ashbyhq.com`), pause/classify honestly (`ATS_REDIRECT_DETECTED`) rather than silently breaking! |
| **Composite Widgets & Multilingual Confirmation** | `universal_form.js:16-18, 567`; `zoho_recruit.js:29` | **Medium** — dial-code country search inputs inside phone widgets risk mis-filling; non-English confirmation pages rely solely on `okPost` + English regex. | **Adapter / Heuristic Hybrid:** Port `isCountryDialCodePicker` filter from `zoho_recruit.js` into `universal_form.js` generically; keep bespoke tenant quirks (`beecatcher`, `_systemfield_*`) inside dedicated adapters (never duplicate ATS adapters). |
| **Frontend Pause/Resume & Credential UI** | `frontend/src/components/ApplyWorkspace.tsx:119-122, 462-470`; `ApplicationsPage.tsx` | **Blocker for manual QA / user validation** — UI cannot edit text for empty discovered `NEEDS_USER_INPUT` answers, cannot call `/resume`, and cannot manage `/employer-credentials`. | **Fix Generically (UI):** Add answer text editor + `/resume` action + `UniversalReviewPanel` + `/employer-credentials` UI. |
| **Capability Registry Alignment** | `AtsCapabilityRegistry.java:140-154, 202-211` | **Low-Medium (UX/truthfulness)** — Workday shows `NONE` despite Phase 19 code; `byApplicationUrl` uses substring `contains`. | **Code Hygiene:** Sync Workday capabilities accurately or mark Workday experimental until live-verified, and parse URI hosts cleanly. |

---

## 5. Safe External Validation Strategy & Environment Requirements

### 5.1 Absolute Safety Rules
1. **Zero real applications:** Never click final Submit (`UNIVERSAL_SUBMIT_CLICK` / `REAL_SUBMIT_CLICK` / `ASHBY_SUBMIT_CLICK` / `WORKDAY_SUBMIT_CLICK`) against any third-party employer job posting or production ATS board.
2. **Non-submitting readiness verification (`UNIVERSAL_APPLY` / `HUMAN_REQUIRED`) vs submission tests:**
   - External non-ATS or sandbox validation **without** an operator-approved isolated sandbox stops strictly at `AWAITING_APPROVAL` after `UNIVERSAL_APPLY` (`submit_approved = false`, zero submit click steps).
   - End-to-end submit + receipt verification runs **only** against: (a) local controlled fixtures (`universal_employer_fixture.js`, `greenhouse_sandbox_fixture.js`), or (b) an explicitly operator-owned & allowlisted test sandbox posting.
3. **Never create bogus accounts, fabricate work auth, or bypass CAPTCHA/MFA.**

### 5.2 Controlled 4-Tier Validation Architecture for Stage 3D

| Tier | Target Environment | What It Validates Safely | Required Config / Prerequisites |
| --- | --- | --- | --- |
| **Tier 1: Rich Real-World Pattern Local Fixtures** | Extended Node/Playwright + Docker loopback targets (`universal_employer_fixture.js` v2 + separate CDN port origin + MailHog/local test inbox) | Cross-origin CDN scripts, custom hidden `display:none` file-upload dropzones, open Shadow DOM controls, portaled `react-select` comboboxes, conditional same-page reveal questions, saved-credential login gates (`EmployerCredentialService`), pause/resume, `PRE_SUBMIT_ATTEMPTING`, crash quarantine, full evidence/tracking. | `app.sandbox.allow-local-test-hosts=true` (test-only profile), `APP_SECRETS_KEY`. Zero external accounts needed. |
| **Tier 2: Operator-Owned Controlled Staging Form** | Self-hosted or test-only external HTTPS multi-step application form owned by operator (e.g. staging subdomain with real TLS + CDN + Cloudflare Turnstile toggle / test endpoint) | Real TLS (`allowLocalTestHosts=false`), real DNS `AddressGuard` check, cross-origin CDN asset loading, Turnstile/CAPTCHA handoff path (`AtsHandoffService` HTTPS requirement), Playwright headless container execution (`--disable-dev-shm-usage`). | Operator-provisioned staging domain + `APP_SANDBOX_ALLOWED_DOMAINS` (never committed). |
| **Tier 3: Official Vendor Sandboxes (Opt-In When Provisioned)** | Greenhouse Job Board Sandbox, Zoho Recruit Developer Sandbox, Ashby/Workday partner test board (strictly non-production sandbox tenants) | Vendor-specific portal DOM fidelity + V069 `candidate_sandbox_registrations` (`GREENHOUSE` only) with `ReceiptCaptureService`. | `APP_SANDBOX_ALLOWED_DOMAINS`, `APP_SANDBOX_ALLOWED_PATH_PREFIXES`, `APP_SANDBOX_REQUIRE_HTTPS=true`, plus operator-managed test account in `employer_credentials` (`APP_SECRETS_KEY`) and **never committed to git**. |
| **Tier 4: Read-Only Captured-DOM Regression Snapshots** | Saved static/DOM captures from public career sites (zero live clicks, offline replay in Playwright `page.route` / local HTTP) | Label extraction, honeypot filtering, Shadow DOM / custom select inspection accuracy across diverse real layouts with zero employer side effects. | Sanitized HTML fixtures under `worker/test/fixtures/` with PII/company tokens scrubbed. |

### 5.3 Exact Configuration Required for External Validation
Currently absent from repository defaults (`application.yml`, `application-prod.yml`):
- `APP_SECRETS_KEY` (32-byte base64 key for `employer_credentials`, `submission_evidence`, managed mail).
- `APP_SANDBOX_ALLOWED_DOMAINS` & `APP_SANDBOX_ALLOWED_PATH_PREFIXES` (required by `SandboxRegistrationPolicy#isConfigured()` for any external sandbox posting registration; currently empty in `application.yml` and `application-prod.yml`).
- `APP_SANDBOX_REQUIRE_HTTPS=true` and `APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS=false` in all non-test environments.
- Optional staging `employer_credentials` seeded solely via candidate session (`POST /api/v1/employer-credentials` once UI/session exists — never baked into `V024` or config files).
- For managed email verification during login/signup tests (`RegistrationIdentityService` / Phase 20): Cloudflare worker secret + inbound domain or local loopback mailbox mock.

---

## 6. Stage 3D Implementation Blueprint (Prioritised)

Smallest high-leverage Stage 3D phase that unblocks safe external & real-world-pattern browser validation without breaking ATS adapters or touching `V001`–`V070`:

### Priority Queue
1. **P0 — Fix Non-Document Cross-Origin Resource Blocking (`worker/src/universal_form.js:101-136`)**
   - *Impact / Risk:* Critical blocker (`L130` aborts all CDN JS/CSS/fonts/API requests on external websites when `allowLocalTestHosts=false`). High reliability gain, minimal complexity.
   - *Change:* Keep top-level document navigation strictly bound (`universalPageIdentityMatches`), abort SSRF/private/loopback/unsafe-scheme URLs via `isBlockedHost(...)` on **all** resource types, and stop aborting legitimate HTTPS third-party script/stylesheet/font/media/XHR subresources. Preserve `expected.origin` enforcement on final submission capture (`universal_confirmation.js`).
2. **P0 — Support Visually Hidden Custom `<input type="file">` & Standalone Apply-Trigger Disambiguation (`worker/src/universal_form.js:160-172, 310-326`)**
   - *Impact / Risk:* Fixes silent `NO_FIELD` failures on stylized Tailwind/React/ATS file dropzones and prevents `"Apply Now"` reveal buttons from being misclassified as final `submitButton`.
   - *Change:* Exempt `type="file"` controls from `display:none`/`opacity:0`/`0x0` honeypot filtering when a visible `<label for>`, enclosing upload container/dropzone button, or non-honeypot attributes exist (still excluding `aria-hidden="true"` trap duplicates with zero interactive trigger). Classify `"Apply"`/`"Apply Now"` as `nextButton`/reveal trigger unless a multi-field form is already active with `submit` semantics.
3. **P0 — Complete Candidate Pause/Resume, Editable Answers & Employer-Credential UI (`frontend/src/`)**
   - *Impact / Risk:* Unblocks genuine end-to-end manual UI validation across Discover → Apply → Pause → Edit Answer → Resume → Approve → Prepare-Submit.
   - *Change:* Allow inline answer text input + confirmation in `ApplyWorkspace.tsx` (`PUT /application-answers/{id}` displaying `DocumentFactValidator` 400 errors), add `UniversalReviewPanel.tsx` supporting `POST /automation/plans/{id}/resume` + `approve-submit` + `prepare-submit` inside `ApplicationsPage.tsx`, and add saved credential management calling existing `/api/v1/employer-credentials`.
4. **P1 — Same-Page Conditional Reveal Loop, SPA Settlement & Scoped Combobox Interaction (`worker/src/universal_form.js`, `worker/src/browser_worker.js`)**
   - *Impact / Risk:* Prevents advancing past conditional mandatory questions or failing on `react-select`/MUI/Radix portaled listboxes and slow SPAs.
   - *Change:* Add bounded DOM-settlement helper before inspection (`<= 2500ms`), scoped `[role="combobox"]` popup opening + option resolution (`aria-controls`/`aria-owns`), post-fill same-page re-inspection pass (detecting newly revealed conditional controls before clicking `Next`), inline error (`validationErrors`) halt before `Next`, and dial-code sibling filter ported from `zoho_recruit.js`.
5. **P1 — Open Shadow DOM Traversal & Login-Wall / Credential Flow (`worker/src/universal_form.js`, `worker/src/browser_worker.js`, `worker/src/handoff.js`)**
   - *Impact / Risk:* Covers Web Components (`el.shadowRoot`) and prevents candidate login pages from crashing into dead-letter `FAILED` instead of using saved credentials or pausing/handing off cleanly.
   - *Change:* Recurse into open `shadowRoot` trees during `inspectLiveFormDom`; wire `step.params.hasSavedCredential` + `this.credentialResolver` cleanly inside `#runUniversalApply`; fix `normalizeHandoffReason` so `UNIVERSAL` MFA does not misreport `WORKDAY_MFA_REQUIRED`; make `UNIVERSAL_LOGIN_REQUIRED` either resolve to `HUMAN_REQUIRED` (`EMPLOYER_CREDENTIAL_REQUIRED`) when unconfigured or handoff-eligible where appropriate.
6. **P2 — Cross-Origin Trusted Origin / Redirect Policy & Capability Registry Alignment (`UniversalBrowserPlanService.java`, `universal_form.js`, `AtsCapabilityRegistry.java`)**
   - *Impact / Risk:* Handles legitimate multi-subdomain career flows (`jobs.acme.com` -> `apply.acme.com`) cleanly and aligns Workday registry metadata (`AtsCapabilityRegistry.java:140-154`) + URI host matching (`L202-211`).

### Exact Proposed Files & Tests for Stage 3D

- **Proposed Modified Source Files:**
  - `worker/src/universal_form.js` (CDN subresource SSRF guard fix, hidden `<input type="file">` label-wrapper support, Shadow DOM traversal, scoped comboboxes, dial-code picker exclusion, reveal vs submit disambiguation, same-page conditional re-inspection helpers)
  - `worker/src/browser_worker.js` (DOM mutation settle wait, same-page re-inspection loop, inline validation check before `Next`, saved-credential resolution on `loginWallPresent` using `hasSavedCredential`, clean pause vs dead-letter handling)
  - `worker/src/handoff.js` (`normalizeHandoffReason` per-`planType` mapping instead of hardcoded `WORKDAY_MFA_REQUIRED`)
  - `backend/src/main/java/com/personal/jobagent/automation/UniversalBrowserPlanService.java` (multi-token name / optional origin allowlist hints if needed — zero migration changes)
  - `backend/src/main/java/com/personal/jobagent/automation/AtsCapabilityRegistry.java` (sync Workday capability status honesty & URI host parsing in `byApplicationUrl`)
  - `frontend/src/components/ApplyWorkspace.tsx` (editable answer input before confirmation for empty `NEEDS_USER_INPUT` / `UNIVERSAL_LIVE_DOM` rows)
  - `frontend/src/components/UniversalReviewPanel.tsx` *(new)* + `frontend/src/pages/ApplicationsPage.tsx` (Universal outcome review, `Resume` action calling `/automation/plans/{id}/resume`, approve/prepare-submit buttons)
  - `frontend/src/pages/ProfilePage.tsx` (or credential panel component using existing `/api/v1/employer-credentials`)
- **Proposed Tests for Stage 3D:**
  - `worker/test/fixtures/universal_employer_fixture.js` + `worker/test/universal_engine.test.js`: add real Chromium cases for (a) external CDN origin (`allowLocalTestHosts=false` style subresource separation or multi-origin mock asset server), (b) visually hidden `display:none` file upload wrapped by `<label>`, (c) open Shadow DOM inputs, (d) dynamically mounted `[role="combobox"]` portal listbox, (e) radio toggle revealing a conditional required question on the same page, (f) login wall resolving saved credential (`credentialResolver`) vs pausing cleanly when absent.
  - `worker/test/handoff.test.js`: verify `UNIVERSAL` MFA/Turnstile handoff normalization does not emit `WORKDAY_MFA_REQUIRED`.
  - `frontend/src/components/ApplyWorkspace.test.tsx`, `frontend/src/components/UniversalReviewPanel.test.tsx`, `frontend/src/pages/ApplicationsPage.lifecycle.test.tsx`: Vitest tests covering answer text submission, `DocumentFactValidator` error display, `/plans/{id}/resume` call, and `approve-submit`/`prepare-submit`.
  - `backend/src/test/java/com/personal/jobagent/automation/AtsCapabilityRegistryTest.java`: verify URI host matching and honest Workday declaration.

---

## 7. Security & Legal Boundaries

- All Stage 3C inspection was read-only against local repository code and CI metadata (`gh run view 45806127601`); zero external employer pages were scraped, filled, or submitted.
- Zero existing migrations (`V001`–`V070`) or source files were altered.
- PR #21 remains OPEN and DRAFT on `integration/wave-3` (`937782293e6057629cb8cc52108e9f8f3f790060`), leaving `master` (`a45888d63bd2a893c7373ed44a6d17b930682a36`) untouched.
- External vendor sandboxes require explicit operator opt-in and credentials provisioned outside version control; third-party CAPTCHA/MFA checks trigger `AtsHandoffService` (`HANDOFF_REQUIRED`), never automation bypasses.

---

## 8. Explicit Unverified Claims & Limitations

1. **No Live Real-World External Website Has Been Tested Yet:** Every runtime proof for `UNIVERSAL`, `GREENHOUSE` sandbox submissions, `ASHBY` submit, `WORKDAY` apply/submit, `LEVER` apply, and `ZOHO_RECRUIT` apply remains scoped to local HTTP fixtures, Playwright `page.route` intercepts, and PostgreSQL 16 Testcontainers ITs. Only read-only DOM structure checks were historically documented for Greenhouse, Lever, Ashby, Zoho Recruit (form fields), and Workday (sign-in only).
2. **External Staging / Vendor Sandboxes Require Operator Provisioning:** No external Greenhouse sandbox URL, Zoho Developer Portal, Ashby test board, or Workday tenant test credentials exist in this repository. Live verification against any vendor portal in Stage 3D or later stays blocked on operator-provided credentials/sandboxes (`APP_SANDBOX_ALLOWED_DOMAINS`, `/api/v1/sandbox-registrations`, `/api/v1/employer-credentials`).
3. **Production External Sites Would Hit Specific Current Bugs (`universal_form.js:130, 160`):** Without Stage 3D's P0 fixes (cross-origin CDN subresource relaxation and hidden file-input handling), running `UNIVERSAL` against real external websites would fail to load external static assets and miss styled file dropzones even prior to reaching any CAPTCHA or screening question.
