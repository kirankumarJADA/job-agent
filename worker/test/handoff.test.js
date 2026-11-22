import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isHandoffEligibleError,
  normalizeHandoffReason,
  initiateHandoff,
  initiateHandoffAndWait,
  HandoffStatus,
} from '../src/handoff.js';

test('isHandoffEligibleError correctly identifies anti-bot and security challenge errors', () => {
  assert.equal(isHandoffEligibleError(new Error('Generic failure')), false);
  assert.equal(isHandoffEligibleError({ reason: 'LEVER_CAPTCHA_PRESENT' }), true);
  assert.equal(isHandoffEligibleError({ reason: 'WORKDAY_MFA_REQUIRED' }), true);
  assert.equal(isHandoffEligibleError({ reason: 'WORKDAY_CAPTCHA_PRESENT' }), true);
  assert.equal(isHandoffEligibleError(new Error('Cloudflare turnstile detected')), true);
  assert.equal(isHandoffEligibleError({ reason: 'MFA_REQUIRED' }), true);
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
});

test('initiateHandoffAndWait initiates handoff and resumes on candidate completion', async () => {
  const plan = {
    planId: 'test-plan-1',
    planType: 'LEVER',
    targetUrl: 'https://jobs.lever.co/company/job-1',
    correlation: { applicationId: 'test-app-1' },
  };
  const error = { reason: 'LEVER_CAPTCHA_PRESENT', details: { screenshot: 'failures/cap.png' } };

  let initiatePayload = null;
  let pollCount = 0;
  let heartbeatCount = 0;

  const mockFetch = async (url, options) => {
    if (url.endsWith('/api/v1/handoff/initiate')) {
      initiatePayload = JSON.parse(options.body);
      return {
        ok: true,
        status: 201,
        json: async () => ({ id: 'session-123', status: 'PENDING' }),
      };
    }
    if (url.includes('/api/v1/handoff/applications/test-app-1')) {
      pollCount += 1;
      if (pollCount === 1) {
        return {
          ok: true,
          status: 200,
          json: async () => ({ id: 'session-123', status: 'PENDING' }),
        };
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ id: 'session-123', status: 'COMPLETED' }),
      };
    }
    throw new Error('Unexpected URL: ' + url);
  };

  const result = await initiateHandoffAndWait({
    plan,
    error,
    backendBase: 'http://localhost:8080',
    authHeaders: () => ({ authorization: 'Bearer test-worker-token' }),
    sendHeartbeat: async () => { heartbeatCount += 1; },
    runtime: { shuttingDown: false },
    pollIntervalMs: 10,
    timeoutMs: 1000,
    fetchImpl: mockFetch,
  });

  assert.equal(result.resumed, true);
  assert.equal(result.status, HandoffStatus.COMPLETED);
  assert.equal(result.sessionId, 'session-123');

  assert.equal(initiatePayload.planId, 'test-plan-1');
  assert.equal(initiatePayload.applicationId, 'test-app-1');
  assert.equal(initiatePayload.atsPlatform, 'LEVER');
  assert.equal(initiatePayload.blockReason, 'LEVER_CAPTCHA_PRESENT');
  assert.equal(initiatePayload.domScreenshotRef, 'failures/cap.png');
});

test('initiateHandoffAndWait stops when candidate cancels handoff', async () => {
  const plan = {
    planId: 'test-plan-2',
    planType: 'WORKDAY',
    targetUrl: 'https://workday.com/login',
    correlation: { applicationId: 'test-app-2' },
  };
  const error = { reason: 'WORKDAY_MFA_REQUIRED' };

  const mockFetch = async (url, options) => {
    if (url.endsWith('/api/v1/handoff/initiate')) {
      return {
        ok: true,
        status: 201,
        json: async () => ({ id: 'session-456', status: 'PENDING' }),
      };
    }
    if (url.includes('/api/v1/handoff/applications/test-app-2')) {
      return {
        ok: true,
        status: 200,
        json: async () => ({ id: 'session-456', status: 'CANCELLED' }),
      };
    }
    throw new Error('Unexpected URL: ' + url);
  };

  const result = await initiateHandoffAndWait({
    plan,
    error,
    backendBase: 'http://localhost:8080',
    authHeaders: () => ({}),
    sendHeartbeat: async () => {},
    runtime: { shuttingDown: false },
    pollIntervalMs: 10,
    timeoutMs: 1000,
    fetchImpl: mockFetch,
  });

  assert.equal(result.resumed, false);
  assert.equal(result.status, HandoffStatus.CANCELLED);
  assert.equal(result.reason, 'CANDIDATE_CANCELLED');
});

test('initiateHandoffAndWait handles session timeout and returns EXPIRED', async () => {
  const plan = {
    planId: 'test-plan-3',
    planType: 'LEVER',
    targetUrl: 'https://jobs.lever.co/company',
    correlation: { applicationId: 'test-app-3' },
  };
  const error = { reason: 'LEVER_CAPTCHA_PRESENT' };

  const mockFetch = async (url) => {
    if (url.endsWith('/api/v1/handoff/initiate')) {
      return {
        ok: true,
        status: 201,
        json: async () => ({ id: 'session-789', status: 'PENDING' }),
      };
    }
    if (url.includes('/api/v1/handoff/applications/test-app-3')) {
      return {
        ok: true,
        status: 200,
        json: async () => ({ id: 'session-789', status: 'PENDING' }),
      };
    }
    throw new Error('Unexpected URL: ' + url);
  };

  const result = await initiateHandoffAndWait({
    plan,
    error,
    backendBase: 'http://localhost:8080',
    authHeaders: () => ({}),
    sendHeartbeat: async () => {},
    runtime: { shuttingDown: false },
    pollIntervalMs: 20,
    timeoutMs: 50, // fast timeout for test
    fetchImpl: mockFetch,
  });

  assert.equal(result.resumed, false);
  assert.equal(result.status, HandoffStatus.EXPIRED);
  assert.equal(result.reason, 'TIMEOUT');
});

test('Option B: initiateHandoff immediately posts to backend and returns session without blocking', async () => {
  const plan = {
    planId: 'test-plan-opt-b',
    planType: 'WORKDAY',
    targetUrl: 'https://workday.com/company/apply',
    correlation: { applicationId: 'test-app-opt-b' },
  };
  const error = { reason: 'WORKDAY_MFA_REQUIRED' };

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
  assert.equal(sentBody.atsPlatform, 'WORKDAY');
  assert.equal(sentBody.blockReason, 'WORKDAY_MFA_REQUIRED');
});
