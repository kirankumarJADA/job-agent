import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { StateStore } from '../src/store.js';

function tmpStore() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'store-test-'));
  return new StateStore(path.join(root, 'state.json'));
}

test('a completed plan with an unchanged fingerprint is never re-entered', () => {
  const store = tmpStore();
  const plan = { planId: 'p1', planFingerprint: 'fp-1', correlation: {} };
  store.begin(plan);
  store.markStep('p1', 'only-step', 'COMPLETED', {});
  store.complete('p1');

  const resumed = store.begin(plan); // same fingerprint
  assert.equal(resumed.status, 'COMPLETED');
  assert.equal(store.step('p1', 'only-step').status, 'COMPLETED');
});

/**
 * Phase 4: the one deliberate exception. When the backend appends
 * REAL_SUBMIT_CLICK to an already-completed plan and reuses the same plan
 * id (AutomationPlanRepository#prepareRealSubmission), the plan's own
 * fingerprint changes. begin() must resume rather than short-circuit, while
 * preserving every already-recorded step so the worker's own per-step skip
 * logic (in browser_worker.js's main loop) carries straight to the new step
 * instead of re-running anything already done.
 */
test('a completed plan whose fingerprint changed resumes, preserving prior step records', () => {
  const store = tmpStore();
  const planV1 = { planId: 'p1', planFingerprint: 'fp-1', correlation: {} };
  store.begin(planV1);
  store.markStep('p1', 'fill-email', 'COMPLETED', { currentUrl: 'https://boards.greenhouse.io/acme/jobs/42' });
  store.markStep('p1', 'validate-form', 'COMPLETED', {});
  store.complete('p1');

  const planV2 = { planId: 'p1', planFingerprint: 'fp-2', correlation: {} }; // one step appended server-side
  const resumed = store.begin(planV2);

  assert.equal(resumed.status, 'RUNNING');
  assert.equal(resumed.planFingerprint, 'fp-2');
  assert.equal(store.step('p1', 'fill-email').status, 'COMPLETED');
  assert.equal(store.step('p1', 'validate-form').status, 'COMPLETED');
  assert.equal(store.step('p1', 'real-submit-click'), undefined); // not yet attempted
  assert.equal(resumed.lastUrl, 'https://boards.greenhouse.io/acme/jobs/42'); // browser resumes here, not about:blank
});

test('a never-completed plan is unaffected by the fingerprint check (ordinary mid-run resume)', () => {
  const store = tmpStore();
  const plan = { planId: 'p1', planFingerprint: 'fp-1', correlation: {} };
  store.begin(plan);
  store.markStep('p1', 'step-a', 'COMPLETED', {});
  // Process crashes before completing the plan — status stays RUNNING.

  const resumed = store.begin(plan);
  assert.equal(resumed.status, 'RUNNING');
  assert.equal(store.step('p1', 'step-a').status, 'COMPLETED');
});
