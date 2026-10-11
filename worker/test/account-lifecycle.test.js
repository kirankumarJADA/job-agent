import { after, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { buildAccountLifecyclePlan, StepType } from '../src/plan.js';
import { BrowserWorker } from '../src/browser_worker.js';

let server;
let mockUrl;
after(() => { if (server) server.close(); });
function startMock() {
  if (server) return mockUrl;
  server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end('<form><input name="email"><input name="password"><button type="submit"></button></form>');
  });
  server.listen(0, '127.0.0.1');
  mockUrl = `http://127.0.0.1:${server.address().port}`;
  return mockUrl;
}

const plan = buildAccountLifecyclePlan({
  baseUrl: 'https://mockemployer.test', employerDomain: 'mockemployer.test',
  applicationId: 'app-1', accountEmail: 'candidate@example.com',
  accountPassword: 'local-only-password', verifyOtp: true,
});

describe('account lifecycle plan (Phase 8.5)', () => {
  it('contains signup, OTP verification and login steps in order', () => {
    const types = plan.steps.map((s) => s.type);
    assert.ok(types.includes(StepType.ACCOUNT_VERIFY_OTP));
    assert.equal(plan.steps[plan.steps.length - 1].id, 'login-submit');
  });

  it('resolves the OTP through the injected provider and continues', async () => {
    const url = startMock();
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-otp-'));
    const resolutions = [];
    const worker = new BrowserWorker({
      statePath: path.join(root, 'state.json'), artifactDir: root,
      verificationResolver: async (body) => {
        resolutions.push(body);
        return { otp: '123456', link: null };
      },
    });
    const lifecycle = buildAccountLifecyclePlan({
      baseUrl: url, employerDomain: 'mockemployer.test',
      applicationId: 'app-x', accountEmail: 'c@x.test', accountPassword: 'pw', verifyOtp: true,
    });
    // Abort right after the OTP step proves the resolver was consulted and
    // the code landed in the flow (full navigation is the e2e's job).
    lifecycle.steps.splice(6, 0,
      { id: 'abort-here', type: 'POLICY_CHECK', policy: 'FORBIDDEN', params: {} });
    await assert.rejects(
      () => worker.execute(lifecycle),
      (error) => error.name === 'HardStopError',
    );
    assert.ok(resolutions.length >= 1);
  });

  it('pauses for the human when the resolver cannot find a verification', async () => {
    const url = startMock();
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-pause-'));
    const lifecycle = buildAccountLifecyclePlan({
      baseUrl: url, employerDomain: 'mockemployer.test',
      applicationId: 'app-y', accountEmail: 'c@x.test', accountPassword: 'pw', verifyOtp: true,
    });
    await assert.rejects(
      () => new BrowserWorker({
        statePath: path.join(root, 'state.json'), artifactDir: root,
        verificationResolver: async () => { throw new Error('VERIFICATION_RESOLVE_FAILED:404'); },
      }).execute(lifecycle),
      (error) => error.message.includes('VERIFICATION_RESOLVE_FAILED')
        || error.name === 'ApprovalRequiredError',
    );
  });

  it('pauses when no verification resolver is configured', async () => {
    const url = startMock();
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-noresolver-'));
    const lifecycle = buildAccountLifecyclePlan({
      baseUrl: url, employerDomain: 'mockemployer.test',
      applicationId: 'app-z', accountEmail: 'c@x.test', accountPassword: 'pw', verifyOtp: true,
    });
    await assert.rejects(
      () => new BrowserWorker({
        statePath: path.join(root, 'state.json'), artifactDir: root,
      }).execute(lifecycle),
      (error) => error.name === 'ApprovalRequiredError',
    );
  });
});
