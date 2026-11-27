# Stage 3I Phase 6 — Independent Security Verification, End-to-End Integration & Remaining Critical Gap Closure

**Date:** 2026-11-27 · **Branch:** `integration/wave-3` · **Baseline:** `1d5841e` (Stage 3I Phase 5) · **PR #21:** OPEN, DRAFT (never merged or marked ready)

This document records what was independently reproduced, what was found broken while reproducing it, what was fixed, and what is still not proven. It describes the changes in the commit that contains it. No real employer application was submitted, no external system was contacted, nothing was deployed, and no production database was accessed.

---

## 0. Bottom line

**Robin cannot autonomously apply to real employer websites today, and this phase does not change that.** What is proven is narrower:

1. **Every real submission still needs two human actions per application**: `approve-submit` and then `prepare-submit`. Auto-apply creates and prepares applications; it never submits (`AutoApplyBatchService`; `AutomationController#approve`/`#prepareSubmit` refuse the worker identity with 403).
2. **No real employer site has been driven end to end.** Every browser submission in the test suites runs against local fixture pages on `127.0.0.1` (worker `universal_engine.test.js` and others) or against simulated worker reports sent through the real backend HTTP API (`UniversalBrowserE2EIT`, `ReceiptReconciliationIT`). Neither is evidence about any real employer portal.
3. **CAPTCHA and anti-bot pages are hard stops.** MFA and login walls become a human handoff. Neither is solved or bypassed.
4. **External validation is NO-GO** (§9). There is no operator authorisation, synthetic staging infrastructure, credentials or production-grade secrets.
5. **A green CI run proves the code behaves as its tests describe, on fixtures.** It does not prove behaviour against a real ATS, real mail delivery or a real network.

During independent reproduction, Phase 6 found and fixed **one P1 submission-approval defect** (§4, APPLY). It also found and fixed **four smaller defects** in the Phase 5 remediations (§2: A2, A4, A5, A6). The first CI run of this phase then exposed **a navigation race in the worker's live-form inspection**, which was fixed as well (§6).

---

## 1. Workstream 0 — Preflight

| Check | Result |
|---|---|
| Local HEAD vs `origin/integration/wave-3` | Both `1d5841e865d97d5ed6bad1fcbdf7283cb530e9be` (the Phase 5 commit). No new upstream work, nothing overwritten. |
| PR #21 | `OPEN`, `isDraft: true`, head `1d5841e`. |
| Migrations | `V001`–`V073` present. None modified in this phase; no `V074` added (§7). |
| Docker / Testcontainers | Docker 29.0.1 running locally. The PostgreSQL 16 `*IT` suites were run locally in this phase, not only in CI. |

---

## 2. Workstream A — Independent reproduction of P1 fixes A1–A6

Each claim from Phase 5 was re-tested with new tests written for this phase. "Defect found" means the Phase 5 claim did not fully hold and was corrected here.

### A1 — Signup identity lifecycle: **VERIFIED**, no defect

`CloudflareTrustedAuthOtpIT#stage3iPhase6_identityLifecycleOtpIsolationAndLeaseAuthorization`, run on real PostgreSQL through MockMvc, checks the following:

- `resolveApplicationContactEmail` creates no `applicant_identities` row. `universal-account` then returns 200.
- An interrupted signup returns the same identity with `existing=false`. A duplicate insert raises `DuplicateKeyException`.
- When candidate B's application confirms candidate A's domain, the response is 403. B's session gets 404. A stays `PENDING`.
- A's own confirm promotes A only. A `DISABLED` identity gets 409, and confirm returns `promoted=false`.

### A2 — Managed-mail retention and concurrency: **VERIFIED after two fixes**

`ManagedMailRetentionConcurrencyIT` (6 tests) uses PostgreSQL 16 via Testcontainers, so these are real row locks, not mocks:

- A lease locked by a second JDBC connection is skipped (`SKIP LOCKED`), and the concurrent refresh wins.
- Expired leases are cleaned up. An active lease survives past retention.
- The purge is atomic: a trigger that fails the `managed_mail_webhook_tokens` delete rolls everything back.
- Two concurrent purges process 300 leases exactly once.
- The sweep uses the database clock.

Defects found and fixed:

- **Concurrent `/resolve` returned 404 to the losing racer** (`MailboxVerificationController`). When `tryReserveManagedMessage` returned `FALSE` because another request had just reserved the lease, the loser did not fall back to the active lease. It now retries `tryRecoverActiveLease` once before answering 404. `ManagedMailIT` passes 14/14, plus 3 repeated runs.
- **Retention compared timestamps against the JVM clock** (`ManagedMailRetentionJob`). `sweep()` now purges against `select now()`, so worker and database clock skew cannot age out a live lease early. A test covers it.

### A3 — OTP and private email isolation: **VERIFIED**, no defect

In the same A1 test, an email containing a one-time code and a link token never appears in any of these places: `/track`, `/track/reviews`, the timeline, the `emails` table, `audit_logs`, `outbox_events` or captured application logs. A candidate session calling worker-only `/resolve` gets 401/403, and the legacy extract endpoint returns 403. Only the authenticated worker receives the code. Retention then erases the content.

### A4 — Secret redaction and `ROBIN_WORKER_SESSION_KEY`: **VERIFIED after fixes**

- **Defect found (backend `LogScrubber`).** These secrets passed through unredacted:
  - credential headers `x-api-key`, `api-key`, `x-auth-token`, `x-access-token`, `proxy-authorization`, `x-csrf-token` and `x-xsrf-token`;
  - unquoted `secret: value` pairs;
  - numeric `code: 123456` verification codes;
  - bare JWTs.

  Four patterns were added. `LogScrubberAdversarialTest` covers them with 24 adversarial cases, including non-secrets that must survive.
- **Defect found (worker `secret_redaction.js`).** `scrubString` missed numeric `code:` values and bare JWTs. Both are fixed. `secret_redaction.test.js` has 22 tests, including the new Phase 6 case.
- **Session key fails closed: VERIFIED.** `assertWorkerSessionKeyConfigured` throws when the key is missing or short, unless `ROBIN_ALLOW_EPHEMERAL_WORKER_SESSION=1` is set (`worker/test/phase5_security.test.js`).

### A5 — Backend SSRF transport pinning: **PROVEN at the socket level**, plus address-class fixes

This is the mandatory item, and it is proven at the transport boundary, not by reasoning.

- `HostileDnsResolverProvider` is a JDK `InetAddressResolverProvider` registered through `META-INF/services`. It is real DNS rebinding inside the JVM: every lookup of `*.robin-rebind.test` returns the attacker address `127.0.0.2`, and every other name goes to the builtin resolver.
- `SafeHttpClientTransportPinningTest` has 4 tests, with keystores generated at runtime and SNI captured on the server:
  1. Pinned HTTPS reaches only the validated `127.0.0.1`. The attacker listener accepts **0** TCP connections. The JDK performs **0** extra DNS lookups during the connection. The server sees SNI equal to the requested host.
  2. **Control:** the same request without pinning *does* reach the attacker, which proves the hostile resolver is real and test 1 is not passing vacuously.
  3. Certificate hostname verification is still enforced through the pinned tunnel: a wrong-host certificate is rejected.
  4. Each redirect hop is validated once, and a hop to a refused address is never connected (`UNSAFE_ADDRESS`).
- **Defect found (`AddressGuard`).** The guard allowed several address classes it should refuse:
  - IPv4: the documentation ranges `192.0.2.0/24`, `198.51.100.0/24` and `203.0.113.0/24`;
  - IPv6: Teredo `2001::/32` and the discard prefix `100::/64`;
  - NAT64 `64:ff9b::/96` and `64:ff9b:1::/48`, and 6to4 `2002::/16`, were not judged by the IPv4 address they embed, so a private IPv4 could be smuggled inside them.

  All of these are fixed. `AddressGuardAddressClassTest` has 53 parameterised cases. A stale comment claiming "the JDK re-resolves" was corrected.
- **Residual (honest):**
  - A kept-alive connection to the same host may reuse an IP validated on an earlier request. That is benign, since that IP was itself validated.
  - Guard subclasses that override `check()` take the unpinned path. Only test subclasses do this; production uses `AddressGuard` itself.
  - `UniversalBrowserPlanService` and `SandboxRegistrationPolicy` use the guard to validate targets only; they make no fetches.
  - **Chromium's own egress in the worker is a separate control** (`worker/test/egress_security.test.js`) and is not covered by the JDK pinning proof.

### A6 — Mailbox authorization: **VERIFIED after one fix**

- A foreign session gets 404. A session/application parity mismatch gets 409. A stopped (`FAILED`) plan gets 409, on both `/resolve` and `/confirm`.
- **Defect found: binding check failed open.** `enforceApplicationBinding` skipped the RUNNING-plan and permitted-domain check entirely when `EmployerCredentialService` was absent or `bindingFor` returned `null`. It now answers 503 and writes a `*_REJECTED` audit with `BINDING_UNAVAILABLE`. The production bean is unconditional, so this was latent, but it was a fail-open path.

---

## 3. Workstream B — P2-7 and P2-8

### P2-7 — Universal receipts returned an empty `submittedIdentity`: **FIXED**

`ApplicationReceiptService` now takes the identity from the first source available, in order, and records which one it used in a new `source` field:

| `source` | Meaning |
|---|---|
| `SUBMIT_STEP_IDENTITY` | The submit step's own reported identity. |
| `SUBMIT_STEP_FIELDS` | Derived from the submit step's filled fields. |
| `PREPARED_PACKAGE_PROFILE_FACTS` | UNIVERSAL only. The `package.profileFacts` the server built from the verified profile and the browser filled from: fullName, first and last name, email, phone, location, LinkedIn. `coverLetterText`, GitHub and portfolio are never copied into the identity. |
| `CURRENT_PROFILE` | No attempt yet, so the current profile is shown. |

The frontend (`SubmissionReceiptModal`) labels the last two sources as prepared by Robin, never as the employer's copy. **The trust level does not change**: a Universal receipt stays `BROWSER_OBSERVED`. This is what Robin sent, not what the employer stored.

Tests:

- `ReceiptReconciliationIT` test 1 asserts the identity, the source, the absence of cover-letter text and GitHub, and the unchanged level.
- `ApplicationReceiptServiceTest` passes 13/13.
- The frontend modal test checks the provenance note.
- The backend and frontend contract fixtures were regenerated from the real DTOs; only `submittedIdentity` changed.

### P2-8 — TRACK could pick a later failed or paused plan over the SUBMITTED one: **FIXED**

The `TrackRepository` plan query now orders by `(status = 'SUBMITTED') desc, created_at desc, id desc`, and `ApplicationReceiptService` selects the default attempt the same way. `TrackPlanSelectionIT` (6 tests, real PostgreSQL 16, real `snapshotParts` and `ApplicationReconciler`) covers these cases:

- A SUBMITTED plan followed by a FAILED one is still shown as APPLIED.
- A SUBMITTED plan followed by HANDOFF_REQUIRED, PREPARED, AWAITING_APPROVAL, RECONCILIATION_REQUIRED or RUNNING is still shown as submitted.
- A second SUBMITTED plan is refused by `V043`.
- Without a SUBMITTED plan, the latest plan is shown and the application is **never** APPLIED: no false APPLIED status.
- Another candidate's SUBMITTED plan never affects this application.
- Plans created at the same instant are ordered deterministically.

---

## 4. Workstream C — Full lifecycle audit

Severity: **P0** truthfulness or safety; **P1** blocks a core promise; **P2** real but not blocking; **L** an intentional limitation.

| Stage | Existing implementation | Source evidence | Test coverage | Defect / limitation | Sev | Recommended action |
|---|---|---|---|---|---|---|
| **FIND** | Platform discovery adapters, dedup, source attribution and freshness. | `JobDiscoveryService`; `jobs.dedup_key`; `job_sources`; `job_source_observations` keyed on provider + source_url + external_job_id; `last_discovered_event_at`; aggregator observation TTL 7 days; archive after 30 days unseen; jobs then observations locked in a fixed order. | `discovery`: 48 test classes, 13 of them ITs (including `DiscoveryMaintenanceConcurrencyIT` and `GreenhouseDiscoveryIT`). | No apply-time liveness check: an expired posting only shows up as "form not found" in the browser. | P2 | Liveness probe through `SafeHttpClient` before a plan is claimed. |
| **MATCH** | Hard filters plus scoring. | `HardFilterService`: titles, location, remote, employment type, experience, salary minimum, sponsorship via `sponsor_records`, company filters, excluded keywords. | `jobs`: 18 classes, 2 ITs. | Lenient when job data is missing: unknown location passes, no salary passes. That is acceptable for suggestions but weak for auto-created applications. | P2 | Strict mode for auto-apply, where missing data sends the job to manual review. |
| **PREP** | Fact-checked documents, reviewed CVs, confirmed answers and approval invalidation. | `DocumentFactValidator` (`fact-validator-2`: deterministic BLOCKERs, heuristic WARNINGs); `cv_version_reviews` with `content_sha256`; `UniversalFieldMapper` (`trustedForAutofill`; protected labels such as password, OTP and demographics are never autofilled); `ApplicationAnswerService` protected demographic questions; `ApplyPackageGuard` → `PACKAGE_APPROVAL_INVALIDATED`; `ApplyReadinessService`. | `documents` (2), `qa` (2), `prep` (1 IT); `UniversalBrowserE2EIT` (a fabricated "PhD at MIT" answer is refused with 400; revoking the CV review strips the submit step). | Passing the validator does not mean every sentence is true (stated in the code). `ApplyPackageGuard` logs and swallows invalidation errors, so a database error there leaves the approval standing. The human gates and, as of this phase, the fence still re-check approval. | L / P3 | Surface invalidation failures to the candidate rather than only logging them. |
| **APPLY** | Per-ATS plan services; Playwright worker; two-step human approval; single-shot pre-submit fence; evidence-validated completion. | `approve-submit` → `READY_TO_SUBMIT`; `prepare-submit` re-runs readiness and drift checks, then appends the submit click; `recordPreSubmitAttemptIfAbsent` (unique `(plan_id, step_key)`); `SubmissionCompletionService` (RUNNING + `submit_approved` + last step is a submit + structured evidence); `V043` (one SUBMITTED plan per application). | `apply` (4), `automation` (28, 6 ITs), worker `universal_engine`, `universal_reliability`, `e2e`. | **DEFECT FOUND AND FIXED (P1), see below:** withdrawing a document or answer approval after the worker claimed the submit pass did not stop the click. **Residual:** Greenhouse, Ashby and Workday submit passes have no backend pre-submit fence (only a worker-local `REAL_SUBMIT_ATTEMPTING` marker), so the same window remains open for those adapters. Every browser run in the suites is on fixtures, not a real ATS. | P1 (fixed for UNIVERSAL) / **P1 residual** for legacy adapters | Add the backend fence, with the approval check, to the Greenhouse, Ashby and Workday worker paths. |
| **AUTO-APPLY** | Batch creation and preparation only. | `AutoApplyBatchService`: reserve and release quota, `maxBatchSize`, `dailyCap`, manual-review-only guard. Approval rules only toggle auto-creation and the minimum score. `submit_approved` is set only by the candidate's `approve-submit`. | `application` (16, 6 ITs). | **No autonomous submission without approval of each application.** This is a safety property and also a product limitation: "auto-apply" means automatic preparation. | L | Keep it. Any change needs an explicit product and consent decision. |
| **EXCEPTIONS & RESUME** | CAPTCHA and anti-bot hard stops; login and MFA handoff; resuming on discovered questions; crash quarantine. | Worker `HardStopError('BLOCKED_ANTI_BOT')`; `universal_form.js` CAPTCHA detection (reCAPTCHA, hCaptcha, Turnstile, `data-sitekey`, challenge text); `V067`/`V068` handoff sessions; handoff link pinned to the employer host; resume refused after the fence or after any submission; stale RUNNING submit-capable plans moved to `RECONCILIATION_REQUIRED` with `submit_approved=false` and never re-queued; a crash after the fence gives `PRIOR_ATTEMPT_UNVERIFIED` and never a second click. | `UniversalBrowserE2EIT` (Stage 3D/3F: lookalike phishing host refused, concurrent resume succeeds once, resume refused after submit); worker `handoff`, `universal_engine` (crash after fence), `universal_account_otp_recovery`; `ReceiptReconciliationIT` #16. | CAPTCHA/MFA are never solved, by design. After this phase's fix, a fence refused because approval was withdrawn is reported by the worker as `PRIOR_ATTEMPT_UNVERIFIED`, so the candidate is asked to reconcile an attempt that never clicked. That is safe but confusing. | L / P3 | Teach the worker the `SUBMIT_APPROVAL_WITHDRAWN` code and return the plan to `AWAITING_APPROVAL`. |
| **TRACK** | Reconciler v2, durable receipts and links, trust levels, temporal policy. | `ApplicationReconciler`; `V069` receipts; `V071` receipt links; verification levels `NOT_ATTEMPTED`, `ATTEMPT_RECORDED`, `BROWSER_OBSERVED`, `CANDIDATE_REPORTED`, `SANDBOX_VERIFIED`, `EMPLOYER_EMAIL_VERIFIED`, `CONFLICTING`; `SUBMISSION_REFERENCE_CONFLICT`; acknowledgements dated before the fence, or in the future, are never verified; P2-8 plan selection. | `track` (26 classes, 8 ITs) including `ReceiptReconciliationIT` (14) and `TrackPlanSelectionIT` (6). | Universal never reaches a trusted employer receipt. `EMPLOYER_EMAIL_VERIFIED` depends on managed-mail delivery that has not been verified live. Employer status (interview, rejection) comes only from email classification; no employer portal is polled. | P2 | Live mail verification is part of the blocked external validation (§9). |

### APPLY defect found and fixed in this phase (P1)

**Symptom.** The flow was: the candidate approves and queues the submission, and the worker claims the submit pass, so the plan is `RUNNING`. The candidate then revokes the CV review or edits an answer. `ApplyPackageGuard` called `invalidateApproval`, which only touched `READY_TO_SUBMIT`/`PREPARED` plans, and the pre-submit fence never re-checked `submit_approved`. The worker therefore went on to click submit with a package the candidate had just withdrawn.

**Fix (backend only, fail-closed, no migration):**

- `recordPreSubmitAttemptIfAbsent` now requires `status = 'RUNNING' and submit_approved = true`, read with `FOR UPDATE` on the plan row. A withdrawal that commits first is re-checked against the updated row, and the fence is refused.
- `invalidateApproval` is now `@Transactional`. It also locks RUNNING UNIVERSAL plans that still have `submit_approved = true`. Then, in a new statement, it skips any plan whose fence already exists and clears `submit_approved` on the rest. The status and steps stay as they are, because the worker owns the run.
- Once the fence exists, the attempt is in flight and is never unwound. Nothing claims it was.
- A refused fence returns 409 with code `SUBMIT_APPROVAL_WITHDRAWN`. The worker already treats any 409 as "do not click".
- A worker that ignored the 409 and reported `SUBMITTED` is refused, because `SubmissionCompletionService` already requires `submit_approved`.

**Proof.** `UniversalBrowserE2EIT#stage3iPhase6_approvalWithdrawnBeforeTheFenceRefusesTheClickAndAnAttemptInFlightIsNeverUnwound` runs on real PostgreSQL 16:

- **A.** Approval is withdrawn while RUNNING and before the fence. The fence is refused with `SUBMIT_APPROVAL_WITHDRAWN` and no fence row is written. A `SUBMITTED` report gets 409. The worker's honest `RECONCILIATION_REQUIRED` report is accepted. The application is not `APPLICATION_SUBMITTED`, and TRACK is not `APPLIED`.
- **B.** The fence is recorded first. A later edit leaves the approval standing and writes no invalidation audit.
- **C1.** A second JDBC connection holds the plan-row lock and withdraws the approval while the fence request is blocked on that lock (confirmed through `pg_stat_activity`). The fence gets 409.
- **C2.** The fence commits while `invalidateApproval` is blocked on the lock. The withdrawal sees the fence and returns 0.

**Mutation check:** with `submit_approved = true` removed from the fence predicate, the test fails (`Status expected:<409> but was:<204>`). The existing Stage 3F fence race test now runs on a genuinely approved submit pass.

---

## 5. Workstream D — End-to-end regression and trust levels

The strongest safe end-to-end coverage is the combination below. **There is still no single test that runs the real worker process against the real backend process**; that is the main integration gap (P2). The boundary between them is covered from both sides by contract tests: worker tests replay backend responses, backend MockMvc tests replay worker requests, and both share `submissionReceiptFixtures.json`.

| Trust level | What proves it | Where | What it does *not* prove |
|---|---|---|---|
| **Browser-observed** (`BROWSER_OBSERVED`) | Real Chromium completes a multi-page form, pauses and resumes, writes one fence, clicks once and produces v2 evidence: redirect chain, final origin, per-document upload proof, self-digest. The backend re-derives the digest and re-checks correlation and host policy; tampered or off-host evidence is never a browser observation. | worker `universal_engine.test.js` and `universal_evidence_v2`; backend `ReceiptReconciliationIT`; `UniversalBrowserE2EIT` (full lifecycle, then the Phase 6 withdrawal test) | Anything about a real employer portal. The pages are local fixtures. |
| **Sandbox-verified** (`SANDBOX_VERIFIED`) | Sandbox receipts are stored separately (`V069`/`V070`), only for registered sandbox hosts, and never mixed with Universal browser evidence. | `SandboxRegistrationIT`; the lifecycle test asserts 0 sandbox receipts for a non-sandbox site | No deployed sandbox exists (Stage 3G is blocked). |
| **Employer-email-verified** (`EMPLOYER_EMAIL_VERIFIED`) | A DKIM-authenticated managed-mail acknowledgement after the fence advances the application to RECEIVED and adds a durable `V071` link. Unverified, pre-fence or future-dated emails never verify. Duplicate or concurrent deliveries produce one receipt. | `ReceiptReconciliationIT` 1–3 and the temporal tests; `cloudflare-email-worker` header-trust tests (25) | Live Cloudflare routing and real employer mail have not been verified. |
| **Employer-status-reconciled** | A later interview and then a rejection keep the submission evidence, and an older message never wins. A withdrawal keeps the browser observation. | `ReceiptReconciliationIT` | Status comes only from email. No employer system is queried. |

No fixture-based submission is presented anywhere as a real employer submission. TRACK keeps each level separate, and no level is promoted without its own evidence.

---

## 6. Workstream E — Test results (exact counts, local, Windows 11, Docker 29.0.1)

| Suite | Command | Result |
|---|---|---|
| Backend unit (surefire) | `mvn -B clean verify` | **2,132 run, 0 failures, 0 errors, 6 skipped** (`RedisConnectivityDiagnosticsTest`, `RedisLettuceAbExperimentTest` and `RedisTlsBindingTest`, 2 each; they need a real Redis) |
| Backend ITs (failsafe, PostgreSQL 16 Testcontainers) | same run | **482 run, 0 failures, 0 errors, 7 skipped** (`ExternalDatabase*IT` ×3 and the live-provider `*LiveIngestionIT`/`PlatformLiveIT` ×4; they need external databases or the internet, which this phase does not touch). `BUILD SUCCESS` |
| Worker | `npm test` (worker) | 406 tests: **405 passed, 0 failed, 1 skipped** (SIGTERM signal test; POSIX signals cannot reach a Node child on Windows, so it runs on Linux CI). `universal_engine.test.js` was also run 3 more times: 6/6 each time. |
| Frontend type check | `npx tsc -b` | clean (one fixture-typing error from this phase's `source` union was fixed by typing it as `string`) |
| Frontend tests | `npm test` (frontend) | 49 files, **492/492 passed** |
| Frontend build | `npm run build` | built successfully |
| Cloudflare email worker | `npm test --prefix cloudflare-email-worker` | **25/25 passed** |
| Phase 20 scripts | `node --test scripts/phase20/*.test.mjs` | **8/8 passed** |

Targeted Phase 6 suites, also included in the full run above:

| Class | Tests |
|---|---|
| `SafeHttpClientTransportPinningTest` | 4 |
| `AddressGuardAddressClassTest` | 53 |
| `ManagedMailRetentionConcurrencyIT` | 6 |
| `ManagedMailIT` | 14 |
| `CloudflareTrustedAuthOtpIT` | 4 |
| `LogScrubberAdversarialTest` | 24 |
| `TrackPlanSelectionIT` | 6 |
| `ReceiptReconciliationIT` | 14 |
| `ApplicationReceiptServiceTest` | 13 |
| `UniversalBrowserE2EIT` | 9 |
| Worker `secret_redaction` | 22 |

### CI, and the worker defect it exposed

The first push of this phase (`ec1aac8`, run `46038516243`) passed `backend`, `frontend` and `cloudflare-email-worker`. **`worker` failed 1 of 405 tests**: `real Chromium validation 422 rejection and fake DOM banner without POST never confirm submission` threw `page.evaluate: Execution context was destroyed, most likely because of a navigation` inside `inspectLiveFormDom`, on the prep pass.

That test had passed in every one of the 39 earlier CI runs we checked, including the Phase 5 baseline, and it passes locally; this phase's worker change (`secret_redaction.js`) is not on that path. It is still **not** dismissed as a flake. It is a real race in production code: `waitForLoadState('domcontentloaded')` can resolve while the previous document is still live, and a navigation that commits mid-`evaluate` aborts the whole run. On a real employer site with redirects or client-side routing, that means a failed run. It fails safe (no click, no duplicate), but it is a failure.

**Fix:** `inspectLiveFormDom` retries the read-only inspection on the settled page, at most 3 times and only for navigation-race errors (`isNavigationRaceError`). Every caller's checks then run on that page, exactly as if the navigation had finished first. That includes CAPTCHA, host and identity checks, and, after the click, the 2xx-POST, DOM and origin evidence rules, so a retry cannot produce a confirmation. Other errors, and pages that never settle, still throw. The new test in `universal_reliability.test.js` covers all three cases.

The final CI run on the HEAD that contains this fix is recorded on PR #21.

---

## 7. Migration status

`V001`–`V073` are byte-identical to the baseline (`git diff 1d5841e -- backend/src/main/resources/db/migration` is empty). **No `V074`**: every Phase 6 fix is a code or query change that works with the existing schema (`V043`, `V067`–`V073`).

---

## 8. Remaining risks

**P0**

- None found in the code on this branch.
- **Deployment caveat:** every P0/P1 remediation on `integration/wave-3` (including the Stage 3I DKIM authentication fix) is **not merged and not deployed**. Whatever runs from `master` does not have them.

**P1**

- **Legacy-adapter fence parity:** Greenhouse, Ashby and Workday submit passes have no backend pre-submit fence, so an approval withdrawn during a RUNNING submit pass is not enforced for them (§4, APPLY).
- **Real-employer behaviour is unverified:** there are no real ATS submissions and no deployed staging (the existing "ATS submission is not yet production-verified" entry and Stage 3G).
- **Live managed-mail delivery** is unverified (Phase 20 / Stage 3G).

**P2**

- No cross-process worker-plus-backend end-to-end test.
- No apply-time posting liveness check.
- Hard filters are lenient on missing job data.
- Universal receipts never reach employer-trusted status.
- Employer status is derived only from email.
- Residual Chromium egress depends on the worker's own controls, separate from JDK pinning.

**P3**

- A withdrawn-approval fence surfaces as a reconciliation request.
- `ApplyPackageGuard` swallows invalidation errors.
- A kept-alive connection may reuse an IP validated earlier for the same host.

---

## 9. External validation decision — **NO-GO**

External validation stays blocked. None of its preconditions exist:

- separate written operator authorisation;
- approved synthetic staging infrastructure;
- provisioned credentials;
- the required secrets (`TRUSTED_AUTHSERV_IDS`, `ROBIN_WORKER_SESSION_KEY` in a real worker runtime, managed-mail routing).

Nothing was deployed and no real application was submitted.

---

## 10. Next recommended phase

**Stage 3I Phase 7 — Cross-Process Submission Integrity & Legacy-Adapter Fence Parity**

1. Add the backend durable pre-submit fence, with the standing-approval check, to the Greenhouse, Ashby and Workday worker submit paths, so withdrawing an approval mid-run is enforced for every adapter.
2. Teach the worker `SUBMIT_APPROVAL_WITHDRAWN`, so the plan returns to `AWAITING_APPROVAL` instead of asking the candidate to reconcile an attempt that never clicked.
3. Build a cross-process harness: the real backend on Testcontainers, the real worker with Chromium, and a local fixture employer site. Assert each trust level end to end, with no mocks at the worker/backend boundary.
4. Add an apply-time posting liveness probe through `SafeHttpClient`, and a strict hard-filter mode for auto-created applications.

External validation remains blocked until the §9 preconditions are explicitly provisioned.
