import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import { StateStore } from '../src/store.js';
import { StepType, greenhousePageIdentityMatches, greenhouseConfirmationPath, greenhouseConfirmationPageMatches } from '../src/plan.js';
import { computePlanOutcome } from '../src/outcome.js';

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

test('greenhouseConfirmationPath computes Greenhouse\'s own fixed redirect target from the job URL alone', () => {
  assert.equal(greenhouseConfirmationPath(URL), '/acme/jobs/42/confirmation');
  assert.equal(greenhouseConfirmationPath(`${URL}/`), '/acme/jobs/42/confirmation');
  assert.equal(greenhouseConfirmationPath('https://boards.greenhouse.io/acme'), null);
  assert.equal(greenhouseConfirmationPath('not-a-url'), null);
});

test('greenhouseConfirmationPageMatches requires the same safety shape as greenhousePageIdentityMatches', () => {
  const confirmationUrl = `${URL}/confirmation`;
  assert.equal(greenhouseConfirmationPageMatches(URL, confirmationUrl), true);
  assert.equal(greenhouseConfirmationPageMatches(URL, `${confirmationUrl}/`), true);
  // Wrong job id in the live URL — must not match this posting's confirmation.
  assert.equal(greenhouseConfirmationPageMatches(URL, confirmationUrl.replace('/42/', '/43/')), false);
  // Right path, wrong (attacker-controlled) host — a redirect can never satisfy this by path alone.
  assert.equal(greenhouseConfirmationPageMatches(URL, confirmationUrl.replace('boards.greenhouse.io', 'evil.example')), false);
  // Right path, downgraded scheme.
  assert.equal(greenhouseConfirmationPageMatches(URL, confirmationUrl.replace('https:', 'http:')), false);
  // Still on the landing page — never a match.
  assert.equal(greenhouseConfirmationPageMatches(URL, URL), false);
  // targetUrl itself isn't a recognised Greenhouse job-posting URL.
  assert.equal(greenhouseConfirmationPageMatches('https://example.test/', confirmationUrl), false);
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

test('a real click that performs the genuine submission request and mutates the DOM to the configured confirmation text reports SUBMITTED, never a fabricated fallback', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-real-click-confirm-'));
  const html = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" id="submit-btn">Submit</button>
    <script>
      document.getElementById('submit-btn').addEventListener('click', async () => {
        const res = await fetch(location.pathname, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ job_application: { id: 1 }, fingerprint: 'test-fingerprint' }),
        });
        if (res.ok) {
          document.body.innerHTML = '<main>Thanks for applying! Application submitted — ref GH-1.</main>';
        }
      });
    </script>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root, { expectedConfirmationText: 'Thanks for applying' });
  const instance = routedWorker(root, (route) => {
    if (route.request().method() === 'POST') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    return route.fulfill({ contentType: 'text/html', body: html });
  });
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, true);
  assert.equal(result.submitResult.category, 'CONFIRMED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('employer-configured confirmation text on the page, with no genuine submission request ever made, is NOT confirmed — text alone is never a bypass', async () => {
  // Same DOM mutation as a genuine success — document.body.innerHTML set
  // straight to the configured text — but with no fetch/POST at all. The
  // mandatory submission-evidence gate must still block confirmation.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-text-no-evidence-'));
  const html = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="document.body.innerHTML='<main>Thanks for applying! Application submitted — ref GH-1.</main>'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root, { expectedConfirmationText: 'Thanks for applying' });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.notEqual(result.submitResult.category, 'CONFIRMED');
  fs.rmSync(root, { recursive: true, force: true });
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

// Phase 1 blocker fix (verified Greenhouse confirmation): the platform-
// structural signal (confirmation URL + `.confirmation` DOM container) is no
// longer sufficient by itself — see confirmation.js's module comment and
// plan.js's greenhouseConfirmationPath for the live Greenhouse source
// evidence this is built from, including the exact submission-request
// contract read from `app/utils/submit_application.ts`: a `POST` to the job
// posting's own pathname, `Content-Type: application/json`, a body carrying
// a `job_application` key. REAL_SUBMIT_CLICK's confirmation now requires
// `browser_worker.js`'s `#captureGreenhouseSubmissionDuringClick` to have
// actually observed a 2xx response to THAT exact request, for THIS click,
// in addition to the URL+DOM pair — fixing a genuine false positive where a
// bare navigation (no real submission at all) could previously satisfy the
// structural signal on its own. These tests exercise that corrected,
// network-anchored signal directly, with no expectedConfirmationText
// configured, through a real Chromium page issuing a real `fetch()` that
// Playwright observes via `page.route()` interception — zero real network
// egress.

// A landing page whose Submit button performs the exact Greenhouse
// submission contract via a real `fetch()`, then — mirroring
// `application_form.tsx`'s own `if (response.ok)` gate exactly — only
// navigates to the confirmation route when that response was itself ok.
function greenhouseRealSubmissionLandingHtml() {
  return `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" id="submit-btn">Submit</button>
    <script>
      document.getElementById('submit-btn').addEventListener('click', async () => {
        const res = await fetch(location.pathname, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ job_application: { id: 1 }, fingerprint: 'test-fingerprint' }),
        });
        if (res.ok) {
          location.href = location.pathname + '/confirmation';
        }
      });
    </script>
  </body></html>`;
}

// Same contract-matching POST, but the navigation always happens regardless
// of the response status — simulating a bug (or an attacker-controlled
// employer page) that reaches the confirmation-looking UI even though the
// real submission was rejected.
function greenhouseAlwaysNavigatesLandingHtml() {
  return `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" id="submit-btn">Submit</button>
    <script>
      document.getElementById('submit-btn').addEventListener('click', async () => {
        await fetch(location.pathname, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ job_application: { id: 1 }, fingerprint: 'test-fingerprint' }),
        });
        location.href = location.pathname + '/confirmation';
      });
    </script>
  </body></html>`;
}

const DEFAULT_CONFIRMATION_HTML = '<!doctype html><html><body><div class="confirmation">Thanks for applying!</div></body></html>';

function greenhouseStructuralRoute({ submissionStatus = 200, landingHtml = greenhouseRealSubmissionLandingHtml(), confirmationHtml = DEFAULT_CONFIRMATION_HTML, onPost = null } = {}) {
  return (route) => {
    const request = route.request();
    if (request.method() === 'POST') {
      if (onPost) onPost(request);
      return route.fulfill({ status: submissionStatus, contentType: 'application/json', body: JSON.stringify({ ok: submissionStatus < 300 }) });
    }
    if (request.url().endsWith('/confirmation')) {
      return route.fulfill({ contentType: 'text/html', body: confirmationHtml });
    }
    return route.fulfill({ contentType: 'text/html', body: landingHtml });
  };
}

test('a verified 2xx response to this attempt\'s own submission request, plus the matching confirmation URL and DOM container, reports SUBMITTED — with no confirmation text configured at all', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-structural-confirm-'));
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, greenhouseStructuralRoute({ submissionStatus: 200 }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, true);
  assert.equal(result.submitResult.category, 'CONFIRMED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a direct navigation to the confirmation URL with no submission request ever made remains unconfirmed — the false-positive this fix closes', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-direct-nav-no-submit-'));
  // The click navigates straight to the confirmation route and DOM — exactly
  // the old (vulnerable) test's shape — but issues no fetch/POST at all.
  // Fixed behaviour: URL+DOM alone must never be enough.
  const landingHtml = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="location.href=location.pathname + '/confirmation'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, greenhouseStructuralRoute({ landingHtml }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.equal(result.submitResult.category, 'AMBIGUOUS_NO_CONFIRMATION_CONFIGURED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a failed/rejected submission response is never confirmed, even when a confirmation-looking page appears anyway', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-rejected-submission-'));
  // The real contract-matching POST is made, but the server rejects it
  // (422); the page still (buggily, or maliciously) navigates to the
  // confirmation URL+container regardless. Must stay unconfirmed.
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, greenhouseStructuralRoute({
    submissionStatus: 422, landingHtml: greenhouseAlwaysNavigatesLandingHtml(),
  }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.equal(result.submitResult.category, 'AMBIGUOUS_NO_CONFIRMATION_CONFIGURED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('an unrelated successful POST cannot confirm the application — only the exact submission contract counts', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-unrelated-post-'));
  // A different, successful POST (no `job_application` key, different
  // intent — an analytics-style ping) happens during the click, then the
  // page still reaches the real confirmation URL+container. The unrelated
  // POST being a real 2xx must not substitute for the actual submission.
  const landingHtml = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" id="submit-btn">Submit</button>
    <script>
      document.getElementById('submit-btn').addEventListener('click', async () => {
        await fetch(location.pathname + '/analytics-ping', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ event: 'submit-clicked' }),
        });
        location.href = location.pathname + '/confirmation';
      });
    </script>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, greenhouseStructuralRoute({ landingHtml }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.equal(result.submitResult.category, 'AMBIGUOUS_NO_CONFIRMATION_CONFIGURED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('Test C — a previously confirmed outcome is restored after a restart, from its own persisted evidence, without clicking a second time', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-restore-confirmed-'));
  let postCount = 0;
  const countingPost = () => { postCount += 1; };

  // First process: the real click genuinely succeeds (verified submission
  // request + confirmation URL + DOM), gets durably persisted as COMPLETED
  // together with its own classification, and then the process crashes
  // (failAfterStep) before it can report that outcome onward — the same
  // simulated-crash technique e2e.test.js already uses for its own
  // crash/restart test.
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, greenhouseStructuralRoute({ submissionStatus: 200, onPost: countingPost }));
  await assert.rejects(
    () => instance.execute(plan, { approve: true, failAfterStep: 'real-submit-click' }),
    /SIMULATED_WORKER_CRASH_AFTER:real-submit-click/,
  );
  const persisted = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[plan.planId];
  assert.equal(persisted.steps['real-submit-click'].status, 'COMPLETED', 'the real click\'s own durable record must survive the crash');
  assert.equal(persisted.steps['real-submit-click'].submitResult?.confirmed, true, 'the genuine confirmation must itself be part of that durable record');
  assert.notEqual(persisted.status, 'COMPLETED', 'the crash must land before the overall plan is marked complete');
  assert.equal(postCount, 1, 'exactly one real submission request was made by the first process');

  // Second process (the "restart"): a brand-new BrowserWorker instance, with
  // no in-memory classification of its own. The already-completed step is
  // skipped (never re-clicked — postCount must not increase), and its exact
  // persisted outcome is restored rather than lost.
  const restarted = routedWorker(root, greenhouseStructuralRoute({ submissionStatus: 200, onPost: countingPost }));
  const result = await restarted.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult?.confirmed, true, 'the restarted process must restore the genuine confirmation, not discard it');
  assert.equal(result.submitResult.category, 'CONFIRMED');
  assert.equal(postCount, 1, 'the restarted process must never click (and therefore never re-submit) a second time');
  fs.rmSync(root, { recursive: true, force: true });
});

test('Test D — a previously persisted ambiguous outcome is restored verbatim after a restart — never re-clicked, never upgraded to a fabricated success', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-restore-ambiguous-'));
  let postCount = 0;
  const countingPost = () => { postCount += 1; };

  // First process: the real submission request is made but rejected (422),
  // so the genuinely computed, durably persisted outcome is itself
  // unconfirmed — then the process crashes.
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, greenhouseStructuralRoute({
    submissionStatus: 422, landingHtml: greenhouseAlwaysNavigatesLandingHtml(), onPost: countingPost,
  }));
  await assert.rejects(
    () => instance.execute(plan, { approve: true, failAfterStep: 'real-submit-click' }),
    /SIMULATED_WORKER_CRASH_AFTER:real-submit-click/,
  );
  const persisted = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[plan.planId];
  assert.equal(persisted.steps['real-submit-click'].status, 'COMPLETED');
  assert.equal(persisted.steps['real-submit-click'].submitResult?.confirmed, false);
  assert.equal(postCount, 1);

  // Restart: the step is skipped (no second click — the rejected submission
  // is never retried blindly), and the exact same unconfirmed classification
  // is restored — never silently discarded to null, and never upgraded.
  const restarted = routedWorker(root, greenhouseStructuralRoute({
    submissionStatus: 422, landingHtml: greenhouseAlwaysNavigatesLandingHtml(), onPost: countingPost,
  }));
  const result = await restarted.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult?.confirmed, false);
  assert.equal(result.submitResult.category, persisted.steps['real-submit-click'].submitResult.category);
  assert.equal(postCount, 1, 'an ambiguous/rejected outcome must never be silently retried with a second click');
  fs.rmSync(root, { recursive: true, force: true });
});

// Test A (mandatory restart test): a crash after the real click genuinely
// fired and was classified in memory, but before that outcome is ever
// durably persisted. The durable ATTEMPTING marker (written BEFORE the
// click — see #runRealSubmitClick) is what survives this crash; restarting
// must never click a second time just because the final outcome never made
// it to disk.
test('Test A — crash after a potentially successful submission but before the outcome is saved: restart never clicks again, outcome stays ambiguous and requires human review', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-crash-before-persist-'));
  let postCount = 0;
  const countingPost = () => { postCount += 1; };

  // First process: the real click genuinely fires (the submission request
  // is made exactly once and a confirmed outcome is computed in memory),
  // but the process crashes in the gap between that computation and ever
  // durably writing it.
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, greenhouseStructuralRoute({ submissionStatus: 200, onPost: countingPost }));
  await assert.rejects(
    () => instance.execute(plan, { approve: true, failDuringStep: 'real-submit-click' }),
    /SIMULATED_WORKER_CRASH_DURING:real-submit-click/,
  );
  assert.equal(instance.lastSubmitResult?.confirmed, true, 'the in-memory computation itself was genuinely confirmed');
  assert.equal(postCount, 1, 'the intercepted submission request was made exactly once');
  const persisted = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[plan.planId];
  assert.equal(persisted.steps['real-submit-click'].status, 'ATTEMPTING',
    'the durable pre-click marker must survive the crash even though the final outcome never did');
  assert.notEqual(persisted.status, 'COMPLETED');

  // Second process (the actual restart, same state store): must not issue
  // another submission request, and must report an ambiguous, human-review
  // outcome rather than either a fabricated success or a silent retry.
  const restarted = routedWorker(root, greenhouseStructuralRoute({ submissionStatus: 200, onPost: countingPost }));
  const result = await restarted.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(postCount, 1, 'a restart must never issue a second submission request for an attempt whose outcome is unverified');
  assert.equal(result.submitResult?.confirmed, false);
  assert.equal(result.submitResult?.category, 'PRIOR_ATTEMPT_UNVERIFIED');

  const { outcome, result: outcomeResult } = computePlanOutcome(plan, result);
  assert.equal(outcome, 'RECONCILIATION_REQUIRED');
  assert.equal(outcomeResult[0].type, 'SUBMISSION_RECONCILIATION');
  assert.equal(outcomeResult[0].category, 'PRIOR_ATTEMPT_UNVERIFIED');
  fs.rmSync(root, { recursive: true, force: true });
});

// Test B (mandatory restart test): a crash after the durable ATTEMPTING
// marker is written but BEFORE the real external click is ever dispatched.
// From a restarted process's point of view this is indistinguishable from
// Test A — the durable record is identical (ATTEMPTING, never COMPLETED) —
// which is exactly why the fix does not need to know which happened: it
// refuses to retry either way.
test('Test B — crash before the external request is actually sent: restart does not auto-retry an attempt with an uncertain execution status', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-crash-before-click-'));
  let postCount = 0;
  const countingPost = () => { postCount += 1; };

  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, greenhouseStructuralRoute({ submissionStatus: 200, onPost: countingPost }));
  await assert.rejects(
    () => instance.execute(plan, { approve: true, failAfterAttemptMarker: 'real-submit-click' }),
    /SIMULATED_WORKER_CRASH_BEFORE_CLICK:real-submit-click/,
  );
  assert.equal(postCount, 0, 'the crash landed before the click, so no submission request was ever made');
  const persisted = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[plan.planId];
  assert.equal(persisted.steps['real-submit-click'].status, 'ATTEMPTING');
  assert.notEqual(persisted.status, 'COMPLETED');

  // Restart: even though no request was ever actually sent, the restarted
  // process has no way to know that from the durable record alone — it must
  // treat this exactly like Test A rather than assuming it is safe to click
  // because "probably nothing happened yet".
  const restarted = routedWorker(root, greenhouseStructuralRoute({ submissionStatus: 200, onPost: countingPost }));
  const result = await restarted.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(postCount, 0, 'a restart must not automatically retry an attempt whose own execution status is uncertain');
  assert.equal(result.submitResult?.confirmed, false);
  assert.equal(result.submitResult?.category, 'PRIOR_ATTEMPT_UNVERIFIED');

  const { outcome, result: outcomeResult } = computePlanOutcome(plan, result);
  assert.equal(outcome, 'RECONCILIATION_REQUIRED');
  assert.equal(outcomeResult[0].type, 'SUBMISSION_RECONCILIATION');
  assert.equal(outcomeResult[0].category, 'PRIOR_ATTEMPT_UNVERIFIED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a misleading success-like generic page is never confirmed: positive-sounding text alone, off the confirmation route, stays unconfirmed', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-misleading-success-'));
  // The click navigates away from the form (so it is genuinely gone) to a
  // page on the SAME site that talks about success — but it is not
  // Greenhouse's own confirmation route and has no confirmation container.
  // Wording like "Success" must never substitute for the verified signal.
  const html = `<!doctype html><html><body><main>Success! Your request was received.</main></body></html>`;
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, (route) => {
    const reqUrl = route.request().url();
    if (reqUrl.includes('?thanks=1')) return route.fulfill({ contentType: 'text/html', body: html });
    return route.fulfill({ contentType: 'text/html', body: `<!doctype html><html><body>
      <main>Review your application</main>
      <button type="submit" onclick="location.href='${URL}?thanks=1'">Submit</button>
    </body></html>` });
  });
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.equal(result.submitResult.category, 'AMBIGUOUS_NO_CONFIRMATION_CONFIGURED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('landing on the confirmation URL without the confirmation container present is never confirmed (half the signal is not enough)', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-confirm-url-no-container-'));
  const landingHtml = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="location.href='${URL}/confirmation'">Submit</button>
  </body></html>`;
  // Right URL, but the container Greenhouse's own Confirmation component
  // always renders is missing — a malformed or spoofed page must not pass.
  const confirmationLikeHtml = `<!doctype html><html><body><main>You're all done!</main></body></html>`;
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, (route) => {
    const body = route.request().url().endsWith('/confirmation') ? confirmationLikeHtml : landingHtml;
    return route.fulfill({ contentType: 'text/html', body });
  });
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.equal(result.submitResult.category, 'AMBIGUOUS_NO_CONFIRMATION_CONFIGURED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a confirmation-class element appearing without navigating to the confirmation URL is never confirmed (the other half of the signal is not enough either)', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-container-no-url-'));
  // The click mutates the DOM to include the exact confirmation container,
  // but genuinely never navigates anywhere — simulating a spoofed or
  // same-page-injected container rather than a real server-driven redirect.
  const html = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="document.body.innerHTML='<div class=&quot;confirmation&quot;>Thanks!</div>'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.equal(result.submitResult.category, 'AMBIGUOUS_NO_CONFIRMATION_CONFIGURED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('an anti-bot block appearing only after the click is a hard stop, not a retryable uncertain result', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-antibot-after-click-'));
  const html = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="document.body.innerHTML='<main>Please complete the CAPTCHA to continue</main>'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan, { approve: true }), (error) => error instanceof HardStopError
    && error.reason === 'BLOCKED_ANTI_BOT');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a generic provider error page after the click is never mistaken for success', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'greenhouse-provider-error-'));
  // The form is genuinely gone (the error page replaced it), nothing matches
  // the anti-bot/login-wall patterns, and no confirmation signal is present —
  // this must resolve to the same honest "ambiguous" category as any other
  // disappeared-form-with-no-evidence case, never SUBMITTED.
  const errorHtml = `<!doctype html><html><body><main>500 Internal Server Error. Please try again later.</main></body></html>`;
  const landingHtml = `<!doctype html><html><body>
    <main>Review your application</main>
    <button type="submit" onclick="location.href='${URL}?error=1'">Submit</button>
  </body></html>`;
  const plan = greenhouseSubmitPlan(root);
  const instance = routedWorker(root, (route) => {
    const isErrorRequest = route.request().url().includes('?error=1');
    return route.fulfill({ contentType: 'text/html', body: isErrorRequest ? errorHtml : landingHtml, status: isErrorRequest ? 500 : 200 });
  });
  const result = await instance.execute(plan, { approve: true });
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.submitResult.confirmed, false);
  assert.equal(result.submitResult.category, 'AMBIGUOUS_NO_CONFIRMATION_CONFIGURED');
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
