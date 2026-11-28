import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { computePlanOutcome } from '../src/outcome.js';
import { isProhibitedDeclaration, planUniversalControls } from '../src/universal_form.js';
import { startUniversalEmployerFixture } from './fixtures/universal_employer_fixture.js';

test('computePlanOutcome routes pre-fence non-duplicate authorization denials to HUMAN_REQUIRED with AUTHORIZATION_REFUSED across all submit adapters', () => {
  const cases = [
    { planType: 'GREENHOUSE', stepType: 'REAL_SUBMIT_CLICK', field: 'submitResult' },
    { planType: 'ASHBY', stepType: 'ASHBY_SUBMIT_CLICK', field: 'ashbySubmitResult' },
    { planType: 'WORKDAY', stepType: 'WORKDAY_SUBMIT_CLICK', field: 'workdaySubmitResult' },
    { planType: 'UNIVERSAL', stepType: 'UNIVERSAL_SUBMIT_CLICK', field: 'universalSubmitResult' },
  ];

  for (const c of cases) {
    const plan = {
      planType: c.planType,
      targetUrl: 'https://employer.example/apply',
      steps: [{ id: 's1', type: c.stepType, policy: 'AUTO', params: {} }],
    };

    for (const code of [
      'SUBMIT_APPROVAL_WITHDRAWN',
      'STANDING_PERMISSION_WITHDRAWN',
      'ELIGIBILITY_CONSTRAINT_FAILED',
      'TARGET_DOMAIN_MISMATCH',
    ]) {
      const out = computePlanOutcome(plan, {
        [c.field]: {
          confirmed: false,
          category: code,
          authorizationDenied: true,
          authorizationCode: code,
          evidence: `Denied pre-fence with ${code}`,
        },
      });
      assert.equal(out.outcome, 'HUMAN_REQUIRED', `${c.planType}/${code} must return HUMAN_REQUIRED`);
      assert.doesNotMatch(out.detail, /Submit click completed/i);
      assert.equal(out.result[0].type, 'AUTHORIZATION_REFUSED');
      assert.equal(out.result[0].code, code);
      assert.equal(out.result[0].clicked, false);
    }

    // Post-fence duplicates and real click uncertainty MUST still require reconciliation
    const dupOut = computePlanOutcome(plan, {
      [c.field]: {
        confirmed: false,
        category: 'DUPLICATE_PRE_SUBMIT_ATTEMPT',
        authorizationDenied: false,
        authorizationCode: 'DUPLICATE_PRE_SUBMIT_ATTEMPT',
        evidence: 'Prior attempt already exists',
      },
    });
    assert.equal(dupOut.outcome, 'RECONCILIATION_REQUIRED');
    assert.equal(dupOut.result[0].type, 'SUBMISSION_RECONCILIATION');

    const postClickOut = computePlanOutcome(plan, {
      [c.field]: {
        confirmed: false,
        category: 'UNKNOWN',
        authorizationDenied: false,
        evidence: 'HTTP 500 after click',
      },
    });
    assert.equal(postClickOut.outcome, 'RECONCILIATION_REQUIRED');
    assert.equal(postClickOut.result[0].type, 'SUBMISSION_RECONCILIATION');
  }
});

test('universal_form flags prohibited legal declarations even if confirmedAnswers attempts to answer them and never checks them', () => {
  assert.equal(isProhibitedDeclaration('i certify that all information is true and accurate'), true);
  assert.equal(isProhibitedDeclaration('i consent to a background check and drug screening'), true);
  assert.equal(isProhibitedDeclaration('i agree to the terms and conditions and privacy policy'), true);
  assert.equal(isProhibitedDeclaration('what is your notice period'), false);

  const inspected = {
    controls: [
      {
        controlId: 'c1',
        tagName: 'input',
        htmlType: 'text',
        label: 'Full name',
        nameAttr: 'full_name',
        idAttr: 'full_name',
        required: true,
      },
      {
        controlId: 'c2',
        tagName: 'input',
        htmlType: 'checkbox',
        label: 'I hereby certify under penalty of perjury that all statements are true and accurate',
        nameAttr: 'certify_accuracy',
        idAttr: 'certify_accuracy',
        required: true,
      },
      {
        controlId: 'c3',
        tagName: 'input',
        htmlType: 'checkbox',
        label: 'I agree to the terms and conditions and privacy policy (optional)',
        nameAttr: 'terms_opt',
        idAttr: 'terms_opt',
        required: false,
      },
    ],
  };

  const plan = planUniversalControls(inspected, {
    profileFacts: { fullName: 'Ada Lovelace' },
    confirmedAnswers: [
      {
        questionText: 'I hereby certify under penalty of perjury that all statements are true and accurate',
        answerText: 'Yes',
      },
      {
        questionText: 'I agree to the terms and conditions and privacy policy (optional)',
        answerText: 'Yes',
      },
    ],
  });

  assert.equal(plan.actions.length, 1, 'Only safe identity text control should be planned');
  assert.equal(plan.actions[0].controlId, 'c1');
  assert.equal(plan.requiredGaps.length, 1);
  assert.equal(plan.requiredGaps[0].category, 'PROHIBITED_DECLARATION');
  assert.equal(plan.requiredGaps[0].prohibitedDeclaration, true);
  assert.equal(plan.unsupported.length, 1);
  assert.equal(plan.unsupported[0].category, 'PROHIBITED_DECLARATION');
});

test('cross-process real node worker.js subprocess acquires pre-submit fence and clicks once on live fixture', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'phase8-xproc-'));
  const statePath = path.join(root, 'worker-state.json');
  const artifactDir = path.join(root, 'artifacts');
  const fixture = await startUniversalEmployerFixture({ confirmationId: 'EMP-2026-8841' });

  const planId = '00000000-0000-7000-8000-00000000e801';
  const appId = '00000000-0000-7000-8000-00000000e802';
  const jobId = '00000000-0000-7000-8000-00000000e803';
  const cvVersionId = '00000000-0000-7000-8000-00000000e804';
  const cvBytes = Buffer.from('%PDF-1.4\n%CrossProcessTest\n%%EOF\n', 'utf8');
  const { createHash } = await import('node:crypto');
  const cvSha = createHash('sha256').update(cvBytes).digest('hex');

  let claimedCount = 0;
  let fenceRequests = 0;
  let completedBody = null;
  let withdrawnPreFence = false;

  const backendServer = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const bodyText = Buffer.concat(chunks).toString('utf8');
    const url = new URL(req.url, 'http://127.0.0.1');

    if (req.method === 'POST' && url.pathname === '/api/v1/automation/plans/claim-next') {
      if (claimedCount >= 1) {
        res.writeHead(204).end();
        return;
      }
      claimedCount += 1;
      const planBody = {
        id: planId,
        applicationId: appId,
        targetUrl: fixture.targetUrl,
        status: 'RUNNING',
        submitApproved: true,
        plan: {
          planType: 'UNIVERSAL',
          version: 1,
          authorizationMode: 'STANDING_AUTO_SUBMIT',
          correlation: {
            planId,
            applicationId: appId,
            jobId,
            employerDomain: '127.0.0.1',
          },
          targetUrl: fixture.targetUrl,
          steps: [
            { id: 'nav-1', type: 'NAVIGATE', policy: 'AUTO', params: { url: fixture.targetUrl } },
            { id: 'apply-1', type: 'UNIVERSAL_APPLY', policy: 'AUTO', params: {} },
            { id: 'submit-1', type: 'UNIVERSAL_SUBMIT_CLICK', policy: 'AUTO', params: { selector: '[data-robin-submit]' } },
          ],
          package: {
            planId,
            applicationId: appId,
            jobId,
            expectedUrl: fixture.targetUrl,
            allowLocalTestHosts: true,
            authorizationMode: 'STANDING_AUTO_SUBMIT',
            cv: {
              versionId: cvVersionId,
              sha256: cvSha,
              jobId,
              applicationId: appId,
              fileName: 'cv.pdf',
              contentType: 'application/pdf',
              byteSize: cvBytes.length,
              url: `/api/v1/automation/plans/${planId}/artifacts/cv?versionId=${cvVersionId}`,
            },
            profileFacts: {
              firstName: 'Ada',
              lastName: 'Lovelace',
              fullName: 'Ada Lovelace',
              email: 'ada@inbox.example',
              phone: '+44 7700 900077',
              linkedin: 'https://linkedin.com/in/adalovelace',
            },
            confirmedAnswers: [
              { questionText: 'Are you legally authorized to work in the United Kingdom?', answerText: 'Yes' },
              { questionText: 'What is your current notice period?', answerText: '4 weeks' },
            ],
            artifacts: [{
              kind: 'cv',
              versionId: cvVersionId,
              sha256: cvSha,
              fileName: 'cv.pdf',
              contentType: 'application/pdf',
              byteSize: cvBytes.length,
              url: `/api/v1/automation/plans/${planId}/artifacts/cv?versionId=${cvVersionId}`,
            }],
          },
        },
        result: [],
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(planBody));
      return;
    }

    if (req.method === 'GET' && url.pathname === `/api/v1/automation/plans/${planId}/artifacts/cv`) {
      res.writeHead(200, { 'Content-Type': 'application/pdf', 'X-Artifact-Sha256': cvSha });
      res.end(cvBytes);
      return;
    }

    if (req.method === 'POST' && url.pathname === `/api/v1/automation/plans/${planId}/discovered-questions`) {
      res.writeHead(201, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ recorded: 0 }));
      return;
    }

    if (req.method === 'POST' && url.pathname === `/api/v1/automation/plans/${planId}/heartbeat`) {
      res.writeHead(204).end();
      return;
    }

    if (req.method === 'POST' && url.pathname === `/api/v1/automation/plans/${planId}/steps`) {
      const payload = JSON.parse(bodyText || '{}');
      if (payload?.result?.phase === 'PRE_SUBMIT_ATTEMPTING') {
        fenceRequests += 1;
        if (withdrawnPreFence) {
          res.writeHead(409, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ code: 'STANDING_PERMISSION_WITHDRAWN', error: 'Revoked pre-fence' }));
          return;
        }
      }
      res.writeHead(204).end();
      return;
    }

    if (req.method === 'POST' && url.pathname === `/api/v1/automation/plans/${planId}/complete`) {
      completedBody = JSON.parse(bodyText || '{}');
      res.writeHead(204).end();
      return;
    }

    res.writeHead(404).end();
  });

  await new Promise((resolve) => backendServer.listen(0, '127.0.0.1', resolve));
  const { port } = backendServer.address();
  const child = spawn(process.execPath, [path.resolve('src/worker.js')], {
    cwd: path.resolve('.'),
    env: {
      ...process.env,
      WORKER_POLL_URL: `http://127.0.0.1:${port}/api/v1/automation/plans/claim-next`,
      WORKER_COMPLETE_URL: `http://127.0.0.1:${port}/api/v1/automation/plans/{id}/complete`,
      WORKER_HEARTBEAT_URL: `http://127.0.0.1:${port}/api/v1/automation/plans/{id}/heartbeat`,
      WORKER_EVENT_TOKEN: 'phase8-xproc-token',
      WORKER_POLL_INTERVAL_MS: '150',
      WORKER_STATE_PATH: statePath,
      WORKER_ARTIFACT_DIR: artifactDir,
      ROBIN_WORKER_SESSION_KEY: 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let logs = '';
  child.stdout.on('data', (d) => { logs += d.toString(); });
  child.stderr.on('data', (d) => { logs += d.toString(); });

  try {
    const deadline = Date.now() + 25000;
    while (!completedBody && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 150));
    }
    assert.ok(completedBody, `Real node worker.js subprocess should complete polling cycle. Logs:\n${logs}`);
    assert.equal(completedBody.outcome, 'SUBMITTED');
    assert.equal(fenceRequests, 1);
    assert.equal(fixture.submissions.length, 1);
    assert.equal(completedBody.result?.[0]?.confirmationNumber, 'EMP-2026-8841');
  } finally {
    child.kill('SIGTERM');
    await new Promise((r) => child.once('exit', r));
    await new Promise((resolve) => backendServer.close(resolve));
    await fixture.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});
