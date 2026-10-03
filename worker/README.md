# Automation Worker

The worker is now the safe browser-execution boundary for declarative
`InteractionPlan` messages. It contains no job-discovery or LLM business logic.
Business code selects the exact job/application package; the worker validates
that package and executes only the supplied plan.

## Components

- `src/plan.js` — canonical plan steps, deterministic selector allowlist,
  package/job isolation validation, and `AUTO` / `REQUIRES_APPROVAL` /
  `FORBIDDEN` policy gates.
- `src/browser_worker.js` — Playwright Chromium executor with navigation,
  fills, selects, radios, checkboxes, uploads, multi-step forms, retries,
  session storage, screenshots, hard stops, durable step idempotency, and
  crash/restart recovery.
- `src/mailbox.js` — restricted mailbox adapter. It accepts only the expected
  application id, trusted sender domain, and fresh messages, and extracts an
  OTP/link without logging its value.
- `src/store.js` — atomic JSON state store. Completed steps are skipped on
  replay; stale `RUNNING` plans are marked recoverable; sessions are persisted
  per application.
- `src/mock_environment.js` — local mock employer and mailbox used by all E2E
  tests. No real employer, mailbox, or application is accessed.

## Commands

```bash
npm install
npx playwright install chromium
npm test
npm run e2e
npm start -- --plan ./plan.json --state ./state.json --artifacts ./artifacts
```

The Docker image uses the official Playwright Chromium image and runs as the
non-root `pwuser`. Compose starts the worker only under the `worker` profile;
without a plan it remains in safe idle mode.

## Safety guarantees

- CAPTCHA, Cloudflare, anti-bot, access-denied, and similar pages are hard
  stops. The worker never attempts to solve or bypass them.
- Real submission is forbidden by the policy step. The E2E uses only the mock
  employer's submission endpoint.
- Approval-required submission pauses before the click and resumes after an
  explicit approval flag.
- Credentials are used only for the local mock flow and are not logged.
- OTPs, links, cookies, and session state are not emitted in event logs.
- CV and cover-letter paths must carry the exact application job id; mismatch
  is rejected before browser launch.
- Duplicate plans and duplicate verification/submission are controlled by the
  durable plan state and completed-step replay behavior.

---

## Production deployment (Phase 16 — Render worker service / OCI container)

The image is built from `worker/Dockerfile` (official Playwright base pinned
to the dependency major, non-root `pwuser`, graceful-shutdown entrypoint).

### Environment variables (Render worker service)

| Variable | Value |
|---|---|
| `WORKER_POLL_URL` | `https://<backend>/api/v1/automation/plans/claim-next` |
| `WORKER_HEARTBEAT_URL` | `https://<backend>/api/v1/automation/plans/{id}/heartbeat` (literal `{id}` is replaced per plan) |
| `WORKER_COMPLETE_URL` | `https://<backend>/api/v1/automation/plans/{id}/complete` |
| `WORKER_EVENT_URL` | `https://<backend>/api/v1/automation/events` |
| `WORKER_EVENT_TOKEN` | same value as the backend's `WORKER_EVENT_TOKEN` (sent as Bearer) |
| `WORKER_POLL_INTERVAL_MS` | optional, default `10000` |

`WORKER_POLL_URL` is also the base the worker derives package and artifact
URLs from, so it must keep the `/api/v1/automation` prefix.

### Behaviour notes

- Empty queue: the claim endpoint returns 204 (or 404 on some gateways) — the
  worker sleeps for `WORKER_POLL_INTERVAL_MS` and polls again.
- Heartbeat: every 30s while a plan is executing. Three consecutive failures
  abort the run cooperatively (the plan stays RUNNING and the backend's
  stale-plan sweeper reclaims it).
- Shutdown: SIGTERM/SIGINT stop polling and abort the active run at the next
  step boundary; the outcome report is skipped so the plan is re-claimed and
  re-executed after restart. Completed steps are never redone.
- Artifacts: downloaded under `/data/artifacts`, sha256- and size-verified
  before use; `/data` must be writable by uid 1000.

### Local production-like check

```
docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod-like.yml \
  --profile worker up -d --build
```
