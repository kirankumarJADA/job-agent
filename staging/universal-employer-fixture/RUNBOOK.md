# Robin synthetic employer portal — operator runbook

**Status: NOT DEPLOYED.** Nothing in this directory may be deployed, given DNS, or pointed at by any Robin
environment until §1 is complete. This package contains only synthetic pages; it never proxies or fetches
anything and must never be used against, or made to look like, a real employer.

## 1. Authorisation (must exist first)

The operator creates an authorisation record (shape: `../harness/authorisation.example.json`) **outside the
repository**, and it must pass `validateAuthorisation` (`../harness/authorisation.js`):

| Field | Requirement |
|---|---|
| `hostname` | exact lower-case FQDN on an operator-owned zone that serves no production users; no wildcard, IP, `workers.dev`/`pages.dev` or reserved example domain |
| `pathPrefix` | e.g. `/robin-fixture/` (must start and end with `/`) |
| `nonProduction`, `syntheticDataOnly` | both `true` |
| `window` | start/end ISO-8601, at most 14 days; harness refuses outside it |
| `operator` | name + contact of the authorised test operator |
| `approval` | `approvedBy` + `approvedAt` (written approval, dated before the window opens) |
| `syntheticCandidate` | label, `realPersonalData: false` |
| `syntheticDocuments` | CV (and cover letter) generated through Robin's PREP flow from the synthetic profile |
| `testAccount` | `host` equal to `hostname`, `storedVia: "employer-credentials-api"` — **no password in the record** |
| `mailbox` | optional managed-mail test address for OTP/acknowledgement scenarios |
| `teardown` | mechanism + retention deadline no later than 7 days after the window |
| `scenariosAuthorised` | the matrix rows (1–15) the operator approves; 13–15 submit to the fixture |

## 2. Deploy the portal (operator, after §1)

1. `cp wrangler.toml.example wrangler.toml` and replace both `REPLACE-WITH-AUTHORISED-HOSTNAME` values with the
   record's `hostname`; set `FIXTURE_PATH_PREFIX` to the record's `pathPrefix` without the trailing slash.
2. `wrangler secret put FIXTURE_ADMIN_TOKEN` — at least 32 random characters, generated for this purpose only.
3. In the Cloudflare dashboard for the zone: TLS mode **Full (strict)**, minimum TLS **1.2**, **Always Use HTTPS**,
   HSTS enabled. Optionally restrict the hostname with Cloudflare Access / WAF to the staging worker's egress IP.
4. `wrangler deploy`. `workers_dev = false`, so no `*.workers.dev` URL exists; any other `Host` gets `421`.
5. Smoke check (no form data): `GET https://<host>/robots.txt` → `Disallow: /`;
   `GET https://<host>/<prefix>/s01-multistep/careers/jobs/stage3-role/apply` → page 1 with HSTS + `noindex` headers.

Scenario pages: `https://<host><prefix>/<slug>/careers/jobs/stage3-role/apply`, slugs listed in
`src/index.js` (`SCENARIO_PROFILES`). Diagnostics/teardown (admin token only):
`GET|POST https://<host><prefix>/__robin/diagnostics|teardown?scenario=<slug>` — counters and receipt ids only.

## 3. Staging Robin environment (operator, after §1)

Separate from production in every respect: its own Render backend + worker services (or equivalent), its own
disposable PostgreSQL database, its own secrets. Required environment (validated by
`../harness/staging_config.js` — values are never printed):

```
ROBIN_ENVIRONMENT=staging
APP_SANDBOX_ALLOWED_DOMAINS=<record.hostname>            # exactly one host
APP_SANDBOX_ALLOWED_PATH_PREFIXES=<record.pathPrefix>    # exactly one prefix
APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS=false
APP_SANDBOX_REQUIRE_HTTPS=true
SPRING_DATASOURCE_URL=<staging database only>            # fingerprint recorded in the authorisation record
APP_SECRETS_KEY=<new, staging-only>                      # never a production key
WORKER_EVENT_TOKEN=<new, staging-only>
# Optional — leave UNSET unless crash-resume with saved sessions is needed:
# ROBIN_WORKER_SESSION_KEY=<base64 of 32 random bytes, staging-only>
```

No `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`/`PLAYWRIGHT_DISABLE_FORCED_CHROMIUM_PROXIED_LOOPBACK` on the worker: the
egress proxy has no off switch and these would signal an attempted bypass.

**Session vault key.** If used: generate on the operator's machine (`openssl rand -base64 32`), store only in the
staging secret store, never print or commit it, never reuse it elsewhere, rotate by replacing it (old sessions
then fail to decrypt and are deleted — fail-safe). Without it, sessions are memory-only and a crash-restarted run
must log in again (verified by `session_security.test.js`).

**Host egress.** The worker's in-process proxy covers Chromium and Playwright traffic only. For staging, add a
host/network policy denying RFC 1918, link-local/metadata (169.254.0.0/16, fd00:ec2::254) and loopback egress
for the worker container, and verify it before claiming host-level protection.

## 4. Run the harness (operator, inside the window)

```
node staging/harness/run.mjs --authorisation <record.json> --commit <exact sha> --out <ledger.json>
```

Exit `2` = an authorisation or staging-config gate failed (every external scenario recorded BLOCKED);
exit `3` = gates passed but no staging scenario driver is configured yet. Evidence categories are never merged.

## 5. Teardown and retention

After each scenario: `POST /__robin/teardown?scenario=<slug>` and confirm diagnostics read zero. The Durable
Object alarm wipes every scenario's storage `DATA_TTL_HOURS` (default 24 h, max 72 h) after first use. After the
window: `wrangler delete`, remove the DNS route, destroy the staging database, revoke the staging secrets and the
test portal account, and record completion against the authorisation record's retention deadline.
