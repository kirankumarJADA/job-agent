# Phase 8.0 — Robin Four-Stage Workflow: Architecture Map, State Model and FIND Slice

Baseline: `phase7-approval-rules-ui` at `d57f498`. Branch: `phase8-find-workflow`.
The branch already held `226c750` (dashboard shell and FIND page, committed without any test run).
This pass audited that commit against the backend, fixed what it got wrong, completed the FIND
slice and ran the full verification. Nothing here is deployed.

FIND → PREP → APPLY → TRACK. Only FIND is completed in this phase. PREP, APPLY and TRACK are
mapped and specified below; they are not marked as finished.

## 1. Architecture map (from code and schema, not from class names)

Classification: **Verified** = implemented with passing tests that exercise it; **Needs IT** =
implemented but not proven end to end against real infrastructure; **Partial** = some of the
user-facing workflow exists; **Missing** = no working implementation.

### FIND

| Capability | Components | Status |
|---|---|---|
| Greenhouse / Ashby board discovery | `DiscoveryOrchestrator`, `GreenhouseProvider`, `AshbyProvider`, `DiscoveryController` (`POST /discovery/run`) | Verified (`GreenhouseDiscoveryIT`, `AshbyDiscoveryTest`, `DiscoveryOrchestratorTest`) |
| On-demand discovery from the UI | `SourcesController` `POST /sources/{id}/health-check` (runs the same board fetch and records source health) | Verified (`SourcesControllerTest`, `JobFeedIT`); now rate-limited and refuses disabled sources |
| Scheduled discovery | `DiscoveryScheduler` | Needs IT (unit-tested; production schedule not observed in this pass) |
| Deduplication | `JobDiscoveryService.ingestJob` (dedup key = company/title/location hash, plus `(source_id, external_id)` unique) | Verified (`GreenhouseDiscoveryIT` INSERTED/TOUCHED/UPDATED, `JobFeedIT` single feed entry) |
| Stale / archive maintenance | `JobDiscoveryService.scheduledDiscoveryMaintenance` (DISCOVERED not seen 30 days → ARCHIVED) | Needs IT |
| Hard filters | `HardFilterService`, `ApplicationPipelineService` | Partial: the rejection is written to the shared `jobs.filter_reasons` / `FILTERED_OUT`, so it is not a per-candidate result and is deliberately not shown to users |
| Per-candidate matching | `JobMatchService`, `SemanticSkillMatcher`, `job_matches` | Verified (unit tests); feed/detail exposure verified by `JobFeedIT` |
| Feed, search, status filter, pagination | `JobsController` `GET /jobs`, `JobRepository.findFeed` | Verified in this phase |
| Job detail with own match, freshness, source | `JobsController` `GET /jobs/{id}`, `JobDetailPage` | Verified in this phase |
| Direct URL import | `POST /jobs/import-url` | Missing (validates syntax, returns `RESOLUTION_PENDING`, ingests nothing; UI says so) |
| LinkedIn / Crawl4AI / Scrapling providers | `LinkedInDiscoveryService`, `Crawl4AiProvider`, `ScraplingProvider` | Not assessed for user-facing use; not exposed in FIND |

### PREP

| Capability | Components | Status |
|---|---|---|
| Job-specific CV from master-profile evidence | `ResumeAtsIntelligenceService`, `ResumeAtsController` (`POST /resume-intelligence/tailor`), V012–V017 | Needs IT (UI exists on job detail) |
| CV binary artifact / PDF download | `CvArtifactService`, V016 binary artifacts | Needs IT; production-quality PDF is the old roadmap's Phase 8 item |
| Cover letters with checksum and approval | `CoverLetterService`, `CoverLetterController`, V006, V027 | Needs IT (UI exists) |
| Drafted application answers + human confirmation | application answers, V025 | Needs IT (UI exists) |
| Reviewable document diff | none | Missing |
| Required-field analysis per ATS | `AtsAdapters`, `AtsController` | Partial |

### APPLY

| Capability | Components | Status |
|---|---|---|
| Decision engine (MANUAL / ASSISTED / CONTROLLED_AUTO) | `ApplicationDecisionService`, V031 | Verified (Phase 5–7 tests) |
| Auto-approval rules, fail closed | `ApprovalRulesController`, `ApprovalRuleHealthMonitor`, V034 | Verified (Phase 7.x) |
| Human review queue (approve / reject / pause / resume / expire, stale refusal) | `ReviewQueueController`, V032–V033 | Verified (Phase 6) |
| Quotas | V030 quota engine | Verified (Phase 4) |
| Automation plans, worker leases, recovery | `AutomationController`, `AutomationPlanRepository`, `AutomationRecoveryScheduler`, worker | Verified (worker 19/19; backend tests) |
| Readiness surfaced to the user | `ApplicationsPage` plan status | Partial |
| Live submission | worker submit step | Hard-stopped (`REAL_SUBMIT`); intentionally not available |
| Confirmed submission receipt | none | Missing |

### TRACK

| Capability | Components | Status |
|---|---|---|
| Owner-scoped application list, detail and timeline | `ApplicationController`, `ApplicationStatusService`, `application_events` | Verified by unit isolation tests; the Postgres `UserDataIsolationIT` only runs when `-Dit.postgres.url` is set, so it is skipped in a default `mvn verify` |
| Lifecycle transitions with audit | `ApplicationStatusService.ALLOWED`, audit log | Verified |
| Notifications | `NotificationService`, `NotificationEventHandler` | Verified |
| Recruiter email classification | `EmailIntelligenceService` | Needs IT (real mailbox is roadmap Phase 13) |
| Receipt-backed "submitted" status | none | Missing |

### Frontend

`DashboardPage` (four stage cards), `JobsFeedPage` (FIND), `JobDetailPage`, `ApplicationsPage`,
`ReviewQueuePage`, `ApprovalRulesPage`, `ProfilePage`, `PreferencesPage`, `Navigation`, `App`.

## 2. Audit of `226c750` and what this pass changed

| Finding | Effect before | Fix |
|---|---|---|
| `GET /jobs` and `GET /jobs/{id}` serialized `JobRecord` directly, giving camelCase JSON (`companyNameRaw`), while the frontend reads snake_case (`company_name_raw`) | Live cards showed no company, location, workplace type or skills, and the UI then substituted invented values | Explicit snake_case wire map in `JobsController.toWire`; contract asserted by `JobFeedIT` |
| `JobCard` / `JobDetailPage` fell back to "United Kingdom", "HYBRID", "Competitive", "£", "Recently", "FULL_TIME" | Fabricated posting data | Missing values are shown as missing; currency comes from the posting |
| Feed had no per-candidate relevance | User could not see which jobs matched them | `match_score` / `match_recommendation` from `job_matches` joined on the caller's profile only |
| No freshness data on the wire (`first_seen_at`/`last_seen_at` were typed in the frontend but never sent) | Stale postings looked current | `first_seen_at`, `last_seen_at`, `stale` (30 days, same rule as the review queue) |
| Soft-deleted jobs appeared in the feed | Removed postings offered as live | Feed excludes `deleted_at is not null`; detail flags `removed` |
| `POST /sources/{id}/health-check` performs a live board fetch but had no rate-limit bucket, and the new Discover button uses it | Unthrottled path to external boards | Added to the `discovery` bucket |
| The health check fetched disabled sources | Disabled boards could still be hit | Disabled sources return their recorded state unchanged |
| Feed showed only the first 50 results | No way to reach older postings | Cursor pagination ("Load more") using the API's `next_cursor` |
| Match explanation stored "an application was created automatically" for APPLY scores | False since Phase 5: the decision engine may route to review | New text says the match was passed to the decision rules and that a match never submits |
| Dashboard PREP card linked to the jobs feed and implied preparation was complete | READY_TO_APPLY is set at application creation, before documents exist | Copy says so; card links to Applications |
| One `226c750` test asserted copy the UI never rendered | The commit's tests had never been run | Test corrected to the actual (accurate) copy |

## 3. API and schema changes

No migration. No new endpoint. Changes to existing endpoints:

- `GET /api/v1/jobs`: items are now explicit snake_case maps with the existing job fields plus
  `first_seen_at`, `last_seen_at`, `stale`, `removed`, `source_name`, `source_kind`,
  `match_score`, `match_recommendation`. Soft-deleted postings are excluded. `limit` is clamped
  to 1..100. Same `q`, `status`, `cursor` semantics. `jobs.filter_reasons` is never exposed.
- `GET /api/v1/jobs/{id}`: `job` uses the same wire map (soft-deleted rows are returned with
  `removed: true`). `match` is unchanged (caller's own `job_matches` row or null).
- `POST /api/v1/sources/{id}/health-check`: rate-limited under `discovery`; disabled sources are
  not fetched.
- CLI and MCP callers of `JobRepository.findJobs` are unchanged.

## 4. State model (existing states only)

```
DISCOVERY        jobs.status (shared catalogue row)
                 DISCOVERED ──match──▶ SCORED
                 DISCOVERED ──hard filter rejects──▶ FILTERED_OUT   (shared row; see limitation)
                 DISCOVERED ──not seen 30 days──▶ ARCHIVED
                 ANALYSED / DECIDED / PIPELINE_ERROR exist in the constraint
MATCH            job_matches (profile_id, job_id): score, recommendation APPLY | REVIEW | SKIP
DECISION         application_decisions (profile_id, job_id), one row per pair
                 AUTO_APPLY | NEEDS_REVIEW | SKIP
                 NEEDS_REVIEW ⇄ PAUSED ; NEEDS_REVIEW|PAUSED ─▶ APPROVED | REJECTED | EXPIRED
                 approval refused when the posting is stale or has no application URL
APPLICATION      applications.status (profile-owned; one per profile+job via partial unique index)
                 READY_TO_APPLY ─▶ APPLICATION_STARTED ─▶ OTP_PENDING ─▶ APPLICATION_SUBMITTED
                 ─▶ CONFIRMATION_RECEIVED ─▶ RECRUITER_CONTACT ─▶ INTERVIEW ─▶ ASSESSMENT ─▶ OFFER
                 REJECTED from submitted onwards; WITHDRAWN / FAILED before submission
PREPARATION /    automation_plans.status: PREPARED, RUNNING, AWAITING_APPROVAL,
READINESS        AWAITING_SUBMIT_APPROVAL, READY_TO_SUBMIT, COMPLETED, SUBMITTED, FAILED,
                 BLOCKED_ANTI_BOT, ABANDONED; submit_approved flag
```

Transitions that exist and are enforced today: discovery ingest and dedup, matching, decision
upsert, review lifecycle, application creation (idempotent), application status machine
(`ApplicationStatusService.ALLOWED`, owner and expected-state checked, audited), plan lifecycle.

Gaps later phases must close without adding a second status table or a workflow engine:

1. "Documents prepared" has no state of its own. READY_TO_APPLY is set when the application is
   created. PREP should derive readiness from linked artifacts (CV version, approved cover letter,
   confirmed answers) and the plan status, not add a parallel status.
2. `APPLICATION_SUBMITTED` / `CONFIRMATION_RECEIVED` can be set through the owner transition API
   with no evidence. That is acceptable only as a user-reported manual action; TRACK must record
   the evidence source (user-reported, worker receipt, employer email) in the event payload and
   the UI must label it. An approval, a prepared plan or an email mention must never set them.
3. Per-candidate hard-filter outcomes are not persisted. Showing "why a job was filtered for you"
   needs an owner-scoped record (for example on `application_decisions` or `job_matches.breakdown`),
   not the shared `jobs.filter_reasons`.

## 5. Dashboard stage behaviour (as built)

| Card | Value | Detail | Link |
|---|---|---|---|
| FIND | jobs in the latest page of `GET /jobs?limit=100` (`+` when `next_cursor` is present) | caller-recommended (APPLY/REVIEW) count in that page; enabled Greenhouse/Ashby boards and how many have a failure streak, from `GET /sources` | `/jobs` |
| PREP | applications with status READY_TO_APPLY | says READY_TO_APPLY does not confirm documents exist | `/applications` |
| APPLY | review queue `pendingCount` | approval does not submit | `/review-queue` |
| TRACK | count of the caller's application records | explicitly not a submitted count | `/applications` |

Each card shows "…" while loading and "Unavailable" with the error when its API fails; nothing is
shown as zero on failure. No new endpoint was added for counters.

## 6. Remaining stages (specification only; not implemented in Phase 8.0)

### PREP (next: Phase 8.1)
Reuse `ResumeAtsIntelligenceService`, `CvArtifactService`, `CoverLetterService` and application
answers. Deliver: per-job readiness derived from linked artifacts; factual validation that every
CV/cover-letter claim maps to master-profile evidence (never invent qualifications or history);
a reviewable diff between master CV and tailored CV; persistent artifacts with hashes;
downloadable PDFs; required-field analysis from the ATS adapter. Prerequisites: integration test
of the tailor → artifact → download path on real Postgres.

### APPLY (Phase 8.2)
Reuse the decision rules, quotas, review queue, ATS adapters, automation plans and worker.
Deliver: required-answer validation against confirmed answers; ATS field mapping; document
selection bound to artifact hashes; duplicate protection across sources (roadmap Phase 10);
readiness checks shown before approval. `REAL_SUBMIT` stays hard-stopped; any live submission
needs its own safety review and the full checklist in `ROBIN_BUILD_STATUS.md`.

### TRACK (Phase 8.3)
Reuse `application_events`, the status machine, notifications and `EmailIntelligenceService`.
Deliver: owner-scoped timeline UI; receipts with an evidence source; recruiter messages,
interviews, rejection, withdrawal, failed operations and recovery. AI email classifications are
shown as inferred and never move an application to a submitted or confirmed state on their own.

## 7. Known limitations after Phase 8.0

- Discovery is available for Greenhouse and Ashby boards only. URL import is a validated stub.
- Search is the existing full-text `q` and `status`. Location, salary, workplace and score
  filters need API support before they can be offered; the UI does not fake them.
- Feed relevance counts are for the loaded page, and say so.
- Per-candidate hard-filter reasons are not shown (see state-model gap 3).
- Existing `job_matches.breakdown.decision` rows keep the old APPLY wording until re-scored.
- Production deployment of this branch has not happened and is not claimed.
