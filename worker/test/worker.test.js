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

// ── Robin x Tsenta hardening: worker.js's own materialization gate (§3) ───
//
// worker.js is a top-level script (it starts a real poll loop or idle
// keepalive interval as a side effect of being executed) — `isMainModule`
// in worker.js guards that dispatch so a plain `import` below is safe, it
// never starts a timer. Set the artifact dir BEFORE importing: `artifactDir`
// is computed once, at module-load time, from this env var.
const materializeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'worker-materialize-'));
process.env.WORKER_ARTIFACT_DIR = materializeRoot;
const { materializeArtifacts, zohoRecruitPackageNeedsMaterialization, ashbyPackageNeedsMaterialization } = await import('../src/worker.js');

const hash = (content) => crypto.createHash('sha256').update(content).digest('hex');

test.after(() => fs.rmSync(materializeRoot, { recursive: true, force: true }));

// ── zohoRecruitPackageNeedsMaterialization: the exact §3 bug and its fix ──

test('a cover-letter-only ZOHO_RECRUIT package needs materialization — the bug this replaced skipped it', () => {
  const plan = { planType: 'ZOHO_RECRUIT', package: { coverLetter: { versionId: 'v1' }, artifacts: [{ kind: 'coverLetter' }] } };
  assert.equal(zohoRecruitPackageNeedsMaterialization(plan), true);
});

test('a cv-only ZOHO_RECRUIT package still needs materialization (unchanged behaviour)', () => {
  const plan = { planType: 'ZOHO_RECRUIT', package: { cv: { versionId: 'v1' }, artifacts: [{ kind: 'cv' }] } };
  assert.equal(zohoRecruitPackageNeedsMaterialization(plan), true);
});

test('a ZOHO_RECRUIT package with both documents needs materialization', () => {
  const plan = { planType: 'ZOHO_RECRUIT', package: { cv: {}, coverLetter: {}, artifacts: [{ kind: 'cv' }, { kind: 'coverLetter' }] } };
  assert.equal(zohoRecruitPackageNeedsMaterialization(plan), true);
});

test('a ZOHO_RECRUIT plan with no package at all needs no materialization', () => {
  assert.equal(zohoRecruitPackageNeedsMaterialization({ planType: 'ZOHO_RECRUIT', package: undefined }), false);
});

test('a non-ZOHO_RECRUIT plan never triggers this path regardless of its package', () => {
  assert.equal(zohoRecruitPackageNeedsMaterialization({ planType: 'GREENHOUSE', package: { cv: {} } }), false);
});

// ── ashbyPackageNeedsMaterialization (Phase 14) ──────────────────────────

test('an ASHBY package with a cv needs materialization', () => {
  const plan = { planType: 'ASHBY', package: { cv: { versionId: 'v1' }, artifacts: [{ kind: 'cv' }] } };
  assert.equal(ashbyPackageNeedsMaterialization(plan), true);
});

test('an ASHBY plan with no package at all needs no materialization', () => {
  assert.equal(ashbyPackageNeedsMaterialization({ planType: 'ASHBY', package: undefined }), false);
});

test('a non-ASHBY plan never triggers the Ashby materialization path regardless of its package', () => {
  assert.equal(ashbyPackageNeedsMaterialization({ planType: 'ZOHO_RECRUIT', package: { cv: {} } }), false);
});

// ── materializeArtifacts: proves the cover-letter-only case actually downloads ──

function withMockedFetch(t, impl) {
  t.mock.method(global, 'fetch', impl);
}

test('materializeArtifacts downloads a cover-letter-only package and sets its local path', async (t) => {
  const bytes = Buffer.from('exact-cover-letter-bytes');
  const sha256 = hash(bytes);
  withMockedFetch(t, async () => new Response(bytes, { status: 200 }));

  const plan = {
    correlation: { applicationId: 'app-1' },
    package: {
      coverLetter: { versionId: 'cl-v1', url: '/api/v1/automation/plans/plan-1/artifacts/cover-letter?versionId=cl-v1', sha256 },
      artifacts: [{ kind: 'coverLetter', url: '/api/v1/automation/plans/plan-1/artifacts/cover-letter?versionId=cl-v1', versionId: 'cl-v1', sha256, fileName: 'cover-letter-cl-v1.txt' }],
    },
  };

  await materializeArtifacts(plan, 'http://backend.test');

  assert.ok(plan.package.coverLetter.path, 'the cover letter artifact must receive a local path once downloaded');
  assert.ok(fs.existsSync(plan.package.coverLetter.path));
  assert.equal(fs.readFileSync(plan.package.coverLetter.path, 'utf8'), 'exact-cover-letter-bytes');
  // No `cv` key was ever present on this package — proves this path never
  // depends on a CV artifact existing at all (the exact §3 regression).
  assert.equal(plan.package.cv, undefined);
});

test('materializeArtifacts rejects a cover-letter-only package whose downloaded bytes do not match the reviewed checksum', async (t) => {
  withMockedFetch(t, async () => new Response(Buffer.from('tampered-bytes'), { status: 200 }));

  const plan = {
    correlation: { applicationId: 'app-1' },
    package: {
      coverLetter: { versionId: 'cl-v1', url: '/api/v1/automation/plans/plan-1/artifacts/cover-letter?versionId=cl-v1', sha256: hash('original-bytes') },
      artifacts: [{ kind: 'coverLetter', url: '/api/v1/automation/plans/plan-1/artifacts/cover-letter?versionId=cl-v1', versionId: 'cl-v1', sha256: hash('original-bytes'), fileName: 'cover-letter-cl-v1.txt' }],
    },
  };

  await assert.rejects(() => materializeArtifacts(plan, 'http://backend.test'), /ARTIFACT_CHECKSUM_MISMATCH/);
});

test('materializeArtifacts rejects a cover-letter-only package whose artifact metadata does not match plan.package', async (t) => {
  withMockedFetch(t, async () => new Response(Buffer.from('exact-cover-letter-bytes'), { status: 200 }));

  const plan = {
    correlation: { applicationId: 'app-1' },
    package: {
      coverLetter: { versionId: 'cl-v1', url: '/api/v1/automation/plans/plan-1/artifacts/cover-letter?versionId=cl-v1', sha256: hash('exact-cover-letter-bytes') },
      // versionId deliberately does not match plan.package.coverLetter.versionId.
      artifacts: [{ kind: 'coverLetter', url: '/api/v1/automation/plans/plan-1/artifacts/cover-letter?versionId=cl-v1', versionId: 'cl-v2', sha256: hash('exact-cover-letter-bytes'), fileName: 'cover-letter-cl-v1.txt' }],
    },
  };

  await assert.rejects(() => materializeArtifacts(plan, 'http://backend.test'), /ARTIFACT_METADATA_MISMATCH/);
});

test('materializeArtifacts rejects a URL that does not stay on the automation-plans artifact path', async (t) => {
  withMockedFetch(t, async () => new Response(Buffer.from('exact-cover-letter-bytes'), { status: 200 }));

  const plan = {
    correlation: { applicationId: 'app-1' },
    package: {
      coverLetter: { versionId: 'cl-v1', url: '/evil/path', sha256: hash('exact-cover-letter-bytes') },
      artifacts: [{ kind: 'coverLetter', url: '/evil/path', versionId: 'cl-v1', sha256: hash('exact-cover-letter-bytes'), fileName: 'cover-letter-cl-v1.txt' }],
    },
  };

  await assert.rejects(() => materializeArtifacts(plan, 'http://backend.test'), /ARTIFACT_URL_REJECTED/);
});

test('materializeArtifacts is a no-op when the package carries no artifacts at all', async (t) => {
  let called = false;
  withMockedFetch(t, async () => { called = true; return new Response(Buffer.from(''), { status: 200 }); });

  const plan = { correlation: { applicationId: 'app-1' }, package: { artifacts: [] } };
  await materializeArtifacts(plan, 'http://backend.test');

  assert.equal(called, false, 'no fetch should happen when there is nothing to materialize');
});
