# ROBIN — Current State

**Prepared:** 2026-11-17. This state snapshot separates the current merged `master` baseline, the open Phase 21 PR, and operator-reported production evidence. A passing test suite is not described as production verification.

## Architecture (confirmed from source)

Java 21 / Spring Boot 3.3.4 modular monolith (`backend/pom.xml`), React 18.3.1 / TypeScript / Vite SPA (`frontend/package.json`), separate Node/Playwright browser-automation worker (`worker/`), separate Cloudflare Email Worker (`cloudflare-email-worker/`), optional Python/Scrapling sidecar (`sidecar/scrapling/`). PostgreSQL with Flyway migrations; Redis supports cache/health behavior.

## Repository and CI state

<<<<<<< HEAD
- PR #10 is merged to `master` as `6c3a72a`; it added a dedicated Node 22 GitHub Actions job for `cloudflare-email-worker`.
- PR #13 (Master Resume Import) is merged to `master` as `0ddf7dfa8954a2c838a9facb87c3aa2ba51d3ad0`. It adds PDF/DOCX resume import, source-grounded review and explicit confirmation, date precision metadata and category editing for imported skills. Its recorded CI passed backend, frontend, browser worker, Cloudflare email worker and Vercel preview status checks.
- PR #11, `phase21-candidate-knowledge-integrity`, contains the Phase 21 candidate-fact validator and PREP/CV/cover-letter changes. The recorded code head `cd668c6` passed GitHub Actions run #75: backend, browser worker, frontend and Cloudflare email worker all successful. Backend results for that recorded run: Surefire 1,397 tests (0 failures, 6 skipped); Failsafe 272 tests (0 failures, 25 skipped). The PR also carries later documentation and is now being reconciled against the newer `master` baseline. CI for its final reconciled head must pass before merge.
- No Phase 21 production deployment or real employer submission is claimed here.
=======
- **Updated 2026-11-04:** all Phase 20 work through `b2be7e0` is pushed to `origin/master` (local and remote SHAs verified after each push). CI evidence per commit is kept in one place: `ROBIN_PHASE20_VERIFICATION.md` §3.1.
- CI now has four jobs — `backend`, `worker`, `frontend`, `cloudflare-email-worker` (added in `6c3a72a`; also runs the Phase 20 operator-probe tests since `b2be7e0`).
- **Production runs `master`:** Render serves `f3204f0` (built 2026-11-05 00:22 UTC) and the Cloudflare Worker serves version `579c2681…` (tag `170af8b`, 2026-11-04 23:36 UTC). Render auto-deploy is off; backend deploys are manual.
- **Open P0 (2026-11-05):** the production database is rejecting writes — the Firebase session exchange and the worker's plan claim fail with `UncategorizedSQLException` while reads succeed. Root cause confirmed 2026-11-06: Supabase Free-plan read-only mode. The 2 GB disk sits at the 95 % threshold, so writes succeed only in short windows. See `ROBIN_GAP_REGISTER.md`, and the backup-first migration plan in `ROBIN_DB_MIGRATION_PHASE2_PLAN.md`.
- 3 stale open PRs (#1, #7, #6 — earlier phase branches) exist on GitHub but their content is already folded into `master` via commit `1b49773`; not blocking.
- PR #11 (`phase21-candidate-knowledge-integrity`, 26 commits, based on `6c3a72a`) holds the Phase 21 implementation. A trial merge into current `master` conflicts only in `docs/ROBIN_PHASE20_VERIFICATION.md`; it is reconciled after Phase 20 closes.
- PR #9's own description states inbound Cloudflare mail delivery is explicitly unproven and Phase 20 must stay open.
>>>>>>> origin/master

## Confirmed candidate knowledge / document path (PR #11)

- Authentication is used to resolve the caller's profile; CV source data is loaded from structured profile records.
- `ResumeAtsIntelligenceService` renders the tailored CV deterministically from stored candidate records. Job-description content changes relevance ordering/gap reporting, not candidate facts.
- `verifiedEvidence` records carry `USER_VERIFIED` provenance. This means candidate-attested/user-provided evidence, not independent third-party verification.
- `DocumentFactValidator` is `fact-validator-2` on the PR branch. It checks supported claim categories and returns blockers/warnings; it is not a universal semantic truth detector.
- `ApplyDocumentSelector` requires current passing validation, artifact integrity and explicit review approval before packaging a CV for application. The PR also rejects approval of stale CV validation in the API and clearly surfaces stale documents in PREP.
- Cover-letter generation remains LLM-assisted. The prompt includes additional structured profile evidence, while work-eligibility data and certification credential IDs are excluded from the LLM prompt. Generated text is revalidated before approval.
- `PrepWorkflowIT` demonstrates a database-backed profile fact flowing into the CV, a provenance record and a passing validator result; it injects an unsupported PhD claim and asserts a blocking `UNSUPPORTED_QUALIFICATION` finding.

See `ROBIN_PHASE21_VERIFICATION.md` for the detailed trace, test results and limitations.

## Confirmed limitations / open work

- Phase 20 remains **OPEN**. One real email reached Cloudflare → Worker → Render → Postgres, was encrypted and appeared in Robin. Live duplicate redelivery, specific application association, real OTP extraction/use and the full recruiter-communication lifecycle remain production-unverified (`ROBIN_PHASE20_VERIFICATION.md` §4.3).
- ATS submissions remain unverified against real employers/candidate-owned sandbox postings; do not claim `SUBMISSION_VERIFIED`.
- Workday capability is corrected to `SIGN_IN_READINESS_ONLY` in the merged baseline; it does not fill the Workday application form.
- Lever real submission remains blocked by hCaptcha; Zoho Recruit remains partial.
- Discovery freshness/dedup, matching/ranking quality, wider factual-accuracy rates, review-diff quality, automation intelligence, UX/accessibility and commercial readiness still need independent evidence passes.
- The current production deployment state is not inferred from GitHub merge status. Consult the current deployment/operations handover before production actions.

## Current safe next steps

1. Finish reconciling PR #11's four documentation conflicts against the latest `master`.
2. Run the full CI workflow for the reconciled PR #11 head and inspect every job.
3. Review/merge PR #11 only after final branch CI is green and deployment impact is explicitly accepted.
4. Keep Phase 20's remaining live-mail proof items visible; do not silently mark Phase 20 closed.
5. Start Phase 22 only after Phase 21 is merged and formally closed.
