import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { chromium } from '@playwright/test';
import { StepType, Policy, assertSafeSelector, evaluatePolicy, validatePackage, planFingerprint, greenhousePageIdentityMatches, greenhouseConfirmationPageMatches, zohoRecruitPageIdentityMatches, leverPageIdentityMatches, workdayPageIdentityMatches, ashbyPageIdentityMatches } from './plan.js';
import { classifySubmissionAttempt, ConfirmationCategory } from './confirmation.js';
import { planZohoFormActions, ZohoFieldCategory, ZohoDocumentUploadState } from './zoho_recruit.js';
import { planLeverFormActions, LeverFieldAction } from './lever.js';
import { planAshbyFormActions, AshbyFieldCategory, AshbyDocumentUploadState, classifyAshbySubmission, AshbySubmissionCategory } from './ashby.js';
import { classifyWorkdayField, planWorkdayFormActions, detectWorkdayStep, classifyWorkdaySubmission, isWorkdayHoneypot, WorkdayFieldCategory, WorkdayDocumentUploadState, WorkdaySubmissionCategory } from './workday.js';

const MAX_WORKDAY_UPLOAD_BYTES = 10 * 1024 * 1024;
import { StateStore } from './store.js';
import { MockMailboxClient, VerificationError } from './mailbox.js';
import { sanitizeUrl, redactSecrets } from './secret_redaction.js';

// Robin x Tsenta continuation: shared between résumé and cover-letter
// uploads on a Zoho Recruit form — a deliberately cautious default, not an
// observed per-tenant limit (no real cap was ever sampled on either live
// tenant inspected).
const MAX_ZOHO_UPLOAD_BYTES = 10 * 1024 * 1024;

// Phase 14: same deliberately cautious default for Ashby's résumé upload —
// no real per-posting cap was ever sampled on the one live posting inspected.
const MAX_ASHBY_UPLOAD_BYTES = 10 * 1024 * 1024;

// Phase 1 final fix (duplicate-submission prevention): a REAL_SUBMIT_CLICK
// step's durable status while its one real external click is in flight —
// written BEFORE the click (see #runRealSubmitClick) and overwritten by
// 'COMPLETED' only once the outcome itself is durably persisted. A step
// stuck at this status (crash before COMPLETED) is the signal the main loop
// uses to refuse ever clicking again automatically — see its own comment.
const REAL_SUBMIT_ATTEMPTING = 'ATTEMPTING';

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
    /** Phase 6: the last LEVER_APPLY step's fill result, if any. */
    this.lastLeverResult = null;
    /** Phase 7: the last WORKDAY_LOGIN step's result, if any. */
    this.lastWorkdayResult = null;
    /** Phase 14: the last ASHBY_APPLY step's field-classification result, if any. */
    this.lastAshbyResult = null;
    /** Phase 14: the last ASHBY_SUBMIT_CLICK step's {confirmed, evidence} result, if any. */
    this.lastAshbySubmitResult = null;
    /** Phase 19: the last WORKDAY_SUBMIT_CLICK step's {confirmed, evidence} result, if any. */
    this.lastWorkdaySubmitResult = null;
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

  async execute(plan, { approve = false, failAfterStep = null, failDuringStep = null, failAfterAttemptMarker = null, headless = true } = {}) {
    // Test seam only (mirrors failAfterStep, never set in production):
    // throws immediately after a step's own in-memory result is computed but
    // BEFORE it is durably persisted — simulating a crash in that exact gap,
    // so a genuinely confirmed (or any other) classification that was never
    // written to the store can never be recovered or reported.
    this.failDuringStep = failDuringStep;
    // Test seam only (never set in production): throws immediately after the
    // durable ATTEMPTING marker (below) is written but BEFORE the real
    // external click ever fires — simulating a crash in that specific gap,
    // so an attempt whose own execution status is genuinely unknown (did the
    // click even happen?) is never silently retried either.
    this.failAfterAttemptMarker = failAfterAttemptMarker;
    // Inspection-only plans navigate and screenshot without touching files.
    // Package validation is specific to plans that upload or interact with
    // form data — skipping it here does not weaken the existing safety model
    // for full plans, which still pass through validatePackage unchanged.
    if (plan.planType === 'GREENHOUSE') {
      this.#validateGreenhousePlan(plan);
    } else if (plan.planType === 'ZOHO_RECRUIT') {
      this.#validateZohoRecruitPlan(plan);
    } else if (plan.planType === 'LEVER') {
      this.#validateLeverPlan(plan);
    } else if (plan.planType === 'WORKDAY') {
      this.#validateWorkdayPlan(plan);
    } else if (plan.planType === 'ASHBY') {
      this.#validateAshbyPlan(plan);
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
      if (plan.planType === 'GREENHOUSE') {
        // The one legitimate exception: a prior process's REAL_SUBMIT_CLICK
        // genuinely completed and its own last known URL is Greenhouse's
        // confirmation route, not plan.targetUrl itself. Recognise that
        // specific, already-durable case the same way #runRealSubmitClick's
        // own post-step check does — anything else still requires an exact
        // match.
        const realSubmitStep = plan.steps.find((candidate) => candidate.type === StepType.REAL_SUBMIT_CLICK);
        const resumedAfterRealSubmit = realSubmitStep && state.steps?.[realSubmitStep.id]?.status === 'COMPLETED';
        this.#assertGreenhouseIdentity(page, plan, resumedAfterRealSubmit ? realSubmitStep : undefined);
      }
      if (plan.planType === 'ZOHO_RECRUIT') this.#assertZohoRecruitIdentity(page, plan);
      if (plan.planType === 'LEVER') this.#assertLeverIdentity(page, plan);
      if (plan.planType === 'WORKDAY') this.#assertWorkdayIdentity(page, plan);
      if (plan.planType === 'ASHBY') this.#assertAshbyIdentity(page, plan);
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
        if (prior?.status === 'COMPLETED' && !replayOtpFill) {
          // Restoring, never reconstructing: a REAL_SUBMIT_CLICK step that
          // already durably completed — in THIS process or an earlier one —
          // is never re-run (no second click, ever). Its exact, previously
          // persisted classification (confirmed or not) is restored verbatim
          // so the final result reflects what genuinely happened, instead of
          // silently reporting nothing (which outcome.js would otherwise
          // treat as "no result recorded" — safe, but a needless loss of a
          // real, already-verified outcome). A step completed before this
          // fix shipped, with no `submitResult` ever persisted, correctly
          // stays unrecoverable here — see the module comment on
          // `#captureGreenhouseSubmissionDuringClick` for why that case must
          // fall through to HUMAN_REQUIRED rather than guessing.
          if (step.type === StepType.REAL_SUBMIT_CLICK && prior.submitResult) {
            this.lastSubmitResult = prior.submitResult;
          }
          if (step.type === StepType.ASHBY_SUBMIT_CLICK && prior.submitResult) {
            this.lastAshbySubmitResult = prior.submitResult;
          }
          continue;
        }
        // A REAL_SUBMIT_CLICK attempt that was durably marked ATTEMPTING
        // (see #runRealSubmitClick, written before the real external click
        // can ever fire) but never reached COMPLETED: the prior process
        // crashed somewhere between "about to click" and "outcome durably
        // recorded" — which specifically includes the window after the real
        // click already happened and (possibly) reached the employer. There
        // is no way to tell from here whether the employer actually
        // received it, so this is never retried automatically — clicking
        // again could be a genuine duplicate submission. Treat it as
        // requiring human review and move on without touching the page.
        if (step.type === StepType.REAL_SUBMIT_CLICK && prior?.status === REAL_SUBMIT_ATTEMPTING) {
          this.lastSubmitResult = {
            category: ConfirmationCategory.PRIOR_ATTEMPT_UNVERIFIED,
            confirmed: false,
            evidence: 'a previous process started this submission click but its outcome was never durably recorded before it stopped; '
              + 'the employer may already have received the application, so this is never retried automatically — human review is required before any further action',
          };
          continue;
        }
        // Same duplicate-submission protection as REAL_SUBMIT_CLICK above,
        // for Ashby's own real submit action.
        if (step.type === StepType.ASHBY_SUBMIT_CLICK && prior?.status === REAL_SUBMIT_ATTEMPTING) {
          this.lastAshbySubmitResult = {
            confirmed: false,
            category: 'PRIOR_ATTEMPT_UNVERIFIED',
            evidence: 'a previous process started this submission click but its outcome was never durably recorded before it stopped; '
              + 'the employer may already have received the application, so this is never retried automatically — human review is required before any further action',
          };
          continue;
        }
        if (step.type === StepType.WORKDAY_SUBMIT_CLICK && prior?.status === REAL_SUBMIT_ATTEMPTING) {
          this.lastWorkdaySubmitResult = {
            confirmed: false,
            category: 'PRIOR_ATTEMPT_UNVERIFIED',
            evidence: 'a previous process started this submission click but its outcome was never durably recorded before it stopped; '
              + 'the employer may already have received the application, so this is never retried automatically — human review is required before any further action',
          };
          continue;
        }
        const policy = evaluatePolicy(step);
        if (policy.action === 'BLOCK') throw new HardStopError(policy.reason, { stepId: step.id });
        if (policy.action === 'PAUSE' && !approve) {
          this.store.pause(plan.planId, policy.reason);
          this.#emit(plan, { type: 'APPROVAL_REQUIRED', stepId: step.id });
          throw new ApprovalRequiredError(step.id);
        }
        try {
          if (plan.planType === 'GREENHOUSE' && step.type !== StepType.NAVIGATE) {
            this.#assertGreenhouseIdentity(page, plan, step);
          }
          if (plan.planType === 'ZOHO_RECRUIT' && step.type !== StepType.NAVIGATE) {
            this.#assertZohoRecruitIdentity(page, plan);
          }
          if (plan.planType === 'LEVER' && step.type !== StepType.NAVIGATE) {
            this.#assertLeverIdentity(page, plan);
          }
          if (plan.planType === 'WORKDAY' && step.type !== StepType.NAVIGATE) {
            this.#assertWorkdayIdentity(page, plan);
          }
          if (plan.planType === 'ASHBY' && step.type !== StepType.NAVIGATE) {
            this.#assertAshbyIdentity(page, plan);
          }
          await this.#runStep(page, plan, step, values);
          if (this.failDuringStep === step.id) {
            // Simulate a crash in the gap between computing this step's
            // result in memory (e.g. this.lastSubmitResult, just set above)
            // and ever durably writing it below. Nothing about this step is
            // persisted — on restart it is neither COMPLETED nor FAILED, so
            // it reruns from scratch rather than resurrecting an
            // unrecorded result.
            throw new Error(`SIMULATED_WORKER_CRASH_DURING:${step.id}`);
          }
          if (plan.planType === 'GREENHOUSE') this.#assertGreenhouseIdentity(page, plan, step);
          if (plan.planType === 'ZOHO_RECRUIT') this.#assertZohoRecruitIdentity(page, plan);
          if (plan.planType === 'LEVER') this.#assertLeverIdentity(page, plan);
          if (plan.planType === 'WORKDAY') this.#assertWorkdayIdentity(page, plan);
          if (plan.planType === 'ASHBY') this.#assertAshbyIdentity(page, plan);
          const stepExtra = { currentUrl: sanitizeUrl(page.url()) };
          // Durable evidence, not just a "this step ran" marker: a
          // REAL_SUBMIT_CLICK step's own classification (confirmed or not,
          // with its evidence) is persisted alongside COMPLETED so a later
          // resume can restore the exact authentic outcome instead of
          // reconstructing — or losing — it. See the restore logic above.
          if (step.type === StepType.REAL_SUBMIT_CLICK) stepExtra.submitResult = this.lastSubmitResult;
          if (step.type === StepType.ASHBY_SUBMIT_CLICK) stepExtra.submitResult = this.lastAshbySubmitResult;
          if (step.type === StepType.WORKDAY_SUBMIT_CLICK) stepExtra.submitResult = this.lastWorkdaySubmitResult;
          this.store.markStep(plan.planId, step.id, 'COMPLETED', stepExtra);
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
        zohoRecruitResult: this.lastZohoRecruitResult ?? null, leverResult: this.lastLeverResult ?? null,
        workdayResult: this.lastWorkdayResult ?? null, ashbyResult: this.lastAshbyResult ?? null,
        ashbySubmitResult: this.lastAshbySubmitResult ?? null,
        workdaySubmitResult: this.lastWorkdaySubmitResult ?? null };
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
        if (plan.planType === 'LEVER' && !leverPageIdentityMatches(p.url, page.url())) {
          throw new HardStopError('LEVER_PAGE_IDENTITY_MISMATCH', { stepId: step.id });
        }
        if (plan.planType === 'WORKDAY' && !workdayPageIdentityMatches(p.url, page.url())) {
          throw new HardStopError('WORKDAY_PAGE_IDENTITY_MISMATCH', { stepId: step.id });
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
      case StepType.LEVER_APPLY:
        this.lastLeverResult = await this.#runLeverApply(page, plan, step);
        break;
      case StepType.WORKDAY_LOGIN:
        this.lastWorkdayResult = await this.#runWorkdayLogin(page, plan, step, values);
        break;
      case StepType.WORKDAY_CREATE_ACCOUNT:
        this.lastWorkdayResult = await this.#runWorkdayCreateAccount(page, plan, step, values);
        break;
      case StepType.WORKDAY_APPLY:
        this.lastWorkdayResult = await this.#runWorkdayApply(page, plan, step, values);
        break;
      case StepType.WORKDAY_SUBMIT_CLICK:
        this.lastWorkdaySubmitResult = await this.#runWorkdaySubmitClick(page, plan, step);
        break;
      case StepType.ASHBY_APPLY:
        this.lastAshbyResult = await this.#runAshbyApply(page, plan, step, values);
        break;
      case StepType.ASHBY_SUBMIT_CLICK:
        this.lastAshbySubmitResult = await this.#runAshbySubmit(page, plan, step);
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
   * ever reachable through a genuinely observed 2xx response to THIS click's
   * own submission request, corroborated by either the confirmation URL +
   * `.confirmation` DOM container, or an explicit, matched, caller-configured
   * confirmation text — never by either corroborating signal alone. A
   * disappeared form, a generic navigation, a direct navigation to the
   * confirmation URL with no submission request ever made, a page that
   * happens to contain the configured text with no such request observed, or
   * "no error happened", is never treated as proof, by design (see
   * confirmation.js's own module comment for the live evidence this is
   * built from).
   *
   * <p>Anti-bot and login-wall signals — both before and after the click —
   * are hard stops, exactly like the pre-existing VALIDATE step's equivalent
   * checks, not a retryable "uncertain" result: either appearing here is
   * anomalous for an already-validated form and should stop the run, not
   * quietly fall through.
   *
   * <p>Crash-then-resume safety is <em>not</em> this method's job any more —
   * it never re-runs at all for a step `execute()`'s own loop already found
   * durably COMPLETED (restores the persisted classification verbatim) or
   * stuck at the durable {@link REAL_SUBMIT_ATTEMPTING} marker written below
   * (treated as an unverified prior attempt, never auto-retried — see the
   * loop's own comment on both). This method only ever runs for a
   * genuinely fresh attempt, so its pre-click inspection has, by
   * construction, no network evidence at all for "this attempt" yet —
   * `submissionRequestConfirmed` is always false there, so it can never
   * report CONFIRMED before the click; it exists purely to surface an
   * anti-bot/login-wall condition already present on the page before
   * clicking at all. It then durably marks the attempt as started —
   * BEFORE the real external click can fire, so that marker alone is what
   * protects against an automatic duplicate if everything after it is
   * lost — clicks once, and re-inspects with whatever network evidence
   * this exact click actually produced.
   */
  async #runRealSubmitClick(page, plan, step) {
    const p = step.params ?? {};
    const inspect = async (submissionRequestConfirmed) => {
      const bodyText = await page.locator('body').innerText().catch(() => '');
      const invalidFieldCount = await page.locator(':invalid').count().catch(() => 0);
      const formFieldsPresent = (await page.locator('#first_name, #email').count().catch(() => 0)) > 0;
      // The platform-structural signal (see confirmation.js's module comment
      // and plan.js's greenhouseConfirmationPageMatches for the live evidence
      // this is built from): the client only reaches this URL+container pair
      // after its own submission POST resolves 2xx, so this is computed from
      // the real DOM/URL, never guessed, and needs no per-employer config —
      // but URL+DOM alone can also be reached by a direct navigation, so
      // `submissionRequestConfirmed` (the actual network evidence for this
      // click, gathered below) is required alongside them.
      const confirmationUrlMatched = greenhouseConfirmationPageMatches(plan.targetUrl, page.url());
      const confirmationContainerPresent = (await page.locator('.confirmation').count().catch(() => 0)) > 0;
      return classifySubmissionAttempt({
        bodyText, invalidFieldCount, formFieldsPresent,
        expectedConfirmationText: p.expectedConfirmationText,
        confirmationUrlMatched, confirmationContainerPresent, submissionRequestConfirmed,
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

    // No click has happened yet in this process, so there is no "this
    // attempt" network evidence to pass in at all — `false` is the honest
    // absence of evidence, not a guess.
    const before = guardAgainstHardStops(await inspect(false));
    if (before.confirmed) return before;
    assertSafeSelector(p.selector);

    // Durable marker BEFORE the one real external side effect this codebase
    // ever performs: once this is written, a crash anywhere from here
    // through "outcome durably COMPLETED" leaves the step at this status
    // forever (the main loop's skip logic never advances it), which is
    // exactly what makes the main loop refuse to click again automatically
    // — see its own comment on REAL_SUBMIT_ATTEMPTING.
    this.store.markStep(plan.planId, step.id, REAL_SUBMIT_ATTEMPTING, { currentUrl: sanitizeUrl(page.url()) });
    if (this.failAfterAttemptMarker === step.id) {
      throw new Error(`SIMULATED_WORKER_CRASH_BEFORE_CLICK:${step.id}`);
    }

    const urlBeforeClick = page.url();
    const submissionRequestConfirmed = await this.#captureGreenhouseSubmissionDuringClick(
      page, plan, () => this.#retry(() => page.locator(p.selector).click()),
    );
    if (submissionRequestConfirmed) {
      // Greenhouse's own real handler is async — it awaits the submission
      // response, then conditionally navigates — so the navigation can
      // genuinely start after the click gesture itself has already
      // finished. Only wait for it when a genuinely successful submission
      // response was actually observed; a bounded wait here, not a guess.
      await page.waitForURL((url) => url.href !== urlBeforeClick, { timeout: 2000 }).catch(() => {});
    }
    await page.waitForLoadState('domcontentloaded').catch(() => {});
    return guardAgainstHardStops(await inspect(submissionRequestConfirmed));
  }

  /**
   * Phase 1 blocker fix (false-positive confirmation): registers a response
   * listener for the exact duration of a single REAL_SUBMIT_CLICK click
   * attempt (including its own internal {@link #retry} attempts, if any),
   * so that only a request/response pair genuinely caused by THIS click can
   * ever satisfy the structural confirmation signal — never a prior
   * attempt's leftover state, a crash-then-resume with no click in this
   * process at all, or a request unrelated to the real submission. The
   * listener is removed again immediately after, so nothing captured here
   * ever leaks into a later step or a later plan.
   *
   * <p>Matches the exact contract read from Greenhouse's own shipped source
   * (`app/utils/submit_application.ts`): a `POST` to the job posting's own
   * pathname — re-verified with the same host/scheme safety shape as
   * {@link greenhousePageIdentityMatches}, so an unrelated cross-origin POST
   * can never count — `Content-Type: application/json`, a body carrying a
   * `job_application` key, and the response itself must be `ok()` (2xx). A
   * matching request with a non-2xx response is observed and simply never
   * sets `confirmed`, so a rejected submission can never pass just because a
   * confirmation-looking page appears afterwards. Anything that doesn't
   * match this exact shape (a different path, method, content type, or no
   * `job_application` key — e.g. an unrelated analytics POST) is ignored.
   *
   * <p>Deliberately uses {@link Page.waitForResponse} (predicate-based,
   * registered <em>before</em> the click) rather than a passive listener
   * checked immediately after the click returns: Greenhouse's own real
   * handler is {@code async} — it awaits the submission `fetch` before
   * conditionally navigating — so the matching response can genuinely
   * arrive after the click gesture itself has already finished. A short,
   * bounded wait for that specific response is the correct way to observe
   * it without racing it.
   */
  async #captureGreenhouseSubmissionDuringClick(page, plan, performClick) {
    const matchesSubmissionRequest = (request) => {
      try {
        if (request.method() !== 'POST') return false;
        if (!greenhousePageIdentityMatches(plan.targetUrl, request.url())) return false;
        const contentType = (request.headers()['content-type'] || '').toLowerCase();
        if (!contentType.includes('application/json')) return false;
        const payload = request.postDataJSON();
        return Boolean(payload && typeof payload === 'object' && 'job_application' in payload);
      } catch {
        // An unparsable or unusual request simply never counts as evidence.
        return false;
      }
    };
    const responsePromise = page
      .waitForResponse((response) => matchesSubmissionRequest(response.request()), { timeout: 1500 })
      .catch(() => null);
    await performClick();
    const response = await responsePromise;
    return Boolean(response && response.ok());
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

  /**
   * Phase 14: the one step an ASHBY plan takes to prepare the application —
   * field classification/fill plus résumé attachment, never submission.
   * Reaching the apply form needs no reveal click here: live verification
   * found Ashby's own `/application` sub-route renders the full form on a
   * direct navigation (the backend builds `plan.targetUrl` as exactly that
   * route) — unlike {@link #runZohoRecruitApply}, this method only ever
   * inspects what the NAVIGATE step already reached.
   *
   * <p>Fields are discovered via each question's stable, platform-wide
   * `field-entry` wrapper and its `data-field-path` attribute (see
   * `ashby.js`'s own module comment for the live evidence) and classified by
   * {@link planAshbyFormActions} — conservative by design: only Legal Name,
   * Email, Phone and LinkedIn are ever auto-filled, and only when the form
   * itself currently accepts the filled value (the same post-fill
   * `checkValidity()` re-check {@link #runZohoRecruitApply} already
   * performs). A CAPTCHA anywhere on the form is an immediate hard stop with
   * nothing filled; none was ever observed live, but the check exists
   * defensively, same as every other adapter here.
   */
  async #runAshbyApply(page, plan, step) {
    const identity = step.params?.identity ?? {};

    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('ASHBY_CAPTCHA_PRESENT', { stepId: step.id });
    }

    if (await page.locator('[class*="field-entry"]').count() === 0) {
      const bodyText = (await page.locator('body').innerText().catch(() => '')).toLowerCase();
      if (/sign in|log in|session expired|authentication required/.test(bodyText)) {
        throw new HardStopError('ASHBY_LOGIN_REQUIRED', { stepId: step.id });
      }
      // Honest, genuine failure — never fabricated as success. A timeout or
      // an unrecognised post-navigation page both land here.
      throw new Error('ASHBY_FORM_NOT_FOUND');
    }

    const inspected = await page.evaluate(() => {
      const entries = Array.from(document.querySelectorAll('[class*="field-entry"]'));
      const results = [];
      let index = 0;
      for (const entry of entries) {
        const fieldPath = entry.getAttribute('data-field-path');
        const labelEl = entry.querySelector('label, [class*="question-title"]');
        const rawLabel = labelEl ? labelEl.textContent.trim() : null;
        const controls = Array.from(entry.querySelectorAll('input, select, textarea'));
        for (const el of controls) {
          el.setAttribute('data-robin-ashby-index', String(index));
          results.push({
            index, fieldPath,
            tag: el.tagName.toLowerCase(),
            type: el.type || null,
            rawLabel,
            required: Boolean(el.required) || /\*\s*$/.test(rawLabel || ''),
          });
          index += 1;
        }
      }
      return results;
    });

    const actions = planAshbyFormActions(inspected, identity);
    if (actions.captchaPresent) {
      throw new HardStopError('ASHBY_CAPTCHA_PRESENT', {
        stepId: step.id,
        fields: actions.fields.map((f) => ({ label: f.rawLabel, category: f.category })),
      });
    }

    const filled = [];
    const extraGaps = [];
    for (const action of actions.toFill) {
      const locator = page.locator(`[data-robin-ashby-index="${action.index}"]`);
      await this.#retry(() => locator.fill(action.value));
      // A post-fill validity check, not just "did .fill() not throw" — the
      // live form's own constraints can reject a verbatim-transcribed value.
      // Never report a field filled unless the form itself currently
      // accepts the value.
      const invalid = await locator.evaluate(
        (el) => typeof el.checkValidity === 'function' && !el.checkValidity()).catch(() => false);
      if (invalid) {
        extraGaps.push({ label: inspected[action.index]?.rawLabel ?? null, category: action.category });
      } else {
        filled.push({ label: inspected[action.index]?.rawLabel ?? null, category: action.category });
      }
    }

    const resumeOutcome = await this.#tryUploadAshbyResume(page, plan, actions.fields);
    const resumeAttached = [AshbyDocumentUploadState.ATTACHED, AshbyDocumentUploadState.CONFIRMED]
      .includes(resumeOutcome.state);
    if (resumeAttached) {
      filled.push({ label: resumeOutcome.label, category: AshbyFieldCategory.RESUME_UPLOAD });
    }

    const requiredGaps = [...actions.requiredGaps, ...extraGaps]
      .filter((gap) => !(resumeAttached && gap.category === AshbyFieldCategory.RESUME_UPLOAD));

    return {
      formFound: true,
      captchaPresent: false,
      filled,
      resumeUploadState: resumeOutcome.state,
      resumeUploadReason: resumeOutcome.reason,
      requiredGaps: requiredGaps.map((gap) => ({ label: gap.label, category: gap.category })),
      fields: actions.fields.map((f) => ({ label: f.rawLabel, category: f.category, required: f.required })),
    };
  }

  /**
   * Phase 14: mirrors {@link #tryUploadZohoArtifact}'s correlation/checksum/
   * accept-type/size-cap discipline, narrowed to the one upload field Ashby's
   * sampled posting actually carries (no cover-letter field was ever
   * observed on it — see ashby.js's own module comment). `ATTACHED`, not
   * `CONFIRMED`: a successful `setInputFiles` call proves only that the
   * reviewed bytes now sit in the browser's own file input, never that the
   * employer's server received or stored anything.
   */
  async #tryUploadAshbyResume(page, plan, classifiedFields) {
    const field = classifiedFields.find((f) => f.category === AshbyFieldCategory.RESUME_UPLOAD);
    if (!field) return { state: AshbyDocumentUploadState.NO_FIELD, reason: null, label: null };
    const label = field.rawLabel ?? null;
    const artifact = plan.package?.cv;
    if (!artifact?.path || !artifact?.versionId || !artifact?.sha256) {
      return { state: AshbyDocumentUploadState.NO_ARTIFACT, reason: 'no reviewed résumé is available for this application', label };
    }
    if (artifact.jobId !== plan.correlation.jobId || artifact.applicationId !== plan.correlation.applicationId) {
      throw new HardStopError('ASHBY_RESUME_CORRELATION_MISMATCH', { jobId: artifact.jobId, applicationId: artifact.applicationId });
    }
    if (!fs.existsSync(artifact.path)) {
      return { state: AshbyDocumentUploadState.REJECTED, reason: 'the file was not available to upload', label };
    }
    const locator = page.locator(`[data-robin-ashby-index="${field.index}"]`);
    const accept = await locator.getAttribute('accept').catch(() => null);
    const acceptsType = !accept || /pdf/i.test(accept);
    if (!acceptsType) {
      return { state: AshbyDocumentUploadState.REJECTED, reason: 'this form does not appear to accept PDF résumés', label };
    }
    const bytes = fs.readFileSync(artifact.path);
    if (bytes.length > MAX_ASHBY_UPLOAD_BYTES) {
      return { state: AshbyDocumentUploadState.REJECTED, reason: 'the file exceeds a safe upload size', label };
    }
    const actualSha256 = crypto.createHash('sha256').update(bytes).digest('hex');
    if (actualSha256 !== artifact.sha256) {
      return { state: AshbyDocumentUploadState.REJECTED, reason: 'the file no longer matches its reviewed checksum', label };
    }
    await locator.setInputFiles(artifact.path);
    return { state: AshbyDocumentUploadState.ATTACHED, reason: null, label };
  }

  /**
   * Phase 14: Ashby's own real submission action — the first genuinely
   * submission-capable step added to this codebase since Greenhouse's. Only
   * ever reached on an already-approved plan (see
   * {@code AutomationPlanRepository#prepareAshbySubmission}, mirroring
   * {@code #prepareRealSubmission}'s exact safety shape) and only ever the
   * last step of an ASHBY plan (enforced by {@link #validateAshbyPlan}).
   *
   * <p>Confirmation evidence (see `ashby.js`'s own module comment for the
   * full reasoning): Ashby's own public developer documentation establishes
   * that a bare 2xx is never enough — its documented
   * {@code applicationForm.submit} contract returns HTTP 200 for BOTH
   * success and failure, so the real signal is the response body's own
   * {@code success}/{@code results.formMessages.blocked} fields, captured
   * live from the real network response this exact click causes, combined
   * with the application form no longer being present. This project never
   * submitted a real test application, so this exact live contract was
   * never directly observed for the native hosted board specifically —
   * {@link classifyAshbySubmission} falls through to an honest `UNKNOWN`
   * rather than a guessed success whenever the observed response does not
   * match this documented shape.
   */
  async #runAshbySubmit(page, plan, step) {
    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('ASHBY_CAPTCHA_PRESENT', { stepId: step.id });
    }

    const submitButton = page.locator('button.ashby-application-form-submit-button');
    if (await submitButton.count() === 0) {
      return { confirmed: false, category: 'FORM_NOT_FOUND', evidence: 'the Ashby submit button was not found on this page' };
    }

    // Durable marker BEFORE the one real external side effect this step
    // ever performs: once this is written, a crash anywhere from here
    // through "outcome durably COMPLETED" leaves the step at this status
    // forever — the main loop's skip logic never advances it, which is
    // exactly what makes it refuse to click again automatically, the same
    // protection #runRealSubmitClick already has (see REAL_SUBMIT_ATTEMPTING's
    // own comment).
    this.store.markStep(plan.planId, step.id, REAL_SUBMIT_ATTEMPTING, { currentUrl: sanitizeUrl(page.url()) });
    if (this.failAfterAttemptMarker === step.id) {
      throw new Error(`SIMULATED_WORKER_CRASH_BEFORE_CLICK:${step.id}`);
    }

    const { networkSuccess, networkBlocked } = await this.#captureAshbySubmissionDuringClick(
      page, () => this.#retry(() => submitButton.click()),
    );
    await page.waitForLoadState('domcontentloaded').catch(() => {});
    await page.waitForTimeout(500);

    const formFieldsPresent = (await page.locator('[class*="field-entry"]').count().catch(() => 0)) > 0;
    const antiBotPresent = (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count().catch(() => 0)) > 0;
    const result = classifyAshbySubmission({ networkSuccess, networkBlocked, formFieldsPresent, antiBotPresent });
    if (result.category === AshbySubmissionCategory.ANTI_BOT) {
      throw new HardStopError('ASHBY_CAPTCHA_PRESENT', { stepId: step.id, evidence: result.evidence });
    }
    return result;
  }

  /**
   * Phase 14: mirrors {@link #captureGreenhouseSubmissionDuringClick}'s
   * exact technique (a response listener registered BEFORE the click, for
   * the exact duration of this one click attempt, removed again immediately
   * after) — adapted to Ashby's own documented contract: a `POST` to
   * `api.ashbyhq.com`'s `applicationForm.submit` route, read for its
   * documented `success`/`results.formMessages.blocked` JSON body fields
   * rather than Greenhouse's `job_application` request-shape check, since
   * Ashby's own contract is read from the RESPONSE, not the request.
   * Returns `undefined` for either field when no matching response was
   * observed at all, or its body did not parse as JSON — never a guess.
   */
  async #captureAshbySubmissionDuringClick(page, performClick) {
    const matchesSubmissionRequest = (request) => {
      try {
        if (request.method() !== 'POST') return false;
        const url = new URL(request.url());
        return url.hostname.toLowerCase() === 'api.ashbyhq.com' && /applicationform\.submit/i.test(url.pathname);
      } catch {
        return false;
      }
    };
    const responsePromise = page
      .waitForResponse((response) => matchesSubmissionRequest(response.request()), { timeout: 3000 })
      .catch(() => null);
    await performClick();
    const response = await responsePromise;
    if (!response) return { networkSuccess: undefined, networkBlocked: undefined };
    try {
      const body = await response.json();
      return {
        networkSuccess: typeof body?.success === 'boolean' ? body.success : undefined,
        networkBlocked: body?.results?.formMessages?.blocked === true,
      };
    } catch {
      return { networkSuccess: undefined, networkBlocked: undefined };
    }
  }

  #assertAshbyIdentity(page, plan) {
    if (!ashbyPageIdentityMatches(plan.targetUrl, page.url())) {
      throw new HardStopError('ASHBY_PAGE_IDENTITY_MISMATCH');
    }
  }

  /**
   * Phase 14: the ASHBY counterpart of {@link #validateZohoRecruitPlan}. The
   * allowed step set is NAVIGATE, SCREENSHOT, ASHBY_APPLY and (new) a
   * genuinely acceptable ASHBY_SUBMIT_CLICK — unlike every other non-
   * Greenhouse adapter here, this plan type CAN carry a real submission step,
   * since live verification found no CAPTCHA and no sign-in wall blocking
   * it. `REAL_SUBMIT_CLICK`/`MOCK_SUBMIT` (Greenhouse's and the mock
   * environment's own step types) are still always forbidden here — defence
   * in depth against a future bug ever cross-wiring step types, redundant
   * with, not a substitute for, {@code AutomationPlanRepository#prepareAshbySubmission}
   * being the only method that can ever introduce ASHBY_SUBMIT_CLICK, and
   * only onto an already-approved plan.
   */
  #validateAshbyPlan(plan) {
    if (!plan.correlation?.applicationId || !plan.correlation?.jobId || !Array.isArray(plan.steps)) {
      throw new HardStopError('INVALID_ASHBY_PLAN_CORRELATION');
    }
    const navigations = plan.steps.filter((step) => step.type === StepType.NAVIGATE);
    const targetUrl = plan.targetUrl ?? navigations[0]?.params?.url;
    if (navigations.length !== 1 || plan.steps[0] !== navigations[0]
        || !ashbyPageIdentityMatches(targetUrl, navigations[0].params?.url)) {
      throw new HardStopError('INVALID_ASHBY_TARGET');
    }
    const ids = new Set();
    for (const step of plan.steps) {
      if (!step?.id || ids.has(step.id)) throw new HardStopError('INVALID_ASHBY_STEP_ID');
      ids.add(step.id);
      if ([StepType.REAL_SUBMIT_CLICK, StepType.MOCK_SUBMIT].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_ASHBY_SUBMIT_STEP', { stepId: step.id });
      }
      if (![StepType.NAVIGATE, StepType.SCREENSHOT, StepType.ASHBY_APPLY, StepType.ASHBY_SUBMIT_CLICK].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_ASHBY_STEP', { stepId: step.id });
      }
    }
    const applySteps = plan.steps.filter((step) => step.type === StepType.ASHBY_APPLY);
    if (applySteps.length > 1) throw new HardStopError('ASHBY_APPLY_STEP_DUPLICATED');
    const submitSteps = plan.steps.filter((step) => step.type === StepType.ASHBY_SUBMIT_CLICK);
    if (submitSteps.length > 1) throw new HardStopError('ASHBY_SUBMIT_STEP_DUPLICATED');
    if (submitSteps.length === 1 && plan.steps.at(-1).type !== StepType.ASHBY_SUBMIT_CLICK) {
      throw new HardStopError('ASHBY_SUBMIT_STEP_MUST_BE_LAST');
    }
    if (submitSteps.length === 1 && applySteps.length !== 1) {
      throw new HardStopError('ASHBY_SUBMIT_WITHOUT_APPLY_STEP');
    }
    if (applySteps.length === 1 && submitSteps.length === 0 && plan.steps.at(-1).type !== StepType.ASHBY_APPLY) {
      throw new HardStopError('ASHBY_APPLY_STEP_MUST_BE_LAST');
    }
  }

  /**
   * Phase 6: the one real action a LEVER plan takes beyond navigating and
   * screenshotting. Unlike {@link #runZohoRecruitApply}, nothing here is
   * classified live — the backend already inspected the real, server-rendered
   * Lever form and computed the exact field list (selector, value,
   * classification) carried in `step.params.fields`; this method only
   * executes exactly that, the same "backend decides, worker executes"
   * division of responsibility {@link #runStep}'s generic FILL_FIELD/SELECT/
   * RADIO cases already use for Greenhouse — just consolidated into one step
   * so Greenhouse's and Zoho Recruit's existing, delicate step-dispatch
   * branches stay untouched.
   *
   * <p>A CAPTCHA anywhere on the form (Lever's real submission is gated by
   * an hCaptcha widget tied to its submit button — verified live) is an
   * immediate hard stop with nothing filled, mirroring Zoho Recruit's and
   * Greenhouse's own anti-bot checks: Robin never attempts to solve or
   * bypass one. Every selector this method touches still goes through
   * {@link assertSafeSelector} against the Lever-specific allowlist in
   * plan.js — the same defence-in-depth Greenhouse's selectors have, unlike
   * Zoho Recruit's necessarily dynamic per-run indices.
   *
   * <p>This step never clicks Submit, and no later step in a LEVER plan ever
   * can (enforced structurally by {@link #validateLeverPlan}, independent of
   * this method, and — one layer further down — by
   * {@link AutomationPlanRepository#prepareRealSubmission} only ever
   * accepting GREENHOUSE plans).
   */
  async #runLeverApply(page, plan, step) {
    const fields = step.params?.fields ?? [];

    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('LEVER_CAPTCHA_PRESENT', { stepId: step.id });
    }
    const bodyText = (await page.locator('body').innerText().catch(() => '')).toLowerCase();
    if (/sign in|log in|session expired|authentication required/.test(bodyText)) {
      throw new HardStopError('LEVER_LOGIN_REQUIRED', { stepId: step.id });
    }

    const { toFill, requiredGaps } = planLeverFormActions(fields);
    const filled = [];
    for (const action of toFill) {
      assertSafeSelector(action.selector);
      const locator = page.locator(action.selector);
      if (action.action === LeverFieldAction.FILL) {
        await this.#retry(() => locator.fill(action.value));
      } else if (action.action === LeverFieldAction.SELECT) {
        await locator.selectOption(action.value);
      } else if (action.action === LeverFieldAction.CHECK) {
        if (await locator.count() !== 1) {
          requiredGaps.push({ key: action.key, label: action.label, classification: 'SUPPORTED_AUTO',
            reason: 'the form no longer offers this exact option' });
          continue;
        }
        await locator.check({ force: false });
      }
      const invalid = await locator.evaluate(
        (el) => typeof el.checkValidity === 'function' && !el.checkValidity()).catch(() => false);
      if (invalid) {
        requiredGaps.push({ key: action.key, label: action.label, classification: 'SUPPORTED_AUTO',
          reason: 'the form rejected this value' });
      } else {
        filled.push({ key: action.key, label: action.label });
      }
    }

    const resumeOutcome = await this.#tryUploadLeverResume(page, plan);
    const resumeAttached = resumeOutcome.state === 'ATTACHED';
    if (resumeAttached) filled.push({ key: 'resume', label: 'Resume' });

    return {
      formFound: true,
      captchaPresent: false,
      filled,
      resumeUploadState: resumeOutcome.state,
      resumeUploadReason: resumeOutcome.reason,
      requiredGaps: requiredGaps.filter((gap) => !(resumeAttached && gap.key === 'resume')),
      fields: fields.map((f) => ({ key: f.key, label: f.label, classification: f.classification, required: f.required })),
    };
  }

  /**
   * Mirrors {@link #tryUploadZohoArtifact}'s correlation/integrity
   * guarantees (exact job/application/version binding, a checksum
   * re-verified against the bytes on disk) for Lever's one file-upload
   * field — simpler than Zoho's version since the field is always known
   * ahead of time ({@code [name="resume"]}, confirmed by the real inspected
   * form), not discovered live.
   */
  async #tryUploadLeverResume(page, plan) {
    const artifact = plan.package?.cv;
    const selector = '[name="resume"]';
    if (await page.locator(selector).count() === 0) {
      return { state: 'NO_FIELD', reason: null };
    }
    if (!artifact?.path || !artifact?.versionId || !artifact?.sha256) {
      return { state: 'NO_ARTIFACT', reason: 'no reviewed résumé is available for this application' };
    }
    if (artifact.jobId !== plan.correlation.jobId || artifact.applicationId !== plan.correlation.applicationId) {
      throw new HardStopError('LEVER_RESUME_CORRELATION_MISMATCH', { jobId: artifact.jobId, applicationId: artifact.applicationId });
    }
    if (!fs.existsSync(artifact.path)) {
      return { state: 'REJECTED', reason: 'the file was not available to upload' };
    }
    const bytes = fs.readFileSync(artifact.path);
    const actualSha256 = crypto.createHash('sha256').update(bytes).digest('hex');
    if (actualSha256 !== artifact.sha256) {
      return { state: 'REJECTED', reason: 'the file no longer matches its reviewed checksum' };
    }
    assertSafeSelector(selector);
    await page.locator(selector).setInputFiles(artifact.path);
    return { state: 'ATTACHED', reason: null };
  }

  /**
   * Phase 7: the one real action a WORKDAY plan takes beyond navigating,
   * screenshotting and resolving a saved credential. Verifies the
   * candidate's saved Workday password against this employer's real sign-in
   * form and stops — it never attempts the application form that follows a
   * successful sign-in (see WorkdayExecutionPlanService's module comment for
   * why that boundary is deliberate, not a shortcut).
   *
   * <p>Only ever interacts with the two fields this adapter's own selector
   * allowlist names ({@code [data-automation-id="email"]} / {@code
   * ["password"]}) and the submit button ({@code ["signInSubmitButton"]}) —
   * all three verified live on a real Workday tenant's real sign-in form.
   * The honeypot field the same live form carries
   * ({@code [data-automation-id="beecatcher"]}, {@code name="website"}) is
   * never named anywhere in this method and is not in the selector
   * allowlist at all, so it is structurally unreachable here, not merely
   * avoided by convention.
   *
   * <p>CAPTCHA and MFA are both immediate stops, exactly like every other
   * adapter's anti-bot handling: Robin never attempts to solve a CAPTCHA and
   * never attempts to guess or bypass a one-time code. A wrong saved
   * password is reported as an honest {@code LOGIN_FAILED} (the candidate's
   * saved credential needs updating) — never retried automatically, since
   * this step only ever runs once per plan (enforced by
   * {@link #validateWorkdayPlan}).
   */
  async #runWorkdayLogin(page, plan, step, values) {
    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id });
    }

    const emailField = page.locator('[data-automation-id="email"]');
    const passwordField = page.locator('[data-automation-id="password"]');
    const submitButton = page.locator('[data-automation-id="signInSubmitButton"]');
    const hasSignInForm = (await emailField.count()) > 0 && (await passwordField.count()) > 0;

    if (!hasSignInForm) {
      const bodyText = (await page.locator('body').innerText().catch(() => '')).toLowerCase();
      if (/verification code|two-factor|two factor|authenticator|security code|one-time code/.test(bodyText)) {
        throw new HardStopError('WORKDAY_MFA_REQUIRED', { stepId: step.id });
      }
      return { status: 'FORM_NOT_FOUND', reason: 'the Workday sign-in form was not found on this page' };
    }

    const username = values.credential?.username;
    const password = values.credential?.password;
    if (!username || !password) {
      return { status: 'FORM_NOT_FOUND', reason: 'no resolved credential was available to sign in with' };
    }

    assertSafeSelector('[data-automation-id="email"]');
    assertSafeSelector('[data-automation-id="password"]');
    assertSafeSelector('[data-automation-id="signInSubmitButton"]');
    await this.#retry(() => emailField.fill(String(username)));
    await passwordField.fill(String(password));
    await this.#retry(() => submitButton.click());
    await page.waitForLoadState('domcontentloaded').catch(() => {});
    await page.waitForTimeout(500);

    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id });
    }
    const afterText = (await page.locator('body').innerText().catch(() => '')).toLowerCase();
    if (/verification code|two-factor|two factor|authenticator|security code|one-time code/.test(afterText)) {
      throw new HardStopError('WORKDAY_MFA_REQUIRED', { stepId: step.id });
    }
    const stillOnSignIn = (await emailField.count()) > 0 && (await passwordField.count()) > 0;
    if (stillOnSignIn) {
      if (/incorrect|invalid|doesn't match|does not match|unable to sign you in|locked/.test(afterText)) {
        return { status: 'LOGIN_FAILED', reason: 'the employer’s sign-in page reported the credential as invalid' };
      }
      return { status: 'LOGIN_FAILED', reason: 'the sign-in form is still present after submitting — sign-in did not succeed' };
    }
    return { status: 'LOGIN_SUCCEEDED', reason: null };
  }

  /**
   * Phase 19: Workday application form filling, multi-step advance, and gap reporting.
   */
  async #runWorkdayApply(page, plan, step, values) {
    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id });
    }

    const emailField = page.locator('[data-automation-id="email"]');
    const passwordField = page.locator('[data-automation-id="password"]');
    const submitButton = page.locator('[data-automation-id="signInSubmitButton"]');
    const hasSignInForm = (await emailField.count()) > 0 && (await passwordField.count()) > 0;

    if (hasSignInForm) {
      const username = values.credential?.username;
      const password = values.credential?.password;
      if (username && password) {
        assertSafeSelector('[data-automation-id="email"]');
        assertSafeSelector('[data-automation-id="password"]');
        assertSafeSelector('[data-automation-id="signInSubmitButton"]');
        await this.#retry(() => emailField.fill(String(username)));
        await passwordField.fill(String(password));
        await this.#retry(() => submitButton.click());
        await page.waitForLoadState('domcontentloaded').catch(() => {});
        await page.waitForTimeout(500);

        if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
          throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id });
        }
        const afterText = (await page.locator('body').innerText().catch(() => '')).toLowerCase();
        if (/verification code|two-factor|two factor|authenticator|security code|one-time code/.test(afterText)) {
          throw new HardStopError('WORKDAY_MFA_REQUIRED', { stepId: step.id });
        }
        const stillOnSignIn = (await emailField.count()) > 0 && (await passwordField.count()) > 0;
        if (stillOnSignIn) {
          return {
            status: 'LOGIN_FAILED',
            reason: /incorrect|invalid|doesn't match|does not match|unable to sign you in|locked/.test(afterText)
              ? 'the employer’s sign-in page reported the credential as invalid'
              : 'the sign-in form is still present after submitting — sign-in did not succeed',
            filledCategories: [],
            requiredGaps: [],
            cvUploadState: WorkdayDocumentUploadState.NO_FIELD,
            coverLetterUploadState: WorkdayDocumentUploadState.NO_FIELD,
          };
        }
      } else {
        return {
          status: 'CREDENTIAL_REQUIRED',
          reason: 'sign-in form is present but no resolved credential was available',
          filledCategories: [],
          requiredGaps: [{ label: 'Workday Credentials', category: WorkdayFieldCategory.REQUIRES_HUMAN, reason: 'Saved Workday credential required to sign in' }],
          cvUploadState: WorkdayDocumentUploadState.NO_FIELD,
          coverLetterUploadState: WorkdayDocumentUploadState.NO_FIELD,
        };
      }
    }

    const candidate = step.params?.identity || plan.package?.candidate || values.identity || {};
    const hasCv = Boolean(plan.package?.cv);
    const hasCoverLetter = Boolean(plan.package?.coverLetter);

    const inspected = await page.evaluate(() => {
      const controls = Array.from(document.querySelectorAll('input, select, textarea'));
      return controls.map((el, i) => {
        const automationId = el.getAttribute('data-automation-id') || '';
        const name = el.getAttribute('name') || '';
        const type = el.getAttribute('type') || el.tagName.toLowerCase();
        const required = el.required || el.getAttribute('aria-required') === 'true' || automationId.toLowerCase().includes('required');
        let label = '';
        if (el.id) {
          const l = document.querySelector(`label[for="${el.id}"]`);
          if (l) label = l.textContent.trim();
        }
        if (!label && el.closest('label')) {
          label = el.closest('label').textContent.trim();
        }
        return {
          index: i,
          automationId,
          name,
          type,
          required,
          label,
          isFileInput: type === 'file' || (el.tagName.toLowerCase() === 'input' && type === 'file'),
          selector: automationId ? `[data-automation-id="${automationId}"]` : (name ? `[name="${name}"]` : null),
        };
      });
    });

    const planned = planWorkdayFormActions(inspected, candidate, { hasCvArtifact: hasCv, hasCoverLetterArtifact: hasCoverLetter });
    if (planned.requiredGaps.some((g) => g.category === WorkdayFieldCategory.CAPTCHA)) {
      throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id });
    }

    const filledCategories = [];
    for (const action of planned.actions) {
      if (action.selector) {
        assertSafeSelector(action.selector);
        const locator = page.locator(action.selector);
        if (await locator.count() > 0) {
          await this.#retry(() => locator.fill(action.value));
          filledCategories.push(action.category);
        }
      }
    }

    const cvOutcome = await this.#tryUploadWorkdayArtifact(page, plan, inspected, {
      category: WorkdayFieldCategory.RESUME_UPLOAD,
      packageKey: 'cv',
      missingReason: 'no reviewed CV is available for this application',
      correlationMismatchReason: 'WORKDAY_RESUME_CORRELATION_MISMATCH',
      acceptPattern: /pdf|doc|docx/i,
      wrongTypeReason: 'this form does not appear to accept the CV file type',
      maxBytes: MAX_WORKDAY_UPLOAD_BYTES,
    });
    if ([WorkdayDocumentUploadState.ATTACHED, WorkdayDocumentUploadState.CONFIRMED].includes(cvOutcome.state)) {
      filledCategories.push(WorkdayFieldCategory.RESUME_UPLOAD);
    }

    const coverLetterOutcome = await this.#tryUploadWorkdayArtifact(page, plan, inspected, {
      category: WorkdayFieldCategory.COVER_LETTER_UPLOAD,
      packageKey: 'coverLetter',
      missingReason: 'no approved cover letter is available for this application',
      correlationMismatchReason: 'WORKDAY_COVER_LETTER_CORRELATION_MISMATCH',
      acceptPattern: /plain|text|txt|pdf|doc|docx/i,
      wrongTypeReason: 'this form does not appear to accept the cover letter file type',
      maxBytes: MAX_WORKDAY_UPLOAD_BYTES,
    });
    if ([WorkdayDocumentUploadState.ATTACHED, WorkdayDocumentUploadState.CONFIRMED].includes(coverLetterOutcome.state)) {
      filledCategories.push(WorkdayFieldCategory.COVER_LETTER_UPLOAD);
    }

    const pageText = await page.locator('body').innerText().catch(() => '');
    const autoIds = inspected.map((f) => f.automationId);
    let currentStep = detectWorkdayStep(pageText, autoIds);

    if (planned.requiredGaps.length === 0) {
      const nextButton = page.locator('[data-automation-id="bottom-navigation-next-button"], [data-automation-id="next-button"]');
      if (await nextButton.count() > 0 && await nextButton.first().isVisible() && await nextButton.first().isEnabled()) {
        await this.#retry(() => nextButton.first().click());
        await page.waitForLoadState('domcontentloaded').catch(() => {});
        await page.waitForTimeout(500);
        const afterStepText = await page.locator('body').innerText().catch(() => '');
        currentStep = detectWorkdayStep(afterStepText, []);
      }
    }

    return {
      status: 'APPLICATION_PREPARED',
      step: currentStep,
      filledCategories: Array.from(new Set(filledCategories)),
      requiredGaps: planned.requiredGaps,
      cvUploadState: cvOutcome.state,
      coverLetterUploadState: coverLetterOutcome.state,
    };
  }

  /**
   * Phase 19: Workday document upload with sha256 checksum and correlation verification.
   */
  async #tryUploadWorkdayArtifact(page, plan, inspectedFields, config) {
    const field = inspectedFields.find((f) => classifyWorkdayField(f) === config.category);
    if (!field) return { state: WorkdayDocumentUploadState.NO_FIELD, reason: null, label: null };
    const label = field.label || config.category;
    const artifact = plan.package?.[config.packageKey];
    if (!artifact?.path || !artifact?.versionId || !artifact?.sha256) {
      return { state: WorkdayDocumentUploadState.NO_ARTIFACT, reason: config.missingReason, label };
    }
    if (artifact.jobId !== plan.correlation.jobId || artifact.applicationId !== plan.correlation.applicationId) {
      throw new HardStopError(config.correlationMismatchReason, { jobId: artifact.jobId, applicationId: artifact.applicationId });
    }
    if (!fs.existsSync(artifact.path)) {
      return { state: WorkdayDocumentUploadState.REJECTED, reason: 'the file was not available to upload', label };
    }
    const selector = field.selector || (field.automationId ? `[data-automation-id="${field.automationId}"]` : 'input[type="file"]');
    assertSafeSelector(selector);
    const locator = page.locator(selector);
    if (await locator.count() === 0) {
      return { state: WorkdayDocumentUploadState.NO_FIELD, reason: null, label };
    }
    const accept = await locator.first().getAttribute('accept').catch(() => null);
    if (accept && !config.acceptPattern.test(accept)) {
      return { state: WorkdayDocumentUploadState.REJECTED, reason: config.wrongTypeReason, label };
    }
    const bytes = fs.readFileSync(artifact.path);
    if (bytes.length > config.maxBytes) {
      return { state: WorkdayDocumentUploadState.REJECTED, reason: 'the file exceeds a safe upload size', label };
    }
    const actualSha256 = crypto.createHash('sha256').update(bytes).digest('hex');
    if (actualSha256 !== artifact.sha256) {
      return { state: WorkdayDocumentUploadState.REJECTED, reason: 'the file no longer matches its reviewed checksum', label };
    }
    await locator.first().setInputFiles(artifact.path);
    return { state: WorkdayDocumentUploadState.ATTACHED, reason: null, label };
  }

  /**
   * Phase 19: Workday guarded submission execution and outcome classification.
   */
  async #runWorkdaySubmitClick(page, plan, step) {
    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id });
    }

    const submitButton = page.locator('[data-automation-id="review-submit-button"], [data-automation-id="bottom-navigation-submit-button"]');
    if (await submitButton.count() === 0) {
      return { confirmed: false, category: WorkdaySubmissionCategory.FORM_NOT_FOUND, evidence: 'the Workday submit button was not found on this page' };
    }

    this.store.markStep(plan.planId, step.id, REAL_SUBMIT_ATTEMPTING, { currentUrl: sanitizeUrl(page.url()) });
    if (this.failAfterAttemptMarker === step.id) {
      throw new Error(`SIMULATED_WORKER_CRASH_BEFORE_CLICK:${step.id}`);
    }

    await this.#retry(() => submitButton.first().click());
    await page.waitForLoadState('domcontentloaded').catch(() => {});
    await page.waitForTimeout(500);

    const currentUrl = page.url();
    const pageText = await page.locator('body').innerText().catch(() => '');
    const hasValidationErrors = (await page.locator('[data-automation-id="error-banner"], [class*="errorMessage"]').count().catch(() => 0)) > 0;

    const category = classifyWorkdaySubmission({ pageText, currentUrl, hasValidationErrors });
    if (category === WorkdaySubmissionCategory.ANTI_BOT) {
      throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id, evidence: 'Anti-bot or CAPTCHA detected after submit click' });
    }
    if (category === WorkdaySubmissionCategory.CONFIRMED) {
      return { confirmed: true, category, evidence: 'Workday confirmed application submission' };
    }
    return {
      confirmed: false,
      category,
      evidence: hasValidationErrors
        ? 'Workday form validation errors detected after submit click'
        : `Submission completed but outcome was ${category}`,
    };
  }

  /**
   * Phase 11: creates a NEW Workday employer account for the candidate, on
   * the same sign-in page {@link #runWorkdayLogin} already verifies
   * against. The create-account trio of selectors
   * ({@code verifyPassword}/{@code createAccountCheckbox}/{@code
   * createAccountSubmitButton}) was verified live alongside the sign-in
   * form's own selectors (see plan.js's {@code workdaySelectors}); the same
   * honeypot ({@code [data-automation-id="beecatcher"]}) is, as with
   * sign-in, never named here and not in the selector allowlist at all.
   *
   * <p>The candidate's resolved registration e-mail ({@code
   * values.identity.email}, from ACCOUNT_RESOLVE_IDENTITY) and a freshly
   * generated, server-saved password ({@code values.credential.password},
   * from ACCOUNT_RESOLVE_CREDENTIAL) fill the form; neither is ever invented
   * here. Checking the agreement checkbox and clicking submit happen ONLY
   * when {@code step.params.termsAuthorized === true} — never a default,
   * never inferred from the step merely existing. This is defense in depth:
   * the backend (WorkdayExecutionPlanService#authorizeAccountCreation) only
   * ever appends this step after the candidate has already, separately,
   * explicitly authorized account creation — but the worker never trusts
   * that alone.
   *
   * <p>Exactly like {@link #runWorkdayLogin}, this never attempts the
   * application form beyond the account itself, and a wrong or rejected
   * attempt is reported honestly (ACCOUNT_CREATE_FAILED) rather than
   * retried automatically — enforced by {@link #validateWorkdayPlan}
   * allowing at most one such step per plan. Whether the new account's
   * verification mail actually arrives and the account actually works is
   * NOT decided here: the candidate's next application run uses the
   * existing WORKDAY_LOGIN step against this same saved credential as its
   * own honest confirmation.
   */
  async #runWorkdayCreateAccount(page, plan, step, values) {
    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id });
    }

    const emailField = page.locator('[data-automation-id="email"]');
    const passwordField = page.locator('[data-automation-id="password"]');
    const verifyPasswordField = page.locator('[data-automation-id="verifyPassword"]');
    const checkbox = page.locator('[data-automation-id="createAccountCheckbox"]');
    const submitButton = page.locator('[data-automation-id="createAccountSubmitButton"]');
    const hasCreateAccountForm = (await emailField.count()) > 0 && (await passwordField.count()) > 0
      && (await verifyPasswordField.count()) > 0 && (await checkbox.count()) > 0 && (await submitButton.count()) > 0;

    if (!hasCreateAccountForm) {
      return { status: 'FORM_NOT_FOUND', reason: 'the Workday create-account form was not found on this page' };
    }

    const email = values.identity?.email;
    const password = values.credential?.password;
    if (!email || !password) {
      return { status: 'FORM_NOT_FOUND', reason: 'no resolved identity or credential was available to create an account with' };
    }

    if (step.params?.termsAuthorized !== true) {
      return { status: 'TERMS_NOT_AUTHORIZED', reason: 'account creation was not explicitly authorized for this step' };
    }

    assertSafeSelector('[data-automation-id="email"]');
    assertSafeSelector('[data-automation-id="password"]');
    assertSafeSelector('[data-automation-id="verifyPassword"]');
    assertSafeSelector('[data-automation-id="createAccountCheckbox"]');
    assertSafeSelector('[data-automation-id="createAccountSubmitButton"]');
    await this.#retry(() => emailField.fill(String(email)));
    await passwordField.fill(String(password));
    await verifyPasswordField.fill(String(password));
    await checkbox.check();
    await this.#retry(() => submitButton.click());
    await page.waitForLoadState('domcontentloaded').catch(() => {});
    await page.waitForTimeout(500);

    if (await page.locator('iframe[src*="captcha" i], iframe[src*="hcaptcha" i], [data-sitekey], .g-recaptcha, .h-captcha').count() > 0) {
      throw new HardStopError('WORKDAY_CAPTCHA_PRESENT', { stepId: step.id });
    }
    const afterText = (await page.locator('body').innerText().catch(() => '')).toLowerCase();
    const stillOnCreateAccount = (await emailField.count()) > 0 && (await checkbox.count()) > 0;
    if (stillOnCreateAccount) {
      if (/already (have|has) an account|already exists|already registered/.test(afterText)) {
        return { status: 'ACCOUNT_CREATE_FAILED', reason: 'the employer’s site reported that an account already exists for this email' };
      }
      return { status: 'ACCOUNT_CREATE_FAILED', reason: 'the create-account form is still present after submitting — account creation did not succeed' };
    }
    return { status: 'ACCOUNT_CREATED', reason: null };
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

  /**
   * REAL_SUBMIT_CLICK is the one step allowed to leave `plan.targetUrl`
   * behind: a genuinely successful Greenhouse submission navigates the
   * browser to this exact posting's own confirmation route (see
   * confirmation.js's module comment for the live evidence), both before the
   * click (crash-resume: a prior attempt already got there) and after it. No
   * other step, and no other destination — including an attacker-controlled
   * host that merely matches the confirmation *path* string — is accepted;
   * `greenhouseConfirmationPageMatches` re-verifies the same host/scheme
   * safety shape as the base identity check.
   */
  #assertGreenhouseIdentity(page, plan, step) {
    const actualUrl = page.url();
    if (greenhousePageIdentityMatches(plan.targetUrl, actualUrl)) return;
    if (step?.type === StepType.REAL_SUBMIT_CLICK && greenhouseConfirmationPageMatches(plan.targetUrl, actualUrl)) return;
    throw new HardStopError('GREENHOUSE_PAGE_IDENTITY_MISMATCH');
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

  /**
   * Phase 6: the LEVER counterpart of {@link #validateZohoRecruitPlan}.
   * Unlike Zoho Recruit, there IS a pre-computed field list here (the
   * backend already inspected the real static form — see
   * ats/AtsAdapters.LeverAdapter), so this also requires `plan.fields` to be
   * present, mirroring {@link #validateGreenhousePlan}'s own correlation
   * check. The property that matters most is identical to Zoho Recruit's:
   * this plan type can never carry a REAL_SUBMIT_CLICK or MOCK_SUBMIT step —
   * redundant with, not a substitute for, the backend's own refusal
   * (AutomationPlanRepository#prepareRealSubmission accepts only GREENHOUSE
   * plans).
   */
  #validateLeverPlan(plan) {
    if (!plan.correlation?.applicationId || !plan.correlation?.jobId
        || !Array.isArray(plan.steps) || !Array.isArray(plan.fields)) {
      throw new HardStopError('INVALID_LEVER_PLAN_CORRELATION');
    }
    const navigations = plan.steps.filter((step) => step.type === StepType.NAVIGATE);
    const targetUrl = plan.targetUrl ?? navigations[0]?.params?.url;
    if (navigations.length !== 1 || plan.steps[0] !== navigations[0]
        || !leverPageIdentityMatches(targetUrl, navigations[0].params?.url)) {
      throw new HardStopError('INVALID_LEVER_TARGET');
    }
    const ids = new Set();
    for (const step of plan.steps) {
      if (!step?.id || ids.has(step.id)) throw new HardStopError('INVALID_LEVER_STEP_ID');
      ids.add(step.id);
      if ([StepType.REAL_SUBMIT_CLICK, StepType.MOCK_SUBMIT].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_LEVER_SUBMIT_STEP', { stepId: step.id });
      }
      if (![StepType.NAVIGATE, StepType.SCREENSHOT, StepType.LEVER_APPLY].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_LEVER_STEP', { stepId: step.id });
      }
    }
    const applySteps = plan.steps.filter((step) => step.type === StepType.LEVER_APPLY);
    if (applySteps.length > 1) throw new HardStopError('LEVER_APPLY_STEP_DUPLICATED');
    if (applySteps.length === 1 && plan.steps.at(-1).type !== StepType.LEVER_APPLY) {
      throw new HardStopError('LEVER_APPLY_STEP_MUST_BE_LAST');
    }
  }

  #assertLeverIdentity(page, plan) {
    if (!leverPageIdentityMatches(plan.targetUrl, page.url())) {
      throw new HardStopError('LEVER_PAGE_IDENTITY_MISMATCH');
    }
  }

  /**
   * Phase 7: the WORKDAY counterpart of {@link #validateLeverPlan}. The
   * allowed step set is deliberately small (NAVIGATE, SCREENSHOT,
   * ACCOUNT_RESOLVE_IDENTITY, ACCOUNT_RESOLVE_CREDENTIAL, and this plan's
   * own WORKDAY_LOGIN or, since Phase 11, WORKDAY_CREATE_ACCOUNT) — there is
   * no application-form step at all, because live inspection never observed
   * one safely reachable (see WorkdayExecutionPlanService's own module
   * comment). As with every other adapter here, this plan type can never
   * carry a REAL_SUBMIT_CLICK or MOCK_SUBMIT step — redundant with, not a
   * substitute for, the backend's own refusal
   * (AutomationPlanRepository#prepareRealSubmission accepts only GREENHOUSE
   * plans).
   *
   * <p>Phase 11: a WORKDAY plan is either a login check or an
   * account-creation attempt, never both — WORKDAY_LOGIN and
   * WORKDAY_CREATE_ACCOUNT are mutually exclusive within one plan, and
   * WORKDAY_CREATE_ACCOUNT additionally requires a preceding
   * ACCOUNT_RESOLVE_IDENTITY step (account creation needs the registration
   * e-mail; a login check does not).
   */
  #validateWorkdayPlan(plan) {
    if (!plan.correlation?.applicationId || !plan.correlation?.jobId || !Array.isArray(plan.steps)) {
      throw new HardStopError('INVALID_WORKDAY_PLAN_CORRELATION');
    }
    const navigations = plan.steps.filter((step) => step.type === StepType.NAVIGATE);
    const targetUrl = plan.targetUrl ?? navigations[0]?.params?.url;
    if (navigations.length !== 1 || plan.steps[0] !== navigations[0]
        || !workdayPageIdentityMatches(targetUrl, navigations[0].params?.url)) {
      throw new HardStopError('INVALID_WORKDAY_TARGET');
    }
    const ids = new Set();
    for (const step of plan.steps) {
      if (!step?.id || ids.has(step.id)) throw new HardStopError('INVALID_WORKDAY_STEP_ID');
      ids.add(step.id);
      if ([StepType.REAL_SUBMIT_CLICK, StepType.MOCK_SUBMIT].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_WORKDAY_SUBMIT_STEP', { stepId: step.id });
      }
      if (![StepType.NAVIGATE, StepType.SCREENSHOT, StepType.ACCOUNT_RESOLVE_IDENTITY,
          StepType.ACCOUNT_RESOLVE_CREDENTIAL, StepType.WORKDAY_LOGIN,
          StepType.WORKDAY_CREATE_ACCOUNT, StepType.WORKDAY_APPLY,
          StepType.WORKDAY_SUBMIT_CLICK].includes(step.type)) {
        throw new HardStopError('FORBIDDEN_WORKDAY_STEP', { stepId: step.id });
      }
    }
    const loginSteps = plan.steps.filter((step) => step.type === StepType.WORKDAY_LOGIN);
    const createAccountSteps = plan.steps.filter((step) => step.type === StepType.WORKDAY_CREATE_ACCOUNT);
    const applySteps = plan.steps.filter((step) => step.type === StepType.WORKDAY_APPLY);
    const submitSteps = plan.steps.filter((step) => step.type === StepType.WORKDAY_SUBMIT_CLICK);
    const identitySteps = plan.steps.filter((step) => step.type === StepType.ACCOUNT_RESOLVE_IDENTITY);
    const credentialSteps = plan.steps.filter((step) => step.type === StepType.ACCOUNT_RESOLVE_CREDENTIAL);
    if (applySteps.length > 1) throw new HardStopError('WORKDAY_APPLY_STEP_DUPLICATED');
    if (submitSteps.length > 1) throw new HardStopError('WORKDAY_SUBMIT_STEP_DUPLICATED');
    if (submitSteps.length === 1 && plan.steps.at(-1).type !== StepType.WORKDAY_SUBMIT_CLICK) {
      throw new HardStopError('WORKDAY_SUBMIT_STEP_MUST_BE_LAST');
    }
    if (submitSteps.length === 1 && applySteps.length !== 1) {
      throw new HardStopError('WORKDAY_SUBMIT_WITHOUT_APPLY_STEP');
    }
    if (applySteps.length === 1 && submitSteps.length === 0 && plan.steps.at(-1).type !== StepType.WORKDAY_APPLY) {
      throw new HardStopError('WORKDAY_APPLY_STEP_MUST_BE_LAST');
    }
    if (applySteps.length === 1 && (loginSteps.length === 1 || createAccountSteps.length === 1)) {
      throw new HardStopError('WORKDAY_APPLY_AND_READINESS_STEP_CONFLICT');
    }
    if (loginSteps.length > 1) throw new HardStopError('WORKDAY_LOGIN_STEP_DUPLICATED');
    if (createAccountSteps.length > 1) throw new HardStopError('WORKDAY_CREATE_ACCOUNT_STEP_DUPLICATED');
    if (identitySteps.length > 1) throw new HardStopError('WORKDAY_IDENTITY_STEP_DUPLICATED');
    if (loginSteps.length === 1 && createAccountSteps.length === 1) {
      throw new HardStopError('WORKDAY_LOGIN_AND_CREATE_ACCOUNT_CONFLICT');
    }
    if (loginSteps.length === 1 && plan.steps.at(-1).type !== StepType.WORKDAY_LOGIN) {
      throw new HardStopError('WORKDAY_LOGIN_STEP_MUST_BE_LAST');
    }
    if (createAccountSteps.length === 1 && plan.steps.at(-1).type !== StepType.WORKDAY_CREATE_ACCOUNT) {
      throw new HardStopError('WORKDAY_CREATE_ACCOUNT_STEP_MUST_BE_LAST');
    }
    if (loginSteps.length === 1 && credentialSteps.length !== 1) {
      throw new HardStopError('WORKDAY_LOGIN_WITHOUT_CREDENTIAL_RESOLUTION');
    }
    if (createAccountSteps.length === 1 && (credentialSteps.length !== 1 || identitySteps.length !== 1)) {
      throw new HardStopError('WORKDAY_CREATE_ACCOUNT_WITHOUT_IDENTITY_OR_CREDENTIAL_RESOLUTION');
    }
  }

  #assertWorkdayIdentity(page, plan) {
    if (!workdayPageIdentityMatches(plan.targetUrl, page.url())) {
      throw new HardStopError('WORKDAY_PAGE_IDENTITY_MISMATCH');
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
