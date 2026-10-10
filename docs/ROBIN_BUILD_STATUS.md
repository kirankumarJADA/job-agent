# Robin Build Status

Persistent engineering handoff. Update this file after every completed phase and at the end of every session.

## Current Date
2026-10-10

## Repository State
- Phase 1–5 commits: `72d18fd`, `f8008c3`, `aecc7e9`, `a32c2da`, `48b7d56`.
- Phase 6 original implementation: `f0511fb` (`feat: add human review queue for application decisions`).
- Phase 6 hardening is isolated on branch `phase6-review-queue-hardening`, based on `f0511fb`. The current hardening series includes `f1bae6c`, `5fa1a01`, `beda52c`, `b0e574c`, `4faa250`, `836d614`, and `1b87d02`. `095021d` was a status-document-only follow-up.
- Phase 7 (auto-approval rule engine + V034): `568994a`.
- Phase 7.1 (Approval Rules Settings UI): `daa848d`, on branch `phase7-approval-rules-ui`.
- Phase 7.2 (auto-approval safety audit + fail-closed correction): `bb449a4`; status record `9f2f4e2`.
- Phase 7.3 (approval-rule health and observability): `5e75e31`, on branch `phase7-approval-rules-ui`.
- Phase 8.0 (architecture audit, workflow dashboard, FIND slice): branch `phase8-find-workflow` = `226c750` (unverified first pass) + the Phase 8.0 completion commit on top. Parent `d57f498`.
- Phase 8.1 (PREP): branch `phase8.1-prep-workflow`, parent `aa4c7f8`.
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
| 7.3 — Approval-rule health & observability | `5e75e31` | Backend: 592 unit (0 fail, 1 skip) + 110 IT (0 fail, 19 skip) = 702. Frontend: 190 tests, tsc clean, build clean. Worker: 19/19 |
| 8.0 - Architecture audit, workflow dashboard, FIND slice | `phase8-find-workflow` | Backend: 593 unit (0 fail, 1 skip) + 117 IT (0 fail, 19 skip) = 710. Frontend: 212 tests (20 files), tsc clean, build clean. Worker: 19/19 |
| 8.1 - PREP: documents, validation, comparison, readiness + V035 | `phase8.1-prep-workflow` | Backend: 613 unit (0 fail, 1 skip) + 123 IT (0 fail, 19 skip) = 736. Frontend: 229 tests (23 files), tsc clean, build clean. Worker: 19/19 |
| 8.2 - APPLY: exact document selection, readiness gate, employer questions, duplicate protection + V036 | `phase8.2-apply-workflow` | Backend: 657 unit (0 fail, 1 skip) + 132 IT (0 fail, 0 skip) = 789. Frontend: 240 tests (24 files), tsc clean, build clean. Worker: 19/19 |

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
Phase 8.2 (APPLY) is verified locally on `phase8.2-apply-workflow` with a draft PR targeting `phase8.1-prep-workflow`. Do not merge or deploy. Next, only when requested: Phase 8.3 TRACK (receipt evidence source, owner timeline, recruiter messages with inferred-vs-confirmed labelling), or the PREP follow-ups (second typeface, CJK/emoji coverage, pre-8.1 CV regeneration prompts). `REAL_SUBMIT` stays hard-stopped.

## Last Verified Baseline
- Phase 8.2: backend 657 unit + 132 integration = 789, 0 failures, 0 errors (1 unit skip: `RedisConnectivityDiagnosticsTest`, its Redis-availability assumption; 0 IT skips — `UserDataIsolationIT` 19/19 ran via `-Dit.postgres.url`), BUILD SUCCESS. Frontend: 240 tests (24 files), tsc clean, build clean. Worker: 19/19.
- Phase 8.1: backend 613 unit + 123 integration = 736, 0 failures, 0 errors (1 + 19 skipped), BUILD SUCCESS; frontend 229 tests (23 files), tsc clean, build clean; worker 19/19.
- Phase 8.0: backend 593 unit + 117 integration = 710, 0 failures, 0 errors (1 + 19 skipped), BUILD SUCCESS; frontend 212 tests (20 files), tsc clean, build clean; worker 19/19.
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


## Phase 8.0 Implementation: Architecture Audit, Workflow Dashboard and FIND Vertical Slice

Full architecture map, audit table, API changes, state-transition map and PREP/APPLY/TRACK specification: `docs/PHASE8_FIND_WORKFLOW.md`.

- Branch `phase8-find-workflow`, parent `d57f498` (tip of `phase7-approval-rules-ui`, verified against `origin`). The branch already contained `226c750` (dashboard shell + FIND page) committed without any test run. That commit was audited and built upon, not duplicated or rewritten.
- Audit findings fixed in this pass:
  - `GET /jobs` and `GET /jobs/{id}` returned camelCase JSON while the frontend reads snake_case, so live cards showed no company/location/workplace/skills and the UI substituted invented values ("United Kingdom", "HYBRID", "Competitive", "£", "Recently", "FULL_TIME"). Now an explicit snake_case wire format (`JobsController.toWire`); invented fallbacks removed.
  - Feed now carries the caller's own `match_score` / `match_recommendation` (left join on `job_matches` scoped to the caller's profile; another candidate's score cannot appear), `first_seen_at`, `last_seen_at`, `stale` (30 days, same rule the review queue uses to refuse approval), `source_name`, `source_kind`. Detail adds `removed`.
  - Soft-deleted jobs are excluded from the feed (they already were from the review queue).
  - `POST /sources/{id}/health-check` (the FIND Discover action; performs a live board fetch) is now in the `discovery` rate-limit bucket and refuses to fetch disabled sources.
  - Cursor pagination ("Load more") on the FIND page using the API's `next_cursor`.
  - Match explanation for APPLY scores no longer claims "an application was created automatically" (false since Phase 5). Existing rows keep old text until re-scored.
  - Dashboard: FIND card shows caller-recommended count in the loaded page and real discovery status from `/sources`; PREP card states READY_TO_APPLY does not confirm documents exist and links to Applications.
  - Job detail: freshness, source, removed/stale warnings, honest unscored/sponsorship/trace copy, links to review queue, applications and the per-job preparation section.
- No Flyway migration. No new endpoint. CLI/MCP callers of `JobRepository.findJobs` unchanged. `REAL_SUBMIT` remains hard-stopped; nothing in this phase submits or claims submission.

### Phase 8.0 files changed (on top of `226c750`)
Backend: `JobRepository.java`, `JobsController.java`, `SourcesController.java`, `RateLimitService.java`, `JobMatchService.java`; tests `JobFeedIT.java` (new, 7 tests), `SourcesControllerTest.java` (+1 test, stubs updated for the new `enabled` column in the query), `RateLimitServiceTest.java`, `JobMatchServiceTest.java` (assertion updated to the corrected wording, plus a negative assertion).
Frontend: `types.ts`, `components/ui.tsx` (`MatchPill`, `formatSalary`, `STALE_AFTER_DAYS`, honest `JobCard`), `JobsFeedPage.tsx`, `JobDetailPage.tsx`, `DashboardPage.tsx`; tests `JobsFeedPage.test.tsx` (+6), `DashboardPage.test.tsx` (+4), `JobDetailPage.test.tsx` (new, 6). One `226c750` test asserted copy the UI never rendered; corrected to the actual copy.
Docs: `PHASE8_FIND_WORKFLOW.md` (rewritten), `ROBIN_BUILD_STATUS.md`.

### Phase 8.0 verification (local Windows checkout)
- Backend: `mvn -B verify` BUILD SUCCESS (6:04). Unit: 593 run, 0 failures, 0 errors, 1 skipped (`RedisConnectivityDiagnosticsTest`). Integration: 117 run, 0 failures, 0 errors, 19 skipped (all `UserDataIsolationIT`, which runs only with `-Dit.postgres.url`). Total 710. New `JobFeedIT`: 7/7. Before the `JobMatchServiceTest` assertion was updated the first full run failed 1 test (the old wording assertion); that run is superseded.
- Frontend: `npx tsc -b` clean; `npm test` 20 files, 212 tests, 0 failures; `npm run build` clean (489 kB JS, 31.5 kB CSS).
- Worker: `npm test` 19/19.

### Phase 8.0 known limitations
- Discovery is Greenhouse/Ashby only; `POST /jobs/import-url` is still a validated stub (`RESOLUTION_PENDING`) and the UI says so.
- Search/filter is the existing full-text `q` + `status`; location/salary/workplace/score filters need API support first.
- Per-candidate hard-filter reasons are not persisted (only the shared `jobs.filter_reasons`), so they are not shown.
- `APPLICATION_SUBMITTED` can still be set by the owner transition API without evidence; TRACK (8.3) must record and label the evidence source.
- Not deployed; not yet run through GitHub Actions.

### Recommended sequence after Phase 8.0
1. Phase 8.1 PREP: readiness derived from linked artifacts, evidence-grounded validation, master-vs-tailored diff, artifact hashes, PDFs, ATS required-field analysis.
2. Phase 8.2 APPLY: required-answer validation, ATS field mapping, artifact-bound document selection, cross-source duplicate protection, readiness gate before approval. `REAL_SUBMIT` stays hard-stopped.
3. Phase 8.3 TRACK: timeline UI, receipt evidence source, recruiter messages, interviews/rejection/withdrawal, failure recovery; AI email classifications labelled as inferred.

## Phase 8.1 Implementation: PREP — Production-Quality Application Preparation

Full detail (architecture as found, evidence model, PDF, validation, comparison, readiness rules, APIs, migration, limitations): `docs/PHASE8_FIND_WORKFLOW.md` sections 8–10.

- Branch `phase8.1-prep-workflow`, parent `aa4c7f8` (verified tip of `phase8-find-workflow`).
- Defects found and fixed: CV PDFs were unreadable (literal `\n` in the content stream), ASCII-only, single-page and truncated at 51 lines; CV bullets rendered as Java map strings, with no name/contact/dates/certifications; PDF rendered twice (hash vs stored bytes); CV download was a plain link that drops the Firebase bearer token; `cover_letters unique(job_id, version)` was global (second candidate collided); generated letters were inserted already approved and failed letters could be approved; `applicationId` in letter/answer requests was not owner/job checked.
- New: Apache PDFBox 3.0.3 renderer (Unicode via bundled Liberation Sans, multi-page, deterministic); shared deterministic fact validator (blockers vs heuristic warnings) applied to CVs, letters and answers; deterministic profile-vs-CV comparison; CV review gate (`cv_version_reviews`, bound to the artifact digest); letter correction as new versions with lineage; letter PDFs; integrity-checked downloads with `X-Content-SHA256` and audit; readiness derived from linked records (`GET /api/v1/prep/jobs/{jobId}/readiness`); frontend PREP workspace and authenticated, checksum-verified downloads.
- Migration: `V035__prep_document_review.sql` (additive).
- `REAL_SUBMIT` remains hard-stopped. Nothing in PREP submits or claims submission.

### Phase 8.1 files changed
Backend main: `pom.xml` (pdfbox), `documents/PdfDocumentRenderer.java` (new), `documents/MarkdownBlocks.java` (new), `documents/DocumentFactValidator.java` (new), `prep/PrepReadinessService.java` (new), `prep/PrepController.java` (new), `resume/CvComparisonService.java` (new), `resume/CvArtifactService.java`, `resume/ResumeAtsIntelligenceService.java`, `resume/ResumeAtsRepository.java`, `resume/ResumeAtsController.java`, `coverletter/CoverLetterService.java`, `coverletter/CoverLetterController.java`, `coverletter/CoverLetterRepository.java`, `coverletter/CoverLetterRecord.java`, `qa/ApplicationAnswerService.java`, `qa/ApplicationAnswerController.java`, `qa/ApplicationAnswerRepository.java`, `profile/ContactRecord.java` (new), `profile/ProfileRepository.java`, `security/SecurityConfig.java` (CORS exposed headers), `db/migration/V035__prep_document_review.sql` (new).
Backend tests: `CvArtifactServiceTest` (1 → 5), `ResumeAtsIntelligenceServiceTest` (1 → 2), `DocumentFactValidatorTest` (new, 8), `CoverLetterReviewTest` (new, 7), `PrepWorkflowIT` (new, 6, PostgreSQL).
Frontend: `api/client.ts` (`apiDownload`, `saveDownload`), `types.ts`, `components/PrepWorkspace.tsx` (new), `components/ApplicationPrepStatus.tsx` (new), `pages/JobDetailPage.tsx`, `pages/ApplicationsPage.tsx`; tests `PrepWorkspace.test.tsx` (new, 10), `download.test.ts` (new, 4), `ApplicationPrepStatus.test.tsx` (new, 2), `JobDetailPage.test.tsx` (+1).

### Phase 8.1 verification (local Windows checkout)
- Before the renderer replacement, the new `CvArtifactServiceTest` cases were run against the old writer: 5 run, 4 failed (readability, multi-page, Unicode, long token). After: 5/5.
- Backend `mvn -B verify`: BUILD SUCCESS (6:26). Unit 613 run, 0 failures, 0 errors, 1 skipped (`RedisConnectivityDiagnosticsTest`). Integration 123 run, 0 failures, 0 errors, 19 skipped (all `UserDataIsolationIT`, runs only with `-Dit.postgres.url`). Total 736. `PrepWorkflowIT` 6/6, `JobFeedIT` 7/7, `ArchModuleBoundaryTest` 4/4.
- Frontend: `npx tsc -b` clean; `npm test` 23 files, 229 tests, 0 failures; `npm run build` succeeds (Vite warns that the main chunk exceeds 500 kB; not an error).
- Worker: `npm test` 19/19.

### What remains before APPLY readiness and TRACK
1. APPLY (8.2): `ExecutionPackageService` must bind only an approved, currently validated, digest-intact letter and the reviewed CV digest; required-answer validation against real ATS form questions; field mapping; cross-source duplicate protection; readiness gate before approval. `REAL_SUBMIT` stays hard-stopped.
2. TRACK (8.3): receipts with an evidence source; owner timeline; recruiter messages with inferred-vs-confirmed labelling.
3. PREP follow-ups: a second typeface for real bold, CJK/emoji font coverage, and regeneration prompts for pre-8.1 CVs.

## Phase 8.2 Implementation: APPLY — Exact Document Selection, Readiness Gate, Employer Questions, Duplicate Protection

Full detail (workflow, endpoints, state transitions, document-binding rules, duplicate semantics, migration): `docs/PHASE8_FIND_WORKFLOW.md` sections 11–11.11.

- Branch `phase8.2-apply-workflow`, parent `e389809` (tip of `phase8.1-prep-workflow`).
- **The known defect is fixed first.** `ExecutionPackageService` attached the latest cover letter regardless of approval. Selection is now fail-closed through the new `ApplyDocumentSelector`: the CV must be the exact reviewed version, its review bound to the selected CV's content digest, and its stored PDF bytes must re-hash to the recorded SHA-256; the cover letter must be the exact version that is explicitly approved, currently validated under the current validator, content-digest intact and rendered-PDF digest intact. A missing review, a legacy-unvalidated CV, an integrity failure or a digest mismatch blocks the package (`ExecutionPackageBlockedException`, 409 with the blocker list). There is no fallback to a newer or previous letter. The package records the exact document IDs/versions and digests it selected.
- **Pre-approval readiness gate.** `ApplyReadinessService` re-evaluates current records on every request (never cached): ownership of job/application/CV/letter/answers, job-application association, the reviewed CV version and digest, the cover-letter requirement (including an explicit UNKNOWN) and approval state, current validation and file integrity, required screening questions and candidate-confirmed answers, duplicate-application checks, approval-rule decisions, quotas, idempotency and review-queue state. Blockers are actionable and separated from warnings and unknowns; a dependency failure returns an explicit `UNAVAILABLE` 503 state, never a false success. It reuses `PrepReadinessService` findings rather than adding a second readiness model.
- **Employer questions with provenance.** `JobFormQuestionService` captures Greenhouse public board application forms (field wrappers, hidden required-input mirrors, `*` label markers) and stores questions with source provenance, stable question IDs, job/form association, required/optional/**UNKNOWN** state (required-ness is captured as evidence; missing metadata is UNKNOWN, never assumed optional), answer type and permissible options. Answers bind to stable question IDs and the application; required answers, formats and permitted options are validated before approval; candidate-confirmed answers are distinguished from generated suggestions. Ashby and the secondary sources expose job descriptions but not the application form: readiness reports questions and the cover-letter requirement as UNKNOWN and the workspace says so instead of inventing requirements.
- **Duplicate-application protection.** `ApplicationIdentityService` normalises canonical job URLs (tracking-only query parameters do not make a distinct job), combines source/provider plus external job ID and employer requisition IDs where available, and detects cross-source duplicates against the candidate's own application records. Uniqueness is enforced in the persistence layer (`V036__apply_selection_and_duplicates.sql`), race-safe under concurrency (concurrent creation commits exactly one application), and a duplicate response identifies the existing record only to its own owner.
- **APPLY review workspace.** `ApplyWorkspace.tsx` (mounted in the applications flow) shows the exact job and application, the selected CV version with review/integrity status, the selected cover-letter version with validation/approval status, screening questions with which answers need confirmation, duplicate warnings, blockers and unknowns; approval is refused while blockers exist and a server refusal renders as a refusal, never as a false success state.
- **Approval invalidation.** `ApplyPackageGuard` withdraws an approved package when a bound record changes afterwards (CV review granted or withdrawn, letter approved/corrected/unapproved, answer edited or unconfirmed) and audits the withdrawal; reusing the stale approval is refused until the package is rebuilt and reviewed again.
- **API** (both owner-scoped; a foreign application id reads as 404): `GET /api/v1/apply/applications/{id}/readiness`, `POST /api/v1/apply/applications/{id}/package` (the readiness gate runs again server-side, so a stale client cannot bypass a blocker). Migration: `V036__apply_selection_and_duplicates.sql` (additive).
- `REAL_SUBMIT` remains hard-stopped: no submission endpoint, no submitting browser automation, no employer-side mutation, no fabricated submission receipt.

### Phase 8.2 files changed
Backend new: `apply/ApplyController.java`, `apply/ApplyReadinessService.java`, `apply/ApplyPackageGuard.java`, `apply/ApplicationIdentityService.java`, `apply/DuplicateApplicationException.java`, `ats/JobFormQuestionService.java`, `automation/ApplyDocumentSelector.java`, `automation/ExecutionPackageBlockedException.java`, `db/migration/V036__apply_selection_and_duplicates.sql`.
Backend modified: `ats/AtsAdapter.java`, `ats/GreenhouseAdapter.java`, `automation/AutomationController.java`, `automation/AutomationPlanRepository.java`, `automation/ExecutionPackageService.java`, `automation/GreenhouseFieldMapper.java`, `application/ApplicationPipelineService.java`, `application/ApplicationPipelineEventHandler.java`, `application/ReviewQueueController.java`, `qa/ApplicationAnswerController.java`, `qa/ApplicationAnswerRepository.java`, `qa/ApplicationAnswerRecord.java`, `coverletter/CoverLetterController.java`, `coverletter/CoverLetterRepository.java`, `resume/ResumeAtsController.java`, `resume/ResumeAtsRepository.java`, `jobs/JobsController.java`.
Backend tests new: `apply/ApplyWorkflowIT.java` (9 tests, PostgreSQL), `apply/ApplyReadinessServiceTest.java`, `apply/ApplicationIdentityServiceTest.java`, `automation/ApplyDocumentSelectorTest.java`. Tests modified: `ApplicationPipelineServiceTest`, `ApplicationPipelineEventHandlerTest`, `AutomationControllerReviewTest`, `GreenhouseExecutionPlanServiceTest`, `GreenhouseFieldMapperTest`, `GreenhouseAdapterTest`, `InspectionPlanBridgeTest`, `CoverLetterReviewTest`, `AutomationLifecycleIT`, `UserDataIsolationTest`.
Frontend: `components/ApplyWorkspace.tsx` (new), `components/ApplyWorkspace.test.tsx` (new, 11 tests), `pages/ApplicationsPage.tsx`, `types.ts`. Docs: `PHASE8_FIND_WORKFLOW.md`, `ROBIN_BUILD_STATUS.md`.

### Phase 8.2 verification (local Windows checkout; ITs on real PostgreSQL 16 via `-Dit.postgres.url`)
- Backend `mvn -B -o verify`: BUILD SUCCESS (6:58). Unit 657 run, 0 failures, 0 errors, 1 skipped (`RedisConnectivityDiagnosticsTest`, its Redis-availability assumption). Integration **132 run, 0 failures, 0 errors, 0 skipped** — `UserDataIsolationIT` 19/19 ran for real this phase and is not counted from a skip. Total 789, 0 failures/errors.
- `ApplyWorkflowIT` 9/9: package creation and approval with real persisted records; an unreviewed CV blocks package creation; an answer edit and a CV-review withdrawal invalidate an approved package and stale re-approval is refused; a cross-source duplicate is refused 409 with owner-only identification; concurrent duplicate creation commits exactly one application; tracking-only query parameters are not distinct roles; a second user's application is invisible.
- `AutomationLifecycleIT` 4/4, `PrepWorkflowIT` 6/6, `ApplicationPipelineIT` 9/9, `ReviewQueueIT` 8/8, `DiscoveryToApplicationIT` 3/3, `CrossFeatureIntegrationIT` 2/2, `JobFeedIT` 7/7 in the same run.
- Frontend: `npx tsc -b` clean; `npm test` 24 files, 240 tests, 0 failures (11 new `ApplyWorkspace`); `npm run build` clean (Vite repeats its >500 kB chunk warning; not an error).
- Worker: `npm test` 19/19, including the submission-impossibility gate.

### Phase 8.2 defects fixed while proving the flow (product fixes, not test accommodations)
- `GreenhouseFieldMapper` labelled confirmed answers `application_answers (ANSWERED)` while every consumer (package builder, plan builder) trusts `application_answers (human-confirmed)`, so a candidate-confirmed answer never counted as a safe value and could not become a fill step.
- `ExecutionPackageService` listed every required employer question as a permanent human-required gap even when a candidate-confirmed answer was safely mapped; a gap now means "no safe value", and approval still refuses while any gap remains.
- `GreenhouseAdapter` ignored the label's `*` required marker on non-radio fields, misreading `work_auth`-style fields as optional.

### Phase 8.2 test repairs (fixtures modernised to the strengthened contract; assertions unchanged or strengthened)
- `ApplyWorkflowIT`'s fixture form is the real Greenhouse field-wrapper shape (required fields carry the hidden required-input mirror or the label's `*` marker), each test uses its own requisition URL so duplicate protection does not cross-contaminate scenarios, and the readiness case asserts both the pre-capture UNKNOWN state and the post-capture provenance. The previous flat fixture could not express required-ness at all, and its expectation that an unmarked field is `REQUIRED` was unsound: required-ness is captured as evidence and a field with no metadata is `UNKNOWN`, never assumed optional.
- `AutomationLifecycleIT` seeds one requisition URL per scenario (Phase 8.2 duplicate protection correctly refuses a second application for one requisition) and a genuinely reviewed CV (validation plus a digest-bound review), which the Phase 8.2 approval gate requires. All pre-existing assertions are unchanged.

### Phase 8.2 known limitations
- Employer-form capture covers Greenhouse public board forms only; Ashby and the secondary sources report the form as not captured and readiness reports questions and the cover-letter requirement as UNKNOWN. JavaScript-rendered forms cannot be inspected (`capture_status = UNAVAILABLE`).
- Required-ness is UNKNOWN for controls with no field-scoped metadata; the UI renders that as unknown, not optional.
- A plan reaches `AWAITING_APPROVAL` only after a validation report (worker `complete` with `HUMAN_REQUIRED`); the worker suite covers the report path, a real browser run remains for Phase 8.3/ops.
- PDF limitations from 8.1 persist (single typeface, no CJK/emoji).
- Not deployed, not merged, branch CI pending.
