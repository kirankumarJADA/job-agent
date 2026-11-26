import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { BrowserWorker } from './browser_worker.js';
import { StepType } from './plan.js';
import { computePlanOutcome } from './outcome.js';
import { initiateHandoff, isHandoffEligibleError } from './handoff.js';
import { redactSecrets, sanitizeUrl } from './secret_redaction.js';
import { StateStore } from './store.js';
import { SessionVault } from './session_vault.js';
import { assertArtifactAcceptable, ArtifactPolicyError, MAX_UNIVERSAL_UPLOAD_BYTES } from './artifact_policy.js';

const args = new Map();
for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i], process.argv[i + 1] ?? true);

const planFile = args.get('--plan') ?? process.env.WORKER_PLAN_FILE;
const statePath = args.get('--state') ?? process.env.WORKER_STATE_PATH ?? '/data/worker-state.json';
const artifactDir = args.get('--artifacts') ?? process.env.WORKER_ARTIFACT_DIR ?? '/data/artifacts';
// Stage 3F: optional AES-256-GCM key (base64, 32 bytes) for the encrypted browser
// session vault. Unset (the default) = sessions are never written to disk.
const sessionKey = process.env.ROBIN_WORKER_SESSION_KEY || null;
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

/** Stage 3F: on shutdown no browser session may outlive the process, encrypted or not. */
function purgeAllSessionsOnShutdown() {
  try {
    const removed = new SessionVault({ artifactDir }).purgeAll();
    process.stdout.write(`${JSON.stringify({ type: 'WORKER_SESSIONS_PURGED', count: removed })}\n`);
  } catch (error) {
    process.stderr.write(`${JSON.stringify({ type: 'WORKER_SESSION_PURGE_FAILED', error: redactSecrets(error?.message ?? error) })}\n`);
  }
}

function requestShutdown(signal) {
  if (runtime.shuttingDown) return;
  runtime.shuttingDown = true;
  process.stdout.write(`${JSON.stringify({ type: 'WORKER_SHUTDOWN', signal })}\n`);
  // Runs after an active run has unwound (and its own per-plan purge), or now when idle.
  process.once('exit', purgeAllSessionsOnShutdown);
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

// ── resilient HTTP client retry & dead-letter poison-pill handling ────────
/**
 * Determines whether an HTTP status code represents a transient cloud infrastructure error
 * that is eligible for retry (502 Bad Gateway, 503 Service Unavailable, 504 Gateway Timeout).
 */
export function isRetryableStatus(status) {
  const s = Number(status);
  return s === 502 || s === 503 || s === 504;
}

/**
 * Determines whether an error is a transient network/fetch failure.
 */
export function isRetryableNetworkError(error) {
  if (!error) return false;
  if (error.status && isRetryableStatus(error.status)) return true;
  const msg = String(error.message || '').toLowerCase();
  const code = String(error.code || '').toUpperCase();
  const name = String(error.name || '');
  return code === 'ECONNRESET' || code === 'ETIMEDOUT' || code === 'ECONNREFUSED' ||
         (name === 'TypeError' && msg.includes('fetch')) ||
         msg.includes('fetch failed') || msg.includes('network') || msg.includes('econnreset') || msg.includes('etimedout');
}

/**
 * Calculates exponential backoff with full jitter according to the architecture specification:
 * ceiling = min(maxDelayMs, baseDelayMs * 2^attempt)
 * sleep = floor(randomFn() * ceiling)
 */
export function calculateBackoff(attempt, baseDelayMs = 500, maxDelayMs = 10000, randomFn = Math.random) {
  const cap = Math.min(maxDelayMs, baseDelayMs * Math.pow(2, attempt));
  const jittered = randomFn() * cap;
  return Math.floor(jittered);
}

/**
 * Resilient HTTP client wrapper executing fetch with exponential backoff and full jitter
 * for transient cloud 502/503/504 errors and network disconnects.
 */
export async function fetchWithRetry(url, options = {}, {
  maxRetries = 3,
  baseDelayMs = 500,
  maxDelayMs = 10000,
  randomFn = Math.random,
  sleepFn = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  fetchFn = (u, opt) => fetch(u, opt),
  onRetry = null,
} = {}) {
  let attempt = 0;
  while (true) {
    let response = null;
    let error = null;
    try {
      response = await fetchFn(url, options);
    } catch (err) {
      error = err;
    }

    if (response) {
      if (response.ok || response.status === 204) {
        return response;
      }

      if (attempt < maxRetries && isRetryableStatus(response.status)) {
        const delay = calculateBackoff(attempt, baseDelayMs, maxDelayMs, randomFn);
        if (typeof onRetry === 'function') {
          onRetry({ attempt, status: response.status, delay, error: null });
        }
        await sleepFn(delay);
        attempt += 1;
        continue;
      }

      return response;
    } else {
      if (attempt < maxRetries && isRetryableNetworkError(error)) {
        const delay = calculateBackoff(attempt, baseDelayMs, maxDelayMs, randomFn);
        if (typeof onRetry === 'function') {
          onRetry({ attempt, status: null, delay, error });
        }
        await sleepFn(delay);
        attempt += 1;
        continue;
      }
      throw error;
    }
  }
}

export const MAX_PLAN_CRASHES = 3;

/**
 * Tracks crashes and provides terminal isolation for poison-pill plans.
 *
 * STORAGE PERSISTENCE MODEL:
 * - When `WORKER_DEAD_LETTER_PATH` is unset (or null), this registry operates
 *   STRICTLY IN-MEMORY using JavaScript Map instances. In-memory state (crash counts
 *   and dead-letter records) will be cleared upon worker process restart.
 * - When `WORKER_DEAD_LETTER_PATH` is configured (e.g., pointing to a local or volume-mounted
 *   JSON file), state is persisted atomically via write-and-rename and reloaded at startup.
 *   This enables crash counts and dead-letter statuses to survive container restarts and spot termination.
 */
export class DeadLetterRegistry {
  constructor({ maxCrashes = MAX_PLAN_CRASHES, persistPath = process.env.WORKER_DEAD_LETTER_PATH || null } = {}) {
    this.maxCrashes = maxCrashes;
    this.persistPath = persistPath;
    this.crashes = new Map();
    this.deadLetters = new Map();
    this.requeueCounts = new Map();
    if (this.persistPath) this.#load();
  }

  recordCrash(planId, error) {
    if (!planId) return 0;
    const safeMessage = redactSecrets(error?.message || String(error ?? ''));
    const list = this.crashes.get(planId) || [];
    list.push({ at: Date.now(), message: safeMessage });
    this.crashes.set(planId, list);
    if (list.length >= this.maxCrashes) {
      this.markDeadLetter(planId, `Poison-pill plan exceeded max crash limit (${list.length}/${this.maxCrashes}): ${safeMessage}`);
    }
    this.#save();
    return list.length;
  }

  getCrashCount(planId) {
    return this.crashes.get(planId)?.length || 0;
  }

  isDeadLetter(planId) {
    return this.deadLetters.has(planId);
  }

  getDeadLetter(planId) {
    return this.deadLetters.get(planId);
  }

  markDeadLetter(planId, reason) {
    if (!planId) return;
    this.deadLetters.set(planId, {
      at: Date.now(),
      reason: redactSecrets(String(reason || 'POISON_PILL_PLAN')),
      crashes: this.getCrashCount(planId),
    });
    this.#save();
  }

  reconcileRequeue(planId, requeueCount = 0) {
    if (!planId || !Number.isInteger(requeueCount) || requeueCount <= 0) return false;
    const prev = this.requeueCounts.get(planId) ?? 0;
    if (requeueCount > prev) {
      this.crashes.delete(planId);
      this.deadLetters.delete(planId);
      this.requeueCounts.set(planId, requeueCount);
      this.#save();
      return true;
    }
    return false;
  }

  clear(planId) {
    if (planId) {
      this.crashes.delete(planId);
      this.deadLetters.delete(planId);
    } else {
      this.crashes.clear();
      this.deadLetters.clear();
      this.requeueCounts.clear();
    }
    this.#save();
  }

  #load() {
    try {
      if (this.persistPath && fs.existsSync(this.persistPath)) {
        const data = JSON.parse(fs.readFileSync(this.persistPath, 'utf8'));
        if (data.deadLetters) {
          for (const [k, v] of Object.entries(data.deadLetters)) {
            this.deadLetters.set(k, v && typeof v === 'object'
              ? { ...v, reason: redactSecrets(String(v.reason || 'POISON_PILL_PLAN')) }
              : v);
          }
        }
        if (data.crashes) {
          for (const [k, v] of Object.entries(data.crashes)) {
            const list = Array.isArray(v)
              ? v.map((entry) => (entry && typeof entry === 'object'
                ? { ...entry, message: redactSecrets(entry.message ?? '') }
                : entry))
              : v;
            this.crashes.set(k, list);
          }
        }
        if (data.requeueCounts) {
          for (const [k, v] of Object.entries(data.requeueCounts)) this.requeueCounts.set(k, v);
        }
      }
    } catch {}
  }

  #save() {
    try {
      if (this.persistPath) {
        const obj = {
          deadLetters: Object.fromEntries(this.deadLetters),
          crashes: Object.fromEntries(this.crashes),
          requeueCounts: Object.fromEntries(this.requeueCounts),
        };
        fs.mkdirSync(path.dirname(this.persistPath), { recursive: true });
        const tmp = `${this.persistPath}.tmp-${process.pid}`;
        fs.writeFileSync(tmp, JSON.stringify(obj, null, 2), { mode: 0o600 });
        fs.renameSync(tmp, this.persistPath);
      }
    } catch {}
  }
}

export const defaultDeadLetterRegistry = new DeadLetterRegistry();

const DEFAULT_PROCESS_PASS_ID = crypto.randomUUID();

export function buildWorkerEventBody(event) {
  const passSegment = event?.passId ?? DEFAULT_PROCESS_PASS_ID;
  const sequenceOrStep = event?.stepId
    ? String(event.stepId)
    : (event?.eventSeq !== undefined && event?.eventSeq !== null ? `seq-${event.eventSeq}` : '');
  return {
    eventId: `${event?.planId ?? 'plan'}:${passSegment}:${event?.type ?? 'EVENT'}:${sequenceOrStep}`,
    ...event,
  };
}

export function createEventSink({
  eventUrlVal = eventUrl,
  authHeadersFn = authHeaders,
  fetchWithRetryFn = fetchWithRetry,
  stdoutWrite = (line) => process.stdout.write(line),
} = {}) {
  return async (event) => {
    stdoutWrite(`${JSON.stringify(event)}\n`);
    if (!eventUrlVal) return { delivered: false, skipped: true };
    const body = JSON.stringify(buildWorkerEventBody(event));
    try {
      const response = await fetchWithRetryFn(eventUrlVal, {
        method: 'POST',
        headers: { ...authHeadersFn(), 'content-type': 'application/json' },
        body,
      });
      return { delivered: response?.ok !== false, status: response?.status ?? null };
    } catch {
      return { delivered: false, failed: true };
    }
  };
}

const eventSink = createEventSink();

export async function apiCall(url, body = null, {
  retryConfig = {},
  authHeadersFn = authHeaders,
  fetchFn = (u, opt) => fetch(u, opt),
} = {}) {
  const headers = { ...authHeadersFn(), 'content-type': 'application/json' };
  const options = { method: 'POST', headers };
  if (body) options.body = JSON.stringify(body);
  const response = await fetchWithRetry(url, options, { fetchFn, ...retryConfig });
  if (response.status === 204) return null;
  if (!response.ok) {
    let errorBody = null;
    try {
      if (typeof response.text === 'function') {
        const rawText = await response.text();
        if (rawText) {
          try {
            errorBody = JSON.parse(rawText);
          } catch {
            errorBody = { message: rawText };
          }
        }
      } else if (typeof response.json === 'function') {
        errorBody = await response.json();
      }
    } catch {}
    const err = new Error(`BACKEND_REQUEST_FAILED:${response.status}`);
    err.status = response.status;
    if (errorBody !== null) err.body = errorBody;
    throw err;
  }
  return response.json();
}

/**
 * Reports plan completion or failure outcome to the backend.
 *
 * RETRY OWNERSHIP ARCHITECTURE:
 * To eliminate nested/multiplicative retry cascades, `fetchWithRetry()` is the sole
 * retry owner across the worker pipeline. `reportOutcome()` delegates directly to
 * `apiCallFn()`, passing `retryConfig` and optional `fetchFn` downwards.
 * This guarantees strictly bounded attempts (initial + maxRetries) and prevents
 * catastrophic request amplification. POST requests to the completion endpoint are
 * safe and idempotent under the backend's state machine.
 */
export async function reportOutcome(planId, outcome, detail, result = null, {
  apiCallFn = apiCall,
  completeUrlVal = completeUrl,
  retryConfig = {},
  fetchFn,
} = {}) {
  const targetUrl = completeUrlVal || completeUrl;
  if (!targetUrl) return null;
  const url = targetUrl.replace('{id}', encodeURIComponent(planId));
  const body = { outcome, detail, result };

  const callOpts = { retryConfig };
  if (fetchFn) callOpts.fetchFn = fetchFn;
  return await apiCallFn(url, body, callOpts);
}

export async function sendHeartbeat(planId, { apiCallFn = apiCall, heartbeatUrlVal = heartbeatUrl } = {}) {
  const target = heartbeatUrlVal || heartbeatUrl;
  if (!target) return;
  await apiCallFn(target.replace('{id}', encodeURIComponent(planId)));
}

async function downloadArtifact(baseUrl, artifact, destination, { enforceSignature = false } = {}) {
  const url = new URL(artifact.url, baseUrl);
  if (url.origin !== new URL(baseUrl).origin || !url.pathname.startsWith('/api/v1/automation/plans/')) {
    throw new Error(`ARTIFACT_URL_REJECTED:${artifact.kind}`);
  }
  // Stage 3F (Workstream D): one 10 MiB ceiling for every artifact, checked
  // against both the declared size and the bytes actually received.
  if (Number.isSafeInteger(artifact.byteSize) && artifact.byteSize > MAX_UNIVERSAL_UPLOAD_BYTES) {
    throw new Error(`ARTIFACT_TOO_LARGE:${artifact.kind}`);
  }
  const response = await fetchWithRetry(url, { headers: authHeaders() });
  if (!response.ok) throw new Error(`ARTIFACT_DOWNLOAD_FAILED:${artifact.kind}:${response.status}`);
  const declaredLength = Number(response.headers?.get?.('content-length'));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_UNIVERSAL_UPLOAD_BYTES) {
    throw new Error(`ARTIFACT_TOO_LARGE:${artifact.kind}`);
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > MAX_UNIVERSAL_UPLOAD_BYTES) throw new Error(`ARTIFACT_TOO_LARGE:${artifact.kind}`);
  const expected = artifact.sha256;
  const actual = crypto.createHash('sha256').update(bytes).digest('hex');
  if (!expected || actual !== expected) throw new Error(`ARTIFACT_CHECKSUM_MISMATCH:${artifact.kind}`);
  if (Number.isSafeInteger(artifact.byteSize) && bytes.length !== artifact.byteSize) throw new Error(`ARTIFACT_SIZE_MISMATCH:${artifact.kind}`);
  if (enforceSignature) {
    try {
      assertArtifactAcceptable(bytes, {
        contentType: artifact.contentType ?? response.headers?.get?.('content-type'),
        fileName: artifact.fileName,
      });
    } catch (error) {
      if (error instanceof ArtifactPolicyError) throw new Error(`${error.reason}:${artifact.kind}`);
      throw error;
    }
  }
  fs.mkdirSync(path.dirname(destination), { recursive: true, mode: 0o700 });
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

/**
 * Phase 14: the ASHBY counterpart — {@code AshbyExecutionPlanService} embeds
 * its package the same way Zoho's own service does (cv only, never a cover
 * letter — see that service's own module comment), so the same
 * presence-gated reasoning applies unchanged. Exported for its own direct
 * unit test, same as {@link zohoRecruitPackageNeedsMaterialization}.
 */
export function ashbyPackageNeedsMaterialization(plan) {
  return plan.planType === 'ASHBY' && Boolean(plan.package);
}

/**
 * Phase 19: Workday package materialization counterpart.
 */
export function workdayPackageNeedsMaterialization(plan) {
  return plan.planType === 'WORKDAY' && Boolean(plan.package);
}

/**
 * Stage 3B: Universal engine package materialization counterpart.
 */
export function universalPackageNeedsMaterialization(plan) {
  return plan.planType === 'UNIVERSAL' && Boolean(plan.package);
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
    await downloadArtifact(backendBase, artifact, destination, { enforceSignature: plan.planType === 'UNIVERSAL' });
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
    completedSteps: claimed.completedSteps ?? stored.completedSteps ?? [],
  };
}

async function reportPlanStep(planId, body, { pollUrl, authHeadersFn }) {
  const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
  const stepUrl = new URL(`${new URL(apiRoot).pathname.replace(/\/$/, '')}/plans/${encodeURIComponent(planId)}/steps`, apiRoot);
  const res = await fetchWithRetry(stepUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeadersFn() },
    body: JSON.stringify(body),
  });
  if (res.status === 409) return { conflict: true };
  if (!res.ok) throw new Error(`STEP_REPORT_FAILED:${res.status}`);
  return { ok: true };
}

async function reportDiscoveredQuestions(planId, body, { pollUrl, authHeadersFn }) {
  const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
  const qUrl = new URL(`${new URL(apiRoot).pathname.replace(/\/$/, '')}/plans/${encodeURIComponent(planId)}/discovered-questions`, apiRoot);
  const res = await fetchWithRetry(qUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeadersFn() },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`DISCOVERED_QUESTIONS_REPORT_FAILED:${res.status}`);
  return res.json().catch(() => ({ ok: true }));
}

export async function executePollCycle({
  pollUrl,
  apiCallFn = apiCall,
  reportOutcomeFn = reportOutcome,
  sendHeartbeatFn = sendHeartbeat,
  runner = null,
  initiateHandoffFn = initiateHandoff,
  authHeadersFn = authHeaders,
  stepsReporterFn = null,
  discoveredQuestionsReporterFn = null,
  runtimeObj = runtime,
  statePathVal = statePath,
  artifactDirVal = artifactDir,
  eventSinkFn = eventSink,
  verificationResolverFn = verificationResolver,
  identityResolverFn = identityResolver,
  credentialResolverFn = credentialResolver,
  deadLetterRegistry = defaultDeadLetterRegistry,
  maxCrashLimit = MAX_PLAN_CRASHES,
} = {}) {
  let planId = null;
  let plan = null;
  let heartbeat;
  let passEventSink = eventSinkFn;
  const inFlightEvents = new Set();
  try {
    const claimed = await apiCallFn(pollUrl);
    if (!claimed?.id) return { status: 'NO_PLAN' };
    planId = String(claimed.id);
    const passId = crypto.randomUUID();
    let nextEventSeq = 0;
    passEventSink = (event) => {
      const hasStep = event?.stepId !== undefined && event?.stepId !== null && event?.stepId !== '';
      const hasSeq = event?.eventSeq !== undefined && event?.eventSeq !== null;
      const enriched = {
        ...event,
        passId: event?.passId ?? passId,
        ...(hasStep || hasSeq ? {} : { eventSeq: ++nextEventSeq }),
      };
      let pending;
      try {
        pending = Promise.resolve(eventSinkFn(enriched));
      } catch {
        pending = Promise.resolve({ delivered: false, failed: true });
      }
      const tracked = pending.catch(() => ({ delivered: false, failed: true }));
      inFlightEvents.add(tracked);
      tracked.finally(() => inFlightEvents.delete(tracked));
      return tracked;
    };

    const requeueCount = Number.isInteger(claimed.requeueCount) ? claimed.requeueCount : 0;
    if (typeof deadLetterRegistry.reconcileRequeue === 'function') {
      if (deadLetterRegistry.reconcileRequeue(planId, requeueCount)) {
        console.log(JSON.stringify({ type: 'PLAN_REQUEUE_RESET', planId, requeueCount }));
      }
    }

    // Terminal isolation: check if this plan is already isolated as a poison-pill dead letter
    if (deadLetterRegistry.isDeadLetter(planId)) {
      const dl = deadLetterRegistry.getDeadLetter(planId);
      const safeDeadReason = redactSecrets(dl?.reason || 'POISON_PILL_PLAN');
      console.error(JSON.stringify({ type: 'PLAN_DEAD_LETTER_BLOCKED', planId, reason: safeDeadReason }));
      await reportOutcomeFn(planId, 'FAILED', `DEAD_LETTER_POISON_PILL: ${safeDeadReason}`, [{
        type: 'PLAN_ERROR',
        outcome: 'FAILED',
        reason: 'DEAD_LETTER_POISON_PILL',
        message: `Plan was previously isolated as dead-letter poison-pill: ${safeDeadReason}`,
        deadLetter: true,
      }]).catch(() => {});
      await passEventSink({
        type: 'PLAN_DEAD_LETTER_ISOLATED',
        planId,
        reason: safeDeadReason,
      });
      return { status: 'DEAD_LETTER', planId, outcome: 'FAILED', detail: dl.reason };
    }

    plan = planFromClaim(planId, claimed);
    if (plan.planType === 'GREENHOUSE') {
      const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
      if (apiRoot === pollUrl) throw new Error('INVALID_WORKER_POLL_URL');
      const backendBase = new URL(apiRoot);
      const packageUrl = new URL(`${backendBase.pathname.replace(/\/$/, '')}/plans/${encodeURIComponent(planId)}/package`, backendBase);
      const baseUrl = backendBase.origin;
      const response = await fetchWithRetry(packageUrl, { headers: authHeadersFn() });
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
    } else if (ashbyPackageNeedsMaterialization(plan)) {
      // Phase 14: same reasoning as ZOHO_RECRUIT above — AshbyExecutionPlanService
      // embeds the package directly in the claimed plan JSON (cv only, no
      // cover letter — see that service's own module comment), so only the
      // résumé bytes themselves need downloading via the same shared path.
      const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
      if (apiRoot === pollUrl) throw new Error('INVALID_WORKER_POLL_URL');
      await materializeArtifacts(plan, new URL(apiRoot).origin);
    } else if (workdayPackageNeedsMaterialization(plan)) {
      const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
      if (apiRoot === pollUrl) throw new Error('INVALID_WORKER_POLL_URL');
      await materializeArtifacts(plan, new URL(apiRoot).origin);
    } else if (universalPackageNeedsMaterialization(plan)) {
      const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
      if (apiRoot === pollUrl) throw new Error('INVALID_WORKER_POLL_URL');
      await materializeArtifacts(plan, new URL(apiRoot).origin);
    }

    const activeStepsReporter = stepsReporterFn ?? (pollUrl && /\/plans\/claim-next\/?$/.test(pollUrl)
      ? (pId, body) => reportPlanStep(pId, body, { pollUrl, authHeadersFn })
      : null);
    const activeQuestionsReporter = discoveredQuestionsReporterFn ?? (pollUrl && /\/plans\/claim-next\/?$/.test(pollUrl)
      ? (pId, body) => reportDiscoveredQuestions(pId, body, { pollUrl, authHeadersFn })
      : null);

    const runnerInstance = runner ?? new BrowserWorker({
      statePath: statePathVal,
      artifactDir: artifactDirVal,
      eventSink: passEventSink,
      verificationResolver: verificationResolverFn,
      identityResolver: identityResolverFn,
      credentialResolver: credentialResolverFn,
      stepsReporter: activeStepsReporter,
      discoveredQuestionsReporter: activeQuestionsReporter,
      sessionKey,
    });
    runtimeObj.runner = runnerInstance;
    let missedHeartbeats = 0;
    heartbeat = setInterval(() => {
      sendHeartbeatFn(planId)
        .then(() => { missedHeartbeats = 0; })
        .catch(() => {
          missedHeartbeats += 1;
          if (missedHeartbeats >= 3) {
            console.error(JSON.stringify({ type: 'WORKER_LEASE_LOST', planId, missedHeartbeats }));
            runnerInstance.requestAbort();
          }
        });
    }, 30_000);
    const runResult = await runnerInstance.execute(plan, { approve: false, headless: true });
    // Phase 4: a GREENHOUSE plan whose last step is REAL_SUBMIT_CLICK has
    // just attempted (or confirmed an already-completed) real submission —
    // its outcome depends on what the worker actually observed, never a
    // hardcoded assumption. Every other GREENHOUSE plan still ends at
    // validate-form exactly as before, always awaiting human review.
    const isSubmitAttempt = plan.planType === 'GREENHOUSE'
      && plan.steps.at(-1)?.type === 'REAL_SUBMIT_CLICK';
    // Phase 14: Ashby's own counterpart — see outcome.js's isAshbySubmitAttempt
    // for the same reasoning (only the last step's exact type decides this,
    // never an assumption about what a given run "should" have done).
    const isAshbySubmitAttempt = plan.planType === 'ASHBY'
      && plan.steps.at(-1)?.type === 'ASHBY_SUBMIT_CLICK';
    const isUniversalSubmitAttempt = plan.planType === 'UNIVERSAL'
      && plan.steps.at(-1)?.type === StepType.UNIVERSAL_SUBMIT_CLICK;
    const { outcome, detail, result } = computePlanOutcome(plan, runResult);
    if (plan.planType === 'UNIVERSAL' && !isUniversalSubmitAttempt) {
      const univ = runResult.universalResult ?? {};
      await Promise.allSettled([
        passEventSink({
          type: 'HUMAN_REVIEW_REQUIRED',
          planId,
          jobId: plan.correlation.jobId,
          applicationId: plan.correlation.applicationId,
          requiredGaps: univ.requiredGaps ?? [],
          filled: univ.filled ?? [],
          resumeUploadState: univ.resumeUploadState ?? 'NO_FIELD',
          coverLetterUploadState: univ.coverLetterUploadState ?? 'NO_FIELD',
        }),
      ]);
    } else if (plan.planType === 'GREENHOUSE' && !isSubmitAttempt) {
      await Promise.allSettled([
        passEventSink({ type: 'HUMAN_REVIEW_REQUIRED', planId, jobId: plan.correlation.jobId,
          applicationId: plan.correlation.applicationId, requiredGaps: plan.requiredGaps }),
      ]);
    } else if (plan.planType === 'ZOHO_RECRUIT') {
      const zoho = runResult.zohoRecruitResult ?? {};
      await Promise.allSettled([
        passEventSink({ type: 'HUMAN_REVIEW_REQUIRED', planId, jobId: plan.correlation.jobId,
          applicationId: plan.correlation.applicationId, requiredGaps: zoho.requiredGaps ?? [],
          filled: zoho.filled ?? [], fields: zoho.fields ?? [],
          resumeUploadState: zoho.resumeUploadState ?? 'NO_FIELD',
          coverLetterUploadState: zoho.coverLetterUploadState ?? 'NO_FIELD' }),
      ]);
    } else if (plan.planType === 'LEVER') {
      const lever = runResult.leverResult ?? {};
      await Promise.allSettled([
        passEventSink({ type: 'HUMAN_REVIEW_REQUIRED', planId, jobId: plan.correlation.jobId,
          applicationId: plan.correlation.applicationId, requiredGaps: lever.requiredGaps ?? [],
          filled: lever.filled ?? [], fields: lever.fields ?? [],
          resumeUploadState: lever.resumeUploadState ?? 'NO_FIELD' }),
      ]);
    } else if (plan.planType === 'WORKDAY') {
      const isWorkdaySubmitAttempt = plan.steps.at(-1)?.type === StepType.WORKDAY_SUBMIT_CLICK;
      if (!isWorkdaySubmitAttempt) {
        const workday = runResult.workdayResult ?? {};
        if (plan.steps.some((s) => s.type === StepType.WORKDAY_APPLY)) {
          await Promise.allSettled([
            passEventSink({
              type: 'HUMAN_REVIEW_REQUIRED',
              planId,
              jobId: plan.correlation.jobId,
              applicationId: plan.correlation.applicationId,
              requiredGaps: workday.requiredGaps ?? [],
              filledCategories: workday.filledCategories ?? [],
              step: workday.step ?? 'APPLICATION_STEP',
              cvUploadState: workday.cvUploadState ?? 'NO_FIELD',
              coverLetterUploadState: workday.coverLetterUploadState ?? 'NO_FIELD',
            }),
          ]);
        } else {
          await Promise.allSettled([
            passEventSink({
              type: 'HUMAN_REVIEW_REQUIRED',
              planId,
              jobId: plan.correlation.jobId,
              applicationId: plan.correlation.applicationId,
              loginStatus: plan.credentialSaved === true ? (workday.status ?? 'FORM_NOT_FOUND') : 'CREDENTIAL_REQUIRED',
            }),
          ]);
        }
      }
    } else if (plan.planType === 'ASHBY' && !isAshbySubmitAttempt) {
      // Phase 14: mirrors the ZOHO_RECRUIT/LEVER review-notification shape
      // exactly. A genuine submit attempt (isAshbySubmitAttempt) is
      // deliberately excluded — same as GREENHOUSE's own isSubmitAttempt
      // guard — since that outcome is either SUBMITTED or a distinct
      // "submission unconfirmed" state, never a form-preparation review.
      const ashby = runResult.ashbyResult ?? {};
      await Promise.allSettled([
        passEventSink({ type: 'HUMAN_REVIEW_REQUIRED', planId, jobId: plan.correlation.jobId,
          applicationId: plan.correlation.applicationId, requiredGaps: ashby.requiredGaps ?? [],
          filled: ashby.filled ?? [], fields: ashby.fields ?? [],
          resumeUploadState: ashby.resumeUploadState ?? 'NO_FIELD' }),
      ]);
    }
    try {
      await reportOutcomeFn(planId, outcome, detail, result);
    } catch (reportErr) {
      if (reportErr?.status === 422 || /BACKEND_REQUEST_FAILED:422\b/.test(String(reportErr?.message))) {
        deadLetterRegistry.clear(planId);
        const reconciliationReason =
          reportErr?.body?.reason || reportErr?.body?.error || detail || 'RECONCILIATION_REQUIRED';
        await Promise.allSettled([
          passEventSink({
            type: 'PLAN_ENDED',
            planId,
            outcome: 'RECONCILIATION_REQUIRED',
            code: reportErr?.body?.code ?? 'SUBMISSION_EVIDENCE_REQUIRED',
            reason: reconciliationReason,
          }),
        ]);
        console.warn(JSON.stringify({
          type: 'PLAN_RECONCILIATION_REQUIRED',
          planId,
          outcome: 'RECONCILIATION_REQUIRED',
          code: reportErr?.body?.code ?? 'SUBMISSION_EVIDENCE_REQUIRED',
        }));
        return {
          status: 'RECONCILIATION_REQUIRED',
          planId,
          outcome: 'RECONCILIATION_REQUIRED',
          detail: reconciliationReason,
          result,
        };
      }
      throw reportErr;
    }
    // Successful execution clears past crash tracking for this plan
    deadLetterRegistry.clear(planId);
    console.log(JSON.stringify({ type: 'PLAN_ENDED', planId, outcome }));
    return { status: 'COMPLETED', planId, outcome, detail, result };
  } catch (error) {
    if (runtimeObj.shuttingDown) {
      // A shutdown-triggered abort must NOT mark the plan FAILED: leave it
      // RUNNING so the backend's stale-plan sweeper reclaims and re-executes
      // it (completed steps are skipped; no plan contains a submit step).
      console.error(JSON.stringify({ type: 'PLAN_DEFERRED_ON_SHUTDOWN', planId }));
      return { status: 'DEFERRED_ON_SHUTDOWN', planId };
    } else if (isHandoffEligibleError(error) && pollUrl && planId && plan) {
      // Phase 3: Candidate handoff for security challenges (Option B: External Human Completion)
      const safeHandoffReason = redactSecrets(error.reason || error.message);
      console.log(JSON.stringify({ type: 'HANDOFF_INITIATING', planId, reason: safeHandoffReason }));
      const apiRoot = pollUrl.replace(/\/plans\/claim-next\/?$/, '');
      const backendBase = new URL(apiRoot).origin;
      try {
        const handoffResult = await initiateHandoffFn({
          plan,
          error,
          backendBase,
          authHeaders: authHeadersFn,
        });
        console.log(JSON.stringify({
          type: 'HANDOFF_TRANSFERRED_TO_HUMAN',
          planId,
          sessionId: handoffResult?.sessionId,
          reason: safeHandoffReason,
        }));
        // Under Option B (External Human Completion), the worker terminates cleanly
        // and releases its execution lease immediately. The candidate will complete
        // and submit the application directly on the employer site.
        return { status: 'HANDOFF', planId, sessionId: handoffResult?.sessionId };
      } catch (handoffErr) {
        const safeHandoffErr = redactSecrets(handoffErr?.message ?? handoffErr);
        const safeOriginalErr = redactSecrets(error?.message ?? error);
        console.error(JSON.stringify({ type: 'HANDOFF_ERROR', planId, error: safeHandoffErr }));
        const errorResult = [{
          type: 'PLAN_ERROR',
          outcome: 'BLOCKED_ANTI_BOT',
          reason: error.reason ? redactSecrets(error.reason) : null,
          message: safeOriginalErr,
        }];
        await reportOutcomeFn(planId, 'BLOCKED_ANTI_BOT', safeOriginalErr, errorResult).catch(() => {});
        return { status: 'HANDOFF_FAILED', planId, error: safeHandoffErr };
      }
    } else {
      let isDead = false;
      const safeErrorMsg = redactSecrets(error?.message ?? error);
      if (planId) {
        const crashCount = deadLetterRegistry.recordCrash(planId, error);
        if (deadLetterRegistry.isDeadLetter(planId) || crashCount >= maxCrashLimit) {
          isDead = true;
          deadLetterRegistry.markDeadLetter(planId, `Repeated crashes (${crashCount}/${maxCrashLimit}): ${safeErrorMsg}`);
        }
      }

      const outcome = error.name === 'HardStopError'
        // MFA_REQUIRED (Phase 7: Workday) is the same bucket as CAPTCHA/
        // anti-bot — a genuine stop condition requiring the human, never a
        // logic failure on Robin's part, and never auto-retried either way.
        ? (/CAPTCHA|ANTI_BOT|ACCESS_DENIED|MFA_REQUIRED/i.test(String(error.reason)) ? 'BLOCKED_ANTI_BOT' : 'FAILED')
        : 'FAILED';

      const reason = isDead ? 'DEAD_LETTER_POISON_PILL' : (error.name === 'HardStopError' && error.reason ? redactSecrets(error.reason) : null);
      const detail = isDead ? `DEAD_LETTER_POISON_PILL: ${safeErrorMsg}` : safeErrorMsg;

      console.error(JSON.stringify({
        type: isDead ? 'PLAN_DEAD_LETTER' : 'POLL_ERROR',
        planId,
        outcome,
        error: safeErrorMsg,
        crashes: planId ? deadLetterRegistry.getCrashCount(planId) : 1,
      }));

      // Persist WHY this plan stopped so the review panel can show the candidate
      // a real reason (missing info, CAPTCHA, a lost lease, …) instead of a bare
      // "failed" with no explanation — reuses the existing generic `result`
      // column and `transitionWithResult` path, the same one ZOHO_RECRUIT_OUTCOME
      // already relies on; no backend change was needed for this.
      const errorResult = [{
        type: 'PLAN_ERROR',
        outcome,
        reason,
        message: safeErrorMsg,
        deadLetter: isDead,
      }];

      if (planId) {
        await reportOutcomeFn(planId, outcome, detail, errorResult).catch(() => {});
        if (isDead) {
          await passEventSink({
            type: 'PLAN_DEAD_LETTER_ISOLATED',
            planId,
            crashes: deadLetterRegistry.getCrashCount(planId),
            error: safeErrorMsg,
          });
        }
      }

      return {
        status: isDead ? 'DEAD_LETTER' : 'ERROR',
        planId,
        outcome,
        error: safeErrorMsg,
        detail,
      };
    }
  } finally {
    if (heartbeat) clearInterval(heartbeat);
    runtimeObj.runner = null;
    if (inFlightEvents.size > 0) {
      await Promise.allSettled([...inFlightEvents]);
    }
  }
}

/**
 * Creates a poll handler with error recovery and logging to safely execute poll cycles.
 */
export function createPollHandler({
  pollUrl,
  apiCallFn = apiCall,
  reportOutcomeFn = reportOutcome,
  sendHeartbeatFn = sendHeartbeat,
  initiateHandoffFn = initiateHandoff,
  authHeadersFn = authHeaders,
  runtimeObj = runtime,
  statePathVal = statePath,
  artifactDirVal = artifactDir,
  eventSinkFn = eventSink,
  verificationResolverFn = verificationResolver,
  identityResolverFn = identityResolver,
  credentialResolverFn = credentialResolver,
  deadLetterRegistry = defaultDeadLetterRegistry,
  maxCrashLimit = MAX_PLAN_CRASHES,
} = {}) {
  let executing = false;
  return async function poll() {
    if (executing || runtimeObj.shuttingDown) return;
    executing = true;
    try {
      return await executePollCycle({
        pollUrl,
        apiCallFn,
        reportOutcomeFn,
        sendHeartbeatFn,
        initiateHandoffFn,
        authHeadersFn,
        runtimeObj,
        statePathVal,
        artifactDirVal,
        eventSinkFn,
        verificationResolverFn,
        identityResolverFn,
        credentialResolverFn,
        deadLetterRegistry,
        maxCrashLimit,
      });
    } catch (error) {
      const safePollErr = redactSecrets(error?.message ?? error);
      console.error(JSON.stringify({ type: 'POLL_LOOP_ERROR', error: safePollErr }));
      return { status: 'POLL_ERROR', error: safePollErr };
    } finally {
      executing = false;
    }
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
// Stage 3F: remove legacy plaintext session files and expired/unreadable vault entries left by a crash.
try {
  const swept = new SessionVault({ artifactDir, key: sessionKey }).sweep();
  if (swept) console.log(JSON.stringify({ type: 'WORKER_SESSIONS_SWEPT', count: swept }));
} catch (error) {
  console.error(JSON.stringify({ type: 'WORKER_SESSION_SWEEP_FAILED', error: redactSecrets(error?.message ?? error) }));
}
if (planFile) {
  const plan = JSON.parse(fs.readFileSync(path.resolve(planFile), 'utf8'));
  const worker = new BrowserWorker({ statePath, artifactDir, eventSink, verificationResolver, identityResolver, credentialResolver, sessionKey });
  runtime.runner = worker;
  worker.execute(plan, { approve, headless: true })
    .then((result) => process.stdout.write(`${JSON.stringify({ type: 'WORKER_RESULT', status: result.status })}\n`))
    .catch((error) => { process.stderr.write(`${JSON.stringify({ type: 'WORKER_ERROR', error: redactSecrets(error?.message ?? error) })}\n`); process.exitCode = 1; });
} else if (pollUrl) {
  console.log(JSON.stringify({ type: 'WORKER_READY', mode: 'POLLING', pollUrl: sanitizeUrl(pollUrl), pollIntervalMs }));
  const poll = createPollHandler({
    pollUrl,
    apiCallFn: apiCall,
    reportOutcomeFn: reportOutcome,
    sendHeartbeatFn: sendHeartbeat,
    initiateHandoffFn: initiateHandoff,
    authHeadersFn: authHeaders,
    runtimeObj: runtime,
    statePathVal: statePath,
    artifactDirVal: artifactDir,
    eventSinkFn: eventSink,
    verificationResolverFn: verificationResolver,
    identityResolverFn: identityResolver,
    credentialResolverFn: credentialResolver,
    deadLetterRegistry: defaultDeadLetterRegistry,
  });
  
  runtime.pollTimer = setInterval(poll, pollIntervalMs);
  poll();
} else {
  console.log(JSON.stringify({ type: 'WORKER_READY', mode: 'IDLE', statePath, artifactDir }));
  setInterval(() => {}, 60_000);
}
}
