import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import { computePlanOutcome } from '../src/outcome.js';
import { createGenuineReceipt, InvalidReceiptError } from '../src/receipt.js';
import { StepType, Policy, planFingerprint } from '../src/plan.js';
import { startGreenhouseSandboxFixture } from './fixtures/greenhouse_sandbox_fixture.js';

const pollArtifactRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-poll-artifacts-'));
process.env.WORKER_ARTIFACT_DIR = pollArtifactRoot;
const { executePollCycle, reportOutcome, apiCall, sendHeartbeat } = await import('../src/worker.js');
test.after(() => fs.rmSync(pollArtifactRoot, { recursive: true, force: true }));

function buildSandboxGreenhousePlan(root, targetUrl, { withSubmit = true, applicationId = 'app-sbx-1', jobId = 'job-sbx-1' } = {}) {
  const cvPath = path.join(root, 'resume.pdf');
  fs.writeFileSync(cvPath, '%PDF-1.4\nsandbox cv\n');
  const cvSha = crypto.createHash('sha256').update(fs.readFileSync(cvPath)).digest('hex');

  const fields = [
    { key: 'first_name', label: 'First name', htmlType: 'text', required: true, selector: '#first_name', classification: 'SUPPORTED_AUTO', value: 'Ada', valueSource: 'profile', reason: 'verified profile', options: [] },
    { key: 'last_name', label: 'Last name', htmlType: 'text', required: true, selector: '#last_name', classification: 'SUPPORTED_AUTO', value: 'Lovelace', valueSource: 'profile', reason: 'verified profile', options: [] },
    { key: 'email', label: 'Email address', htmlType: 'email', required: true, selector: '#email', classification: 'SUPPORTED_AUTO', value: 'ada@example.local', valueSource: 'profile', reason: 'verified profile', options: [] },
    { key: 'resume', label: 'Resume', htmlType: 'file', required: false, selector: '#resume', classification: 'SUPPORTED_AUTO', value: 'resume.pdf', valueSource: 'cv', reason: 'approved cv', options: [] },
  ];

  const steps = [
    { id: 'navigate-application', type: StepType.NAVIGATE, policy: Policy.AUTO, params: { url: targetUrl } },
    { id: 'fill-first_name', type: StepType.FILL_FIELD, policy: Policy.AUTO, params: { selector: '#first_name', value: 'Ada' } },
    { id: 'fill-last_name', type: StepType.FILL_FIELD, policy: Policy.AUTO, params: { selector: '#last_name', value: 'Lovelace' } },
    { id: 'fill-email', type: StepType.FILL_FIELD, policy: Policy.AUTO, params: { selector: '#email', value: 'ada@example.local' } },
    {
      id: 'upload-resume',
      type: StepType.UPLOAD_FILE,
      policy: Policy.AUTO,
      params: {
        selector: '#resume',
        path: cvPath,
        artifact: 'cv',
        expectedJobId: jobId,
        expectedApplicationId: applicationId,
        expectedVersionId: 'cv-ver-1',
        sha256: cvSha,
      },
    },
    { id: 'validate-form', type: StepType.VALIDATE, policy: Policy.AUTO, params: {} },
  ];
  if (withSubmit) {
    steps.push({ id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: Policy.AUTO, params: { selector: 'button[type="submit"]' } });
  }

  return {
    planId: `plan-${applicationId}`,
    planType: 'GREENHOUSE',
    version: 1,
    targetUrl,
    safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
    correlation: { applicationId, jobId },
    fields,
    requiredGaps: [],
    package: {
      applicationId,
      jobId,
      expectedUrl: targetUrl,
      safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
      fields,
      artifacts: [{ kind: 'cv', versionId: 'cv-ver-1', sha256: cvSha }],
      cv: { versionId: 'cv-ver-1', applicationId, jobId, sha256: cvSha, path: cvPath },
    },
    steps,
  };
}

test('full Greenhouse sandbox fixture capture produces valid DOM/network evidence verified through outcome & receipt contracts', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-e2e-ok-'));
  const fixture = await startGreenhouseSandboxFixture({ confirmationId: 'GH-SBX-849201' });
  const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: root,
    onPageCreated: fixture.attachToPage,
  });

  try {
    const runResult = await worker.execute(plan, { approve: true, headless: true });
    assert.equal(runResult.status, 'COMPLETED');
    assert.equal(fixture.submissions.length, 1);
    assert.equal(runResult.submitResult.confirmed, true);
    assert.equal(runResult.submitResult.confirmationNumber, 'GH-SBX-849201');
    assert.deepEqual(runResult.submitResult.signals, [
      'GREENHOUSE_SUBMISSION_RESPONSE_2XX',
      'GREENHOUSE_CONFIRMATION_URL_MATCHED',
      'GREENHOUSE_CONFIRMATION_CONTAINER_PRESENT',
      'EMPLOYER_CONFIRMATION_ID_OBSERVED',
    ]);

    const outcome = computePlanOutcome(plan, runResult);
    assert.equal(outcome.outcome, 'SUBMITTED');
    assert.equal(outcome.result.length, 1);
    const entry = outcome.result[0];
    assert.equal(entry.confirmationNumber, 'GH-SBX-849201');
    assert.ok(entry.rawEvidence.includes('GH-SBX-849201'));
    assert.doesNotMatch(entry.rawEvidence, /secret_csrf_should_not_leak|<input|<script/);

    const serverSha256 = crypto.createHash('sha256').update(entry.rawEvidence, 'utf8').digest('hex');
    const verifiedReceipt = createGenuineReceipt({
      employerConfirmationId: entry.confirmationNumber,
      receiptSha256: serverSha256,
      rawEvidence: entry.rawEvidence,
      atsPlatform: 'GREENHOUSE',
      submittedAt: runResult.submitResult.observedAt,
      profileId: 'profile-sbx-1',
      applicationId: plan.correlation.applicationId,
      attestationConfirmed: true,
      observedSignals: entry.signals,
      sandboxPosting: true,
      candidateAuthorizedSandbox: true,
    });
    assert.equal(verifiedReceipt.employerConfirmationId, 'GH-SBX-849201');
    assert.equal(verifiedReceipt.receiptSha256, serverSha256);
  } finally {
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('negative: missing matching POST submission request keeps outcome RECONCILIATION_REQUIRED', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-no-post-'));
  const fixture = await startGreenhouseSandboxFixture({ includeSubmissionPost: false });
  const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: root,
    onPageCreated: fixture.attachToPage,
  });

  try {
    const runResult = await worker.execute(plan, { approve: true });
    assert.equal(runResult.submitResult.confirmed, false);
    assert.equal(fixture.analyticsRequests.length, 1);
    const outcome = computePlanOutcome(plan, runResult);
    assert.equal(outcome.outcome, 'RECONCILIATION_REQUIRED');
  } finally {
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('negative: unrelated POST without job_application payload cannot confirm submission', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-bad-post-'));
  const fixture = await startGreenhouseSandboxFixture({ includeJobApplicationKey: false });
  const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: root,
    onPageCreated: fixture.attachToPage,
  });

  try {
    const runResult = await worker.execute(plan, { approve: true });
    assert.equal(runResult.submitResult.confirmed, false);
    assert.equal(computePlanOutcome(plan, runResult).outcome, 'RECONCILIATION_REQUIRED');
  } finally {
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('negative: non-2xx submission response (422/500) cannot confirm even when confirmation DOM appears', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-500-'));
  const fixture = await startGreenhouseSandboxFixture({ submissionStatus: 500 });
  const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: root,
    onPageCreated: fixture.attachToPage,
  });

  try {
    const runResult = await worker.execute(plan, { approve: true });
    assert.equal(runResult.submitResult.confirmed, false);
    assert.equal(computePlanOutcome(plan, runResult).outcome, 'RECONCILIATION_REQUIRED');
  } finally {
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('negative: missing .confirmation container fails structural confirmation', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-no-container-'));
  const fixture = await startGreenhouseSandboxFixture({ includeConfirmationContainer: false });
  const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: root,
    onPageCreated: fixture.attachToPage,
  });

  try {
    const runResult = await worker.execute(plan, { approve: true });
    assert.equal(runResult.submitResult.confirmed, false);
    assert.equal(computePlanOutcome(plan, runResult).outcome, 'RECONCILIATION_REQUIRED');
  } finally {
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('negative: missing confirmation ID or requisition ID leaves confirmationNumber absent and receipt rejected', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-no-cid-'));
  const fixture = await startGreenhouseSandboxFixture({
    includeConfirmationId: false,
    confirmationText: 'Thanks! Requisition ID: REQ-8821',
  });
  const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: root,
    onPageCreated: fixture.attachToPage,
  });

  try {
    const runResult = await worker.execute(plan, { approve: true });
    assert.equal(runResult.submitResult.confirmed, true);
    assert.equal('confirmationNumber' in runResult.submitResult, false);
    const outcome = computePlanOutcome(plan, runResult);
    assert.equal('confirmationNumber' in outcome.result[0], false);

    assert.throws(
      () => createGenuineReceipt({
        employerConfirmationId: outcome.result[0].confirmationNumber,
        receiptSha256: crypto.createHash('sha256').update(outcome.result[0].rawEvidence, 'utf8').digest('hex'),
        rawEvidence: outcome.result[0].rawEvidence,
        atsPlatform: 'GREENHOUSE',
        submittedAt: runResult.submitResult.observedAt,
        profileId: 'p1',
        applicationId: 'a1',
        attestationConfirmed: true,
        observedSignals: outcome.result[0].signals,
        sandboxPosting: true,
        candidateAuthorizedSandbox: true,
      }),
      InvalidReceiptError,
    );
  } finally {
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('negative: CAPTCHA on page triggers HardStopError before submit attempt', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-captcha-'));
  const fixture = await startGreenhouseSandboxFixture({ captchaBeforeClick: true });
  const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: root,
    onPageCreated: fixture.attachToPage,
  });

  try {
    await assert.rejects(() => worker.execute(plan, { approve: true }), HardStopError);
    assert.equal(fixture.submissions.length, 0);
  } finally {
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('negative: crash before click and crash after click refuse automatic retry (zero duplicate submissions)', async () => {
  for (const mode of ['before_click', 'after_click']) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), `gh-sandbox-crash-${mode}-`));
    const fixture = await startGreenhouseSandboxFixture({ confirmationId: 'GH-SBX-77123' });
    const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
    const firstWorker = new BrowserWorker({
      statePath: path.join(root, 'state.json'),
      artifactDir: root,
      onPageCreated: fixture.attachToPage,
    });

    try {
      const execOpts = mode === 'before_click'
        ? { approve: true, failAfterAttemptMarker: 'real-submit-click' }
        : { approve: true, failDuringStep: 'real-submit-click' };

      await assert.rejects(() => firstWorker.execute(plan, execOpts), /SIMULATED_WORKER_CRASH/);
      assert.equal(fixture.submissions.length, mode === 'before_click' ? 0 : 1);

      const secondWorker = new BrowserWorker({
        statePath: path.join(root, 'state.json'),
        artifactDir: root,
        onPageCreated: fixture.attachToPage,
      });
      const resumed = await secondWorker.execute(plan, { approve: true });
      assert.equal(resumed.submitResult.confirmed, false);
      assert.equal(resumed.submitResult.category, 'PRIOR_ATTEMPT_UNVERIFIED');
      assert.equal(fixture.submissions.length, mode === 'before_click' ? 0 : 1);
      assert.equal(computePlanOutcome(plan, resumed).outcome, 'RECONCILIATION_REQUIRED');
    } finally {
      await fixture.close();
      fs.rmSync(root, { recursive: true, force: true });
    }
  }
});

test('executePollCycle materializes backend Greenhouse artifacts and POSTs captured SUBMISSION_EVIDENCE to /complete', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-poll-'));
  const fixture = await startGreenhouseSandboxFixture({ confirmationId: 'GH-SBX-POLL1' });
  const cvBytes = Buffer.from('%PDF-1.4\npoll cycle sandbox cv\n', 'utf8');
  const cvSha = crypto.createHash('sha256').update(cvBytes).digest('hex');
  const planId = 'plan-app-poll-1';
  const applicationId = 'app-poll-1';
  const jobId = 'job-poll-1';
  const artifactRelUrl = `/api/v1/automation/plans/${planId}/artifacts/cv?versionId=cv-ver-poll`;

  const fields = [
    { key: 'first_name', label: 'First name', htmlType: 'text', required: true, selector: '#first_name', classification: 'SUPPORTED_AUTO', value: 'Ada', valueSource: 'profile', reason: 'verified profile', options: [] },
    { key: 'last_name', label: 'Last name', htmlType: 'text', required: true, selector: '#last_name', classification: 'SUPPORTED_AUTO', value: 'Lovelace', valueSource: 'profile', reason: 'verified profile', options: [] },
    { key: 'email', label: 'Email address', htmlType: 'email', required: true, selector: '#email', classification: 'SUPPORTED_AUTO', value: 'ada@example.local', valueSource: 'profile', reason: 'verified profile', options: [] },
    { key: 'resume', label: 'Resume', htmlType: 'file', required: false, selector: '#resume', classification: 'SUPPORTED_AUTO', value: 'resume.pdf', valueSource: 'cv', reason: 'approved cv', options: [] },
  ];

  // Mirrors GreenhouseExecutionPlanService: backend upload step carries NO local filesystem `path`
  const storedPlan = {
    planId,
    planType: 'GREENHOUSE',
    version: 1,
    targetUrl: fixture.targetUrl,
    safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
    correlation: { applicationId, jobId },
    fields,
    requiredGaps: [],
    steps: [
      { id: 'navigate-application', type: StepType.NAVIGATE, policy: Policy.AUTO, params: { url: fixture.targetUrl } },
      { id: 'fill-first_name', type: StepType.FILL_FIELD, policy: Policy.AUTO, params: { selector: '#first_name', value: 'Ada' } },
      { id: 'fill-last_name', type: StepType.FILL_FIELD, policy: Policy.AUTO, params: { selector: '#last_name', value: 'Lovelace' } },
      { id: 'fill-email', type: StepType.FILL_FIELD, policy: Policy.AUTO, params: { selector: '#email', value: 'ada@example.local' } },
      {
        id: 'upload-resume',
        type: StepType.UPLOAD_FILE,
        policy: Policy.AUTO,
        params: {
          selector: '#resume',
          artifact: 'cv',
          expectedJobId: jobId,
          expectedApplicationId: applicationId,
          expectedVersionId: 'cv-ver-poll',
          sha256: cvSha,
        },
      },
      { id: 'validate-form', type: StepType.VALIDATE, policy: Policy.AUTO, params: {} },
      { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: Policy.AUTO, params: { selector: 'button[type="submit"]' } },
    ],
  };

  let completionBody = null;
  const backendServer = http.createServer(async (req, res) => {
    const reqUrl = new URL(req.url, 'http://127.0.0.1');
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const bodyText = Buffer.concat(chunks).toString('utf8');

    if (req.method === 'POST' && reqUrl.pathname === '/api/v1/automation/plans/claim-next') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ id: planId, applicationId, targetUrl: fixture.targetUrl, submitApproved: true, plan: storedPlan }));
      return;
    }
    if (req.method === 'GET' && reqUrl.pathname === `/api/v1/automation/plans/${planId}/package`) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        planId,
        applicationId,
        jobId,
        expectedUrl: fixture.targetUrl,
        safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
        fields,
        requiredGaps: [],
        cv: { versionId: 'cv-ver-poll', applicationId, jobId, url: artifactRelUrl, sha256: cvSha },
        artifacts: [{ kind: 'cv', versionId: 'cv-ver-poll', url: artifactRelUrl, sha256: cvSha, fileName: 'resume.pdf' }],
      }));
      return;
    }
    if (req.method === 'GET' && reqUrl.pathname === `/api/v1/automation/plans/${planId}/artifacts/cv`) {
      res.writeHead(200, { 'Content-Type': 'application/pdf' });
      res.end(cvBytes);
      return;
    }
    if (req.method === 'POST' && reqUrl.pathname === `/api/v1/automation/plans/${planId}/heartbeat`) {
      res.writeHead(204);
      res.end();
      return;
    }
    if (req.method === 'POST' && reqUrl.pathname === `/api/v1/automation/plans/${planId}/complete`) {
      completionBody = JSON.parse(bodyText);
      res.writeHead(204);
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  });

  await new Promise((resolve) => backendServer.listen(0, '127.0.0.1', resolve));
  const { port } = backendServer.address();
  const backendOrigin = `http://127.0.0.1:${port}`;
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: pollArtifactRoot,
    onPageCreated: fixture.attachToPage,
  });

  try {
    const cycleResult = await executePollCycle({
      pollUrl: `${backendOrigin}/api/v1/automation/plans/claim-next`,
      apiCallFn: (url, body) => apiCall(url, body, { authHeadersFn: () => ({ Authorization: 'Bearer test-token' }) }),
      authHeadersFn: () => ({ Authorization: 'Bearer test-token' }),
      reportOutcomeFn: (id, outcome, detail, result) => reportOutcome(
        id, outcome, detail, result,
        {
          completeUrlVal: `${backendOrigin}/api/v1/automation/plans/{id}/complete`,
          apiCallFn: (url, body) => apiCall(url, body, { authHeadersFn: () => ({ Authorization: 'Bearer test-token' }) }),
        },
      ),
      sendHeartbeatFn: (id) => sendHeartbeat(
        id,
        {
          heartbeatUrlVal: `${backendOrigin}/api/v1/automation/plans/{id}/heartbeat`,
          apiCallFn: (url, body) => apiCall(url, body, { authHeadersFn: () => ({ Authorization: 'Bearer test-token' }) }),
        },
      ),
      runner: worker,
      eventSinkFn: async () => {},
    });

    assert.equal(cycleResult.status, 'COMPLETED');
    assert.equal(completionBody.outcome, 'SUBMITTED');
    assert.equal(completionBody.result.length, 1);
    const entry = completionBody.result[0];
    assert.equal(entry.type, 'SUBMISSION_EVIDENCE');
    assert.equal(entry.planType, 'GREENHOUSE');
    assert.equal(entry.confirmed, true);
    assert.equal(entry.confirmationNumber, 'GH-SBX-POLL1');
    assert.ok(entry.rawEvidence.includes('GH-SBX-POLL1'));

    assert.throws(
      () => createGenuineReceipt({
        employerConfirmationId: entry.confirmationNumber,
        receiptSha256: '0'.repeat(64),
        rawEvidence: entry.rawEvidence,
        atsPlatform: 'GREENHOUSE',
        submittedAt: new Date().toISOString(),
        profileId: 'profile-sbx-1',
        applicationId,
        attestationConfirmed: true,
        observedSignals: entry.signals,
        sandboxPosting: true,
        candidateAuthorizedSandbox: true,
      }),
      InvalidReceiptError,
    );
  } finally {
    await new Promise((resolve, reject) => backendServer.close((err) => (err ? reject(err) : resolve())));
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('negative: login wall after submit triggers HardStopError and mismatched postTargetPath cannot confirm', async () => {
  const root1 = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-login-'));
  const loginFixture = await startGreenhouseSandboxFixture({ loginWallAfterSubmit: true });
  const plan1 = buildSandboxGreenhousePlan(root1, loginFixture.targetUrl);
  const worker1 = new BrowserWorker({
    statePath: path.join(root1, 'state.json'),
    artifactDir: root1,
    onPageCreated: loginFixture.attachToPage,
  });

  try {
    await assert.rejects(() => worker1.execute(plan1, { approve: true }), HardStopError);
  } finally {
    await loginFixture.close();
    fs.rmSync(root1, { recursive: true, force: true });
  }

  const root2 = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-postpath-'));
  const wrongPathFixture = await startGreenhouseSandboxFixture({ postTargetPath: '/other-board/jobs/999' });
  const plan2 = buildSandboxGreenhousePlan(root2, wrongPathFixture.targetUrl);
  const worker2 = new BrowserWorker({
    statePath: path.join(root2, 'state.json'),
    artifactDir: root2,
    onPageCreated: wrongPathFixture.attachToPage,
  });

  try {
    const runResult = await worker2.execute(plan2, { approve: true });
    assert.equal(runResult.submitResult.confirmed, false);
    assert.equal(computePlanOutcome(plan2, runResult).outcome, 'RECONCILIATION_REQUIRED');
  } finally {
    await wrongPathFixture.close();
    fs.rmSync(root2, { recursive: true, force: true });
  }
});

test('negative: hidden inline script in confirmation DOM is stripped from containerText and tampered upload path is rejected', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-sandbox-xss-upload-'));
  const fixture = await startGreenhouseSandboxFixture({
    confirmationId: 'GH-SBX-CLEAN1',
    confirmationText: 'Submitted cleanly.<script>window.__LEAKED_SCRIPT__="bad_secret";</script><input value="hidden_val" />',
  });
  const plan = buildSandboxGreenhousePlan(root, fixture.targetUrl);
  const worker = new BrowserWorker({
    statePath: path.join(root, 'state.json'),
    artifactDir: root,
    onPageCreated: fixture.attachToPage,
  });

  try {
    const okRun = await worker.execute(plan, { approve: true });
    assert.equal(okRun.submitResult.confirmed, true);
    const parsedRaw = JSON.parse(okRun.submitResult.rawEvidence);
    assert.doesNotMatch(parsedRaw.confirmationText, /bad_secret|__LEAKED_SCRIPT__|hidden_val/);
    assert.doesNotMatch(parsedRaw.confirmationContainerHtml, /bad_secret|<script|<input/);

    const evilPlan = buildSandboxGreenhousePlan(root, fixture.targetUrl, { applicationId: 'app-sbx-tamper', jobId: 'job-sbx-tamper' });
    const evilFile = path.join(root, 'evil.pdf');
    fs.writeFileSync(evilFile, 'evil');
    evilPlan.steps.find((s) => s.id === 'upload-resume').params.path = evilFile;
    const workerTamper = new BrowserWorker({
      statePath: path.join(root, 'state-tamper.json'),
      artifactDir: root,
      onPageCreated: fixture.attachToPage,
    });
    await assert.rejects(
      () => workerTamper.execute(evilPlan, { approve: true }),
      (err) => err instanceof HardStopError && err.reason === 'GREENHOUSE_ARTIFACT_CORRELATION_MISMATCH',
    );
  } finally {
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});
