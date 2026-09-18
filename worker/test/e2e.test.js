import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { startMockEnvironment } from '../src/mock_environment.js';
import { BrowserWorker, ApprovalRequiredError, HardStopError } from '../src/browser_worker.js';
import { buildApplicationPlan, Policy, StepType } from '../src/plan.js';

function fixture(root, jobId, suffix) {
  const file = path.join(root, `${suffix}-${jobId}.txt`);
  fs.writeFileSync(file, `${suffix.toUpperCase()} FOR JOB ${jobId}`);
  return file;
}

function packageFor(root, jobId, applicationId, domain = 'mockemployer.test') {
  return {
    jobId, applicationId, accountId: `account-${applicationId}`,
    accountEmail: `candidate+${applicationId}@local.test`, accountPassword: 'local-only-password',
    companyDomain: domain,
    candidate: { fullName: 'Local Candidate', country: 'GB', workAuthorized: true, sponsorship: 'none' },
    answer: { text: 'I am interested based only on my verified engineering experience.' },
    cv: { jobId, versionId: `cv-${applicationId}`, applicationId, path: fixture(root, jobId, 'cv') },
    coverLetter: { jobId, versionId: `cl-${applicationId}`, applicationId, path: fixture(root, jobId, 'cover-letter') },
  };
}

async function setup() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jobagent-worker-'));
  const jobId = 'job-local-a';
  const applicationId = 'application-local-a';
  const mock = await startMockEnvironment({ jobId, applicationId });
  const pkg = packageFor(root, jobId, applicationId);
  const plan = buildApplicationPlan({ baseUrl: mock.baseUrl, mailboxUrl: mock.mailboxUrl, package: pkg });
  const worker = new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root, eventSink: (event) => fs.appendFileSync(path.join(root, 'events.jsonl'), `${JSON.stringify(event)}\n`) });
  return { root, mock, pkg, plan, worker };
}

test('complete safe browser flow executes against local mock employer', async () => {
  const env = await setup();
  try {
    const result = await env.worker.execute(env.plan, { approve: true, headless: true });
    assert.equal(result.status, 'COMPLETED');
    assert.deepEqual(env.mock.submission, {
      applicationId: env.pkg.applicationId,
      jobId: env.pkg.jobId,
      hasCv: true,
      hasCoverLetter: true,
      rawLength: env.mock.submission.rawLength,
    });
    assert.deepEqual(env.mock.events.map((x) => x.type), [
      'SIGNUP_COMPLETED', 'VERIFICATION_COMPLETED', 'LOGIN_COMPLETED',
      'APPLICATION_STEP_ONE_COMPLETED', 'APPLICATION_SUBMITTED',
    ]);
    const state = JSON.parse(fs.readFileSync(path.join(env.root, 'state.json'), 'utf8'));
    assert.equal(state.plans[env.plan.planId].status, 'COMPLETED');
    assert.equal(state.plans[env.plan.planId].verification.applicationId, env.pkg.applicationId);
    const workerEvents = state.plans[env.plan.planId].events.map((event) => event.type);
    assert.ok(workerEvents.includes('VERIFICATION_RECEIVED'));
    assert.ok(workerEvents.includes('APPLICATION_SUBMITTED'));
    assert.ok(workerEvents.includes('WORKER_COMPLETED'));
  } finally { await env.mock.close(); }
});

test('requires approval before mock submission and resumes without replaying earlier steps', async () => {
  const env = await setup();
  try {
    await assert.rejects(() => env.worker.execute(env.plan, { approve: false }), ApprovalRequiredError);
    assert.equal(env.mock.submission, null);
    assert.deepEqual(env.mock.events.map((x) => x.type), [
      'SIGNUP_COMPLETED', 'VERIFICATION_COMPLETED', 'LOGIN_COMPLETED', 'APPLICATION_STEP_ONE_COMPLETED',
    ]);
    const result = await env.worker.execute(env.plan, { approve: true });
    assert.equal(result.status, 'COMPLETED');
    assert.equal(env.mock.events.filter((x) => x.type === 'SIGNUP_COMPLETED').length, 1);
    assert.equal(env.mock.events.filter((x) => x.type === 'APPLICATION_SUBMITTED').length, 1);
  } finally { await env.mock.close(); }
});

test('crash and restart recover from durable completed steps', async () => {
  const env = await setup();
  try {
    await assert.rejects(() => env.worker.execute(env.plan, { approve: true, failAfterStep: 'application-step-one' }), /SIMULATED_WORKER_CRASH/);
    const restarted = new BrowserWorker({ statePath: path.join(env.root, 'state.json'), artifactDir: env.root });
    const result = await restarted.execute(env.plan, { approve: true });
    assert.equal(result.status, 'COMPLETED');
    assert.equal(env.mock.events.filter((x) => x.type === 'SIGNUP_COMPLETED').length, 1);
    assert.equal(env.mock.events.filter((x) => x.type === 'APPLICATION_STEP_ONE_COMPLETED').length, 1);
    assert.equal(env.mock.events.filter((x) => x.type === 'APPLICATION_SUBMITTED').length, 1);
  } finally { await env.mock.close(); }
});

test('cross-job package mismatch is blocked before browser launch', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jobagent-worker-negative-'));
  const bad = packageFor(root, 'job-a', 'application-a');
  bad.cv.jobId = 'job-b';
  assert.throws(() => buildApplicationPlan({ baseUrl: 'http://127.0.0.1', mailboxUrl: 'http://127.0.0.1', package: bad }), /CROSS_JOB_FILE_CONTAMINATION/);
});

test('forbidden real submission is a hard stop', async () => {
  const env = await setup();
  try {
    const forbidden = { ...env.plan, steps: env.plan.steps.map((step) => step.id === 'application-policy' ? { ...step, params: { action: 'REAL_SUBMIT' } } : step) };
    await assert.rejects(() => env.worker.execute(forbidden, { approve: true }), HardStopError);
    assert.equal(env.mock.submission, null);
  } finally { await env.mock.close(); }
});

test('wrong-company verification is rejected before continuation', async () => {
  const env = await setup();
  try {
    env.mock.injectMessage({ id: 'wrong-company', applicationId: 'other-application', sender: 'security@wrong.test', receivedAt: new Date().toISOString(), body: 'OTP 123456' });
    const bad = { ...env.plan, steps: env.plan.steps.map((step) => step.id === 'verification-email' ? { ...step, params: { ...step.params, senderDomain: 'other.test' } } : step) };
    await assert.rejects(() => env.worker.execute(bad, { approve: true }), /VERIFICATION_NOT_FOUND_OR_MISMATCHED/);
    assert.equal(env.mock.submission, null);
  } finally { await env.mock.close(); }
});

test('invalid OTP does not reach the verified terminal state', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jobagent-worker-otp-'));
  const jobId = 'job-otp';
  const applicationId = 'application-otp';
  const mock = await startMockEnvironment({ jobId, applicationId, otp: '654321', mailboxOtp: '999999' });
  const pkg = packageFor(root, jobId, applicationId);
  const plan = buildApplicationPlan({ baseUrl: mock.baseUrl, mailboxUrl: mock.mailboxUrl, package: pkg });
  const worker = new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root });
  try {
    await assert.rejects(() => worker.execute(plan, { approve: true }), /TERMINAL_STATE_NOT_REACHED:Mock Employer Login/);
    assert.equal(mock.submission, null);
  } finally { await mock.close(); }
});
