# ROBIN — Gap Register

Each entry records evidence and status. Severity: **P0** user-facing truthfulness/safety defect; **P1** blocks a core promise or phase exit; **P2** genuine but not a current phase blocker.

---

## P0 — Workday capability was overstated — **FIXED IN MASTER BASELINE**

**Evidence:** the former `AtsCapabilityRegistry.WORKDAY` row reported form-filling capability even though `WorkdayExecutionPlanService` only verifies a saved credential against the real sign-in page and does not reach or fill the application form.

**Fix:** commit `610d574` sets the Workday form/submission dimensions to `NONE`, so the API returns `SIGN_IN_READINESS_ONLY`. The fix is included in the `master` baseline used by merged PR #10. This source-level correction does not prove a real Workday application submission; Robin does not claim that capability.

---

## P1 — Phase 20 managed-mail production verification remains open

A real inbound email reached Cloudflare → Worker → Render → Postgres, was encrypted and appeared in Robin. Unit/integration tests also cover signature verification, replay protection and deduplication.

**Still not production-proven:** live duplicate webhook redelivery/idempotency, association of a received message to the correct specific application, actual automatic OTP extraction/use in a supported employer verification flow, and the complete recruiter-communication lifecycle. A single real email or an isolated Testcontainers run does not prove these live flows. See `ROBIN_PHASE20_VERIFICATION.md` §4.3.

Encryption-key continuity and the initial end-to-end delivery are resolved; do not conflate that with full Phase 20 closure.

---

## P1 — ATS submission is not yet production-verified

No ATS adapter is assigned `SUBMISSION_VERIFIED` by design. The registry's rules forbid real-employer test submissions. Greenhouse has a guarded submit path tested against fixtures, but this is not a verified production or candidate-owned sandbox submission. Any product messaging that implies a submission has been independently confirmed would be false.

---

## P1 — Candidate document-fact validation hardening — **IMPLEMENTED AND CI-VERIFIED IN PR #11; PENDING MERGE**

The Phase 21 review found concrete weaknesses in the former validator: experience years derived from the earliest start year to the present; numeric figures could match by digits alone despite a different unit/currency; work-authorisation statements were not compared against specific structured eligibility values; and a target employer/school/project/skill could be mistaken for a previous employer.

PR #11 (`phase21-candidate-knowledge-integrity`) addresses these and adds:
- merged work-month interval calculations that respect end dates and gaps;
- exact figure matching including unit/currency;
- explicit structured UK right-to-work and sponsorship contradiction blockers;
- prior-employer evidence limited to recorded work-experience employers;
- additional persisted profile evidence in cover-letter generation context;
- validator version `fact-validator-2`, with stale CV rejection in API/application packaging and explicit stale-state/revalidation handling in PREP.

**Test evidence:** PR #11 CI run #75 is green. `DocumentFactValidatorTest` 13/13, `CoverLetterReviewTest` 8/8 and PostgreSQL-backed `PrepWorkflowIT` 7/7 passed. Full backend Surefire: 1,397 tests, 0 failures, 6 skipped; Failsafe: 272 tests, 0 failures, 25 skipped. The 25 skipped tests are not counted as executed proof. The PR is not merged, so these changes are not yet part of `master`.

**Residual limitations:** the validator is bounded and cannot semantically prove every sentence. `USER_VERIFIED` means candidate-attested, not third-party verified. Employer/requirement extraction includes heuristics. Work eligibility is UK-shaped, not a global country-by-country rights engine. Textual unit/currency matching may reject equivalent formatting and needs future boundary/normalisation work. No aggregate CV factual-accuracy rate has been measured.

Full trace and limitations: `docs/ROBIN_PHASE21_VERIFICATION.md`.

---

## P1 — Cloudflare email worker had no CI coverage — **RESOLVED BY MERGED PR #10**

The original gap was that the Cloudflare Email Worker tests ran only locally. PR #10 added a Node 22 GitHub Actions job that runs `npm test --prefix cloudflare-email-worker`. PR #10 is merged to `master` as `6c3a72a`; CI run #62 passed all four jobs. This is no longer an open gap.

---

## P2 — Lever / Zoho Recruit: real submission is blocked or partial by design

Lever: hCaptcha is enforced in both the backend plan-type gate and browser worker plan validator. Zoho: identity-field filling is partial and most custom questions remain surfaced as gaps; no real submission occurs. Keep capability labels honest; do not remove safety gates to improve a raw ATS count.

---

## P2 — Workday account-creation verification is unknown

`AtsCapabilityRegistry` records that the real post-creation verification mechanism (OTP, link, or neither) has not been established. This is honestly labelled `PARTIAL`; resolve only with safe evidence before expanding Workday claims.

---

## P2 — Tsenta ATS-count inconsistency not reproduced

A direct check on 2026-11-03 found both `tsenta.com` and `help.tsenta.com` consistently claimed "30+ ATSes". Do not repeat the earlier "15+ vs 30+" inconsistency absent new evidence.

---

## Not yet independently gap-analysed

Discovery freshness/dedup accuracy, matching/ranking quality, hard-filter integrity under adversarial scores, broader CV factual-accuracy rates, automation-intelligence limits, review-before-send diff quality, UX/accessibility and commercial readiness remain unscored or only partially explored. Services existing in the codebase is not proof that their outputs meet the intended product standard. Follow the evidence passes in `ROBIN_BUILD_ROADMAP.md`.
