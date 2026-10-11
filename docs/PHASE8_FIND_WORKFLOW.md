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

> Phase 8.0 snapshot. Superseded by Phase 8.3 (section 13 below): the evidence timeline,
> receipts with a verification policy, reconciliation and employer-message review are now
> implemented and integration-tested; a live mailbox connection is still missing.

| Capability | Components | Status |
|---|---|---|
| Owner-scoped application list, detail and timeline | `ApplicationController`, `ApplicationStatusService`, `application_events` | Verified by unit isolation tests; the Postgres `UserDataIsolationIT` only runs when `-Dit.postgres.url` is set, so it is skipped in a default `mvn verify` |
| Lifecycle transitions with audit | `ApplicationStatusService.ALLOWED`, audit log | Verified |
| Notifications | `NotificationService`, `NotificationEventHandler` | Verified |
| Recruiter email classification | `EmailIntelligenceService` | Needs IT (real mailbox is roadmap Phase 13). Phase 8.3: rewritten as inference-only classification with candidate review, verified by PostgreSQL ITs; still no mailbox |
| Receipt-backed "submitted" status | none | Missing. Phase 8.3: `application_receipts` + policy (13.3); reachable as VERIFIED only through a mailbox connector that does not exist yet |

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
   (Phase 8.3: addressed without changing the endpoint — such transitions are classified
   `CANDIDATE_REPORTED`, email ingestion no longer transitions anything, and the candidate-facing
   status is reconciled from evidence; see 13.5.)
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
Implemented in Phase 8.3 — see section 13. Original specification:
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

## 11. Phase 8.2 — APPLY: reviewable execution workflow

Branch `phase8.2-apply-workflow`, parent `e389809` (tip of `phase8.1-prep-workflow`). Nothing here
is deployed, merged, or able to submit an application. `REAL_SUBMIT` remains hard-stopped.

### 11.1 The five states, never conflated

1. A job was **discovered** (`jobs` row).
2. An **application record** was created (owner-scoped `applications` row).
3. An **execution package** exists whose documents and answers are valid (exact versions selected,
   every check green).
4. That package was **approved** for the next step (`submit_approved`, `READY_TO_SUBMIT`).
5. A **submission confirmed by trustworthy evidence** — unreachable in this phase.

`READY_TO_APPLY` (an application pipeline status) is evidence of none of the above; the readiness
payload says so in its `note`, and no UI state claims a submission.

### 11.2 Document selection is fail-closed (`ApplyDocumentSelector`)

The known Phase 8.1 defect is fixed: `ExecutionPackageService` used to attach the newest cover
letter for the application regardless of approval. Selection is now exact, immutable and
version-bound; the package records the chosen IDs, versions and digests, and the plan JSON is
checksum-bound (`packageDigest`), so a package is reproducible and tamper-evident.

**CV requirements** (all must hold; any failure blocks, nothing is substituted):
belongs to the caller and the correct job/application; it is the exact version the user reviewed
(`cv_version_reviews`); the review is bound to the CV's current content digest; the stored PDF
bytes match the recorded SHA-256; its validation is current (`DocumentFactValidator.VERSION`) and
passing. A missing review (`CV_REVIEW_REQUIRED`), failed integrity check
(`CV_ARTIFACT_INTEGRITY_FAILED`), legacy-unvalidated CV (`CV_LEGACY_UNVALIDATED`), stale validator
(`CV_STALE_VALIDATION`) or digest mismatch (`CV_REVIEW_DIGEST_MISMATCH`) blocks the package. A newer
CV never displaces a reviewed one silently: it is reported as a warning (`NEWER_CV_NOT_USED`) and
the replacement must be reviewed to be used.

**Cover-letter requirements**: belongs to the caller/job/application; explicitly approved; current
validation acceptable; body content matches its digest and the rendered PDF matches its recorded
digest. Unapproved, changed, integrity-failed or stale-validated letters are rejected
(`COVER_LETTER_NOT_APPROVED`, `COVER_LETTER_CHECKSUM_MISMATCH`, …); there is no fallback to a
newest or previous letter. When the employer's requirement is unknown it is represented as
`UNKNOWN` — never invented, never claimed complete.

### 11.3 Pre-approval readiness gate (`ApplyReadinessService`, one model)

Before any approval the current preparation state is re-derived from the underlying records —
ownership of job/application/CV/letter/answers, job↔application association, the reviewed CV
version and digest, cover-letter requirement and approval state, current validation and file
integrity, required screening questions and confirmed answers, duplicate detection, approval-rule
decision, quota and review-queue state, and what has not been checked at all. The result separates
**blockers** (hard), **warnings** and **unknowns**; a database or dependency failure returns an
explicit `UNAVAILABLE` outcome (HTTP 503) — never a false "ready" and never a false "blocked".
There is no second readiness model and no parallel status table: PREP readiness
(`PrepReadinessService`) stays the preparation view; APPLY readiness is the approval gate.

### 11.4 API surface (Phase 8.2)

| Method | Path | Behaviour |
|---|---|---|
| GET | `/api/v1/apply/applications/{id}/readiness` | Owner-scoped readiness, recomputed per request (never cached). Foreign id → 404. Dependency failure → 503 `UNAVAILABLE`. |
| POST | `/api/v1/apply/applications/{id}/package` | Runs the readiness gate again server-side (stale UI cannot bypass a blocker), then selects exact documents and creates the execution plan. Refusal → 409 with `blockers`, `warnings`, `unknowns` and an audit entry (`APPLY_PACKAGE_REFUSED`). |
| POST | `/api/v1/automation/plans/{id}/approve-submit` | Re-runs the readiness gate, then package-drift and artifact-integrity preconditions. Confirms `READY_TO_SUBMIT` + `submissionEnabled: false` only on success. |
| PUT | `/api/v1/application-answers/{id}` | Candidate answer confirmation/editing; invalidates any approved package that carried it. |

**Approval invalidation** (`ApplyPackageGuard`): any CV review grant/withdrawal, cover-letter
change, or answer edit withdraws an approved package and returns the plan to `AWAITING_APPROVAL`,
with a `PACKAGE_APPROVAL_INVALIDATED` audit entry. Re-approval is additionally refused while the
package has drifted from current records (`PACKAGE_DRIFT`), so a stale approval can never be reused.

### 11.5 Employer questions and form-field mapping

`job_form_questions` stores what a **real, read-only inspection** of the employer's application
form exposed, with provenance (`source`, `form_url`, `capture_status`, `captured_at`): the stable
control key, question text, answer type, statically-present options, and required-ness as an
evidence-backed tri-state — `REQUIRED` (required attribute, `aria-required="true"`, the label's `*`
marker, or Greenhouse's hidden required-input mirror), `OPTIONAL` (an explicit `(optional)` /
`aria-required="false"` marker, or a labelled field wrapper rendered without the mirror), and
`UNKNOWN` when the form supplied no metadata. Absent metadata is never assumed optional
(`GreenhouseAdapter.requiredness`; cross-field mirrors can never mark a sibling field required).
Answers bind to the captured question (`application_answers.form_question_id`) and carry
`answer_origin` (`MODEL_DRAFT` / `CANDIDATE_CONFIRMED` / `CANDIDATE_EDITED`); only
candidate-confirmed answers are eligible for automatic mapping, and required questions without one
block approval.

**Honest capability limit.** Only Greenhouse public board forms are inspectable today (static
HTML; JavaScript-rendered forms report `UNAVAILABLE`). Ashby and the secondary providers expose
job descriptions but not the application form; for those jobs the form is simply not captured and
readiness reports the questions and the cover-letter requirement as **unknown**. No employer
question is fabricated, no field is inferred optional, and no form-field mapping is invented. The
`AtsAdapter.FormDescriptor`/`JobFormQuestionService.capture` seam is the safe extension point for
future form capture; no ATS adapter pretends to support application APIs it does not implement.

### 11.6 Duplicate-application semantics (cross-source, race-safe)

Identity is derived from the strongest signals actually available, in order: board/embedded
requisition identity (`greenhouse:<org>:<requisition>`, `ashby:<org>:<id>` from the
canonical/application URL), then the normalised URL (tracking-only parameters — `utm_*`, `gclid`,
`fbclid`, `ref`, `source`, … — stripped, case-normalised host, no fragment, no trailing slash),
then `src:<kind>:<externalId>`. Titles are never used: different roles with the same title stay
distinct, and one requisition seen through two boards is one role. Unknown identity (`NULL` key)
never matches anything. Enforcement is in the persistence layer: the partial unique index
`applications_profile_identity_live_uq` on `(profile_id, identity_key)` where the application is
live, so under concurrent creation exactly one application commits and the loser receives a clear
duplicate/conflict response. The existing application is identified only to its owner. Repeated
requests reuse the existing idempotency keys and never create duplicate packages or approvals.
Legacy rows keep a `NULL` key and are matched on the fly, owner-scoped.

### 11.7 Migration V036 (additive only)

`V036__apply_selection_and_duplicates.sql`:
1. `job_form_questions` (job-scoped capture with provenance, `unique (job_id, source, question_key)`).
2. `application_answers.form_question_id` (nullable FK, `on delete set null`) and `answer_origin`
   (check `MODEL_DRAFT|CANDIDATE_CONFIRMED|CANDIDATE_EDITED`, default `MODEL_DRAFT` — existing rows
   keep their honest "draft" meaning).
3. `applications.identity_key` (nullable) + the partial unique index above. No column changes
   meaning, no data is rewritten, pre-8.1 rows are unaffected.

### 11.8 State transitions and submission safety

Only `AWAITING_APPROVAL` plans can be approved (→ `READY_TO_SUBMIT`); a worker request can never
approve (`403 human approval is required`); `CLICK`/`MOCK_SUBMIT`/`REAL_SUBMIT` steps make a plan
unapprovable; no endpoint transitions anything to `SUBMITTED`. Queued or retried work revalidates
ownership, approval, document digests and package state before acting. Approval records intent
only — `submissionEnabled` is always `false`, and no submission receipt is fabricated; a future
receipt must name its evidence source and be distinguishable from inferred evidence (TRACK, 8.3).

### 11.9 Phase 8.2 verification

- Backend `mvn -B -o verify` with `-Dit.postgres.url` pointed at a real PostgreSQL 16:
  **BUILD SUCCESS**. Unit **657 run, 0 failures, 0 errors, 1 skipped** (the skipped test is
  `RedisConnectivityDiagnosticsTest`, which skips itself when local Docker Redis is unreachable).
  Integration **134 run, 0 failures, 0 errors, 0 skipped** — `UserDataIsolationIT` (19 tests) ran
  for real this phase via the `it.postgres.url` property and is not being counted from a skip.
- `ApplyWorkflowIT` (Testcontainers PostgreSQL, 11 tests): package creation and approval with real
  persisted records; an unreviewed CV blocks package creation; an answer edit or a CV-review
  withdrawal invalidates an approved package and re-approval is refused; a cross-source duplicate
  is refused with 409 and identified only to its owner; concurrent creation commits exactly one
  application (persistence-layer unique index); a retried package request reuses the identical
  plan and a retried approval does not double-approve; the live-identity uniqueness is enforced by
  a database constraint and a violating insert rolls back (a withdrawal legitimately frees the
  identity); tracking-only query parameters are not distinct roles; another user's application is
  invisible (reads as nonexistent).
- Two intermediate full runs reported `UserDataIsolationIT` 19 errors each before the green run
  above: both pointed `it.postgres.url` at the host's native PostgreSQL on port 5432 instead of
  the test container (published on 55432) and failed password authentication. No test or code was
  changed to hide the failure; the connection target was corrected and the whole suite re-run.
- `AutomationLifecycleIT` 4/4 (validation → review → explicit approval → `READY_TO_SUBMIT`,
  submission impossible; anti-bot terminal stop; stale-plan reclaim), `PrepWorkflowIT` 6/6,
  `ApplicationPipelineIT` 9/9, `ReviewQueueIT` 8/8, `DiscoveryToApplicationIT` 3/3,
  `CrossFeatureIntegrationIT` 2/2, `JobFeedIT` 7/7 and the remaining ITs green in the same run.
- Frontend: `npx tsc -b` clean; `npm test` 24 files, **240 tests, 0 failures** (11 new
  `ApplyWorkspace` tests); `npm run build` clean (Vite repeats its >500 kB chunk-size warning; not
  an error).
- Worker: `npm test` 19/19, including "Greenhouse human review never transitions to submission and
  no submit step is allowed" and the policy-gate suite.

**Defects found and fixed while proving the flow (not test accommodations):**
- `GreenhouseFieldMapper` labelled confirmed answers `application_answers (ANSWERED)` while every
  consumer (package builder, plan builder) trusts `application_answers (human-confirmed)`, so a
  candidate-confirmed answer never counted as a safe value and could not become a fill step.
- `ExecutionPackageService` listed every required employer question as a permanent human-required
  gap even when a candidate-confirmed answer was safely mapped; a gap now means "no safe value",
  and approval still refuses while any gap remains.
- `GreenhouseAdapter` ignored the label's `*` required marker on non-radio fields, so
  `work_auth`-style fields were misread as optional.

**Test repairs (fixtures modernised to the strengthened contract; assertions unchanged or
strengthened):**
- `ApplyWorkflowIT`'s fixture form is now the real Greenhouse field-wrapper shape (required fields
  carry the hidden required-input mirror or the label's `*` marker), each test uses its own
  requisition URL so duplicate protection does not cross-contaminate scenarios, and the readiness
  case asserts both the pre-capture UNKNOWN state and the post-capture provenance. The previous
  flat fixture could not express required-ness at all, and its expectation that an unmarked field
  is `REQUIRED` was unsound: required-ness is captured as evidence and a field with no metadata is
  `UNKNOWN`, never assumed optional.
- `AutomationLifecycleIT` seeds one requisition URL per scenario (Phase 8.2 duplicate protection
  correctly refuses a second application for one requisition) and a genuinely reviewed CV
  (validation plus a digest-bound review), which the Phase 8.2 approval gate requires.

### 11.10 Remaining risks and known limitations (APPLY)

- Employer-form capture covers Greenhouse public board forms only; Ashby/secondary sources report
  the form as not captured and readiness reports questions and the cover-letter requirement as
  UNKNOWN. JavaScript-rendered forms cannot be inspected (`capture_status = UNAVAILABLE`).
- Required-ness is UNKNOWN for controls with no field-scoped metadata; the UI renders that as
  unknown, not optional.
- A plan reaches `AWAITING_APPROVAL` only after a validation report (worker `complete` with
  `HUMAN_REQUIRED`); the worker suite covers the report path, the browser run is Phase 8.3/ops.
- PDF limitations from 8.1 persist (single typeface, no CJK/emoji).
- Not deployed; not merged; CI on the branch is pending.

### 11.11 REAL_SUBMIT

`REAL_SUBMIT` remains hard-stopped. No endpoint submits an application, no browser automation
submits anything, no employer-side mutation exists, `submissionEnabled` is always `false`, no
submission receipt is fabricated, and worker requests can never grant submission approval.


---

# Phase 8.2.5 — FIND across the 30 Tsenta-listed platforms + Tsenta

Branch `phase8.2.5-ats-discovery-expansion`, parent `bf38078` (tip of `phase8.2-apply-workflow`).
Phase 8.3 TRACK was not started. Nothing was merged or deployed. `REAL_SUBMIT` stays hard-stopped.

## 12.1 Platform list and how it was verified

The platform list is Tsenta's own published list ("Supported Job Platforms",
https://tsenta.com/ai-disclosure, read 2026-10-10): Workday, Greenhouse, Lever, Ashby, Rippling,
iCIMS, BambooHR, Workable, JazzHR, Jobvite, BreezyHR, Oracle Cloud, SmartRecruiters, Paylocity,
UltiPro, ADP, Dover, Gem, Zoho Recruit, BrassRing, CareerPlug, Dayforce, Hireology, Join, Phenom,
Pinpoint, Polymer, Recruitee, SAP SuccessFactors, Teamtailor. The names in the manifest
(`discovery/platform/PlatformKind`) match that list byte-for-byte and in order; aliases (UKG Pro,
Kenexa, Oracle Recruiting Cloud, Tellent Recruitee, …) resolve to the same entries.
`PlatformCoverageAuditTest` fails if the manifest drifts from that list.

Every adapter below was built against responses fetched live from a real public board during this
phase (tenant named in the table). The captured responses, trimmed, are the test fixtures under
`backend/src/test/resources/discovery/platforms/`. Nothing was modelled from documentation alone.

## 12.2 Coverage inventory

Readiness is a statement about the implementation. The candidate-independent **coverage status**
(`GET /api/v1/sources/coverage`) is derived at runtime: `BLOCKED` / `UNVERIFIED` from readiness;
for live-validated adapters `NEEDS_BOARD_CONFIGURATION` until an enabled board of that platform is
configured, `TEMPORARILY_UNAVAILABLE` when every enabled board's last run failed, otherwise
`SUPPORTED`. A fresh database therefore reports every live-validated platform except Greenhouse
(which has the seeded Stripe board) as `NEEDS_BOARD_CONFIGURATION` — that is accurate: nothing is
discovered from a platform until a board is configured.

| # | Platform | Readiness | Method (verified 2026-10-10) | Board identifier / config | Validated tenant | Listing complete? |
|---|---|---|---|---|---|---|
| 1 | Workday | LIVE_VALIDATED | CXS JSON: `POST /wday/cxs/{tenant}/{site}/jobs` (limit/offset; `total` only on page 1), detail `GET …{externalPath}` | `tenant.wdN/site` (derived from any `*.myworkdayjobs.com` URL) | nvidia.wd5/NVIDIAExternalCareerSite | yes, unless page cap hit |
| 2 | Greenhouse | LIVE_VALIDATED | Job Board API (existing `GreenhouseProvider`) | board token | stripe (seeded) | yes |
| 3 | Lever | LIVE_VALIDATED | Postings API `?mode=json&skip&limit`, EU host supported | site, `eu:site` | palantir | yes |
| 4 | Ashby | LIVE_VALIDATED | Posting API (existing `AshbyProvider`) | board name | ramp | yes |
| 5 | Rippling | LIVE_VALIDATED | `api.rippling.com/platform/api/ats/v1/board/{slug}/jobs` + per-job detail | board slug | rippling | yes |
| 6 | iCIMS | LIVE_VALIDATED | Server-rendered `/jobs/search?pr=N&in_iframe=1` following `<link rel=next>`; JSON-LD job pages | portal host `*.icims.com` | us-erac.icims.com | yes |
| 7 | BambooHR | UNVERIFIED | `/{sub}.bamboohr.com/careers/list` answers (`{meta,result}`) but the only tenants found had zero openings, so the job schema could not be validated | company subdomain | — | — |
| 8 | Workable | LIVE_VALIDATED | Widget API `/api/v1/widget/accounts/{account}?details=true` | account | huggingface | yes |
| 9 | JazzHR | LIVE_VALIDATED | Public XML feed `app.jazz.co/feeds/export/jobs/{sub}` (served as `text/html`; still parsed only as XML with a `jobs` root) | applytojob subdomain | labelmaster | yes |
| 10 | Jobvite | LIVE_VALIDATED | Server-rendered `jobs.jobvite.com/{slug}/jobs` + job pages (a posting with no description keeps it unknown) | company slug | oneco (fixtures), egnyte (live suite) | yes |
| 11 | BreezyHR | LIVE_VALIDATED | `{sub}.breezy.hr/json` + job page description | portal subdomain | breezy | yes |
| 12 | Oracle Cloud | LIVE_VALIDATED | `recruitingCEJobRequisitions` (finder findReqs, limit/offset, TotalJobsCount) + `recruitingCEJobRequisitionDetails` | `host/siteNumber` | eeho.fa.us2.oraclecloud.com/CX_45001 | yes |
| 13 | SmartRecruiters | LIVE_VALIDATED | Posting API (`limit/offset`, `totalFound`) + posting detail | company identifier | BoschGroup | yes |
| 14 | Paylocity | LIVE_VALIDATED | `Recruiting/Jobs/All/{guid}` (`window.pageData`) + JSON-LD job pages | company GUID | 3876b9cb-… (BCI Burke) | yes |
| 15 | UltiPro (UKG Pro) | LIVE_VALIDATED | `JobBoardView/LoadSearchResults` (Top/Skip, totalCount) + embedded `CandidateOpportunityDetail` | `host/tenant/boardGuid` | recruiting2.ultipro.com/HEL1006HELI/73d20f56-… | yes |
| 16 | ADP | UNVERIFIED | No public Workforce Now / Recruiting Management tenant (cid) could be found to validate against | — | — | — |
| 17 | Dover | UNVERIFIED | Careers pages are a JS app (Cloudflare Turnstile on the page); no public listing endpoint identified | — | — | — |
| 18 | Gem | LIVE_VALIDATED | `api.gem.com/job_board/v0/{board}/job_posts/` | board | gem | yes |
| 19 | Zoho Recruit | LIVE_VALIDATED | `/recruit/v2/public/Job_Openings?pagename=Careers` | career-site host | sehyog.zohorecruit.in | **never** (pagination unobserved) |
| 20 | BrassRing | **BLOCKED** | Search results are only returned to a POST carrying a session-bound `__RequestVerificationToken`; the initial page lists no jobs. Replaying anti-forgery tokens is outside "public listing" scope | — | — | — |
| 21 | CareerPlug | UNVERIFIED | Tested subdomains redirect to a single application or a sign-in page; no public listing endpoint confirmed | — | — | — |
| 22 | Dayforce | UNVERIFIED | Page is client-rendered; the search API answered 403 to a plain public request | — | — | — |
| 23 | Hireology | UNVERIFIED | No public tenant or listing endpoint confirmed | — | — | — |
| 24 | Join | LIVE_VALIDATED | `__NEXT_DATA__` job list with `pagination.pageCount` + JSON-LD job pages | company slug | join | yes |
| 25 | Phenom | LIVE_VALIDATED | `POST https://{careers host}/widgets` (`refineSearch`, from/size, totalHits) + JSON-LD job pages; custom domains accepted only via the import flow's page signature check | `host/country/lang` | careers.phenom.com/us/en | yes |
| 26 | Pinpoint | LIVE_VALIDATED | `{sub}.pinpointhq.com/postings.json` | subdomain | workwithus | yes |
| 27 | Polymer | UNVERIFIED | `api.polymer.co/v1/hire/organizations/{org}/jobs` exists but rejected every real slug tried (`organization could not be found`) | — | — | — |
| 28 | Recruitee | LIVE_VALIDATED | `{sub}.recruitee.com/api/offers/` | subdomain | vandebron | yes |
| 29 | SAP SuccessFactors | UNVERIFIED | Classic career sites render jobs client-side; no stable public listing endpoint validated | — | — | — |
| 30 | Teamtailor | LIVE_VALIDATED | `{host}/jobs.rss` (full descriptions) | `*.teamtailor.com` host | career.teamtailor.com | **never** (feed not documented as exhaustive) |
| 31 | Tsenta (aggregator) | LIVE_VALIDATED | Public SSR pages (see 12.4) | scope `jobs`, `jobs/remote`, `jobs/internships`, `jobs/entry-level` | tsenta.com/jobs | **never** |

Totals: 21 of 30 ATS platforms LIVE_VALIDATED (adapter + real fixtures + live fetch), 8
UNVERIFIED, 1 BLOCKED; Tsenta LIVE_VALIDATED. Unverified/blocked platforms have **no adapter** —
no placeholder that returns nothing or fake data — but their URLs are still recognised, so an
import explains exactly which platform it is and why it cannot be followed yet.

Live re-validation is a separate, opt-in suite: `PlatformLiveIT` (tag `live`) runs only with
`-Dlive.discovery=true` and lists one page (+ up to three postings) of every tenant above through the
production client; each posting needs a title and a genuine https application URL, and at least one
must carry a description. Fixture tests prove parsers; only that suite proves an endpoint still answers.
Last run 2026-10-11: 22/22 pass (21 ATS boards + Tsenta). That run first exposed and then confirmed
fixes for JazzHR's `text/html`-labelled XML feed and Jobvite postings that have no description.

## 12.3 Architecture (one registry, one run path, one identity model)

- `PlatformRegistry` is the only adapter registry (Greenhouse and Ashby are wrapped by
  `ProviderBackedAdapter`, so their existing providers remain the single parser for those
  platforms). Scheduler, `POST /discovery/run`, source health check and URL import all resolve
  adapters through it; there is no second source registry or identity system.
- `BoardAdapter` is two-phase: `list` enumerates a board and says whether the enumeration is
  COMPLETE (ended on the platform's own end-of-list signal — never on a page cap, budget, timeout or
  error); `detail` completes one entry when the listing lacks a description.
- `BoardDiscoveryService` runs a source: listing → complete entries ingested; detail-needing entries
  are touched if this source already knows them, otherwise fetched within a per-run detail budget,
  the rest deferred to the next run → completeness-gated removal → run metadata and health.
- `SafeHttpClient` is the only HTTP client adapters use: SSRF guard on every URL and every redirect
  hop (https only, default port, no credentials, no IP literals or internal names, host must resolve
  only to public unicast addresses — loopback, RFC 1918, CGNAT, link-local incl. 169.254.169.254,
  multicast, IPv6 ULA/link-local and mapped forms refused), redirects followed manually (max 3),
  20 s timeouts, 8 MB response cap (2 MB for import sniffing), content-type allowlist, bounded
  retries with exponential backoff + jitter on 429/5xx/timeouts honouring `Retry-After` (capped at
  10 s), a hard per-run request budget and a minimum interval between requests. Error messages
  carry category and status only — never headers or bodies.
- Known limitation: the JDK client re-resolves DNS at connect time, so a hostile resolver could
  answer differently between check and connect (rebinding). Platform hosts are pinned to the
  platform's own domains wherever it has one; Phenom (custom domains) relies on the check alone.

Normalisation into the existing `jobs` model is conservative (`Normalize`): unknown stays unknown.
Not parsed on purpose: relative dates ("Posted Today"), locale-ambiguous dates (Zoho `02/06/2026`),
dates more than a day in the future (Tsenta showed `2029-04-09`), salary numbers without an ISO
currency, currency symbols, a currency without an amount, salaries a platform marks hidden
(Pinpoint `compensation_visible=false`, UKG `PayRangeVisible=false`), placeholder locations
(`UNAVAILABLE`), and a single city/country for multi-location postings. Sponsorship is never
stored. Where a listing's structured date and a page's JSON-LD disagree, the listing wins (Phenom
JSON-LD reported the fetch date; Paylocity's was shifted by hours).

## 12.4 Tsenta

Permission: the project owner confirmed permission to collect Tsenta's public job listings. Scope
kept to: `robots.txt` (fetched first every run; `/jobs` allowed, `/private/` and `/admin/`
disallowed — a disallow, or an unreadable robots file, stops the run), the public listing pages
`/jobs[/remote|/internships|/entry-level]?page=N` and the public detail pages `/jobs/{slug}-{uuid}`.
No dashboard, account, API or authenticated surface; a 401/403/challenge is reported as
`ACCESS_DENIED`, never retried around.

Page structure found: Next.js App Router, fully server-rendered; 20 cards per page; `?page=N`
paginates server-side with disjoint pages (the site reports 7.6M postings). Each detail page
embeds a schema.org `JobPosting` and a React Server Component payload; the RSC `job` object for the
posting's own uuid carries `companyName`, `workplaceType`, `employmentType`, `locations`,
`salaryMin/Max/Currency/Period`, `datePosted`, `active`, `atsType` and `url` — the **original
employer application URL** (e.g. a `*.zohorecruit.in` or `*.csod.com` requisition page). The page
also contains Tsenta's own `dashboard.tsenta.com/dashboard?apply={uuid}` link; it is never used.

Extraction: listing cards → posting ids; detail → JSON-LD for title/description/salary, RSC job
object (decoded from the `self.__next_f.push` string chunks with a JSON parser, scanned linearly;
only the object whose `id` equals this posting's uuid) for employer, workplace, locations,
employment type, date, active flag and the employer URL. Any URL on a Tsenta host is rejected as an
application URL. A posting without a provable employer URL is **not stored** (it would otherwise
need a Tsenta URL as its apply link) and is counted as `skipped_no_employer_url`.

Identity: the Tsenta uuid is the source-scoped id; the employer URL usually carries an ATS job id
(`ats:` key), so a Tsenta posting joins the same posting found directly on the employer's ATS.

Refresh/staleness: every Tsenta listing is PARTIAL by definition (no run can enumerate millions of
postings), so absence from a run never removes anything. A Tsenta posting is closed only when its
own detail page says `active:false` (or `validThrough` has passed); otherwise it ages out through
the existing 30-day staleness/archival. A failed or incomplete Tsenta crawl therefore cannot stale
the Tsenta job set. Defaults: 3 pages and 40 detail pages per run, 1.5 s between requests, request
budget = robots + pages + details; every 12 hours when configured through import.

## 12.5 Cross-source identity and deduplication

`JobIdentity` derives, strongest first: `src:{sourceId}:{externalId}`, `ats:{platform job id}`
from the job or application URL (formats for 21 platforms; the Phase 8.2 `greenhouse:`/`ashby:`
forms are unchanged), `req:{employer}:{requisition id}`, and `url:{normalised URL}` (tracking
parameters, fragments, case, default ports and trailing slashes ignored). Employer + title is never
a key — the old company|title|location `dedup_key` is still stored but no longer merges postings.

Ingestion (`JobDiscoveryService.ingestJob`) takes transaction-scoped advisory locks on every key in
a fixed order, resolves the strongest matching key in `job_identity_keys` (V037), and stores the
posting's keys (first writer wins; a key bound to another job is conflicting evidence and is left
alone, nothing is merged on it). A match from a different source is a cross-source duplicate: it
refreshes `last_seen_at`, records provenance for that route, may upgrade a weaker application URL
(ATS job URL > employer page; aggregator URLs are never application URLs) and replaces an aggregator
canonical URL — but never rewrites content or bumps `repost_count`. A job first found through
Tsenta is promoted to the employer's ATS source (its text becomes authoritative) when that source
lists it. Re-ingestion is idempotent. Existing jobs got `src:` keys in V037 and URL/ATS keys at
startup (`JobIdentityBackfill`, same Java normalisation, idempotent, never merges old rows).

Changed behaviour (documented): the same-source UPDATED path now also refreshes `title` and
`description_text` (previously only the hash changed, leaving stale text).

## 12.6 Removal and source health

- Only a COMPLETE listing may mark this source's unseen postings `MISSING`
  (`job_source_observations.source_status/removed_at`, with `source_id`). A job gets `deleted_at`
  only when **no** source still lists it, and is restored automatically when any source lists it
  again. A failed, capped, budget-limited, partial, Teamtailor, Zoho Recruit or Tsenta listing removes nothing.
- A source's own `active:false` / passed `validThrough` closes that observation (`CLOSED`).
- Every run records `last_attempted_at`, `last_success_at`, `last_run` (listed, created, updated,
  unchanged, cross-source duplicates, deferred, closed, removed, skipped, detail failures, pages,
  requests, duration, completeness, failure category) and `health`
  (`ok`/`degraded`/`failed` + `failure_streak` + circuit). Disabled sources (flag or policy) are
  never fetched by scheduler, run, health check or import, and import never re-enables one.

## 12.7 URL import (`POST /api/v1/jobs/import-url`)

Request `{url, platform?, board?, preview?, confirm?, employer_name?}`. Validate (≤2048 chars,
http(s) only — http upgraded to https, no credentials, default ports, no IP literals/internal
names, host resolves only to public addresses) → detect from URL patterns → if nothing matches,
ONE guarded page fetch looking for an embedded board link or the Phenom signature (no crawling)
→ respond `RESOLVED` / `NEEDS_CONFIGURATION` (with the platform's board format) / `UNSUPPORTED`
(platform named with its readiness and reason) / `UNRECOGNIZED`. `preview=true` lists one page so
the board can be inspected; `confirm=true` creates a `DISCOVERY_ONLY` source on a 6-hour schedule
and runs it once (`CONFIGURED`). At most two imports run concurrently; the endpoint shares the
per-account `discovery` rate-limit bucket. Malformed or unsafe URLs are 400.

## 12.8 Source hiding (UI decision)

Candidate-facing views — feed, job cards, search, job detail, PREP, APPLY, dashboard — never say
which website, ATS platform or aggregator surfaced a posting. Enforced in the API, not just the UI:
the candidate job DTO (`JobsController.toWire`) no longer contains `source_id`, `source_name`,
`source_kind`, `external_id` or `canonical_url`; the APPLY readiness payload no longer contains the
form-capture source (`GREENHOUSE_PUBLIC_FORM`); the duplicate-application message no longer names
a platform ("same employer requisition identifier"). The frontend types drop those fields; the job
card's "via …", the detail page's "Source" row, the feed's per-source discovery panel and the
dashboard's platform wording were removed. Employer, title and the employer's genuine application
link stay visible and unaltered (an ATS-hosted application page is the employer's real form and is
shown as such; it is rendered as a labelled link rather than spelled out). An aggregator URL is
never offered as an application link (`application_url` is nulled in the DTO if one ever reaches
it). Provenance stays in the database (`jobs.source_id`, `job_source_observations`,
`job_identity_keys`) and in the operational Job Sources page, which shows sources, recorded health
(no source is ever shown "healthy" without a recorded OK run — the previous page hard-coded
"Healthy"), run counters, URL import and platform coverage. Robin is never presented as the
employer.

## 12.9 Migration V037 and API changes

`V037__platform_discovery_expansion.sql` (additive): `job_sources.kind` check widened to the 31
manifest kinds + the 3 legacy kinds; `job_sources.last_attempted_at/last_success_at/last_run`;
`job_identity_keys` (+ `src:` backfill); `job_identity_backfill_skips`;
`job_source_observations.source_id/removed_at` (+ backfill from `job-source:<uuid>` provider names)
and an index. Nothing is dropped.

API: `GET /api/v1/sources/coverage` (new); `GET /api/v1/sources` adds run metadata;
`POST /api/v1/sources/{id}/health-check` runs every supported platform through the run service and
returns the recorded state plus `run`; `POST /api/v1/discovery/run` returns the run report
(counts, completeness, failure category, `ingested` actions) for every supported platform;
`POST /api/v1/jobs/import-url` is implemented (was a `RESOLUTION_PENDING` stub); candidate job DTO
and APPLY readiness lose the provenance fields listed in 12.8.

## 12.10 Known limitations

- 8 platforms UNVERIFIED and 1 BLOCKED (12.2): no discovery from them until a public endpoint and
  a real tenant with openings can be validated; imports of their URLs say so.
- Detail-needing platforms refresh content only for new postings; already-known postings are
  touched (kept fresh) without re-fetching their description, so description edits on those
  platforms are picked up only if the listing itself changes identity.
- Teamtailor, Zoho Recruit and Tsenta listings never drive removals; their postings rely on staleness.
- DNS-rebinding window described in 12.3.
- Zoho Recruit pagination beyond the first public response has not been observed (the validated
  tenant has 8 openings), so its listings are reported partial and never remove anything.
- Workday hosts on `*.myworkdaysite.com` are not recognised (only `*.myworkdayjobs.com`).

---

# Phase 8.3 — TRACK: evidence-backed timeline, receipts and employer messages

Branch `phase8.3-track-workflow`, created from `phase8.2.5-ats-discovery-expansion` at `c2b7cbc`
(verified against GitHub before branching; PR #4 not merged). Nothing here is deployed or merged.
`REAL_SUBMIT` remains hard-stopped.

The rule this phase implements: Robin never says an application was submitted or received unless
the evidence meets the verification policy in 13.3. Everything else is labelled as what it is: a
Robin record, a statement the candidate made, Robin's inference, or employer evidence that could
not be verified.

## 13.1 TRACK architecture as found (before Phase 8.3)

| What | Where it lived | Evidence quality |
|---|---|---|
| Application created, preparation steps (CV / cover letter / answers OK or FAILED), form-plan created, package deferred, plan failed | `application_events` rows written by the pipeline and plan code | Persisted Robin records |
| Status transitions | `ApplicationStatusService` (owner API) → `applications.status` + `STATUS_CHANGED` event + audit | Persisted, but with **no evidence source**: `APPLICATION_SUBMITTED` / `CONFIRMATION_RECEIVED` could be set by the owner with nothing behind them |
| Email-driven transitions | `EmailIntelligenceService` (pre-8.3): keyword classification with a constant 0.95 "confidence", attached to whichever application's company name appeared in the text, whole body stored, then **moved the status** (event key `email:<id>`) | Inference presented as fact |
| CV reviewed/approved, cover letter generated/corrected/approved, package created/refused, form reviewed, package approved for submission, approval invalidated, plan outcomes, match approved | append-only `audit_logs` (V008 trigger) | Persisted Robin/candidate records, but not on the application timeline |
| Worker activity | `worker_events` | Worker telemetry, including the mock `APPLICATION_SUBMITTED` events of the fixture flow. **Not evidence** |
| "Prepared" | inferred from `applications.status = READY_TO_APPLY` and the plan status | No event of its own |
| Submission receipts, employer acknowledgements | nothing | No record existed |
| Mailbox access | `identity/ExternalMailboxProviders` (Gmail / Outlook classes that refuse to poll), `CredentialVault` (in-memory) | No live connector |

Decision: keep `applications.status` as Robin's internal workflow status with its meaning unchanged
(it is still shown, labelled "Recorded workflow status"), extend `application_events` additively into
the persisted evidence timeline, add two narrow tables (`application_receipts`,
`email_application_links`), and derive the candidate-facing status at read time. No second
lifecycle model, no workflow engine, no stored "reconciled status" that could drift.

## 13.2 Timeline event model

The timeline is `application_events` (persisted, now append-only) plus a read-time projection of the
append-only audit records listed below, merged in one keyset-paginated query ordered by
`(coalesce(event_time, occurred_at), id)`. Both sources are owner-checked in SQL.

Each event carries: stable id; owning profile and application (owner filled and checked by a
trigger); `type` and a neutral `summary`; `event_time` (actual time, when known) and
`event_time_precision` (`EXACT` / `DATE` / `UNKNOWN`); `occurred_at` (when Robin recorded it);
`classification`; `verification_status`; `external_id` (provider message id or quoted reference);
`email_id` / `receipt_id` evidence references; `supersedes_event_id`; `provenance` (channel,
classifier/matcher/policy versions, sender-authenticated flag); `event_key` (idempotency).

| `classification` | Meaning | Actor shown | Label |
|---|---|---|---|
| `INTERNAL` | Robin's own record (created, prepared, plan built, package built…) | Robin | Robin activity |
| `CANDIDATE_REPORTED` | Something the candidate stated or confirmed | You | Reported by you |
| `INFERRED` | Robin's interpretation of an employer message, not confirmed | Employer (message) / Robin | Inferred by Robin — not confirmed |
| `EXTERNAL` + `VERIFIED` | Employer evidence that passed the policy | Employer | Verified employer evidence |
| `EXTERNAL` + `UNVERIFIED` | Employer evidence that identifies the application but could not be verified | Employer | Employer evidence — unverified |
| `EXTERNAL` + `REJECTED` | Contradictory or wrong-application evidence | Employer | Evidence rejected |
| `EXTERNAL` + `UNKNOWN` | Employer evidence that does not establish anything by itself | Employer | Employer evidence — insufficient to verify |

`VERIFIED` is only possible on `EXTERNAL` rows (CHECK constraint).

Event types recorded (all grounded in an action or a piece of evidence):
`APPLICATION_CREATED`, `PREPARATION_STEP` (CV / cover letter / answers prepared or failed),
`FORM_PLAN_PREPARED`, `PACKAGE_DEFERRED`, `FORM_PLAN_FAILED`, `STATUS_RECORDED` (legacy status
changes, labelled as yours or as a pre-8.3 inference), `EMPLOYER_MESSAGE_RECEIVED`,
`RECEIPT_RECORDED`, `CANDIDATE_REPORT` (submitted / acknowledged / recruiter contact / assessment /
interview / offer / not progressed / withdrawn), `MESSAGE_REVIEWED`, `RECEIPT_REVIEWED`; and,
projected from audit: `CV_GENERATED`, `CV_APPROVED`, `CV_APPROVAL_WITHDRAWN`,
`COVER_LETTER_GENERATED`, `COVER_LETTER_CORRECTED`, `COVER_LETTER_APPROVED`,
`COVER_LETTER_APPROVAL_WITHDRAWN`, `PACKAGE_CREATED` ("nothing was sent to the employer"),
`PACKAGE_REFUSED`, `FORM_REVIEWED`, `PACKAGE_APPROVED` ("submission is disabled, nothing was sent"),
`PACKAGE_APPROVAL_REFUSED`, `PACKAGE_APPROVAL_WITHDRAWN`, `FORM_FILL_PAUSED`, `FORM_FILL_FAILED`,
`FORM_FILL_BLOCKED`, `FORM_INSPECTED`, `MATCH_APPROVED`. Unknown internal types map to
`INTERNAL_EVENT` ("Robin recorded an internal step"); free-text payload fields are never copied.

Not on the timeline, by design: `worker_events` (telemetry, including mock submissions) and
"submission queued / attempted" — with `REAL_SUBMIT` hard-stopped no submission is ever queued or
attempted, so no such event can truthfully exist. When a submission capability is built it must
write an `INTERNAL` "attempted" event, which the reconciler does not treat as evidence.

Timestamps are never invented. A message without a received time is stored with
`received_time_known = false`; its events have precision `UNKNOWN`, render as "Date unknown", and
are ordered by when Robin recorded them. A candidate report with only a date is `DATE` precision
and renders without a time of day.

History is preserved: `application_events` is append-only (UPDATE and direct DELETE raise; only a
cascade from deleting the application, the owner or the source message can remove rows).
Corrections are new rows: confirming or re-classifying a message writes `MESSAGE_REVIEWED` with
`supersedes_event_id` pointing at the inferred row; the UI keeps the earlier row, marked
"Corrected later — kept as history". Every candidate decision is also audited
(`TRACK_CANDIDATE_REPORT`, `TRACK_RECEIPT_RECORDED`, `TRACK_RECEIPT_REVIEWED`,
`TRACK_MESSAGE_REVIEWED`, `TRACK_MESSAGE_RECLASSIFIED`).

## 13.3 Submission evidence and the verification policy (`track-receipt-policy-v1`)

`application_receipts` records one row per piece of evidence and application: owner and
application, `evidence_type`, `channel`, `email_id`, `provider_message_id`, `external_reference`
(an application reference quoted by the message, never generated), `sender_domain`,
`evidence_time` + precision, `captured_at`, `fingerprint` (unique per application), outcome
`verification_status` with its `verification_reasons` and `policy_version`, a minimal `summary`,
the identity evidence used, and the candidate's review (`candidate_review`, time, note,
`review_version`).

Evidence types actually supported:

| Evidence type | How it arrives | Best possible outcome |
|---|---|---|
| `EMPLOYER_ACKNOWLEDGEMENT_EMAIL` via `CANDIDATE_FORWARDED` | The candidate adds an employer message (Applications page or `POST /emails/ingest`) | `UNVERIFIED` — the sender cannot be authenticated |
| `EMPLOYER_ACKNOWLEDGEMENT_EMAIL` via `MAILBOX_CONNECTOR` | `EmailIntelligenceService.ingestFromMailboxConnector` — a processing boundary only; **no connector exists**, exercised by fixture tests | `VERIFIED` when every condition below holds |
| `CANDIDATE_STATEMENT` via `CANDIDATE_ENTERED` | "I submitted this application myself" | `UNVERIFIED`, always |

Not supported, because they do not exist: an ATS confirmation response (Robin never submits),
a worker receipt, a scraped confirmation page.

The policy, in order:

1. A candidate statement → `UNVERIFIED` ("your own statement; it cannot be verified").
2. Evidence dated more than one day in the future → `REJECTED` (contradictory).
3. The message's own strong identifiers belong to a different application only → `REJECTED`.
4. Nothing identifies this application, or several applications are identified → `UNKNOWN`.
5. Otherwise `VERIFIED` only if **all** hold, else `UNVERIFIED` with each failing reason listed:
   retrieved by an authorised mailbox connector; the provider reported DKIM `pass` for a signing
   domain aligned with the From domain (equal or parent) and no SPF/DMARC `fail`; delivered to the
   connected mailbox address; identifies exactly this application by job link, posting
   identifier or requisition number; explicitly says the application was received.

The schema enforces the two hard limits independently of the code: a `CANDIDATE_STATEMENT` can
never be `VERIFIED`, and only `MAILBOX_CONNECTOR` evidence can be `VERIFIED` (CHECK constraints;
an IT proves a forged insert is refused).

A candidate can confirm or reject a receipt for an application (`review_version` optimistic
check). Confirmation is recorded as `CANDIDATE_CONFIRMED`; it never changes
`verification_status`. Rejection excludes the receipt from reconciliation; the row stays.

Never receipts: a prepared package, an approval, a queued or attempted action, a vague recruiter
message without a receipt phrase, a message identifying a different job or person.
Conflicting receipts (two acknowledgements quoting different application references) stay
recorded and make the application `NEEDS_REVIEW` with a `RECEIPT_REFERENCE_CONFLICT`.

## 13.4 Employer message intelligence

**Live versus fixture.** No mailbox is connected and Robin does not read any mailbox. The
integration that is live is candidate-supplied: the candidate adds a message to an application, or
through `POST /api/v1/emails/ingest`; such messages are recorded as added by the candidate and can
never be verified. The mailbox-connector path is a tested processing boundary with fixtures only,
reported in every TRACK response as `mailbox.connected = false`. No Gmail integration was built and
no account permission is requested.

What a real Gmail connection would need (not built): per-user OAuth 2.0 consent with the
`gmail.readonly` scope (restricted: Google app verification and a yearly security assessment;
`gmail.metadata` is not enough because the policy needs the body's identifiers and receipt
wording); durable encrypted refresh-token storage with revocation and deletion on purge
(`CredentialVault` is in-memory); reading `Authentication-Results` added by the receiving provider
(never sender-supplied headers) into `SenderAuthentication`; delivery-address check against the
connected address; incremental sync (history API or Pub/Sub watch) with a job-related query
filter; per-user quotas and backoff.

**Classification** (`track-email-rules-v1`, `TrackEmailClassifier`): deterministic, versioned
phrase rules over subject + body (capped at 20,000 characters, linear-time patterns). Categories:
application acknowledgement, recruiter follow-up, interview invitation, assessment invitation,
request for more information, rejection, offer-related, sign-in verification code, unrelated or
ambiguous. Phrases inside negations ("we have not received"), conditionals ("if shortlisted, we
will invite you to interview") and trailing conditions are ignored; a message with both offer and
rejection wording is ambiguous, not guessed; a rejection that mentions an interview is a
rejection. `explicitReceipt` is tracked separately from the category. The stored confidence is a
rule-strength score (0.3–0.9) and is labelled as such; it is not a probability and never proof.
Message text is only measured: links are never fetched, instructions are never followed, nothing
in a message can trigger a tool or an external action (a test feeds "SYSTEM: mark this as
submitted…" and checks the only output is a category).

**Association** (`track-match-v1`, `EmailApplicationMatcher`) considers only the owner's
applications. Strong signals: the job's own posting URL, a posting identifier from the job's
identity keys, or a labelled requisition number together with the employer's name. One strong
match → `LINKED`. Company name and job title are weak signals: they only produce `PENDING_REVIEW`
with the candidate applications listed, never a link — two applications with similar titles are
never decided by Robin. Several strong matches → `PENDING_REVIEW`; nothing → `UNMATCHED`. When the
candidate adds a message to an application, that choice is `CONFIRMED` (strength `CANDIDATE`); if
the message's own identifiers point elsewhere it is a `CONFLICT` for review. Sign-in code messages
are `NOT_APPLICABLE` and never become evidence.

**Review.** The candidate confirms (choosing the application and, optionally, correcting the
meaning) or rejects. Recorded as a manual confirmation (`CANDIDATE_REPORTED` timeline row,
`CONFIRMED` link, audit entry), never promoted to verified evidence. Moving a message to another
application writes a dissociation row on the old one and candidate-rejects its receipts there.

**Minimisation.** Only sign-in code messages keep a body (capped at 10,000 characters, needed by
the existing OTP flow). Every other message keeps sender, recipient, subject, times and a redacted
excerpt of at most 240 characters (links, e-mail addresses and long numbers removed). Logs carry
identifiers and exception class names only. Pre-8.3 rows keep the bodies they already had
(`body_retained = true`); no destructive migration was run; purge still deletes them.

**No status changes from messages.** Ingestion records evidence; it never transitions
`applications.status`. Notifications use neutral wording; `CONFIRMATION_EMAIL_RECEIVED` is emitted
only for a `VERIFIED` receipt, otherwise "An employer message needs your review" or "Possible
application acknowledgement — not verified".

## 13.5 Reconciliation (`track-reconcile-v1`, `ApplicationReconciler`)

Derived at read time from the recorded status and its history, candidate reports, receipts,
linked messages, the plan status and preparation results. Never stored, never written back.

Evidence strength: `VERIFIED` (4) > `CANDIDATE_CONFIRMED` = `CANDIDATE_REPORTED` (3) >
`UNVERIFIED` (2) > `INTERNAL` (1) > `INFERRED` (0). Only verified evidence and the candidate's own
reports/confirmations can set a stage; unverified employer evidence can set only Applied or
Acknowledged; Robin's inferences never set a stage (they create "Confirm what this message means"
and a pending suggestion instead).

Rules:
- The furthest stage supported by eligible evidence wins (ties: stronger basis, then later time).
  Older or weaker evidence never regresses it.
- A rejection or reported withdrawal ends progress unless it is older than the progress evidence;
  same-day ordering that cannot be established (date-only vs exact) is treated as unknown order.
  An offer and a rejection on the same day are an `OUTCOME_CONFLICT`.
- `NEEDS_REVIEW` when evidence conflicts (`RECEIPT_REFERENCE_CONFLICT`, `ASSOCIATION_CONFLICT`,
  `OUTCOME_CONFLICT`) or when the recorded status came from a pre-8.3 email inference
  (`RECORDED_STATUS_INFERRED`).
- A recorded status that is ahead of all evidence and has no history → `UNKNOWN` ("Status
  unknown"), never assumed. Recorded `WITHDRAWN` stays withdrawn (later activity is flagged);
  `FAILED` → "Stopped".
- Submission is its own dimension: `CONFIRMED` only with a verified receipt; `UNVERIFIED` when the
  candidate reported or confirmed it or unverified employer evidence identifies the application;
  `CONFLICTING`; otherwise `UNKNOWN` ("No evidence of submission"). Acknowledgement likewise
  (`VERIFIED` / `UNVERIFIED` / `NONE`).
- Every view lists what needs the candidate (`attention`), what conflicts, and what is unknown
  (always including "Robin cannot read your mailbox" while no connector exists).
- `robinSubmission.submitted` is always `false`: Robin has not submitted anything.

| Situation | Status shown | Submission |
|---|---|---|
| Package prepared (`READY_TO_APPLY`) | Prepared — not submitted | No evidence of submission |
| Package approved for submission (`READY_TO_SUBMIT`) | Prepared — not submitted ("an approval is not a submission") | No evidence of submission |
| Candidate: "I applied myself" | Applied — reported by you | Not verified |
| Forwarded acknowledgement identifying the job | Acknowledged by the employer — not verified | Not verified |
| Fixture connector acknowledgement, authenticated, delivered, identifying, explicit | Acknowledged by the employer — verified | Confirmed by verified employer evidence |
| Interview email inferred | unchanged + "Confirm what this message means" | unchanged |
| Interview email confirmed by the candidate | Interview — confirmed by you | unchanged |
| Two acknowledgements with different references | Needs your review | Conflicting evidence |

Failure mode: if the database cannot be read, the TRACK endpoints answer 503
`{availability: "UNAVAILABLE"}` and the applications list carries `track.availability =
"UNAVAILABLE"`; the UI says the status is unavailable and guesses nothing.

## 13.6 API

All routes are under `/api/v1`, authenticated, owner-scoped from the session (never from a body),
404 for another account's application, message or evidence (indistinguishable from missing),
CSRF-protected for writes, and audited.

| Method and path | Purpose | Notes |
|---|---|---|
| `GET /applications/{id}/track` | Reconciled status, reason, submission/acknowledgement, attention, unknowns, conflicts, receipts with policy reasons, messages awaiting review, mailbox state, rule versions | 404 / 503 as above |
| `GET /applications/{id}/track/timeline?cursor=&limit=` | Chronological page (default 50, max 100), opaque keyset cursor, `nextCursor` `""` at the end | 400 for a bad cursor or limit |
| `POST /applications/{id}/track/reports` | `{kind, occurredOn?, note?, clientReportId?}` candidate report | `kind` ∈ SUBMITTED, ACKNOWLEDGED, RECRUITER_CONTACT, ASSESSMENT, INTERVIEW, OFFER, REJECTED, WITHDRAWN; date 2000-01-01..tomorrow; note ≤ 500; replay returns `replayed: true` |
| `POST /applications/{id}/track/receipts/{receiptId}/review` | `{decision: CONFIRM|REJECT, note?, expectedVersion?}` | 409 on a stale version |
| `GET /track/reviews` | Employer messages awaiting the candidate (≤ 50) with candidates and match signals | `mailboxConnected` |
| `POST /track/emails/{emailId}/review` | `{decision, applicationId (confirm), category?, expectedVersion?}` | 409 stale; 409 for a sign-in code message; 400 for a code category |

Changed endpoints (behaviour changes, documented):
- `GET /applications` rows gain `track` (`availability`, `status`, `label`, `basis`, `submission`,
  `needsReview`, `attentionCount`, `pending`). `status` is unchanged in meaning.
- `POST /emails/ingest`: accepts `applicationId` (the candidate's choice, checked against the
  message); `receivedAt` is optional (missing = unknown, no longer "now"); refuses an empty message
  (400), oversized fields (400) and a foreign application (404); returns `associationStatus`,
  `needsReview`, `receiptVerification`; 503 on a database failure with nothing stored; no longer
  changes application status; joins the new `track` rate-limit bucket (30 requests per minute per
  account, shared with the TRACK writes).

## 13.7 Migration V038 (`V038__application_tracking.sql`, additive)

- `application_events`: new nullable/defaulted columns (13.2); backfilled `profile_id`; legacy
  `STATUS_CHANGED` rows classified (`email:%` keys → `INFERRED`/`UNVERIFIED`, others →
  `CANDIDATE_REPORTED`); unique `(application_id, event_key)`; timeline indexes; guard trigger
  (owner fill/check on insert, STATUS_CHANGED classification for the unchanged legacy writer,
  append-only).
- `application_receipts` (13.3) with owner trigger and CHECK constraints.
- `emails`: `channel` (legacy rows `CANDIDATE_FORWARDED`), `content_fingerprint` unique per owner,
  `body_retained`, `processing_version`, `sender_authentication`, `received_time_known`.
- `email_application_links`: one row per message; every pre-8.3 company-name correlation becomes a
  `PENDING_REVIEW` / `LEGACY` proposal with confidence 0.30 (the stored 0.95 was a constant), never
  a link.
- `audit_logs_entity_idx` for the projection.

Nothing is promoted: no receipt is created for legacy data, no legacy row is verified, and
`applications.status` is untouched. `TrackMigrationCompatibilityIT` migrates a database to V037,
writes pre-8.3 rows exactly as the old code did, migrates to V038 and checks all of this.

## 13.8 Ownership, privacy, audit and source hiding

- Every repository query filters on the session's profile; foreign ids answer 404.
- The database refuses cross-owner rows independently: the `application_events` trigger and
  `track_assert_owner()` on receipts and links (application owner, and message owner for links).
- Writes are audited; logs carry identifiers only; message bodies are minimised (13.4).
- Purge (profile delete) cascades through events, receipts, links and messages. In the product
  only purge deletes applications; if an application row is deleted, its events and receipts go
  with it and links to it are cleared (`SET NULL`), so no orphaned evidence remains (the
  pre-existing `emails.application_id` reference has no delete action and still has to be
  cleared first, as before).
- Source hiding (Phase 8.2.5) holds on every TRACK surface: timeline types are neutral
  (`GREENHOUSE_PLAN_CREATED` → `FORM_PLAN_PREPARED`, `GREENHOUSE_SUBMIT_APPROVED` →
  `PACKAGE_APPROVED`), TRACK responses carry no source, platform or canonical-URL field, and
  evidence is described by its channel ("Employer acknowledgement evidence", "Added by you",
  "Retrieved from your connected mailbox"). Internal provenance stays in the database. A message's
  own sender, subject and excerpt are the candidate's message content, shown redacted, not
  discovery provenance. Regression tests: backend `candidateFacingTrackResponsesNeverNameTheDiscoverySource`,
  frontend `SourceVisibility.test.tsx` (TRACK panel, review queue and Applications page with
  provider names planted in every payload).

## 13.9 Idempotency, retries and concurrency

- Timeline: unique `(application_id, event_key)` with `on conflict do nothing`; keys are derived
  from the evidence (`track-email:<emailId>`, `track-receipt:<receiptId>`,
  `track-report:<kind>:<clientReportId or content hash>`, `track-…-review:<id>:v<n>`).
- Messages: unique `(profile_id, message_id)` and `(profile_id, content_fingerprint)`; a losing
  concurrent insert reports a replay. Links: unique `email_id`. Receipts: unique
  `(application_id, fingerprint)`.
- Reviews use optimistic versions (`decision_version`, `review_version`); a stale decision is 409,
  never a silent overwrite.
- Each ingestion, report and review is one transaction; a failing write rolls the whole operation
  back, the API answers 503 (ingest) or 503 / 409 (TRACK), and a retry succeeds exactly once
  (`aFailedTimelineWriteRollsBackTheWholeOperation`).
- Proven with 16 concurrent ingestions of the same message: one message, one link, one receipt,
  one timeline row.
- Deleted applications: links survive with `application_id = null` and are not shown as
  candidates; reviews and reports on a missing application answer 404.

## 13.10 Frontend

`ApplicationsPage` shows the reconciled label in each card's status pill (never the raw recorded
status; "Status unavailable" when TRACK is unavailable; "Recorded: …" only for a server without
TRACK data) and keeps "Recorded workflow status". Above the list, `TrackReviewQueue` lists employer
messages awaiting the candidate. Each card has an `ApplicationTrackPanel` (loaded on demand): status
and reason, submission and acknowledgement, messages count, the Robin-has-not-submitted note,
pending suggestion, conflicts, attention, unknowns, submission evidence with the policy text and
per-receipt reasons, confirm/reject for employer evidence, messages to review, "Record an update
yourself" (stable `clientReportId` per draft so a retried report is recorded once), "Add an
employer message", and the paginated timeline with labels, honest dates and corrected-history
marks. Loading, empty, error (with retry, no backend detail), unavailable (503) and
permission-denied (401/403/404) states are explicit. Green is used only for verified evidence; no
request success, approval or worker result is ever shown as a submission.

## 13.11 Verification

Run on Windows, 2026-10-11, against the branch working tree whose code was hash-verified
identical to the authoring copy (`git write-tree` of everything except `docs/` =
`8326874fd75f26dc8dfa39aff3fa9a3228afd2c4` on both sides). The commits contain exactly that tree
plus these two documentation files. (A first full run on the previous tree passed with one TRACK IT
fewer; the authentication/CSRF IT was then added and everything below was re-run.)

- `mvn -B verify -Dit.postgres.url=jdbc:postgresql://127.0.0.1:55436/jobagent ...` (in `backend/`):
  **BUILD SUCCESS**. Unit: 953 run, 0 failures, 0 errors, 1 skipped
  (`RedisConnectivityDiagnosticsTest`, its Redis-availability assumption). Integration: 165 run,
  0 failures, 0 errors, 1 skipped (`PlatformLiveIT`, the opt-in live suite).
- New TRACK unit tests (89): `ApplicationReconcilerTest` 27, `TrackEmailClassifierTest` 24,
  `EmailApplicationMatcherTest` 14, `ReceiptVerificationPolicyTest` 11, `TrackTimelineMapperTest` 7,
  `TrackTextTest` 6; plus `RateLimitServiceTest` +2 (7 total). `UserDataScopingTest` 18/18 with the
  rewritten email service.
- PostgreSQL ITs: `ApplicationTrackingIT` 17/17 (Testcontainers `postgres:16`; forwarded vs
  fixture-connector acknowledgements, forged VERIFIED refused by the schema, approval never a
  submission, similar titles left to the candidate, inferred → confirmed → reclassified with history
  kept, 16-thread duplicate ingestion, report idempotency, rollback of a failed event write and
  retry, reconciliation over time, legacy and history-less statuses, pagination and unknown times,
  validation, sessions and CSRF required on every TRACK endpoint, cross-account isolation, source
  hiding, deletion and purge);
  `TrackMigrationCompatibilityIT` 1/1 (V037 + legacy rows → V038); `UserDataIsolationIT` 21/21
  (19 existing + 2 TRACK). Unchanged suites still green, e.g. `ApplyWorkflowIT` 11/11,
  `ApplicationPipelineIT` 9/9, `CrossSourceDiscoveryIT` 10/10, `JobFeedIT` 7/7.
- `UserDataIsolationIT` ran against a disposable `postgres:16` container published on
  `127.0.0.1:55436`, chosen after inspecting `docker ps` and the listening ports (the machine's other
  PostgreSQL services on 5432 and 5433, and the stale `robin-it-pg` container, were not used or
  touched). After the run the container held the V38 schema and the two isolation accounts, which
  confirms the suite used it; it was then removed.
- Surefire printed "going to kill self fork JVM ... 30 seconds after System.exit(0)" during
  teardown, exactly as in every Phase 8.2.5 run; results are unaffected.
- Frontend: `npx tsc -b` clean; `npm test` 290/290 (29 files; +39: `ApplicationTrackPanel` 18,
  `TrackReviewQueue` 12, `ApplicationsPage.track` 6, `SourceVisibility` +3 TRACK cases);
  `npm run build` clean (chunk-size warning only).
- Worker: `npm test` 19/19.
- Authoring copy (Linux, during development, not the final tree): every non-live IT class
  163/163 including `UserDataIsolationIT` 21/21, and `ApplicationTrackingIT` 17/17 once the
  authentication/CSRF test was added; unit tests through the JUnit launcher 945 passed, 2 skipped,
  4 aborted (Redis assumptions), 2 failed only in `ActuatorInfoExposureTest`, which needs the
  Maven-generated `build-info.properties` and passes under Maven above; frontend 290/290, tsc and
  build clean. The Windows results above are the ones for the committed tree.

## 13.12 Live versus fixture-only

| Capability | State |
|---|---|
| Timeline, reports, receipts, reconciliation, reviews | Live in the application (PostgreSQL ITs) |
| Candidate-added employer messages | Live (UI and API) — always unverified |
| Mailbox connector (Gmail/Outlook) | Not built; boundary exercised by fixtures only; responses say no mailbox is connected |
| `VERIFIED` receipts | Reachable only through the connector boundary, so fixture-only today |
| Employer-side submission and its confirmation | Not available: `REAL_SUBMIT` hard-stopped |

## 13.13 Known limitations

- No mailbox connection: employer messages exist only when the candidate adds them, and nothing
  the candidate adds can be verified, so no real application can reach "Submission confirmed"
  yet. Building the connector is the next prerequisite (13.4 lists what it needs).
- The classifier is phrase-based English; other languages and unusual wording fall to
  "Unrelated or unclear" and the candidate's review.
- Association needs a job URL, posting identifier or requisition number in the message; many
  real acknowledgements carry none and will wait for the candidate.
- The owner transition API (`POST /applications/{id}/status`) still accepts legacy statuses
  without evidence; TRACK labels the result as reported by the candidate, but the endpoint itself
  is unchanged for compatibility.
- Pre-8.3 email bodies remain stored until purge.
- The legacy `/applications/{id}/timeline` endpoint (raw events, unpaginated) is kept for the
  APPLY decision panel.
- No live or production run; not deployed.

## 13.14 REAL_SUBMIT

Unchanged and hard-stopped. TRACK adds no submission path, no employer-side action and no outbound
message. Approving a package still records approval only; the timeline and every status say
nothing was sent.


## 14. Phase 8.4 — Gmail read-only integration (feature branch, not live-verified)

Branch: `phase8.4-gmail-integration`. Draft PR #6 targets `phase8.2.5-ats-discovery-expansion`; it is intentionally separate and has not been merged or deployed.

### 14.1 Implemented path

- OAuth endpoints: `GET /api/v1/integrations/gmail/status`, `GET /authorize`, `GET /callback`, `POST /sync`, `POST /disconnect`.
- Explicit environment mapping in `application.yml`:
  - `GMAIL_CLIENT_ID` → `app.gmail.client-id`
  - `GMAIL_CLIENT_SECRET` → `app.gmail.client-secret`
  - `GMAIL_REDIRECT_URI` → `app.gmail.redirect-uri`
  - `APP_GMAIL_TOKEN_KEY` → `app.gmail.token-key` (base64-encoded 32-byte key recommended)
  - `APP_GMAIL_STATE_SECRET` → `app.gmail.state-secret`
  - `APP_FRONTEND_URL` → `app.frontend-url` (production must set the Vercel origin explicitly)
  - Optional `APP_GMAIL_LOOKBACK_DAYS` → bounded initial lookback (maximum 90 days).
- The requested and explicitly accepted OAuth scope is only `https://www.googleapis.com/auth/gmail.readonly`. No Gmail write endpoint is implemented.
- Refresh tokens are encrypted using AES-256-GCM; OAuth state is signed, bound to the profile, time-limited and consumed from a database-backed hash table exactly once.
- Initial sync reads the provider's `historyId` from `users.getProfile` before the bounded, paginated message list. Initial-list page tokens and the snapshot cursor are persisted together. Incremental sync handles sparse history records by fetching full message resources before checking their Gmail labels. Expired history cursors trigger a bounded resync; failed/partial work does not delete earlier evidence.
- Message time is taken from Gmail's provider-supplied `internalDate`, not a sender-controlled `Date` header. Inbox eligibility comes from Gmail's structured label IDs, not the sender-controlled `To` / `Delivered-To` headers.
- The provider's first `Authentication-Results` header is considered only when its authentication-service identifier is `mx.google.com`; an untrusted first header fails closed. This parsing and the receipt policy still require a real-mailbox validation before live receipt verification is claimed.
- The Connect button preserves the configured API base path (`/api/v1`) when navigating to the OAuth start endpoint.

### 14.2 Important verification boundary

Fixture/mocked tests do not prove a real Google OAuth grant or real mailbox sync. Live Gmail authorization has not been performed because credentials and the consent screen are not configured in this environment. Do not say that a live application receipt has been verified until a connected mailbox provides acceptable evidence and that end-to-end path is demonstrated.

PR #6 remains draft while the independent audit fixes are being verified. Check the latest CI result on the PR head before changing this status. No production secrets were added, no deployment was performed, no mail was sent or mutated, no real application was submitted, and `REAL_SUBMIT` remains hard-stopped.
