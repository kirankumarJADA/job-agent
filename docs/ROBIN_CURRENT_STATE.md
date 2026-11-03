# ROBIN — Current State

**Prepared:** 2026-11-03. This snapshot separates the merged `master` state from the open Phase 21 PR and from operator-reported production evidence. A passing test suite is not described as production verification.

## Architecture (confirmed from source)

Java 21 / Spring Boot 3.3.4 modular monolith (`backend/pom.xml`), React 18.3.1 / TypeScript / Vite SPA (`frontend/package.json`), separate Node/Playwright browser-automation worker (`worker/`), separate Cloudflare Email Worker (`cloudflare-email-worker/`), optional Python/Scrapling sidecar (`sidecar/scrapling/`). PostgreSQL with Flyway migrations; Redis supports cache/health behavior.

## Repository and CI state

- `master` baseline: `6c3a72a0150a9fca08df9a21536b88c6ae9a0284`.
- PR #10 is merged as `6c3a72a`. It adds a dedicated Node 22 GitHub Actions job for `cloudflare-email-worker`; run #62 passed all four jobs.
- PR #11, `phase21-candidate-knowledge-integrity`, contains the Phase 21 candidate-fact validator and PREP/CV/cover-letter changes. The code head `cd668c6` passed GitHub Actions run #75: backend, browser worker, frontend and Cloudflare email worker all successful. The backend results were Surefire 1,397 tests (0 failures, 6 skipped) and Failsafe 272 tests (0 failures, 25 skipped).
- PR #11 is **not merged**. Documentation updates have since been added to the same PR branch; those doc-only commits need the latest branch CI to complete before PR closure.
- No Phase 21 production deployment or real employer submission was performed.

## Confirmed candidate knowledge / document path

- Authentication is used to resolve the caller's profile; CV source data is loaded from structured profile records.
- `ResumeAtsIntelligenceService` renders the tailored CV deterministically from stored candidate records. Job-description content changes relevance ordering/gap reporting, not candidate facts.
- `verifiedEvidence` records carry `USER_VERIFIED` provenance. This means candidate-attested/user-provided evidence, not independent third-party verification.
- `DocumentFactValidator` is now `fact-validator-2` on the PR branch. It checks supported claim categories and returns blockers/warnings; it is not a universal semantic truth detector.
- `ApplyDocumentSelector` requires current passing validation, artifact integrity and explicit review approval before packaging a CV for application. The PR also rejects approval of stale CV validation in the API and clearly surfaces stale documents in PREP.
- Cover-letter generation remains LLM-assisted. The prompt now includes additional structured profile evidence, while work-eligibility data and certification credential IDs are excluded from the LLM prompt. The generated text is revalidated before approval.
- `PrepWorkflowIT` demonstrates a database-backed profile fact flowing into the CV, a provenance record and a passing validator result; it injects an unsupported PhD claim and asserts a blocking `UNSUPPORTED_QUALIFICATION` finding.

See `ROBIN_PHASE21_VERIFICATION.md` for the detailed trace, test results and limitations.

## Confirmed limitations / open work

- Phase 20 is still **open**. One real email reached Cloudflare → Worker → Render → Postgres, was encrypted and appeared in Robin. Live duplicate redelivery, specific application association, real OTP extraction/use and the full recruiter-communication lifecycle remain production-unverified (`ROBIN_PHASE20_VERIFICATION.md` §4.3).
- ATS submissions remain unverified against real employers/sandbox postings; do not claim `SUBMISSION_VERIFIED`.
- Workday capability is corrected to `SIGN_IN_READINESS_ONLY` in the merged master baseline; it does not fill the Workday application form.
- Lever real submission remains blocked by hCaptcha; Zoho Recruit remains partial.
- Discovery freshness/dedup, matching/ranking quality, wider factual-accuracy rates, review-diff quality, automation intelligence, UX/accessibility and commercial readiness still need independent evidence passes.

## Current safe next steps

1. Complete the latest PR #11 CI after its documentation updates.
2. Review/merge PR #11 only after the final branch head is green and deployment impact is explicitly accepted.
3. Keep Phase 20's remaining live-mail proof items visible; do not silently mark Phase 20 closed.
4. Start Phase 22 only after Phase 21 is merged and formally closed.
