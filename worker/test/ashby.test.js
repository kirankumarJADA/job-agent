import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import { StepType, ashbyPageIdentityMatches } from '../src/plan.js';
import { classifyAshbyField, planAshbyFormActions, classifyAshbySubmission, AshbyFieldCategory, AshbyDocumentUploadState, AshbySubmissionCategory } from '../src/ashby.js';

const URL = 'https://jobs.ashbyhq.com/ramp/a85afad0-8051-4333-ae89-0f82321dea87';
const APPLICATION_URL = `${URL}/application`;
const hash = (content) => crypto.createHash('sha256').update(content).digest('hex');

// Mirrors the REAL, live-verified Ashby application form (Phase 14
// investigation, jobs.ashbyhq.com/ramp): each question wrapped in a stable
// `field-entry` container carrying `data-field-path`, a label with the
// `question-title` class, and the real platform-wide submit button class.
// `_systemfield_location`'s native `required` attribute is deliberately
// omitted here — live verification found Ashby's own combobox does not set
// it even though the rendered label carries a trailing `*`, the exact
// discrepancy `classifyAshbyField`'s required-detection must handle.
function fieldEntry(fieldPath, label, inputHtml) {
  return `<div class="_fieldEntry_x ashby-application-form-field-entry" data-field-path="${fieldPath}">
    <label class="_label_x ashby-application-form-question-title">${label}</label>
    <div>${inputHtml}</div>
  </div>`;
}

function applicationFormHtml({ withSubmitScript = false } = {}) {
  const fields = [
    fieldEntry('_systemfield_name', 'Legal Name', '<input type="text" required>'),
    fieldEntry('_systemfield_email', 'Email', '<input type="email" required>'),
    fieldEntry('11111111-1111-1111-1111-111111111111', 'Phone',
      '<input type="tel" required>'
      + '<input type="radio" name="communicationConsent" value="given">'
      + '<input type="radio" name="communicationConsent" value="notGiven">'),
    fieldEntry('_systemfield_location', 'Where do you plan on working from (for payroll tax purposes)?*', '<input type="text">'),
    fieldEntry('_systemfield_resume', 'Resume', '<input type="file" accept=".pdf,.doc,.docx" required>'),
    fieldEntry('22222222-2222-2222-2222-222222222222', 'LinkedIn Profile', '<input type="text" required>'),
  ].join('\n');
  const submitScript = withSubmitScript ? `
    <script>
      document.querySelector('.ashby-application-form-submit-button').addEventListener('click', async (e) => {
        e.preventDefault();
        const res = await fetch('https://api.ashbyhq.com/applicationForm.submit', {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
        });
        const body = await res.json();
        if (body.success && !(body.results && body.results.formMessages && body.results.formMessages.blocked)) {
          document.getElementById('application-form').remove();
        }
      });
    </script>` : '';
  return `<!doctype html><html><body>
    <form id="application-form">
      ${fields}
      <button class="ashby-application-form-submit-button" type="submit">Submit Application</button>
    </form>
    ${submitScript}
  </body></html>`;
}

function ashbyPlan(root, { extraSteps = [], pkg = null } = {}) {
  const steps = [
    { id: 'navigate-application', type: StepType.NAVIGATE, policy: 'AUTO', params: { url: APPLICATION_URL } },
    { id: 'screenshot-landing', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} },
    ...extraSteps,
  ];
  return {
    planId: 'plan-ashby', planType: 'ASHBY', version: 1,
    correlation: { applicationId: 'app-1', jobId: 'job-1' }, targetUrl: APPLICATION_URL,
    safetyContract: 'NO_SUBMIT', steps, package: pkg,
  };
}

function applyStep(identity = {}) {
  return { id: 'ashby-apply', type: StepType.ASHBY_APPLY, policy: 'AUTO', params: { identity } };
}

function submitStep() {
  return { id: 'ashby-submit-click', type: StepType.ASHBY_SUBMIT_CLICK, policy: 'AUTO', params: {} };
}

function worker(root) {
  return new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root });
}

/** @param apiHandler route handler for api.ashbyhq.com (the submit response), omit to leave it unmocked (never reached) */
function routedWorker(root, pageHandler, apiHandler = null, opts = {}) {
  return new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => {
      await page.route(`${APPLICATION_URL}**`, pageHandler);
      if (apiHandler) await page.route('https://api.ashbyhq.com/**', apiHandler);
    },
    ...opts,
  });
}

function jsonRoute(body, status = 200) {
  return (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

// ── Identity / selector safety (no browser) ─────────────────────────────

test('Ashby identity requires the exact jobs.ashbyhq.com host, allowing only its own /application sub-route', () => {
  assert.equal(ashbyPageIdentityMatches(URL, URL), true);
  assert.equal(ashbyPageIdentityMatches(URL, APPLICATION_URL), true);
  assert.equal(ashbyPageIdentityMatches(URL, 'https://evil.example.com/ramp/x'), false);
  assert.equal(ashbyPageIdentityMatches(URL, URL.replace('https://', 'http://')), false);
  assert.equal(ashbyPageIdentityMatches(URL, 'https://jobs.ashbyhq.com/other-board/x'), false);
});

// ── Pure classification (no browser) ─────────────────────────────────────

test('classifyAshbyField classifies stable system fields by their field-path, regardless of label wording', () => {
  assert.equal(classifyAshbyField('_systemfield_name', 'Legal Name', 'input', 'text'), AshbyFieldCategory.IDENTITY_NAME);
  assert.equal(classifyAshbyField('_systemfield_email', 'Email', 'input', 'email'), AshbyFieldCategory.IDENTITY_EMAIL);
  assert.equal(classifyAshbyField('_systemfield_resume', 'Resume', 'input', 'file'), AshbyFieldCategory.RESUME_UPLOAD);
  // The payroll-tax field is always human-required by its stable path, even
  // though this employer customized its visible label text — the exact
  // live-verified discrepancy this field exists to protect against.
  assert.equal(
    classifyAshbyField('_systemfield_location', 'Where do you plan on working from (for payroll tax purposes)?', 'input', 'text'),
    AshbyFieldCategory.REQUIRES_HUMAN,
  );
});

test('classifyAshbyField falls back to label text for per-posting custom questions (a random field-path)', () => {
  assert.equal(classifyAshbyField('11111111-1111-1111-1111-111111111111', 'Phone', 'input', 'tel'), AshbyFieldCategory.IDENTITY_PHONE);
  assert.equal(classifyAshbyField('22222222-2222-2222-2222-222222222222', 'LinkedIn Profile', 'input', 'text'), AshbyFieldCategory.IDENTITY_LINKEDIN);
  assert.equal(classifyAshbyField('33333333-3333-3333-3333-333333333333', 'Current Employer', 'input', 'text'), AshbyFieldCategory.REQUIRES_HUMAN);
});

test('classifyAshbyField never auto-selects a radio or checkbox, regardless of path or label', () => {
  assert.equal(classifyAshbyField('11111111-1111-1111-1111-111111111111', 'Phone', 'input', 'radio'), AshbyFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyAshbyField('_systemfield_name', 'Legal Name', 'input', 'checkbox'), AshbyFieldCategory.REQUIRES_HUMAN);
});

test('classifyAshbyField flags a differently-worded captcha label rather than missing it', () => {
  assert.equal(classifyAshbyField(null, 'Please complete the CAPTCHA below', 'div', null), AshbyFieldCategory.CAPTCHA);
});

test('planAshbyFormActions fills only matched identity fields and reports every other required field as a gap', () => {
  const fields = [
    { index: 0, fieldPath: '_systemfield_name', rawLabel: 'Legal Name', tag: 'input', type: 'text', required: true },
    { index: 1, fieldPath: '_systemfield_email', rawLabel: 'Email', tag: 'input', type: 'email', required: true },
    { index: 2, fieldPath: '11111111-1111-1111-1111-111111111111', rawLabel: 'Phone', tag: 'input', type: 'tel', required: true },
    { index: 3, fieldPath: '_systemfield_location', rawLabel: 'Where do you plan on working from (for payroll tax purposes)?*', tag: 'input', type: 'text', required: true },
  ];
  const result = planAshbyFormActions(fields, { name: 'Ada Lovelace', email: 'ada@example.test' });
  assert.equal(result.captchaPresent, false);
  assert.deepEqual(result.toFill.map((f) => f.index), [0, 1]);
  assert.deepEqual(result.requiredGaps.map((g) => g.index), [2, 3]);
});

test('planAshbyFormActions fills phone and LinkedIn when the candidate has them, never invents either', () => {
  const fields = [
    { index: 0, fieldPath: '11111111-1111-1111-1111-111111111111', rawLabel: 'Phone', tag: 'input', type: 'tel', required: true },
    { index: 1, fieldPath: '22222222-2222-2222-2222-222222222222', rawLabel: 'LinkedIn Profile', tag: 'input', type: 'text', required: true },
  ];
  const withBoth = planAshbyFormActions(fields, { phone: '+1 555 0100', linkedIn: 'https://linkedin.com/in/ada' });
  assert.deepEqual(withBoth.toFill, [
    { index: 0, value: '+1 555 0100', category: 'IDENTITY_PHONE' },
    { index: 1, value: 'https://linkedin.com/in/ada', category: 'IDENTITY_LINKEDIN' },
  ]);
  assert.deepEqual(withBoth.requiredGaps, []);
});

test('planAshbyFormActions fills nothing at all when a CAPTCHA is present anywhere on the form', () => {
  const fields = [
    { index: 0, fieldPath: '_systemfield_name', rawLabel: 'Legal Name', tag: 'input', type: 'text', required: true },
    { index: 1, fieldPath: null, rawLabel: 'CAPTCHA', tag: 'div', type: null, required: true },
  ];
  const result = planAshbyFormActions(fields, { name: 'Ada' });
  assert.equal(result.captchaPresent, true);
  assert.deepEqual(result.toFill, []);
});

test('classifyAshbySubmission requires both the documented success contract and the form being gone', () => {
  assert.equal(classifyAshbySubmission({ networkSuccess: true, networkBlocked: false, formFieldsPresent: false, antiBotPresent: false }).confirmed, true);
  assert.equal(classifyAshbySubmission({ networkSuccess: undefined, formFieldsPresent: false, antiBotPresent: false }).category, AshbySubmissionCategory.UNKNOWN);
  assert.equal(classifyAshbySubmission({ networkSuccess: false, formFieldsPresent: false, antiBotPresent: false }).category, AshbySubmissionCategory.SUBMISSION_REJECTED);
  assert.equal(classifyAshbySubmission({ networkSuccess: true, networkBlocked: true, formFieldsPresent: false, antiBotPresent: false }).category, AshbySubmissionCategory.BLOCKED);
  assert.equal(classifyAshbySubmission({ networkSuccess: true, networkBlocked: false, formFieldsPresent: true, antiBotPresent: false }).category, AshbySubmissionCategory.FORM_STILL_PRESENT);
  assert.equal(classifyAshbySubmission({ networkSuccess: true, formFieldsPresent: false, antiBotPresent: true }).category, AshbySubmissionCategory.ANTI_BOT);
  for (const result of [
    classifyAshbySubmission({ networkSuccess: true, networkBlocked: true, formFieldsPresent: false, antiBotPresent: false }),
    classifyAshbySubmission({ networkSuccess: false, formFieldsPresent: false, antiBotPresent: false }),
    classifyAshbySubmission({ networkSuccess: undefined, formFieldsPresent: false, antiBotPresent: false }),
  ]) {
    assert.equal(result.confirmed, false);
  }
});

// ── Plan safety ──────────────────────────────────────────────────────────

test('an ASHBY plan can never carry a REAL_SUBMIT_CLICK step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-forbidden-real-submit-'));
  const submit = { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO', params: { selector: 'button[type="submit"]' } };
  const plan = ashbyPlan(root, { extraSteps: [applyStep(), submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_ASHBY_SUBMIT_STEP');
});

test('an ASHBY plan can never carry a MOCK_SUBMIT step either', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-forbidden-mock-'));
  const submit = { id: 'mock-submit', type: StepType.MOCK_SUBMIT, policy: 'AUTO', params: { selector: 'button' } };
  const plan = ashbyPlan(root, { extraSteps: [applyStep(), submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_ASHBY_SUBMIT_STEP');
});

test('a step type outside the ASHBY allowlist is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-forbidden-step-'));
  const fill = { id: 'fill-something', type: StepType.FILL_FIELD, policy: 'AUTO', params: { selector: '#x', value: 'y' } };
  const plan = ashbyPlan(root, { extraSteps: [applyStep(), fill] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_ASHBY_STEP');
});

test('ASHBY_SUBMIT_CLICK must be the plan\'s last step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-not-last-'));
  const extra = { id: 'screenshot-extra', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} };
  const plan = ashbyPlan(root, { extraSteps: [applyStep(), submitStep(), extra] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ASHBY_SUBMIT_STEP_MUST_BE_LAST');
});

test('ASHBY_SUBMIT_CLICK without a preceding ASHBY_APPLY step is rejected — never signs off on an unprepared form', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-no-apply-'));
  const plan = ashbyPlan(root, { extraSteps: [submitStep()] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ASHBY_SUBMIT_WITHOUT_APPLY_STEP');
});

test('a duplicated ASHBY_SUBMIT_CLICK step is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-dup-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep(), submitStep(), { ...submitStep(), id: 'ashby-submit-click-2' }] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ASHBY_SUBMIT_STEP_DUPLICATED');
});

test('a plan whose navigation target is not an allowed Ashby host is rejected before any browser work', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-bad-target-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep()] });
  plan.steps[0].params.url = 'https://evil.example.com/ramp/x/application';
  await assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'INVALID_ASHBY_TARGET');
  fs.rmSync(root, { recursive: true, force: true });
});

// ── Live (routed, zero-real-network) ASHBY_APPLY execution ──────────────

test('a valid form fills identity fields, reports every other required field as a gap, never touches the radio', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-apply-ok-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada Lovelace', email: 'ada@example.test', phone: '+1 555 0100', linkedIn: 'https://linkedin.com/in/ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml() }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.ashbyResult.formFound, true);
  const filledCategories = result.ashbyResult.filled.map((f) => f.category);
  assert.deepEqual(new Set(filledCategories), new Set(['IDENTITY_NAME', 'IDENTITY_EMAIL', 'IDENTITY_PHONE', 'IDENTITY_LINKEDIN']));
  // The payroll-tax location question is always a gap, regardless of the candidate's own `location` not being supplied at all.
  assert.ok(result.ashbyResult.requiredGaps.some((g) => g.category === 'REQUIRES_HUMAN'));
  assert.equal(result.ashbyResult.resumeUploadState, AshbyDocumentUploadState.NO_ARTIFACT);
  fs.rmSync(root, { recursive: true, force: true });
});

test('a missing identity value is never guessed — the field is reported as a gap instead', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-apply-gap-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep({})] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml() }));
  const result = await instance.execute(plan);
  assert.deepEqual(result.ashbyResult.filled, []);
  const gapCategories = result.ashbyResult.requiredGaps.map((g) => g.category);
  assert.ok(gapCategories.includes('IDENTITY_NAME'));
  assert.ok(gapCategories.includes('IDENTITY_EMAIL'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('a CAPTCHA present on the form is a hard stop — nothing is filled', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-captcha-'));
  const html = applicationFormHtml().replace('</body>', '<div class="h-captcha" data-sitekey="abc"></div></body>');
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ASHBY_CAPTCHA_PRESENT');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a missing application form is reported as a genuine failure, never fabricated as success', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-form-not-found-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><body>Nothing here</body></html>' }));
  await assert.rejects(() => instance.execute(plan), (error) => String(error.message).includes('ASHBY_FORM_NOT_FOUND'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('a reviewed résumé is uploaded only once its artifact correlation, type and checksum all verify', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-resume-'));
  const cvPath = path.join(root, 'cv.pdf');
  fs.writeFileSync(cvPath, 'reviewed cv bytes');
  const pkg = { cv: { path: cvPath, versionId: 'v1', sha256: hash('reviewed cv bytes'), jobId: 'job-1', applicationId: 'app-1' } };
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' })], pkg });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml() }));
  const result = await instance.execute(plan);
  assert.equal(result.ashbyResult.resumeUploadState, AshbyDocumentUploadState.ATTACHED);
  fs.rmSync(root, { recursive: true, force: true });
});

test('résumé upload is refused when the file no longer matches its reviewed checksum', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-resume-bad-checksum-'));
  const cvPath = path.join(root, 'cv.pdf');
  fs.writeFileSync(cvPath, 'tampered bytes');
  const pkg = { cv: { path: cvPath, versionId: 'v1', sha256: hash('reviewed cv bytes'), jobId: 'job-1', applicationId: 'app-1' } };
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' })], pkg });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml() }));
  const result = await instance.execute(plan);
  assert.equal(result.ashbyResult.resumeUploadState, AshbyDocumentUploadState.REJECTED);
  fs.rmSync(root, { recursive: true, force: true });
});

test('résumé upload hard-stops on a cross-application artifact correlation mismatch', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-resume-mismatch-'));
  const cvPath = path.join(root, 'cv.pdf');
  fs.writeFileSync(cvPath, 'bytes');
  const pkg = { cv: { path: cvPath, versionId: 'v1', sha256: hash('bytes'), jobId: 'WRONG-job', applicationId: 'app-1' } };
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' })], pkg });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml() }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ASHBY_RESUME_CORRELATION_MISMATCH');
  fs.rmSync(root, { recursive: true, force: true });
});

// ── Live (routed, zero-real-network) ASHBY_SUBMIT_CLICK execution ────────

test('a confirmed submission requires Ashby\'s own documented success contract and the form disappearing', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-ok-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada', email: 'ada@example.test' }), submitStep()] });
  const instance = routedWorker(
    root,
    (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml({ withSubmitScript: true }) }),
    jsonRoute({ success: true, results: { formMessages: { blocked: false } } }),
  );
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.ashbySubmitResult.confirmed, true);
  assert.equal(result.ashbySubmitResult.category, AshbySubmissionCategory.CONFIRMED);
  fs.rmSync(root, { recursive: true, force: true });
});

test('a rejected submission (success:false) is never confirmed', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-rejected-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' }), submitStep()] });
  const instance = routedWorker(
    root,
    (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml({ withSubmitScript: true }) }),
    jsonRoute({ success: false, errors: [{ message: 'invalid' }] }),
  );
  const result = await instance.execute(plan);
  assert.equal(result.ashbySubmitResult.confirmed, false);
  assert.equal(result.ashbySubmitResult.category, AshbySubmissionCategory.SUBMISSION_REJECTED);
  fs.rmSync(root, { recursive: true, force: true });
});

test('a blocked submission (success:true, blocked:true) is never confirmed', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-blocked-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' }), submitStep()] });
  const instance = routedWorker(
    root,
    (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml({ withSubmitScript: true }) }),
    jsonRoute({ success: true, results: { formMessages: { blocked: true } } }),
  );
  const result = await instance.execute(plan);
  assert.equal(result.ashbySubmitResult.confirmed, false);
  assert.equal(result.ashbySubmitResult.category, AshbySubmissionCategory.BLOCKED);
  fs.rmSync(root, { recursive: true, force: true });
});

test('no matching submission response observed is reported as UNKNOWN, never a fabricated success', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-unknown-'));
  // The submit button has no script attached at all — clicking it does
  // nothing network-wise, so no api.ashbyhq.com response is ever observed.
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' }), submitStep()] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml() }));
  const result = await instance.execute(plan);
  assert.equal(result.ashbySubmitResult.confirmed, false);
  assert.equal(result.ashbySubmitResult.category, AshbySubmissionCategory.UNKNOWN);
  fs.rmSync(root, { recursive: true, force: true });
});

test('a CAPTCHA present before the submit click is a hard stop — nothing is clicked', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-captcha-'));
  const html = `${applicationFormHtml({ withSubmitScript: true }).replace('</body>', '<div class="h-captcha" data-sitekey="abc"></div></body>')}`;
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada' }), submitStep()] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }),
    jsonRoute({ success: true, results: { formMessages: { blocked: false } } }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ASHBY_CAPTCHA_PRESENT');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a crash immediately after the durable attempt marker is never retried automatically on resume', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-submit-crash-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada', email: 'ada@example.test' }), submitStep()] });
  const crashing = routedWorker(
    root,
    (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml({ withSubmitScript: true }) }),
    jsonRoute({ success: true, results: { formMessages: { blocked: false } } }),
    {},
  );
  await assert.rejects(() => crashing.execute(plan, { failAfterAttemptMarker: 'ashby-submit-click' }),
    (error) => String(error.message).startsWith('SIMULATED_WORKER_CRASH_BEFORE_CLICK'));

  const resumed = routedWorker(
    root,
    (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml({ withSubmitScript: true }) }),
    jsonRoute({ success: true, results: { formMessages: { blocked: false } } }),
  );
  const result = await resumed.execute(plan);
  // Never retried — the click never fires a second time; the result is the
  // honest "a previous attempt's outcome is unknown" classification, not a
  // fabricated confirmation and not a second real click.
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.ashbySubmitResult.confirmed, false);
  assert.equal(result.ashbySubmitResult.category, 'PRIOR_ATTEMPT_UNVERIFIED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a worker that crashes after navigating resumes without re-navigating and completes normally', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ashby-crash-resume-'));
  const plan = ashbyPlan(root, { extraSteps: [applyStep({ name: 'Ada', email: 'ada@example.test' })] });
  const crashing = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml() }));
  await assert.rejects(() => crashing.execute(plan, { failAfterStep: 'navigate-application' }));
  const resumed = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: applicationFormHtml() }));
  const result = await resumed.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.ok(result.ashbyResult.filled.some((f) => f.category === 'IDENTITY_NAME'));
  fs.rmSync(root, { recursive: true, force: true });
});
