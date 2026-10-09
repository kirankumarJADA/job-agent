# Phase 8.0 — Robin Four-Stage Workflow Architecture Map

Baseline: phase7-approval-rules-ui at d57f498 (verified remote revision when this phase started).

## Product workflow

FIND → PREP → APPLY → TRACK

Phase 8.0 delivers a connected dashboard shell and a FIND vertical slice. PREP, APPLY and TRACK are mapped below for later passes; this document does not claim those later stages are finished.

## Existing architecture and status

| Stage | Existing components | Status at Phase 8.0 start | Phase 8.0 scope |
|---|---|---|---|
| FIND | DiscoveryOrchestrator, JobDiscoveryService, GreenhouseProvider, AshbyProvider, JobRepository, JobsController, JobsFeedPage, JobDetailPage, SourcesPage | Discovery providers and job feed exist; dashboard contains hard-coded summary values, Jobs Feed hides read errors, and URL import currently returns RESOLUTION_PENDING without resolution | Real source actions for enabled Greenhouse/Ashby sources, feed refresh, visible source outcomes, explicit API errors, existing indexed-job search/filter, connected workflow dashboard |
| PREP | CvArtifactService, resume ATS analysis, cover-letter services, candidate profile, JobDetailPage, ApplicationPipelineService | Components and per-job application preparation UI exist; production-quality tailored PDF and complete diff workflow require separate integration verification | Dashboard links to existing workflow; further document work deferred to Phase 8.1 |
| APPLY | ApplicationDecisionService, ApprovalRulesController, ReviewQueueController, ATS adapters, automation plans and worker | Rule engine and human review are implemented; REAL_SUBMIT remains hard-stopped | Dashboard shows the real pending review count and links to queue; readiness and live submission work deferred |
| TRACK | ApplicationController, ApplicationStatusService, application events, EmailIntelligenceService, notifications, ApplicationsPage | Application records and email intelligence exist; complete confirmed submission-receipt workflow has not been established by this pass | Dashboard shows actual application-record count without claiming submitted count; full tracking work deferred to Phase 8.3 |

## Actual API contracts reused

- GET /api/v1/jobs?limit=100: indexed job page with items and next_cursor. Dashboard appends “+” only when the response contains next_cursor.
- GET /api/v1/applications: owner-scoped application records.
- GET /api/v1/review-queue?includePaused=true: review items and pendingCount.
- GET /api/v1/sources: configured source registry.
- POST /api/v1/sources/{id}/health-check: for enabled Greenhouse/Ashby sources, the existing controller invokes the real board connector and records health. This is the discovery action used in the FIND page.
- POST /api/v1/jobs/import-url currently validates URL syntax and returns RESOLUTION_PENDING; it does not resolve or persist a job. The UI now says so explicitly and does not claim import success.

## Phase 8.0 state integrity

- A source action is considered successful only when the persisted response contains health.status = ok. Failed or unrecognised health output is not shown as success.
- Jobs Feed exposes catalogue errors instead of presenting them as zero matches.
- Dashboard values are derived from API responses; unavailable API results are shown as unavailable.
- The “Track” number means application records visible to the authenticated account. It is not a count of confirmed submissions.
- The “Apply” number is the review queue pending count. Approval is not submission.
- No new backend endpoint, table or migration is introduced in Phase 8.0.
- REAL_SUBMIT remains hard-stopped.

## Deferred stages

### PREP — Phase 8.1
Verify and complete per-role CV/cover-letter generation, grounded factual edits, a document diff/review flow, artifact hashes, downloadable PDFs and required-field analysis. Reuse the existing services before adding schema.

### APPLY — Phase 8.2
Connect readiness checks, required answers, document selection, supported ATS field mapping, duplicate protection and submission confirmation. Any live submission capability requires a separately approved implementation and safety review. Do not bypass REAL_SUBMIT.

### TRACK — Phase 8.3
Build the application timeline from owner-scoped lifecycle events and confirmed receipts. Integrate recruiter email classification while separating inferred signals from confirmed status. Add no duplicate status table.

## Verification approach

Run the full backend Maven verification (including integration tests), frontend TypeScript checks, all frontend tests, production build, worker tests and architecture/security tests in a Windows or CI environment. This implementation session cannot run the user's Windows checkout; local verification must not be claimed until CI or the local checkout runs all required commands.

## Known limitations not solved by this pass

- Direct URL resolution is still pending; the current import endpoint does not ingest jobs.
- FIND source actions shown here support enabled Greenhouse and Ashby boards only, matching the existing source-controller contract.
- Search and filtering are limited to the existing job endpoint's full-text q and status parameters; salary/location filters need backend/API support before they can be offered as functional filters.
- PREP, APPLY readiness/live submissions, and TRACK receipt/email automation need their own end-to-end implementation passes.
