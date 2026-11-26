# ROBIN — STAGE 3G: Controlled External Validation & Staging Provisioning

**Repository:** `kirankumarJADA/job-agent` · **Branch:** `integration/wave-3` · **PR #21:** OPEN / DRAFT, base `master`, unmerged
**Starting HEAD:** `9a783915fedb75cd2c8691da7e950265312a2443` (Stage 3F) · **Final HEAD:** the commit that adds this report (see the PR #21 head; the exact SHA and its CI run are given in the stage hand-off, because a commit cannot cite its own SHA)

**External submissions performed: NONE.** No external host was contacted, no account was created anywhere, nothing was deployed, no DNS was published, and no production configuration or secret was touched.

---

## 1. Stage 3F baseline

| Item | Evidence |
|---|---|
| Stage 3F commit | `9a783915fedb75cd2c8691da7e950265312a2443` |
| CI on that exact commit | run [45844744406](https://github.com/kirankumarJADA/job-agent/actions/runs/45844744406) — backend, worker, frontend, cloudflare-email-worker: **success** (worker 356/356 incl. the POSIX SIGTERM test) |
| Working tree at start | clean; `origin/integration/wave-3` = local HEAD |

Read before editing: Stage 3F and 3E reports, the gap register, Stage 3A–3D reports (architecture/test layout), the fixture, `egress_proxy.js`, `session_vault.js`, `browser_worker.js`, `universal_form.js`, and the backend services listed in the stage brief.

---

## 2. Authorisation gate — **ABSENT → external validation BLOCKED**

Inspected (names only, no values printed): tracked configuration (`application*.yml`, `.env.example`, `cloudflare-email-worker/wrangler.toml`, `frontend/vercel.json`, Dockerfiles, `infra/docker-compose*.yml`), `docs/DEPLOYMENT.md`, `docs/EXTERNAL_DEPENDENCIES.md`, and the local environment's variable names.

| Required element | Found |
|---|---|
| Operator-owned / written-permission HTTPS test hostname | **No** |
| Exact permitted hostname + path prefix | **No** — `APP_SANDBOX_ALLOWED_DOMAINS` / `…_PATH_PREFIXES` default to empty; production forces HTTPS and no local hosts |
| Written non-production + synthetic-only confirmation | **No** |
| Test window + authorised operator | **No** |
| Synthetic candidate + synthetic documents | **No** (none designated for external use) |
| Encrypted test portal account for the exact host | **No** |
| Test mailbox | **No** (the only Cloudflare worker is the production email router) |
| Teardown mechanism + retention deadline | **No** |

The only staging-like signal on this machine is an environment variable named `USE_STAGING_OAUTH`, which is unrelated to a test portal and is not authorisation. `wrangler` is not installed. **No authorisation record exists, so no external target was accessed.**

---

## 3. What was prepared (safe, local)

### 3.1 One fixture, two runtimes (Section 4 of the brief)

The fixture logic now lives in **`staging/universal-employer-fixture/src/app.js`** — a runtime-agnostic app built on the standard `Request`/`Response` and Web Crypto APIs. `worker/test/fixtures/universal_employer_fixture.js` is reduced to a Node `http` adapter over it, so local tests and the staging package exercise **the same code** (no second engine). Defaults reproduce the Stage 3B fixture; the existing `universal_engine` suite passes unchanged through the adapter.

Added (all synthetic, all opt-in): conditional mandatory field (sponsorship → visa type), body-portal ARIA combobox, open Shadow DOM input + closed custom element, hidden native file input behind a visible dropzone, PDF-only CV and DOCX-only cover-letter variants, official Cloudflare Turnstile **test-mode** widget (`1x00000000000000000000AA`, script loaded only in staging), HTTP 200 `{ok:false}`, unique receipt ids, and an **idempotency counter** that accepts exactly one submission and answers every later attempt with 409 (counted). Reflected values are HTML-escaped. The app never makes an outbound request.

### 3.2 Cloudflare staging package — **prepared, NOT deployed**

`staging/universal-employer-fixture/`:
- `src/index.js` — Worker + Durable Object. Serves only `FIXTURE_HOSTNAME` (anything else, including `*.workers.dev`, gets 421), redirects HTTP to HTTPS, serves only `FIXTURE_PATH_PREFIX/<known scenario slug>/…`, never proxies or fetches, keeps each scenario's state (including the idempotency counter) in one Durable Object so it is strongly consistent and survives restarts, wipes data via an alarm after `DATA_TTL_HOURS` (default 24 h, max 72 h), and exposes diagnostics/teardown only with `Bearer FIXTURE_ADMIN_TOKEN` (closed when unset or < 32 chars). Every response carries HSTS, `noindex`, `no-store`, `nosniff`, `no-referrer` and a restrictive CSP.
- `wrangler.toml.example` — template only (`workers_dev = false`, placeholder hostname, admin token as a secret); there is deliberately no `wrangler.toml`, so nothing can be deployed by accident.
- `RUNBOOK.md` — authorisation requirements, deployment, staging environment, session-key provisioning, host-egress policy, harness use, teardown.

### 3.3 Staging configuration contract (Section 5)

`staging/harness/staging_config.js` validates a staging environment against the authorisation record and returns **names and codes only** (never values): `ROBIN_ENVIRONMENT=staging`; `APP_SANDBOX_ALLOWED_DOMAINS` = exactly the authorised host; `APP_SANDBOX_ALLOWED_PATH_PREFIXES` = exactly the authorised prefix; `APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS=false`; `APP_SANDBOX_REQUIRE_HTTPS=true`; staging database URL present and matching the record's fingerprint; `APP_SECRETS_KEY` / `WORKER_EVENT_TOKEN` present, ≥ 32 chars, and not on the operator's production-fingerprint deny-list; `ROBIN_WORKER_SESSION_KEY` optional — **absent ⇒ fail-safe warning (memory-only sessions)**, present ⇒ must be 32 bytes base64 and not a production secret; and no `HTTP(S)_PROXY`/`ALL_PROXY`/`NO_PROXY`/`PLAYWRIGHT_DISABLE_FORCED_CHROMIUM_PROXIED_LOOPBACK` on the worker (the egress proxy has no off switch). It always warns that the in-process proxy covers Chromium/Playwright only. No key was created, printed or reused.

### 3.4 Authorisation-gated external harness (Section 6)

`staging/harness/`:
- `authorisation.js` — the record schema and gate (exact operator FQDN; no wildcard/IP/`workers.dev`/`pages.dev`/reserved example domains; prefix; non-production + synthetic-only; window ≤ 14 days and **current time inside it**; operator; written approval dated before the window; synthetic candidate and documents; test account scoped to the host with no secret in the record; teardown with retention ≤ window + 7 days; explicit scenario list). `authorisation.example.json` is a placeholder that **fails** validation.
- `target_policy.js` — only `https://<exact host>[:443]<prefix>…`; refuses other hosts, sub-domains, look-alikes, IP literals (v4/v6), trailing-dot or non-canonical hosts, userinfo, other ports, plain HTTP, paths outside the prefix, encoded traversal, and any redirect hop that leaves the target.
- `evidence.js` — run ids (`3g-YYYYMMDD-<random>`), the four evidence categories kept separate, **REAL_EMPLOYER records refused**, an external PASS refused unless cleanup is VERIFIED, exact 40-char commit SHA required, redaction of URLs (origin + path) and of any credential/cookie/OTP/token/document field.
- `scenarios.js` — the Stage 3E §5 matrix (15 rows, unchanged in intent) mapped to fixture slugs; rows 1–12 never submit.
- `run.mjs` — gates in order: authorisation (exit 2) → staging config (exit 2) → scenario driver (exit 3). When blocked it records all 15 external scenarios as BLOCKED and contacts nothing. **Run today with no record: exit 2, `AUTHORISATION_RECORD_ABSENT`, 15 × BLOCKED.**

The harness adds to — never replaces — AddressGuard, the backend sandbox allow-list, the worker egress proxy, approval fences, ownership checks and the receipt policy, none of which was changed.

**Not implemented (next blocker after authorisation):** the **staging scenario driver** — the component that seeds synthetic jobs/applications into the staging database, drives Robin's existing APIs (package → approve-submit → prepare-submit), waits for the staging worker, reads the fixture's diagnostics and verifies teardown. It needs a staging seeding contract (Robin has no job-creation API; discovery is the only producer), which only makes sense against a provisioned staging backend.

---

## 4. Controlled validation matrix

Common fields for every row — **Commit:** the final Stage 3G HEAD · **Local run:** worker suite on this commit (383 run, 382 passed, 0 failed, 1 skipped) and the backend suite (§5) · **External test-run id:** `3g-20261126-*` harness runs (BLOCKED, no external id issued) · **Environment hostname:** none (no authorised host) · **External cleanup:** not applicable (nothing created).

| # | Scenario | Local fixture result (evidence) | Real PostgreSQL result | External staging result | Status |
|---:|---|---|---|---|---|
| 1 | Multi-step form | PASS — `universal_engine`: full multi-step slice (page 1 filled, pause on page 2, no submit) | PASS — `UniversalBrowserE2EIT` lifecycle | **BLOCKED** — no authorisation | Local ✓ / External ✗ |
| 2 | Third-party CDN | PASS — `universal_reliability` Workstream A; `egress_security` (WS/SW/popup/redirect/rebinding) | n/a | **BLOCKED** | Local ✓ / External ✗ |
| 3 | Custom CV/cover-letter upload | PASS — `universal_reliability` Workstream B; `session_security` artifact policy; fixture dropzone/PDF-only/DOCX-only variants (`staging_fixture_package`) | unit: PDF cover-letter serving (`Stage3FBackendHardeningTest`) | **BLOCKED** | Local ✓ / External ✗ |
| 4 | Conditional fields | PASS — `universal_reliability` Workstream C; fixture variant markup | n/a | **BLOCKED** | Local ✓ / External ✗ |
| 5 | Portal comboboxes | PASS — `universal_reliability` Workstream C; fixture variant | n/a | **BLOCKED** | Local ✓ / External ✗ |
| 6 | Open Shadow DOM | PASS — `universal_reliability` Workstream C (open filled, closed disclosed); fixture variant | n/a | **BLOCKED** | Local ✓ / External ✗ |
| 7 | Login with test account | PASS — `universal_reliability` Workstream D (worker side) | PASS — `UniversalBrowserE2EIT` credential binding (exact host, running plan, look-alikes refused) | **BLOCKED** | Local ✓ / External ✗ |
| 8 | MFA handoff & recovery | PASS — `universal_reliability` Workstream D, `universal_engine` security boundaries, `handoff` suite | PASS — `AtsHandoffIntegrationIT`, `UniversalBrowserE2EIT` (concurrent resume → exactly one) | **BLOCKED** | Local ✓ / External ✗ |
| 9 | Unknown mandatory question | PASS — `universal_engine` (pause + discovered-questions round-trip) | PASS — `UniversalBrowserE2EIT` (approval/resume refused until answered) | **BLOCKED** | Local ✓ / External ✗ |
| 10 | Confirmed-answer reuse | covered by backend unit tests only (`UniversalFieldMapperTest`, `UniversalBrowserPlanService`) | — | **BLOCKED** | Local (unit) ✓ / External ✗ |
| 11 | CAPTCHA pause | PASS — `universal_engine` security boundaries, `handoff` suite; fixture serves official Turnstile test markup | n/a | **BLOCKED** | Local ✓ / External ✗ |
| 12 | Redirect & SSRF | PASS — `egress_security` (19 tests incl. DNS rebinding, metadata, no-bypass proofs) + `universal_engine` | n/a | **BLOCKED** | Local ✓ / External ✗ |
| 13 | Confirmation detection | PASS — `universal_engine` (422, fake banner never confirmed); fixture `{ok:false}` variant | PASS — `UniversalBrowserE2EIT` evidence path | **BLOCKED** — submission scenario, authorisation absent | Local ✓ / External ✗ |
| 14 | Duplicate prevention | PASS — `universal_engine` crash after PRE_SUBMIT_ATTEMPTING never re-clicks; fixture idempotency counter incl. durable Durable Object restart | PASS — `UniversalBrowserE2EIT` 8 concurrent fences → exactly one | **BLOCKED** | Local ✓ / External ✗ |
| 15 | Encrypted evidence & Track | PASS — worker evidence path | PASS — `UniversalBrowserE2EIT` (encrypted `submission_evidence`, `APPLICATION_SUBMITTED`, no receipt); `ApplicationReceiptIT`; Track shows `APPLIED (Unverified)` | **BLOCKED** | Local ✓ / External ✗ |

**Counts.** External staging: **0 PASS · 0 FAIL · 15 BLOCKED.** Local fixture / PostgreSQL: 14 rows with browser-level or integration evidence passing on this commit, row 10 with unit-level evidence only. Real employer websites: **not used** (category refused by the evidence ledger).

These local passes are **not** external passes and do not establish compatibility with any real employer site.

---

## 5. Test totals (this commit)

| Suite | Result |
|---|---|
| Browser worker (`npm test`) | **383 run, 382 passed, 0 failed, 1 skipped** (POSIX-only SIGTERM test; runs on Linux CI) — includes the new `staging_fixture_package` (16) and `staging_harness` (11) suites |
| Backend (`mvn -o clean verify`) | **BUILD SUCCESS** — Surefire **1,972 run, 0 failures, 0 errors, 6 skipped** (Redis diagnostics); Failsafe / PostgreSQL 16 Testcontainers **440 run, 0 failures, 0 errors, 7 skipped** (external-database and live job-board ITs). No backend source changed in Stage 3G. |
| Frontend, Cloudflare email worker, Phase 20 probes | not changed in Stage 3G; exercised by CI on the pushed commit |

During verification one existing Stage 3F browser test (`a popup that redirects to a private network…`) failed with "execution context was destroyed": a test-sequencing race (Chromium commits the error page of a refused navigation asynchronously). Worker source is unchanged since Stage 3F; the test now returns to a stable page with a bounded retry before opening the popup (security assertions unchanged) and passed 6/6 consecutive runs plus the full suite. V001–V070: unchanged; no migration added.

---

## 6. Carried-forward blockers — re-inspected

| Blocker | Current state (code inspected) | Disposition |
|---|---|---|
| UNIVERSAL submissions cannot produce verified `submission_receipts` | `ReceiptVerificationPolicy.ALLOWED_ATS_PLATFORMS` excludes `UNIVERSAL`; `JdbcReceiptTrustStore.STRICT_APPROVAL_SQL` accepts only GREENHOUSE/ASHBY/WORKDAY approvals | **Open — needs its own reviewed design** (sandbox-registration semantics for an operator-owned host); not changed to make tests pass |
| `ROBIN_WORKER_SESSION_KEY` not configured | default disabled in code; provisioning procedure in `RUNBOOK.md` §3; staging contract warns when absent | **Open — operator** |
| Enterprise SSO on a sibling sub-domain | credential binding is exact-host (`EmployerCredentialController` / `bindingFor`) | **Open by design** — separate phase |
| Worker proxy does not protect other processes | unchanged | **Open** — host/network egress policy required (RUNBOOK §3) |
| Backend `AddressGuard` connect-time DNS gap | `AddressGuard` still documents JDK re-resolution at connect | **Open** — separate phase (pin connections for discovery fetches) |
| PDF cover-letter selection → upload E2E | still unit-level only (`Stage3FBackendHardeningTest`) | **Open** |
| **New:** staging scenario driver / seeding contract | not implemented (§3.4) | **Open — after authorisation** |
| **New:** acknowledgement-email path in the fixture | not implemented (needs an operator-verified sending domain + managed-mail test address) | **Open — after authorisation** |

## 7. Prioritised follow-ups

1. **Operator authorisation record** (§2 elements) — the gating item.
2. Provision per `RUNBOOK.md`: hostname + Cloudflare deploy, staging backend/worker/database with the contract in §3.3, host egress policy.
3. Implement the staging scenario driver + seeding contract; run the matrix inside the window; teardown verification.
4. Separate design review: UNIVERSAL verified-receipt path for operator-owned sandboxes.
5. PDF cover-letter end-to-end integration test; backend AddressGuard connection pinning; SSO sub-domain policy.

## 8. Final verdict

**STAGE 3G BLOCKED — AUTHORISATION / PROVISIONING.** No valid external authorisation or staging target exists. Local preparation (single shared fixture, deployable-but-undeployed staging package, staging configuration contract, authorisation-gated harness, 27 new tests) is complete and passing; **no scenario ran externally and no external submission occurred.**
