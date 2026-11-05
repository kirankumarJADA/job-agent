# ROBIN — Gap Register

Each entry cites exact file/line evidence. Severity: **P0** (user-facing truthfulness/safety bug, fix before anything else) · **P1** (blocks Phase 20 or a core promise) · **P2** (real but not blocking).

---

## P0 — Workday capability was overstated to the candidate — **FIXED, commit `610d574`**

**Status:** Fixed in this review cycle. The data values described below have already been corrected; this entry is kept as the evidentiary record of why the fix was needed.

**Evidence:** `backend/src/main/java/com/personal/jobagent/automation/AtsCapabilityRegistry.java:140-149` sets the `WORKDAY` row's `formFilling = FULL` (12th positional value in the `AtsCapabilities` record, confirmed via `AtsCapabilities.java:35-51`). `AtsCapabilities.summary()` (`AtsCapabilities.java:59-74`) returns `FORM_FILL_AVAILABLE` whenever `formFilling != NONE`, with no other gate. This summary is served verbatim to the frontend via `GET /api/v1/ats-capabilities` (`AtsCapabilityController.java:43-46`).

Meanwhile `WorkdayExecutionPlanService.java:29-83` — the actual implementation — states explicitly, as its own module Javadoc: *"this service implements exactly the stage that IS genuinely supported end to end: verifying the candidate's SAVED Workday credential... It NEVER attempts the application form itself."* Even `AtsCapabilityRegistry`'s own class-level comment (lines 47-62, the prose describing what was verified) repeats this same limitation correctly. **The row's own Javadoc (lines 144-149) and its data values (line 143) contradict the file's own class-level comment three paragraphs above it.**

**Impact:** a candidate opening a Workday job in Robin is told the form can be filled, when the shipped service can only check that a saved password still works. This is exactly the class of claim the whole project's stated philosophy ("never claim a capability the code doesn't prove") exists to prevent.

**Fix (applied):** `AtsCapabilityRegistry.WORKDAY`'s `formInspection`, `requiredFieldValidation`, `questionAnswering`, `documentAttachment`, `formFilling`, `finalSubmission`, and `submissionConfirmation` values are now `NONE`, so `summary()` correctly falls through to `SIGN_IN_READINESS_ONLY`. `AtsCapabilityRegistryTest` and `AtsCapabilityControllerTest` were updated to match (re-run and confirmed independently in this review cycle — see the accompanying review's Task D for the exact command and result). Committed as `610d574`, not yet pushed to `origin/master`.

---

## P1 — Phase 20 managed mail: partially verified in production, not fully closed (full detail in `ROBIN_PHASE20_VERIFICATION.md`)

**Update 2026-11-03:** a real inbound email completed Cloudflare → Worker → Render → Postgres → UI for the first time — one message, encrypted, persisted, zero purged. The `APP_SECRETS_KEY`-missing state is resolved (new key generated and added, backend restarted). Encryption-key continuity is also resolved: a pre-provisioning count found zero rows in both `employer_credentials` and `managed_inbound_messages`, so no row can depend on a lost prior key. **Still open:** duplicate webhook redelivery/idempotency in production, email-to-application association, automatic OTP extraction, and the full recruiter-communication lifecycle — none of these are proven yet. See `ROBIN_PHASE20_VERIFICATION.md` §4.3.

## P0 — Production database is rejecting writes (opened 2026-11-05 00:57 UTC; root cause not yet confirmed)

**Evidence (public `/actuator/prometheus` counters since the 00:22 UTC deploy; no logs available to this review):**
- `POST /api/v1/auth/firebase/session`: 500 `UncategorizedSQLException` on all 5 attempts — two invalid-token probes (whose only write is the audit-row insert) and three of the operator's real page loads. The SPA swallows this failure (`AuthContext` falls back to the bearer token), so the UI looks healthy.
- `POST /api/v1/automation/plans/claim-next` (worker poll; a `SELECT … FOR UPDATE SKIP LOCKED` plus one `UPDATE`): 204 ×52 early after the deploy, then 500 `UncategorizedSQLException` ×128; between 00:55:51 and 00:57:08 UTC it failed 7 times and succeeded 0 times.
- Every GET observed returns 200; `/actuator/health` (db `UP`) only proves connectivity.

**Impact while it lasts:** new sign-ups cannot be provisioned (only the exchange creates accounts); the automation worker cannot claim plans; inbound managed mail cannot be stored — the webhook's first statement is an insert, so it answers 503 and Cloudflare retries until its retry window ends; any user write in the UI fails.

**Leading hypothesis (unconfirmed):** Postgres in read-only mode — Supabase enforces this when a project exceeds its storage quota — which raises SQLSTATE `25006`, a class Spring cannot categorise. It fits reads succeeding, both unrelated write paths failing, and `claim-next` succeeding only before a switch point. **Confirm with:** the Render log line for `UncategorizedSQLException` (it names the SQL state and Postgres message), and in the Supabase SQL editor (read-only): `show default_transaction_read_only;` and `select pg_size_pretty(pg_database_size(current_database()));`.

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

**Fix:** add a fourth CI job mirroring the `frontend`/`worker` pattern (Node setup, `npm install`, `npm test`), ~10 lines.

## P1 — No ATS ever reaches `SUBMISSION_VERIFIED` by design — product messaging must not imply otherwise

Not a code bug — a product-communication risk. `AtsCapabilityRegistry`'s own rules forbid ever testing against a real employer, so this state is structurally permanent until a controlled real-submission test is explicitly authorized by the candidate for their own application. Any roadmap or marketing copy claiming "submission verified" for Greenhouse/Ashby today would be false.

## P2 — Lever / Zoho Recruit: real submission permanently blocked, not a near-term fix

Lever: hCaptcha, enforced in two independent places (`AutomationPlanRepository#prepareRealSubmission` plan-type gate + `browser_worker.js#validateLeverPlan`). Zoho: most custom questions remain unanswerable gaps even where identity-field filling works. These are correctly labeled `PARTIAL`/never-submits in the registry already — no overclaim found here, unlike Workday. No action needed beyond what's already honestly documented.

## P2 — Workday account-creation verification is unknown

**Evidence:** `AtsCapabilityRegistry.java:54-62` — account creation (`WORKDAY_CREATE_ACCOUNT`) is implemented and tested, but *"the real post-creation verification mechanism (an OTP? a link? neither?) is unknown."* This is honestly flagged as `PARTIAL`, not overstated — listed here only because it's a real open question worth resolving before Workday work expands.

## P2 — Unverified claim retired this session: "15+ vs 30+ ATS" Tsenta inconsistency

Direct check of `tsenta.com` and `help.tsenta.com` on 2026-11-03 found both consistently say "30+ ATSes." This specific inconsistency does not currently reproduce and should not be repeated in future competitive material (see `ROBIN_TSENTA_SCORECARD.md`).

---

## Not yet independently gap-analyzed this session (flagged, not assumed complete or broken)

Discovery adapters' actual freshness/dedup accuracy, matching/ranking precision, CV-tailoring factual-accuracy rate, and automation-intelligence limits all have real services present (`JobDeduplicationService`, `JobMatchService`, `HardFilterService`, `SemanticSkillMatcher` — all confirmed to exist by file, not yet read line-by-line for correctness). Per your own rule ("do not assume missing or complete from this list alone"), these need a dedicated evidence pass before being scored — see `ROBIN_BUILD_ROADMAP.md` for sequencing. Scoring them now without reading them would be exactly the kind of unverified claim this whole audit is trying to eliminate.
