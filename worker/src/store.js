import fs from 'node:fs';
import path from 'node:path';

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
    if (existing?.status === 'COMPLETED') return existing;
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
    plan.steps[stepId] = { status, ...extra, at: Date.now() };
    if (extra.currentUrl) plan.lastUrl = extra.currentUrl;
    plan.heartbeatAt = Date.now();
    this.#save();
  }

  verification(planId, value) {
    const plan = this.state.plans[planId];
    plan.verification = value;
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
