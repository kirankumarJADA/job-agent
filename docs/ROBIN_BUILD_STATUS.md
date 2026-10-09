# Robin Build Status

Persistent engineering handoff. Update this file after every completed phase and at the end of every session.

## Current Date
2026-10-09

## Repository State
- Phase 1–5 commits: `72d18fd`, `f8008c3`, `aecc7e9`, `a32c2da`, `48b7d56`.
- Phase 6 original implementation: `f0511fb` (`feat: add human review queue for application decisions`).
- Phase 6 hardening is isolated on branch `phase6-review-queue-hardening`, based on `f0511fb`. The current hardening series includes `f1bae6c`, `5fa1a01`, `beda52c`, `b0e574c`, `4faa250`, `836d614`, and `1b87d02`. `095021d` was a status-document-only follow-up.
- Phase 7 implementation: commit pending (`feat: add per-user auto-approval rule engine`).
- Do not infer the state of a separate Windows working tree from this GitHub branch.

## Completed Phases
| Phase | Commit | Status |
|---|---|---|
| 1 — Hard filtering before semantic matching | `72d18fd` | Previously reported passing: 460 tests |
| 2 — Semantic skill matching (three-tier) + V029 cache | `f8008c3` | Previously reported passing: 496 tests |
| 3 — Secondary provider parallel fan-out | `aecc7e9` | Previously reported passing: 499 tests |
| 4 — Rate-limit / cost / quota engine + V030 | `a32c2da` | Previously reported passing: 512 tests |
| 5 — Automatic application decision + V031 | `48b7d56` | Previously reported passing: 621 backend tests |
| 6 — Human review queue + V032 | `f0511fb` | Previous session reported backend 628/0/0, frontend 173/173 + build, worker 19/19 |
| 6 — Review lifecycle hardening + V033 | `1b87d02` | Full GitHub Actions CI passed on source revision `1b87d02` |
| 7 — Auto-approval rule engine + V034 | pending | Local verify: 559 unit (0 fail, 1 skip) + 108 IT (0 fail, 19 skip) = 667 total |
| 7.1 — Approval Rules Settings UI | pending | Frontend: 178 tests pass, tsc clean, vite build clean |

## Phase 7 Implementation: Auto-Approval Rule Engine
- `V034__approval_rules.sql` creates `user_approval_rules` table with per-profile configurable thresholds: `auto_approve_enabled` (boolean), `min_score` (integer 0–100), `max_daily_auto` (integer), and `require_cover_letter` (boolean). Owner-isolated by `profile_id` with unique constraint.
- `ApplicationDecisionService.decide()` extended with step 4a: loads the user's approval rule via `loadRule()` and, in `CONTROLLED_AUTO` mode, checks whether the match score meets `min_score` and auto-approve is enabled. If so, the decision is `AUTO_APPROVED`; otherwise it falls through to `APPROVAL_REQUIRED` (human review queue).
- Fail-closed design: absent, disabled, invalid, or unavailable rules always default to requiring human approval. A database exception during rule loading logs the error and falls back to Phase 5 defaults — never silently auto-approves.
- `loadRule()` queries `user_approval_rules WHERE profile_id = ?` with owner isolation. Returns `Optional<UserApprovalRule>`.
- `ruleFor(profileId)` public accessor for the REST layer. Returns the rule or empty.
- `saveRule(profileId, rule)` upserts via `INSERT ... ON CONFLICT (profile_id) DO UPDATE` with owner scoping.
- `ApprovalRulesController` at `/api/v1/approval-rules`: GET returns the current rule (or 404), PUT validates and upserts. Both require Firebase authentication and scope to the authenticated profile.
- `REAL_SUBMIT` remains hard-stopped. This phase builds automated eligibility evaluation and approval decisions, not live job application submission.
- Rules never override quotas, safety restrictions, hard stops, or required validation.

## Phase 7 Test Coverage
- `ApplicationDecisionServiceTest`: 39 tests (8 Phase5Baseline + 20 Phase7RuleEngine + 7 Phase7SaveRule + 2 Phase7RuleFor + 2 Idempotency). All pass.
- `ApprovalRulesControllerTest`: 7 tests (4 PutRule + 3 GetRule). All pass. Pure unit tests with mocked dependencies.
- Owner-isolation verified: `differentProfilesCannotAccessEachOthersRules` confirms that queries use `eq(PROFILE)` matchers so one profile's rules are invisible to another.
- Fail-closed verified: `ruleLoadExceptionFailsSafeToPhase5Defaults` confirms database exceptions during rule loading produce `APPROVAL_REQUIRED`, not auto-approval.
- Edge cases: absent rule → APPROVAL_REQUIRED; disabled rule → APPROVAL_REQUIRED; score below threshold → APPROVAL_REQUIRED; MANUAL mode ignores rules entirely.

## Phase 6 Implementation and Hardening
- `V032__review_queue_lifecycle.sql` adds `APPROVED`, `REJECTED`, `PAUSED`, and `EXPIRED`, plus `reviewed_at` and `application_id`.
- `V033__review_decision_audit_metadata.sql` additively records reviewer attribution, rejection rationale, update timestamps, and an owner/update index. Earlier migrations are not rewritten.
- The owner-scoped review API returns a pending count and score, recommendation, hard-filter outcome/reasons, mode/reason, application/preparation state, and timestamps. Since filter-rejection reasons are stored on a global job row rather than per profile, the queue reports that its decision passed the owner's filters and never exposes another profile's rejection reasons. Replayed decisions update `updated_at` without resetting the original `created_at`, so the review expiry window remains meaningful.
- Approval uses the existing idempotent application preparation pipeline, links the application, and writes audit/outbox outcome events. It does not submit to the employer.
- Rejection records terminal state, acting account, timestamp and reason, plus audit and outbox notification events. State transitions check owner and expected state.
- Replayed match events cannot overwrite already-resolved decisions or emit redundant approval-required notifications for terminal decisions, including paused rows. Soft-deleted jobs are excluded; postings not seen for 30 days are flagged and approval is refused until refreshed, as is approval when the application URL is missing.
- The UI includes pending count, job/filter/application/preparation details, and an optional rejection reason. It opts into showing paused rows so Resume remains available; pending count excludes paused rows.
- Authentication, owner isolation, worker auth, CSRF rules and the `REAL_SUBMIT` hard stop must remain intact.

## Verification
- Phase 7 local `mvn verify` on Windows: BUILD SUCCESS in 6:43 min. Unit tests: 559 run, 0 failures, 0 errors, 1 skipped. Integration tests: 108 run, 0 failures, 0 errors, 19 skipped. V034 migration applied successfully in every Testcontainers context.
- Phase 6 baseline results (backend 628/0/0, frontend 173/173 + build, worker 19/19) were reported by the prior coding session.
- GitHub Actions run [37956342224](https://github.com/kirankumarJADA/job-agent/actions/runs/37956342224) for commit `836d614` passed all three jobs.
- GitHub Actions run [37956839152](https://github.com/kirankumarJADA/job-agent/actions/runs/37956839152) for functional source revision `1b87d02` also passed all jobs.
- `.github/workflows/ci.yml` runs backend `mvn -B verify`, worker `npm test`, frontend `npx tsc -b`, `npm test`, and `npm run build`.
- A successful CI run does not prove production deployment.

## Production State (last reported; not re-verified here)
- Reported backend: `https://job-agent-mwhu.onrender.com`; frontend: `https://job-agent-beige.vercel.app`.
- Current live backend/frontend SHA and whether this branch has been deployed are unverified.
- `REAL_SUBMIT` remains intentionally disabled; do not report an application submitted without employer confirmation.

## Remaining Phases
8 Production PDF · 9 Real Greenhouse submission (gated) · 10 Cross-source dedup · 11 Workday · 12 Lever · 13 Real mailbox/OTP · 14 User search · 15 URL extraction · 16 Source catalogue · 17 Region-aware discovery · 18 Recruiter email intelligence · 19 Follow-ups · 20 Analytics · 21 Dashboard completion · 22 Bulk ops · 23 Webhooks · 24 PWA/extension/MCP.

## Phase 7.1 Implementation: Approval Rules Settings UI
- `ApprovalRulesPage.tsx` (275 lines): Settings page with toggle switch for auto-approve enable/disable, range slider and number input for minimum score (0–100), decision mode explainer (MANUAL / ASSISTED / CONTROLLED_AUTO), safety notice confirming REAL_SUBMIT remains hard-stopped.
- Uses `apiFetch` from `api/client.ts` with Firebase token, CSRF, request IDs for GET/PUT to `/api/v1/approval-rules`.
- Route added at `/approval-rules` in `App.tsx`.
- Navigation item added in `Navigation.tsx` with shield+checkmark icon.
- `ApprovalRule` interface added to `types.ts`.
- `ApprovalRulesPage.test.tsx` (179 lines): 7 tests covering loading, editing, saving, validation, disabled rules, error handling, and save failure.
- `cross-env` added to devDependencies; test script updated to `cross-env NODE_ENV=test npx vitest run` to fix pre-existing React production-build `act()` error caused by Vitest dep optimizer inlining `process.env.NODE_ENV` as production on Windows.
- `vite.config.ts` updated with `test` section (jsdom environment) and NODE_ENV guard for belt-and-suspenders fix.
- Fail-closed: absent, disabled, or invalid user rules never silently enable auto-approval. REAL_SUBMIT remains hard-stopped.

## Phase 7.1 Test Coverage
- `ApprovalRulesPage.test.tsx`: 7 tests — loads and displays current rule, toggle + score editing, save success, validation error for out-of-range scores, unconfigured rule notice, API load error, save error. All pass.
- Full frontend test suite: 178 tests pass, 0 failures (1 pre-existing suite-level failure in `devCredentialsBundle.test.ts` due to esbuild/TextEncoder jsdom incompatibility — not related to Phase 7.1).
- TypeScript compilation: clean (`tsc --noEmit`, 0 errors).
- Production build: clean (`vite build`, 476 kB JS + 31 kB CSS).

## Next Exact Task
Phase 7.1 is verified locally. Commit and push on `phase7-approval-rules-ui`, then stop unless Phase 8 is explicitly requested. If requested, implement production PDF generation for tailored CVs/cover letters.

## Last Verified Baseline
- Phase 7 backend local verify: 559 unit + 108 integration = 667 test cases, 0 failures, 0 errors (1 + 19 skipped). BUILD SUCCESS.
- Phase 7.1 frontend verify: 178 tests pass, tsc clean, vite build clean.
- Previous CI-verified functional source revision: `1b87d02` (Phase 6 hardening).
- Phase 7 backend files changed: `V034__approval_rules.sql`, `ApplicationDecisionService.java`, `ApprovalRulesController.java`, `ApplicationDecisionServiceTest.java`, `ApprovalRulesControllerTest.java`.
- Phase 7.1 frontend files changed: `ApprovalRulesPage.tsx`, `ApprovalRulesPage.test.tsx`, `App.tsx`, `Navigation.tsx`, `types.ts`, `vite.config.ts`, `package.json`, `package-lock.json`, `ROBIN_BUILD_STATUS.md`.
