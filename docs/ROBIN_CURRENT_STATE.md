# ROBIN — Current State

**Prepared:** 2026-11-17, updated after PR #11 merged into `master`. This state snapshot separates the merged `master` repository state, CI evidence and historical operator-reported production observations. A passing test suite is not described as production verification, and no current production health or deployment state is asserted here.

## Architecture (confirmed from source)

Java 21 / Spring Boot 3.3.4 modular monolith (`backend/pom.xml`), React 18.3.1 / TypeScript / Vite SPA (`frontend/package.json`), separate Node/Playwright browser-automation worker (`worker/`), separate Cloudflare Email Worker (`cloudflare-email-worker/`), optional Python/Scrapling sidecar (`sidecar/scrapling/`). PostgreSQL with Flyway migrations; Redis supports cache/health behavior.

## Repository and CI state (2026-11-17)

- PR #11, `phase21-candidate-knowledge-integrity` (*fix: strengthen candidate knowledge and document fact integrity*), is **merged** into `master` as merge commit `199e051d908c552ee7bc6db283998fea85a394d6` (2026-11-17 18:19 UTC). It contains the Phase 21 candidate-fact validator and PREP/CV/cover-letter changes. Its final head `0825275` (the branch with `master` merged in) passed GitHub Actions run #122: backend, browser worker, frontend and Cloudflare email worker all successful. The merge commit's tree is identical to `0825275`, and the push-triggered run #123 on `199e051` completed successfully. The earlier recorded code head `cd668c6` passed run #75; backend results for that run: Surefire 1,397 tests (0 failures, 6 skipped); Failsafe 272 tests (0 failures, 25 skipped).
- PR #11 adds no Flyway migration: its validator-version change (`fact-validator-1` → `fact-validator-2`) is enforced in application code. Migrations on `master` now run to `V054`: `V052__outbox_dispatch_exclusions.sql` arrived with the production-cutover branch (16 Nov), `V053__resume_import_previews.sql` and `V054__date_precision_metadata.sql` with PR #13.
- The PR #11 merge brought unresolved Git conflict markers into four documents (this file, `ROBIN_BUILD_ROADMAP.md`, `ROBIN_GAP_REGISTER.md` and `ROBIN_PHASE20_VERIFICATION.md`); the follow-up documentation cleanup reconciles both sides. An earlier note (2026-11-04) recorded PR #11 as 26 commits based on `6c3a72a`, with a trial merge conflicting only in `docs/ROBIN_PHASE20_VERIFICATION.md` and reconciliation planned after Phase 20 closed; PR #11 merged first, while Phase 20 is still open.
- PR #10 is merged to `master` as `6c3a72a`; it added a dedicated Node 22 GitHub Actions job for `cloudflare-email-worker`.
- PR #13 (Master Resume Import) is merged to `master` as `0ddf7dfa8954a2c838a9facb87c3aa2ba51d3ad0`. It adds PDF/DOCX resume import, source-grounded review and explicit confirmation, date precision metadata and category editing for imported skills. Its recorded CI passed backend, frontend, browser worker, Cloudflare email worker and Vercel preview status checks.
- PR #12 (Redis startup diagnostic fix, *fix(redis): stop startup diagnostic misreporting slow Redis connections as DOWN*) is merged to `master` as `2407c1b`.
- The production-cutover branch merged to `master` as `c2ffe8d` (*merge: production cutover from staging-oracle-rehearsal*, 2026-11-16): Flyway `V052`, maintenance-mode worker guards, discovery deduplication/relevance changes, migration-safety tests and Oracle scripts. Its push CI run #112 failed; the next `master` runs — #116 (`2407c1b`), #121 (`0ddf7df`) and #123 (`199e051`) — completed successfully. The merge is repository history, not evidence of the current production database or deployment.
- **Updated 2026-11-04:** all Phase 20 work through `b2be7e0` is pushed to `origin/master` (local and remote SHAs verified after each push). CI evidence per commit is kept in one place: `ROBIN_PHASE20_VERIFICATION.md` §3.1.
- CI now has four jobs — `backend`, `worker`, `frontend`, `cloudflare-email-worker` (added in `6c3a72a`; also runs the Phase 20 operator-probe tests since `b2be7e0`).
- 3 stale open PRs (#1, #7, #6 — earlier phase branches) exist on GitHub but their content is already folded into `master` via commit `1b49773`; not blocking. Re-checked 2026-11-17: these three are still the only open PRs.
- PR #9's own description states inbound Cloudflare mail delivery is explicitly unproven and Phase 20 must stay open.
- No Phase 21 production deployment or real employer submission is claimed here.

## Historical production observations (2026-11-04 to 2026-11-07; not current status)

Retained as evidence of what was observed at the time. They do not describe the current production deployment, database or health; re-check before any production action.

- **Production ran `master` (recorded 2026-11-05):** Render served `f3204f0` (built 2026-11-05 00:22 UTC) and the Cloudflare Worker served version `579c2681…` (tag `170af8b`, 2026-11-04 23:36 UTC). Render auto-deploy was off; backend deploys were manual.
- **P0 opened 2026-11-05:** the production database was rejecting writes — the Firebase session exchange and the worker's plan claim failed with `UncategorizedSQLException` while reads succeeded. Root cause confirmed 2026-11-06: Supabase Free-plan read-only mode. The 2 GB disk sat at the 95 % threshold, so writes succeeded only in short windows. See `ROBIN_GAP_REGISTER.md`, and the backup-first migration plan in `ROBIN_DB_MIGRATION_PHASE2_PLAN.md`.
- **2026-11-07:** the Render backend web service was suspended as step R1 of that migration plan (`ROBIN_DB_MIGRATION_PHASE2_PLAN.md` §10.13); the plan's latest dated entries are from 8–9 Nov 2026.

## Confirmed candidate knowledge / document path (Phase 21, merged via PR #11)

- Authentication is used to resolve the caller's profile; CV source data is loaded from structured profile records.
- `ResumeAtsIntelligenceService` renders the tailored CV deterministically from stored candidate records. Job-description content changes relevance ordering/gap reporting, not candidate facts.
- `verifiedEvidence` records carry `USER_VERIFIED` provenance. This means candidate-attested/user-provided evidence, not independent third-party verification.
- `DocumentFactValidator` is `fact-validator-2` on `master` (merged via PR #11). It checks supported claim categories and returns blockers/warnings; it is not a universal semantic truth detector.
- `ApplyDocumentSelector` requires current passing validation, artifact integrity and explicit review approval before packaging a CV for application. PR #11 also rejects approval of stale CV validation in the API and clearly surfaces stale documents in PREP.
- Cover-letter generation remains LLM-assisted. The prompt includes additional structured profile evidence, while work-eligibility data and certification credential IDs are excluded from the LLM prompt. Generated text is revalidated before approval.
- `PrepWorkflowIT` demonstrates a database-backed profile fact flowing into the CV, a provenance record and a passing validator result; it injects an unsupported PhD claim and asserts a blocking `UNSUPPORTED_QUALIFICATION` finding.

See `ROBIN_PHASE21_VERIFICATION.md` for the detailed trace, test results and limitations.

## Confirmed limitations / open work

- Phase 20 remains **OPEN**. One real email reached Cloudflare → Worker → Render → Postgres on 2026-11-03, was encrypted and appeared in Robin. Live candidate tenant isolation, duplicate redelivery, specific application association, real OTP extraction/use and the full recruiter-communication lifecycle remain production-unverified (`ROBIN_PHASE20_VERIFICATION.md` §5–§6).
- Phase 21 is merged and CI-verified, not production-verified. Its residual limitations remain: the validator is bounded and does not semantically prove every sentence; `USER_VERIFIED` means candidate-attested; employer/requirement extraction includes heuristics; work eligibility is UK-shaped; textual unit/currency matching can reject equivalent formatting; cover-letter prose is still LLM-generated; CVs validated under `fact-validator-1` must be regenerated and reviewed before use; no aggregate CV factual-accuracy rate has been measured (`ROBIN_GAP_REGISTER.md`; `ROBIN_PHASE21_VERIFICATION.md` §5).
- ATS submissions remain unverified against real employers/candidate-owned sandbox postings; do not claim `SUBMISSION_VERIFIED`.
- Workday capability is corrected to `SIGN_IN_READINESS_ONLY` in the merged baseline; it does not fill the Workday application form.
- Lever real submission remains blocked by hCaptcha; Zoho Recruit remains partial.
- Discovery freshness/dedup, matching/ranking quality, wider factual-accuracy rates, review-diff quality, automation intelligence, UX/accessibility and commercial readiness still need independent evidence passes.
- The current production deployment state is not inferred from GitHub merge status. Consult the current deployment/operations handover before production actions.

## Current safe next steps

1. Keep Phase 20's remaining live-mail proof items visible (`ROBIN_PHASE20_VERIFICATION.md` §5–§6); do not silently mark Phase 20 closed — each item must be verified in production or explicitly scope-deferred with the reason recorded.
2. Before any production action, get a fresh status check of the live deployment and database from the current operations handover; the observations above are historical.
3. Treat any production deployment of the merged Phase 21 changes as a separate, operator-approved step with its deployment impact explicitly accepted; this snapshot does not record whether it has happened.
4. Phase 22 (discovery evidence pass) was gated on Phase 21 being merged; that prerequisite is met, so it is next per `ROBIN_BUILD_ROADMAP.md`.
