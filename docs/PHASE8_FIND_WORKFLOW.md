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

> Phase 8.0 snapshot. Superseded by Phase 8.1 (sections 8–10 below): CV/letter PDFs, comparison,
> correction workflow, validation and readiness are now implemented and integration-tested.

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

---

# Phase 8.1 — PREP: Production-Quality Application Preparation

Branch `phase8.1-prep-workflow`, parent `aa4c7f8` (`phase8-find-workflow`). Builds on the existing
CV, cover-letter and answer services; no second document system was added.

## 8. PREP architecture as found (before Phase 8.1)

| Capability | Status found | Evidence |
|---|---|---|
| CV tailoring, input hash, profile revision and snapshot hash, immutable `cv_versions` (V013 trigger), evidence rows | Working | `ResumeAtsIntelligenceService`, `ResumeAtsRepository` |
| CV PDF | Broken | Hand-written writer replaced every non-ASCII character with `?`, rendered one page, stopped after 51 lines, and wrote literal `\n` into the content stream so the text could not be extracted. Four new `CvArtifactServiceTest` cases failed against it before replacement. |
| CV content | Incomplete | Bullets rendered as Java map strings (`[{text=...}]`); no name, contact, dates or certifications; the job title was used as the CV heading; an invented summary line when none existed |
| Hash vs bytes | Fragile | PDF rendered twice (once to hash, once to store) |
| Download | Broken for Firebase sessions | Plain `<a href>`, which sends no `Authorization` header |
| Cover-letter versions | Bug | V006 `unique(job_id, version)` was global, so a second candidate's v1 for the same job collided |
| Cover-letter approval | Unsafe | Letters that passed the (weak) check were inserted **already approved**; letters with failed validation could be approved |
| Cover-letter / answer `applicationId` | Unchecked | Taken from the request body without an owner/job check |
| Letter validation | Weak | Two fixed phrases plus year matching |
| Letter PDF, correction workflow, comparison, CV review state, reloading an existing CV, readiness | Missing | — |

## 9. What Phase 8.1 built

### Source of truth and evidence
The structured master profile (skills, work experience, education, projects, certifications,
profile fields, and the account's name and email) is the only source of candidate facts. Robin
has no uploaded "master CV" file and does not pretend to. The CV text is assembled
deterministically from those records (`ResumeAtsIntelligenceService.render`); no model writes CV
content. The job description is untrusted and only decides **order** (relevant verified skills
first) and which requirements are reported as missing. Every rendered section has an evidence
link (`verified_evidence`, `cv_claim_evidence`, `profile_evidence`) with source type and id.

### CV generation
- Input hash = job text + profile snapshot hash + generator version + renderer version + validator
  version. Identical inputs replay the existing immutable version; any change creates a new one.
- The PDF is rendered **once**; those exact bytes are stored in `files.content`, and
  `cv_versions.content_sha256` / `files.sha256` are their SHA-256 (`ResumeAtsRepository.insert(..., byte[])`).
- Missing values are omitted, never invented (no summary section without a summary record).

### PDF implementation
Apache PDFBox 3.0.3 (Apache-2.0), added to `pom.xml`; the bundled Liberation Sans font (SIL OFL)
is embedded as a subset for Unicode. `documents/PdfDocumentRenderer`: A4, one column, real text,
no tables or images, headings, bullets, word wrap, character wrap for long URLs, unlimited pages,
"Page X of Y" footers, deterministic output (no dates, content-derived trailer /ID). Characters
the font cannot draw are replaced with `?` **and counted** (`renderer.substituted_characters`).

### Fact validation (`documents/DocumentFactValidator`, applied to CVs, letters and answers)
- **Deterministic → BLOCKER**: years not covered by any dated record (full date ranges now count),
  claimed years of experience above what dated history/skill years support, figures (`%`, currency,
  `x`, `k/m`) absent from the candidate's own records, degrees with no matching education record,
  named certifications not recorded, work-authorisation statements with no recorded eligibility,
  security clearance not stated in any record.
- **Heuristic → WARNING**: an organisation the text places the candidate at that is not an
  employer, institution or project; a job requirement mentioned in the text with no candidate
  evidence; a work-authorisation statement when eligibility is recorded (confirm it matches).
- Reports carry `validator_version`, counts, every finding with code/severity/kind, and a scope
  sentence. "Passed" means no blocker of the checked kinds; the UI says exactly that and never
  "verified". Employer text is never used as candidate evidence.

### Master-versus-tailored comparison (`resume/CvComparisonService`)
`GET /api/v1/resume-intelligence/cv/{id}/comparison` compares the stored CV markdown and evidence
with the owner's profile records: skills emphasised / retained / omitted; requirements with their
evidence source vs requirements missing; per-record RETAINED / SHORTENED / OMITTED with bullet
counts; CV entries no longer in the profile; attention items. If the profile snapshot hash differs
from the one stored on the CV, it says the profile changed and that the comparison uses the
current profile. No model is involved.

### Cover letters
- New letters start **unapproved**. Approval re-runs the current validation, stores the result,
  and is refused (409, audited) on any blocker or if the body no longer matches its V027 digest.
  Withdrawing approval is always allowed.
- `POST /cover-letters/{id}/corrections`: a correction is a **new version** (`origin =
  USER_CORRECTED`, `parent_version_id` = corrected version), revalidated, unapproved, audited.
  The original is untouched.
- Each version's PDF is rendered once and stored in `files` (`cover_letters.pdf_file_id`); letters
  created before 8.1 get their PDF rendered once on first download and kept.
- Generation refuses an `applicationId` that is not the caller's application for that job.

### Readiness (`prep/PrepReadinessService`, `GET /api/v1/prep/jobs/{jobId}/readiness[?applicationId=]`)
Derived only from linked records; nothing is stored:
- CV: `MISSING`, `INTEGRITY_FAILED` (stored bytes ≠ recorded digest), `LEGACY_UNVALIDATED`,
  `VALIDATION_BLOCKED`, `AWAITING_REVIEW`, `APPROVED` (review row bound to the current digest);
  plus whether it is the version attached to the application.
- Cover letter: requirement `UNKNOWN` (no employer data); `NOT_GENERATED`, `INTEGRITY_FAILED`,
  `VALIDATION_BLOCKED`, `AWAITING_APPROVAL`, `APPROVED_UNDER_OLD_CHECKS`, `APPROVED`.
- Answers: `NEEDS_USER_INPUT`/`HARD_STOP` are blockers; unconfirmed answers are actions.
- Overall: `NOT_STARTED`, `BLOCKED`, `IN_PROGRESS`, `READY_FOR_REVIEW` ("Ready for the next
  human-review step"). `READY_TO_APPLY` is ignored. ATS-specific required questions and whether
  a letter is required are listed under `notChecked`. A storage failure returns 503
  `PREP_UNAVAILABLE`, never "not started".

### Downloads and audit
CV `GET /resume-intelligence/cv/{id}/artifact` and letter `GET /cover-letters/{id}/pdf`: owner-scoped
(foreign ids are 404), served only when the stored bytes match their recorded digest (else 409 and an
audit row), `application/pdf`, attachment filename, `Cache-Control: no-store`, and
`X-Content-SHA256`. CORS now exposes `Content-Disposition` and `X-Content-SHA256`. The frontend's
`apiDownload` uses the same bearer token + cookie as `apiFetch`, hashes the bytes in the browser and
refuses to save a file whose digest is missing or different. Audited actions:
`TAILORED_CV_ARTIFACT_DOWNLOADED`, `TAILORED_CV_ARTIFACT_INTEGRITY_FAILED`, `TAILORED_CV_APPROVED`,
`TAILORED_CV_APPROVAL_WITHDRAWN`, `COVER_LETTER_PDF_DOWNLOADED`, `COVER_LETTER_PDF_INTEGRITY_FAILED`,
`COVER_LETTER_CORRECTED`, `COVER_LETTER_APPROVAL_REFUSED`, plus the existing generation/approval rows.

### API changes (all owner-scoped, additive)
| Method | Path | Purpose |
|---|---|---|
| GET | `/resume-intelligence/job/{jobId}[?applicationId=]` | Latest CV with artifact integrity and review (`{"cv": null}` when none) |
| GET | `/resume-intelligence/cv/{id}/comparison` | Deterministic comparison |
| PUT | `/resume-intelligence/cv/{id}/review` | Approve / withdraw (gated) |
| GET | `/resume-intelligence/cv/{id}/artifact` | Now integrity-checked, 404 for not found, digest header |
| POST | `/cover-letters/{id}/corrections` | New corrected version |
| GET | `/cover-letters/{id}/pdf` | Letter PDF |
| PUT | `/cover-letters/{id}/approval` | Now gated by revalidation and digest |
| GET | `/prep/jobs/{jobId}/readiness` | Preparation readiness |

`CoverLetterRecord` gained `contentSha256`, `parentVersionId`, `origin`, `pdfStored`.

### Migration V035 (`V035__prep_document_review.sql`, additive)
Drops the global `cover_letters_job_id_version_key` and adds unique `(profile_id, job_id, version)`;
adds `cover_letters.parent_version_id`, `origin` (check `GENERATED|USER_CORRECTED`, default
`GENERATED`), `pdf_file_id`; creates `cv_version_reviews` (decision beside the immutable CV,
bound to the reviewed digest). No column changes meaning; no data rewritten.

### Frontend
`components/PrepWorkspace.tsx` replaces the three in-page sections of `JobDetailPage`: readiness
panel (blockers, actions, not checked, refresh, unavailable state), CV (load existing, generate for
the linked application, findings, gaps, review gate, verified download, comparison), cover letter
(version selector, findings, approve/withdraw with server refusal shown, correction editor, new
version, verified download), screening answers (draft for the linked application, confirm).
`ApplicationsPage` shows each application's readiness and links to its PREP workspace.

## 10. PREP known limitations

- Unicode beyond the Liberation Sans repertoire (for example CJK, emoji) renders as `?` and is counted.
- Bold is simulated (fill + stroke); there is one typeface.
- Validation covers the listed claim types; free-text achievements without figures, and
  paraphrased employer names, are not provable deterministically and may pass.
- Employer-required questions and cover-letter requirements are unknown; readiness says so.
- `ExecutionPackageService` (APPLY) still binds the latest letter for an application regardless
  of approval; Phase 8.2 must bind only an approved, current-validated, digest-intact version.
- CVs and letters created before 8.1 keep their old artifacts; readiness marks such CVs
  `LEGACY_UNVALIDATED` and the old CV PDFs are not readable.
- Nothing in PREP submits an application. `REAL_SUBMIT` stays hard-stopped.
