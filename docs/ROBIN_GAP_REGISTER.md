# ROBIN — Gap Register

Each entry records evidence and status. Severity: **P0** user-facing truthfulness/safety defect; **P1** blocks a core promise or phase exit; **P2** genuine but not a current phase blocker.

---

## P0 — Workday capability was overstated — **FIXED IN MERGED MASTER BASELINE**

**Evidence:** the former `AtsCapabilityRegistry.WORKDAY` row reported form-filling capability even though `WorkdayExecutionPlanService` only verifies a saved credential against the real sign-in page and does not reach or fill the application form.

**Fix:** commit `610d574` sets the Workday form/submission dimensions to `NONE`, so the API returns `SIGN_IN_READINESS_ONLY`. The fix is included in merged master. This source-level correction does not prove a real Workday application submission; Robin does not claim that capability.

---

## P1 — Phase 20 managed-mail production verification remains open

A real inbound email reached Cloudflare → Worker → Render → Postgres, was encrypted and appeared in Robin. Unit/integration tests also cover signature verification, replay protection and deduplication.

**Still not production-proven:** live duplicate webhook redelivery/idempotency, association of a received message to the correct specific application, actual automatic OTP extraction/use in a supported employer verification flow, and the complete recruiter-communication lifecycle. A single real email or an isolated Testcontainers run does not prove these live flows. See `ROBIN_PHASE20_VERIFICATION.md` §4.3.

Encryption-key continuity and the initial end-to-end delivery are resolved; do not conflate that with full Phase 20 closure.

---

## P0 — Production database write outage (reported 2026-11-05; root cause confirmed 2026-11-06)

**Historical finding:** the 2026-11-05/06 production investigation found PostgreSQL writes intermittently failing while reads continued. The investigation attributed the behavior to Supabase Free-plan read-only mode triggered at the 95% disk threshold, after a discovery/outbox data surge. The operational backup-first migration work and later production database cutover are recorded in the separate database migration/operations documents.

**Status note:** this is retained as historical incident evidence, not asserted as the current live production condition on 2026-11-17. Do not infer current database health or write availability from this historical entry.

---

## P2 — Errors on public endpoints reach anonymous callers as 401

The prior investigation observed error dispatches to `/error` being replaced by the authentication entry point, obscuring some underlying 4xx/5xx responses from anonymous probes. This was recorded as an observability/API error-reporting concern; current behavior requires fresh verification before treating it as active.

---

## P2 — Firebase revocation checks and session behavior

The November 2026 investigation recorded revocation checks on bearer-authenticated requests and separate cookie-session behavior. Treat measured latency and session behavior as areas for current verification rather than assume the historical observation is unchanged.

---

## P2 — Receipts list behavior for a foreign application id

A prior review observed owner-filtered empty results rather than 404 for a foreign application id. This did not expose foreign data but was inconsistent with other application-scoped endpoints. Current behavior should be verified before treating it as an active defect.

---

## P2 — Gmail-ingested mail thread association

A previous source review identified that Gmail API message IDs and RFC Message-ID threading were handled differently from the Cloudflare-managed-mail path. Managed-mail reply threading was implemented and tested. Re-check the current Gmail mapper before scheduling a fix.

---

## P1 — Candidate document-fact validation hardening — **IMPLEMENTED AND CI-VERIFIED IN PR #11; PENDING MERGE**

The Phase 21 review found concrete weaknesses in the prior validator: experience years derived from earliest start year to present; numeric figures could match by digits alone despite a different unit/currency; work-authorisation statements were not compared against specific structured eligibility values; and a target employer/school/project/skill could be mistaken for a previous employer.

PR #11 (`phase21-candidate-knowledge-integrity`) addresses these and adds:
- merged work-month interval calculations that respect end dates and gaps;
- exact figure matching including unit/currency;
- explicit structured UK right-to-work and sponsorship contradiction blockers;
- prior-employer evidence limited to recorded work-experience employers;
- additional persisted profile evidence in cover-letter generation context;
- validator version `fact-validator-2`, with stale CV rejection in API/application packaging and explicit stale-state/revalidation handling in PREP.

**Test evidence:** PR #11 CI run #75 passed all four jobs on its recorded code head. `DocumentFactValidatorTest` 13/13, `CoverLetterReviewTest` 8/8 and PostgreSQL-backed `PrepWorkflowIT` 7/7 passed. Full backend Surefire: 1,397 tests, 0 failures, 6 skipped; Failsafe: 272 tests, 0 failures, 25 skipped. These historical run counts do not substitute for CI on the final reconciled branch head. The PR is not merged, so Phase 21 is not yet part of master.

**Residual limitations:** the validator is bounded and cannot semantically prove every sentence. `USER_VERIFIED` means candidate-attested, not third-party verified. Employer/requirement extraction includes heuristics. Work eligibility is UK-shaped, not a global country-by-country rights engine. Textual unit/currency matching may reject equivalent formatting and needs future boundary/normalisation work. No aggregate CV factual-accuracy rate has been measured.

Full trace and limitations: `docs/ROBIN_PHASE21_VERIFICATION.md`.

---

## P1 — Cloudflare email worker CI coverage — **RESOLVED BY MERGED PR #10**

PR #10 added a Node 22 GitHub Actions job that runs `npm test --prefix cloudflare-email-worker`. The merged workflow includes this gate. Keep the CI coverage status distinct from live production verification of the entire mail lifecycle.

---

## P1 — ATS submission is not yet production-verified

No ATS adapter is assigned `SUBMISSION_VERIFIED` by design. The registry's rules forbid real-employer test submissions. Greenhouse has a guarded submit path tested against fixtures, but this is not a verified production or candidate-owned sandbox submission. Any product messaging that implies submission has been independently confirmed would be false.

---

## P2 — Lever / Zoho Recruit: real submission is blocked or partial by design

Lever: hCaptcha is enforced in both the backend plan-type gate and browser worker plan validator. Zoho: identity-field filling is partial and most custom questions remain surfaced as gaps; no real submission occurs. Keep capability labels honest; do not remove safety gates to improve a raw ATS count.

---

## P2 — Workday account-creation verification is unknown

`AtsCapabilityRegistry` records that the real post-creation verification mechanism (OTP, link, or neither) has not been established. It is honestly labelled `PARTIAL`; resolve only with safe evidence before expanding Workday claims.

---

## P2 — Tsenta ATS-count inconsistency not reproduced

A direct check on 2026-11-03 found both `tsenta.com` and `help.tsenta.com` consistently claimed "30+ ATSes". Do not repeat the earlier "15+ vs 30+" inconsistency absent new evidence.

---

## Not yet independently gap-analysed

Discovery freshness/dedup accuracy, matching/ranking quality, hard-filter integrity under adversarial scores, broader CV factual-accuracy rates, automation-intelligence limits, review-before-send diff quality, UX/accessibility and commercial readiness remain unscored or only partially explored. Services existing in the codebase is not proof that their outputs meet the intended product standard. Follow the evidence passes in `ROBIN_BUILD_ROADMAP.md`.
