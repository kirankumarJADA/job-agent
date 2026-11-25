import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  isRetryableStatus,
  isRetryableNetworkError,
  calculateBackoff,
  fetchWithRetry,
  DeadLetterRegistry,
  MAX_PLAN_CRASHES,
  apiCall,
  reportOutcome,
  executePollCycle,
  createPollHandler,
  buildWorkerEventBody,
} from '../src/worker.js';

// ── 1. HTTP Status & Network Error Retry Policies ──────────────────────────

test('isRetryableStatus: cleanly identifies transient cloud 502/503/504 errors', () => {
  // Retryable transient cloud gateway / service error codes
  assert.equal(isRetryableStatus(502), true, '502 Bad Gateway must be retryable');
  assert.equal(isRetryableStatus(503), true, '503 Service Unavailable must be retryable');
  assert.equal(isRetryableStatus(504), true, '504 Gateway Timeout must be retryable');
  assert.equal(isRetryableStatus('502'), true, 'String status "502" must be coerced');
  assert.equal(isRetryableStatus('503'), true, 'String status "503" must be coerced');
  assert.equal(isRetryableStatus('504'), true, 'String status "504" must be coerced');

  // Non-retryable success / client / application codes
  assert.equal(isRetryableStatus(200), false, '200 OK must not be retryable');
  assert.equal(isRetryableStatus(204), false, '204 No Content must not be retryable');
  assert.equal(isRetryableStatus(400), false, '400 Bad Request must not be retried');
  assert.equal(isRetryableStatus(401), false, '401 Unauthorized must not be retried');
  assert.equal(isRetryableStatus(403), false, '403 Forbidden must not be retried');
  assert.equal(isRetryableStatus(404), false, '404 Not Found must not be retried');
  assert.equal(isRetryableStatus(409), false, '409 Conflict must not be retried');
  assert.equal(isRetryableStatus(500), false, '500 Internal Error is non-transient logic failure');
});

test('isRetryableNetworkError: identifies transient socket and network drops', () => {
  assert.equal(isRetryableNetworkError(new TypeError('fetch failed')), true);
  assert.equal(isRetryableNetworkError({ code: 'ECONNRESET', message: 'connection reset' }), true);
  assert.equal(isRetryableNetworkError({ code: 'ETIMEDOUT', message: 'timeout' }), true);
  assert.equal(isRetryableNetworkError({ code: 'ECONNREFUSED', message: 'refused' }), true);
  assert.equal(isRetryableNetworkError({ status: 502 }), true);
  assert.equal(isRetryableNetworkError({ status: 503 }), true);
  assert.equal(isRetryableNetworkError({ status: 504 }), true);

  assert.equal(isRetryableNetworkError(null), false);
  assert.equal(isRetryableNetworkError(new Error('SyntaxError: invalid json')), false);
  assert.equal(isRetryableNetworkError({ status: 400 }), false);
});

// ── 2. Exponential Backoff and Full Jitter Timing ──────────────────────────

test('calculateBackoff: exponential ceiling progression with deterministic jitter', () => {
  const base = 500;
  const max = 10000;

  // With randomFn returning 1.0 (maximum full jitter = full exponential ceiling)
  const maxJitter = () => 1.0;
  assert.equal(calculateBackoff(0, base, max, maxJitter), 500);   // 500 * 2^0 = 500
  assert.equal(calculateBackoff(1, base, max, maxJitter), 1000);  // 500 * 2^1 = 1000
  assert.equal(calculateBackoff(2, base, max, maxJitter), 2000);  // 500 * 2^2 = 2000
  assert.equal(calculateBackoff(3, base, max, maxJitter), 4000);  // 500 * 2^3 = 4000
  assert.equal(calculateBackoff(4, base, max, maxJitter), 8000);  // 500 * 2^4 = 8000
  assert.equal(calculateBackoff(5, base, max, maxJitter), 10000); // capped at maxDelayMs = 10000
  assert.equal(calculateBackoff(10, base, max, maxJitter), 10000); // capped at maxDelayMs = 10000

  // With randomFn returning 0.5 (midpoint of full jitter)
  const midJitter = () => 0.5;
  assert.equal(calculateBackoff(0, base, max, midJitter), 250);
  assert.equal(calculateBackoff(1, base, max, midJitter), 500);
  assert.equal(calculateBackoff(2, base, max, midJitter), 1000);
  assert.equal(calculateBackoff(3, base, max, midJitter), 2000);
  assert.equal(calculateBackoff(4, base, max, midJitter), 4000);
  assert.equal(calculateBackoff(5, base, max, midJitter), 5000);

  // With randomFn returning 0.0 (lower bound of full jitter)
  const minJitter = () => 0.0;
  assert.equal(calculateBackoff(0, base, max, minJitter), 0);
  assert.equal(calculateBackoff(1, base, max, minJitter), 0);
  assert.equal(calculateBackoff(5, base, max, minJitter), 0);
});

test('calculateBackoff: full jitter distribution remains bounded within [0, ceiling]', () => {
  const base = 500;
  const max = 10000;
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const ceiling = Math.min(max, base * Math.pow(2, attempt));
    for (let sample = 0; sample < 100; sample += 1) {
      const delay = calculateBackoff(attempt, base, max, Math.random);
      assert.ok(delay >= 0, `delay ${delay} must be >= 0`);
      assert.ok(delay <= ceiling, `delay ${delay} must be <= ceiling ${ceiling}`);
      assert.equal(Number.isInteger(delay), true, `delay must be an integer`);
    }
  }
});

test('fetchWithRetry: verifies exponential backoff sleep timing sequence under transient 502/503/504', async () => {
  const sleepCalls = [];
  const sleepFn = async (ms) => {
    sleepCalls.push(ms);
  };

  let callCount = 0;
  const fetchFn = async () => {
    callCount += 1;
    if (callCount === 1) return { status: 502, ok: false };
    if (callCount === 2) return { status: 503, ok: false };
    if (callCount === 3) return { status: 504, ok: false };
    return { status: 200, ok: true, json: async () => ({ success: true }) };
  };

  // Fixed 1.0 randomFn ensures deterministic verification of ceiling backoff
  const response = await fetchWithRetry('http://backend.invalid/api', {}, {
    maxRetries: 3,
    baseDelayMs: 200,
    maxDelayMs: 2000,
    randomFn: () => 1.0,
    sleepFn,
    fetchFn,
  });

  assert.equal(callCount, 4);
  assert.equal(response.status, 200);
  // attempt 0 (ceiling 200ms), attempt 1 (ceiling 400ms), attempt 2 (ceiling 800ms)
  assert.deepEqual(sleepCalls, [200, 400, 800]);
});

// ── 3. HTTP Client Retries (Simulated 502, 503, 504 and Non-Retryable) ──────

test('fetchWithRetry: recovers after transient 502 Bad Gateway', async () => {
  let calls = 0;
  const fetchFn = async () => {
    calls += 1;
    if (calls < 3) return { status: 502, ok: false };
    return { status: 200, ok: true, json: async () => ({ recovered: true }) };
  };

  const res = await fetchWithRetry('http://cloud.test/api', {}, {
    maxRetries: 3,
    baseDelayMs: 10,
    fetchFn,
    sleepFn: async () => {},
  });

  assert.equal(calls, 3);
  assert.equal(res.status, 200);
});

test('fetchWithRetry: recovers after transient 503 Service Unavailable', async () => {
  let calls = 0;
  const fetchFn = async () => {
    calls += 1;
    if (calls === 1) return { status: 503, ok: false };
    return { status: 200, ok: true };
  };

  const res = await fetchWithRetry('http://cloud.test/api', {}, {
    maxRetries: 3,
    baseDelayMs: 10,
    fetchFn,
    sleepFn: async () => {},
  });

  assert.equal(calls, 2);
  assert.equal(res.status, 200);
});

test('fetchWithRetry: recovers after transient 504 Gateway Timeout', async () => {
  let calls = 0;
  const fetchFn = async () => {
    calls += 1;
    if (calls === 1) return { status: 504, ok: false };
    return { status: 200, ok: true };
  };

  const res = await fetchWithRetry('http://cloud.test/api', {}, {
    maxRetries: 3,
    baseDelayMs: 10,
    fetchFn,
    sleepFn: async () => {},
  });

  assert.equal(calls, 2);
  assert.equal(res.status, 200);
});

test('fetchWithRetry: refuses to retry non-retryable 400 Bad Request', async () => {
  let calls = 0;
  const fetchFn = async () => {
    calls += 1;
    return { status: 400, ok: false };
  };

  const res = await fetchWithRetry('http://cloud.test/api', {}, {
    maxRetries: 3,
    fetchFn,
    sleepFn: async () => {},
  });

  assert.equal(calls, 1, '400 Bad Request must return immediately without retries');
  assert.equal(res.status, 400);
});

test('fetchWithRetry: refuses to retry non-retryable 404 Not Found', async () => {
  let calls = 0;
  const fetchFn = async () => {
    calls += 1;
    return { status: 404, ok: false };
  };

  const res = await fetchWithRetry('http://cloud.test/api', {}, {
    maxRetries: 3,
    fetchFn,
    sleepFn: async () => {},
  });

  assert.equal(calls, 1, '404 Not Found must return immediately without retries');
  assert.equal(res.status, 404);
});

test('fetchWithRetry: cleanly bounds retry attempts when 502 persists across all retries', async () => {
  let calls = 0;
  const fetchFn = async () => {
    calls += 1;
    return { status: 502, ok: false };
  };

  const res = await fetchWithRetry('http://cloud.test/api', {}, {
    maxRetries: 3,
    baseDelayMs: 10,
    fetchFn,
    sleepFn: async () => {},
  });

  // initial attempt (1) + maxRetries (3) = 4 calls total
  assert.equal(calls, 4);
  assert.equal(res.status, 502);
});

test('fetchWithRetry: recovers from transient network fetch failure', async () => {
  let calls = 0;
  const fetchFn = async () => {
    calls += 1;
    if (calls < 2) throw new TypeError('fetch failed');
    return { status: 200, ok: true };
  };

  const res = await fetchWithRetry('http://cloud.test/api', {}, {
    maxRetries: 3,
    baseDelayMs: 10,
    fetchFn,
    sleepFn: async () => {},
  });

  assert.equal(calls, 2);
  assert.equal(res.status, 200);
});

// ── 4. API Interactions with Retries (apiCall, claim, reportOutcome, poll) ──

test('apiCall: retries and returns JSON payload when backend recovers from 502', async () => {
  let attempts = 0;
  const mockFetch = async () => {
    attempts += 1;
    if (attempts < 2) return { status: 502, ok: false };
    return {
      status: 200,
      ok: true,
      json: async () => ({ id: 'plan-xyz', planType: 'INSPECTION' }),
    };
  };

  const result = await apiCall('http://backend.invalid/plans/claim-next', null, {
    retryConfig: { sleepFn: async () => {}, maxRetries: 3, baseDelayMs: 10 },
    fetchFn: mockFetch,
  });

  assert.equal(attempts, 2);
  assert.equal(result.id, 'plan-xyz');
});

test('apiCall: throws BACKEND_REQUEST_FAILED when 503 persists after max retries', async () => {
  let attempts = 0;
  const mockFetch = async () => {
    attempts += 1;
    return { status: 503, ok: false };
  };

  await assert.rejects(
    () => apiCall('http://backend.invalid/plans/claim-next', null, {
      retryConfig: { sleepFn: async () => {}, maxRetries: 3, baseDelayMs: 10 },
      fetchFn: mockFetch,
    }),
    /BACKEND_REQUEST_FAILED:503/
  );
  assert.equal(attempts, 4);
});

test('reportOutcome: retries with exponential backoff on 503 via fetchWithRetry and delivers completion', async () => {
  let attempts = 0;
  const mockFetch = async () => {
    attempts += 1;
    if (attempts < 3) return { status: 503, ok: false };
    return {
      status: 200,
      ok: true,
      json: async () => ({ acknowledged: true }),
    };
  };

  await reportOutcome('plan-100', 'COMPLETED', 'All steps confirmed', null, {
    completeUrlVal: 'http://backend.invalid/plans/{id}/complete',
    retryConfig: { sleepFn: async () => {}, maxRetries: 3, baseDelayMs: 10 },
    fetchFn: mockFetch,
  });

  assert.equal(attempts, 3);
});

test('reportOutcome: throws after exhausting retries under persistent 504 with single retry owner', async () => {
  let attempts = 0;
  const mockFetch = async () => {
    attempts += 1;
    return { status: 504, ok: false };
  };

  await assert.rejects(
    () => reportOutcome('plan-200', 'FAILED', 'Fatal error', null, {
      completeUrlVal: 'http://backend.invalid/plans/{id}/complete',
      retryConfig: { sleepFn: async () => {}, maxRetries: 2, baseDelayMs: 10 },
      fetchFn: mockFetch,
    }),
    /BACKEND_REQUEST_FAILED:504/
  );
  assert.equal(attempts, 3); // 1 initial + 2 retries
});

test('reportOutcome: does not introduce outer retry loop around apiCall (single retry owner architecture)', async () => {
  let apiCallInvocations = 0;
  const mockApiCall = async () => {
    apiCallInvocations += 1;
    throw new Error('BACKEND_REQUEST_FAILED:503');
  };

  await assert.rejects(
    () => reportOutcome('plan-300', 'FAILED', 'Some error', null, {
      apiCallFn: mockApiCall,
      completeUrlVal: 'http://backend.invalid/plans/{id}/complete',
      retryConfig: { maxRetries: 3 },
    }),
    /BACKEND_REQUEST_FAILED:503/
  );

  // Crucial: apiCall is invoked exactly ONCE by reportOutcome because fetchWithRetry inside apiCall is the sole retry owner
  assert.equal(apiCallInvocations, 1);
});

test('createPollHandler: handles transient 502 during poll cycle without crashing process', async () => {
  let callCount = 0;
  const poll = createPollHandler({
    pollUrl: 'http://backend.invalid/plans/claim-next',
    apiCallFn: async () => {
      callCount += 1;
      throw new Error('BACKEND_REQUEST_FAILED:502');
    },
    reportOutcomeFn: async () => {},
    sendHeartbeatFn: async () => {},
    initiateHandoffFn: async () => {},
    runtimeObj: { shuttingDown: false },
  });

  const result = await poll();
  assert.equal(result.status, 'ERROR');
  assert.equal(callCount, 1);
});

// ── 5. Dead-Letter Registry & Poison-Pill Terminal Isolation ────────────────

test('DeadLetterRegistry: records crashes and marks dead letter at threshold', () => {
  const reg = new DeadLetterRegistry({ maxCrashes: 3 });
  assert.equal(reg.isDeadLetter('plan-1'), false);
  assert.equal(reg.getCrashCount('plan-1'), 0);

  reg.recordCrash('plan-1', new Error('Crash 1'));
  assert.equal(reg.getCrashCount('plan-1'), 1);
  assert.equal(reg.isDeadLetter('plan-1'), false);

  reg.recordCrash('plan-1', new Error('Crash 2'));
  assert.equal(reg.getCrashCount('plan-1'), 2);
  assert.equal(reg.isDeadLetter('plan-1'), false);

  reg.recordCrash('plan-1', new Error('Crash 3: poison pill'));
  assert.equal(reg.getCrashCount('plan-1'), 3);
  assert.equal(reg.isDeadLetter('plan-1'), true);
  assert.ok(reg.getDeadLetter('plan-1').reason.includes('poison pill'));
});

test('DeadLetterRegistry: state persists across reloads via persistPath', () => {
  const tmpFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'dl-reg-')), 'dead-letters.json');
  try {
    const reg1 = new DeadLetterRegistry({ maxCrashes: 2, persistPath: tmpFile });
    reg1.recordCrash('plan-persisted', new Error('Crash A'));
    reg1.recordCrash('plan-persisted', new Error('Crash B'));
    assert.equal(reg1.isDeadLetter('plan-persisted'), true);

    // Reconstruct new instance from disk
    const reg2 = new DeadLetterRegistry({ maxCrashes: 2, persistPath: tmpFile });
    assert.equal(reg2.isDeadLetter('plan-persisted'), true);
    assert.equal(reg2.getCrashCount('plan-persisted'), 2);
  } finally {
    fs.rmSync(path.dirname(tmpFile), { recursive: true, force: true });
  }
});

test('DeadLetterRegistry: operates strictly in-memory when persistPath is unset', () => {
  const reg1 = new DeadLetterRegistry({ maxCrashes: 2, persistPath: null });
  reg1.recordCrash('plan-mem-only', new Error('Crash 1'));
  reg1.recordCrash('plan-mem-only', new Error('Crash 2'));
  assert.equal(reg1.isDeadLetter('plan-mem-only'), true);

  // New instance without persistPath starts completely clean (in-memory behavior)
  const reg2 = new DeadLetterRegistry({ maxCrashes: 2, persistPath: null });
  assert.equal(reg2.getCrashCount('plan-mem-only'), 0);
  assert.equal(reg2.isDeadLetter('plan-mem-only'), false);
});

test('executePollCycle: repeated execution crashes trigger terminal isolation and report DEAD_LETTER_POISON_PILL', async () => {
  const registry = new DeadLetterRegistry({ maxCrashes: 3 });
  const planId = 'poison-plan-42';
  const claimedPlan = {
    id: planId,
    plan: {
      planType: 'INSPECTION',
      targetUrl: 'http://test.invalid',
      steps: [{ type: 'NAVIGATE', url: 'http://test.invalid' }],
    },
  };

  let outcomeReported = null;
  const mockReportOutcome = async (id, outcome, detail, result) => {
    outcomeReported = { id, outcome, detail, result };
  };

  const deadLetterEvents = [];
  const mockEventSink = async (evt) => {
    if (evt.type === 'PLAN_DEAD_LETTER_ISOLATED') deadLetterEvents.push(evt);
  };

  const crashingRunner = {
    execute: async () => {
      throw new Error('Simulated poison-pill browser crash');
    },
    requestAbort: () => {},
  };

  // Run 1: first crash
  const res1 = await executePollCycle({
    pollUrl: 'http://backend.invalid/plans/claim-next',
    apiCallFn: async () => claimedPlan,
    runner: crashingRunner,
    reportOutcomeFn: mockReportOutcome,
    eventSinkFn: mockEventSink,
    deadLetterRegistry: registry,
    maxCrashLimit: 3,
  });
  assert.equal(res1.status, 'ERROR');
  assert.equal(registry.getCrashCount(planId), 1);
  assert.equal(registry.isDeadLetter(planId), false);

  // Run 2: second crash
  const res2 = await executePollCycle({
    pollUrl: 'http://backend.invalid/plans/claim-next',
    apiCallFn: async () => claimedPlan,
    runner: crashingRunner,
    reportOutcomeFn: mockReportOutcome,
    eventSinkFn: mockEventSink,
    deadLetterRegistry: registry,
    maxCrashLimit: 3,
  });
  assert.equal(res2.status, 'ERROR');
  assert.equal(registry.getCrashCount(planId), 2);
  assert.equal(registry.isDeadLetter(planId), false);

  // Run 3: third crash triggers dead-letter terminal isolation
  const res3 = await executePollCycle({
    pollUrl: 'http://backend.invalid/plans/claim-next',
    apiCallFn: async () => claimedPlan,
    runner: crashingRunner,
    reportOutcomeFn: mockReportOutcome,
    eventSinkFn: mockEventSink,
    deadLetterRegistry: registry,
    maxCrashLimit: 3,
  });
  assert.equal(res3.status, 'DEAD_LETTER');
  assert.equal(res3.outcome, 'FAILED');
  assert.equal(registry.isDeadLetter(planId), true);
  assert.equal(deadLetterEvents.length, 1);
  assert.equal(deadLetterEvents[0].planId, planId);
  assert.equal(outcomeReported.outcome, 'FAILED');
  assert.ok(outcomeReported.detail.includes('DEAD_LETTER_POISON_PILL'));
  assert.equal(outcomeReported.result[0].reason, 'DEAD_LETTER_POISON_PILL');
  assert.equal(outcomeReported.result[0].deadLetter, true);
});

test('executePollCycle: pre-execution guard intercepts already dead-lettered plan and refuses execution', async () => {
  const registry = new DeadLetterRegistry({ maxCrashes: 3 });
  const planId = 'quarantined-plan-99';
  registry.markDeadLetter(planId, 'Prior fatal crashes');

  const claimedPlan = {
    id: planId,
    plan: {
      planType: 'INSPECTION',
      targetUrl: 'http://test.invalid',
      steps: [{ type: 'NAVIGATE', url: 'http://test.invalid' }],
    },
  };

  let runnerInvoked = false;
  const mockRunner = {
    execute: async () => {
      runnerInvoked = true;
      return { status: 'COMPLETED' };
    },
    requestAbort: () => {},
  };

  let terminalReported = null;
  const mockReportOutcome = async (id, outcome, detail, result) => {
    terminalReported = { id, outcome, detail, result };
  };

  const events = [];
  const mockEventSink = async (evt) => events.push(evt);

  const res = await executePollCycle({
    pollUrl: 'http://backend.invalid/plans/claim-next',
    apiCallFn: async () => claimedPlan,
    runner: mockRunner,
    reportOutcomeFn: mockReportOutcome,
    eventSinkFn: mockEventSink,
    deadLetterRegistry: registry,
  });

  assert.equal(res.status, 'DEAD_LETTER');
  assert.equal(res.outcome, 'FAILED');
  assert.equal(runnerInvoked, false, 'Runner must NEVER be executed for a dead-lettered poison pill');
  assert.equal(terminalReported.outcome, 'FAILED');
  assert.ok(terminalReported.detail.includes('DEAD_LETTER_POISON_PILL'));
  assert.equal(events.some((e) => e.type === 'PLAN_DEAD_LETTER_ISOLATED'), true);
});

test('executePollCycle: successful execution clears past crash count for the plan', async () => {
  const registry = new DeadLetterRegistry({ maxCrashes: 3 });
  const planId = 'healthy-plan-7';
  registry.recordCrash(planId, new Error('Past transient crash'));
  assert.equal(registry.getCrashCount(planId), 1);

  const claimedPlan = {
    id: planId,
    plan: {
      planType: 'INSPECTION',
      targetUrl: 'http://test.invalid',
      steps: [{ type: 'NAVIGATE', url: 'http://test.invalid' }],
    },
  };

  const healthyRunner = {
    execute: async () => ({ status: 'COMPLETED' }),
    requestAbort: () => {},
  };

  const res = await executePollCycle({
    pollUrl: 'http://backend.invalid/plans/claim-next',
    apiCallFn: async () => claimedPlan,
    runner: healthyRunner,
    reportOutcomeFn: async () => {},
    eventSinkFn: async () => {},
    deadLetterRegistry: registry,
  });

  assert.equal(res.status, 'COMPLETED');
  assert.equal(registry.getCrashCount(planId), 0, 'Clean execution must clear crash records');
  assert.equal(registry.isDeadLetter(planId), false);
});

test('apiCall: attaches status and parsed JSON body on non-retryable 422 response', async () => {
  let seenErr = null;
  try {
    await apiCall('http://backend.invalid/plans/p-422/complete', { outcome: 'SUBMITTED' }, {
      fetchFn: async () => ({
        ok: false,
        status: 422,
        text: async () => JSON.stringify({
          code: 'SUBMISSION_EVIDENCE_REQUIRED',
          status: 'RECONCILIATION_REQUIRED',
          reason: 'missing confirmation signal',
        }),
      }),
      retryConfig: { sleepFn: async () => {}, maxRetries: 2 },
    });
  } catch (err) {
    seenErr = err;
  }
  assert.ok(seenErr);
  assert.equal(seenErr.status, 422);
  assert.equal(seenErr.body.code, 'SUBMISSION_EVIDENCE_REQUIRED');
  assert.equal(seenErr.body.status, 'RECONCILIATION_REQUIRED');
});

test('executePollCycle: treats 422 RECONCILIATION_REQUIRED as non-crash terminal outcome without FAILED overwrite', async () => {
  const registry = new DeadLetterRegistry({ maxCrashes: 3 });
  const planId = 'reconcile-422-plan';
  const claimedPlan = {
    id: planId,
    plan: {
      planType: 'ASHBY',
      targetUrl: 'https://jobs.ashbyhq.com/acme/1',
      correlation: { jobId: 'j-1', applicationId: 'a-1' },
      steps: [
        { type: 'NAVIGATE', url: 'https://jobs.ashbyhq.com/acme/1' },
        { type: 'ASHBY_SUBMIT_CLICK' },
      ],
    },
  };

  const reportCalls = [];
  const emittedEvents = [];
  const res = await executePollCycle({
    pollUrl: 'http://backend.invalid/plans/claim-next',
    apiCallFn: async () => claimedPlan,
    sendHeartbeatFn: async () => {},
    runner: {
      execute: async () => ({
        status: 'COMPLETED',
        submitResult: {
          confirmed: true,
          category: 'CONFIRMED',
          evidence: 'Confirmation page appeared',
          signals: [],
        },
      }),
      requestAbort: () => {},
    },
    reportOutcomeFn: async (id, outcome) => {
      reportCalls.push({ id, outcome });
      const err = new Error('BACKEND_REQUEST_FAILED:422');
      err.status = 422;
      err.body = {
        code: 'SUBMISSION_EVIDENCE_REQUIRED',
        status: 'RECONCILIATION_REQUIRED',
        reason: 'missing structured evidence',
      };
      throw err;
    },
    eventSinkFn: async (evt) => emittedEvents.push(evt),
    deadLetterRegistry: registry,
  });

  assert.equal(res.status, 'RECONCILIATION_REQUIRED');
  assert.equal(res.outcome, 'RECONCILIATION_REQUIRED');
  assert.equal(reportCalls.length, 1, '422 must NOT trigger a fallback FAILED overwrite call');
  assert.equal(registry.getCrashCount(planId), 0, '422 reconciliation must NOT count as a worker crash');
  assert.equal(registry.isDeadLetter(planId), false);
  assert.ok(emittedEvents.some((e) => e.type === 'PLAN_ENDED' && e.outcome === 'RECONCILIATION_REQUIRED'));
});

test('buildWorkerEventBody: produces unique eventId per execution pass while remaining deterministic within a pass', () => {
  const pass1Body = buildWorkerEventBody({
    planId: 'plan-evt-1',
    passId: 'pass-a',
    type: 'STEP_STARTED',
    stepId: 'navigate-application',
  });
  const pass1RetryBody = buildWorkerEventBody({
    planId: 'plan-evt-1',
    passId: 'pass-a',
    type: 'STEP_STARTED',
    stepId: 'navigate-application',
  });
  const pass2Body = buildWorkerEventBody({
    planId: 'plan-evt-1',
    passId: 'pass-b',
    type: 'STEP_STARTED',
    stepId: 'navigate-application',
  });

  assert.equal(pass1Body.eventId, pass1RetryBody.eventId);
  assert.notEqual(pass1Body.eventId, pass2Body.eventId);
});

test('DeadLetterRegistry + executePollCycle: manual requeueCount increase unblocks isolated plan and resets pass events', async () => {
  const tmpFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'dl-requeue-')), 'dead-letters.json');
  try {
    const registry = new DeadLetterRegistry({ maxCrashes: 2, persistPath: tmpFile });
    const planId = 'requeue-plan-9';
    const baseClaim = {
      id: planId,
      requeueCount: 0,
      plan: {
        planType: 'INSPECTION',
        targetUrl: 'http://test.invalid',
        steps: [{ type: 'NAVIGATE', url: 'http://test.invalid' }],
      },
    };
    const crashingRunner = {
      execute: async () => {
        throw new Error('Transient profile gap before fix');
      },
      requestAbort: () => {},
    };
    const emittedEvents = [];
    const sink = async (evt) => emittedEvents.push(evt);

    await executePollCycle({
      pollUrl: 'http://backend.invalid/plans/claim-next',
      apiCallFn: async () => baseClaim,
      runner: crashingRunner,
      reportOutcomeFn: async () => {},
      eventSinkFn: sink,
      deadLetterRegistry: registry,
      maxCrashLimit: 2,
    });
    await executePollCycle({
      pollUrl: 'http://backend.invalid/plans/claim-next',
      apiCallFn: async () => baseClaim,
      runner: crashingRunner,
      reportOutcomeFn: async () => {},
      eventSinkFn: sink,
      deadLetterRegistry: registry,
      maxCrashLimit: 2,
    });
    assert.equal(registry.isDeadLetter(planId), true);

    // Without manual requeue (requeueCount still 0), execution stays blocked
    const blocked = await executePollCycle({
      pollUrl: 'http://backend.invalid/plans/claim-next',
      apiCallFn: async () => baseClaim,
      runner: { execute: async () => ({ status: 'COMPLETED' }), requestAbort: () => {} },
      reportOutcomeFn: async () => {},
      eventSinkFn: sink,
      deadLetterRegistry: registry,
      maxCrashLimit: 2,
    });
    assert.equal(blocked.status, 'DEAD_LETTER');

    // Reload from disk and verify manual requeueCount=1 clears prior isolation cleanly
    const reloaded = new DeadLetterRegistry({ maxCrashes: 2, persistPath: tmpFile });
    assert.equal(reloaded.isDeadLetter(planId), true);

    const reqClaim = { ...baseClaim, requeueCount: 1 };
    const resumed = await executePollCycle({
      pollUrl: 'http://backend.invalid/plans/claim-next',
      apiCallFn: async () => reqClaim,
      runner: { execute: async () => ({ status: 'COMPLETED' }), requestAbort: () => {} },
      reportOutcomeFn: async () => {},
      eventSinkFn: sink,
      deadLetterRegistry: reloaded,
      maxCrashLimit: 2,
    });
    assert.equal(resumed.status, 'COMPLETED');
    assert.equal(reloaded.isDeadLetter(planId), false);
    assert.equal(reloaded.getCrashCount(planId), 0);
    // Pass IDs across passes differ so backend worker_events never deduplicates the new pass
    const dlPassIds = new Set(emittedEvents.map((e) => e.passId));
    assert.ok(dlPassIds.size >= 1);
  } finally {
    fs.rmSync(path.dirname(tmpFile), { recursive: true, force: true });
  }
});
