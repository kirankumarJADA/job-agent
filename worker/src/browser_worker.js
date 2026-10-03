import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { chromium } from '@playwright/test';
import { StepType, Policy, assertSafeSelector, evaluatePolicy, validatePackage, planFingerprint, greenhousePageIdentityMatches } from './plan.js';
import { StateStore } from './store.js';
import { MockMailboxClient, VerificationError } from './mailbox.js';

export class HardStopError extends Error {
  constructor(reason, details = {}) { super(reason); this.name = 'HardStopError'; this.reason = reason; this.details = details; }
}

export class ApprovalRequiredError extends Error {
  constructor(stepId) { super(`REQUIRES_APPROVAL:${stepId}`); this.name = 'ApprovalRequiredError'; this.stepId = stepId; }
}

/** Raised when required fields/questions need human input before submission. */
export class HumanInputRequiredError extends Error {
  constructor(gaps) { super(`HUMAN_INPUT_REQUIRED:${gaps.length}`); this.name = 'HumanInputRequiredError'; this.gaps = gaps; }
}

export class BrowserWorker {
  constructor({ statePath, artifactDir, eventSink = () => {} }) {
    this.store = new StateStore(statePath);
    this.artifactDir = artifactDir;
    this.eventSink = eventSink;
    /** Set by requestAbort(): the run stops at the next step boundary. */
    this.abortRequested = false;
  }

  /**
   * Cooperative abort for lease loss: when the backend stops acknowledging
   * heartbeats, the orchestrator will reclaim this plan for re-execution.
   * Continuing to drive a live employer form without a lease risks two
   * workers on one application, so the run stops at the next step boundary.
   * Completed steps stay durable and are skipped on the re-execution.
   */
  requestAbort() {
    this.abortRequested = true;
  }

  async execute(plan, { approve = false, failAfterStep = null, headless = true } = {}) {
    // Inspection-only plans navigate and screenshot without touching files.
    // Package validation is specific to plans that upload or interact with
    // form data — skipping it here does not weaken the existing safety model
    // for full plans, which still pass through validatePackage unchanged.
    if (plan.planType === 'GREENHOUSE') {
      this.#validateGreenhousePlan(plan);
    } else if (plan.planType !== 'INSPECTION') {
      validatePackage(plan.package);
    }
    if (plan.planType === 'GREENHOUSE') this.#validateGreenhouseArtifacts(plan);
    plan.planFingerprint = planFingerprint(plan);
    const state = this.store.begin(plan);
    if (state.status === 'COMPLETED') return { status: 'COMPLETED', recovered: true, state };
    // Container-safe Chromium flags: Render/OCI instances expose a tiny
    // /dev/shm where the default shared-memory ring buffer crashes the
    // renderer, and the hardened images run without the user-namespace
    // sandbox. Both flags are required for reliable headless execution in
    // the production container (worker/Dockerfile).
    const browser = await chromium.launch({
      headless,
      args: ['--disable-dev-shm-usage', '--no-sandbox', '--disable-gpu'],
    });
    const sessionDir = path.join(this.artifactDir, 'sessions', plan.correlation.applicationId);
    fs.mkdirSync(sessionDir, { recursive: true });
    const context = await browser.newContext({ storageState: fs.existsSync(path.join(sessionDir, 'storage.json')) ? path.join(sessionDir, 'storage.json') : undefined });
    const page = await context.newPage();
    const values = { verification: state.verification, greenhouseExpected: new Map(state.greenhouseExpected ?? []) };
    // A paused or crashed worker may have completed the navigation step but
    // not yet completed the next step. Restore the last durable URL before
    // resuming so a fresh browser process never attempts a form action on
    // about:blank.
    if (state.status !== 'COMPLETED' && state.lastUrl) {
      await page.goto(state.lastUrl, { waitUntil: 'domcontentloaded' });
      if (plan.planType === 'GREENHOUSE') this.#assertGreenhouseIdentity(page, plan);
    }
    this.#emit(plan, { type: 'WORKER_STARTED', planId: plan.planId });

    try {
      for (const step of plan.steps) {
        if (this.abortRequested) {
          throw new HardStopError('WORKER_LEASE_LOST', { stepId: step.id });
        }
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
          if (plan.planType === 'GREENHOUSE' && step.type !== StepType.NAVIGATE) {
            this.#assertGreenhouseIdentity(page, plan);
          }
          await this.#runStep(page, plan, step, values);
          if (plan.planType === 'GREENHOUSE') this.#assertGreenhouseIdentity(page, plan);
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
        if (plan.planType === 'GREENHOUSE' && !greenhousePageIdentityMatches(p.url, page.url())) {
          throw new HardStopError('GREENHOUSE_PAGE_IDENTITY_MISMATCH', { stepId: step.id });
        }
        break;
      case StepType.FILL_FIELD:
        assertSafeSelector(p.selector);
        await this.#retry(() => page.locator(p.selector).fill(p.valueFrom ? values.verification?.[p.valueFrom.split('.')[1]] : p.value));
        if (plan.planType === 'GREENHOUSE') values.greenhouseExpected ??= new Map();
        if (plan.planType === 'GREENHOUSE') {
          values.greenhouseExpected.set(p.selector, p.value);
          this.store.greenhouseExpected(plan.planId, [...values.greenhouseExpected.entries()]);
        }
        break;
      case StepType.SELECT:
        assertSafeSelector(p.selector);
        if (plan.planType === 'GREENHOUSE' && !p.allowedOptions?.includes(p.value)) {
          throw new HardStopError('GREENHOUSE_OPTION_NOT_INSPECTED', { stepId: step.id });
        }
        await page.locator(p.selector).selectOption(p.value);
        break;
      case StepType.CHECK:
        assertSafeSelector(p.selector);
        if (p.checked) await page.locator(p.selector).check();
        break;
      case StepType.RADIO:
        assertSafeSelector(p.selector);
        if (plan.planType === 'GREENHOUSE') {
          if (!p.allowedOptions?.includes(p.value)) {
            throw new HardStopError('GREENHOUSE_OPTION_NOT_INSPECTED', { stepId: step.id });
          }
          const name = await page.locator(p.selector).getAttribute('name');
          if (!name || !/^[A-Za-z0-9_-]{1,80}$/.test(name)) {
            throw new HardStopError('GREENHOUSE_RADIO_GROUP_UNSAFE', { stepId: step.id });
          }
          const selector = `input[type=radio][name="${name}"][value="${p.value}"]`;
          assertSafeSelector(selector);
          const matching = page.locator(selector);
          if (await matching.count() !== 1) throw new HardStopError('GREENHOUSE_RADIO_OPTION_MISMATCH', { stepId: step.id });
          await matching.check({ force: false });
        } else {
          await page.locator(p.selector).check({ force: false });
        }
        break;
      case StepType.UPLOAD_FILE:
        assertSafeSelector(p.selector);
        if (plan.planType === 'GREENHOUSE' && (p.expectedJobId !== plan.correlation.jobId
            || p.expectedApplicationId !== plan.correlation.applicationId)) {
          throw new HardStopError('CROSS_JOB_FILE_CONTAMINATION', { stepId: step.id });
        }
        if (!fs.existsSync(p.path)) throw new Error(`FILE_NOT_FOUND:${p.path}`);
        const artifact = plan.planType === 'GREENHOUSE'
          ? (p.artifact === 'coverLetter' ? plan.package.coverLetter : p.artifact === 'cv' ? plan.package.cv : null)
          : (p.selector.includes('cover_letter') ? plan.package.coverLetter : plan.package.cv);
        if (!artifact?.versionId || p.expectedVersionId !== artifact.versionId) throw new HardStopError('CV_VERSION_LINK_MISMATCH', { stepId: step.id });
        if (plan.planType === 'GREENHOUSE' && artifact.sha256 !== p.sha256) {
          throw new HardStopError('PACKAGE_CHECKSUM_MISMATCH', { stepId: step.id });
        }
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
      case StepType.VALIDATE: {
        if (plan.planType === 'GREENHOUSE') {
          this.#assertGreenhouseIdentity(page, plan);
          await this.#assertSafePage(page);
          const bodyText = (await page.locator('body').innerText()).toLowerCase();
          if (/sign in|log in|session expired|authentication required/.test(bodyText)) {
            throw new HardStopError('GREENHOUSE_LOGIN_REQUIRED', { stepId: step.id });
          }
          if (await page.locator('input[type="password"]').count() > 0) {
            throw new HardStopError('GREENHOUSE_LOGIN_REQUIRED', { stepId: step.id });
          }
          const invalid = await page.locator(':invalid').count();
          if (invalid > 0) throw new HardStopError('GREENHOUSE_REQUIRED_FIELDS_INVALID', { stepId: step.id, invalidCount: invalid });
          for (const field of plan.fields) {
            if (!field.required) continue;
            const control = page.locator(field.selector);
            if (field.htmlType === 'file') {
              const files = await control.evaluate((element) => element.files?.length ?? 0).catch(() => 0);
              if (files === 0) throw new HardStopError('GREENHOUSE_REQUIRED_UPLOAD_MISSING', { stepId: step.id, key: field.key });
              continue;
            }
            const value = await control.inputValue().catch(() => '');
            if (!value.trim()) throw new HardStopError('GREENHOUSE_REQUIRED_FIELD_EMPTY', { stepId: step.id, key: field.key });
          }
          if (await page.locator('iframe[src*="captcha" i], [data-sitekey], .g-recaptcha, input[name*="captcha" i]').count() > 0) {
            throw new HardStopError('BLOCKED_ANTI_BOT', { stepId: step.id });
          }
          for (const [selector, expected] of values.greenhouseExpected ?? []) {
            const actual = await this.#retry(() => page.locator(selector).inputValue());
            if (actual !== expected) throw new HardStopError('GREENHOUSE_VALIDATION_FAILED', { stepId: step.id, selector });
          }
          for (const gap of plan.requiredGaps ?? []) {
            if (gap.classification === 'HARD_STOP') throw new HardStopError('GREENHOUSE_REQUIRED_HARD_STOP', { key: gap.key });
          }
          break;
        }
        if (p.expectedUrlIncludes && !page.url().includes(p.expectedUrlIncludes)) {
          throw new HardStopError(`PAGE_CHANGED:${p.expectedUrlIncludes}`, { stepId: step.id });
        }
        for (const check of p.checks ?? []) {
          assertSafeSelector(check.selector);
          const actual = await this.#retry(() => page.locator(check.selector).inputValue());
          if (actual !== check.expected) {
            throw new HardStopError(`VALIDATION_FAILED:${check.selector}`, { stepId: step.id, expected: check.expected, actual: this.#safeError({ message: actual }) });
          }
        }
        break;
      }
      default: throw new Error(`UNKNOWN_STEP_TYPE:${step.type}`);
    }
  }

  #validateGreenhousePlan(plan) {
    if (!plan.correlation?.applicationId || !plan.correlation?.jobId
        || !Array.isArray(plan.steps) || !Array.isArray(plan.fields)
        || !Array.isArray(plan.requiredGaps)) {
      throw new HardStopError('INVALID_GREENHOUSE_PLAN_CORRELATION');
    }
    const navigations = plan.steps.filter((step) => step.type === StepType.NAVIGATE);
    const targetUrl = plan.targetUrl ?? navigations[0]?.params?.url;
    if (!plan.package?.expectedUrl || plan.package.expectedUrl !== targetUrl
        || plan.package.applicationId !== plan.correlation.applicationId
        || plan.package.jobId !== plan.correlation.jobId
        || navigations.length !== 1 || plan.steps[0] !== navigations[0]
        || !greenhousePageIdentityMatches(targetUrl, navigations[0].params?.url)) {
      throw new HardStopError('INVALID_GREENHOUSE_TARGET');
    }
    const fieldKeys = new Set();
    for (const field of plan.fields) {
      if (!field?.key || fieldKeys.has(field.key) || typeof field.required !== 'boolean'
          || typeof field.selector !== 'string' || typeof field.classification !== 'string'
          || typeof field.htmlType !== 'string' || typeof field.value !== 'string'
          || !Array.isArray(field.options) || typeof field.label !== 'string'
          || typeof field.valueSource !== 'string' || typeof field.reason !== 'string') {
        throw new HardStopError('INVALID_GREENHOUSE_FIELD_METADATA');
      }
      fieldKeys.add(field.key);
      assertSafeSelector(field.selector);
    }
    const gapKeys = new Set();
    for (const gap of plan.requiredGaps) {
      if (!gap?.key || !fieldKeys.has(gap.key) || gapKeys.has(gap.key)
          || typeof gap.classification !== 'string' || typeof gap.reason !== 'string') {
        throw new HardStopError('INVALID_GREENHOUSE_REQUIRED_GAPS');
      }
      gapKeys.add(gap.key);
    }
    const allowedFillSelectors = new Set();
    const allowedQuestionSelectors = new Set();
    for (const field of plan.fields) {
      if (field.classification !== 'SUPPORTED_AUTO' || !field.value.trim()) continue;
      if (['first_name', 'last_name', 'email', 'phone', 'candidate-location', 'location'].includes(field.key)) {
        allowedFillSelectors.add(field.selector);
      }
      if (field.key.startsWith('question_') && field.valueSource === 'application_answers (human-confirmed)') {
        allowedQuestionSelectors.add(field.selector);
      }
    }
    if (!plan.steps.some((step) => step.type === StepType.VALIDATE)) throw new HardStopError('GREENHOUSE_VALIDATION_STEP_REQUIRED');
    if (plan.package.safetyContract !== plan.safetyContract
        || !Array.isArray(plan.package.fields) || plan.package.fields.length !== plan.fields.length) {
      throw new HardStopError('GREENHOUSE_PACKAGE_PLAN_MISMATCH');
    }
    for (const field of plan.fields) {
      const packaged = plan.package.fields.find((candidate) => candidate.key === field.key);
      if (!packaged || packaged.selector !== field.selector || packaged.value !== field.value
          || packaged.classification !== field.classification || packaged.htmlType !== field.htmlType) {
        throw new HardStopError('GREENHOUSE_PACKAGE_PLAN_MISMATCH');
      }
    }
    const ids = new Set();
    for (const step of plan.steps) {
      if (!step?.id || ids.has(step.id)) throw new HardStopError('INVALID_GREENHOUSE_STEP_ID');
      ids.add(step.id);
      if (![StepType.NAVIGATE, StepType.SCREENSHOT, StepType.FILL_FIELD, StepType.SELECT, StepType.RADIO, StepType.UPLOAD_FILE, StepType.VALIDATE].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_GREENHOUSE_STEP', { stepId: step.id });
      }
      if (step.type === StepType.VALIDATE) {
        if (step.id !== 'validate-form' || step !== plan.steps.at(-1)) throw new HardStopError('GREENHOUSE_VALIDATION_STEP_MUST_BE_LAST');
      }
      if ([StepType.SELECT, StepType.RADIO].includes(step.type)) {
        const params = step.params ?? {};
        const field = plan.fields.find((candidate) => candidate.key === step.id.slice(`${step.type.toLowerCase()}-`.length));
        const allowedType = step.type === StepType.SELECT ? 'select' : 'radio';
        if (!field || !field.key.startsWith('question_')
            || field.classification !== 'SUPPORTED_AUTO'
            || field.valueSource !== 'application_answers (human-confirmed)'
            || field.htmlType !== allowedType || field.selector !== params.selector
            || field.value !== params.value || !field.options.includes(params.value)
            || !Array.isArray(params.allowedOptions)
            || JSON.stringify([...params.allowedOptions].sort()) !== JSON.stringify([...field.options].sort())) {
          throw new HardStopError('GREENHOUSE_QUESTION_SELECTION_MISMATCH', { stepId: step.id });
        }
      }
      if (step.type === StepType.UPLOAD_FILE) {
        const params = step.params ?? {};
        const fieldKey = step.id.slice('upload-'.length);
        const field = plan.fields.find((candidate) => candidate.key === fieldKey);
        const artifact = params.artifact === 'cv' ? plan.package.cv
          : params.artifact === 'coverLetter' ? plan.package.coverLetter : null;
        if (!artifact || !field || field.htmlType !== 'file' || field.key !== fieldKey
            || field.classification !== 'SUPPORTED_AUTO' || field.selector !== params.selector
            || params.expectedJobId !== plan.correlation.jobId
            || params.expectedApplicationId !== plan.correlation.applicationId
            || params.expectedVersionId !== artifact.versionId || params.sha256 !== artifact.sha256) {
          throw new HardStopError('GREENHOUSE_UPLOAD_STEP_MISMATCH', { stepId: step.id });
        }
      }
      if (step.type === StepType.FILL_FIELD) {
        const params = step.params ?? {};

        assertSafeSelector(params.selector);
        if (typeof params.value !== 'string' || !params.value.trim()) {
          throw new HardStopError('INVALID_GREENHOUSE_FIELD_VALUE', { stepId: step.id });
        }
        const field = plan.fields.find((candidate) => candidate.key === step.id.slice('fill-'.length));
        const controlledKeys = new Set(['first_name', 'last_name', 'email', 'phone', 'candidate-location', 'location']);
        const safeContact = field && controlledKeys.has(field.key)
          && ['text', 'tel', 'email'].includes(field.htmlType) && allowedFillSelectors.has(params.selector);
        const confirmedQuestion = field && field.key.startsWith('question_')
          && allowedQuestionSelectors.has(params.selector)
          && ['text', 'email', 'tel', 'textarea'].includes(field.htmlType);
        if (!field || (!safeContact && !confirmedQuestion)
            || field.classification !== 'SUPPORTED_AUTO'
            || field.selector !== params.selector || field.value !== params.value
            || !field.valueSource || typeof field.reason !== 'string') {
          throw new HardStopError('GREENHOUSE_FIELD_NOT_VERIFIED', { stepId: step.id });
        }
      }
    }
  }

  #assertGreenhouseIdentity(page, plan) {
    if (!greenhousePageIdentityMatches(plan.targetUrl, page.url())) {
      throw new HardStopError('GREENHOUSE_PAGE_IDENTITY_MISMATCH');
    }
  }

  #validateGreenhouseArtifacts(plan) {
    const artifacts = plan.package?.artifacts ?? [];
    if (!Array.isArray(artifacts)) throw new HardStopError('INVALID_GREENHOUSE_ARTIFACT_LIST');
    const seen = new Set();
    for (const artifact of artifacts) {
      const expected = artifact.kind === 'cv' ? plan.package.cv : artifact.kind === 'coverLetter' ? plan.package.coverLetter : null;
      if (!expected || seen.has(artifact.kind) || expected.versionId !== artifact.versionId || expected.sha256 !== artifact.sha256
          || expected.jobId !== plan.correlation.jobId || expected.applicationId !== plan.correlation.applicationId
          || !expected.path || !fs.existsSync(expected.path)) {
        throw new HardStopError('GREENHOUSE_ARTIFACT_CORRELATION_MISMATCH');
      }
      const actual = crypto.createHash('sha256').update(fs.readFileSync(expected.path)).digest('hex');
      if (actual !== artifact.sha256) throw new HardStopError('GREENHOUSE_ARTIFACT_CHECKSUM_MISMATCH');
      seen.add(artifact.kind);
    }
    for (const step of plan.steps) {
      if (step.type !== StepType.UPLOAD_FILE) continue;
      const expected = step.params?.artifact === 'cv' ? plan.package.cv
        : step.params?.artifact === 'coverLetter' ? plan.package.coverLetter : null;
      const field = plan.fields.find((candidate) => candidate.key === step.id.slice('upload-'.length));
      if (!expected || !field || field.htmlType !== 'file' || field.selector !== step.params.selector
          || field.classification !== 'SUPPORTED_AUTO'
          || step.params.expectedVersionId !== expected.versionId || step.params.expectedJobId !== plan.correlation.jobId
          || step.params.sha256 !== expected.sha256 || step.params.expectedApplicationId !== plan.correlation.applicationId) {
        throw new HardStopError('GREENHOUSE_UPLOAD_STEP_MISMATCH', { stepId: step.id });
      }
    }
    for (const kind of ['cv', 'coverLetter']) {
      if (plan.package[kind] && !seen.has(kind)) throw new HardStopError('GREENHOUSE_ARTIFACT_NOT_MATERIALIZED', { kind });
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
