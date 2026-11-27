import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { BrowserWorker, ApprovalRequiredError } from '../src/browser_worker.js';
import { StepType, buildAccountLifecyclePlan } from '../src/plan.js';
import { StateStore } from '../src/store.js';
import { classifyUniversalSubmitOutcome } from '../src/universal_confirmation.js';
import { assertWorkerSessionKeyConfigured } from '../src/worker.js';

const TEST_SESSION_KEY = crypto.randomBytes(32).toString('base64');

function createTempArtifact(root, name, bytes) {
  const filePath = path.join(root, name);
  fs.writeFileSync(filePath, bytes);
  const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
  return { filePath, sha256 };
}

async function startHtmlServer(handler) {
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    req.bodyText = Buffer.concat(chunks).toString('utf8');
    await handler(req, res);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    port,
    origin: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

test('assertWorkerSessionKeyConfigured fails closed without valid 32-byte key unless explicitly overridden', () => {
  assert.throws(() => assertWorkerSessionKeyConfigured({ env: {}, sessionKeyValue: null }),
    /ROBIN_WORKER_SESSION_KEY/);
  assert.throws(() => assertWorkerSessionKeyConfigured({ env: {}, sessionKeyValue: 'short' }),
    /ROBIN_WORKER_SESSION_KEY/);
  assert.equal(
    assertWorkerSessionKeyConfigured({ env: { ROBIN_ALLOW_EPHEMERAL_WORKER_SESSION: '1' }, sessionKeyValue: null }),
    false
  );
  assert.equal(assertWorkerSessionKeyConfigured({ env: {}, sessionKeyValue: TEST_SESSION_KEY }), true);
});

test('StateStore never persists plaintext identity email by default and retains universalSignupSucceeded across pause', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-p5-store-'));
  try {
    const file = path.join(root, 'worker-state.json');
    const store = new StateStore(file);
    store.begin({ planId: 'plan-s1', planFingerprint: 'fp', correlation: { applicationId: 'app-s1' } });
    store.identity('plan-s1', {
      email: 'robin+secret123@mail.example',
      mailbox: 'managed',
      existing: false,
      signupSucceeded: true,
    });
    store.pause('plan-s1', 'UNIVERSAL_EMAIL_VERIFICATION_REQUIRED', {
      currentUrl: 'https://employer.example/verify?token=top-secret-tok&otp=112233',
      signupSucceeded: true,
    });

    const raw = fs.readFileSync(file, 'utf8');
    assert.ok(!raw.includes('robin+secret123@mail.example'), 'plaintext email must not be written to worker-state.json');
    assert.ok(!raw.includes('top-secret-tok'));
    assert.ok(!raw.includes('112233'));

    const parsed = JSON.parse(raw).plans['plan-s1'];
    assert.equal(parsed.identity.mailbox, 'managed');
    assert.equal(parsed.identity.email, undefined);
    assert.equal(parsed.universalSignupSucceeded, true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('classifyUniversalSubmitOutcome redacts secrets in serverRejected errorMessage', () => {
  const outcome = classifyUniversalSubmitOutcome({
    expectedUrl: 'https://employer.example/apply',
    finalUrl: 'https://employer.example/apply',
    networkResponses: [{
      method: 'POST',
      status: 422,
      errorMessage: 'Callback failed https://employer.example/verify?activation_code=act-secret-9&otp=482913',
    }],
    domState: {},
  });
  assert.equal(outcome.confirmed, false);
  assert.ok(!outcome.evidence.includes('act-secret-9'));
  assert.ok(!outcome.evidence.includes('482913'));
  assert.match(outcome.evidence, /\[REDACTED\]/);
});

test('ACCOUNT_VERIFY_OTP rejects cross-origin HTTP redirect on link navigation and releases lease without confirming', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-p5-redirect-'));
  let evilServer;
  let mainServer;
  try {
    evilServer = await startHtmlServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end('<main>Off-origin page</main>');
    });
    mainServer = await startHtmlServer((req, res) => {
      const u = new URL(req.url, 'http://127.0.0.1');
      if (u.pathname === '/bounce-verify') {
        res.writeHead(302, { Location: `${evilServer.origin}/phish` });
        res.end();
        return;
      }
      res.writeHead(200, { 'Content-Type': 'text/html' });
      if (u.pathname === '/signup') {
        res.end('<form action="/verify" method="get"><input name="email"><input name="password"><button type="submit">Sign up</button></form>');
      } else if (u.pathname === '/verify') {
        res.end('<form action="/verified" method="get"><input name="otp"><button type="submit">Verify</button></form>');
      } else {
        res.end('<main>Done</main>');
      }
    });

    const released = [];
    const confirmed = [];
    const worker = new BrowserWorker({
      localTestNetwork: true,
      statePath: path.join(root, 'state.json'),
      artifactDir: path.join(root, 'artifacts'),
      sessionKey: TEST_SESSION_KEY,
      verificationResolver: async () => ({
        link: `${mainServer.origin}/bounce-verify`,
        verificationId: 'ver-redir-1',
        leaseToken: 'tok-redir-1',
      }),
      verificationConfirmer: async (b) => { confirmed.push(b); return { status: 'CONSUMED' }; },
      verificationReleaser: async (b) => { released.push(b); return { status: 'RELEASED' }; },
    });

    const plan = buildAccountLifecyclePlan({
      baseUrl: mainServer.origin,
      employerDomain: '127.0.0.1',
      applicationId: 'app-redir-1',
      accountEmail: 'candidate@example.com',
      accountPassword: 'pw',
      verifyOtp: true,
    });

    await assert.rejects(
      () => worker.execute(plan, { approve: false, headless: true }),
      (err) => err.name === 'ApprovalRequiredError' && err.reason === 'VERIFICATION_LINK_REJECTED'
    );
    assert.equal(confirmed.length, 0, 'cross-origin redirect must never confirm verification lease');
    assert.ok(released.length >= 1, 'cross-origin redirect must release verification lease');
  } finally {
    await mainServer?.close();
    await evilServer?.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('Universal signup calls identityConfirmer, persists signupSucceeded + vault before pause, and tolerates 409 on OTP confirm resume', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-p5-univ-recover-'));
  let server;
  try {
    let signupSubmissions = 0;
    server = await startHtmlServer(async (req, res) => {
      const u = new URL(req.url, 'http://127.0.0.1');
      if (u.pathname === '/apply') {
        const cookie = req.headers.cookie || '';
        if (cookie.includes('verified=1')) {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end(`
            <main>
              <h1>Application Form</h1>
              <form action="/done" method="post">
                <label>Full Name <input name="fullName" required></label>
                <label>Email <input type="email" name="email" required></label>
                <button type="submit">Submit Application</button>
              </form>
            </main>
          `);
          return;
        }
        if (cookie.includes('signedup=1')) {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end(`
            <main>
              <h1>Verify Your Email</h1>
              <p>Enter the 6-digit verification code sent to your email address.</p>
              <form action="/verify-otp" method="post">
                <label>Verification code <input type="text" name="otp" required></label>
                <button type="submit">Verify Code</button>
              </form>
            </main>
          `);
          return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`
          <main>
            <h1>Create Account</h1>
            <form action="/signup" method="post">
              <label>Email <input type="email" name="email" required></label>
              <label>Password <input type="password" name="password" required></label>
              <label>Confirm Password <input type="password" name="confirmPassword" required></label>
              <button type="submit">Create Account</button>
            </form>
            <a href="/login">Already have an account? Sign in</a>
          </main>
        `);
        return;
      }
      if (u.pathname === '/signup' && req.method === 'POST') {
        signupSubmissions += 1;
        res.writeHead(302, {
          Location: '/apply',
          'Set-Cookie': 'signedup=1; Path=/; HttpOnly',
        });
        res.end();
        return;
      }
      if (u.pathname === '/verify-otp' && req.method === 'POST') {
        res.writeHead(302, {
          Location: '/apply',
          'Set-Cookie': ['signedup=1; Path=/; HttpOnly', 'verified=1; Path=/; HttpOnly'],
        });
        res.end();
        return;
      }
      res.writeHead(404);
      res.end('Not found');
    });

    const cv = createTempArtifact(root, 'cv.pdf', Buffer.from('%PDF-1.4\n%% phase5\n', 'utf8'));
    const targetUrl = `${server.origin}/apply`;
    const plan = {
      planId: 'plan-p5-univ-1',
      planType: 'UNIVERSAL',
      version: 1,
      correlation: {
        applicationId: 'app-p5-univ-1',
        jobId: 'job-p5-univ-1',
        employerDomain: '127.0.0.1',
        sessionId: 'sess-p5-univ-1',
      },
      targetUrl,
      employerDomain: '127.0.0.1',
      hasSavedCredential: false,
      accountCreationAuthorized: true,
      safetyContract: 'NO_SUBMIT',
      steps: [
        { id: 'navigate-application', type: StepType.NAVIGATE, policy: 'AUTO', params: { url: targetUrl } },
        {
          id: 'universal-apply',
          type: StepType.UNIVERSAL_APPLY,
          policy: 'AUTO',
          params: {
            expectedUrl: targetUrl,
            hasSavedCredential: false,
            accountCreationAuthorized: true,
            employerDomain: '127.0.0.1',
            sessionId: 'sess-p5-univ-1',
          },
        },
      ],
      package: {
        expectedUrl: targetUrl,
        allowLocalTestHosts: true,
        employerDomain: '127.0.0.1',
        sessionId: 'sess-p5-univ-1',
        hasSavedCredential: false,
        accountCreationAuthorized: true,
        profileFacts: {
          fullName: 'Ada Lovelace',
          email: 'robin+ada@mail.example',
        },
        confirmedAnswers: [],
        cv: {
          kind: 'cv',
          versionId: 'cv-1',
          sha256: cv.sha256,
          jobId: 'job-p5-univ-1',
          applicationId: 'app-p5-univ-1',
          path: cv.filePath,
          filename: 'cv.pdf',
          contentType: 'application/pdf',
        },
        artifacts: [{ kind: 'cv', versionId: 'cv-1', sha256: cv.sha256 }],
      },
    };

    const statePath = path.join(root, 'worker-state.json');
    const artifactDir = path.join(root, 'artifacts');
    const identityConfirms = [];

    // Pass 1: signs up, calls identityConfirmer, saves cookies + signupSucceeded, then pauses waiting for OTP
    const w1 = new BrowserWorker({
      statePath,
      artifactDir,
      sessionKey: TEST_SESSION_KEY,
      identityResolver: async () => ({ email: 'robin+ada@mail.example', mailbox: 'managed', existing: false }),
      identityConfirmer: async (b) => { identityConfirms.push(b); return { confirmed: true }; },
      credentialResolver: async () => ({ username: 'robin+ada@mail.example', password: 'StrongPassword123!' }),
      verificationResolver: async () => { throw new Error('VERIFICATION_RESOLVE_FAILED:404'); },
    });

    await assert.rejects(
      () => w1.execute(plan, { approve: false, headless: true }),
      (err) => err.name === 'ApprovalRequiredError' && err.reason === 'UNIVERSAL_EMAIL_VERIFICATION_REQUIRED'
    );
    assert.equal(signupSubmissions, 1);
    assert.equal(identityConfirms.length, 1);
    assert.equal(identityConfirms[0].applicationId, 'app-p5-univ-1');

    const savedState = JSON.parse(fs.readFileSync(statePath, 'utf8')).plans['plan-p5-univ-1'];
    assert.equal(savedState.universalSignupSucceeded, true);
    assert.ok(!fs.readFileSync(statePath, 'utf8').includes('robin+ada@mail.example'));

    // Pass 2: resume from vault + OTP arrives; verificationConfirmer returns 409 conflict non-fatally
    const events = [];
    const w2 = new BrowserWorker({
      statePath,
      artifactDir,
      sessionKey: TEST_SESSION_KEY,
      eventSink: (e) => events.push(e),
      identityResolver: async () => ({ email: 'robin+ada@mail.example', mailbox: 'managed', existing: true }),
      identityConfirmer: async (b) => { identityConfirms.push(b); return { confirmed: true }; },
      verificationResolver: async () => ({
        provider: 'managed',
        otp: '482913',
        verificationId: 'ver-p5-1',
        leaseToken: 'lt-p5-1',
      }),
      verificationConfirmer: async () => ({ conflict: true, status: 409 }),
    });

    const resumed = await w2.execute(plan, { approve: false, headless: true });
    assert.equal(resumed.status, 'COMPLETED');
    assert.equal(signupSubmissions, 1, 'resumed worker must never repeat signup');
    assert.equal(resumed.universalResult.emailVerified, true);
    assert.ok(events.some((e) => e.type === 'MAILBOX_VERIFICATION_CONFIRM_CONFLICT'));
  } finally {
    await server?.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});
