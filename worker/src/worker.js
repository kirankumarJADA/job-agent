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
const eventSink = (event) => {
  process.stdout.write(`${JSON.stringify(event)}\\n`);
  if (eventUrl) {
    const body = JSON.stringify({ eventId: `${event.planId ?? 'plan'}:${event.type}:${event.stepId ?? ''}`, ...event });
    const headers = { 'content-type': 'application/json' };
    // Machine-to-machine auth for hosted deployments. When the backend has
    // WORKER_EVENT_TOKEN configured it rejects bearer calls that do not
    // match; when unset (local dev) the header is simply not sent.
    if (eventToken) headers['authorization'] = `Bearer ${eventToken}`;
    (async () => { for (let attempt = 0; attempt < 3; attempt += 1) { try { const response = await fetch(eventUrl, { method: 'POST', headers, body }); if (response.ok) return; } catch {} await new Promise((resolve) => setTimeout(resolve, 100 * (attempt + 1))); } })();
  }
};

if (!planFile) {
  // Safe idle mode for the container: orchestration supplies plans through a
  // queue/file adapter later; the process stays alive and exposes no browser
  // or mailbox access until a plan is explicitly supplied.
  console.log(JSON.stringify({ type: 'WORKER_READY', mode: 'IDLE', statePath, artifactDir }));
  setInterval(() => {}, 60_000);
} else {
  const plan = JSON.parse(fs.readFileSync(path.resolve(planFile), 'utf8'));
  const worker = new BrowserWorker({
    statePath,
    artifactDir,
    eventSink,
  });
  worker.execute(plan, { approve, headless: true })
    .then((result) => { process.stdout.write(`${JSON.stringify({ type: 'WORKER_RESULT', status: result.status })}\n`); })
    .catch((error) => { process.stderr.write(`${JSON.stringify({ type: 'WORKER_ERROR', error: error.message })}\n`); process.exitCode = 1; });
}
