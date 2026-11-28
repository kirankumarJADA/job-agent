import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { BrowserWorker, ApprovalRequiredError, HardStopError } from '../src/browser_worker.js';
import { StepType } from '../src/plan.js';
import { computePlanOutcome } from '../src/outcome.js';
import { redactSecrets } from '../src/secret_redaction.js';

const { executePollCycle } = await import('../src/worker.js');

const TEST_SESSION_KEY = crypto.randomBytes(32).toString('base64');

function createTempArtifact(root, name, bytes) {
  const filePath = path.join(root, name);
  fs.writeFileSync(filePath, bytes);
  const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
  return { filePath, sha256 };
}

function buildPlan(root, targetUrl, {
  planId = 'plan-p3-1',
  applicationId = 'app-p3-1',
  employerDomain = '127.0.0.1',
  sessionId = 'sess-p3-1',
  hasSavedCredential = false,
  accountCreationAuthorized = false,
} = {}) {
  const cv = createTempArtifact(root, 'cv-p3.pdf', Buffer.from('%PDF-1.4\n%% phase3 cv\n', 'utf8'));
  return {
    planId,
    planType: 'UNIVERSAL',
    version: 1,
    correlation: {
      applicationId,
      jobId: 'job-p3-1',
      employerDomain,
      sessionId,
    },
    targetUrl,
    employerDomain,
    hasSavedCredential,
    accountCreationAuthorized,
    safetyContract: 'NO_SUBMIT',
    steps: [
      { id: 'navigate-application', type: StepType.NAVIGATE, policy: 'AUTO', params: { url: targetUrl } },
      { id: 'screenshot-landing', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} },
      {
        id: 'universal-apply',
        type: StepType.UNIVERSAL_APPLY,
        policy: 'AUTO',
        params: { expectedUrl: targetUrl, hasSavedCredential, accountCreationAuthorized, employerDomain, sessionId },
      },
    ],
    package: {
      expectedUrl: targetUrl,
      allowLocalTestHosts: true,
      employerDomain,
      sessionId,
      hasSavedCredential,
      accountCreationAuthorized,
      profileFacts: {
        fullName: 'Grace Hopper',
        firstName: 'Grace',
        lastName: 'Hopper',
        email: 'robin+grace@mail.example',
        phone: '+44 7700 900111',
      },
      confirmedAnswers: [],
      cv: {
        kind: 'cv',
        versionId: 'cv-v-p3',
        sha256: cv.sha256,
        jobId: 'job-p3-1',
        applicationId,
        path: cv.filePath,
        filename: 'cv-p3.pdf',
        contentType: 'application/pdf',
      },
      artifacts: [{ kind: 'cv', versionId: 'cv-v-p3', sha256: cv.sha256 }],
    },
  };
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

test('executePollCycle treats ApprovalRequiredError as HUMAN_REQUIRED pause without dead-lettering and preserves Universal auth flags', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-p3-poll-'));
  try {
    let seenPlan = null;
    const outcomes = [];
    const deadLetters = {
      cleared: [],
      recorded: [],
      clear(id) { this.cleared.push(id); },
      recordCrash(id, err) { this.recorded.push({ id, err }); return 1; },
      isDeadLetter() { return false; },
      getCrashCount() { return 0; },
    };

    const result = await executePollCycle({
      pollUrl: 'http://backend.invalid/api/v1/automation/plans/claim-next',
      apiCallFn: async () => ({
        id: 'plan-pause-1',
        applicationId: 'app-pause-1',
        targetUrl: 'https://careers.example.com/jobs/1',
        plan: {
          planType: 'UNIVERSAL',
          correlation: { jobId: 'job-1', applicationId: 'app-pause-1' },
          credentialSaved: false,
          accountCreationAuthorized: true,
          employerDomain: 'example.com',
          steps: [],
        },
      }),
      runner: {
        requestAbort() {},
        async execute(plan) {
          seenPlan = plan;
          throw new ApprovalRequiredError('universal-apply', 'UNIVERSAL_EMAIL_VERIFICATION_REQUIRED');
        },
      },
      reportOutcomeFn: async (planId, outcome, detail) => {
        outcomes.push({ planId, outcome, detail });
      },
      sendHeartbeatFn: async () => {},
      eventSinkFn: async () => {},
      deadLetterRegistry: deadLetters,
      statePathVal: path.join(root, 'worker-state.json'),
      artifactDirVal: root,
    });

    assert.equal(seenPlan.accountCreationAuthorized, true);
    assert.equal(seenPlan.employerDomain, 'example.com');
    assert.equal(result.status, 'HUMAN_REQUIRED');
    assert.equal(result.outcome, 'HUMAN_REQUIRED');
    assert.equal(result.reason, 'UNIVERSAL_EMAIL_VERIFICATION_REQUIRED');
    assert.equal(deadLetters.recorded.length, 0);
    assert.deepEqual(deadLetters.cleared, ['plan-pause-1']);
    assert.equal(outcomes.length, 1);
    assert.equal(outcomes[0].outcome, 'HUMAN_REQUIRED');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('Universal signup + email OTP verification + /confirm continues cleanly to form fill and never leaks secrets to disk', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-p3-signup-otp-'));
  let server;
  try {
    const state = { signedUpEmail: null, signedUpPass: null, otpSubmitted: null, cookiesVerified: false };
    server = await startHtmlServer(async (req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      if (url.pathname === '/apply') {
        const cookie = req.headers.cookie || '';
        if (cookie.includes('verified=1')) {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end(`
            <main>
              <h1>Application Form</h1>
              <form action="/submit" method="POST">
                <label>Full Name <input name="fullName" required></label>
                <label>Email <input name="email" type="email" required></label>
                <label>Resume <input type="file" name="resume" accept=".pdf"></label>
                <button type="submit">Submit Application</button>
              </form>
            </main>
          `);
          return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`
          <main>
            <h1>Create Account</h1>
            <a href="/login">Already have an account? Sign in</a>
            <form action="/signup" method="POST">
              <label>Email <input type="email" name="email" required></label>
              <label>First Name <input type="text" name="firstName"></label>
              <label>Last Name <input type="text" name="lastName"></label>
              <label>Password <input type="password" name="password" required></label>
              <label>Confirm Password <input type="password" name="confirmPassword" required></label>
              <button type="submit">Create Account</button>
            </form>
          </main>
        `);
        return;
      }
      if (url.pathname === '/signup' && req.method === 'POST') {
        const params = new URLSearchParams(req.bodyText);
        state.signedUpEmail = params.get('email');
        state.signedUpPass = params.get('password');
        assert.equal(params.get('confirmPassword'), params.get('password'));
        res.writeHead(302, { Location: '/verify-otp', 'Set-Cookie': 'sid=signup-1; Path=/' });
        res.end();
        return;
      }
      if (url.pathname === '/verify-otp' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`
          <main>
            <h1>Verify Your Email</h1>
            <p>Enter the 6-digit verification code we sent to your email address.</p>
            <form action="/verify-otp" method="POST">
              <label>Verification Code <input type="text" name="code" required></label>
              <button type="submit">Verify Email</button>
            </form>
          </main>
        `);
        return;
      }
      if (url.pathname === '/verify-otp' && req.method === 'POST') {
        const params = new URLSearchParams(req.bodyText);
        state.otpSubmitted = params.get('code');
        state.cookiesVerified = state.otpSubmitted === '654321';
        if (state.cookiesVerified) {
          res.writeHead(302, { Location: '/apply', 'Set-Cookie': 'verified=1; Path=/' });
          res.end();
        } else {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end(`<main><h1>Verify Your Email</h1><p class="error">Invalid verification code</p><form action="/verify-otp" method="POST"><input name="code"><button type="submit">Verify</button></form></main>`);
        }
        return;
      }
      res.writeHead(404);
      res.end('not found');
    });

    const events = [];
    const confirmed = [];
    const released = [];
    const statePath = path.join(root, 'worker-state.json');
    const artifactDir = root;
    const plan = buildPlan(root, `${server.origin}/apply`, {
      accountCreationAuthorized: true,
      hasSavedCredential: false,
    });

    const worker = new BrowserWorker({
      statePath,
      artifactDir,
      sessionKey: TEST_SESSION_KEY,
      eventSink: async (evt) => events.push(evt),
      identityResolver: async () => ({
        email: 'robin+vault99@mail.example',
        mailbox: 'managed',
        existing: false,
      }),
      credentialResolver: async () => ({
        password: 'Sup3r-Secret-Generated-Password!',
      }),
      verificationResolver: async () => ({
        provider: 'managed',
        otp: '654321',
        verificationId: 'ver-lease-1',
        leaseToken: 'secret-lease-token-xyz',
        leaseExpiresAt: '2099-01-01T00:00:00Z',
        receivedAt: new Date().toISOString(),
      }),
      verificationConfirmer: async (body) => {
        confirmed.push(body);
        return { status: 'CONSUMED' };
      },
      verificationReleaser: async (body) => {
        released.push(body);
        return { status: 'RELEASED' };
      },
    });

    const res = await worker.execute(plan, { approve: false, headless: true });
    const outcome = computePlanOutcome(plan, res);

    assert.equal(res.status, 'COMPLETED');
    assert.equal(outcome.outcome, 'HUMAN_REQUIRED');
    assert.equal(res.universalResult.signupSucceeded, true);
    assert.equal(res.universalResult.emailVerified, true);
    assert.equal(res.universalResult.resumeUploadState, 'ATTACHED');
    assert.equal(state.signedUpEmail, 'robin+vault99@mail.example');
    assert.equal(state.signedUpPass, 'Sup3r-Secret-Generated-Password!');
    assert.equal(state.otpSubmitted, '654321');
    assert.equal(confirmed.length, 1);
    assert.equal(confirmed[0].verificationId, 'ver-lease-1');
    assert.equal(confirmed[0].leaseToken, 'secret-lease-token-xyz');
    assert.equal(released.length, 0);

    // State file and emitted events must never expose OTP, lease token, password, or plain email when vault is enabled
    const persistedState = fs.readFileSync(statePath, 'utf8');
    assert.doesNotMatch(persistedState, /654321/);
    assert.doesNotMatch(persistedState, /secret-lease-token-xyz/);
    assert.doesNotMatch(persistedState, /Sup3r-Secret-Generated-Password!/);
    assert.doesNotMatch(persistedState, /robin\+vault99@mail\.example/);

    const serializedEvents = JSON.stringify(events);
    assert.doesNotMatch(serializedEvents, /654321/);
    assert.doesNotMatch(serializedEvents, /Sup3r-Secret-Generated-Password!/);
    assert.doesNotMatch(serializedEvents, /secret-lease-token-xyz/);
  } finally {
    await server?.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('Rejected OTP or cross-origin verification link calls /release (never /confirm), and crash resume preserves SessionVault cookies without duplicate signup', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-p3-resume-'));
  let server;
  try {
    let signupPosts = 0;
    server = await startHtmlServer(async (req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      const cookie = req.headers.cookie || '';
      if (url.pathname === '/apply') {
        if (cookie.includes('verified=1')) {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end(`<main><h1>Application Form</h1><form><label>Full Name <input name="fullName"></label><input type="file" name="cv" accept=".pdf"></form></main>`);
          return;
        }
        if (cookie.includes('signedup=1')) {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end(`<main><h1>Verify Email</h1><p>Click the confirmation link we emailed you to continue.</p></main>`);
          return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`<main><h1>Sign Up</h1><form action="/signup" method="POST"><input type="email" name="email"><input type="password" name="password"><input type="password" name="confirmPassword"><button type="submit">Register</button></form></main>`);
        return;
      }
      if (url.pathname === '/signup' && req.method === 'POST') {
        signupPosts += 1;
        res.writeHead(302, { Location: '/apply', 'Set-Cookie': 'signedup=1; Path=/' });
        res.end();
        return;
      }
      if (url.pathname === '/confirm-link') {
        res.writeHead(302, { Location: '/apply', 'Set-Cookie': 'verified=1; Path=/' });
        res.end();
        return;
      }
      res.writeHead(404);
      res.end();
    });

    const statePath = path.join(root, 'worker-state.json');
    const artifactDir = root;
    const plan = buildPlan(root, `${server.origin}/apply`, {
      accountCreationAuthorized: true,
      hasSavedCredential: false,
    });

    // Pass 1: Sign up, receive cross-origin verification link -> release lease & pause cleanly while retaining signedup=1 cookie in vault
    const confirmed1 = [];
    const released1 = [];
    const pass1 = new BrowserWorker({
      statePath,
      artifactDir,
      sessionKey: TEST_SESSION_KEY,
      identityResolver: async () => ({ email: 'robin+vault99@mail.example', mailbox: 'managed', existing: false }),
      credentialResolver: async () => ({ password: 'VaultPassword123!' }),
      verificationResolver: async () => ({
        provider: 'managed',
        link: 'https://evil-phish.example/steal?code=999999&key=abc&magic=xyz&email=u@x.com',
        verificationId: 'ver-cross-1',
        leaseToken: 'lease-tok-1',
      }),
      verificationConfirmer: async (b) => confirmed1.push(b),
      verificationReleaser: async (b) => released1.push(b),
    });

    await assert.rejects(
      () => pass1.execute(plan, { approve: false, headless: true }),
      (err) => err.name === 'ApprovalRequiredError' && err.reason === 'VERIFICATION_LINK_REJECTED'
    );
    assert.equal(signupPosts, 1);
    assert.equal(released1.length, 1);
    assert.equal(confirmed1.length, 0);

    // Pass 2: Resume with valid same-origin verification link -> restores session cookie (skips duplicate signup!), follows link, confirms lease, fills form
    const confirmed2 = [];
    const released2 = [];
    const pass2 = new BrowserWorker({
      statePath,
      artifactDir,
      sessionKey: TEST_SESSION_KEY,
      identityResolver: async () => { throw new Error('Should not re-sign-up using new identity'); },
      credentialResolver: async () => ({ password: 'VaultPassword123!' }),
      verificationResolver: async () => ({
        provider: 'managed',
        link: `${server.origin}/confirm-link?token=good`,
        verificationId: 'ver-good-2',
        leaseToken: 'lease-tok-2',
      }),
      verificationConfirmer: async (b) => confirmed2.push(b),
      verificationReleaser: async (b) => released2.push(b),
    });

    const res2 = await pass2.execute(plan, { approve: false, headless: true });
    const outcome2 = computePlanOutcome(plan, res2);
    assert.equal(res2.status, 'COMPLETED');
    assert.equal(outcome2.outcome, 'HUMAN_REQUIRED');
    assert.equal(signupPosts, 1, 'SessionVault cookies must resume straight onto email verification without duplicate signup');
    assert.equal(confirmed2.length, 1);
    assert.equal(released2.length, 0);

    // Verify redaction of sensitive URL query params (?code=, ?key=, ?magic=, ?email=)
    const redacted = redactSecrets('https://example.com/callback?code=123456&key=k1&magic=m1&email=u@test.example&ok=1');
    assert.doesNotMatch(redacted, /123456|k1|m1|u@test\.example/);
    assert.match(redacted, /ok=1/);
  } finally {
    await server?.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('Auth boundaries: unauthorized signup pauses, existing identity switches to login, and authenticator 2FA hard-stops', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-p3-guards-'));
  let server;
  try {
    server = await startHtmlServer(async (req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      if (url.pathname === '/signup-wall') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`
          <main>
            <h1>Register</h1>
            <a href="/login-wall">Sign in</a>
            <form action="/signup" method="POST">
              <input type="email" name="email">
              <input type="password" name="password">
              <input type="password" name="confirmPassword">
              <button type="submit">Create Account</button>
            </form>
          </main>
        `);
        return;
      }
      if (url.pathname === '/login-wall') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`<main><h1>Sign in</h1><form action="/login" method="POST"><input type="email" name="email"><input type="password" name="password"><button type="submit">Sign In</button></form></main>`);
        return;
      }
      if (url.pathname === '/mfa-wall') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`<main><h1>Two-Factor Authentication</h1><p>Enter the 6-digit code from your authenticator app.</p><form><input type="text" name="otp"><button type="submit">Continue</button></form></main>`);
        return;
      }
      if (url.pathname === '/login-then-create') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`
          <main>
            <h1>Sign in</h1>
            <a href="/signup-wall">Create account</a>
            <form action="/login" method="POST">
              <input type="email" name="email">
              <input type="password" name="password">
              <button type="submit">Sign In</button>
            </form>
          </main>
        `);
        return;
      }
      if (url.pathname === '/rejected-otp-page') {
        if (req.method === 'POST') {
          res.writeHead(200, { 'Content-Type': 'text/html' });
          res.end(`
            <main>
              <h1>Verify Your Email</h1>
              <p class="error">Invalid verification code. Please check your 6-digit verification code.</p>
              <form action="/rejected-otp-page" method="POST">
                <label>Verification Code <input type="text" name="code" required></label>
                <button type="submit">Verify Email</button>
              </form>
            </main>
          `);
          return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`
          <main>
            <h1>Verify Your Email</h1>
            <p>Enter the 6-digit verification code we emailed to you.</p>
            <form action="/rejected-otp-page" method="POST">
              <label>Verification Code <input type="text" name="code" required></label>
              <button type="submit">Verify Email</button>
            </form>
          </main>
        `);
        return;
      }
      res.writeHead(404);
      res.end();
    });

    // 1. Unauthorized signup pauses instead of auto-registering
    const wUnauth = new BrowserWorker({
      statePath: path.join(root, 's1.json'),
      artifactDir: path.join(root, 'a1'),
    });
    const planUnauth = buildPlan(root, `${server.origin}/signup-wall`, { accountCreationAuthorized: false });
    await assert.rejects(
      () => wUnauth.execute(planUnauth, { approve: false, headless: true }),
      (err) => err.name === 'ApprovalRequiredError' && err.reason === 'UNIVERSAL_ACCOUNT_CREATION_AUTHORIZATION_REQUIRED'
    );

    // 2. Existing identity on authorized signup switches to /login-wall and pauses for saved credential rather than duplicate signup
    const wExisting = new BrowserWorker({
      statePath: path.join(root, 's2.json'),
      artifactDir: path.join(root, 'a2'),
      identityResolver: async () => ({ email: 'robin+existing@mail.example', mailbox: 'managed', existing: true }),
    });
    const planExisting = buildPlan(root, `${server.origin}/signup-wall`, { accountCreationAuthorized: true, hasSavedCredential: false });
    await assert.rejects(
      () => wExisting.execute(planExisting, { approve: false, headless: true }),
      (err) => err.name === 'HardStopError' && err.reason === 'UNIVERSAL_LOGIN_REQUIRED'
    );

    // 3. Login wall with accountCreationAuthorized and non-existing identity switches to /signup-wall
    let resolvedInLoginSwitch = false;
    const wSwitchSignup = new BrowserWorker({
      statePath: path.join(root, 's2b.json'),
      artifactDir: path.join(root, 'a2b'),
      identityResolver: async () => {
        resolvedInLoginSwitch = true;
        return { email: 'robin+new@mail.example', mailbox: 'managed', existing: false };
      },
    });
    const planSwitch = buildPlan(root, `${server.origin}/login-then-create`, { accountCreationAuthorized: true, hasSavedCredential: false });
    await assert.rejects(
      () => wSwitchSignup.execute(planSwitch, { approve: false, headless: true }),
      (err) => err.name === 'ApprovalRequiredError' && err.reason === 'UNIVERSAL_CREDENTIAL_REQUIRED'
    );
    assert.equal(resolvedInLoginSwitch, true);

    // 4. Rejected OTP on submit releases lease (never confirms) and pauses with UNIVERSAL_EMAIL_VERIFICATION_FAILED
    const otpConfirmed = [];
    const otpReleased = [];
    const wRejectedOtp = new BrowserWorker({
      statePath: path.join(root, 's2c.json'),
      artifactDir: path.join(root, 'a2c'),
      verificationResolver: async () => ({
        provider: 'managed',
        otp: '123456',
        verificationId: 'ver-rej-1',
        leaseToken: 'tok-rej-1',
      }),
      verificationConfirmer: async (b) => otpConfirmed.push(b),
      verificationReleaser: async (b) => otpReleased.push(b),
    });
    const planRejOtp = buildPlan(root, `${server.origin}/rejected-otp-page`, { accountCreationAuthorized: true });
    await assert.rejects(
      () => wRejectedOtp.execute(planRejOtp, { approve: false, headless: true }),
      (err) => err.name === 'ApprovalRequiredError' && err.reason === 'UNIVERSAL_EMAIL_VERIFICATION_FAILED'
    );
    assert.equal(otpReleased.length, 1);
    assert.equal(otpReleased[0].reason, 'OTP_REJECTED');
    assert.equal(otpConfirmed.length, 0);

    // 5. Authenticator app 2FA still hard-stops as UNIVERSAL_MFA_REQUIRED
    const wMfa = new BrowserWorker({
      statePath: path.join(root, 's3.json'),
      artifactDir: path.join(root, 'a3'),
    });
    const planMfa = buildPlan(root, `${server.origin}/mfa-wall`, { accountCreationAuthorized: true });
    await assert.rejects(
      () => wMfa.execute(planMfa, { approve: false, headless: true }),
      (err) => err.name === 'HardStopError' && err.reason === 'UNIVERSAL_MFA_REQUIRED'
    );
  } finally {
    await server?.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});
