import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import { StepType, workdayPageIdentityMatches, assertSafeSelector } from '../src/plan.js';
import {
  classifyWorkdayField,
  planWorkdayFormActions,
  detectWorkdayStep,
  classifyWorkdaySubmission,
  isWorkdayHoneypot,
  WorkdayFieldCategory,
  WorkdayDocumentUploadState,
  WorkdaySubmissionCategory,
} from '../src/workday.js';

const URL = 'https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/x/apply';

// Mirrors the REAL, live-verified Workday sign-in form (Phase 7 investigation,
// nvidia.wd5.myworkdayjobs.com): data-automation-id="email"/"password"/
// "signInSubmitButton", plus the real honeypot ("beecatcher", name="website").
// The submit button is a real <form> submit — clicking it causes a genuine
// page navigation back through the route handler below, exactly like the
// real Workday sign-in page redisplaying or replacing itself after submit.
const SIGN_IN_HTML = `<!doctype html><html><body>
  <h1>Sign In</h1>
  <form action="${URL}" method="get">
    <input type="text" data-automation-id="email" autocomplete="email">
    <input type="password" data-automation-id="password" autocomplete="current-password">
    <button type="submit" data-automation-id="signInSubmitButton">Sign In</button>
    <input type="text" name="website" data-automation-id="beecatcher" style="display:none">
  </form>
</body></html>`;

function workdayPlan(root, { extraSteps = [], credentialSaved = true } = {}) {
  const steps = [
    { id: 'navigate-application', type: StepType.NAVIGATE, policy: 'AUTO', params: { url: URL } },
    { id: 'screenshot-landing', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} },
    ...extraSteps,
  ];
  return {
    planId: 'plan-workday', planType: 'WORKDAY', version: 1,
    correlation: { applicationId: 'app-1', jobId: 'job-1', employerDomain: 'nvidia.wd5.myworkdayjobs.com' },
    targetUrl: URL, safetyContract: 'NO_SUBMIT', steps, credentialSaved,
  };
}

function credentialStep() {
  return { id: 'account-resolve-credential', type: StepType.ACCOUNT_RESOLVE_CREDENTIAL, policy: 'AUTO',
    params: { employerDomain: 'nvidia.wd5.myworkdayjobs.com' } };
}

function identityStep() {
  return { id: 'account-resolve-identity', type: StepType.ACCOUNT_RESOLVE_IDENTITY, policy: 'AUTO',
    params: { employerDomain: 'nvidia.wd5.myworkdayjobs.com' } };
}

function loginStep() {
  return { id: 'workday-login', type: StepType.WORKDAY_LOGIN, policy: 'AUTO', params: {} };
}

function createAccountStep(id = 'workday-create-account', params = { termsAuthorized: true }) {
  return { id, type: StepType.WORKDAY_CREATE_ACCOUNT, policy: 'AUTO', params };
}

function worker(root, { credentialResolver = null } = {}) {
  return new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root, credentialResolver });
}

function routedWorker(root, routeHandler, opts = {}) {
  return new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => { await page.route(`${URL}**`, routeHandler); },
    ...opts,
  });
}

// ── Identity / selector safety (no browser) ─────────────────────────────

test('Workday identity requires the same tenant host, with or without a data-center infix', () => {
  assert.equal(workdayPageIdentityMatches(URL, URL), true);
  assert.equal(workdayPageIdentityMatches(URL, 'https://evil.example.com/phish'), false);
  assert.equal(workdayPageIdentityMatches(URL, URL.replace('https://', 'http://')), false);
  assert.equal(workdayPageIdentityMatches(URL, 'https://other-tenant.wd5.myworkdayjobs.com/en-US/Other/job/x/apply'), false);
  assert.equal(workdayPageIdentityMatches(
    'https://acme.myworkdayjobs.com/en-US/Acme/job/x/apply',
    'https://acme.myworkdayjobs.com/en-US/Acme/job/x/apply/applyManually'), true);
});

test('the Workday selector allowlist admits only the three verified sign-in controls, never the honeypot', () => {
  assert.doesNotThrow(() => assertSafeSelector('[data-automation-id="email"]'));
  assert.doesNotThrow(() => assertSafeSelector('[data-automation-id="password"]'));
  assert.doesNotThrow(() => assertSafeSelector('[data-automation-id="signInSubmitButton"]'));
  assert.throws(() => assertSafeSelector('[data-automation-id="beecatcher"]'), /UNALLOWLISTED_SELECTOR/);
  assert.throws(() => assertSafeSelector('input[name="website"]'), /UNALLOWLISTED_SELECTOR/);
});

test('the Workday selector allowlist also admits the create-account trio, never the honeypot', () => {
  assert.doesNotThrow(() => assertSafeSelector('[data-automation-id="verifyPassword"]'));
  assert.doesNotThrow(() => assertSafeSelector('[data-automation-id="createAccountCheckbox"]'));
  assert.doesNotThrow(() => assertSafeSelector('[data-automation-id="createAccountSubmitButton"]'));
  assert.throws(() => assertSafeSelector('[data-automation-id="beecatcher"]'), /UNALLOWLISTED_SELECTOR/);
});

// ── Plan safety ──────────────────────────────────────────────────────────

test('a WORKDAY plan can never carry a REAL_SUBMIT_CLICK step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-forbidden-submit-'));
  const submit = { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO', params: { selector: 'button[type="submit"]' } };
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep(), submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_WORKDAY_SUBMIT_STEP');
});

test('a WORKDAY plan can never carry a MOCK_SUBMIT step either', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-forbidden-mock-'));
  const submit = { id: 'mock-submit', type: StepType.MOCK_SUBMIT, policy: 'AUTO', params: { selector: 'button' } };
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep(), submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_WORKDAY_SUBMIT_STEP');
});

test('a step type outside the WORKDAY allowlist is rejected — no application-form step exists', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-forbidden-step-'));
  const fill = { id: 'fill-something', type: StepType.FILL_FIELD, policy: 'AUTO', params: { selector: '#x', value: 'y' } };
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep(), fill] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_WORKDAY_STEP');
});

test('WORKDAY_LOGIN must be the plan\'s last step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-login-not-last-'));
  const extra = { id: 'screenshot-extra', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} };
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep(), extra] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_LOGIN_STEP_MUST_BE_LAST');
});

test('WORKDAY_LOGIN without a preceding credential resolution is rejected — never signs in with nothing', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-login-no-credential-'));
  const plan = workdayPlan(root, { extraSteps: [loginStep()] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_LOGIN_WITHOUT_CREDENTIAL_RESOLUTION');
});

test('a plan whose navigation target is not an allowed Workday host is rejected before any browser work', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-bad-target-'));
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep()] });
  plan.steps[0].params.url = 'https://evil.example.com/acme/job/x/apply';
  await assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'INVALID_WORKDAY_TARGET');
  fs.rmSync(root, { recursive: true, force: true });
});

// ── Phase 11: WORKDAY_CREATE_ACCOUNT plan safety ─────────────────────────

test('WORKDAY_CREATE_ACCOUNT must be the plan\'s last step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-not-last-'));
  const extra = { id: 'screenshot-extra', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} };
  const plan = workdayPlan(root, { extraSteps: [identityStep(), credentialStep(), createAccountStep(), extra] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_CREATE_ACCOUNT_STEP_MUST_BE_LAST');
});

test('WORKDAY_CREATE_ACCOUNT without a preceding identity resolution is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-no-identity-'));
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), createAccountStep()] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_CREATE_ACCOUNT_WITHOUT_IDENTITY_OR_CREDENTIAL_RESOLUTION');
});

test('WORKDAY_CREATE_ACCOUNT without a preceding credential resolution is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-no-credential-'));
  const plan = workdayPlan(root, { extraSteps: [identityStep(), createAccountStep()] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_CREATE_ACCOUNT_WITHOUT_IDENTITY_OR_CREDENTIAL_RESOLUTION');
});

test('a plan can never carry both WORKDAY_LOGIN and WORKDAY_CREATE_ACCOUNT — it is one or the other', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-login-create-conflict-'));
  const plan = workdayPlan(root, { extraSteps: [identityStep(), credentialStep(), loginStep(), createAccountStep()] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_LOGIN_AND_CREATE_ACCOUNT_CONFLICT');
});

test('a duplicated WORKDAY_CREATE_ACCOUNT step is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-dup-'));
  const plan = workdayPlan(root, { extraSteps: [identityStep(), credentialStep(),
    createAccountStep('workday-create-account-1'), createAccountStep('workday-create-account-2')] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_CREATE_ACCOUNT_STEP_DUPLICATED');
});

// ── Phase 11: WORKDAY_CREATE_ACCOUNT live (routed, zero-real-network) execution ──

// Mirrors the live-verified Workday create-account form (same tenant/page as
// SIGN_IN_HTML above): the sign-in trio plus verifyPassword, the agreement
// checkbox, and the dedicated submit button — plus the same honeypot.
const CREATE_ACCOUNT_HTML = `<!doctype html><html><body>
  <h1>Create Account</h1>
  <form action="${URL}" method="get">
    <input type="text" data-automation-id="email" autocomplete="email">
    <input type="password" data-automation-id="password" autocomplete="new-password">
    <input type="password" data-automation-id="verifyPassword" autocomplete="new-password">
    <input type="checkbox" data-automation-id="createAccountCheckbox">
    <button type="submit" data-automation-id="createAccountSubmitButton">Create Account</button>
    <input type="text" name="website" data-automation-id="beecatcher" style="display:none">
  </form>
</body></html>`;

const ACCOUNT_CREATED_HTML = '<!doctype html><html><body><h1>Account created</h1></body></html>';

const IDENTITY_EMAIL = 'candidate@managed.robinapp.example';

function accountCreationResolvers() {
  return {
    identityResolver: async () => ({ email: IDENTITY_EMAIL, mailbox: 'managed' }),
    credentialResolver: async () => ({ username: IDENTITY_EMAIL, password: 'Rb9!freshly-generated-secret' }),
  };
}

test('authorized account creation fills the real create-account form and never touches the honeypot', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-ok-'));
  let filledHoneypot = false;
  let requestCount = 0;
  const plan = workdayPlan(root, { extraSteps: [identityStep(), credentialStep(), createAccountStep()] });
  const instance = new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root, ...accountCreationResolvers(),
    onPageCreated: async (page) => {
      await page.route(`${URL}**`, (route) => {
        requestCount += 1;
        route.fulfill({ contentType: 'text/html', body: requestCount === 1 ? CREATE_ACCOUNT_HTML : ACCOUNT_CREATED_HTML });
      });
      await page.exposeFunction('__reportHoneypot', () => { filledHoneypot = true; });
      await page.addInitScript(() => {
        document.addEventListener('input', (e) => {
          if (e.target?.getAttribute?.('data-automation-id') === 'beecatcher' && e.target.value) {
            window.__reportHoneypot();
          }
        }, true);
      });
    },
  });
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdayResult.status, 'ACCOUNT_CREATED');
  assert.equal(filledHoneypot, false);
  fs.rmSync(root, { recursive: true, force: true });
});

test('account creation without explicit termsAuthorized touches nothing and reports TERMS_NOT_AUTHORIZED', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-no-terms-'));
  let filled = false;
  const plan = workdayPlan(root, {
    extraSteps: [identityStep(), credentialStep(), createAccountStep('workday-create-account', {})],
  });
  const instance = new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root, ...accountCreationResolvers(),
    onPageCreated: async (page) => {
      await page.route(`${URL}**`, (route) => route.fulfill({ contentType: 'text/html', body: CREATE_ACCOUNT_HTML }));
      await page.exposeFunction('__reportFill', () => { filled = true; });
      await page.addInitScript(() => {
        document.addEventListener('input', () => { window.__reportFill(); }, true);
        document.addEventListener('change', () => { window.__reportFill(); }, true);
      });
    },
  });
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdayResult.status, 'TERMS_NOT_AUTHORIZED');
  assert.equal(filled, false);
  fs.rmSync(root, { recursive: true, force: true });
});

test('an "already exists" response after submitting is reported as ACCOUNT_CREATE_FAILED', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-exists-'));
  const ALREADY_EXISTS_HTML = CREATE_ACCOUNT_HTML.replace('<h1>Create Account</h1>',
    '<h1>Create Account</h1><p>An account with this email address already exists</p>');
  const plan = workdayPlan(root, { extraSteps: [identityStep(), credentialStep(), createAccountStep()] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: ALREADY_EXISTS_HTML }),
    accountCreationResolvers());
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdayResult.status, 'ACCOUNT_CREATE_FAILED');
  assert.match(result.workdayResult.reason, /already exists/);
  fs.rmSync(root, { recursive: true, force: true });
});

test('a missing create-account form is reported as FORM_NOT_FOUND, not a hard stop', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-form-missing-'));
  const plan = workdayPlan(root, { extraSteps: [identityStep(), credentialStep(), createAccountStep()] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: SIGN_IN_HTML }),
    accountCreationResolvers());
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdayResult.status, 'FORM_NOT_FOUND');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a CAPTCHA present on the create-account page is a hard stop — nothing is filled', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-captcha-'));
  const html = `${CREATE_ACCOUNT_HTML.replace('</body>', '<div class="h-captcha" data-sitekey="abc"></div></body>')}`;
  const plan = workdayPlan(root, { extraSteps: [identityStep(), credentialStep(), createAccountStep()] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }),
    accountCreationResolvers());
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_CAPTCHA_PRESENT');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a worker that crashes after navigating resumes and completes the account-creation attempt', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-create-account-crash-'));
  const plan = workdayPlan(root, { extraSteps: [identityStep(), credentialStep(), createAccountStep()] });
  const crashing = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: CREATE_ACCOUNT_HTML }),
    accountCreationResolvers());
  await assert.rejects(() => crashing.execute(plan, { failAfterStep: 'navigate-application' }));
  let requestCount = 0;
  const resumed = routedWorker(root, (route) => {
    requestCount += 1;
    route.fulfill({ contentType: 'text/html', body: requestCount === 1 ? CREATE_ACCOUNT_HTML : ACCOUNT_CREATED_HTML });
  }, accountCreationResolvers());
  const result = await resumed.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdayResult.status, 'ACCOUNT_CREATED');
  fs.rmSync(root, { recursive: true, force: true });
});

// ── Live (routed, zero-real-network) browser execution ──────────────────

const WELCOME_HTML = '<!doctype html><html><body><h1>Welcome back</h1></body></html>';

test('a correct saved credential signs in successfully, never touching the honeypot field', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-login-ok-'));
  let filledHoneypot = false;
  let requestCount = 0;
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep()] });
  const credentialResolver = async () => ({ username: 'ada@example.test', password: 'correct-horse-battery-staple' });
  const instance = new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root, credentialResolver,
    onPageCreated: async (page) => {
      // First load shows the sign-in form; the form's own submit navigation
      // (a real browser navigation, intercepted here) is answered with the
      // post-sign-in page — mirroring how a real successful Workday sign-in
      // replaces the sign-in page rather than leaving its fields in place.
      await page.route(`${URL}**`, (route) => {
        requestCount += 1;
        route.fulfill({ contentType: 'text/html', body: requestCount === 1 ? SIGN_IN_HTML : WELCOME_HTML });
      });
      await page.exposeFunction('__reportHoneypot', () => { filledHoneypot = true; });
      await page.addInitScript(() => {
        document.addEventListener('input', (e) => {
          if (e.target?.getAttribute?.('data-automation-id') === 'beecatcher' && e.target.value) {
            window.__reportHoneypot();
          }
        }, true);
      });
    },
  });
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdayResult.status, 'LOGIN_SUCCEEDED');
  assert.equal(filledHoneypot, false);
  fs.rmSync(root, { recursive: true, force: true });
});

test('a sign-in form still present after submitting is reported as a genuine login failure', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-login-wrong-'));
  // Submitting does nothing (no navigation / DOM change) in this fixture —
  // the sign-in fields remain, exactly like a rejected real password would.
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep()] });
  const credentialResolver = async () => ({ username: 'ada@example.test', password: 'wrong-password' });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: SIGN_IN_HTML }), { credentialResolver });
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdayResult.status, 'LOGIN_FAILED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a credential the resolver cannot find pauses safely for human action — never fabricated, never signed in', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-no-credential-value-'));
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep()] });
  const credentialResolver = async () => { throw new Error('EMPLOYER_CREDENTIAL_NOT_FOUND:404'); };
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: SIGN_IN_HTML }), { credentialResolver });
  await assert.rejects(() => instance.execute(plan), (error) => error.name === 'ApprovalRequiredError');
  // Resolver failure pauses for human approval (ApprovalRequiredError) rather
  // than proceeding — proven by the plan never reaching COMPLETED and never
  // reporting a fabricated login result.
  const state = instance.store.get(plan.planId);
  assert.notEqual(state.status, 'COMPLETED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('MFA required after submitting is a hard stop, not a retryable uncertain result', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-mfa-'));
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep()] });
  const credentialResolver = async () => ({ username: 'ada@example.test', password: 'correct-horse-battery-staple' });
  let requestCount = 0;
  const instance = new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root, credentialResolver,
    onPageCreated: async (page) => {
      await page.route(`${URL}**`, (route) => {
        requestCount += 1;
        const body = requestCount === 1 ? SIGN_IN_HTML
          : '<!doctype html><html><body><p>Enter the verification code sent to your authenticator app</p></body></html>';
        route.fulfill({ contentType: 'text/html', body });
      });
    },
  });
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_MFA_REQUIRED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a CAPTCHA present before any interaction is a hard stop — nothing is filled', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-captcha-'));
  const html = `${SIGN_IN_HTML.replace('</body>', '<div class="h-captcha" data-sitekey="abc"></div></body>')}`;
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep()] });
  const credentialResolver = async () => ({ username: 'ada@example.test', password: 'x' });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }), { credentialResolver });
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKDAY_CAPTCHA_PRESENT');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a worker that crashes after navigating resumes without re-navigating and completes normally', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-crash-resume-'));
  const plan = workdayPlan(root, { extraSteps: [credentialStep(), loginStep()] });
  const credentialResolver = async () => ({ username: 'ada@example.test', password: 'correct-horse-battery-staple' });
  const crashing = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: SIGN_IN_HTML }), { credentialResolver });
  await assert.rejects(() => crashing.execute(plan, { failAfterStep: 'navigate-application' }));
  let requestCount = 0;
  const resumed = routedWorker(root, (route) => {
    requestCount += 1;
    route.fulfill({ contentType: 'text/html', body: requestCount === 1 ? SIGN_IN_HTML : WELCOME_HTML });
  }, { credentialResolver });
  const result = await resumed.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdayResult.status, 'LOGIN_SUCCEEDED');
  fs.rmSync(root, { recursive: true, force: true });
});


// ── Phase 19: Workday Application Workflow Tests ────────────────────

test('classifyWorkdayField classifies platform-wide automation IDs accurately', () => {
  assert.equal(classifyWorkdayField({ automationId: 'legalNameSection_firstName' }), WorkdayFieldCategory.FIRST_NAME);
  assert.equal(classifyWorkdayField({ automationId: 'legalNameSection_lastName' }), WorkdayFieldCategory.LAST_NAME);
  assert.equal(classifyWorkdayField({ automationId: 'email' }), WorkdayFieldCategory.EMAIL);
  assert.equal(classifyWorkdayField({ automationId: 'phone-number' }), WorkdayFieldCategory.PHONE);
  assert.equal(classifyWorkdayField({ automationId: 'addressSection_primaryAddress_addressLine1' }), WorkdayFieldCategory.ADDRESS_LINE1);
  assert.equal(classifyWorkdayField({ automationId: 'addressSection_primaryAddress_city' }), WorkdayFieldCategory.CITY);
  assert.equal(classifyWorkdayField({ automationId: 'addressSection_primaryAddress_postalCode' }), WorkdayFieldCategory.POSTAL_CODE);
  assert.equal(classifyWorkdayField({ automationId: 'addressSection_primaryAddress_country' }), WorkdayFieldCategory.COUNTRY);
  assert.equal(classifyWorkdayField({ automationId: 'linkedinQuestion' }), WorkdayFieldCategory.LINKEDIN);
});

test('classifyWorkdayField strictly protects anti-bot honeypot elements', () => {
  assert.equal(isWorkdayHoneypot({ automationId: 'beecatcher' }), true);
  assert.equal(isWorkdayHoneypot({ name: 'website' }), true);
  assert.equal(isWorkdayHoneypot({ automationId: 'legalNameSection_firstName' }), false);
  assert.equal(classifyWorkdayField({ automationId: 'beecatcher' }), WorkdayFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyWorkdayField({ name: 'website' }), WorkdayFieldCategory.REQUIRES_HUMAN);
});

test('classifyWorkdayField marks checkboxes, radios, and captchas as non-auto-fillable', () => {
  assert.equal(classifyWorkdayField({ type: 'checkbox', label: 'I agree to the terms' }), WorkdayFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyWorkdayField({ type: 'radio', label: 'Sponsorship required' }), WorkdayFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyWorkdayField({ automationId: 'recaptcha-widget' }), WorkdayFieldCategory.CAPTCHA);
});

test('planWorkdayFormActions auto-fills verified candidate identity and detects missing gaps', () => {
  const fields = [
    { automationId: 'legalNameSection_firstName', required: true },
    { automationId: 'legalNameSection_lastName', required: true },
    { automationId: 'email', required: true },
    { automationId: 'phone-number', required: false },
    { automationId: 'addressSection_primaryAddress_addressLine1', required: true },
    { automationId: 'custom_salary_expectation', required: true, label: 'Expected Salary' },
    { automationId: 'beecatcher', required: false },
  ];
  const candidate = {
    firstName: 'Alan',
    lastName: 'Turing',
    email: 'alan@example.com',
    addressLine1: 'Bletchley Park',
  };
  const planned = planWorkdayFormActions(fields, candidate, { hasCvArtifact: true });
  assert.equal(planned.actions.length, 4);
  assert.ok(planned.filledCategories.includes(WorkdayFieldCategory.FIRST_NAME));
  assert.ok(planned.filledCategories.includes(WorkdayFieldCategory.LAST_NAME));
  assert.ok(planned.filledCategories.includes(WorkdayFieldCategory.EMAIL));
  assert.ok(planned.filledCategories.includes(WorkdayFieldCategory.ADDRESS_LINE1));
  assert.ok(!planned.actions.some((a) => a.automationId === 'beecatcher'));
  assert.equal(planned.requiredGaps.length, 1);
  assert.equal(planned.requiredGaps[0].category, WorkdayFieldCategory.REQUIRES_HUMAN);
});

test('detectWorkdayStep identifies wizard steps from text and button automation IDs', () => {
  assert.equal(detectWorkdayStep('Please enter your contact details', ['legalNameSection_firstName']), 'MY_INFORMATION');
  assert.equal(detectWorkdayStep('Upload your resume/CV', ['file-upload-input-file']), 'MY_EXPERIENCE');
  assert.equal(detectWorkdayStep('Review your application before submission', ['review-submit-button']), 'REVIEW');
});

test('classifyWorkdaySubmission classifies confirmed, validation error, and blocked outcomes', () => {
  assert.equal(classifyWorkdaySubmission({ pageText: 'Thank you for applying to Nvidia' }), WorkdaySubmissionCategory.CONFIRMED);
  assert.equal(classifyWorkdaySubmission({ currentUrl: 'https://nvidia.wd5.myworkdayjobs.com/apply/submitted' }), WorkdaySubmissionCategory.CONFIRMED);
  assert.equal(classifyWorkdaySubmission({ hasValidationErrors: true, pageText: 'Please correct the errors below' }), WorkdaySubmissionCategory.VALIDATION_ERROR);
  assert.equal(classifyWorkdaySubmission({ pageText: 'Security check: please solve this CAPTCHA' }), WorkdaySubmissionCategory.ANTI_BOT);
  assert.equal(classifyWorkdaySubmission({ pageText: 'Review and Submit your application' }), WorkdaySubmissionCategory.FORM_STILL_PRESENT);
});

test('a WORKDAY plan can carry WORKDAY_APPLY and guarded WORKDAY_SUBMIT_CLICK steps in correct order', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-plan-valid-'));
  const plan = workdayPlan(root, {
    extraSteps: [
      { id: 'workday-apply', type: StepType.WORKDAY_APPLY, policy: 'AUTO', params: {} },
      { id: 'workday-submit-click', type: StepType.WORKDAY_SUBMIT_CLICK, policy: 'AUTO', params: {} },
    ],
  });
  let count = 0;
  const instance = routedWorker(root, (route) => {
    count += 1;
    if (count === 1) {
      route.fulfill({ contentType: 'text/html', body: REVIEW_SUBMIT_HTML });
    } else {
      route.fulfill({ contentType: 'text/html', body: CONFIRMATION_HTML });
    }
  });
  const res = await instance.execute(plan);
  assert.equal(res.status, 'COMPLETED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('WORKDAY_SUBMIT_CLICK without preceding WORKDAY_APPLY is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-plan-no-apply-'));
  const plan = workdayPlan(root, {
    extraSteps: [
      { id: 'workday-submit-click', type: StepType.WORKDAY_SUBMIT_CLICK, policy: 'AUTO', params: {} },
    ],
  });
  return assert.rejects(() => worker(root).execute(plan), (err) => err instanceof HardStopError
    && err.reason === 'WORKDAY_SUBMIT_WITHOUT_APPLY_STEP');
});

test('WORKDAY_APPLY conflicting with WORKDAY_LOGIN in the same plan is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-plan-conflict-'));
  const plan = workdayPlan(root, {
    extraSteps: [
      credentialStep(),
      loginStep(),
      { id: 'workday-apply', type: StepType.WORKDAY_APPLY, policy: 'AUTO', params: {} },
    ],
  });
  return assert.rejects(() => worker(root).execute(plan), (err) => err instanceof HardStopError
    && err.reason === 'WORKDAY_APPLY_AND_READINESS_STEP_CONFLICT');
});

const APPLICATION_FORM_HTML = `<!doctype html><html><body>
  <h1>My Information</h1>
  <form action="${URL}" method="post">
    <input type="text" data-automation-id="legalNameSection_firstName" id="fn">
    <input type="text" data-automation-id="legalNameSection_lastName" id="ln">
    <input type="text" data-automation-id="email" id="em">
    <input type="text" data-automation-id="phone-number" id="ph">
    <input type="text" data-automation-id="addressSection_primaryAddress_addressLine1" id="addr">
    <input type="file" data-automation-id="file-upload-input-file" accept=".pdf">
    <button type="button" data-automation-id="bottom-navigation-next-button">Next</button>
    <input type="text" name="website" data-automation-id="beecatcher" style="display:none">
  </form>
</body></html>`;

test('WORKDAY_APPLY fills candidate identity fields, attaches CV, and never touches honeypot', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-apply-exec-'));
  const cvPath = path.join(root, 'cv.pdf');
  fs.writeFileSync(cvPath, 'sample cv pdf content');
  const cvSha = crypto.createHash('sha256').update(fs.readFileSync(cvPath)).digest('hex');

  const plan = workdayPlan(root, {
    extraSteps: [
      {
        id: 'workday-apply',
        type: StepType.WORKDAY_APPLY,
        policy: 'AUTO',
        params: {
          identity: {
            firstName: 'Ada',
            lastName: 'Lovelace',
            email: 'ada@example.test',
            phone: '+44 7700 900077',
            addressLine1: '12 St James Square',
          },
        },
      },
    ],
  });
  plan.package = {
    applicationId: 'app-1',
    jobId: 'job-1',
    cv: {
      applicationId: 'app-1',
      jobId: 'job-1',
      versionId: 'cv-v1',
      path: cvPath,
      sha256: cvSha,
    },
  };

  const instance = routedWorker(root, (route) => {
    route.fulfill({ contentType: 'text/html', body: APPLICATION_FORM_HTML });
  });

  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.ok(result.workdayResult.filledCategories.includes(WorkdayFieldCategory.FIRST_NAME));
  assert.ok(result.workdayResult.filledCategories.includes(WorkdayFieldCategory.LAST_NAME));
  assert.ok(result.workdayResult.filledCategories.includes(WorkdayFieldCategory.EMAIL));
  assert.ok(result.workdayResult.filledCategories.includes(WorkdayFieldCategory.PHONE));
  assert.ok(result.workdayResult.filledCategories.includes(WorkdayFieldCategory.ADDRESS_LINE1));
  assert.ok(result.workdayResult.filledCategories.includes(WorkdayFieldCategory.RESUME_UPLOAD));
  assert.equal(result.workdayResult.cvUploadState, WorkdayDocumentUploadState.ATTACHED);

  fs.rmSync(root, { recursive: true, force: true });
});

const REVIEW_SUBMIT_HTML = `<!doctype html><html><body>
  <h1>Review and Submit</h1>
  <form action="${URL}" method="get">
    <button type="submit" data-automation-id="review-submit-button">Submit Application</button>
  </form>
</body></html>`;

const CONFIRMATION_HTML = `<!doctype html><html><body>
  <h1>Thank you for applying!</h1>
  <p>Your application has been submitted successfully.</p>
</body></html>`;

test('WORKDAY_SUBMIT_CLICK clicks submit button and captures platform confirmation', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workday-submit-exec-'));
  const plan = workdayPlan(root, {
    extraSteps: [
      { id: 'workday-apply', type: StepType.WORKDAY_APPLY, policy: 'AUTO', params: {} },
      { id: 'workday-submit-click', type: StepType.WORKDAY_SUBMIT_CLICK, policy: 'AUTO', params: {} },
    ],
  });

  let loadCount = 0;
  const instance = routedWorker(root, (route) => {
    loadCount += 1;
    if (loadCount === 1) {
      route.fulfill({ contentType: 'text/html', body: REVIEW_SUBMIT_HTML });
    } else {
      route.fulfill({ contentType: 'text/html', body: CONFIRMATION_HTML });
    }
  });

  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.workdaySubmitResult.confirmed, true);
  assert.equal(result.workdaySubmitResult.category, WorkdaySubmissionCategory.CONFIRMED);

  fs.rmSync(root, { recursive: true, force: true });
});
