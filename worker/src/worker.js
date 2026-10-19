import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { BrowserWorker } from './browser_worker.js';
import { computePlanOutcome } from './outcome.js';

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
const verificationResolveUrl = process.env.WORKER_VERIFICATION_URL;
const registrationIdentityUrl = process.env.WORKER_REGISTRATION_IDENTITY_URL;
const employerCredentialUrl = process.env.WORKER_EMPLOYER_CREDENTIAL_URL;
const completeUrl = process.env.WORKER_COMPLETE_URL;

const authHeaders = () => eventToken ? { authorization: `Bearer ${eventToken}` } : {};
const verificationResolver = verificationResolveUrl
  ? async (body) => {
      const response = await fetch(verificationResolveUrl, { method: 'POST',
        headers: { ...authHeaders(), 'content-type': 'application/json' },
        body: JSON.stringify(body) });
      if (!response.ok) throw new Error(`VERIFICATION_RESOLVE_FAILED:${response.status}`);
      return response.json();
    }
  : null;
// Phase 8.5: which address an employer account is registered with (Robin-managed by default).
const identityResolver = registrationIdentityUrl
  ? async (body) => {
      const response = await fetch(registrationIdentityUrl, { method: 'POST',
        headers: { ...authHeaders(), 'content-type': 'application/json' },
        body: JSON.stringify(body) });
      if (!response.ok) throw new Error(`REGISTRATION_IDENTITY_FAILED:${response.status}`);
      return response.json();
    }
  : null;
// Phase 8.5 continuation: this candidate's saved portal password for one
// employer domain (EmployerCredentialService), decrypted only here, at the
// worker's own execution boundary — never logged, never written to the
// worker's on-disk state (see browser_worker.js's ACCOUNT_RESOLVE_CREDENTIAL).
const credentialResolver = employerCredentialUrl
  ? async (body) => {
      const response = await fetch(employerCredentialUrl, { method: 'POST',
        headers: { ...authHeaders(), 'content-type': 'application/json' },
        body: JSON.stringify(body) });
      if (!response.ok) throw new Error(`EMPLOYER_CREDENTIAL_FAILED:${response.status}`);
      return response.json();
    }
  : null;

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

async function reportOutcome(planId, outcome, detail, result = null) {
  if (!completeUrl) return;
  // The completion report is the single most important call the worker makes:
  // a transient backend blip must not end with the plan stranded in RUNNING
  // (which the backend sweeper would then reclaim for re-execution). Retry a
  // few times before giving up — the sweeper is the backstop either way.
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      await apiCall(completeUrl.replace('{id}', encodeURIComponent(planId)), { outcome, detail, result });
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

/**
 * Robin x Tsenta hardening: whether a ZOHO_RECRUIT plan's package needs
 * artifact materialization at all — keyed on the PACKAGE's mere presence,
 * never on any one specific document. The previous condition
 * (`plan.package?.cv`) silently skipped materialization whenever a plan had
 * ONLY an approved cover letter and no CV: a real, correctly-embedded
 * `plan.package.coverLetter`/`plan.package.artifacts` entry would simply
 * never be downloaded, so `.path` stayed unset and the worker reported
 * `NO_ARTIFACT` for a document that genuinely existed. `materializeArtifacts`
 * itself already safely no-ops when `plan.package.artifacts` is empty, so
 * gating on the package's presence — rather than re-deriving which specific
 * documents it holds — is both correct and simpler; exported for its own
 * direct unit test.
 */
export function zohoRecruitPackageNeedsMaterialization(plan) {
  return plan.planType === 'ZOHO_RECRUIT' && Boolean(plan.package);
}

export async function materializeArtifacts(plan, backendBase) {
  // Shared by GREENHOUSE and ZOHO_RECRUIT (any plan type whose package
  // carries cv/coverLetter + a matching artifacts list) — error identifiers
  // are deliberately ATS-agnostic, not "GREENHOUSE_...", since a ZOHO_RECRUIT
  // cover-letter-only package hits this same code path.
  const artifacts = plan.package?.artifacts ?? [];
  if (!Array.isArray(artifacts)) throw new Error('INVALID_ARTIFACT_LIST');
  const seenKinds = new Set();
  for (const artifact of artifacts) {
    if (!['cv', 'coverLetter'].includes(artifact.kind) || seenKinds.has(artifact.kind)
        || artifact.versionId !== plan.package[artifact.kind === 'cv' ? 'cv' : 'coverLetter']?.versionId
        || artifact.url !== plan.package[artifact.kind === 'cv' ? 'cv' : 'coverLetter']?.url) {
      throw new Error('ARTIFACT_METADATA_MISMATCH');
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

// Robin x Tsenta hardening: guards the entire dispatch below so this module
// can be safely `import`ed (e.g. by worker.test.js, to unit-test
// `materializeArtifacts`/`zohoRecruitPackageNeedsMaterialization` directly)
// without starting a real poll loop or idle keepalive interval — those only
// run when this file is actually executed as the process entrypoint, the
// same distinction CommonJS's `require.main === module` draws.
const isMainModule = process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url;
if (isMainModule) {
if (planFile) {
  const plan = JSON.parse(fs.readFileSync(path.resolve(planFile), 'utf8'));
  const worker = new BrowserWorker({ statePath, artifactDir, eventSink, verificationResolver, identityResolver, credentialResolver });
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
      } else if (zohoRecruitPackageNeedsMaterialization(plan)) {
        // Robin x Tsenta continuation: unlike GREENHOUSE, the package is
        // already embedded in the claimed plan JSON (ZohoRecruitExecutionPlanService
        // builds it directly, with no live-DOM-dependent fields to fetch
        // separately) — only the document bytes themselves need downloading,
        // reusing the exact same correlation/checksum-verified download path.
        // Triggers whenever a package exists at all (cv-only, cover-letter-
        // only, or both) — see zohoRecruitPackageNeedsMaterialization's own
        // comment for the bug this replaced (gating on `.cv` alone silently
        // skipped a cover-letter-only package).
        const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
        if (apiRoot === pollUrl) throw new Error('INVALID_WORKER_POLL_URL');
        await materializeArtifacts(plan, new URL(apiRoot).origin);
      }

      // Heartbeat on an interval while executing. A single missed beat is
      // tolerated (network jitter), but if the backend stays unreachable the
      // run must STOP: the backend's stale-plan sweeper will reclaim the plan
      // for re-execution, and continuing to drive a real employer form with
      // no live lease risks two workers operating the same application at
      // once. Stopping is always the safe direction.
      const runner = new BrowserWorker({ statePath, artifactDir, eventSink, verificationResolver, identityResolver, credentialResolver });
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
      const runResult = await runner.execute(plan, { approve: false, headless: true });
      // Phase 4: a GREENHOUSE plan whose last step is REAL_SUBMIT_CLICK has
      // just attempted (or confirmed an already-completed) real submission —
      // its outcome depends on what the worker actually observed, never a
      // hardcoded assumption. Every other GREENHOUSE plan still ends at
      // validate-form exactly as before, always awaiting human review.
      const isSubmitAttempt = plan.planType === 'GREENHOUSE'
        && plan.steps.at(-1)?.type === 'REAL_SUBMIT_CLICK';
      const { outcome, detail, result } = computePlanOutcome(plan, runResult);
      if (plan.planType === 'GREENHOUSE' && !isSubmitAttempt) {
        await Promise.allSettled([
          eventSink({ type: 'HUMAN_REVIEW_REQUIRED', planId, jobId: plan.correlation.jobId,
            applicationId: plan.correlation.applicationId, requiredGaps: plan.requiredGaps }),
        ]);
      } else if (plan.planType === 'ZOHO_RECRUIT') {
        const zoho = runResult.zohoRecruitResult ?? {};
        await Promise.allSettled([
          eventSink({ type: 'HUMAN_REVIEW_REQUIRED', planId, jobId: plan.correlation.jobId,
            applicationId: plan.correlation.applicationId, requiredGaps: zoho.requiredGaps ?? [],
            filled: zoho.filled ?? [], fields: zoho.fields ?? [],
            resumeUploadState: zoho.resumeUploadState ?? 'NO_FIELD',
            coverLetterUploadState: zoho.coverLetterUploadState ?? 'NO_FIELD' }),
        ]);
      }
      await reportOutcome(planId, outcome, detail, result);
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
        // Persist WHY this plan stopped so the review panel can show the candidate
        // a real reason (missing info, CAPTCHA, a lost lease, …) instead of a bare
        // "failed" with no explanation — reuses the existing generic `result`
        // column and `transitionWithResult` path, the same one ZOHO_RECRUIT_OUTCOME
        // already relies on; no backend change was needed for this.
        const errorResult = [{
          type: 'PLAN_ERROR',
          outcome,
          reason: error.name === 'HardStopError' ? error.reason : null,
          message: String(error.message),
        }];
        if (planId) await reportOutcome(planId, outcome, String(error.message), errorResult).catch(() => {});
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
}
