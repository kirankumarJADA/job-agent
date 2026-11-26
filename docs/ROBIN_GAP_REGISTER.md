# ROBIN — Gap Register

Each entry records evidence and status. Severity: **P0** user-facing truthfulness/safety defect; **P1** blocks a core promise or phase exit; **P2** genuine but not a current phase blocker.

**Updated 2026-11-17** after PR #11 merged (`199e051`); appended **2026-11-26** with Stage 3G preparation, Stage 3H receipt architecture audit findings, Stage 3I Phase 0 security remediation, Stage 3I Phase 1 Cloudflare `message.headers` trust-boundary hardening + multi-channel receipt architecture, and Stage 3I Phase 2 durable receipt linking & reconciliation (V071) on `integration/wave-3`. Dated production observations below are historical evidence, not a statement of current production state.

---

## P0 — Managed Mail (Cloudflare / Mailgun) DKIM authentication bypass & `dkimDomain` aliasing in TRACK receipts — **REMEDIATED ON `integration/wave-3` (Stage 3I Phase 0 + Phase 1, 2026-11-26), NOT YET MERGED/DEPLOYED**

**Original Evidence (`4fe3642` on `integration/wave-3`):**
1. `cloudflare-email-worker/src/mime_parser.js:39-48` extracted `Authentication-Results` without verifying a trusted receiver `authserv-id`, took only the first matching header, and fell back to treating any `DKIM-Signature` header presence as `dkim=pass`.
2. `ManagedInboundService.java` copied `message.senderDomain()` (the unauthenticated `From:` domain) into `SenderAuthentication.dkimDomain` and passed `null` for `dmarc`.
3. `track/ReceiptVerificationPolicy.java` checked `fromDomain` alignment against `dkimDomain` without server-owned employer-domain binding.

**Remediation (`docs/STAGE_3I_PHASE_0_RECEIPT_TRUST_SECURITY_REMEDIATION.md`, `docs/STAGE_3I_PHASE_1_MULTI_CHANNEL_RECEIPT_ARCHITECTURE.md`):**
- Removed the `DKIM-Signature` existence fallback in `cloudflare-email-worker/src/mime_parser.js`, bound `Authentication-Results` strictly to explicit `TRUSTED_AUTHSERV_IDS` (topmost header only, conflicting headers fail closed), and forwarded extracted `dkimDomain` (`header.d`/`header.i`), `dmarc`, `authServId`, `authTrusted`, and `authConflict`.
- **Stage 3I Phase 1 `message.headers` follow-up:** Updated `cloudflare-email-worker/src/index.js` and `mime_parser.js` so `worker.email` derives authentication verdicts (`spf`, `dkim`, `dkimDomain`, `dmarc`, `authServId`, `authTrusted`, `authConflict`) **exclusively** from Cloudflare's documented `ForwardableEmailMessage.headers` interface (`message.headers.get('authentication-results')`), ignoring sender-injected auth headers in `message.raw` and failing closed when `message.headers` is missing/untrusted/conflicting, while continuing to parse MIME body and standard headers from `message.raw`.
- Removed `message.senderDomain()` aliasing in `ManagedInboundService.java`, wired `app.managed-mail.cloudflare.trusted-authserv-ids` in `application.yml` aligned with `cloudflare-email-worker/wrangler.toml` (`TRUSTED_AUTHSERV_IDS`, both fail-closed empty by default), and hardened `CloudflareInboundParser` to require explicit `"authTrusted": true` alongside valid `dkimDomain` and `authServId`.
- Added `EmployerDomainPolicy` (`track/ReceiptVerificationPolicy.java`, `TrackRepository.java`, `TrackEvidenceRecorder.java`) binding verified email receipts to employer corporate registrable domains, vetted ATS notification domains, or server-configured `app.track.vetted-sender-mappings`.

**Remaining deployment/operational condition:** Deployments relying on Cloudflare Email Routing DKIM verification must configure matching `TRUSTED_AUTHSERV_IDS` / `app.managed-mail.cloudflare.trusted-authserv-ids` (e.g. `mx.cloudflare.net`) and verify live Cloudflare Edge `Authentication-Results` delivery once authorized; live Cloudflare Email Routing delivery has **not** been tested in Stage 3I Phase 0/1. PR `#21` remains `OPEN` / `DRAFT` (not merged to `master` or deployed).

---

## P1 — Universal receipt verification & evidence trust architecture (Stage 3H → Stage 3I Phase 0–2, 2026-11-26) — **PARTIALLY REMEDIATED (Phase 0–1 complete; Phase 2 durable linking, reconciliation, conflict, temporal and evidence v2 IMPLEMENTED ON `integration/wave-3`, NOT MERGED; Phases 3–4 and live validation open)**

**Evidence:** `docs/STAGE_3H_UNIVERSAL_RECEIPT_VERIFICATION_ARCHITECTURE_AUDIT.md`, `docs/STAGE_3I_PHASE_0_RECEIPT_TRUST_SECURITY_REMEDIATION.md`, `docs/STAGE_3I_PHASE_1_MULTI_CHANNEL_RECEIPT_ARCHITECTURE.md`, `docs/STAGE_3I_PHASE_2_DURABLE_RECEIPT_LINKING_IMPLEMENTATION.md`.

**Gate 0B–0F Remediations Implemented ON `integration/wave-3`:**
- **Approval attestation mismatch:** `JdbcReceiptTrustStore.STRICT_APPROVAL_SQL` now includes `'UNIVERSAL_SUBMIT_APPROVED'` while keeping `'UNIVERSAL'` excluded from `track/receipt/ReceiptVerificationPolicy.ALLOWED_ATS_PLATFORMS`.
- **Level B / `RECONCILIATION_REQUIRED` conflation:** `ApplicationReceiptService.SubmissionReceipt`, `frontend/src/types.ts`, contract fixtures, and `SubmissionReceiptModal.tsx` now distinguish clean `BROWSER_OBSERVED` (`confirmed = false`, `reconciliation.required = false`) from genuine `RECONCILIATION_REQUIRED` / tampered evidence errors.
- **Managed Mail visibility:** `ApplicationTrackingService.mailboxConnected()` now recognises active, domain-configured `managed_mail_addresses` entries (`status = 'ACTIVE'`) for the owning profile and fails closed when disabled/misconfigured.
- **Timeline projection gaps:** Added `UNIVERSAL_FORM_REVIEWED`, `UNIVERSAL_SUBMIT_APPROVED`, `UNIVERSAL_SUBMIT_APPROVAL_REFUSED`, `UNIVERSAL_SUBMIT_QUEUED`, `AUTOMATION_PLAN_SUBMITTED`, and `AUTOMATION_PLAN_RECONCILIATION_REQUIRED` to `TrackRepository.PROJECTED_AUDIT_ACTIONS` and `TrackTimelineMapper#fromAudit`.
- **Worker Universal evidence hardening:** `worker/src/universal_confirmation.js` now shares `greenhouse_evidence.js` validation (`isDisqualifiedIdentifier`, `sanitizeContainerHtml`, `sanitizeEvidenceText`, `canonicalJson`), drops `data.id`, disqualifies `MOCK-*`/`REQ-*`/internal IDs and negative/generic banners, and emits canonical `robin-submission-evidence-v1` payloads.

**Stage 3I Phase 1 Architecture Completed (`docs/STAGE_3I_PHASE_1_MULTI_CHANNEL_RECEIPT_ARCHITECTURE.md`):**
- Formalised the 5-level evidence trust model (`ATTEMPT_RECORDED`, `BROWSER_OBSERVED`, `EMPLOYER_EMAIL_VERIFIED`, `SANDBOX_VERIFIED`, `EMPLOYER_STATUS_RECONCILED`), unified 7-channel ingestion contract, deterministic correlation + `TrackVocabulary`-reusing reconciliation state machine, additive `V071+` schema proposal (`V001`–`V070` untouched), and 15-scenario adversarial test plan.

**Stage 3I Phase 2 Implemented ON `integration/wave-3` (`docs/STAGE_3I_PHASE_2_DURABLE_RECEIPT_LINKING_IMPLEMENTATION.md`):**
- **V071 `application_receipt_links`:** additive, append-only correlation decisions linking each employer receipt to its application and, when Robin submitted it, to the plan and `submission_evidence` row; both original confirmation references, channel, provider, receipt fingerprint, evidence digest, evidence time + source, submission anchor + source and skew allowance are stored. One current row per receipt (unique root + unique supersede); isolation trigger rejects cross-profile/cross-application rows; UPDATE and direct DELETE refused; fail-closed schema precheck; historical receipts backfilled `V071_BACKFILL` / `NOT_ASSESSED` only. `V001`–`V070` byte-identical; V069 `submission_receipts` unchanged and still sandbox-only.
- **Reconciler progression (`track-reconcile-v2`):** Robin's own submission is now an ordinary signal, so a verified C1 acknowledgement advances a SUBMITTED application to ACKNOWLEDGED and confirmed Level D evidence advances it further; rejection/withdrawal end progress without erasing the submission record; conflicts take priority (NEEDS_REVIEW); results are order-independent (inputs sorted, total tie-break). Level B stays `Applied — not verified` with no mandatory review item; sandbox receipts are labelled `(sandbox)`.
- **`SUBMISSION_REFERENCE_CONFLICT`:** a VERIFIED acknowledgement whose reference differs from intact, worker-recorded browser evidence (or matches another application's) needs review and is never counted as verified; candidate confirm/reject resolves it through the existing receipt review and appends a superseding link row.
- **Temporal policy (`track-temporal-policy-v1`, `track-receipt-policy-v2`):** acknowledgements are checked against the PRE_SUBMIT_ATTEMPTING fence time (else the strict submit-approval time, else the candidate's reported submission date) with a 5-minute skew; earlier → UNVERIFIED + `RECEIPT_TEMPORAL_CONFLICT`; future beyond skew → REJECTED; no provider-assigned time → never VERIFIED.
- **Universal evidence `robin-submission-evidence-v2`:** correlation, redirect chain (host + status), final origin, identifier source, per-document upload proof (`FILE_PART_OBSERVED` / `ATTACHED_NOT_CONFIRMED` / `NOT_ATTACHED` / `NO_FIELD`), personal-data redaction and a canonical self-digest re-derived by the backend (`CanonicalJson.java`, cross-language contract fixture); off-origin redirect hops rejected by the worker and the server; v1 evidence still accepted.

**Still open (Stage 3I Phases 3–4 and Phase 2 residuals):**
- Future Outlook/IMAP/webhook adapters (`ExternalMailboxProviders.Outlook`/`Imap` remain fail-closed stubs; no employer webhook endpoint exists); `WEBHOOK_CONNECTOR` channel not added.
- A browser cannot observe multipart file bytes (Chromium omits them even with route interception), so document upload proof tops out at `FILE_PART_OBSERVED`; files uploaded by a separate request on an earlier page are reported `ATTACHED_NOT_CONFIRMED`, never as sent.
- Applications Robin never attempted and the candidate gave no submission date have no temporal anchor (`NO_SUBMISSION_ANCHOR`): only the future-dated rule applies.
- `UNIVERSAL` remains excluded from trusted receipt policy (no Level C2 for Universal); C1 depends on mailbox connectors whose live delivery is unverified (see the P0 and Stage 3G entries).
- Receipts recorded before V071 keep `policy_version = track-receipt-policy-v1`; ones verified without a provider time are now shown as not verified at reconcile time.

---

## P2 — Robin's own submission mirror was read as a candidate report — **FIXED AT READ TIME ON `integration/wave-3` (Stage 3I Phase 2, 2026-11-26)**

**Evidence:** `AutomationController#markApplicationSubmitted` mirrors a real submission onto the pre-8.3 application status (`STATUS_CHANGED`, actor `worker`, `payload.event_key = automation.real_submit[.start]:<planId>`). The V038 `application_events_guard` trigger classifies every non-email `STATUS_CHANGED` row as `CANDIDATE_REPORTED`. Before Phase 2 the reconciler returned early for SUBMITTED plans, hiding it; once Robin's submission became an ordinary signal, a browser-observed submission read `Applied — reported by you` (found by `ReceiptReconciliationIT`).

**Fix:** `TrackRepository#snapshotParts` ignores `STATUS_CHANGED` rows whose `event_key` starts with `automation.` (Robin's submission is represented by its plan and evidence). Historical rows are not rewritten and V038 is unchanged; the trigger still mislabels these rows for any other reader of `application_events.classification`.

---

## P1 — Controlled external validation (Stage 3G) — **BLOCKED: no operator authorisation (2026-11-26)**

**Evidence:** `docs/STAGE_3G_CONTROLLED_EXTERNAL_VALIDATION.md`. Repository configuration, deployment docs and environment names contain no authorisation record, no staging hostname and no sandbox allow-list; the harness (`staging/harness/run.mjs`) refuses with `AUTHORISATION_RECORD_ABSENT` and records all 15 matrix scenarios as external BLOCKED. No external host was contacted and no external submission occurred.

**Prepared (not deployed):** one shared synthetic fixture (`staging/universal-employer-fixture/src/app.js`, also used by local tests), a Cloudflare Worker + Durable Object package with host gate, admin-token diagnostics/teardown, HSTS and data expiry, a staging configuration contract, and the authorisation-gated harness.

**Still open:** operator authorisation and provisioning; the staging scenario driver/seeding contract; UNIVERSAL verified-receipt design; host-level egress policy; backend AddressGuard connection pinning; SSO sub-domain policy; PDF cover-letter end-to-end proof; `ROBIN_WORKER_SESSION_KEY` provisioning.

---

## P1 — Universal browser network isolation and credential scoping (Stage 3E SEC-1 … SEC-8) — **IMPLEMENTED ON `integration/wave-3` (Stage 3F), NOT MERGED**

**Evidence:** Stage 3E (`docs/STAGE_3E_EXTERNAL_VALIDATION_READINESS_AUDIT.md`) demonstrated live bypasses of the worker's `page.route` guard (WebSocket, Service Worker, popup), no DNS-rebinding protection, plaintext `storage.json` sessions, and a `/employer-credentials/resolve` endpoint without employer-domain binding.

**Fix (Stage 3F, `docs/STAGE_3F_SECURITY_HARDENING_IMPLEMENTATION.md`):** connection-level loopback egress proxy (DNS resolved and pinned by the worker, private/metadata/special ranges refused), context-level guard with WebSocket routing, Service Workers blocked, popups closed, no plaintext session files (optional encrypted vault), exact-host credential binding to the application's job/plan host with an active run, 10 MiB artifact cap, cross-origin header stripping, URL redaction.

**Still open:** proven only against local fixtures and injected DNS answers; no authorised external test environment exists, so external validation (Stage 3G) remains blocked; host-level egress control is not deployed; backend `AddressGuard` fetches still resolve before connecting.

---

## P0 — Workday capability was overstated — **FIXED IN MERGED MASTER BASELINE**

**Evidence:** the former `AtsCapabilityRegistry.WORKDAY` row reported form-filling capability even though `WorkdayExecutionPlanService` only verifies a saved credential against the real sign-in page and does not reach or fill the application form.

**Fix:** commit `610d574` sets the Workday form/submission dimensions to `NONE`, so the API returns `SIGN_IN_READINESS_ONLY`. The fix is included in merged master. This source-level correction does not prove a real Workday application submission; Robin does not claim that capability.

---

## P1 — Phase 20 managed-mail production verification remains open

A real inbound email (2026-11-03) reached Cloudflare → Worker → Render → Postgres, was encrypted and appeared in Robin. Unit/integration tests also cover signature verification, replay protection and deduplication.

**Still not production-proven:** live candidate tenant isolation, live duplicate webhook redelivery/idempotency, association of a received message to the correct specific application, actual automatic OTP extraction/use in a supported employer verification flow, and the complete recruiter-communication lifecycle. A single real email or an isolated Testcontainers run does not prove these live flows. See `ROBIN_PHASE20_VERIFICATION.md` §5 (status matrix) and §6.0–§6.4 (operator procedures).

Encryption-key continuity and the initial end-to-end delivery are resolved; do not conflate that with full Phase 20 closure.

---

## P0 — Production database write outage (opened 2026-11-05 00:57 UTC; root cause confirmed 2026-11-06) — **HISTORICAL INCIDENT RECORD**

**Status note (2026-11-17):** this is retained as historical incident evidence, not asserted as the current live production condition on 2026-11-17. Do not infer current database health or write availability from this historical entry. The operational backup-first migration work and later production database cutover are recorded in the separate database migration/operations documents; in this repository, `ROBIN_DB_MIGRATION_PHASE2_PLAN.md` holds the plan and its dated execution records through 9 Nov 2026 (including the Render backend suspension on 7 Nov, §10.13), and the production-cutover branch merged to `master` as `c2ffe8d` on 16 Nov 2026.

**Historical evidence (public `/actuator/prometheus` counters since the 2026-11-05 00:22 UTC deploy; no logs available to that review):**
- `POST /api/v1/auth/firebase/session`: 500 `UncategorizedSQLException` on all 5 attempts — two invalid-token probes (whose only write is the audit-row insert) and three of the operator's real page loads. The SPA swallows this failure (`AuthContext` falls back to the bearer token), so the UI looks healthy.
- `POST /api/v1/automation/plans/claim-next` (worker poll; a `SELECT … FOR UPDATE SKIP LOCKED` plus one `UPDATE`): 204 ×52 early after the deploy, then 500 `UncategorizedSQLException` ×128; between 00:55:51 and 00:57:08 UTC it failed 7 times and succeeded 0 times.
- Every GET observed returns 200; `/actuator/health` (db `UP`) only proves connectivity.

**Impact while it lasts (as recorded 2026-11-05):** new sign-ups cannot be provisioned (only the exchange creates accounts); the automation worker cannot claim plans; inbound managed mail cannot be stored — the webhook's first statement is an insert, so it answers 503 and Cloudflare retries until its retry window ends; any user write in the UI fails.

**Root cause (confirmed 2026-11-06): Supabase Free-plan read-only mode.**

- The project's disk is fixed at 2 GB.
- Supabase switches the database to read-only at 95 % disk use and switches it back automatically below 95 %. Read-only queries on 6 Nov returned `default_transaction_read_only = on` with source `configuration file`.
- Writes therefore succeed only in short windows. In between they fail, which per Supabase's docs raises SQLSTATE `25006`, a class Spring cannot categorise.
- The disk filled after the discovery surge that began on 2 Nov: about 60,000 jobs and about 774,000 outbox rows.
- The Render log line itself has not been read.

The organisation's grace period was recorded (2026-11-06) as ending on 12 Nov 2026. The evidence and the backup-first migration plan are in `ROBIN_DB_MIGRATION_PHASE2_PLAN.md` §0–§1.

---

## P2 — Errors on public endpoints reach anonymous callers as 401 (opened 2026-11-05)

**Evidence (recorded 2026-11-05):** `GET /actuator/does-not-exist` answers 401 `"instance":"/error"`, and the failing session exchange above answered anonymous callers 401 *"Authentication is required"* instead of 500: the error dispatch to `/error` is not permitted, so any 4xx/5xx raised inside a `permitAll` endpoint is replaced by the entry point's 401. It hid this incident from an unauthenticated probe (an authenticated browser saw the real 500). Permitting the `ERROR` dispatch would make failures legible but would also let anonymous callers tell missing paths (404) from protected ones (401) — a deliberate trade-off to decide, not a one-line fix.

**Status (2026-11-17):** recorded as an observability/API error-reporting concern; current behavior requires fresh verification before treating it as active.

---

## Resolved — Firebase revocation hardening deployed (2026-11-05 00:22 UTC, `f3204f0`)

**Historical deployment record (2026-11-05):** Render served `f3204f0` (`/actuator/info` `deployment.commit`); bearer-authenticated requests worked on the fresh JVM, so revocation lookups succeeded with the production service account. The session-exchange failure is tracked under the P0 above. This is not a statement of the current production deployment.

---

## Resolved (was P1) — Firebase revocation hardening was on `master` but not deployed (opened 2026-11-04)

**Evidence (2026-11-04):** `a442f9b`/`484c5c5` (revocation-aware verification, session isolation) and `e3f19c4` (revocation-lookup fault classification, deleted-account session termination) change `backend/src/main`. Render's `/actuator/info` still reports `build.time` `2026-11-03T21:20:48.844Z`, which predates all three, and nine later pushes produced no new build — Render auto-deploy is evidently off. Production therefore still accepts revoked/disabled-account bearer tokens for as long as their signature is valid.

**Fix:** operator triggers a manual Render deploy of current `master`, then confirms `deployment.commit` in `/actuator/info` (added by `5cd8238`) equals `origin/master`. Procedure: `ROBIN_PHASE20_VERIFICATION.md` §3.4. Resolved by the 2026-11-05 deploy recorded above.

---

## P2 — Revocation is checked per bearer request only (opened 2026-11-04)

**Evidence (2026-11-04):** `FirebaseAuthenticationFilter` verifies with `checkRevoked=true` only when an `Authorization: Bearer` header is present. The SPA's `apiFetch` attaches one to every request, so normal traffic is checked; a request carrying only the session cookie is not, and stays valid until the in-memory session's idle timeout (Tomcat default, 30 minutes). Each checked request also costs one authenticated Identity Toolkit lookup — expect added per-request latency after the deploy above; measure it before deciding whether a short-TTL cache is warranted.

**Status (2026-11-17):** treat measured latency and session behavior as areas for current verification rather than assume the historical observation is unchanged.

---

## P2 — Receipts list answers a foreign application id with `200 {"items":[]}` (opened 2026-11-04)

**Evidence (2026-11-04):** `TrackController#receiptsList` → `ApplicationReceiptService.getAttempts` filters by `profile_id` and returns an empty list, whereas every other application-scoped GET answers 404. Not a leak (no foreign row is returned), but inconsistent; the isolation probe treats an empty owner-filtered list as a refusal.

**Status (2026-11-17):** current behavior should be verified before treating it as an active defect.

---

## P2 — Gmail-ingested mail is not threaded (opened 2026-11-04)

**Evidence (2026-11-04):** `GmailMessageMapper.fromApi` stores Gmail's API message id (not the RFC Message-ID) in `emails.message_id` and captures `threadId` without passing it on, so `In-Reply-To`/`References` threading (`131823a`) cannot match Gmail-ingested messages. Managed-mail (Cloudflare) replies are threaded. A Gmail design would key on `threadId` or store the RFC Message-ID alongside.

**Status (2026-11-17):** re-check the current Gmail mapper before scheduling a fix.

---

## Resolved — recruiter replies follow their thread (managed mail), `131823a`

Until `131823a` neither the Worker nor the backend read `In-Reply-To`/`References`, so a follow-up that did not repeat the job link or requisition fell to a weak, review-only match. Replies now carry a `THREAD_REPLY` strong signal when an earlier message of the same owner's thread is LINKED/CONFIRMED; proven by `CloudflareRecruiterLifecycleIT` scenarios 5 and 6. Worker redeployed 2026-11-04 23:36 UTC (version `579c2681…`); live effect still requires the Render deploy (`ROBIN_PHASE20_VERIFICATION.md` §3.4). *(Recorded 2026-11-04. The 2026-11-05 Render deploy of `f3204f0` includes `131823a` — `ROBIN_PHASE20_VERIFICATION.md` §3.4 — but live thread-reply behavior has not been exercised in production; see §6.4 there.)*

---

## P1 — Candidate document-fact validation hardening — **IMPLEMENTED, CI-VERIFIED AND MERGED (PR #11)**

The Phase 21 review found concrete weaknesses in the prior validator: experience years derived from earliest start year to present; numeric figures could match by digits alone despite a different unit/currency; work-authorisation statements were not compared against specific structured eligibility values; and a target employer/school/project/skill could be mistaken for a previous employer.

PR #11 (`phase21-candidate-knowledge-integrity`) addresses these and adds:
- merged work-month interval calculations that respect end dates and gaps;
- exact figure matching including unit/currency;
- explicit structured UK right-to-work and sponsorship contradiction blockers;
- prior-employer evidence limited to recorded work-experience employers;
- additional persisted profile evidence in cover-letter generation context;
- validator version `fact-validator-2`, with stale CV rejection in API/application packaging and explicit stale-state/revalidation handling in PREP.

**Test evidence:** PR #11 CI run #75 passed all four jobs on its recorded code head `cd668c6`. `DocumentFactValidatorTest` 13/13, `CoverLetterReviewTest` 8/8 and PostgreSQL-backed `PrepWorkflowIT` 7/7 passed. Full backend Surefire: 1,397 tests, 0 failures, 6 skipped; Failsafe: 272 tests, 0 failures, 25 skipped. These run #75 counts do not substitute for CI on the final branch head: the final PR head `0825275` passed CI run #122 on all four jobs (backend, browser worker, frontend, Cloudflare email worker); its per-suite counts are not restated here.

**Merge record:** PR #11 merged into `master` on 2026-11-17 as merge commit `199e051d908c552ee7bc6db283998fea85a394d6`, whose tree is identical to `0825275`; the push-triggered run #123 on `199e051` completed successfully. Phase 21 is now part of `master`. This is not a production deployment claim.

**Residual limitations:** the validator is bounded and cannot semantically prove every sentence. `USER_VERIFIED` means candidate-attested, not third-party verified. Employer/requirement extraction includes heuristics. Work eligibility is UK-shaped, not a global country-by-country rights engine. Textual unit/currency matching may reject equivalent formatting and needs future boundary/normalisation work. No aggregate CV factual-accuracy rate has been measured.

**Residual risks:** cover-letter prose remains LLM-generated and must be reviewed by the candidate; validation does not make it inherently reliable. CVs validated under `fact-validator-1` are intentionally stale and must be regenerated and reviewed (older cover letters revalidated) before application use. Skipped tests are not executed proof: run #75 skipped `UserDataIsolationIT` by its environment assumption, along with four live-ingestion tests. No production deployment of Phase 21 is recorded, so its behavior on production data is unverified.

Full trace and limitations: `docs/ROBIN_PHASE21_VERIFICATION.md`.

---

## P1 — Cloudflare email worker CI coverage — **RESOLVED BY MERGED PR #10 (`6c3a72a`)**

PR #10 added a Node 22 GitHub Actions job that runs `npm test --prefix cloudflare-email-worker` — the fourth CI job, mirroring the `frontend`/`worker` pattern, that this entry originally called for. The merged workflow includes this gate. As recorded on 2026-11-04, it had passed on every `master` run since `6c3a72a` (runs #63, #81–#88); `b2be7e0` adds a step in the same job for the Phase 20 operator-probe tests. PR #11's final CI run #122 also passed this job. Keep the CI coverage status distinct from live production verification of the entire mail lifecycle.

---

## P1 — ATS submission is not yet production-verified

No ATS adapter is assigned `SUBMISSION_VERIFIED` by design. The registry's rules forbid real-employer test submissions. Greenhouse has a guarded submit path tested against fixtures, but this is not a verified production or candidate-owned sandbox submission. Any product messaging that implies submission has been independently confirmed would be false.

---

## P2 — Lever / Zoho Recruit: real submission is blocked or partial by design

Lever: hCaptcha is enforced in both the backend plan-type gate and browser worker plan validator. Zoho: identity-field filling is partial and most custom questions remain surfaced as gaps; no real submission occurs. Keep capability labels honest; do not remove safety gates to improve a raw ATS count.

---

## P2 — Workday account-creation verification is unknown

`AtsCapabilityRegistry` records that the real post-creation verification mechanism (OTP, link, or neither) has not been established. It is honestly labelled `PARTIAL`; resolve only with safe evidence before expanding Workday claims.

---

## P2 — Tsenta ATS-count inconsistency not reproduced

A direct check on 2026-11-03 found both `tsenta.com` and `help.tsenta.com` consistently claimed "30+ ATSes". Do not repeat the earlier "15+ vs 30+" inconsistency absent new evidence.

---

## Not yet independently gap-analysed

Discovery freshness/dedup accuracy, matching/ranking quality, hard-filter integrity under adversarial scores, broader CV factual-accuracy rates, automation-intelligence limits, review-before-send diff quality, UX/accessibility and commercial readiness remain unscored or only partially explored. Services existing in the codebase is not proof that their outputs meet the intended product standard. Follow the evidence passes in `ROBIN_BUILD_ROADMAP.md`.
