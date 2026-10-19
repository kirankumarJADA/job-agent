import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import { StateStore } from '../src/store.js';
import { StepType, greenhousePageIdentityMatches } from '../src/plan.js';

const URL = 'https://boards.greenhouse.io/acme/jobs/42';
const hash = (content) => crypto.createHash('sha256').update(content).digest('hex');

function greenhousePlan(root, { fields = [], gaps = [], extraSteps = [], packageFields = fields } = {}) {
  const steps = [
    { id: 'navigate-application', type: StepType.NAVIGATE, policy: 'AUTO', params: { url: URL } },
    { id: 'screenshot-landing', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} },
    ...extraSteps,
    { id: 'validate-form', type: StepType.VALIDATE, policy: 'AUTO', params: {} },
  ];
  return {
    planId: 'plan-gh', planType: 'GREENHOUSE', version: 1,
    correlation: { applicationId: 'app-1', jobId: 'job-1' }, targetUrl: URL,
    safetyContract: 'NO_SUBMIT', fields, requiredGaps: gaps, steps,
    package: { planId: 'plan-gh', applicationId: 'app-1', jobId: 'job-1', expectedUrl: URL,
      safetyContract: 'NO_SUBMIT', fields: packageFields, artifacts: [] },
  };
}

function field(key, { type = 'text', value = '', classification = 'REQUIRES_HUMAN', required = false,
  selector = `#${key}`, source = '', options = [] } = {}) {
  return { key, label: key, htmlType: type, required, selector, options, classification,
    valueSource: source, value, reason: '' };
}

function worker(root) {
  return new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root });
}

test('Greenhouse identity requires exact HTTPS Greenhouse board origin and path', () => {
  assert.equal(greenhousePageIdentityMatches(URL, URL), true);
  assert.equal(greenhousePageIdentityMatches(URL, URL.replace('boards.greenhouse.io', 'evil.example')), false);
  assert.equal(greenhousePageIdentityMatches(URL, URL.replace('https:', 'http:')), false);
  assert.equal(greenhousePageIdentityMatches(URL, URL.replace('/42', '/43')), false);
});

test('Greenhouse plan rejects a fill unsupported by the field classification', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-worker-'));
  const formField = field('email', { value: 'candidate@example.test', classification: 'SUPPORTED_AUTO', source: 'users.email' });
  const fill = { id: 'fill-email', type: StepType.FILL_FIELD, policy: 'AUTO', params: { selector: '#email', value: 'attacker@example.test' } };
  const plan = greenhousePlan(root, { fields: [formField], extraSteps: [fill] });
  await assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'GREENHOUSE_FIELD_NOT_VERIFIED');
});

test('Greenhouse upload is bound to the exact artifact version, job, application and digest', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-upload-'));
  const file = path.join(root, 'cv.pdf');
  fs.writeFileSync(file, 'exact-cv');
  const digest = hash('exact-cv');
  const cv = { versionId: 'cv-v1', sha256: digest, jobId: 'job-1', applicationId: 'app-1', path: file };
  const formField = field('resume', { type: 'file', classification: 'SUPPORTED_AUTO', source: 'cv_versions/files' });
  const upload = { id: 'upload-resume', type: StepType.UPLOAD_FILE, policy: 'AUTO', params: {
    selector: '#resume', artifact: 'cv', expectedJobId: 'job-1', expectedApplicationId: 'app-1',
    expectedVersionId: 'cv-wrong-version', sha256: digest,
  } };
  const plan = greenhousePlan(root, { fields: [formField], extraSteps: [upload] });
  plan.package.cv = cv;
  plan.package.artifacts = [{ kind: 'cv', versionId: 'cv-v1', sha256: digest, jobId: 'job-1', applicationId: 'app-1', path: file }];
  plan.package.fields = plan.fields;
  await assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'GREENHOUSE_UPLOAD_STEP_MISMATCH');
});

test('Greenhouse human review never transitions to submission and no submit step is allowed', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-review-'));
  const store = new StateStore(path.join(root, 'state.json'));
  assert.equal(store.get('missing'), undefined);
  const submit = { id: 'submit', type: StepType.CLICK, policy: 'AUTO', params: { selector: 'button[type="submit"]' } };
  const plan = greenhousePlan(root, { extraSteps: [submit] });
  const instance = worker(root);
  return assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_GREENHOUSE_STEP');
});

// Phase 4: the real-submission step. These first few tests exercise
// #validateGreenhousePlan's static rejection of a malformed shape only — the
// same boundary every other Greenhouse-specific test in this file checks,
// no browser needed. A well-formed plan's full execution — the real click,
// DOM-text confirmation, and the pre-click re-inspection that makes a
// crash-then-resume safe — previously had no browser-level test at all
// (VALIDATE and REAL_SUBMIT_CLICK both require an already-navigated live
// page, and greenhousePageIdentityMatches requires a real
// boards.greenhouse.io/job-boards.greenhouse.io hostname a local mock HTTP
// server cannot satisfy). That gap is closed further below (search for
// `onPageCreated`): a real Chromium page is driven through
// page.route()-intercepted, zero-real-network fixtures instead.

test('REAL_SUBMIT_CLICK is rejected unless it is the plan\'s last step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-submit-position-'));
  const submit = { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO',
    params: { selector: 'button[type="submit"]' } };
  // Appended BEFORE validate-form (via extraSteps), so validate-form ends up
  // last instead — exactly the misplacement a real attacker-controlled or
  // buggy caller might produce.
  const plan = greenhousePlan(root, { extraSteps: [submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'GREENHOUSE_SUBMIT_STEP_MISPLACED');
});

test('REAL_SUBMIT_CLICK is rejected when it does not immediately follow validate-form', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-submit-gap-'));
  const screenshot = { id: 'screenshot-extra', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} };
  const submit = { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO',
    params: { selector: 'button[type="submit"]' } };
  const plan = greenhousePlan(root, {});
  plan.steps.push(screenshot, submit); // validate-form, screenshot, real-submit-click — a gap
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'GREENHOUSE_SUBMIT_STEP_MISPLACED');
});

test('REAL_SUBMIT_CLICK with any selector other than the allow-listed submit button is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-submit-selector-'));
  const submit = { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO',
    params: { selector: '#some-other-button' } };
  const plan = greenhousePlan(root, {});
  plan.steps.push(submit);
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'GREENHOUSE_SUBMIT_SELECTOR_NOT_ALLOWED');
});

test('A REAL_SUBMIT_CLICK step is still forbidden everywhere a plain CLICK already was', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-submit-wrong-id-'));
  // Right type and position, wrong id — the id check is independent of position.
  const submit = { id: 'click-the-thing', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO',
    params: { selector: 'button[type="submit"]' } };
  const plan = greenhousePlan(root, {});
  plan.steps.push(submit);
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'GREENHOUSE_SUBMIT_STEP_MISPLACED');
});

// Phase 8.5 continuation (confirmation hardening): the same static-shape
// boundary above is unchanged, but these tests close the previously
// acknowledged "no test here drives a real browser" gap for exactly the
// REAL_SUBMIT_CLICK/confirmation path, using `onPageCreated` (a test-only
// seam, see browser_worker.js) to register `page.route()` interception
// BEFORE the plan's own NAVIGATE step runs — a real Chromium page genuinely
// navigates, clicks and inspects DOM state, but zero real network requests
// ever leave the machine and no real employer site is ever touched.
function greenhouseSubmitPlan(root, { expectedConfirmationText } = {}) {
  const submit = { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO',
    params: { selector: 'button[type="submit"]', expectedConfirmationText } };
  // greenhousePlan() appends its own 'validate-form' VALIDATE step last, so
  // passing `submit` as the (only) extra step yields [navigate, screenshot,
  // real-submit-click, validate-form] — wrong order. Build the steps
  // directly instead, placing real-submit-click immediately after (and
  // only after) validate-form, which is the one shape #validateGreenhousePlan
  // accepts.
  const plan = greenhousePlan(root, {});
  plan.steps.push(submit);
  return plan;
}

function routedWorker(root, routeHandler) {
  return new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => { await page.route(`${URL}**`, routeHandler); },
  });
}

test('a real click that mutates the DOM to the configured confirmation text reports SUBMITTED, never a fabricated fallback', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-real-click-confirm-'));
  const html = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="document.body.innerHTML='<main>Thanks for applying! Application submitted — ref GH-1.</main>'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root, { expectedConfirmationText: 'Thanks for applying' });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, true);
  assert.equal(result.submitResult.category, 'CONFIRMED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a page already showing the configured confirmation before the step runs is never re-clicked (crash-resume safety)', async () => {
  // Simulates a worker that crashed after a real click actually reached the
  // employer but before that result was persisted: the page genuinely is
  // the confirmation page already when this step's pre-click inspection
  // runs. If the implementation ever regressed to clicking again instead of
  // trusting the pre-click inspection, the onclick below would navigate to
  // a DIFFERENT job id, which greenhousePageIdentityMatches rejects — so a
  // regression here fails LOUD (the whole run rejects) rather than silently.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-no-reclick-'));
  const html = `<!doctype html><html><body>
    <main>Thanks for applying! Application submitted — ref GH-2.</main>
    <button type="submit" onclick="location.href='${URL.replace('/42', '/99-wrong-job')}'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root, { expectedConfirmationText: 'Thanks for applying' });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, true);
});

test('a click that leaves the application form still present is reported unconfirmed, never fabricated as SUBMITTED', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-still-present-'));
  // The required contact fields carry values (so VALIDATE's :invalid check
  // passes before the click) and the click handler intentionally does
  // nothing — simulating a submission that silently failed to go through
  // and left the candidate back on the same, still-filled-in form.
  const html = `<!doctype html><html><body>
    <input id="first_name" value="Ada">
    <input id="email" value="ada@example.test">
    <button type="submit" onclick="void 0">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root, { expectedConfirmationText: 'Thanks for applying' });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.equal(result.submitResult.category, 'FORM_STILL_PRESENT');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a login wall appearing only after the click is a hard stop, not a retryable uncertain result', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-login-after-click-'));
  const html = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="document.body.innerHTML='<main>Please sign in to continue</main>'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root, { expectedConfirmationText: 'Thanks for applying' });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan, { approve: true }), (error) => error instanceof HardStopError
    && error.reason === 'GREENHOUSE_LOGIN_REQUIRED_AFTER_SUBMIT');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a click whose resulting navigation never completes times out as a genuine failure, never a fabricated success', async () => {
  // Playwright's locator.click() auto-waits for a navigation the click
  // itself triggers, so a route that never responds makes the click call
  // time out and throw — proven here with page.setDefault(Navigation)Timeout
  // turned down so the test stays fast rather than waiting on the real 30s
  // default. That exception propagates exactly like any other step failure
  // (through #retry, then #runStep's caller) straight to a REJECTED run
  // (store.fail + WORKER_FAILED), never silently through to a confirmed or
  // completed result — there is no code path between a timed-out click and
  // `lastSubmitResult` being set at all.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-nav-timeout-'));
  const landingHtml = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="location.href='${URL}?next=hang'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root, { expectedConfirmationText: 'Thanks for applying' });
  const instance = new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => {
      page.setDefaultTimeout(300);
      page.setDefaultNavigationTimeout(300);
      await page.route(`${URL}**`, (route) => {
        if (route.request().url().includes('next=hang')) return; // never resolves — simulates a hang
        return route.fulfill({ contentType: 'text/html', body: landingHtml });
      });
    },
  });
  await assert.rejects(() => instance.execute(plan, { approve: true }), (error) => /Timeout.*exceeded/i.test(error.message));
  assert.equal(instance.lastSubmitResult, null, 'a timed-out click must never set a submit result at all');
  const state = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[plan.planId];
  assert.equal(state.status, 'FAILED');
  assert.ok(!state.steps['real-submit-click'] || state.steps['real-submit-click'].status !== 'COMPLETED',
    'the timed-out step must never be recorded as completed');
  fs.rmSync(root, { recursive: true, force: true });
});

test('A lease-loss abort stops the run at the next step boundary with a hard stop', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-lease-'));
  const fill = { id: 'fill-email', type: StepType.FILL_FIELD, policy: 'AUTO',
    params: { selector: '#email', value: 'candidate@example.test' } };
  const plan = greenhousePlan(root, {
    fields: [
      field('email', { value: 'candidate@example.test', classification: 'SUPPORTED_AUTO', source: 'users.email' }),
      field('phone', { value: '+44 20 7946 0000', classification: 'SUPPORTED_AUTO', source: 'profiles.phone' }),
    ],
    extraSteps: [fill, { id: 'fill-phone', type: StepType.FILL_FIELD, policy: 'AUTO',
      params: { selector: '#phone', value: '+44 20 7946 0000' } }],
  });
  const instance = worker(root);
  // Simulate the orchestrator losing its lease mid-run: the abort is observed
  // at the NEXT step boundary and raises the stable hard-stop reason.
  instance.requestAbort();
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'WORKER_LEASE_LOST');
});
