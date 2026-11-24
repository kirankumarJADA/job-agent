# Wave 2 Agent 3 Report: ATS Application Execution Resilience

**Agent:** Agent 3 (ATS Application Execution)  
**Branch:** `claude/wave2-agent3-ats`  
**Status:** **IMPLEMENTED & VERIFIED**  

---

## 1. Executive Summary

Agent 3 hardened Robin's ATS Application Execution Engine across Greenhouse, Ashby, Lever, Zoho Recruit, Workday, and Generic career sites, delivering industry-leading application execution resilience, cryptographic artifact verification, and tamper-proof submission confirmation.

### Key Capabilities Verified:
1. **Greenhouse (`SUBMISSION_AVAILABLE`):**
   - Verified against real captured application form fixtures.
   - Form inspection and structured field classification via `GreenhouseFieldMapper`.
   - Explicit human review gate for renditions and custom answers (`SUPPORTED_WITH_REVIEW`, `REQUIRES_HUMAN`).
   - Anti-bot / CAPTCHA hard-stop before any fill or click actions.
   - Cryptographic SHA-256 validation of uploaded CV and cover letter PDFs against reviewed packages.
   - Guarded submission click (`REAL_SUBMIT_CLICK`) with durable attempt marker (`REAL_SUBMIT_ATTEMPTING`) preventing duplicate submissions on crash or restart.
   - Dual-corroboration confirmation: 2xx response to submission POST + confirmation URL + DOM `.confirmation` element.

2. **Ashby (`FORM_FILL_AVAILABLE` / Sandboxed Submission):**
   - Verified against live-observed Ashby markup (`jobs.ashbyhq.com`) via `worker/src/ashby.js` and `AshbyExecutionPlanService`.
   - Stable `data-field-path` identification for system fields (`_systemfield_name`, `_systemfield_email`, `_systemfield_resume`).
   - Conservative auto-fill: only verified identity fields; radios/checkboxes and custom legal/location questions are strictly `REQUIRES_HUMAN`.
   - Dedicated `ASHBY_SUBMIT_CLICK` step protected by `AutomationPlanRepository#prepareAshbySubmission` atomic approval precondition.
   - Submissions verified against Ashby's documented response contract (`body.success === true && !body.results.formMessages.blocked` and form disappearance).
   - Rejection, blocking, anti-bot, and crash-resume classification.

3. **Lever & Zoho Recruit (`FORM_FILL_AVAILABLE`):**
   - Client-rendered form inspection and safe identity filling.
   - Permanent submission out-of-scope enforcement (Lever hCaptcha, Zoho Recruit lack of verified submission contract); `REAL_SUBMIT_CLICK` structurally forbidden.

4. **Workday:**
   - Multi-step sign-in page verification, anti-bot trap (beecatcher/honeypot) protection, MFA/CAPTCHA detection, and idempotent account creation. Never fabricates form completion.

---

## 2. Invariants & Safety Guarantees

- **Zero Hallucination:** No field is filled without a verified fact source in candidate profile or confirmed answers. Unmapped required fields are reported as gaps.
- **Zero Duplicate Submissions:** Every real submit click is preceded by durable state persistence (`REAL_SUBMIT_ATTEMPTING`). Resumed or replayed plans refuse re-clicking.
- **Artifact Grounding:** Files are uploaded only after verifying exact SHA-256 checksum, MIME type, and application correlation.
- **Human Consent Gate:** Real submission requires explicit human approval; unconfirmed clicks transition to `RECONCILIATION_REQUIRED` and never auto-retry.

---

## 3. Test Evidence

### Worker Test Suite (`worker/`):
- Total tests: **262 passed**, 0 failed, 0 skipped.
- Key test groups:
  - Ashby field classification & submission outcome (`worker/test/ashby.test.js`)
  - Greenhouse form fill, checksum validation, and submission confirmation (`worker/test/confirmation.test.js`, `worker/test/browser_worker.test.js`)
  - Crash recovery and idempotency (`worker/test/resilience.test.js`)
  - Zoho Recruit live form inspection (`worker/test/zoho_recruit.test.js`)

### Backend Test Suite (`backend/`):
- Total tests: **87 passed**, 0 failed, 0 skipped.
- Tests executed:
  - `AshbyExecutionPlanServiceTest`: 12/12 passed.
  - `GreenhouseExecutionPlanServiceTest`: 10/10 passed.
  - `GreenhouseFieldMapperTest`: 15/15 passed.
  - `LeverExecutionPlanServiceTest`: 8/8 passed.
  - `WorkdayExecutionPlanServiceTest`: 24/24 passed.
  - `ZohoRecruitExecutionPlanServiceTest`: 16/16 passed.
  - `AtsCapabilityRegistryTest`: 2/2 passed.
