import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isHandoffEligibleError,
  normalizeHandoffReason,
  initiateHandoff,
  HandoffStatus,
} from '../src/handoff.js';

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
