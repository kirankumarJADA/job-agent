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
