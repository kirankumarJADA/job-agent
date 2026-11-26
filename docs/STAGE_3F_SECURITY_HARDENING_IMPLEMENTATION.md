# ROBIN — STAGE 3F: Universal Browser Security Hardening & External Test Provisioning

**Repository:** `kirankumarJADA/job-agent` · **Branch:** `integration/wave-3` · **PR #21:** OPEN / DRAFT (unchanged)
**Scope:** implementation of the Stage 3E security findings (SEC-1 … SEC-8, PROD-1, PROD-2) plus an external test-environment provisioning plan. No real application was submitted, no employer account was accessed, nothing was deployed, and no production configuration was changed.

---

## 1. Preflight

| Check | Observed |
|---|---|
| Branch / HEAD before edits | `integration/wave-3` @ `5e8bc49c57d704244b92ed4b6792225575aac734` (Stage 3E audit commit) |
| `origin/integration/wave-3` | `5e8bc49c…` — identical, working tree clean |
| PR #21 | `state: OPEN`, `isDraft: true`, base `master` |
| CI — Stage 3D code commit `f4d35ae` | run `45820718627`: **success** |
| CI — Stage 3E docs commit `5e8bc49` | run `45823309506`: **in progress** at preflight (not treated as green) |
| `master` | `a45888d63bd2a893c7373ed44a6d17b930682a36` (untouched) |
| Migrations | V001–V070 unchanged; **no new migration** was needed |

Documents read in full before editing: Stage 3E audit; Stage 3A–3D reports were consulted for architecture and test layout.

---

## 2. Stage 3E findings addressed

| Finding | Status | Where |
|---|---|---|
| **SEC-1** WebSocket bypass of `page.route` | **Fixed** — context `routeWebSocket` policy + connection-level egress proxy | `universal_form.js`, `egress_proxy.js` |
| **SEC-2** Service Worker bypass | **Fixed** — `serviceWorkers: 'block'` on every context (all plan types) | `browser_worker.js#openHardenedBrowser` |
| **SEC-3** Popups / `window.open` / `target=_blank` | **Fixed** — guard moved to the BrowserContext; secondary pages closed on sight; egress proxy underneath | `browser_worker.js#closeUnexpectedPages` |
| **SEC-4** No DNS resolution / DNS rebinding | **Fixed for browser traffic** — loopback egress proxy resolves, validates every A/AAAA answer, pins the socket to the validated IP | `egress_proxy.js` |
| **SEC-5** Trailing-dot hosts, arbitrary ports, substring ATS match, handoff URL | **Fixed** — canonicalisation, 443-only public port policy, host-boundary ATS matching, handoff link bound to the plan's own host | `egress_proxy.js`, `universal_form.js`, `UniversalBrowserPlanService`, `AtsHandoffService` |
| **SEC-6** Plaintext `storage.json`, raw URLs in logs/audit, unmasked screenshots | **Fixed** — no plaintext session files; opt-in encrypted vault; URL redaction in log + audit; masked, `0600` screenshots | `session_vault.js`, `SafeUrls`, `ApplyController` |
| **SEC-7** `/employer-credentials/resolve` lacked domain binding | **Fixed** — exact-host binding to the persisted job URL / RUNNING plan target, active run required | `EmployerCredentialController`, `EmployerCredentialService` |
| **SEC-8** No 10 MiB cap, header leakage on cross-origin redirects | **Fixed** — cap worker- and server-side, magic-byte check for UNIVERSAL, credential headers stripped across origins | `artifact_policy.js`, `worker.js`, `ExecutionPackageService`, `universal_form.js` |
| **PROD-1** "Autonomous submission" copy on `CONTROLLED_AUTO` | **Fixed** — copy now states every submission needs explicit approval | `PreferencesPage.tsx` |
| **PROD-2** Cover letters always `.txt` | **Fixed** — the letter's existing immutable PDF is exported when stored and intact; otherwise `.txt` is kept and the worker refuses it against PDF-only fields (candidate intervention) | `UniversalBrowserPlanService`, `ExecutionPackageService`, `AutomationController` |

---

## 3. Security architecture decisions

### 3.1 Network enforcement (Workstream A)

Three layers, outermost first:

1. **Connection boundary — `worker/src/egress_proxy.js` (new).** Every plan, every plan type, launches Chromium behind a loopback-only forward proxy (`proxy: { server, bypass: '<-loopback>' }`, so even loopback goes through it). Playwright's own `route.fetch()` uses the same proxy (verified in the Playwright 1.63 source: the API request context uses `context.proxy || browser.proxy`). For each connection the proxy:
   - canonicalises the host (punycode, lower-case, trailing dots stripped, WHATWG numeric-IPv4 normalisation, brackets), refusing userinfo, `%`, whitespace and other malformed forms;
   - refuses IP literals (except allow-listed test loopback), internal names (`localhost`, `*.local`, `*.internal`, `metadata.google.internal`, `kubernetes.default`, single-label names, …);
   - enforces the port policy (public: **443 only**; plain HTTP is refused outside test loopback);
   - resolves the name **itself** (`dns.lookup(all, verbatim)`) and refuses the connection if **any** answer is loopback, RFC 1918, CGNAT, link-local / cloud-metadata, multicast, unspecified, reserved, documentation, ULA, or site-local — including IPv4 embedded in IPv4-mapped (`::ffff:`), NAT64 (`64:ff9b::`) and 6to4 (`2002::`) IPv6;
   - opens the TCP socket to **the exact address it validated** (no second lookup — DNS pinning), and re-checks the kernel-reported peer address.
   TLS remains end-to-end (CONNECT tunnel), so the proxy never sees HTTPS plaintext and logs only `{host, reason}` / `{host, port, address}`.
   Every refusal carries an `X-Robin-Egress: blocked; reason=…` header. Chromium treats a refused CONNECT as a tunnel error, but Playwright's `route.fetch()` surfaces the proxy's 403 as an ordinary empty response; the context guard converts a marked response into an explicit abort with an `EGRESS_<reason>` diagnostic (found during final verification — see §5).
2. **Context policy — `installUniversalNavigationGuard(context, …)`.** For UNIVERSAL plans the guard is installed on the BrowserContext (not the first page): same-origin documents only, public HTTPS subresources on 443, `routeWebSocket` for WebSocket handshakes, manual redirect walking with per-hop validation and **cross-origin stripping** of `Authorization`, `Cookie`, `Proxy-Authorization`, CSRF and API-key headers.
3. **Browser hardening — `openHardenedBrowser()` (exported, used by production and by the adversarial tests).** `serviceWorkers: 'block'`, `acceptDownloads: false`, WebRTC constrained to proxied UDP via launch flags and its constructors removed by an init script (WebRTC cannot be proxied over CONNECT), and unexpected pages closed immediately.

**Why this architecture.** The worker already runs as one process per container (OCI/Render). An in-process proxy needs no new infrastructure, cannot be bypassed by page content, and gives connection-level enforcement for everything Chromium does. A host firewall or a separate egress gateway would protect other processes too and is recommended as defence in depth (§9), but is not required for the browser guarantee.

**Test mode.** `allowLocalTestHosts` (UNIVERSAL, backend-controlled) now permits loopback **only on the plan's own port**. Dedicated-ATS suites that drive local mock servers opt in with a constructor-only `localTestNetwork: true` seam that `worker.js` never passes. Private, link-local and metadata ranges stay blocked in every mode.

### 3.2 Session and credential lifecycle (Workstream B)

- **No plaintext session files.** The `storage.json` write is gone. By default the session exists only in the live browser context for one run.
- **Optional encrypted vault** (`ROBIN_WORKER_SESSION_KEY`, base64 32 bytes): AES-256-GCM, additional data = application id + timestamp (a file moved to another application will not decrypt), atomic write, mode `0600` in a `0700` directory, 2-hour TTL, deleted when tampered, expired or undecryptable.
- **Deleted on** completion, failure, hard stop / handoff and lease loss; **kept only** across an approval pause or a crash so the same plan can resume.
- **Shutdown.** SIGTERM/SIGINT registers an exit handler that purges every session; startup sweeps legacy `storage.json` and expired vault entries.
- **Diagnostics.** Failure screenshots mask every form control and are written `0600`; proxy diagnostics contain host + reason only.
- **Trade-off (disclosed):** without a vault key, a crash-restarted run starts logged out; a skipped login step then surfaces as a login wall → candidate handoff. Fail-safe, not silent.

### 3.3 Employer credential domain binding (Workstream C)

`POST /api/v1/employer-credentials/resolve` now, in order: worker token only → application owner derived from the application row → **an automation plan for that application must be RUNNING** (409 otherwise) → requested domain (normalised: case, trailing dot, `www.`) must **exactly equal** the canonical https host of the persisted `jobs.application_url` or of the RUNNING plan's `target_url` (403 otherwise; no suffix or parent-domain matching, so `x.evil.com`, `evilx.com`, sub-domains and parents never match) → ACTIVE credential only (404 for missing/disabled) → undecryptable credential is a clean 409, never a 500 or a partial secret. Passwords remain AES-GCM at rest and are returned only in this worker response.

### 3.4 Upload and artifact hardening (Workstream D)

- One **10 MiB** ceiling: `worker.js#downloadArtifact` checks the declared size, `Content-Length` and the received bytes (all plan types); `ExecutionPackageService.artifactBytes` refuses larger artifacts server-side (409).
- **UNIVERSAL** artifacts must match their declared type by magic bytes (`%PDF-`, ZIP for `.docx`, OLE for `.doc`, valid UTF-8 for text) both when downloaded and again before execution; unsupported types are refused. Existing per-ATS checks are unchanged.
- SHA-256, ownership and CV/cover-letter separation checks are unchanged and still enforced.

### 3.5 Redirect, logging and URL security (Workstream E)

`SafeUrls.redactForLog` (scheme, host, port, path only) is used for the UNIVERSAL plan log line and the `APPLY_PACKAGE_CREATED` audit row. `isDedicatedAtsUrl` now matches the parsed host on a label boundary. The handoff link shown to the candidate must be on the plan's own employer host.

### 3.6 Cover-letter PDF (Workstream F)

No new PDF service: the letter's existing immutable PDF (`CoverLetterService` / `cover_letters.pdf_file_id`) is exported as `cover-letter-<v>.pdf` (`application/pdf`, recorded SHA-256, byte size) when it is stored, intact and a real PDF; served by the new `cover-letter-pdf` artifact kind with checksum re-verification.

### 3.7 Submission-state safety (Workstream G)

- The `PRE_SUBMIT_ATTEMPTING` fence is now **one atomic insert-if-absent** under the fixed key `pre-submit-attempting`, only while the plan is RUNNING; concurrent reports collide on the unique `(plan_id, step_key)` index.
- Both resume paths (`resumeUniversalPlan`, `resumeFromHandoff`) refuse when **any** plan of the application is SUBMITTED or the application is `APPLICATION_SUBMITTED`.
- Unchanged and re-verified: V043 single-submission index, atomic SUBMITTED + evidence transaction, stale-submit quarantine, owner-scoped resume, no fabricated receipts. No unattended submission was introduced.

---

## 4. Actual guarantees and their limits

**Proven in controlled tests (local loopback listeners, injected DNS answers, real Chromium):**
- WebSocket to non-allow-listed loopback, private and metadata addresses: no TCP connection reaches the listener. A **negative control** shows the same probe *does* connect without the hardened launch.
- Service Worker registration cannot intercept or issue requests.
- Popups / new tabs are closed and their loopback navigation never connects.
- DNS answers to loopback, RFC 1918, link-local/metadata, ULA, IPv4-mapped and mixed public+private sets are refused; a public name rebound to loopback never reaches a socket; each connection is resolved once and connected to exactly that address.
- Trailing-dot, numeric-IPv4, encoded and userinfo hosts, prohibited ports and plain HTTP are refused before any DNS lookup.
- **No path around the proxy:** with an injected rebinding resolver, a public hostname requested by Chromium directly (dedicated-ATS plans, no context guard) and one requested through the UNIVERSAL guard's `route.fetch()` both arrive at the proxy, are resolved by it, and are refused as `BLOCKED_ADDRESS`; the only connections ever opened are to the allow-listed fixture.
- Cross-origin redirects drop credential headers; same-origin redirects keep them.
- Credential binding, the atomic fence, resume refusal, concurrent handoff resume and audit redaction — against real PostgreSQL 16.

**Not proven / not claimed:**
- Enforcement for **other processes** on the worker host (the proxy governs Chromium + Playwright only). Backend-side `AddressGuard` (discovery fetches) still resolves before connecting in the JDK, as documented in Stage 3E — outside this stage's browser scope.
- **Live external behaviour.** No external target exists (§6); fixture results are not evidence of compatibility or security against real employer sites.
- The SIGTERM child-process test needs POSIX signals: it is **skipped on Windows** and runs on the Linux CI runner.
- The PDF cover-letter **selection → upload** path is covered by unit tests of the serving and integrity checks, not by an end-to-end integration run.
- Enterprise SSO on a sibling sub-domain (`login.employer.com`) is deliberately **not** supported by credential binding (exact host only) and still ends in a candidate handoff.

---

## 5. Test coverage and results

### 5.1 New and changed tests

| Suite | File | What it proves |
|---|---|---|
| Worker — adversarial egress (19) | `worker/test/egress_security.test.js` | IP classes incl. mapped/NAT64/6to4; canonicalisation (trailing dot, octal/hex/integer IPv4, `%`-encoding, userinfo); 443-only port policy; DNS answers to loopback/RFC 1918/link-local/metadata/ULA/mixed sets refused; rebinding between validation and connection (one lookup, pinned address, second answer refused); proxy refuses literals/internal names/ports before DNS; plain HTTP refused; **real Chromium**: WebSocket to loopback/private/metadata never connects (+ negative control that does connect without hardening), Service Worker blocked, popups/`target=_blank` closed with zero connections, redirect to private/metadata refused, cross-origin redirect strips Authorization/Cookie/CSRF (same-origin keeps them), public names reach the proxy from Chromium and from `route.fetch()` |
| Worker — session & artifacts (9) | `worker/test/session_security.test.js` | vault disabled by default; encrypted at rest (no cookie plaintext), `0600`/`0700` (POSIX); AAD binding, tamper, TTL and wrong-key rejection; legacy `storage.json` removal and startup sweep; completed and failed runs leave no session and never load a planted legacy cookie; **SIGTERM** child process purges all sessions (POSIX only); 10 MiB cap, magic-byte mismatch, unsupported types, safe file names |
| Worker — existing suites | `e2e`, `account-lifecycle` | unchanged assertions; now run behind the egress proxy with the explicit `localTestNetwork` test seam; approval-pause and crash-restart resume use a test-only vault key |
| Backend — unit (22 + 6) | `EmployerCredentialControllerTest`, `Stage3FBackendHardeningTest` | domain binding matrix (wrong employer, lookalikes, sub-domain, parent, normalisation, no active run, no usable job host, undecryptable credential, worker-token misuse); PDF cover-letter serving + tamper refusal; server-side 10 MiB cap; host-boundary ATS matching; handoff host rule; URL redaction |
| Backend — real PostgreSQL 16 (5 new) | `UniversalBrowserE2EIT` | credential resolve bound to job host + RUNNING plan (incl. disabled credential, lookalikes, foreign application, candidate session refused); **8 concurrent** `PRE_SUBMIT_ATTEMPTING` reports → exactly one accepted; resume refused after another attempt submitted (both resume paths) with a working control; handoff link to a foreign domain refused and **6 concurrent** handoff resumes → exactly one; `APPLY_PACKAGE_CREATED` audit stores a redacted URL |
| Backend — fixture fix | `AtsHandoffIntegrationIT` | two tests initiated a handoff on a host different from their seeded plan; aligned to the plan's own host (assertions unchanged) |

### 5.2 Final local results (all on the final code)

| Suite | Command | Result |
|---|---|---|
| Backend unit + ArchUnit (Surefire) | `mvn -o clean verify` | **1,972 run, 0 failures, 0 errors, 6 skipped** (Redis diagnostics/TLS experiments, pre-existing) |
| Backend integration, PostgreSQL 16 Testcontainers (Failsafe) | same run | **440 run, 0 failures, 0 errors, 7 skipped** (external-database and live job-board ITs, pre-existing) — `BUILD SUCCESS` |
| Browser worker | `npm test` | **356 run, 355 passed, 0 failed, 1 skipped** (SIGTERM test: POSIX signals unavailable on Windows; runs on the Linux CI runner) |
| Frontend | `npx tsc -b`, `npm test -- --run`, `npm run build` | `tsc -b` clean; **49 files / 482 tests passed**; production build succeeded |
| Cloudflare email worker | `npm test` | **17/17 passed** |
| Phase 20 probes | `node --test scripts/phase20/*.test.mjs` | **8/8 passed** |

**Runs that failed during the stage, and why (none hidden):**
- First worker full run: 14 dedicated-ATS/e2e tests failed — they drive local mock servers and were correctly blocked by the new production egress policy; fixed by the explicit `localTestNetwork` test seam. Two e2e resume tests then failed because they relied on plaintext cross-run cookies; fixed by the encrypted vault (test key), not by restoring plaintext.
- Backend full run #1: `PdfDocumentRendererTest.anUnusuallyLargeCvRendersWithinBoundedTimeAndMemory` exceeded its wall-clock bound while the frontend suite ran concurrently (unchanged code; passed 10/10 alone and in every later full run).
- Backend full run #2: 2 `AtsHandoffIntegrationIT` failures from inconsistent fixtures (see 5.1) — the new handoff host rule rejected them as designed.
- One concurrent frontend run reported 1 failed test out of 482 while the backend build ran; it could not be reproduced (two subsequent runs 482/482). The failing test's name was not captured.
- Final verification: the new `route.fetch()` no-bypass test failed with `FETCHED` — a diagnostic probe showed the request **did** reach the proxy and was refused (`BLOCKED_ADDRESS`), but Playwright surfaced the proxy's 403 as an empty response. Fixed with the `X-Robin-Egress` refusal marker (§3.1); no connection to the rebound address was ever made.

### 5.3 Evidence classes — kept separate

- **Local fixtures** (worker): loopback HTTP listeners and injected DNS answers. They prove the code paths; they are not evidence about real employer sites.
- **Real PostgreSQL integration** (backend Failsafe): real schema (V001–V070), real SQL, real concurrency.
- **External employer validation:** **none performed** — no authorised target exists (§6).

### 5.4 Files

Worker: `src/egress_proxy.js` (new), `src/session_vault.js` (new), `src/artifact_policy.js` (new), `src/browser_worker.js`, `src/universal_form.js`, `src/worker.js`, `test/egress_security.test.js` (new), `test/session_security.test.js` (new), `test/e2e.test.js`, `test/account-lifecycle.test.js`.
Backend: `common/SafeUrls.java` (new), `identity/EmployerCredentialController.java`, `identity/EmployerCredentialService.java`, `automation/AutomationController.java`, `automation/AutomationPlanRepository.java`, `automation/ExecutionPackageService.java`, `automation/UniversalBrowserPlanService.java`, `automation/handoff/AtsHandoffService.java`, `apply/ApplyController.java`; tests `Stage3FBackendHardeningTest.java` (new), `EmployerCredentialControllerTest.java`, `UniversalBrowserE2EIT.java`, `AtsHandoffIntegrationIT.java`.
Frontend: `src/pages/PreferencesPage.tsx`. Docs: this report, `ROBIN_GAP_REGISTER.md`.
Migrations: none added; `git diff 5e8bc49 -- backend/src/main/resources/db/migration` is empty.

---

## 6. External staging provisioning plan (Workstream H) — NOT deployed

No authorised external sandbox exists; none was invented. The following extends existing infrastructure (Cloudflare is already used for managed mail; the backend runs on Render, the worker on OCI) and requires **explicit operator approval before any step is executed**.

| Area | Plan |
|---|---|
| Hosting | A Cloudflare Worker (or Pages project) on an **operator-owned** account serving a port of `worker/test/fixtures/universal_employer_fixture.js` (multi-step form, conditional questions, custom uploads, login + MFA gate, CAPTCHA test widget page, confirmation + receipt endpoint, idempotency counter). Static + KV/D1 for state; no third-party traffic. |
| Hostname | A dedicated sub-domain on an operator domain that serves no production users, e.g. `robin-test-employer.<operator-domain>`; recorded in the authorisation record (exact host + path prefix). |
| TLS | Cloudflare-managed certificate, TLS 1.2+, HSTS; plain HTTP redirected to HTTPS. The worker's production policy (443, HTTPS) applies unchanged — no test-mode flags. |
| Network isolation | Separate staging backend instance (Render preview/staging service) with its own database; `APP_SANDBOX_ALLOWED_DOMAINS=robin-test-employer.<operator-domain>`, `APP_SANDBOX_ALLOWED_PATH_PREFIXES=/apply/`, `APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS=false`, `APP_SANDBOX_REQUIRE_HTTPS=true`; a staging worker with the egress proxy (default) and, ideally, a host firewall denying RFC 1918 / metadata egress. |
| Synthetic identity | A dedicated synthetic candidate profile (no real PII), synthetic PDF CV and cover letter generated through the normal PREP flow, synthetic answers. |
| Test account | Fixture-side test account created by the operator; stored only via `/api/v1/employer-credentials` (encrypted) for the exact fixture host. |
| Test mailbox | A managed-mail address on the existing Cloudflare email worker domain (`APP_MANAGED_MAIL_DOMAIN`) for OTP and acknowledgement emails; the fixture sends from a DKIM-signed test domain. |
| Fixture behaviour | Page 1 identity, page 2 conditional sponsorship question, custom dropzone + hidden `input[type=file]` (PDF-only and DOCX-only variants), login wall, TOTP/email MFA step, official CAPTCHA test-mode widget page, validation-error variant, `{ok:false}` 200 variant, genuine 2xx + confirmation page with a receipt number, and an idempotency counter rejecting a second POST. |
| Confirmation & receipt | Fixture returns a receipt id and sends a DKIM-signed acknowledgement email; Robin's expected outcome is encrypted `submission_evidence` + `APPLIED (Unverified)` unless a sandbox registration or verified email receipt applies — never a fabricated receipt. |
| Retention & teardown | Fixture purges submissions and uploads after 24 h (scheduled job) and exposes an operator-authenticated teardown endpoint; staging DB destroyed after each validation window; worker artifact/session directories purged (already automatic). |
| Monitoring & audit | Cloudflare analytics + fixture request log (host, path, method, status only; no bodies), staging backend audit_logs, worker egress diagnostics; all retained for the window only. |
| Operator approval | Written sign-off naming: exact hostname and path prefix, non-production status, the test window, the synthetic identity, the data-deletion SLA, and who may run the 15-scenario matrix. Nothing in this repository enables it automatically. |

**Local externally reachable fixture (separate design, not enabled):** the same fixture could be exposed from a developer machine through an operator-owned Cloudflare Tunnel on the dedicated sub-domain, with Access policies restricting it to the staging worker's egress IP. It must not be made public without approval, and is not required for any test in this repository.

---

## 7. Unresolved blockers

1. **No authorised external test environment** — Stage 3G (controlled external validation) remains blocked until §6 is approved and provisioned.
2. **Host-level egress control** is not in place in any deployment; the in-process proxy is the enforcement point for browser traffic only.
3. **UNIVERSAL receipts** still cannot reach `submission_receipts` (POL-1 from Stage 3E, intentionally out of scope): external UNIVERSAL submissions remain `APPLIED (Unverified)` until a DKIM-verified acknowledgement arrives.
4. **Session vault key provisioning**: `ROBIN_WORKER_SESSION_KEY` is not set anywhere; without it crash recovery requires re-login (fail-safe).

## 8. Gap register

`docs/ROBIN_GAP_REGISTER.md` gains a Stage 3F entry (P1 security hardening implemented; external validation still blocked).

## 9. Next-stage recommendation

1. Merge-readiness review of PR #21 (still Draft) with this stage's CI evidence.
2. Operator decision on §6; once approved and provisioned, Stage 3G runs the 15-scenario matrix against the authorised host only, reported separately from local fixture results.
3. Add a host firewall / egress policy to the worker deployment (deny RFC 1918, link-local, metadata) as defence in depth, and apply equivalent connect-time pinning to backend `AddressGuard` fetches.

## 10. Final verdict

Local verification on the final code is complete and green (§5.2). This report is committed together with the implementation, so it cannot cite the CI run of its own commit: the pushed-commit GitHub Actions result (backend, worker, frontend, cloudflare-email-worker) is the remaining gate and is recorded with the stage hand-off, not here. Stage 3F is only complete if that run passes; if any job fails, Stage 3F is **not** complete and this work is preserved for correction.

What this stage does **not** claim: universal compatibility with real employer sites, protection for processes other than the worker's Chromium/Playwright, or any external validation (none was performed).
