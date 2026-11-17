# ROBIN — Gap Register

Each entry records evidence and status. Severity: **P0** user-facing truthfulness/safety defect; **P1** blocks a core promise or phase exit; **P2** genuine but not a current phase blocker.

---

## P0 — Workday capability was overstated — **FIXED IN MERGED MASTER BASELINE**

**Evidence:** the former `AtsCapabilityRegistry.WORKDAY` row reported form-filling capability even though `WorkdayExecutionPlanService` only verifies a saved credential against the real sign-in page and does not reach or fill the application form.

**Fix:** commit `610d574` sets the Workday form/submission dimensions to `NONE`, so the API returns `SIGN_IN_READINESS_ONLY`. The fix is included in merged master. This source-level correction does not prove a real Workday application submission; Robin does not claim that capability.

---

## P1 — Phase 20 managed-mail production verification remains open

A real inbound email reached Cloudflare → Worker → Render → Postgres, was encrypted and appeared in Robin. Unit/integration tests also cover signature verification, replay protection and deduplication.

<<<<<<< HEAD
**Still not production-proven:** live duplicate webhook redelivery/idempotency, association of a received message to the correct specific application, actual automatic OTP extraction/use in a supported employer verification flow, and the complete recruiter-communication lifecycle. A single real email or an isolated Testcontainers run does not prove these live flows. See `ROBIN_PHASE20_VERIFICATION.md` §4.3.

Encryption-key continuity and the initial end-to-end delivery are resolved; do not conflate that with full Phase 20 closure.

---
=======
## P0 — Production database is rejecting writes (opened 2026-11-05 00:57 UTC; root cause confirmed 2026-11-06)

**Evidence (public `/actuator/prometheus` counters since the 00:22 UTC deploy; no logs available to this review):**
- `POST /api/v1/auth/firebase/session`: 500 `UncategorizedSQLException` on all 5 attempts — two invalid-token probes (whose only write is the audit-row insert) and three of the operator's real page loads. The SPA swallows this failure (`AuthContext` falls back to the bearer token), so the UI looks healthy.
- `POST /api/v1/automation/plans/claim-next` (worker poll; a `SELECT … FOR UPDATE SKIP LOCKED` plus one `UPDATE`): 204 ×52 early after the deploy, then 500 `UncategorizedSQLException` ×128; between 00:55:51 and 00:57:08 UTC it failed 7 times and succeeded 0 times.
- Every GET observed returns 200; `/actuator/health` (db `UP`) only proves connectivity.

**Impact while it lasts:** new sign-ups cannot be provisioned (only the exchange creates accounts); the automation worker cannot claim plans; inbound managed mail cannot be stored — the webhook's first statement is an insert, so it answers 503 and Cloudflare retries until its retry window ends; any user write in the UI fails.

**Root cause (confirmed 2026-11-06): Supabase Free-plan read-only mode.**

- The project's disk is fixed at 2 GB.
- Supabase switches the database to read-only at 95 % disk use and switches it back automatically below 95 %. Read-only queries on 6 Nov returned `default_transaction_read_only = on` with source `configuration file`.
- Writes therefore succeed only in short windows. In between they fail, which per Supabase's docs raises SQLSTATE `25006`, a class Spring cannot categorise.
- The disk filled after the discovery surge that began on 2 Nov: about 60,000 jobs and about 774,000 outbox rows.
- The Render log line itself has not been read.

The organisation's grace period ends on 12 Nov 2026. The evidence and the backup-first migration plan are in `ROBIN_DB_MIGRATION_PHASE2_PLAN.md` §0–§1.

## P2 — Errors on public endpoints reach anonymous callers as 401 (opened 2026-11-05)

**Evidence:** `GET /actuator/does-not-exist` answers 401 `"instance":"/error"`, and the failing session exchange above answered anonymous callers 401 *"Authentication is required"* instead of 500: the error dispatch to `/error` is not permitted, so any 4xx/5xx raised inside a `permitAll` endpoint is replaced by the entry point's 401. It hid this incident from an unauthenticated probe (an authenticated browser saw the real 500). Permitting the `ERROR` dispatch would make failures legible but would also let anonymous callers tell missing paths (404) from protected ones (401) — a deliberate trade-off to decide, not a one-line fix.

## Resolved — Firebase revocation hardening deployed (2026-11-05 00:22 UTC, `f3204f0`)

Render now serves `f3204f0` (`/actuator/info` `deployment.commit`); bearer-authenticated requests work on the fresh JVM, so revocation lookups succeed with the production service account. The session-exchange failure is tracked under the P0 above.

## Resolved (was P1) — Firebase revocation hardening was on `master` but not deployed (opened 2026-11-04)

**Evidence:** `a442f9b`/`484c5c5` (revocation-aware verification, session isolation) and `e3f19c4` (revocation-lookup fault classification, deleted-account session termination) change `backend/src/main`. Render's `/actuator/info` still reports `build.time` `2026-11-03T21:20:48.844Z`, which predates all three, and nine later pushes produced no new build — Render auto-deploy is evidently off. Production therefore still accepts revoked/disabled-account bearer tokens for as long as their signature is valid.

**Fix:** operator triggers a manual Render deploy of current `master`, then confirms `deployment.commit` in `/actuator/info` (added by `5cd8238`) equals `origin/master`. Procedure: `ROBIN_PHASE20_VERIFICATION.md` §3.4.

## P2 — Revocation is checked per bearer request only (opened 2026-11-04)

**Evidence:** `FirebaseAuthenticationFilter` verifies with `checkRevoked=true` only when an `Authorization: Bearer` header is present. The SPA's `apiFetch` attaches one to every request, so normal traffic is checked; a request carrying only the session cookie is not, and stays valid until the in-memory session's idle timeout (Tomcat default, 30 minutes). Each checked request also costs one authenticated Identity Toolkit lookup — expect added per-request latency after the deploy above; measure it before deciding whether a short-TTL cache is warranted.

## P2 — Receipts list answers a foreign application id with `200 {"items":[]}` (opened 2026-11-04)

**Evidence:** `TrackController#receiptsList` → `ApplicationReceiptService.getAttempts` filters by `profile_id` and returns an empty list, whereas every other application-scoped GET answers 404. Not a leak (no foreign row is returned), but inconsistent; the isolation probe treats an empty owner-filtered list as a refusal.

## P2 — Gmail-ingested mail is not threaded (opened 2026-11-04)

**Evidence:** `GmailMessageMapper.fromApi` stores Gmail's API message id (not the RFC Message-ID) in `emails.message_id` and captures `threadId` without passing it on, so `In-Reply-To`/`References` threading (`131823a`) cannot match Gmail-ingested messages. Managed-mail (Cloudflare) replies are threaded. A Gmail design would key on `threadId` or store the RFC Message-ID alongside.

## Resolved — recruiter replies follow their thread (managed mail), `131823a`

Until `131823a` neither the Worker nor the backend read `In-Reply-To`/`References`, so a follow-up that did not repeat the job link or requisition fell to a weak, review-only match. Replies now carry a `THREAD_REPLY` strong signal when an earlier message of the same owner's thread is LINKED/CONFIRMED; proven by `CloudflareRecruiterLifecycleIT` scenarios 5 and 6. Worker redeployed 2026-11-04 23:36 UTC (version `579c2681…`); live effect still requires the Render deploy (`ROBIN_PHASE20_VERIFICATION.md` §3.4).

## Resolved — `cloudflare-email-worker` CI coverage

`6c3a72a` added the `cloudflare-email-worker` job to `.github/workflows/ci.yml`; it has passed on every master run since (runs #63, #81–#88). `b2be7e0` adds a step in the same job for the Phase 20 operator-probe tests.
>>>>>>> origin/master

## P0 — Production database write outage (reported 2026-11-05; root cause confirmed 2026-11-06)

**Historical finding:** the 2026-11-05/06 production investigation found PostgreSQL writes intermittently failing while reads continued. The investigation attributed the behavior to Supabase Free-plan read-only mode triggered at the 95% disk threshold, after a discovery/outbox data surge. The operational backup-first migration work and later production database cutover are recorded in the separate database migration/operations documents.

**Status note:** this is retained as historical incident evidence, not asserted as the current live production condition on 2026-11-17. Do not infer current database health or write availability from this historical entry.

---

## P2 — Errors on public endpoints reach anonymous callers as 401

The prior investigation observed error dispatches to `/error` being replaced by the authentication entry point, obscuring some underlying 4xx/5xx responses from anonymous probes. This was recorded as an observability/API error-reporting concern; current behavior requires fresh verification before treating it as active.

---

## P2 — Firebase revocation checks and session behavior

The November 2026 investigation recorded revocation checks on bearer-authenticated requests and separate cookie-session behavior. Treat measured latency and session behavior as areas for current verification rather than assume the historical observation is unchanged.

---

## P2 — Receipts list behavior for a foreign application id

A prior review observed owner-filtered empty results rather than 404 for a foreign application id. This did not expose foreign data but was inconsistent with other application-scoped endpoints. Current behavior should be verified before treating it as an active defect.

---

## P2 — Gmail-ingested mail thread association

A previous source review identified that Gmail API message IDs and RFC Message-ID threading were handled differently from the Cloudflare-managed-mail path. Managed-mail reply threading was implemented and tested. Re-check the current Gmail mapper before scheduling a fix.

---

## P1 — Candidate document-fact validation hardening — **IMPLEMENTED AND CI-VERIFIED IN PR #11; PENDING MERGE**

The Phase 21 review found concrete weaknesses in the prior validator: experience years derived from earliest start year to present; numeric figures could match by digits alone despite a different unit/currency; work-authorisation statements were not compared against specific structured eligibility values; and a target employer/school/project/skill could be mistaken for a previous employer.

PR #11 (`phase21-candidate-knowledge-integrity`) addresses these and adds:
- merged work-month interval calculations that respect end dates and gaps;
- exact figure matching including unit/currency;
- explicit structured UK right-to-work and sponsorship contradiction blockers;
- prior-employer evidence limited to recorded work-experience employers;
- additional persisted profile evidence in cover-letter generation context;
- validator version `fact-validator-2`, with stale CV rejection in API/application packaging and explicit stale-state/revalidation handling in PREP.

**Test evidence:** PR #11 CI run #75 passed all four jobs on its recorded code head. `DocumentFactValidatorTest` 13/13, `CoverLetterReviewTest` 8/8 and PostgreSQL-backed `PrepWorkflowIT` 7/7 passed. Full backend Surefire: 1,397 tests, 0 failures, 6 skipped; Failsafe: 272 tests, 0 failures, 25 skipped. These historical run counts do not substitute for CI on the final reconciled branch head. The PR is not merged, so Phase 21 is not yet part of master.

**Residual limitations:** the validator is bounded and cannot semantically prove every sentence. `USER_VERIFIED` means candidate-attested, not third-party verified. Employer/requirement extraction includes heuristics. Work eligibility is UK-shaped, not a global country-by-country rights engine. Textual unit/currency matching may reject equivalent formatting and needs future boundary/normalisation work. No aggregate CV factual-accuracy rate has been measured.

Full trace and limitations: `docs/ROBIN_PHASE21_VERIFICATION.md`.

---

## P1 — Cloudflare email worker CI coverage — **RESOLVED BY MERGED PR #10**

PR #10 added a Node 22 GitHub Actions job that runs `npm test --prefix cloudflare-email-worker`. The merged workflow includes this gate. Keep the CI coverage status distinct from live production verification of the entire mail lifecycle.

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
