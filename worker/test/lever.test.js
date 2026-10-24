import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import { StepType, leverPageIdentityMatches } from '../src/plan.js';
import { planLeverFormActions, LeverFieldAction } from '../src/lever.js';

const URL = 'https://jobs.lever.co/acme/6ed76ce8-4156-4b60-b120-403538bd66cd/apply';
const hash = (content) => crypto.createHash('sha256').update(content).digest('hex');

function field(over) {
  return { key: 'name', label: 'Name', htmlType: 'text', required: true, selector: '[name="name"]',
    options: [], classification: 'SUPPORTED_AUTO', value: 'Ada Lovelace', valueSource: null, reason: null, ...over };
}

function leverPlan(root, { fields = [], extraSteps = [], pkg = null } = {}) {
  const steps = [
    { id: 'navigate-application', type: StepType.NAVIGATE, policy: 'AUTO', params: { url: URL } },
    { id: 'screenshot-landing', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} },
    ...extraSteps,
  ];
  return {
    planId: 'plan-lever', planType: 'LEVER', version: 1,
    correlation: { applicationId: 'app-1', jobId: 'job-1' }, targetUrl: URL,
    safetyContract: 'NO_SUBMIT', steps, fields, requiredGaps: [], package: pkg,
  };
}

function applyStep(fields) {
  return { id: 'lever-apply', type: StepType.LEVER_APPLY, policy: 'AUTO', params: { fields } };
}

function worker(root) {
  return new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root });
}

function routedWorker(root, routeHandler) {
  return new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => { await page.route(`${URL}**`, routeHandler); },
  });
}

// ── Pure planning (no browser) ──────────────────────────────────────────

test('planLeverFormActions fills a supported text field with a verified value', () => {
  const { toFill, requiredGaps } = planLeverFormActions([field()]);
  assert.equal(toFill.length, 1);
  assert.equal(toFill[0].action, LeverFieldAction.FILL);
  assert.equal(toFill[0].value, 'Ada Lovelace');
  assert.equal(requiredGaps.length, 0);
});

test('planLeverFormActions reports a required field with no value as a gap, never fabricating one', () => {
  const { toFill, requiredGaps } = planLeverFormActions([
    field({ key: 'phone', label: 'Phone', classification: 'REQUIRES_HUMAN', value: '', required: true }),
  ]);
  assert.equal(toFill.length, 0);
  assert.equal(requiredGaps.length, 1);
  assert.equal(requiredGaps[0].key, 'phone');
});

test('planLeverFormActions never reports an optional field with no value as a gap', () => {
  const { toFill, requiredGaps } = planLeverFormActions([
    field({ key: 'org', label: 'Current company', classification: 'REQUIRES_HUMAN', value: '', required: false }),
  ]);
  assert.equal(toFill.length, 0);
  assert.equal(requiredGaps.length, 0);
});

test('planLeverFormActions selects a checkbox option only when the form actually offers it', () => {
  const checkboxField = field({
    key: 'cards[a69a985a-eae9-4c14-90fb-b5a4b891523e][field0]', label: 'Language Skill(s)',
    htmlType: 'checkbox', options: ['English (ENG)', 'Spanish (SPA)'], value: 'English (ENG)', required: true,
  });
  const { toFill, requiredGaps } = planLeverFormActions([checkboxField]);
  assert.equal(toFill.length, 1);
  assert.equal(toFill[0].action, LeverFieldAction.CHECK);
  assert.ok(toFill[0].selector.includes('[value="English (ENG)"]'));
  assert.equal(requiredGaps.length, 0);
});

test('planLeverFormActions reports a gap, never guesses, when the value does not match any real option', () => {
  const checkboxField = field({
    key: 'cards[a69a985a-eae9-4c14-90fb-b5a4b891523e][field0]', label: 'Language Skill(s)',
    htmlType: 'checkbox', options: ['English (ENG)', 'Spanish (SPA)'], value: 'Klingon', required: true,
  });
  const { toFill, requiredGaps } = planLeverFormActions([checkboxField]);
  assert.equal(toFill.length, 0);
  assert.equal(requiredGaps.length, 1);
});

test('planLeverFormActions never produces an action for the resume field — upload is handled separately', () => {
  const { toFill } = planLeverFormActions([field({ key: 'resume', label: 'Resume', htmlType: 'file', value: '', required: true })]);
  assert.equal(toFill.length, 0);
});

// ── Identity / plan-safety ───────────────────────────────────────────────

test('Lever identity requires an exact HTTPS jobs.lever.co/jobs.eu.lever.co host and path', () => {
  assert.equal(leverPageIdentityMatches(URL, URL), true);
  assert.equal(leverPageIdentityMatches(URL, 'https://evil.example.com/acme/x/apply'), false);
  assert.equal(leverPageIdentityMatches(URL, URL.replace('https://', 'http://')), false);
  assert.equal(leverPageIdentityMatches(URL, 'https://jobs.lever.co/other-org/x/apply'), false);
  assert.equal(leverPageIdentityMatches('https://jobs.eu.lever.co/acme/x/apply', 'https://jobs.eu.lever.co/acme/x/apply'), true);
});

test('a LEVER plan can never carry a REAL_SUBMIT_CLICK step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-forbidden-submit-'));
  const submit = { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO', params: { selector: 'button[type="submit"]' } };
  const plan = leverPlan(root, { extraSteps: [applyStep([]), submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_LEVER_SUBMIT_STEP');
});

test('a LEVER plan can never carry a MOCK_SUBMIT step either', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-forbidden-mock-submit-'));
  const submit = { id: 'mock-submit', type: StepType.MOCK_SUBMIT, policy: 'AUTO', params: { selector: 'button' } };
  const plan = leverPlan(root, { extraSteps: [applyStep([]), submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_LEVER_SUBMIT_STEP');
});

test('a step type outside the LEVER allowlist is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-forbidden-step-'));
  const fill = { id: 'fill-something', type: StepType.FILL_FIELD, policy: 'AUTO', params: { selector: '#x', value: 'y' } };
  const plan = leverPlan(root, { extraSteps: [fill] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_LEVER_STEP');
});

test('LEVER_APPLY must be the plan\'s last step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-apply-not-last-'));
  const extra = { id: 'screenshot-extra', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} };
  const plan = leverPlan(root, { extraSteps: [applyStep([]), extra] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'LEVER_APPLY_STEP_MUST_BE_LAST');
});

test('an unallowlisted selector is rejected even if somehow present in a LEVER field list', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-bad-selector-'));
  const html = '<!doctype html><html><body><input name="name"></body></html>';
  const malicious = field({ selector: 'input[onfocus="alert(1)"]' });
  const plan = leverPlan(root, { fields: [malicious], extraSteps: [applyStep([malicious])] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  return assert.rejects(() => instance.execute(plan), (error) => error instanceof Error
    && error.message.startsWith('UNALLOWLISTED_SELECTOR'));
});

// ── Live (routed, zero-real-network) browser execution ──────────────────

test('a valid form fills every backend-classified AUTO field and reports the rest honestly', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-valid-form-'));
  const html = `<!doctype html><html><body>
    <input name="name" required>
    <input name="email" type="email" required>
    <input name="phone" type="text">
    <textarea name="comments"></textarea>
  </body></html>`;
  const fields = [
    field({ key: 'name', selector: '[name="name"]', value: 'Ada Lovelace' }),
    field({ key: 'email', selector: '[name="email"]', htmlType: 'email', value: 'ada@example.test', label: 'Email' }),
    field({ key: 'phone', selector: '[name="phone"]', value: '', required: false, classification: 'REQUIRES_HUMAN', label: 'Phone' }),
    field({ key: 'comments', selector: '[name="comments"]', htmlType: 'textarea', value: 'Approved cover letter text.',
      classification: 'SUPPORTED_WITH_REVIEW', required: false, label: 'Comments' }),
  ];
  const plan = leverPlan(root, { fields, extraSteps: [applyStep(fields)] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  const lever = result.leverResult;
  assert.deepEqual(lever.filled.map((f) => f.key).sort(), ['comments', 'email', 'name']);
  assert.equal(lever.requiredGaps.length, 0);
  fs.rmSync(root, { recursive: true, force: true });
});

test('a value the live form rejects via its own validity constraint is reported as a gap, never claimed filled', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-invalid-value-'));
  const html = '<!doctype html><html><body><input name="email" type="email" required></body></html>';
  const fields = [field({ key: 'email', selector: '[name="email"]', htmlType: 'email', value: 'not-an-email', label: 'Email' })];
  const plan = leverPlan(root, { fields, extraSteps: [applyStep(fields)] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  const lever = result.leverResult;
  assert.equal(lever.filled.length, 0);
  assert.ok(lever.requiredGaps.some((g) => g.key === 'email'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('a résumé is uploaded only once its artifact correlation and checksum both verify', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-resume-upload-'));
  const file = path.join(root, 'cv.pdf');
  fs.writeFileSync(file, 'exact-cv-bytes');
  const digest = hash('exact-cv-bytes');
  const html = '<!doctype html><html><body><input name="name"><input type="file" name="resume"></body></html>';
  const fields = [field(), field({ key: 'resume', htmlType: 'file', selector: '[name="resume"]', value: '', label: 'Resume' })];
  const plan = leverPlan(root, {
    fields, extraSteps: [applyStep(fields)],
    pkg: { cv: { path: file, versionId: 'cv-v1', sha256: digest, jobId: 'job-1', applicationId: 'app-1' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.leverResult.resumeUploadState, 'ATTACHED');
  assert.ok(result.leverResult.filled.some((f) => f.key === 'resume'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('résumé upload hard-stops on a cross-application artifact correlation mismatch', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-resume-mismatch-'));
  const file = path.join(root, 'cv.pdf');
  fs.writeFileSync(file, 'bytes');
  const html = '<!doctype html><html><body><input type="file" name="resume"></body></html>';
  const fields = [field({ key: 'resume', htmlType: 'file', selector: '[name="resume"]', value: '', label: 'Resume' })];
  const plan = leverPlan(root, {
    fields, extraSteps: [applyStep(fields)],
    pkg: { cv: { path: file, versionId: 'cv-v1', sha256: hash('bytes'), jobId: 'WRONG_JOB', applicationId: 'app-1' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'LEVER_RESUME_CORRELATION_MISMATCH');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a CAPTCHA present on the form is a hard stop — nothing is filled', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-captcha-'));
  const html = `<!doctype html><html><body>
    <input name="name">
    <div class="h-captcha" data-sitekey="abc"></div>
  </body></html>`;
  const fields = [field()];
  const plan = leverPlan(root, { fields, extraSteps: [applyStep(fields)] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'LEVER_CAPTCHA_PRESENT');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a login wall is a hard stop, not a retryable uncertain result', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-login-wall-'));
  const html = '<!doctype html><html><body><p>Please sign in to continue</p></body></html>';
  const fields = [field()];
  const plan = leverPlan(root, { fields, extraSteps: [applyStep(fields)] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'LEVER_LOGIN_REQUIRED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a plan whose navigation target does not match an allowed Lever host is rejected before any browser work', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-redirect-'));
  const plan = leverPlan(root, { fields: [], extraSteps: [applyStep([])] });
  // Rewriting the navigation step's own target after plan construction is
  // caught by #validateLeverPlan's own target/navigation-identity check
  // before any browser work happens at all — mirrors the equivalent Zoho
  // Recruit and Greenhouse tests exactly.
  plan.steps[0].params.url = 'https://evil.example.com/acme/x/apply';
  const instance = worker(root);
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'INVALID_LEVER_TARGET');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a worker that crashes after navigating resumes without re-navigating and completes normally', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lever-crash-resume-'));
  const html = '<!doctype html><html><body><input name="name"></body></html>';
  const fields = [field()];
  const plan = leverPlan(root, { fields, extraSteps: [applyStep(fields)] });
  const crashing = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => crashing.execute(plan, { failAfterStep: 'navigate-application' }));
  const resumed = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await resumed.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.leverResult.filled.length, 1);
  fs.rmSync(root, { recursive: true, force: true });
});
