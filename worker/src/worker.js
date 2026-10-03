import fs from 'node:fs';
import crypto from 'node:crypto';
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
const pollUrl = process.env.WORKER_POLL_URL;
const pollIntervalMs = parseInt(process.env.WORKER_POLL_INTERVAL_MS || '10000', 10);
const heartbeatUrl = process.env.WORKER_HEARTBEAT_URL;
const completeUrl = process.env.WORKER_COMPLETE_URL;

const authHeaders = () => eventToken ? { authorization: `Bearer ${eventToken}` } : {};

// ── graceful shutdown ─────────────────────────────────────────────
// SIGTERM/SIGINT (docker stop, Render restart, OCI kill) must never leave a
// plan stranded: the active run is aborted cooperatively at the next step
// boundary (completed steps stay durable and are skipped on re-execution),
// and the outcome report is SKIPPED on shutdown so the plan remains RUNNING
// — the backend's stale-plan sweeper then reclaims it for re-execution.
// Reporting FAILED instead would strand it terminal for a full re-prepare.
const runtime = { shuttingDown: false, runner: null, pollTimer: null };

function requestShutdown(signal) {
  if (runtime.shuttingDown) return;
  runtime.shuttingDown = true;
  process.stdout.write(`${JSON.stringify({ type: 'WORKER_SHUTDOWN', signal })}\n`);
  if (runtime.pollTimer) clearInterval(runtime.pollTimer);
  if (runtime.runner) {
    // Active run: abort cooperatively at the next step boundary; the process
    // exits naturally once the run's timers and browser have been torn down.
    runtime.runner.requestAbort();
  } else {
    // No run in flight: without this the idle keepalive interval would keep
    // the event loop alive until the platform's SIGKILL deadline.
    process.exit(0);
  }
}

process.on('SIGTERM', () => requestShutdown('SIGTERM'));
process.on('SIGINT', () => requestShutdown('SIGINT'));

const eventSink = (event) => {
  process.stdout.write(`${JSON.stringify(event)}\n`);
  if (!eventUrl) return;
  const body = JSON.stringify({ eventId: `${event.planId ?? 'plan'}:${event.type}:${event.stepId ?? ''}`, ...event });
  (async () => {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        const response = await fetch(eventUrl, { method: 'POST', headers: { ...authHeaders(), 'content-type': 'application/json' }, body });
        if (response.ok) return;
      } catch {}
      await new Promise((resolve) => setTimeout(resolve, 100 * (attempt + 1)));
    }
  })();
};

async function apiCall(url, body = null) {
  const headers = { ...authHeaders(), 'content-type': 'application/json' };
  const options = { method: 'POST', headers };
  if (body) options.body = JSON.stringify(body);
  const response = await fetch(url, options);
  if (response.status === 204) return null;
  if (!response.ok) throw new Error(`BACKEND_REQUEST_FAILED:${response.status}`);
  return response.json();
}

async function reportOutcome(planId, outcome, detail) {
  if (!completeUrl) return;
  // The completion report is the single most important call the worker makes:
  // a transient backend blip must not end with the plan stranded in RUNNING
  // (which the backend sweeper would then reclaim for re-execution). Retry a
  // few times before giving up — the sweeper is the backstop either way.
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      await apiCall(completeUrl.replace('{id}', encodeURIComponent(planId)), { outcome, detail });
      return;
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 500 * (attempt + 1)));
    }
  }
  throw lastError ?? new Error('REPORT_OUTCOME_FAILED');
}

async function sendHeartbeat(planId) {
  if (!heartbeatUrl) return;
  await apiCall(heartbeatUrl.replace('{id}', encodeURIComponent(planId)));
}

async function downloadArtifact(baseUrl, artifact, destination) {
  const url = new URL(artifact.url, baseUrl);
  if (url.origin !== new URL(baseUrl).origin || !url.pathname.startsWith('/api/v1/automation/plans/')) {
    throw new Error(`ARTIFACT_URL_REJECTED:${artifact.kind}`);
  }
  const response = await fetch(url, { headers: authHeaders() });
  if (!response.ok) throw new Error(`ARTIFACT_DOWNLOAD_FAILED:${artifact.kind}:${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  const expected = artifact.sha256;
  const actual = crypto.createHash('sha256').update(bytes).digest('hex');
  if (!expected || actual !== expected) throw new Error(`ARTIFACT_CHECKSUM_MISMATCH:${artifact.kind}`);
  if (Number.isSafeInteger(artifact.byteSize) && bytes.length !== artifact.byteSize) throw new Error(`ARTIFACT_SIZE_MISMATCH:${artifact.kind}`);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  const temporary = `${destination}.tmp-${process.pid}`;
  fs.writeFileSync(temporary, bytes, { mode: 0o600 });
  fs.renameSync(temporary, destination);
  return destination;
}

async function materializeArtifacts(plan, backendBase) {
  const artifacts = plan.package?.artifacts ?? [];
  if (!Array.isArray(artifacts)) throw new Error('INVALID_GREENHOUSE_ARTIFACT_LIST');
  const seenKinds = new Set();
  for (const artifact of artifacts) {
    if (!['cv', 'coverLetter'].includes(artifact.kind) || seenKinds.has(artifact.kind)
        || artifact.versionId !== plan.package[artifact.kind === 'cv' ? 'cv' : 'coverLetter']?.versionId
        || artifact.url !== plan.package[artifact.kind === 'cv' ? 'cv' : 'coverLetter']?.url) {
      throw new Error('GREENHOUSE_ARTIFACT_METADATA_MISMATCH');
    }
    seenKinds.add(artifact.kind);
    const safeName = path.basename(artifact.fileName ?? '');
    if (!safeName || safeName !== artifact.fileName) throw new Error('UNSAFE_ARTIFACT_FILENAME');
    const destination = path.join(artifactDir, 'package', plan.correlation.applicationId, safeName);
    await downloadArtifact(backendBase, artifact, destination);
    plan.package[artifact.kind === 'cv' ? 'cv' : 'coverLetter'].path = destination;
  }
}

function planFromClaim(planId, claimed) {
  const stored = claimed.plan ?? {};
  return {
    planId,
    planType: stored.planType ?? 'FULL',
    version: stored.version ?? 1,
    correlation: stored.correlation ?? {
      jobId: stored.jobId ?? '',
      applicationId: String(claimed.applicationId ?? stored.applicationId ?? ''),
    },
    package: stored.package,
    targetUrl: stored.targetUrl ?? claimed.targetUrl,
    steps: stored.steps ?? [],
    safetyContract: stored.safetyContract,
    fields: stored.fields ?? [],
    requiredGaps: stored.requiredGaps ?? [],
  };
}

if (planFile) {
  const plan = JSON.parse(fs.readFileSync(path.resolve(planFile), 'utf8'));
  const worker = new BrowserWorker({ statePath, artifactDir, eventSink });
  runtime.runner = worker;
  worker.execute(plan, { approve, headless: true })
    .then((result) => process.stdout.write(`${JSON.stringify({ type: 'WORKER_RESULT', status: result.status })}\n`))
    .catch((error) => { process.stderr.write(`${JSON.stringify({ type: 'WORKER_ERROR', error: error.message })}\n`); process.exitCode = 1; });
} else if (pollUrl) {
  console.log(JSON.stringify({ type: 'WORKER_READY', mode: 'POLLING', pollUrl, pollIntervalMs }));
  let executing = false;

  async function poll() {
    if (executing || runtime.shuttingDown) return;
    let planId = null;
    let heartbeat;
    try {
      const claimed = await apiCall(pollUrl);
      if (!claimed?.id) return;
      executing = true;
      planId = String(claimed.id);
      const plan = planFromClaim(planId, claimed);
      if (plan.planType === 'GREENHOUSE') {
        const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
        if (apiRoot === pollUrl) throw new Error('INVALID_WORKER_POLL_URL');
        const backendBase = new URL(apiRoot);
        const packageUrl = new URL(`${backendBase.pathname.replace(/\/$/, '')}/plans/${encodeURIComponent(planId)}/package`, backendBase);
        const baseUrl = backendBase.origin;
        const response = await fetch(packageUrl, { headers: authHeaders() });
        if (!response.ok) throw new Error(`GREENHOUSE_PACKAGE_FAILED:${response.status}`);
        plan.package = await response.json();
        plan.fields = plan.package.fields ?? [];
        if (plan.package.planId !== planId || plan.package.applicationId !== plan.correlation.applicationId
            || plan.package.jobId !== plan.correlation.jobId || plan.package.expectedUrl !== plan.targetUrl) {
          throw new Error('GREENHOUSE_PACKAGE_CORRELATION_MISMATCH');
        }
        plan.requiredGaps = plan.package.requiredGaps ?? [];
        await materializeArtifacts(plan, baseUrl);
      }

      // Heartbeat on an interval while executing. A single missed beat is
      // tolerated (network jitter), but if the backend stays unreachable the
      // run must STOP: the backend's stale-plan sweeper will reclaim the plan
      // for re-execution, and continuing to drive a real employer form with
      // no live lease risks two workers operating the same application at
      // once. Stopping is always the safe direction.
      const runner = new BrowserWorker({ statePath, artifactDir, eventSink });
      runtime.runner = runner;
      let missedHeartbeats = 0;
      heartbeat = setInterval(() => {
        sendHeartbeat(planId)
          .then(() => { missedHeartbeats = 0; })
          .catch(() => {
            missedHeartbeats += 1;
            if (missedHeartbeats >= 3) {
              console.error(JSON.stringify({ type: 'WORKER_LEASE_LOST', planId, missedHeartbeats }));
              runner.requestAbort();
            }
          });
      }, 30_000);
      await runner.execute(plan, { approve: false, headless: true });
      const outcome = plan.planType === 'GREENHOUSE' ? 'HUMAN_REQUIRED' : 'COMPLETED';
      if (plan.planType === 'GREENHOUSE') await Promise.allSettled([
        eventSink({ type: 'HUMAN_REVIEW_REQUIRED', planId, jobId: plan.correlation.jobId,
          applicationId: plan.correlation.applicationId, requiredGaps: plan.requiredGaps }),
      ]);
      const detail = plan.planType === 'GREENHOUSE'
        ? `Form preparation finished; human review required (${plan.requiredGaps.length} outstanding field(s))`
        : 'Inspection completed';
      await reportOutcome(planId, outcome, detail);
      console.log(JSON.stringify({ type: 'PLAN_ENDED', planId, outcome }));
    } catch (error) {
      if (runtime.shuttingDown) {
        // A shutdown-triggered abort must NOT mark the plan FAILED: leave it
        // RUNNING so the backend's stale-plan sweeper reclaims and re-executes
        // it (completed steps are skipped; no plan contains a submit step).
        console.error(JSON.stringify({ type: 'PLAN_DEFERRED_ON_SHUTDOWN', planId }));
      } else {
        const outcome = error.name === 'HardStopError'
          ? (/CAPTCHA|ANTI_BOT|ACCESS_DENIED/i.test(String(error.reason)) ? 'BLOCKED_ANTI_BOT' : 'FAILED')
          : 'FAILED';
        console.error(JSON.stringify({ type: 'POLL_ERROR', planId, outcome, error: error.message }));
        if (planId) await reportOutcome(planId, outcome, String(error.message)).catch(() => {});
      }
    } finally {
      if (heartbeat) clearInterval(heartbeat);
      executing = false;
      runtime.runner = null;
    }
  }

  runtime.pollTimer = setInterval(poll, pollIntervalMs);
  poll();
} else {
  console.log(JSON.stringify({ type: 'WORKER_READY', mode: 'IDLE', statePath, artifactDir }));
  setInterval(() => {}, 60_000);
}
