import fs from 'node:fs';
import path from 'node:path';
import { sanitizeUrl } from './secret_redaction.js';

export class StateStore {
  constructor(filePath) {
    this.filePath = filePath;
    this.state = this.#load();
  }

  #load() {
    try { return JSON.parse(fs.readFileSync(this.filePath, 'utf8')); }
    catch { return { plans: {} }; }
  }

  #save() {
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    const tmp = `${this.filePath}.tmp-${process.pid}`;
    fs.writeFileSync(tmp, JSON.stringify(this.state, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, this.filePath);
  }

  begin(plan) {
    const existing = this.state.plans[plan.planId];
    // Phase 4: a completed plan is normally done for good — except the one
    // deliberate case where the backend appended a REAL_SUBMIT_CLICK step to
    // an already-approved plan and reused the same plan id (see
    // AutomationPlanRepository#prepareRealSubmission). That changes the
    // plan's own fingerprint, which is exactly the signal used here: a
    // completed plan whose fingerprint still matches stays done; one whose
    // fingerprint has changed resumes, with every already-completed step
    // preserved so the existing per-step skip logic carries straight to the
    // new step rather than re-running anything.
    if (existing?.status === 'COMPLETED' && existing.planFingerprint === plan.planFingerprint) return existing;
    const now = Date.now();
    const next = existing ?? {
      planId: plan.planId,
      planFingerprint: plan.planFingerprint,
      correlation: plan.correlation,
      steps: {},
      verification: null,
      events: [],
    };
    next.status = 'RUNNING';
    next.planFingerprint = plan.planFingerprint;
    next.heartbeatAt = now;
    next.startedAt ??= now;
    this.state.plans[plan.planId] = next;
    this.#save();
    return next;
  }

  recoverStale(maxAgeMs = 60_000) {
    const now = Date.now();
    const recovered = [];
    for (const plan of Object.values(this.state.plans)) {
      if (plan.status === 'RUNNING' && now - (plan.heartbeatAt ?? 0) > maxAgeMs) {
        plan.status = 'RECOVERABLE';
        plan.events.push({ type: 'WORKER_STALE_RECOVERED', at: now });
        recovered.push(plan.planId);
      }
    }
    if (recovered.length) this.#save();
    return recovered;
  }

  heartbeat(planId) {
    const plan = this.state.plans[planId];
    if (plan) { plan.heartbeatAt = Date.now(); this.#save(); }
  }

  step(planId, stepId) { return this.state.plans[planId]?.steps?.[stepId]; }

  markStep(planId, stepId, status, extra = {}) {
    const plan = this.state.plans[planId];
    // Defense in depth at the actual persistence boundary: every caller of
    // markStep already sanitizes a URL before passing it in (browser_worker.js),
    // but a URL is never written to disk unsanitized regardless of what a
    // future caller remembers to do — sanitizing an already-sanitized URL is
    // a no-op, so this costs nothing on the already-safe path.
    const safeExtra = extra.currentUrl ? { ...extra, currentUrl: sanitizeUrl(extra.currentUrl) } : extra;
    plan.steps[stepId] = { status, ...safeExtra, at: Date.now() };
    if (safeExtra.currentUrl) plan.lastUrl = safeExtra.currentUrl;
    plan.heartbeatAt = Date.now();
    this.#save();
  }

  verification(planId, value) {
    const plan = this.state.plans[planId];
    plan.verification = value;
    this.#save();
  }

  /** Phase 8.5: the employer account identity (address and mailbox kind) for this plan. */
  identity(planId, value) {
    const plan = this.state.plans[planId];
    plan.identity = value;
    this.#save();
  }

  greenhouseExpected(planId, entries) {
    const plan = this.state.plans[planId];
    plan.greenhouseExpected = entries;
    this.#save();
  }

  event(planId, event) {
    this.state.plans[planId].events.push({ ...event, at: Date.now() });
    this.#save();
  }

  complete(planId) {
    const plan = this.state.plans[planId];
    plan.status = 'COMPLETED';
    plan.completedAt = Date.now();
    this.#save();
  }

  pause(planId, reason) {
    const plan = this.state.plans[planId];
    plan.status = 'PAUSED';
    plan.pauseReason = reason;
    this.#save();
  }

  fail(planId, error) {
    const plan = this.state.plans[planId];
    plan.status = 'FAILED';
    plan.error = error;
    this.#save();
  }

  get(planId) { return this.state.plans[planId]; }
}
