import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { chromium } from '@playwright/test';
import { StepType, Policy, assertSafeSelector, evaluatePolicy, validatePackage, planFingerprint, greenhousePageIdentityMatches, zohoRecruitPageIdentityMatches } from './plan.js';
import { classifySubmissionAttempt, ConfirmationCategory } from './confirmation.js';
import { planZohoFormActions, ZohoFieldCategory, ZohoDocumentUploadState } from './zoho_recruit.js';
import { StateStore } from './store.js';
import { MockMailboxClient, VerificationError } from './mailbox.js';
import { sanitizeUrl, redactSecrets } from './secret_redaction.js';

// Robin x Tsenta continuation: shared between résumé and cover-letter
// uploads on a Zoho Recruit form — a deliberately cautious default, not an
// observed per-tenant limit (no real cap was ever sampled on either live
// tenant inspected).
const MAX_ZOHO_UPLOAD_BYTES = 10 * 1024 * 1024;

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
  constructor({ statePath, artifactDir, eventSink = () => {}, verificationResolver = null, identityResolver = null,
                credentialResolver = null, onPageCreated = null }) {
    this.store = new StateStore(statePath);
    this.artifactDir = artifactDir;
    this.eventSink = eventSink;
    this.verificationResolver = verificationResolver;
    /** Phase 8.5: asks the backend which address to register an employer account with. */
    this.identityResolver = identityResolver;
    /** Phase 8.5 continuation: asks the backend (EmployerCredentialService) for
     * this candidate's saved portal password for one employer domain. */
    this.credentialResolver = credentialResolver;
    /** Set by requestAbort(): the run stops at the next step boundary. */
    this.abortRequested = false;
    /** Phase 4: the last REAL_SUBMIT_CLICK step's {confirmed, evidence} result, if any. */
    this.lastSubmitResult = null;
    /** Robin x Tsenta continuation: the last ZOHO_RECRUIT_APPLY step's field-classification result, if any. */
    this.lastZohoRecruitResult = null;
    /**
     * Test seam only (never set in production: worker.js never passes it):
     * `async (page) => {}`, invoked once, right after the page is created and
     * before any navigation. The only way to register `page.route(...)`
     * interception before the GREENHOUSE identity check's first NAVIGATE —
     * `greenhousePageIdentityMatches` requires a real `boards.greenhouse.io`/
     * `job-boards.greenhouse.io` hostname, which a local mock HTTP server
     * cannot satisfy, so this is what lets a test drive the real
     * `#runRealSubmitClick` browser path (click, DOM-text confirmation,
     * pre-click re-inspection) without ever making a real network request or
     * touching a real employer site.
     */
    this.onPageCreated = onPageCreated;
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
    } else if (plan.planType === 'ZOHO_RECRUIT') {
      this.#validateZohoRecruitPlan(plan);
    } else if (plan.planType !== 'INSPECTION' && plan.package) {
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
    if (this.onPageCreated) await this.onPageCreated(page);
    const values = {
      verification: state.verification,
      // The registration identity survives a restart, so a resumed run logs in
      // with the address it signed up with (and never asks for a new one).
      identity: state.identity ?? null,
      greenhouseExpected: new Map(state.greenhouseExpected ?? []),
    };
    // A paused or crashed worker may have completed the navigation step but
    // not yet completed the next step. Restore the last durable URL before
    // resuming so a fresh browser process never attempts a form action on
    // about:blank.
    if (state.status !== 'COMPLETED' && state.lastUrl) {
      await page.goto(state.lastUrl, { waitUntil: 'domcontentloaded' });
      if (plan.planType === 'GREENHOUSE') this.#assertGreenhouseIdentity(page, plan);
      if (plan.planType === 'ZOHO_RECRUIT') this.#assertZohoRecruitIdentity(page, plan);
    }
    this.#emit(plan, { type: 'WORKER_STARTED', planId: plan.planId });

    try {
      for (const step of plan.steps) {
        if (this.abortRequested) {
          throw new HardStopError('WORKER_LEASE_LOST', { stepId: step.id });
        }
        this.store.heartbeat(plan.planId);
        const prior = this.store.step(plan.planId, step.id);
        const otpSubmitCompleted = this.store.step(plan.planId, 'account-verify-otp-submit')?.status === 'COMPLETED';
        const replayOtpFill = step.id === 'account-verify-otp-fill'
          && !otpSubmitCompleted && Boolean(state.verification?.otp);
        if (prior?.status === 'COMPLETED' && !replayOtpFill) continue;
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
          if (plan.planType === 'ZOHO_RECRUIT' && step.type !== StepType.NAVIGATE) {
            this.#assertZohoRecruitIdentity(page, plan);
          }
          await this.#runStep(page, plan, step, values);
          if (plan.planType === 'GREENHOUSE') this.#assertGreenhouseIdentity(page, plan);
          if (plan.planType === 'ZOHO_RECRUIT') this.#assertZohoRecruitIdentity(page, plan);
          this.store.markStep(plan.planId, step.id, 'COMPLETED', { currentUrl: sanitizeUrl(page.url()) });
          if (step.id === 'account-verify-otp-submit') {
            values.verification = null;
            this.store.verification(plan.planId, null);
          }
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
      return { status: 'COMPLETED', state: this.store.get(plan.planId), submitResult: this.lastSubmitResult ?? null,
        zohoRecruitResult: this.lastZohoRecruitResult ?? null };
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
        if (plan.planType === 'ZOHO_RECRUIT' && !zohoRecruitPageIdentityMatches(p.url, page.url())) {
          throw new HardStopError('ZOHO_RECRUIT_PAGE_IDENTITY_MISMATCH', { stepId: step.id });
        }
        break;
      case StepType.FILL_FIELD: {
        assertSafeSelector(p.selector);
        const value = p.valueFrom ? this.#valueFrom(values, p.valueFrom) : p.value;
        if (p.valueFrom && (value === null || value === undefined || value === '')) {
          if (p.skipWhenMissing) break;
          throw new ApprovalRequiredError(step.id);
        }
        await this.#retry(() => page.locator(p.selector).fill(String(value ?? '')));
        if (plan.planType === 'GREENHOUSE') values.greenhouseExpected ??= new Map();
        if (plan.planType === 'GREENHOUSE') {
          values.greenhouseExpected.set(p.selector, p.value);
          this.store.greenhouseExpected(plan.planId, [...values.greenhouseExpected.entries()]);
        }
        break;
      }
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
        if (p.onlyWhenVerificationOtp && !values.verification?.otp) break;
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
      case StepType.ACCOUNT_RESOLVE_IDENTITY: {
        // Phase 8.5: the account e-mail comes from the backend — by default the
        // candidate's Robin-managed address. Never guessed, never invented.
        const pauseForHuman = (reason) => {
          this.store.pause(plan.planId, reason);
          this.#emit(plan, { type: reason, stepId: step.id });
          throw new ApprovalRequiredError(step.id);
        };
        if (!this.identityResolver) pauseForHuman('REGISTRATION_IDENTITY_UNAVAILABLE');
        let identity;
        try {
          identity = await this.identityResolver({
            applicationId: plan.correlation.applicationId,
            employerDomain: p.employerDomain,
            sessionId: plan.correlation.sessionId ?? null,
          });
        } catch (error) {
          pauseForHuman(String(error?.message ?? '').endsWith(':409')
            ? 'MANAGED_MAILBOX_NOT_READY' : 'REGISTRATION_IDENTITY_UNAVAILABLE');
        }
        const email = String(identity?.email ?? '').trim().toLowerCase();
        if (!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/.test(email)) {
          pauseForHuman('REGISTRATION_IDENTITY_INVALID');
        }
        values.identity = { email, mailbox: identity.mailbox ?? null };
        this.store.identity(plan.planId, values.identity);
        // The address itself is never put in an event: it routes the candidate's mail.
        this.#emit(plan, { type: 'REGISTRATION_IDENTITY_RESOLVED', stepId: step.id, mailbox: values.identity.mailbox });
        break;
      }
      case StepType.ACCOUNT_RESOLVE_CREDENTIAL: {
        // Phase 8.5 continuation: the portal password comes from
        // EmployerCredentialService via the worker-authenticated resolve
        // endpoint. Never guessed, never generated here, never persisted to
        // this.store — unlike identity/verification (short-lived, low-harm
        // if it ever leaked to the on-disk state file), a portal password is
        // a long-lived reusable secret, so it lives only in this run's
        // in-memory `values` for the steps that need it.
        const pauseForHuman = (reason) => {
          this.store.pause(plan.planId, reason);
          this.#emit(plan, { type: reason, stepId: step.id });
          throw new ApprovalRequiredError(step.id);
        };
        if (!this.credentialResolver) pauseForHuman('EMPLOYER_CREDENTIAL_UNAVAILABLE');
        let credential;
        try {
          credential = await this.credentialResolver({
            applicationId: plan.correlation.applicationId,
            employerDomain: p.employerDomain,
          });
        } catch (error) {
          pauseForHuman(String(error?.message ?? '').endsWith(':404')
            ? 'EMPLOYER_CREDENTIAL_NOT_FOUND' : 'EMPLOYER_CREDENTIAL_UNAVAILABLE');
        }
        const password = String(credential?.password ?? '');
        if (!password) pauseForHuman('EMPLOYER_CREDENTIAL_INVALID');
        values.credential = { password, username: credential?.username ?? null };
        // Never put the password (or anything derived from it) in an event.
        this.#emit(plan, { type: 'EMPLOYER_CREDENTIAL_RESOLVED', stepId: step.id });
        break;
      }
      case StepType.ACCOUNT_VERIFY_OTP: {
        // Never guess. Missing, ambiguous or unavailable results pause safely.
        const pauseForHuman = (reason) => {
          this.store.pause(plan.planId, reason);
          this.#emit(plan, { type: reason, stepId: step.id });
          throw new ApprovalRequiredError(step.id);
        };
        if (!this.verificationResolver) pauseForHuman('VERIFICATION_PROVIDER_UNAVAILABLE');
        let resolution;
        try {
          resolution = await this.verificationResolver({
            applicationId: plan.correlation.applicationId,
            employerDomain: p.employerDomain,
            sessionId: plan.correlation.sessionId ?? null,
            mailboxAddress: values.identity?.email ?? plan.correlation.mailboxAddress ?? null,
          });
        } catch {
          pauseForHuman('VERIFICATION_PROVIDER_UNAVAILABLE');
        }
        if (!resolution || (!resolution.otp && !resolution.link)) pauseForHuman('VERIFICATION_NOT_FOUND');
        if (resolution.otp) {
          const otp = String(resolution.otp).trim();
          if (!/^\d{4,8}$/.test(otp)) pauseForHuman('VERIFICATION_CODE_INVALID');
          values.verification = { otp, receivedAt: resolution.receivedAt ?? null };
          this.store.verification(plan.planId, values.verification);
          break;
        }
        let link;
        try { link = new URL(resolution.link); } catch { pauseForHuman('VERIFICATION_LINK_REJECTED'); }
        const current = new URL(page.url());
        const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(current.hostname);
        const safeProtocol = link.protocol === 'https:'
          || (loopback && current.protocol === 'http:' && link.protocol === 'http:');
        if (!safeProtocol || link.origin !== current.origin || link.username || link.password) {
          pauseForHuman('VERIFICATION_LINK_REJECTED');
        }
        await this.#retry(() => page.goto(link.href, { waitUntil: 'domcontentloaded' }));
        await this.#assertSafePage(page);
        values.verification = null;
        this.store.verification(plan.planId, null);
        break;
      }
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
      case StepType.REAL_SUBMIT_CLICK:
        this.lastSubmitResult = await this.#runRealSubmitClick(page, plan, step);
        break;
      case StepType.ZOHO_RECRUIT_APPLY:
        this.lastZohoRecruitResult = await this.#runZohoRecruitApply(page, plan, step);
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

  /**
   * Phase 4: the one real, external form submission this codebase performs.
   * Never fabricates a result: classifySubmissionAttempt (confirmation.js)
   * decides the outcome from real page data, and `confirmed: true` is only
   * ever reachable through an explicit, matched, caller-configured
   * confirmation text — a disappeared form, a generic navigation, or "no
   * error happened" is never treated as proof, by design (see
   * confirmation.js's own module comment for why this means the capability
   * is currently self-gated: no plan configures that text yet).
   *
   * <p>Anti-bot and login-wall signals — both before and after the click —
   * are hard stops, exactly like the pre-existing VALIDATE step's equivalent
   * checks, not a retryable "uncertain" result: either appearing here is
   * anomalous for an already-validated form and should stop the run, not
   * quietly fall through.
   *
   * <p>Inspects the page <em>before</em> clicking, which is what makes a
   * crash-then-resume safe: if a prior attempt's click actually reached the
   * employer but the worker died before recording it, resuming must not
   * click a second time. Only when the pre-click inspection is not already
   * CONFIRMED (and not a hard-stop condition) does it click once and
   * re-inspect.
   */
  async #runRealSubmitClick(page, plan, step) {
    const p = step.params ?? {};
    const inspect = async () => {
      const bodyText = await page.locator('body').innerText().catch(() => '');
      const invalidFieldCount = await page.locator(':invalid').count().catch(() => 0);
      const formFieldsPresent = (await page.locator('#first_name, #email').count().catch(() => 0)) > 0;
      return classifySubmissionAttempt({
        bodyText, invalidFieldCount, formFieldsPresent,
        expectedConfirmationText: p.expectedConfirmationText,
      });
    };
    const guardAgainstHardStops = (result) => {
      if (result.category === ConfirmationCategory.ANTI_BOT) {
        throw new HardStopError('BLOCKED_ANTI_BOT', { stepId: step.id, evidence: result.evidence });
      }
      if (result.category === ConfirmationCategory.LOGIN_WALL) {
        throw new HardStopError('GREENHOUSE_LOGIN_REQUIRED_AFTER_SUBMIT', { stepId: step.id, evidence: result.evidence });
      }
      return result;
    };

    const before = guardAgainstHardStops(await inspect());
    if (before.confirmed) return before;
    assertSafeSelector(p.selector);
    await this.#retry(() => page.locator(p.selector).click());
    await page.waitForLoadState('domcontentloaded').catch(() => {});
    return guardAgainstHardStops(await inspect());
  }

  /**
   * Robin x Tsenta continuation: the one real action a ZOHO_RECRUIT plan
   * takes beyond navigating and screenshotting. Zoho Recruit's apply form is
   * rendered entirely client-side (see zoho_recruit.js's module comment for
   * the live evidence), so the form must first be revealed by clicking the
   * page's own "I'm interested" control (a stable Zoho Recruit platform
   * class, verified identical across two independent tenants — never a
   * guess from one sample), then its fields are read directly off the live
   * DOM and classified by their rendered label text, since the field `name`
   * attributes Zoho Recruit generates are per-tenant and not a stable
   * selector contract the way Greenhouse's are.
   *
   * <p>Conservative by design: only first name, last name and email are
   * ever filled automatically (zoho_recruit.js's AUTO_FILLABLE_CATEGORIES).
   * A CAPTCHA anywhere on the form is an immediate hard stop with nothing
   * filled — Robin never attempts to solve one. Everything else
   * (phone/mobile, city, country, a custom job-title dropdown, résumé
   * upload, consent checkboxes, and any other per-tenant question) is
   * reported as a required gap for a human to complete. This step never
   * clicks Submit, and no later step in a ZOHO_RECRUIT plan ever can
   * (enforced structurally by #validateZohoRecruitPlan, independent of this
   * method).
   *
   * <p>Fields are located by a `data-robin-zoho-index` attribute this method
   * writes itself during inspection, keyed by a trusted, worker-generated
   * array index — never by interpolating any page-supplied text into a CSS
   * selector, so there is no selector-injection surface despite the field
   * list being entirely dynamic. This is why these selectors do not go
   * through {@link assertSafeSelector}: that allowlist is for the finite set
   * of pre-known, statically reviewable selectors other plan types use; a
   * per-run generated index has no such finite enumeration to allowlist.
   */
  async #runZohoRecruitApply(page, plan, step) {
    const identity = step.params?.identity ?? {};
    const hasFormFields = async () => (await page.locator('input, select, textarea').count()) > 0;

    if (!(await hasFormFields())) {
      const trigger = page.locator('button.lyteBackgroundColorBtn.lyteSuccess').first();
      if (await trigger.count() === 0) {
        throw new Error('ZOHO_RECRUIT_APPLY_TRIGGER_NOT_FOUND');
      }
      await this.#retry(() => trigger.click());
      await page.waitForLoadState('domcontentloaded').catch(() => {});
      await page.locator('input, select, textarea').first()
        .waitFor({ state: 'visible', timeout: 3000 }).catch(() => {});
    }

    if (!(await hasFormFields())) {
      const bodyText = (await page.locator('body').innerText().catch(() => '')).toLowerCase();
      if (/sign in|log in|session expired|authentication required/.test(bodyText)) {
        throw new HardStopError('ZOHO_RECRUIT_LOGIN_REQUIRED', { stepId: step.id });
      }
      // Honest, genuine failure — never fabricated as success. A timeout or
      // an unrecognised post-click page both land here.
      throw new Error('ZOHO_RECRUIT_FORM_NOT_FOUND');
    }

    const inspected = await page.evaluate(() => {
      function findLabel(el) {
        let node = el;
        for (let up = 0; up < 6 && node; up++) {
          node = node.parentElement;
          if (!node) break;
          const candidate = Array.from(node.children).find(
            (c) => c !== el.parentElement && /label|title|field-label/i.test(c.className || ''));
          if (candidate && candidate.innerText && candidate.innerText.trim()) return candidate.innerText.trim();
        }
        return null;
      }
      const controls = Array.from(document.querySelectorAll('input, select, textarea'));
      return controls.map((el, index) => {
        el.setAttribute('data-robin-zoho-index', String(index));
        const rawLabel = findLabel(el);
        return {
          index,
          tag: el.tagName.toLowerCase(),
          type: el.type || null,
          placeholder: el.placeholder || null,
          rawLabel,
          required: Boolean(el.required) || /\*\s*$/.test(rawLabel || ''),
        };
      });
    });

    const actions = planZohoFormActions(inspected, identity);
    if (actions.captchaPresent) {
      throw new HardStopError('ZOHO_RECRUIT_CAPTCHA_PRESENT', {
        stepId: step.id,
        fields: actions.fields.map((f) => ({ label: f.rawLabel, category: f.category })),
      });
    }

    const filled = [];
    const extraGaps = [];
    for (const action of actions.toFill) {
      const locator = page.locator(`[data-robin-zoho-index="${action.index}"]`);
      await this.#retry(() => locator.fill(action.value));
      // A post-fill validity check, not just "did .fill() not throw" — the
      // live form's own constraints (a pattern, a type mismatch) can reject
      // a verbatim-transcribed value (e.g. a phone number in a format this
      // particular tenant's field does not accept). Never report a field
      // filled unless the form itself currently accepts the value.
      const invalid = await locator.evaluate(
        (el) => typeof el.checkValidity === 'function' && !el.checkValidity()).catch(() => false);
      if (invalid) {
        extraGaps.push({ label: inspected[action.index]?.rawLabel ?? null, category: action.category });
      } else {
        filled.push({ label: inspected[action.index]?.rawLabel ?? null, category: action.category });
      }
    }

    const resumeOutcome = await this.#tryUploadZohoArtifact(page, plan, actions.fields, {
      category: ZohoFieldCategory.RESUME_UPLOAD,
      packageKey: 'cv',
      missingReason: 'no reviewed résumé is available for this application',
      correlationMismatchReason: 'ZOHO_RECRUIT_RESUME_CORRELATION_MISMATCH',
      acceptPattern: /pdf/i,
      wrongTypeReason: 'this form does not appear to accept PDF résumés',
      maxBytes: MAX_ZOHO_UPLOAD_BYTES,
    });
    // ATTACHED is the actual, honest terminal state — the file was selected
    // into the browser's form, never "uploaded" or "submitted" (Robin never
    // clicks Submit here at all). Only ATTACHED/CONFIRMED count as filled.
    const resumeAttached = [ZohoDocumentUploadState.ATTACHED, ZohoDocumentUploadState.CONFIRMED]
      .includes(resumeOutcome.state);
    if (resumeAttached) {
      filled.push({ label: resumeOutcome.label, category: ZohoFieldCategory.RESUME_UPLOAD });
    }

    // Cover letter: verified absent on both sampled tenants (see
    // zoho_recruit.js's module comment) — this only ever does anything on a
    // tenant whose live form actually carries a field classified
    // COVER_LETTER_UPLOAD. `NO_FIELD` distinguishes "this form doesn't ask
    // for one" (informational, not a gap) from every other state (a real,
    // reported gap).
    const coverLetterOutcome = await this.#tryUploadZohoArtifact(page, plan, actions.fields, {
      category: ZohoFieldCategory.COVER_LETTER_UPLOAD,
      packageKey: 'coverLetter',
      missingReason: 'no approved cover letter is available for this application',
      correlationMismatchReason: 'ZOHO_RECRUIT_COVER_LETTER_CORRELATION_MISMATCH',
      acceptPattern: /plain|text|txt|pdf|doc/i,
      wrongTypeReason: 'this form does not appear to accept the cover letter file type',
      maxBytes: MAX_ZOHO_UPLOAD_BYTES,
    });
    const coverLetterAttached = [ZohoDocumentUploadState.ATTACHED, ZohoDocumentUploadState.CONFIRMED]
      .includes(coverLetterOutcome.state);
    if (coverLetterAttached) {
      filled.push({ label: coverLetterOutcome.label, category: ZohoFieldCategory.COVER_LETTER_UPLOAD });
    }

    const requiredGaps = [...actions.requiredGaps, ...extraGaps]
      .filter((gap) => !(resumeAttached && gap.category === ZohoFieldCategory.RESUME_UPLOAD))
      .filter((gap) => !(coverLetterAttached && gap.category === ZohoFieldCategory.COVER_LETTER_UPLOAD));

    return {
      formFound: true,
      captchaPresent: false,
      filled,
      resumeUploadState: resumeOutcome.state,
      resumeUploadReason: resumeOutcome.reason,
      coverLetterUploadState: coverLetterOutcome.state,
      coverLetterUploadReason: coverLetterOutcome.reason,
      requiredGaps: requiredGaps.map((gap) => ({ label: gap.label, category: gap.category })),
      fields: actions.fields.map((f) => ({ label: f.rawLabel, category: f.category, required: f.required })),
    };
  }

  /**
   * Robin x Tsenta continuation: attempts one binary upload action
   * #runZohoRecruitApply performs beyond text fills — résumé or cover
   * letter, the only two difference being which classified field category
   * and which `plan.package` artifact to use. Mirrors the correlation and
   * integrity guarantees Greenhouse's UPLOAD_FILE step already enforces
   * (exact job/application/version binding, a checksum re-verified against
   * the bytes on disk) plus two checks specific to this adapter, since
   * Zoho Recruit's upload fields are discovered live rather than known ahead
   * of time: the field's own `accept` list must plausibly admit the
   * artifact's type, and the file must stay under a conservative size cap —
   * Robin has no verified per-tenant upload limit for either document, so
   * this cap is a deliberately cautious default, not evidence derived from
   * any sampled form. A cross-application/job artifact mismatch is a hard
   * stop, the same severity Greenhouse treats it with; every other reason to
   * skip the upload (no field present at all, no artifact available, wrong
   * accepted type, oversized, checksum mismatch, file missing) is reported
   * back as an honest, recoverable {@link ZohoDocumentUploadState} — never
   * silently treated as done.
   *
   * <p><b>`ATTACHED`, not `CONFIRMED`.</b> A successful {@code setInputFiles}
   * call is the ENTIRE signal this method has: it proves the reviewed bytes
   * now sit in the browser's own file input, nothing more. It is not, and
   * must never be read as, proof the employer's server received or stored
   * the file — this adapter never clicks Submit at all (enforced structurally
   * by {@link #validateZohoRecruitPlan}), so there is no later point in the
   * run where real server-side acceptance could even be observed. `CONFIRMED`
   * exists in the enum for a future, separately verified signal; this method
   * never produces it.
   *
   * @returns {{state: string, reason: string|null, label: string|null}} `state`
   *   is one of {@link ZohoDocumentUploadState}.
   */
  async #tryUploadZohoArtifact(page, plan, classifiedFields, config) {
    const field = classifiedFields.find((f) => f.category === config.category);
    if (!field) return { state: ZohoDocumentUploadState.NO_FIELD, reason: null, label: null };
    const label = field.rawLabel ?? null;
    const artifact = plan.package?.[config.packageKey];
    if (!artifact?.path || !artifact?.versionId || !artifact?.sha256) {
      return { state: ZohoDocumentUploadState.NO_ARTIFACT, reason: config.missingReason, label };
    }
    if (artifact.jobId !== plan.correlation.jobId || artifact.applicationId !== plan.correlation.applicationId) {
      throw new HardStopError(config.correlationMismatchReason, { jobId: artifact.jobId, applicationId: artifact.applicationId });
    }
    if (!fs.existsSync(artifact.path)) {
      return { state: ZohoDocumentUploadState.REJECTED, reason: 'the file was not available to upload', label };
    }
    const locator = page.locator(`[data-robin-zoho-index="${field.index}"]`);
    const accept = await locator.getAttribute('accept').catch(() => null);
    const acceptsType = !accept || config.acceptPattern.test(accept);
    if (!acceptsType) {
      return { state: ZohoDocumentUploadState.REJECTED, reason: config.wrongTypeReason, label };
    }
    const bytes = fs.readFileSync(artifact.path);
    if (bytes.length > config.maxBytes) {
      return { state: ZohoDocumentUploadState.REJECTED, reason: 'the file exceeds a safe upload size', label };
    }
    const actualSha256 = crypto.createHash('sha256').update(bytes).digest('hex');
    if (actualSha256 !== artifact.sha256) {
      return { state: ZohoDocumentUploadState.REJECTED, reason: 'the file no longer matches its reviewed checksum', label };
    }
    await locator.setInputFiles(artifact.path);
    return { state: ZohoDocumentUploadState.ATTACHED, reason: null, label };
  }

  /** Values a step may take from earlier steps: `verification.otp`, `identity.email`. */
  #valueFrom(values, path) {
    const [source, key] = String(path).split('.');
    if (source === 'verification') return values.verification?.[key];
    if (source === 'identity') return values.identity?.[key];
    if (source === 'credential') return values.credential?.[key];
    throw new Error(`UNKNOWN_VALUE_SOURCE:${source}`);
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
    // Phase 4: a REAL_SUBMIT_CLICK step is allowed in a GREENHOUSE plan under
    // exactly one shape — it is the plan's last step, its id is literally
    // 'real-submit-click', and the step immediately before it is the
    // validate-form step. Anything else (present elsewhere, duplicated,
    // wrongly named, or validate-form not immediately preceding it) is
    // rejected below, the same way every other Greenhouse step shape is.
    const submitIndex = plan.steps.findIndex((step) => step.type === StepType.REAL_SUBMIT_CLICK);
    const hasSubmitStep = submitIndex !== -1;
    if (hasSubmitStep && (submitIndex !== plan.steps.length - 1
        || plan.steps[submitIndex]?.id !== 'real-submit-click'
        || plan.steps[submitIndex - 1]?.type !== StepType.VALIDATE
        || plan.steps[submitIndex - 1]?.id !== 'validate-form')) {
      throw new HardStopError('GREENHOUSE_SUBMIT_STEP_MISPLACED');
    }
    const ids = new Set();
    for (const step of plan.steps) {
      if (!step?.id || ids.has(step.id)) throw new HardStopError('INVALID_GREENHOUSE_STEP_ID');
      ids.add(step.id);
      if (![StepType.NAVIGATE, StepType.SCREENSHOT, StepType.FILL_FIELD, StepType.SELECT, StepType.RADIO,
            StepType.UPLOAD_FILE, StepType.VALIDATE, StepType.REAL_SUBMIT_CLICK].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_GREENHOUSE_STEP', { stepId: step.id });
      }
      if (step.type === StepType.VALIDATE) {
        const isLast = step === plan.steps.at(-1);
        const isBeforeSubmit = hasSubmitStep && plan.steps.indexOf(step) === submitIndex - 1;
        if (step.id !== 'validate-form' || (!isLast && !isBeforeSubmit)) {
          throw new HardStopError('GREENHOUSE_VALIDATION_STEP_MUST_BE_LAST');
        }
      }
      if (step.type === StepType.REAL_SUBMIT_CLICK) {
        const params = step.params ?? {};
        if (params.selector !== 'button[type="submit"]') {
          throw new HardStopError('GREENHOUSE_SUBMIT_SELECTOR_NOT_ALLOWED', { stepId: step.id });
        }
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

  /**
   * Robin x Tsenta continuation: the ZOHO_RECRUIT counterpart of
   * {@link #validateGreenhousePlan}, intentionally much lighter — there is
   * no pre-computed field/value list to cross-check (identification happens
   * live, inside #runZohoRecruitApply itself) — but it still enforces the
   * one property that matters most: this plan type can NEVER carry a
   * REAL_SUBMIT_CLICK or MOCK_SUBMIT step, under any circumstance. This is
   * deliberately redundant with the backend's own refusal
   * (AutomationPlanRepository#prepareRealSubmission already accepts only
   * GREENHOUSE plans) — two independent layers, so a future bug in either
   * one alone still cannot make Zoho Recruit submit for real.
   */
  #validateZohoRecruitPlan(plan) {
    if (!plan.correlation?.applicationId || !plan.correlation?.jobId || !Array.isArray(plan.steps)) {
      throw new HardStopError('INVALID_ZOHO_RECRUIT_PLAN_CORRELATION');
    }
    const navigations = plan.steps.filter((step) => step.type === StepType.NAVIGATE);
    const targetUrl = plan.targetUrl ?? navigations[0]?.params?.url;
    if (navigations.length !== 1 || plan.steps[0] !== navigations[0]
        || !zohoRecruitPageIdentityMatches(targetUrl, navigations[0].params?.url)) {
      throw new HardStopError('INVALID_ZOHO_RECRUIT_TARGET');
    }
    const ids = new Set();
    for (const step of plan.steps) {
      if (!step?.id || ids.has(step.id)) throw new HardStopError('INVALID_ZOHO_RECRUIT_STEP_ID');
      ids.add(step.id);
      if ([StepType.REAL_SUBMIT_CLICK, StepType.MOCK_SUBMIT].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_ZOHO_RECRUIT_SUBMIT_STEP', { stepId: step.id });
      }
      if (![StepType.NAVIGATE, StepType.SCREENSHOT, StepType.ZOHO_RECRUIT_APPLY].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_ZOHO_RECRUIT_STEP', { stepId: step.id });
      }
    }
    const applySteps = plan.steps.filter((step) => step.type === StepType.ZOHO_RECRUIT_APPLY);
    if (applySteps.length > 1) throw new HardStopError('ZOHO_RECRUIT_APPLY_STEP_DUPLICATED');
    if (applySteps.length === 1 && plan.steps.at(-1).type !== StepType.ZOHO_RECRUIT_APPLY) {
      throw new HardStopError('ZOHO_RECRUIT_APPLY_STEP_MUST_BE_LAST');
    }
  }

  #assertZohoRecruitIdentity(page, plan) {
    if (!zohoRecruitPageIdentityMatches(plan.targetUrl, page.url())) {
      throw new HardStopError('ZOHO_RECRUIT_PAGE_IDENTITY_MISMATCH');
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
    return error instanceof VerificationError ? error.message : redactSecrets(error?.message ?? error);
  }

  #emit(plan, event) {
    const safe = { ...event, jobId: plan.correlation.jobId, applicationId: plan.correlation.applicationId };
    this.store.event(plan.planId, safe);
    this.eventSink(safe);
  }
}
