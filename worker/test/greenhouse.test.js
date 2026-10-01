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
