import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import { StepType, Policy } from '../src/plan.js';
import { computePlanOutcome } from '../src/outcome.js';
import { startGreenhouseSandboxFixture } from './fixtures/greenhouse_sandbox_fixture.js';
import { startUniversalEmployerFixture } from './fixtures/universal_employer_fixture.js';

const pollArtifactRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'fence-parity-artifacts-'));
process.env.WORKER_ARTIFACT_DIR = pollArtifactRoot;
const { executePollCycle, reportOutcome, apiCall, sendHeartbeat } = await import('../src/worker.js');
test.after(() => fs.rmSync(pollArtifactRoot, { recursive: true, force: true }));

const ASHBY_URL = 'https://jobs.ashbyhq.com/ramp/fence-parity/application';
const WORKDAY_URL = 'https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/x/apply';

const ASHBY_HTML = `<!doctype html><html><body>
  <form id="application-form">
    <div class="ashby-application-form-field-entry" data-field-path="_systemfield_name">
      <label class="ashby-application-form-question-title">Legal Name</label>
      <div><input type="text" required></div>
    </div>
    <button class="ashby-application-form-submit-button" type="submit">Submit Application</button>
  </form>
  <script>
    document.querySelector('.ashby-application-form-submit-button').addEventListener('click', async (e) => {
      e.preventDefault();
      const res = await fetch('https://api.ashbyhq.com/applicationForm.submit', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
      });
      const body = await res.json();
      if (body.success) document.getElementById('application-form').remove();
    });
  </script>
</body></html>`;

const WORKDAY_REVIEW_HTML = `<!doctype html><html><body>
  <h1>Review</h1>
  <button type="button" data-automation-id="review-submit-button"
          onclick="fetch('${WORKDAY_URL}?submitted=1').then(() => { document.body.innerHTML = '<h1>Application Submitted</h1><p>Thank you for applying</p>'; })">
    Submit
  </button>
</body></html>`;

function buildAdapterScenario(root, adapter) {
  if (adapter === 'GREENHOUSE') {
    const url = 'https://boards.greenhouse.io/acme/jobs/777';
    let clicks = 0;
    const plan = {
      planId: 'plan-fence-gh',
      planType: 'GREENHOUSE',
      version: 1,
      targetUrl: url,
      safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
      correlation: { applicationId: 'app-gh-1', jobId: 'job-gh-1' },
      fields: [],
      requiredGaps: [],
      package: {
        planId: 'plan-fence-gh',
        applicationId: 'app-gh-1',
        jobId: 'job-gh-1',
        expectedUrl: url,
        safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
        fields: [],
        artifacts: [],
      },
      steps: [
        { id: 'navigate-application', type: StepType.NAVIGATE, policy: Policy.AUTO, params: { url } },
        { id: 'validate-form', type: StepType.VALIDATE, policy: Policy.AUTO, params: {} },
        { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: Policy.AUTO, params: { selector: 'button[type="submit"]' } },
      ],
    };
    const onPageCreated = async (page) => {
      await page.route(`${url}**`, (route) => {
        const req = route.request();
        if (req.method() === 'POST') {
          clicks += 1;
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true }) });
        }
        if (req.url().endsWith('/confirmation')) {
          return route.fulfill({
            status: 200,
            contentType: 'text/html',
            body: '<!doctype html><html><body><div class="confirmation">Thanks for applying!</div></body></html>',
          });
        }
        return route.fulfill({
          status: 200,
          contentType: 'text/html',
          body: `<!doctype html><html><body>
            <main>Review your application</main>
            <button type="submit" id="submit-btn">Submit</button>
            <script>
              document.getElementById('submit-btn').addEventListener('click', async () => {
                const res = await fetch(location.pathname, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ job_application: { id: 1 }, fingerprint: 'test-fingerprint' }),
                });
                if (res.ok) location.href = location.pathname + '/confirmation';
              });
            </script>
          </body></html>`,
        });
      });
    };
    return {
      plan,
      expectedStepType: StepType.REAL_SUBMIT_CLICK,
      submitField: 'submitResult',
      getClicks: () => clicks,
      onPageCreated,
    };
  }

  if (adapter === 'ASHBY') {
    let clicks = 0;
    const plan = {
      planId: 'plan-fence-ashby',
      planType: 'ASHBY',
      version: 1,
      targetUrl: ASHBY_URL,
      safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
      correlation: { applicationId: 'app-ashby-1', jobId: 'job-ashby-1' },
      steps: [
        { id: 'navigate-application', type: StepType.NAVIGATE, policy: Policy.AUTO, params: { url: ASHBY_URL } },
        { id: 'ashby-apply', type: StepType.ASHBY_APPLY, policy: Policy.AUTO, params: { identity: { fullName: 'Ada Lovelace', email: 'ada@example.test' } } },
        { id: 'ashby-submit-click', type: StepType.ASHBY_SUBMIT_CLICK, policy: Policy.AUTO, params: {} },
      ],
    };
    const onPageCreated = async (page) => {
      await page.route(`${ASHBY_URL}**`, (route) => route.fulfill({ status: 200, contentType: 'text/html', body: ASHBY_HTML }));
      await page.route('https://api.ashbyhq.com/**', (route) => {
        clicks += 1;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true }) });
      });
    };
    return {
      plan,
      expectedStepType: StepType.ASHBY_SUBMIT_CLICK,
      submitField: 'ashbySubmitResult',
      getClicks: () => clicks,
      onPageCreated,
    };
  }

  if (adapter === 'WORKDAY') {
    let clicks = 0;
    const plan = {
      planId: 'plan-fence-workday',
      planType: 'WORKDAY',
      version: 1,
      targetUrl: WORKDAY_URL,
      safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
      correlation: { applicationId: 'app-wd-1', jobId: 'job-wd-1', employerDomain: 'nvidia.wd5.myworkdayjobs.com' },
      steps: [
        { id: 'navigate-application', type: StepType.NAVIGATE, policy: Policy.AUTO, params: { url: WORKDAY_URL } },
        { id: 'workday-apply', type: StepType.WORKDAY_APPLY, policy: Policy.AUTO, params: { identity: {} } },
        { id: 'workday-submit-click', type: StepType.WORKDAY_SUBMIT_CLICK, policy: Policy.AUTO, params: {} },
      ],
    };
    const onPageCreated = async (page) => {
      await page.route(`${WORKDAY_URL}**`, (route) => {
        if (route.request().url().includes('submitted=1')) {
          clicks += 1;
          return route.fulfill({ status: 200, contentType: 'application/json', body: '{"submitted":true}' });
        }
        return route.fulfill({ status: 200, contentType: 'text/html', body: WORKDAY_REVIEW_HTML });
      });
    };
    return {
      plan,
      expectedStepType: StepType.WORKDAY_SUBMIT_CLICK,
      submitField: 'workdaySubmitResult',
      getClicks: () => clicks,
      onPageCreated,
    };
  }

  throw new Error(`Unknown adapter: ${adapter}`);
}

async function setupUniversalScenario(root) {
  const fixture = await startUniversalEmployerFixture();
  const cvPath = path.join(root, 'univ-cv.pdf');
  const cvBytes = Buffer.from('%PDF-1.4\nuniversal fence parity cv\n');
  fs.writeFileSync(cvPath, cvBytes);
  const cvSha = crypto.createHash('sha256').update(cvBytes).digest('hex');
  const plan = {
    planId: 'plan-fence-univ',
    planType: 'UNIVERSAL',
    version: 1,
    targetUrl: fixture.targetUrl,
    safetyContract: 'STEP_ALLOWLIST_NO_AUTO_SUBMIT',
    correlation: { applicationId: 'app-univ-1', jobId: 'job-univ-1' },
    package: {
      applicationId: 'app-univ-1',
      jobId: 'job-univ-1',
      expectedUrl: fixture.targetUrl,
      allowLocalTestHosts: true,
      profileFacts: {
        fullName: 'Ada Lovelace',
        firstName: 'Ada',
        lastName: 'Lovelace',
        email: 'ada@example.test',
        phone: '+447700900111',
        location: 'London',
      },
      cv: {
        versionId: 'cv-univ-1',
        applicationId: 'app-univ-1',
        jobId: 'job-univ-1',
        sha256: cvSha,
        fileName: 'univ-cv.pdf',
        url: '/api/v1/automation/plans/plan-fence-univ/artifacts/cv',
        path: cvPath,
      },
      artifacts: [{
        kind: 'cv',
        versionId: 'cv-univ-1',
        sha256: cvSha,
        fileName: 'univ-cv.pdf',
        byteSize: cvBytes.length,
        url: '/api/v1/automation/plans/plan-fence-univ/artifacts/cv',
      }],
      confirmedAnswers: [
        { answerId: 'a1', questionText: 'Are you legally authorized to work in the United Kingdom?', answerText: 'Yes' },
        { answerId: 'a2', questionText: 'What is your current notice period?', answerText: '4 weeks' },
      ],
    },
    steps: [
      { id: 'navigate-application', type: StepType.NAVIGATE, policy: Policy.AUTO, params: { url: fixture.targetUrl } },
      { id: 'universal-apply', type: StepType.UNIVERSAL_APPLY, policy: Policy.AUTO, params: {} },
      { id: 'universal-submit-click', type: StepType.UNIVERSAL_SUBMIT_CLICK, policy: Policy.AUTO, params: { selector: '[data-robin-submit]' } },
    ],
  };
  return {
    plan,
    expectedStepType: StepType.UNIVERSAL_SUBMIT_CLICK,
    submitField: 'universalSubmitResult',
    getClicks: () => fixture.submissions.length,
    onPageCreated: undefined,
    close: () => fixture.close(),
  };
}

for (const adapter of ['GREENHOUSE', 'ASHBY', 'WORKDAY', 'UNIVERSAL']) {
  test(`${adapter}: pre-submit authorization parity across allow, missing reporter, 409 withdrawal, duplicate 409, crash restart, and 503 unavailability`, async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), `fence-parity-${adapter.toLowerCase()}-`));
    const scenario = adapter === 'UNIVERSAL'
      ? await setupUniversalScenario(root)
      : buildAdapterScenario(root, adapter);

    try {
      // 1. Happy path: fence MUST be called strictly before submit click with exact adapter & stepType metadata
      {
        const events = [];
        let clicksAtFenceCall = -1;
        const w = new BrowserWorker({
          statePath: path.join(root, 's-ok.json'),
          artifactDir: root,
          onPageCreated: scenario.onPageCreated,
          stepsReporter: async (planId, body) => {
            if (body?.result?.phase === 'PRE_SUBMIT_ATTEMPTING') {
              clicksAtFenceCall = scenario.getClicks();
              events.push(body);
            }
            return { ok: true };
          },
        });
        const res = await w.execute(scenario.plan, { approve: true, headless: true });
        assert.equal(res.status, 'COMPLETED');
        assert.equal(res[scenario.submitField]?.confirmed, true);
        assert.equal(events.length, 1);
        assert.equal(clicksAtFenceCall, 0, `${adapter}: PRE_SUBMIT_ATTEMPTING must fire strictly before browser click`);
        assert.equal(events[0].result.atsPlatform, adapter);
        assert.equal(events[0].result.stepType, scenario.expectedStepType);
        assert.ok(events[0].result.currentUrl, `${adapter}: currentUrl must be forwarded to backend fence`);
      }

      // 2. Missing stepsReporter (self-authorization blocked): fails closed with zero clicks
      {
        const clicksBefore = scenario.getClicks();
        const wNoReporter = new BrowserWorker({
          statePath: path.join(root, 's-no-rep.json'),
          artifactDir: root,
          onPageCreated: scenario.onPageCreated,
        });
        await assert.rejects(
          () => wNoReporter.execute(scenario.plan, { approve: true, headless: true }),
          (err) => err instanceof HardStopError && err.reason === 'PRE_SUBMIT_AUTHORIZATION_UNAVAILABLE',
        );
        assert.equal(scenario.getClicks(), clicksBefore, `${adapter}: missing reporter must yield 0 submit clicks`);
      }

      // 3. Backend refuses with 409 SUBMIT_APPROVAL_WITHDRAWN -> 0 clicks, outcome never SUBMITTED
      {
        const clicksBefore = scenario.getClicks();
        const wWithdrawn = new BrowserWorker({
          statePath: path.join(root, 's-withdrawn.json'),
          artifactDir: root,
          onPageCreated: scenario.onPageCreated,
          stepsReporter: async () => ({
            conflict: true,
            code: 'SUBMIT_APPROVAL_WITHDRAWN',
            error: 'the submit approval was withdrawn before the submission click',
          }),
        });
        const res = await wWithdrawn.execute(scenario.plan, { approve: true, headless: true });
        assert.equal(scenario.getClicks(), clicksBefore, `${adapter}: withdrawn approval must execute 0 clicks`);
        assert.equal(res[scenario.submitField]?.confirmed, false);
        assert.equal(res[scenario.submitField]?.category, 'SUBMIT_APPROVAL_WITHDRAWN');
        const out = computePlanOutcome(scenario.plan, res);
        assert.equal(out.outcome, 'RECONCILIATION_REQUIRED');
        assert.notEqual(out.outcome, 'SUBMITTED');
      }

      // 4. Backend refuses with 409 DUPLICATE_PRE_SUBMIT_ATTEMPT -> 0 clicks, PRIOR_ATTEMPT_UNVERIFIED
      {
        const clicksBefore = scenario.getClicks();
        const wDup = new BrowserWorker({
          statePath: path.join(root, 's-dup.json'),
          artifactDir: root,
          onPageCreated: scenario.onPageCreated,
          stepsReporter: async () => ({
            conflict: true,
            code: 'DUPLICATE_PRE_SUBMIT_ATTEMPT',
            error: 'a pre-submit attempt checkpoint already exists',
          }),
        });
        const res = await wDup.execute(scenario.plan, { approve: true, headless: true });
        assert.equal(scenario.getClicks(), clicksBefore);
        assert.equal(res[scenario.submitField]?.confirmed, false);
        assert.equal(res[scenario.submitField]?.category, 'PRIOR_ATTEMPT_UNVERIFIED');
      }

      // 5. Restarted worker receives backend completedSteps carrying PRE_SUBMIT_ATTEMPTING -> 0 clicks
      {
        const clicksBefore = scenario.getClicks();
        let fenceRequests = 0;
        const planWithBackendCheckpoint = {
          ...scenario.plan,
          completedSteps: [{ stepKey: 'pre-submit-attempting', status: 'RUNNING', result: { phase: 'PRE_SUBMIT_ATTEMPTING' } }],
        };
        const wRestart = new BrowserWorker({
          statePath: path.join(root, 's-restart.json'),
          artifactDir: root,
          onPageCreated: scenario.onPageCreated,
          stepsReporter: async (_p, body) => {
            if (body?.result?.phase === 'PRE_SUBMIT_ATTEMPTING') fenceRequests += 1;
            return { ok: true };
          },
        });
        const res = await wRestart.execute(planWithBackendCheckpoint, { approve: true, headless: true });
        assert.equal(scenario.getClicks(), clicksBefore, `${adapter}: restarted worker must never re-click after PRE_SUBMIT_ATTEMPTING`);
        assert.equal(fenceRequests, 0, `${adapter}: restarted worker must short-circuit before calling fence`);
        assert.equal(res[scenario.submitField]?.confirmed, false);
        assert.equal(res[scenario.submitField]?.category, 'PRIOR_ATTEMPT_UNVERIFIED');
      }

      // 6. Real executePollCycle HTTP failure (503 unavailable) & HTTP 409 denial
      {
        let mode = '503';
        let completeBodies = [];
        let stepsRequests = 0;
        const clicksBefore = scenario.getClicks();
        const server = http.createServer(async (req, res) => {
          const reqUrl = new URL(req.url, 'http://127.0.0.1');
          const chunks = [];
          for await (const c of req) chunks.push(c);
          const body = Buffer.concat(chunks).toString('utf8');
          if (req.method === 'POST' && reqUrl.pathname === '/api/v1/automation/plans/claim-next') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
              id: scenario.plan.planId,
              applicationId: scenario.plan.correlation.applicationId,
              targetUrl: scenario.plan.targetUrl,
              submitApproved: true,
              plan: scenario.plan.planType === 'UNIVERSAL'
                ? {
                    ...scenario.plan,
                    package: (() => {
                      const { path: _ignored, ...cvMeta } = scenario.plan.package.cv;
                      return { ...scenario.plan.package, cv: cvMeta };
                    })(),
                  }
                : scenario.plan,
            }));
            return;
          }
          if (req.method === 'GET' && reqUrl.pathname.endsWith('/package')) {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            const basePkg = scenario.plan.package ?? {
              planId: scenario.plan.planId,
              applicationId: scenario.plan.correlation.applicationId,
              jobId: scenario.plan.correlation.jobId,
              expectedUrl: scenario.plan.targetUrl,
              fields: [],
              requiredGaps: [],
              artifacts: [],
            };
            const httpPkg = { ...basePkg };
            if (httpPkg.cv) {
              const { path: _ignoredLocalPath, ...cvWithoutPath } = httpPkg.cv;
              httpPkg.cv = {
                ...cvWithoutPath,
                fileName: 'univ-cv.pdf',
                url: `/api/v1/automation/plans/${scenario.plan.planId}/artifacts/cv`,
              };
            }
            res.end(JSON.stringify(httpPkg));
            return;
          }
          if (req.method === 'GET' && reqUrl.pathname.includes('/artifacts/')) {
            res.writeHead(200, { 'Content-Type': 'application/pdf' });
            res.end(Buffer.from('%PDF-1.4\nuniversal fence parity cv\n'));
            return;
          }
          if (req.method === 'POST' && reqUrl.pathname.endsWith('/steps')) {
            const stepBody = JSON.parse(body);
            if (stepBody?.result?.phase !== 'PRE_SUBMIT_ATTEMPTING') {
              res.writeHead(204);
              res.end();
              return;
            }
            stepsRequests += 1;
            if (mode === '503') {
              res.writeHead(503, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({ error: 'auth unavailable', code: 'PRE_SUBMIT_AUTH_UNAVAILABLE' }));
              return;
            }
            res.writeHead(409, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'eligibility changed', code: 'ELIGIBILITY_CONSTRAINT_FAILED' }));
            return;
          }
          if (req.method === 'POST' && reqUrl.pathname.endsWith('/complete')) {
            completeBodies.push(JSON.parse(body));
            res.writeHead(204);
            res.end();
            return;
          }
          if (req.method === 'POST' && reqUrl.pathname.endsWith('/heartbeat')) {
            res.writeHead(204);
            res.end();
            return;
          }
          res.writeHead(404);
          res.end();
        });
        await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
        const origin = `http://127.0.0.1:${server.address().port}`;
        try {
          const authHeadersFn = () => ({ Authorization: 'Bearer test-token' });
          const cycle503 = await executePollCycle({
            pollUrl: `${origin}/api/v1/automation/plans/claim-next`,
            apiCallFn: (url, body) => apiCall(url, body, { authHeadersFn, retryOptions: { maxRetries: 0, baseDelayMs: 1 } }),
            authHeadersFn,
            reportOutcomeFn: (id, outcome, detail, result) => reportOutcome(id, outcome, detail, result, {
              completeUrlVal: `${origin}/api/v1/automation/plans/{id}/complete`,
              apiCallFn: (url, body) => apiCall(url, body, { authHeadersFn }),
            }),
            sendHeartbeatFn: () => Promise.resolve(),
            runner: new BrowserWorker({
              statePath: path.join(root, 's-poll-503.json'),
              artifactDir: pollArtifactRoot,
              onPageCreated: scenario.onPageCreated,
            }),
            eventSinkFn: async () => {},
          });
          assert.equal(scenario.getClicks(), clicksBefore, `${adapter}: HTTP 503 on fence must execute 0 clicks`);
          assert.equal(cycle503.status, 'ERROR');
          assert.equal(completeBodies.length, 1);
          assert.equal(completeBodies[0].outcome, 'FAILED');
          assert.ok(stepsRequests >= 1);

          mode = '409';
          completeBodies = [];
          const cycle409 = await executePollCycle({
            pollUrl: `${origin}/api/v1/automation/plans/claim-next`,
            apiCallFn: (url, body) => apiCall(url, body, { authHeadersFn, retryOptions: { maxRetries: 0, baseDelayMs: 1 } }),
            authHeadersFn,
            reportOutcomeFn: (id, outcome, detail, result) => reportOutcome(id, outcome, detail, result, {
              completeUrlVal: `${origin}/api/v1/automation/plans/{id}/complete`,
              apiCallFn: (url, body) => apiCall(url, body, { authHeadersFn }),
            }),
            sendHeartbeatFn: () => Promise.resolve(),
            runner: new BrowserWorker({
              statePath: path.join(root, 's-poll-409.json'),
              artifactDir: pollArtifactRoot,
              onPageCreated: scenario.onPageCreated,
            }),
            eventSinkFn: async () => {},
          });
          assert.equal(scenario.getClicks(), clicksBefore, `${adapter}: HTTP 409 ELIGIBILITY_CONSTRAINT_FAILED must execute 0 clicks`);
          assert.equal(cycle409.outcome, 'RECONCILIATION_REQUIRED');
          assert.equal(completeBodies[0].outcome, 'RECONCILIATION_REQUIRED');
          assert.equal(completeBodies[0].result[0].category, 'ELIGIBILITY_CONSTRAINT_FAILED');
        } finally {
          await new Promise((resolve) => server.close(resolve));
        }
      }
    } finally {
      if (scenario.close) await scenario.close();
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
}
