import test from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import {
  isHandoffEligibleError,
  normalizeHandoffReason,
  initiateHandoff,
  HandoffStatus,
} from '../src/handoff.js';
import { executePollCycle } from '../src/worker.js';
import { BrowserWorker } from '../src/browser_worker.js';

test('isHandoffEligibleError correctly identifies anti-bot and security challenge errors', () => {
  assert.equal(isHandoffEligibleError(new Error('Generic failure')), false);
  assert.equal(isHandoffEligibleError({ reason: 'LEVER_CAPTCHA_PRESENT' }), true);
  assert.equal(isHandoffEligibleError({ reason: 'WORKDAY_MFA_REQUIRED' }), true);
  assert.equal(isHandoffEligibleError({ reason: 'WORKDAY_CAPTCHA_PRESENT' }), true);
  assert.equal(isHandoffEligibleError(new Error('Cloudflare turnstile detected')), true);
  assert.equal(isHandoffEligibleError({ reason: 'MFA_REQUIRED' }), true);
  assert.equal(isHandoffEligibleError({ reason: 'BLOCKED_ANTI_BOT' }), true);
  assert.equal(isHandoffEligibleError(new Error('BLOCKED_ANTI_BOT')), true);
  assert.equal(isHandoffEligibleError({ reason: 'ANTI_BOT_CHALLENGE' }), true);
  assert.equal(isHandoffEligibleError(null), false);
});

test('normalizeHandoffReason maps error reasons to standard platform strings', () => {
  assert.equal(
    normalizeHandoffReason({ reason: 'LEVER_CAPTCHA_PRESENT' }, 'LEVER'),
    'LEVER_CAPTCHA_PRESENT'
  );
  assert.equal(
    normalizeHandoffReason({ reason: 'WORKDAY_MFA_REQUIRED' }, 'WORKDAY'),
    'WORKDAY_MFA_REQUIRED'
  );
  assert.equal(
    normalizeHandoffReason({ reason: 'Two-factor authenticator code required' }, 'WORKDAY'),
    'WORKDAY_MFA_REQUIRED'
  );
  assert.equal(
    normalizeHandoffReason({ reason: 'hCaptcha found' }, 'LEVER'),
    'LEVER_CAPTCHA_PRESENT'
  );
  assert.equal(
    normalizeHandoffReason({ reason: 'BLOCKED_ANTI_BOT' }, 'GREENHOUSE'),
    'EMPLOYER_SECURITY_CHECK'
  );
  assert.equal(
    normalizeHandoffReason(new Error('Cloudflare turnstile challenge'), 'GREENHOUSE'),
    'CLOUDFLARE_TURNSTILE'
  );
});

test('Option B: initiateHandoff posts correct body with domScreenshotRef and returns session without blocking', async () => {
  const plan = {
    planId: 'test-plan-opt-b',
    planType: 'WORKDAY',
    targetUrl: 'https://workday.com/company/apply',
    correlation: { applicationId: 'test-app-opt-b' },
  };
  const error = {
    reason: 'WORKDAY_MFA_REQUIRED',
    details: { screenshot: 'failures/workday-mfa.png' },
  };

  let initiateCalled = false;
  let sentBody = null;

  const mockFetch = async (url, options) => {
    if (url.endsWith('/api/v1/handoff/initiate')) {
      initiateCalled = true;
      sentBody = JSON.parse(options.body);
      return {
        ok: true,
        status: 201,
        json: async () => ({ id: 'session-opt-b-1', status: 'PENDING' }),
      };
    }
    throw new Error('Unexpected URL: ' + url);
  };

  const result = await initiateHandoff({
    plan,
    error,
    backendBase: 'http://localhost:8080',
    authHeaders: () => ({ authorization: 'Bearer test-worker-token' }),
    fetchImpl: mockFetch,
  });

  assert.equal(initiateCalled, true);
  assert.equal(result.initiated, true);
  assert.equal(result.sessionId, 'session-opt-b-1');
  assert.equal(result.status, 'PENDING');
  assert.equal(result.blockReason, 'WORKDAY_MFA_REQUIRED');
  assert.equal(sentBody.planId, 'test-plan-opt-b');
  assert.equal(sentBody.applicationId, 'test-app-opt-b');
  assert.equal(sentBody.atsPlatform, 'WORKDAY');
  assert.equal(sentBody.blockReason, 'WORKDAY_MFA_REQUIRED');
  assert.equal(sentBody.domScreenshotRef, 'failures/workday-mfa.png');
  assert.equal(sentBody.ttlMinutes, 15);
});

test('Option B: initiateHandoff throws HANDOFF_INITIATE_FAILED on non-2xx status', async () => {
  const plan = {
    planId: 'test-plan-fail',
    planType: 'LEVER',
    targetUrl: 'https://jobs.lever.co/company/job',
    correlation: { applicationId: 'test-app-fail' },
  };
  const error = { reason: 'LEVER_CAPTCHA_PRESENT' };

  const mockFetch = async () => ({
    ok: false,
    status: 500,
  });

  await assert.rejects(
    () => initiateHandoff({
      plan,
      error,
      backendBase: 'http://localhost:8080',
      fetchImpl: mockFetch,
    }),
    /HANDOFF_INITIATE_FAILED:500/
  );
});

test('Option B: initiateHandoff applies custom timeoutMs correctly in ttlMinutes', async () => {
  const plan = {
    planId: 'test-plan-custom-ttl',
    planType: 'GREENHOUSE',
    targetUrl: 'https://boards.greenhouse.io/job',
    correlation: { applicationId: 'test-app-custom-ttl' },
  };
  const error = { reason: 'BLOCKED_ANTI_BOT' };

  let sentBody = null;
  const mockFetch = async (url, options) => {
    sentBody = JSON.parse(options.body);
    return {
      ok: true,
      status: 201,
      json: async () => ({ id: 'session-ttl-1', status: 'PENDING' }),
    };
  };

  const result = await initiateHandoff({
    plan,
    error,
    backendBase: 'http://localhost:8080',
    timeoutMs: 30 * 60 * 1000,
    fetchImpl: mockFetch,
  });

  assert.equal(result.initiated, true);
  assert.equal(result.blockReason, 'EMPLOYER_SECURITY_CHECK');
  assert.equal(sentBody.ttlMinutes, 30);
  assert.equal(sentBody.blockReason, 'EMPLOYER_SECURITY_CHECK');
});


// ── P0 / P1 Worker Remediation Regression Tests ─────────────────────────

test('Test A: polling dispatch invokes initiateHandoff with valid plan and context when execution throws handoff-eligible error', async () => {
  const claimedPlan = {
    id: 'plan-test-a',
    targetUrl: 'https://jobs.lever.co/company/job',
    plan: {
      planId: 'plan-test-a',
      planType: 'LEVER',
      correlation: { applicationId: 'app-test-a', jobId: 'job-test-a' },
      targetUrl: 'https://jobs.lever.co/company/job',
      steps: [],
    },
  };

  const handoffError = new Error('Lever CAPTCHA encountered');
  handoffError.name = 'HardStopError';
  handoffError.reason = 'LEVER_CAPTCHA_PRESENT';
  handoffError.details = { screenshot: 'failures/lever-captcha.png' };

  let handoffArgs = null;
  const mockInitiateHandoff = async (args) => {
    handoffArgs = args;
    return { initiated: true, sessionId: 'session-a-999', status: 'PENDING' };
  };

  const mockRunner = {
    execute: async () => {
      throw handoffError;
    },
    requestAbort: () => {},
  };

  const result = await executePollCycle({
    pollUrl: 'http://localhost:8080/api/v1/automation/plans/claim-next',
    apiCallFn: async () => claimedPlan,
    runner: mockRunner,
    initiateHandoffFn: mockInitiateHandoff,
    authHeadersFn: () => ({ authorization: 'Bearer test-worker-token' }),
    reportOutcomeFn: async () => {},
    sendHeartbeatFn: async () => {},
    runtimeObj: { shuttingDown: false, runner: null },
  });

  assert.equal(result.status, 'HANDOFF');
  assert.equal(result.sessionId, 'session-a-999');
  assert.ok(handoffArgs !== null, 'initiateHandoff must be invoked');
  assert.equal(handoffArgs.plan.planId, 'plan-test-a');
  assert.equal(handoffArgs.plan.correlation.applicationId, 'app-test-a');
  assert.equal(handoffArgs.error.reason, 'LEVER_CAPTCHA_PRESENT');
  assert.equal(handoffArgs.backendBase, 'http://localhost:8080');
  assert.deepEqual(handoffArgs.authHeaders(), { authorization: 'Bearer test-worker-token' });
});

test('Test B: polling dispatch handles failure before plan construction without sending invalid handoff', async () => {
  let handoffCalled = false;
  const mockInitiateHandoff = async () => {
    handoffCalled = true;
    return { initiated: true, sessionId: 'invalid', status: 'PENDING' };
  };

  let outcomeReported = null;
  const mockReportOutcome = async (id, outcome, detail) => {
    outcomeReported = { id, outcome, detail };
  };

  const result = await executePollCycle({
    pollUrl: 'http://localhost:8080/api/v1/automation/plans/claim-next',
    apiCallFn: async () => {
      throw new Error('BACKEND_503_SERVICE_UNAVAILABLE');
    },
    initiateHandoffFn: mockInitiateHandoff,
    reportOutcomeFn: mockReportOutcome,
    runtimeObj: { shuttingDown: false, runner: null },
  });

  assert.equal(result.status, 'ERROR');
  assert.equal(handoffCalled, false, 'initiateHandoff must not be called when plan was not constructed');
  assert.equal(outcomeReported, null, 'no outcome should be reported when planId is absent');
});

test('Test C: browser teardown resilience preserves original error and executes browser.close even if context.close fails', async () => {
  let contextClosed = false;
  let browserClosed = false;
  const cleanupLogs = [];
  const originalConsoleError = console.error;
  console.error = (msg) => {
    cleanupLogs.push(msg);
  };

  try {
    const mockPage = {
      goto: async () => { throw new Error('SIMULATED_STEP_ERROR'); },
      url: () => 'https://jobs.lever.co/company/job',
      screenshot: async () => Buffer.from('mock-screenshot'),
      locator: () => ({ count: async () => 0 }),
    };
    const mockContext = {
      newPage: async () => mockPage,
      close: async () => {
        contextClosed = true;
        throw new Error('CDP_SOCKET_DESTROYED');
      },
      storageState: async () => {},
    };

    const mockBrowser = {
      newContext: async () => mockContext,
      close: async () => {
        browserClosed = true;
      },
    };

    const mockChromium = {
      launch: async () => mockBrowser,
    };

    const tempState = path.join(os.tmpdir(), `test-state-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
    const tempArtifacts = fs.mkdtempSync(path.join(os.tmpdir(), 'test-artifacts-'));

    const worker = new BrowserWorker({
      statePath: tempState,
      artifactDir: tempArtifacts,
      chromium: mockChromium,
    });

    const plan = {
      planId: 'plan-teardown-resilience',
      planType: 'LEVER',
      targetUrl: 'https://jobs.lever.co/company/job',
      correlation: { applicationId: 'app-teardown-resilience', jobId: 'job-1' },
      fields: [],
      steps: [
        {
          id: 'step-nav',
          type: 'NAVIGATE',
          params: { url: 'https://jobs.lever.co/company/job' },
          policy: 'AUTO',
        },
      ],
    };

    // Execute to simulate an execution error during step execution
    await assert.rejects(
      () => worker.execute(plan),
      /SIMULATED_STEP_ERROR/
    );

    // Verify context.close was attempted and rejected
    assert.equal(contextClosed, true, 'context.close() must have been called');
    // Verify browser.close was still executed despite context.close rejecting!
    assert.equal(browserClosed, true, 'browser.close() must be executed even when context.close() rejects');

    // Verify cleanup failure was logged
    const hasCleanupErrorLog = cleanupLogs.some(
      (log) => typeof log === 'string' && log.includes('BROWSER_CLEANUP_ERROR') && log.includes('context')
    );
    assert.equal(hasCleanupErrorLog, true, 'cleanup error must be logged via BROWSER_CLEANUP_ERROR');

    // Clean up temp files
    try { fs.unlinkSync(tempState); } catch {}
    try { fs.rmSync(tempArtifacts, { recursive: true, force: true }); } catch {}
  } finally {
    console.error = originalConsoleError;
  }
});
