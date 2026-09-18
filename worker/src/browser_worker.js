import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { chromium } from '@playwright/test';
import { StepType, Policy, assertSafeSelector, evaluatePolicy, validatePackage, planFingerprint } from './plan.js';
import { StateStore } from './store.js';
import { MockMailboxClient, VerificationError } from './mailbox.js';

export class HardStopError extends Error {
  constructor(reason, details = {}) { super(reason); this.name = 'HardStopError'; this.reason = reason; this.details = details; }
}

export class ApprovalRequiredError extends Error {
  constructor(stepId) { super(`REQUIRES_APPROVAL:${stepId}`); this.name = 'ApprovalRequiredError'; this.stepId = stepId; }
}

export class BrowserWorker {
  constructor({ statePath, artifactDir, eventSink = () => {} }) {
    this.store = new StateStore(statePath);
    this.artifactDir = artifactDir;
    this.eventSink = eventSink;
  }

  async execute(plan, { approve = false, failAfterStep = null, headless = true } = {}) {
    validatePackage(plan.package);
    plan.planFingerprint = planFingerprint(plan);
    const state = this.store.begin(plan);
    if (state.status === 'COMPLETED') return { status: 'COMPLETED', recovered: true, state };
    const browser = await chromium.launch({ headless });
    const sessionDir = path.join(this.artifactDir, 'sessions', plan.correlation.applicationId);
    fs.mkdirSync(sessionDir, { recursive: true });
    const context = await browser.newContext({ storageState: fs.existsSync(path.join(sessionDir, 'storage.json')) ? path.join(sessionDir, 'storage.json') : undefined });
    const page = await context.newPage();
    const values = { verification: state.verification };
    // A paused or crashed worker may have completed the navigation step but
    // not yet completed the next step. Restore the last durable URL before
    // resuming so a fresh browser process never attempts a form action on
    // about:blank.
    if (state.status !== 'COMPLETED' && state.lastUrl) {
      await page.goto(state.lastUrl, { waitUntil: 'domcontentloaded' });
    }
    this.#emit(plan, { type: 'WORKER_STARTED', planId: plan.planId });

    try {
      for (const step of plan.steps) {
        this.store.heartbeat(plan.planId);
        const prior = this.store.step(plan.planId, step.id);
        if (prior?.status === 'COMPLETED') continue;
        const policy = evaluatePolicy(step);
        if (policy.action === 'BLOCK') throw new HardStopError(policy.reason, { stepId: step.id });
        if (policy.action === 'PAUSE' && !approve) {
          this.store.pause(plan.planId, policy.reason);
          this.#emit(plan, { type: 'APPROVAL_REQUIRED', stepId: step.id });
          throw new ApprovalRequiredError(step.id);
        }
        try {
          await this.#runStep(page, plan, step, values);
          this.store.markStep(plan.planId, step.id, 'COMPLETED', { currentUrl: page.url() });
          this.#emit(plan, { type: 'STEP_COMPLETED', stepId: step.id, stepType: step.type });
          const businessEvent = this.#businessEvent(step.id);
          if (businessEvent) this.#emit(plan, { type: businessEvent });
          await context.storageState({ path: path.join(sessionDir, 'storage.json') });
          if (failAfterStep === step.id) {
            // Simulate a process crash after durable commit. Do not convert
            // the already-completed step into FAILED; restart must skip it.
            throw new Error(`SIMULATED_WORKER_CRASH_AFTER:${step.id}`);
          }
        } catch (error) {
          if (error instanceof ApprovalRequiredError || error instanceof HardStopError || String(error?.message).startsWith('SIMULATED_WORKER_CRASH')) throw error;
          const screenshot = await this.#failureScreenshot(page, plan, step.id);
          this.store.markStep(plan.planId, step.id, 'FAILED', { error: this.#safeError(error), screenshot });
          if (this.#isHardStop(error, page)) {
            const hard = new HardStopError('BLOCKED_ANTI_BOT', { stepId: step.id, screenshot });
            this.store.fail(plan.planId, hard.reason);
            this.#emit(plan, { type: 'HARD_STOP', reason: hard.reason, stepId: step.id, screenshot });
            throw hard;
          }
          this.store.fail(plan.planId, this.#safeError(error));
          this.#emit(plan, { type: 'WORKER_FAILED', stepId: step.id, error: this.#safeError(error), screenshot });
          throw error;
        }
      }
      this.store.complete(plan.planId);
      this.#emit(plan, { type: 'WORKER_COMPLETED', planId: plan.planId });
      return { status: 'COMPLETED', state: this.store.get(plan.planId) };
    } catch (error) {
      if (!(error instanceof ApprovalRequiredError) && !(error instanceof HardStopError) && !String(error.message).startsWith('SIMULATED_WORKER_CRASH')) {
        this.store.fail(plan.planId, this.#safeError(error));
      }
      throw error;
    } finally {
      await context.close();
      await browser.close();
    }
  }

  async #runStep(page, plan, step, values) {
    const p = step.params;
    switch (step.type) {
      case StepType.NAVIGATE:
        await this.#retry(() => page.goto(p.url, { waitUntil: 'domcontentloaded' }));
        await this.#assertSafePage(page);
        break;
      case StepType.FILL_FIELD:
        assertSafeSelector(p.selector);
        await this.#retry(() => page.locator(p.selector).fill(p.valueFrom ? values.verification?.[p.valueFrom.split('.')[1]] : p.value));
        break;
      case StepType.SELECT:
        assertSafeSelector(p.selector);
        await page.locator(p.selector).selectOption(p.value);
        break;
      case StepType.CHECK:
        assertSafeSelector(p.selector);
        if (p.checked) await page.locator(p.selector).check();
        break;
      case StepType.RADIO:
        assertSafeSelector(p.selector);
        await page.locator(p.selector).check({ force: false });
        break;
      case StepType.UPLOAD_FILE:
        assertSafeSelector(p.selector);
        if (p.expectedJobId !== plan.correlation.jobId) throw new HardStopError('CROSS_JOB_FILE_CONTAMINATION', { stepId: step.id });
        if (!fs.existsSync(p.path)) throw new Error(`FILE_NOT_FOUND:${p.path}`);
        const artifact = p.selector.includes('cover_letter') ? plan.package.coverLetter : plan.package.cv;
        if (!artifact.versionId || p.expectedVersionId !== artifact.versionId) throw new HardStopError('CV_VERSION_LINK_MISMATCH', { stepId: step.id });
        if (artifact.sha256) {
          const actual = crypto.createHash('sha256').update(fs.readFileSync(p.path)).digest('hex');
          if (actual !== artifact.sha256) throw new HardStopError('PACKAGE_CHECKSUM_MISMATCH', { stepId: step.id });
        }
        await page.locator(p.selector).setInputFiles(p.path);
        break;
      case StepType.CLICK:
        assertSafeSelector(p.selector);
        await this.#retry(() => page.locator(p.selector).click());
        await page.waitForLoadState('domcontentloaded').catch(() => {});
        await this.#assertSafePage(page);
        if (p.expectedText && !(await page.locator('body').innerText()).includes(p.expectedText)) {
          throw new Error(`TERMINAL_STATE_NOT_REACHED:${p.expectedText}`);
        }
        break;
      case StepType.WAIT_FOR:
        assertSafeSelector(p.selector);
        await page.locator(p.selector).waitFor({ state: 'visible', timeout: 5000 });
        break;
      case StepType.MAILBOX_VERIFY: {
        const mailbox = new MockMailboxClient(p.mailboxUrl);
        const verification = await mailbox.getVerification({ applicationId: p.applicationId, senderDomain: p.senderDomain, receivedAfter: Date.now() - 120000 });
        values.verification = verification;
        this.store.verification(plan.planId, verification);
        this.#emit(plan, { type: 'VERIFICATION_RECEIVED', messageId: verification.messageId });
        break;
      }
      case StepType.POLICY_CHECK:
        if (p.action === 'REAL_SUBMIT') throw new HardStopError('FORBIDDEN_REAL_SUBMISSION');
        break;
      case StepType.MOCK_SUBMIT:
        await page.locator(p.selector).click();
        await page.waitForLoadState('domcontentloaded').catch(() => {});
        await this.#assertSafePage(page);
        if (p.expectedText && !(await page.locator('body').innerText()).includes(p.expectedText)) {
          throw new Error(`TERMINAL_STATE_NOT_REACHED:${p.expectedText}`);
        }
        break;
      case StepType.SCREENSHOT:
        await page.screenshot({ path: path.join(this.artifactDir, `${plan.planId}-${step.id}.png`), fullPage: true });
        break;
      default: throw new Error(`UNKNOWN_STEP_TYPE:${step.type}`);
    }
  }

  async #retry(fn, attempts = 3) {
    let last;
    for (let i = 0; i < attempts; i++) {
      try { return await fn(); } catch (error) { last = error; await new Promise((resolve) => setTimeout(resolve, 100 * (i + 1))); }
    }
    throw last;
  }

  async #assertSafePage(page) {
    const text = (await page.locator('body').innerText().catch(() => '')).toLowerCase();
    if (/captcha|cloudflare|are you human|anti[- ]bot|access denied/.test(text)) {
      throw new HardStopError('BLOCKED_ANTI_BOT');
    }
  }

  #businessEvent(stepId) {
    return {
      'signup-submit': 'SIGNUP_COMPLETED',
      'verification-email': 'VERIFICATION_EMAIL_RECEIVED',
      'verification-submit': 'VERIFICATION_COMPLETED',
      'login-submit': 'LOGIN_COMPLETED',
      'application-step-one': 'APPLICATION_PREPARED',
      'application-submit': 'APPLICATION_SUBMITTED',
    }[stepId] ?? null;
  }

  #isHardStop(error, page) {
    return error instanceof HardStopError || /captcha|cloudflare|anti[- ]bot|access denied/i.test(String(error.message));
  }

  async #failureScreenshot(page, plan, stepId) {
    const dir = path.join(this.artifactDir, 'failures');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${plan.planId}-${stepId}.png`);
    await page.screenshot({ path: file, fullPage: true }).catch(() => {});
    return file;
  }

  #safeError(error) {
    return error instanceof VerificationError ? error.message : String(error?.message ?? error).replace(/(password|otp|token|cookie|secret)=?[^\s,;]*/gi, '$1=[REDACTED]');
  }

  #emit(plan, event) {
    const safe = { ...event, jobId: plan.correlation.jobId, applicationId: plan.correlation.applicationId };
    this.store.event(plan.planId, safe);
    this.eventSink(safe);
  }
}
