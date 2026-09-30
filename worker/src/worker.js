import fs from 'node:fs';
import path from 'node:path';
import { BrowserWorker } from './browser_worker.js';

const args = new Map();
for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i], process.argv[i + 1] ?? true);

const planFile = args.get('--plan') ?? process.env.WORKER_PLAN_FILE;
const statePath = args.get('--state') ?? process.env.WORKER_STATE_PATH ?? '/data/worker-state.json';
const artifactDir = args.get('--artifacts') ?? process.env.WORKER_ARTIFACT_DIR ?? '/data/artifacts';
const approve = args.get('--approve') === true || args.get('--approve') === 'true';
const eventUrl = process.env.WORKER_EVENT_URL;
const eventToken = process.env.WORKER_EVENT_TOKEN;
// ── polling configuration ─────────────────────────────────────────
const pollUrl = process.env.WORKER_POLL_URL;       // POST claim-next
const pollIntervalMs = parseInt(process.env.WORKER_POLL_INTERVAL_MS || '10000', 10);
const heartbeatUrl = process.env.WORKER_HEARTBEAT_URL; // POST plans/{id}/heartbeat
const completeUrl = process.env.WORKER_COMPLETE_URL;   // POST plans/{id}/complete

const eventSink = (event) => {
  process.stdout.write(`${JSON.stringify(event)}\n`);
  if (eventUrl) {
    const body = JSON.stringify({ eventId: `${event.planId ?? 'plan'}:${event.type}:${event.stepId ?? ''}`, ...event });
    const headers = { 'content-type': 'application/json' };
    if (eventToken) headers['authorization'] = `Bearer ${eventToken}`;
    (async () => { for (let attempt = 0; attempt < 3; attempt += 1) { try { const response = await fetch(eventUrl, { method: 'POST', headers, body }); if (response.ok) return; } catch {} await new Promise((resolve) => setTimeout(resolve, 100 * (attempt + 1))); } })();
  }
};

/** POST with bearer auth, returning parsed JSON or null on non-2xx. */
async function apiCall(url, body = null) {
  const headers = { 'content-type': 'application/json' };
  if (eventToken) headers['authorization'] = `Bearer ${eventToken}`;
  const opts = { method: 'POST', headers };
  if (body) opts.body = JSON.stringify(body);
  const res = await fetch(url, opts);
  if (res.status === 204) return null;
  if (!res.ok) return null;
  return res.json();
}

/** Report plan outcome back to the backend. */
async function reportOutcome(planId, outcome, detail) {
  if (!completeUrl) return;
  const url = completeUrl.replace('{id}', planId);
  await apiCall(url, { outcome, detail }).catch(() => {});
}

/** Send heartbeat for a running plan. */
async function sendHeartbeat(planId) {
  if (!heartbeatUrl) return;
  const url = heartbeatUrl.replace('{id}', planId);
  await apiCall(url).catch(() => {});
}

// ── execution modes ──────────────────────────────────────────────

if (planFile) {
  // File-driven mode: existing behavior for tests and local dev.
  const plan = JSON.parse(fs.readFileSync(path.resolve(planFile), 'utf8'));
  const worker = new BrowserWorker({ statePath, artifactDir, eventSink });
  worker.execute(plan, { approve, headless: true })
    .then((result) => { process.stdout.write(`${JSON.stringify({ type: 'WORKER_RESULT', status: result.status })}\n`); })
    .catch((error) => { process.stderr.write(`${JSON.stringify({ type: 'WORKER_ERROR', error: error.message })}\n`); process.exitCode = 1; });
} else if (pollUrl) {
  // Queue-polling mode: claim and execute plans from the backend.
  console.log(JSON.stringify({ type: 'WORKER_READY', mode: 'POLLING', pollUrl, pollIntervalMs }));
  let executing = false;

  async function poll() {
    if (executing) return;
    try {
      const claimed = await apiCall(pollUrl);
      if (!claimed || !claimed.id) return; // 204 or empty — no work available
      executing = true;
      const planId = String(claimed.id);

      // Build the plan object the worker expects from the claimed row.
      const storedPlan = claimed.plan ?? {};
      const plan = {
        planId,
        planType: storedPlan.planType ?? 'FULL',
        version: storedPlan.version ?? 1,
        correlation: storedPlan.correlation ?? {
          jobId: storedPlan.jobId ?? '',
          applicationId: String(claimed.applicationId ?? storedPlan.applicationId ?? ''),
        },
        package: storedPlan.package ?? undefined,
        steps: storedPlan.steps ?? [],
        safetyContract: storedPlan.safetyContract,
      };

      // Heartbeat on an interval while executing.
      const hbInterval = setInterval(() => sendHeartbeat(planId), 30_000);

      const worker = new BrowserWorker({ statePath, artifactDir, eventSink });
      try {
        const result = await worker.execute(plan, { approve: false, headless: true });
        await reportOutcome(planId, 'COMPLETED', 'Inspection completed');
        console.log(JSON.stringify({ type: 'PLAN_COMPLETED', planId }));
      } catch (error) {
        const outcome = error.name === 'HardStopError' ? 'BLOCKED_ANTI_BOT' : 'FAILED';
        await reportOutcome(planId, outcome, String(error.message));
        console.error(JSON.stringify({ type: 'PLAN_FAILED', planId, error: error.message }));
      } finally {
        clearInterval(hbInterval);
        executing = false;
      }
    } catch (error) {
      console.error(JSON.stringify({ type: 'POLL_ERROR', error: error.message }));
    }
  }

  setInterval(poll, pollIntervalMs);
  poll(); // immediate first poll

} else {
  // Idle mode: no plan file, no poll URL. Wait for external orchestration.
  console.log(JSON.stringify({ type: 'WORKER_READY', mode: 'IDLE', statePath, artifactDir }));
  setInterval(() => {}, 60_000);
}
