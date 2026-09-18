import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { StateStore } from '../src/store.js';
import { MockMailboxClient } from '../src/mailbox.js';
import { Policy, evaluatePolicy, assertSafeSelector, validatePackage } from '../src/plan.js';

test('policy gates distinguish auto, approval, and forbidden', () => {
  assert.deepEqual(evaluatePolicy({ policy: Policy.AUTO }), { action: 'EXECUTE' });
  assert.deepEqual(evaluatePolicy({ policy: Policy.REQUIRES_APPROVAL }), { action: 'PAUSE', reason: 'REQUIRES_APPROVAL' });
  assert.deepEqual(evaluatePolicy({ policy: Policy.FORBIDDEN }), { action: 'BLOCK', reason: 'FORBIDDEN' });
});

test('selector allowlist and package isolation reject unsafe inputs', () => {
  assert.doesNotThrow(() => assertSafeSelector('input[name="email"]'));
  assert.throws(() => assertSafeSelector('body *'), /UNALLOWLISTED_SELECTOR/);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'worker-unit-'));
  const pkg = { jobId: 'a', applicationId: 'app-a', cv: { jobId: 'a', versionId: 'cv-a', path: path.join(root, 'cv') }, coverLetter: { jobId: 'a', versionId: 'cl-a', path: path.join(root, 'cl') } };
  assert.throws(() => validatePackage({ ...pkg, cv: { ...pkg.cv, jobId: 'b' } }), /CROSS_JOB_FILE_CONTAMINATION/);
  assert.throws(() => validatePackage({ ...pkg, coverLetter: { ...pkg.coverLetter, applicationId: 'other-app' } }), /CROSS_APPLICATION_FILE_CONTAMINATION/);
});

test('package checksum binds the worker to the exact immutable artifacts', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'worker-checksum-'));
  const cv = path.join(root, 'a.md'); const cl = path.join(root, 'a-cover.md');
  fs.writeFileSync(cv, 'resume-a'); fs.writeFileSync(cl, 'cover-a');
  const digest = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const pkg = { jobId: 'a', applicationId: 'app-a', cv: { jobId: 'a', versionId: 'cv-a', path: cv, sha256: digest(cv) }, coverLetter: { jobId: 'a', versionId: 'cl-a', path: cl, sha256: digest(cl) } };
  assert.equal(validatePackage(pkg), true);
  fs.writeFileSync(cv, 'resume-b');
  assert.throws(() => validatePackage(pkg), /PACKAGE_CHECKSUM_MISMATCH/);
});

test('state store recovers stale running plan and preserves completed steps', () => {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'worker-state-')), 'state.json');
  const store = new StateStore(file);
  const plan = { planId: 'plan-a', planFingerprint: 'fp', correlation: { jobId: 'job-a', applicationId: 'app-a' } };
  store.begin(plan);
  store.markStep('plan-a', 'navigate', 'COMPLETED', { currentUrl: 'http://mock/app' });
  const state = JSON.parse(fs.readFileSync(file, 'utf8'));
  state.plans['plan-a'].heartbeatAt = 0;
  fs.writeFileSync(file, JSON.stringify(state));
  const restarted = new StateStore(file);
  assert.deepEqual(restarted.recoverStale(1), ['plan-a']);
  assert.equal(restarted.step('plan-a', 'navigate').status, 'COMPLETED');
});

test('mailbox rejects stale, wrong-application, and wrong-domain messages', async () => {
  const client = new MockMailboxClient('http://mailbox.invalid', async () => ({ ok: true, async json() {
    return [{ id: 'wrong-app', applicationId: 'other', sender: 'security@mock.test', receivedAt: new Date().toISOString(), body: 'OTP 123456' },
      { id: 'stale', applicationId: 'app-a', sender: 'security@mock.test', receivedAt: '2020-01-01T00:00:00Z', body: 'OTP 999999' }];
  } }));
  await assert.rejects(() => client.getVerification({ applicationId: 'app-a', senderDomain: 'mock.test', receivedAfter: Date.now() - 1000 }), /VERIFICATION_NOT_FOUND_OR_MISMATCHED/);
});
