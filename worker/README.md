# worker/ — Automation Worker (Phase 6)

This directory is intentionally empty until Phase 6 (App Assistant). It is
created now so the queue contract between the backend and the worker can be
designed early without any implementation pressure.

## Contract (designed now, implemented in P6)

- The backend's `automation` module publishes a **declarative
  `InteractionPlan`** (a sequence of steps like `NAVIGATE`, `FILL_FIELD`,
  `UPLOAD_FILE`, `CLICK`, `WAIT_FOR`, `SCREENSHOT`) onto a queue.
- The worker is a **separate deployable with zero business logic**. It only
  knows how to execute the steps in an `InteractionPlan` using Playwright,
  and reports step-by-step results back (success, failure, screenshot,
  captured page state) via events.
- Any CAPTCHA or anti-bot challenge encountered by the worker is a **hard
  stop**: the worker reports `BLOCKED_ANTI_BOT` and the pipeline routes to a
  human-review notification. The worker never attempts to solve or bypass
  challenges (see architecture doc §A3.9 / Compliance Policy Engine).
- Worker language is decided in Phase 6 (default: Python). This is
  irrelevant to Phase 1 — only the queue message contract matters now, and
  that contract lives in `backend/src/main/java/com/personal/jobagent/automation/`
  (interfaces + DTOs only, no worker code, until P6).

## Why this is a separate deployable

Keeping browser automation out of the backend process means:
1. The backend never needs a browser runtime or its dependencies.
2. A crash or hang in a Playwright session can't take down the API/dashboard.
3. The worker can be scaled, restarted, or swapped independently.

Do not add implementation code here before Phase 6 — if you find yourself
tempted to, that's a signal the phase boundary is being skipped.
