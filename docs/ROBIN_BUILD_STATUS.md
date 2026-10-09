# Robin Build Status

Persistent engineering handoff. Update this file after every completed phase and at the end of every session.

## Current Date
2026-10-09

## Repository State
- Phase 1–5 commits: `72d18fd`, `f8008c3`, `aecc7e9`, `a32c2da`, `48b7d56`.
- Phase 6 original implementation: `f0511fb` (`feat: add human review queue for application decisions`).
- Phase 6 hardening is isolated on branch `phase6-review-queue-hardening`, based on `f0511fb`. The current hardening series includes `f1bae6c`, `5fa1a01`, `beda52c`, `b0e574c`, `4faa250`, `836d614`, and `1b87d02`. `095021d` was a status-document-only follow-up.
- Phase 7 (auto-approval rule engine + V034): `568994a`.
- Phase 7.1 (Approval Rules Settings UI): `daa848d`, on branch `phase7-approval-rules-ui`.
- Phase 7.2 (auto-approval safety audit + fail-closed correction): `bb449a4`; status record `9f2f4e2`.
- Phase 7.3 (approval-rule health and observability): commit pending on `phase7-approval-rules-ui`.
- `ROBIN_PROJECT_HANDOFF.md` does not exist in this repository (checked all branches and history). This file is the persistent engineering handoff.
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
| 7 — Auto-approval rule engine + V034 | `568994a` | Local verify: 559 unit (0 fail, 1 skip) + 108 IT (0 fail, 19 skip) = 667 total |
| 7.1 — Approval Rules Settings UI | `daa848d` | Frontend: 178 tests pass, tsc clean, build clean (one pre-existing suite failure, fixed in 7.2) |
| 7.2 — Auto-approval safety audit (fail-closed) | `bb449a4` | Backend: 564 unit (0 fail, 1 skip) + 109 IT (0 fail, 19 skip) = 673. Frontend: 185 tests, tsc clean, build clean. Worker: 19/19 |
| 7.3 — Approval-rule health & observability | pending | Backend: 592 unit (0 fail, 1 skip) + 110 IT (0 fail, 19 skip) = 702. Frontend: 190 tests, tsc clean, build clean. Worker: 19/19 |

## Phase 7 Implementation: Auto-Approval Rule Engine (corrected here in 7.2)
- `V034__approval_rules.sql` creates `user_approval_rules` with exactly three value columns: `auto_approve_enabled` (boolean, default false) and `min_score` (integer, default 85, `CHECK (min_score BETWEEN 1 AND 100)`), plus `updated_at`. (Earlier drafts of this document invented `max_daily_auto` and `require_cover_letter` columns that were never created; they do not exist.) Owner-isolated by `profile_id` with a unique constraint.
- `ApplicationDecisionService.decide()` records the decision engine outcome as `AUTO_APPLY`, `NEEDS_REVIEW`, `SKIP`, or a later human state — never `AUTO_APPROVED`/`APPROVAL_REQUIRED`, which were earlier naming errors in this document. Every decision is persisted to `application_decisions`.
- `saveRule(profileId, autoApproveEnabled, minScore)` upserts via `INSERT ... ON CONFLICT (profile_id) DO UPDATE` with owner scoping and validates the score is 1–100.
- `ApprovalRulesController` at `/api/v1/approval-rules`: GET returns the current rule plus `configured` (true only when a row exists); PUT validates and upserts. Both are owner-scoped from the authenticated identity — no id in a request body is ever trusted. Mutations write an audit entry.
- `REAL_SUBMIT` remains hard-stopped. This phase builds automated eligibility evaluation and approval decisions, not live job application submission.
- Rules never override quotas, safety restrictions, hard stops, or required validation.
- The trailing comment in `V034__approval_rules.sql` still claims "CONTROLLED_AUTO auto-applies" when no row exists. That statement is superseded by Phase 7.2 below. The migration file is deliberately left byte-for-byte unchanged: it is already applied, and editing it (even a comment) would change its Flyway checksum and break validation on every existing database.

## Phase 7.2 Implementation: Fail-Closed Decision Handling (this phase)
- **The defect.** `loadRule()` returned `null` both when no rule existed and when rule loading failed, and `CONTROLLED_AUTO` treated a `null` rule as "auto-apply every APPLY-level match". So a missing rule — or a database failure that made the rule unreadable — silently enabled automatic approval without a successfully loaded, explicitly enabled rule.
- **Rule resolution is now tri-state.** `lookupRule(profileId)` returns `CONFIGURED`, `ABSENT`, or `UNREADABLE`, so "no rule" is never conflated with "a rule we could not read". A query exception, a missing/unparseable `min_score`, or a threshold outside 1–100 resolves to `UNREADABLE`.
- **Fail-closed outcomes:**
  - `UNREADABLE` → `NEEDS_REVIEW` in every automatic mode. It never reverts to a default that could auto-approve a match the owner had disabled.
  - A configured, `auto_approve_enabled = false` rule → `NEEDS_REVIEW` in both automatic modes.
  - `CONTROLLED_AUTO` with `ABSENT` → `NEEDS_REVIEW`. "Auto-apply everything" is now an explicit opt-in, never the silent default. **This is the Phase 7.2 behaviour change.**
  - `CONTROLLED_AUTO` with an enabled rule → `AUTO_APPLY` only when `matchScore >= min_score`.
  - `ASSISTED` with an enabled rule → `AUTO_APPLY` only when `matchScore >= min_score`; with `ABSENT`, the mode's own conservative floor of 85 applies (documented Phase 5 default, stricter than the 70 APPLY threshold).
  - `MANUAL` → `NEEDS_REVIEW` unconditionally, before any rule is consulted.
  - `SKIP`/`REVIEW` recommendations are returned before the rule step in every mode.
- **Quota ordering.** The daily quota gate runs before the rule is loaded, so a rule can never authorise approval past the quota. This is asserted directly (`quotaIsCheckedEvenWhenAnEnabledRuleWouldOtherwiseApprove`).
- **Migration strategy (explicit, not silent).** No schema change is needed: existing rows keep their meaning. What changes is the *absence* of a row in `CONTROLLED_AUTO`. Any deployment that relied on the old "no rule → auto-apply everything" default must now save an explicitly enabled rule, or matches queue for human review. This was chosen over preserving the old default because the old default is exactly the fail-open behaviour the audit required removing. The three pipelines that asserted the old default (`ApplicationPipelineIT`, `DiscoveryToApplicationIT`, `AutomationLifecycleIT`) now seed an explicitly enabled rule to represent the opted-in posture, and a new IT (`controlledAutoWithoutARuleQueuesForReviewInsteadOfAutoApplying`) proves the fail-closed path end to end.
- **Safeguards unchanged.** Hard stops, required-field validation, artifact-integrity checks, duplicate protection, idempotent upserts, owner isolation, and the `REAL_SUBMIT` hard stop are untouched by this change.

## Phase 7.3 Implementation: Approval Rule Health and Observability (this phase)
- **The gap.** Phase 7.2 made an unreadable rule fail closed, but silently. The API reported it as an ordinary unconfigured rule, the UI showed no settings at all, and nothing told the owner their automatic approval had stopped working.
- **API — availability, not a boolean.** `GET /api/v1/approval-rules` now reports `availability`:
  - `CONFIGURED` / `ABSENT` → **200** with `{availability, configured, autoApproveEnabled, minScore, applicationMode, assistedFloor}`. `configured` is retained for existing consumers and is exactly `availability == CONFIGURED`.
  - `UNREADABLE` → **503** with `{availability: "UNREADABLE", reason, message, applicationMode, assistedFloor}` and deliberately no `autoApproveEnabled`/`minScore`: there are no settings that can be honestly reported, and echoing defaults would invite a client to treat them as loaded state. A database or validation error can no longer be represented as a successfully loaded, unconfigured rule.
  - `reason` is the bounded enum `QUERY_FAILED` / `INVALID_THRESHOLD`. The raw database message is never returned or alerted on — a JDBC failure message can name a host, port and role.
  - `applicationMode` is the mode `ApplicationDecisionService` would actually apply (ASSISTED when unset or blank), and `assistedFloor` is sourced from the engine's own constant, so clients describe the real default behaviour instead of guessing.
  - `PUT` is unchanged apart from reporting `availability: CONFIGURED` on success.
- **Decision engine.** The private `RuleLookup` became the public `RuleState(availability, rule, unreadableCause)`; `ruleState(profileId)` exposes it to the API, and `effectiveApplicationMode(profileId)` centralises the mode default `decide()` uses. Decision semantics are unchanged — the reporting is strictly additive and is called *after* the fail-closed branch is chosen.
- **Metrics** (through the existing `AutomationMetrics` Micrometer seam, all labels bounded):
  - `robin_approval_rule_lookups_total{availability}` — one per decision-time lookup; CONFIGURED / ABSENT / UNREADABLE.
  - `robin_auto_approval_withheld_total{reason}` — RULE_UNREADABLE / RULE_DISABLED / NO_RULE_IN_CONTROLLED_AUTO. Threshold and quota withholdings are excluded on purpose: they are already visible in `robin_application_decisions_total`.
  - `robin_approval_rule_alerts_total{outcome}` — EMITTED / DEDUPED / PERSIST_FAILED.
  - No profile id, job id, or email address is ever a metric label; those appear only in server-side log lines and in the owner's own notification.
- **Owner-visible alert.** A new `ApprovalRuleHealthMonitor` raises the alert through the existing outbox fan-out, so it reuses the whole notification mechanism (`outbox_events` → `NotificationEventHandler` → `notifications`) with a new event type `approval_rule.unavailable` (severity WARN, category APPROVAL_RULE_UNAVAILABLE, link `/approval-rules`, owner resolved from the payload `profile_id`). **No new persistence and no migration were required** — the existing table is already durable, owner-scoped and de-duplicated.
  - Noise control is two-layered: an in-process guard permits at most one *attempt* per owner per UTC hour (a burst of matched jobs produces one alert, not one per job), and the `dedup_key` carries a UTC-day bucket, so the partial unique index on `notifications.dedup_key` holds the owner to at most one row per day across processes and restarts. The guard map is capped at 10 000 windows and is cleared on overflow; clearing can only cause a few extra collapsed attempts, never a duplicate notification.
- **Honest about database-outage limits.** The durable alert is *attempted*, never guaranteed: the same outage that makes the rule unreadable usually blocks the outbox write too. When the emit fails, the failure is logged and counted as `PERSIST_FAILED` and no durable alert is claimed. Metrics and logs still record it because neither touches the database. A failed attempt is still rate-limited, so a sustained outage costs one log line and one metric increment per owner per hour instead of one per matched job.
  - Known limitation: a failed attempt is not retried until the next hour window, so a transient outage can delay the owner's alert by up to an hour.

## Phase 7 / 7.2 Test Coverage
- `ApplicationDecisionServiceTest` (unit): 44 tests — the original Phase 5 baseline plus rule-engine, threshold-boundary, quota, owner-isolation, save-validation, and idempotency coverage. Phase 7.2 adds `ruleLoadExceptionInControlledAutoNeverAutoApproves`, `outOfRangeStoredThresholdFailsClosed`, `nonNumericStoredThresholdFailsClosed`, `anotherProfilesDisabledRuleCannotForceReviewOnThisProfile`, and `quotaIsCheckedEvenWhenAnEnabledRuleWouldOtherwiseApprove`, and rewrites the two tests that previously asserted the fail-open default (`missingRuleInControlledAutoQueuesForReview`, `ruleLoadExceptionFailsClosedInBothAutomaticModes`).
- `ApprovalRulesControllerTest` (unit): 7 tests. GET returns `configured=false` with the default score when no rule exists, and the stored rule otherwise; PUT validates and audits; both are owner-scoped.
- `ApplicationPipelineIT` (integration): 8 tests, including the new fail-closed proof.
- Owner isolation: queries are matched on the owner's `profile_id`, so one profile's rule is invisible to another.
- Edge cases covered: absent rule → review in CONTROLLED_AUTO; unreadable rule → review in both automatic modes; out-of-range or non-numeric stored threshold → review; disabled rule → review in both automatic modes; score exactly at / one below the threshold; quota exhaustion → review; MANUAL ignores rules entirely.

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
- Phase 7.3 local `mvn -B -o verify` on Windows: BUILD SUCCESS. Unit tests: 592 run, 0 failures, 0 errors, 1 skipped. Integration tests: 110 run, 0 failures, 0 errors, 19 skipped. Total 702 test cases, 0 failures/errors.
- Phase 7.3 frontend: `npx tsc -b` clean; `npm test` 190 passed (17 files); `npm run build` clean.
- Phase 7.3 worker: `npm test` 19 passed, 0 failed.
- Phase 7 local `mvn verify` on Windows: BUILD SUCCESS in 6:43 min. Unit tests: 559 run, 0 failures, 0 errors, 1 skipped. Integration tests: 108 run, 0 failures, 0 errors, 19 skipped. V034 migration applied successfully in every Testcontainers context.
- Phase 7.2 local `mvn -B -o verify` on Windows: BUILD SUCCESS. Unit tests: 564 run, 0 failures, 0 errors, 1 skipped. Integration tests: 109 run, 0 failures, 0 errors, 19 skipped. Total 673 test cases, 0 failures/errors.
- Phase 7.2 frontend: `npx tsc -b` clean; `npm test` 185 passed (17 files); `npm run build` clean.
- Phase 7.2 worker: `npm test` 19 passed, 0 failed — the `REAL_SUBMIT` hard-stop test still passes.
- An intermediate Phase 7.2 `mvn verify` failed before the integration seeds were corrected: `ApplicationPipelineIT` (6 errors), `DiscoveryToApplicationIT` (1 failure, 2 errors), `AutomationLifecycleIT` (1 failure, 3 errors). All were the intended consequence of the fail-closed change (they assumed the old no-rule auto-apply) and were resolved by seeding an explicit enabled rule; the final run above is green.
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

## Phase 7.1 Implementation: Approval Rules Settings UI (corrected here in 7.2)
- `ApprovalRulesPage.tsx`: settings page with a toggle for auto-approve enable/disable, range slider and number input for the minimum score (1–100), a decision-mode explainer (MANUAL / ASSISTED / CONTROLLED_AUTO), and a safety notice confirming `REAL_SUBMIT` remains hard-stopped.
- Uses `apiFetch` from `api/client.ts` (Firebase token, CSRF, request IDs) and the shared `ApprovalRule` type from `types.ts` for GET/PUT to `/api/v1/approval-rules`.
- Route added at `/approval-rules` in `App.tsx`; navigation item in `Navigation.tsx`.
- `cross-env` added to devDependencies; test script is `cross-env NODE_ENV=test npx vitest run`, which fixes the Windows React production-build `act()` error without mutating the environment at config-load time.

## Phase 7.2 Frontend Corrections (this phase)
- **Unconfigured vs. disabled is now explicit.** The page renders three states — *Not configured* (no rule row), *Disabled* (a saved rule with auto-approval off), and *Enabled* — each with its own explanation. Earlier it rendered "Disabled" for an unconfigured rule and described a Phase 5 default that no longer applies to Controlled Auto.
- **An untouched default form can no longer save a disabled rule.** Save is blocked until the owner makes a deliberate change (`configured || dirty`), with copy explaining why. Previously, loading an unconfigured rule (which reads back as `autoApproveEnabled: false`) and clicking Save would persist an explicitly disabled rule the owner never chose.
- **Mode copy matches the backend.** Controlled Auto is described as requiring an enabled rule — with no rule saved, or a disabled one, every match goes to review; Assisted keeps its built-in floor of 85. The page also states that enabling a rule never overrides a safety check or the quota, and that an unreadable rule falls back to human review.
- **`vite.config.ts` cleaned up.** The Phase 7.1 additions were removed: the global `test.environment: "jsdom"` (which broke `devCredentialsBundle.test.ts`, whose esbuild run asserts `new TextEncoder().encode("") instanceof Uint8Array` and cannot run under jsdom) and the `process.env.NODE_ENV = "test"` guard (which fired during `npm run dev` too, mutating the environment for normal development, and was redundant once the test script sets `NODE_ENV=test`). The suites that need a DOM opt in with `// @vitest-environment jsdom`; everything else runs on the default node environment. No global override remains.
- No backend API change was required: GET `/api/v1/approval-rules` already returns a `configured` flag, which is what distinguishes an unconfigured rule from a configured one. One known limitation: a rule row that exists but is unreadable is reported as `configured=false`, because `ruleFor()` returns `null` for both absent and unreadable rules. This is safe (decisions already fail closed to review) but is not yet surfaced distinctly in the API.

## Phase 7.1 / 7.2 / 7.3 Test Coverage
- `ApprovalRulesPage.test.tsx`: 17 tests — loading/display, editing, save success, score validation, unconfigured-vs-disabled distinction, the saved-but-off state, blocked save on an untouched unconfigured form, save only after an explicit choice, the absent-state explanation following the real decision mode (Manual / Assisted / Controlled Auto), the unreadable warning with the form hidden and a working retry, a generic load failure reported as an outage rather than an unreadable rule, the notification promise in the safety copy, and load/save error handling. All pass.
- Full frontend suite: 17 files, 190 tests, 0 failures. This includes `devCredentialsBundle.test.ts` (2 tests), which failed before Phase 7.2 and now passes.
- TypeScript: clean (`tsc -b`, 0 errors). Production build: clean (`vite build`, 480.35 kB JS + 30.79 kB CSS).

## Phase 7.3 Test Coverage
- `ApprovalRulesControllerTest` (unit, 11 tests): the configured, absent and unreadable GET responses; that an unreadable rule is a 503 with an explicit status and carries no settings; the bounded reason; the effective mode; PUT validation, audit and owner scoping.
- `ApprovalRuleHealthMonitorTest` (unit, 11 tests): one bounded label per availability; a captor assertion that every label is a member of the enum set and never the profile id; withheld-reason labels; a 25-decision burst collapsing to one alert; per-owner isolation; no alert on healthy lookups; no raw failure text in the alert; the invalid-threshold wording; and `PERSIST_FAILED` reported (without throwing, and without claiming delivery) when the outbox write fails, including rate-limiting of failed attempts.
- `NotificationEventHandlerTest` (+1 test): the new `approval_rule.unavailable` event maps to an owner-scoped WARN notification that honours the producer's `dedup_key`.
- `ApplicationDecisionServiceTest` (+8 tests): every decision-time lookup reported with its resolved state; unreadable disabled-rule and absent-rule withholdings attributed to the rule; a healthy auto-apply attributed to no withholding; MANUAL and quota paths not attributed to the rule; `ruleState` for all three states; and `effectiveApplicationMode` matching the engine's default.
- `ApplicationPipelineIT` (+1 test, 9 total): with the rule genuinely unreadable, the match queues for review, no application is created, and exactly one durable `APPROVAL_RULE_UNAVAILABLE` notification lands for that owner.

## Next Exact Task
Phase 7.3 is verified locally. Commit and push on `phase7-approval-rules-ui`, then stop unless Phase 8 is explicitly requested. Phase 8 (production PDF generation for tailored CVs/cover letters) must not begin until explicitly requested. Phase 6 hardening PR #1 against `phase5-ready` remains open and was not touched.

## Last Verified Baseline
- Phase 7.3 backend local verify: 592 unit + 110 integration = 702 test cases, 0 failures, 0 errors (1 + 19 skipped). BUILD SUCCESS.
- Phase 7.3 frontend verify: 190 tests pass (17 files), tsc clean, build clean.
- Phase 7.3 worker verify: 19/19 pass.
- Phase 7.2 backend local verify: 564 unit + 109 integration = 673 test cases, 0 failures, 0 errors. BUILD SUCCESS.
- Phase 7.2 frontend verify: 185 tests pass (17 files), tsc clean, build clean.
- Previous CI-verified functional source revision: `1b87d02` (Phase 6 hardening). Phase 7 and 7.1 have not been through GitHub Actions yet.
- Phase 7 backend files changed: `V034__approval_rules.sql`, `ApplicationDecisionService.java`, `ApprovalRulesController.java`, `ApplicationDecisionServiceTest.java`, `ApprovalRulesControllerTest.java`.
- Phase 7.1 frontend files changed: `ApprovalRulesPage.tsx`, `ApprovalRulesPage.test.tsx`, `App.tsx`, `Navigation.tsx`, `types.ts`, `vite.config.ts`, `package.json`, `package-lock.json`, `ROBIN_BUILD_STATUS.md`.
- Phase 7.2 files changed: `ApplicationDecisionService.java`, `ApplicationDecisionServiceTest.java`, `ApplicationPipelineIT.java`, `DiscoveryToApplicationIT.java`, `AutomationLifecycleIT.java`, `ApprovalRulesPage.tsx`, `ApprovalRulesPage.test.tsx`, `vite.config.ts`, `ROBIN_BUILD_STATUS.md`. No schema/migration change.
- Phase 7.3 files changed: `ApprovalRuleHealthMonitor.java` (new), `ApplicationDecisionService.java`, `ApprovalRulesController.java`, `AutomationMetrics.java`, `NotificationEvents.java`, `NotificationEventHandler.java`, `ApprovalRuleHealthMonitorTest.java` (new), `ApprovalRulesControllerTest.java`, `ApplicationDecisionServiceTest.java`, `ApplicationPipelineIT.java`, `NotificationEventHandlerTest.java`, `frontend/src/types.ts`, `ApprovalRulesPage.tsx`, `ApprovalRulesPage.test.tsx`, `ROBIN_BUILD_STATUS.md`. **No schema/migration change.**
