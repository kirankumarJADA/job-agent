# ROBIN — Gap Register

Each entry cites exact file/line evidence. Severity: **P0** (user-facing truthfulness/safety bug, fix before anything else) · **P1** (blocks Phase 20 or a core promise) · **P2** (real but not blocking).

---

## P0 — Workday capability was overstated to the candidate — **FIXED, commit `610d574`**

**Status:** Fixed in this review cycle. The data values described below have already been corrected; this entry is kept as the evidentiary record of why the fix was needed.

**Evidence:** `backend/src/main/java/com/personal/jobagent/automation/AtsCapabilityRegistry.java:140-149` sets the `WORKDAY` row's `formFilling = FULL` (12th positional value in the `AtsCapabilities` record, confirmed via `AtsCapabilities.java:35-51`). `AtsCapabilities.summary()` (`AtsCapabilities.java:59-74`) returns `FORM_FILL_AVAILABLE` whenever `formFilling != NONE`, with no other gate. This summary is served verbatim to the frontend via `GET /api/v1/ats-capabilities` (`AtsCapabilityController.java:43-46`).

Meanwhile `WorkdayExecutionPlanService.java:29-83` — the actual implementation — states explicitly, as its own module Javadoc: *"this service implements exactly the stage that IS genuinely supported end to end: verifying the candidate's SAVED Workday credential... It NEVER attempts the application form itself."* Even `AtsCapabilityRegistry`'s own class-level comment (lines 47-62, the prose describing what was verified) repeats this same limitation correctly. **The row's own Javadoc (lines 144-149) and its data values (line 143) contradict the file's own class-level comment three paragraphs above it.**

**Impact:** a candidate opening a Workday job in Robin is told the form can be filled, when the shipped service can only check that a saved password still works. This is exactly the class of claim the whole project's stated philosophy ("never claim a capability the code doesn't prove") exists to prevent.

**Fix (applied):** `AtsCapabilityRegistry.WORKDAY`'s `formInspection`, `requiredFieldValidation`, `questionAnswering`, `documentAttachment`, `formFilling`, `finalSubmission`, and `submissionConfirmation` values are now `NONE`, so `summary()` correctly falls through to `SIGN_IN_READINESS_ONLY`. `AtsCapabilityRegistryTest` and `AtsCapabilityControllerTest` were updated to match (re-run and confirmed independently in this review cycle — see the accompanying review's Task D for the exact command and result). Committed as `610d574`, not yet pushed to `origin/master`.

---

## P1 — Phase 20 managed mail: partially verified in production, not fully closed (full detail in `ROBIN_PHASE20_VERIFICATION.md`)

**Update 2026-11-03:** a real inbound email completed Cloudflare → Worker → Render → Postgres → UI for the first time — one message, encrypted, persisted, zero purged. The `APP_SECRETS_KEY`-missing state is resolved (new key generated and added, backend restarted). Encryption-key continuity is also resolved: a pre-provisioning count found zero rows in both `employer_credentials` and `managed_inbound_messages`, so no row can depend on a lost prior key. **Still open:** duplicate webhook redelivery/idempotency in production, email-to-application association, automatic OTP extraction, and the full recruiter-communication lifecycle — none of these are proven yet. See `ROBIN_PHASE20_VERIFICATION.md` §4.3.

## P1 — `cloudflare-email-worker` has no CI coverage

**Evidence:** `.github/workflows/ci.yml` has jobs for `backend`, `worker`, `frontend` only — no job runs `npm test --prefix cloudflare-email-worker`. Every "6/6 passed" claim for this package (including in PR #9's own description) was necessarily a local/manual run.

**Impact:** a future change to the email Worker could silently break without CI catching it — the one component directly in the Phase 20 critical path is the one with no automated gate.

**Fix:** add a fourth CI job mirroring the `frontend`/`worker` pattern (Node setup, `npm install`, `npm test`), ~10 lines.

## P1 — No ATS ever reaches `SUBMISSION_VERIFIED` by design — product messaging must not imply otherwise

Not a code bug — a product-communication risk. `AtsCapabilityRegistry`'s own rules forbid ever testing against a real employer, so this state is structurally permanent until a controlled real-submission test is explicitly authorized by the candidate for their own application. Any roadmap or marketing copy claiming "submission verified" for Greenhouse/Ashby today would be false.

## P2 — Lever / Zoho Recruit: real submission permanently blocked, not a near-term fix

Lever: hCaptcha, enforced in two independent places (`AutomationPlanRepository#prepareRealSubmission` plan-type gate + `browser_worker.js#validateLeverPlan`). Zoho: most custom questions remain unanswerable gaps even where identity-field filling works. These are correctly labeled `PARTIAL`/never-submits in the registry already — no overclaim found here, unlike Workday. No action needed beyond what's already honestly documented.

## P2 — Workday account-creation verification is unknown

**Evidence:** `AtsCapabilityRegistry.java:54-62` — account creation (`WORKDAY_CREATE_ACCOUNT`) is implemented and tested, but *"the real post-creation verification mechanism (an OTP? a link? neither?) is unknown."* This is honestly flagged as `PARTIAL`, not overstated — listed here only because it's a real open question worth resolving before Workday work expands.

## P2 — Unverified claim retired this session: "15+ vs 30+ ATS" Tsenta inconsistency

Direct check of `tsenta.com` and `help.tsenta.com` on 2026-11-03 found both consistently say "30+ ATSes." This specific inconsistency does not currently reproduce and should not be repeated in future competitive material (see `ROBIN_TSENTA_SCORECARD.md`).

---

## Not yet independently gap-analyzed this session (flagged, not assumed complete or broken)

Discovery adapters' actual freshness/dedup accuracy, matching/ranking precision, CV-tailoring factual-accuracy rate, and automation-intelligence limits all have real services present (`JobDeduplicationService`, `JobMatchService`, `HardFilterService`, `SemanticSkillMatcher` — all confirmed to exist by file, not yet read line-by-line for correctness). Per your own rule ("do not assume missing or complete from this list alone"), these need a dedicated evidence pass before being scored — see `ROBIN_BUILD_ROADMAP.md` for sequencing. Scoring them now without reading them would be exactly the kind of unverified claim this whole audit is trying to eliminate.
