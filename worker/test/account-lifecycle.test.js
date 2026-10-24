import { after, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { buildAccountLifecyclePlan } from '../src/plan.js';
import { BrowserWorker } from '../src/browser_worker.js';

let server;
let mockUrl;
let submittedOtp;
// What the mock employer received: the sign-up and login addresses, and every page requested.
const employerSaw = { signupEmail: null, loginEmail: null, signupPassword: null, loginPassword: null, paths: [] };
function resetEmployer() {
  submittedOtp = null;
  employerSaw.signupEmail = null;
  employerSaw.loginEmail = null;
  employerSaw.signupPassword = null;
  employerSaw.loginPassword = null;
  employerSaw.paths = [];
}
after(async () => { if (server?.listening) await new Promise((resolve) => server.close(resolve)); });
async function startMock() {
  if (server?.listening) return mockUrl;
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    employerSaw.paths.push(url.pathname);
    res.writeHead(200, { 'Content-Type': 'text/html' });
    if (url.pathname === '/signup') res.end('<form action="/verify" method="get"><input name="email"><input name="password"><button type="submit">Sign up</button></form>');
    else if (url.pathname === '/verify') { employerSaw.signupEmail = url.searchParams.get('email'); employerSaw.signupPassword = url.searchParams.get('password'); res.end('<form action="/verified" method="get"><input name="otp"><button type="submit">Verify</button></form>'); }
    else if (url.pathname === '/verified') { submittedOtp = url.searchParams.get('otp'); res.end('<main>Verification successful</main>'); }
    else if (url.pathname === '/login') res.end('<form action="/dashboard" method="get"><input name="email"><input name="password"><button type="submit">Log in</button></form>');
    else if (url.pathname === '/dashboard') { employerSaw.loginEmail = url.searchParams.get('email'); employerSaw.loginPassword = url.searchParams.get('password'); res.end('<main>Dashboard</main>'); }
    else { res.writeHead(404); res.end('Not found'); }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  mockUrl = 'http://127.0.0.1:' + server.address().port;
  return mockUrl;
}
const plan = buildAccountLifecyclePlan({baseUrl:'https://mockemployer.test',employerDomain:'mockemployer.test',
  applicationId:'app-1',accountEmail:'candidate@example.com',accountPassword:'local-only-password',verifyOtp:true});

describe('account lifecycle plan (Phase 8.5)', () => {
  it('contains signup, OTP, form entry, verify and login in order without application submission', () => {
    assert.deepEqual(plan.steps.map(s=>s.id),[
      'signup-navigate','signup-email','signup-password','signup-submit',
      'account-verify-otp','account-verify-otp-fill','account-verify-otp-submit',
      'login-navigate','login-email','login-password','login-submit',
    ]);
    assert.equal(plan.safetyContract,'NO_SUBMIT');
    assert.ok(!plan.steps.some(s=>s.type==='MOCK_SUBMIT'||s.params?.action==='REAL_SUBMIT'));
  });
  it('resolves and enters OTP, submits verification, then continues to login', async () => {
    const url=await startMock(), root=fs.mkdtempSync(path.join(os.tmpdir(),'lifecycle-otp-'));
    submittedOtp=null; const resolutions=[]; const events=[];
    const worker=new BrowserWorker({statePath:path.join(root,'state.json'),artifactDir:root,eventSink:e=>events.push(e),
      verificationResolver:async b=>{resolutions.push(b);return {otp:'123456',receivedAt:new Date().toISOString()};}});
    const p=buildAccountLifecyclePlan({baseUrl:url,employerDomain:new URL(url).hostname,applicationId:'app-x',
      accountEmail:'c@x.test',accountPassword:'pw',verifyOtp:true});
    const result=await worker.execute(p);
    assert.equal(result.status,'COMPLETED'); assert.equal(resolutions.length,1); assert.equal(submittedOtp,'123456');
    assert.equal(resolutions[0].applicationId,'app-x');
    assert.ok(events.some(e=>e.type==='STEP_COMPLETED'&&e.stepId==='account-verify-otp-fill'));
    const state=JSON.parse(fs.readFileSync(path.join(root,'state.json'),'utf8'));
    assert.equal(state.plans[p.planId].verification,null);
    fs.rmSync(root,{recursive:true,force:true});
  });
  it('follows same-origin loopback verification link and skips OTP submission', async () => {
    const url=await startMock(), root=fs.mkdtempSync(path.join(os.tmpdir(),'lifecycle-link-')); submittedOtp=null;
    const worker=new BrowserWorker({statePath:path.join(root,'state.json'),artifactDir:root,
      verificationResolver:async()=>({link:url+'/verified?token=local-test'})});
    const p=buildAccountLifecyclePlan({baseUrl:url,employerDomain:new URL(url).hostname,applicationId:'app-link',
      accountEmail:'c@x.test',accountPassword:'pw',verifyOtp:true});
    assert.equal((await worker.execute(p)).status,'COMPLETED'); assert.equal(submittedOtp,null);
    fs.rmSync(root,{recursive:true,force:true});
  });
  it('pauses when provider resolution fails', async () => {
    const url=await startMock(), root=fs.mkdtempSync(path.join(os.tmpdir(),'lifecycle-pause-'));
    const p=buildAccountLifecyclePlan({baseUrl:url,employerDomain:new URL(url).hostname,applicationId:'app-y',
      accountEmail:'c@x.test',accountPassword:'pw',verifyOtp:true});
    const w=new BrowserWorker({statePath:path.join(root,'state.json'),artifactDir:root,
      verificationResolver:async()=>{throw Error('VERIFICATION_RESOLVE_FAILED:404');}});
    await assert.rejects(()=>w.execute(p),e=>e.name==='ApprovalRequiredError');
    const state=JSON.parse(fs.readFileSync(path.join(root,'state.json'),'utf8')).plans[p.planId];
    assert.equal(state.status,'PAUSED'); assert.equal(state.pauseReason,'VERIFICATION_PROVIDER_UNAVAILABLE');
    fs.rmSync(root,{recursive:true,force:true});
  });
  it('pauses when no provider is configured', async () => {
    const url=await startMock(), root=fs.mkdtempSync(path.join(os.tmpdir(),'lifecycle-none-'));
    const p=buildAccountLifecyclePlan({baseUrl:url,employerDomain:new URL(url).hostname,applicationId:'app-z',
      accountEmail:'c@x.test',accountPassword:'pw',verifyOtp:true});
    const w=new BrowserWorker({statePath:path.join(root,'state.json'),artifactDir:root});
    await assert.rejects(()=>w.execute(p),e=>e.name==='ApprovalRequiredError');
    const state=JSON.parse(fs.readFileSync(path.join(root,'state.json'),'utf8')).plans[p.planId];
    assert.equal(state.status,'PAUSED'); assert.equal(state.pauseReason,'VERIFICATION_PROVIDER_UNAVAILABLE');
    fs.rmSync(root,{recursive:true,force:true});
  });
  it('pauses instead of following link to another origin', async () => {
    const url=await startMock(), root=fs.mkdtempSync(path.join(os.tmpdir(),'lifecycle-untrusted-'));
    const p=buildAccountLifecyclePlan({baseUrl:url,employerDomain:new URL(url).hostname,applicationId:'app-link-bad',
      accountEmail:'c@x.test',accountPassword:'pw',verifyOtp:true});
    const w=new BrowserWorker({statePath:path.join(root,'state.json'),artifactDir:root,
      verificationResolver:async()=>({link:'https://example.invalid/verify?token=secret'})});
    await assert.rejects(()=>w.execute(p),e=>e.name==='ApprovalRequiredError');
    const state=JSON.parse(fs.readFileSync(path.join(root,'state.json'),'utf8')).plans[p.planId];
    assert.equal(state.status,'PAUSED'); assert.equal(state.pauseReason,'VERIFICATION_LINK_REJECTED');
    fs.rmSync(root,{recursive:true,force:true});
  });
});

// ── Phase 8.5 managed mail: the Robin-managed address is the default registration identity ──
const MANAGED = 'u7kq2m9x4vb3tn5p@mail.robin.example';

describe('account lifecycle with the Robin-managed address (Phase 8.5)', () => {
  it('asks the backend for the address instead of baking one into the plan', () => {
    const p = buildAccountLifecyclePlan({ baseUrl: 'https://mockemployer.test', employerDomain: 'mockemployer.test',
      applicationId: 'app-m', sessionId: 'sess-m', accountPassword: 'local-only-password' });
    assert.equal(p.steps[0].id, 'account-resolve-identity');
    assert.equal(p.steps[0].type, 'ACCOUNT_RESOLVE_IDENTITY');
    for (const id of ['signup-email', 'login-email']) {
      const field = p.steps.find((s) => s.id === id);
      assert.equal(field.params.valueFrom, 'identity.email');
      assert.equal(field.params.value, undefined);
    }
    assert.deepEqual(p.correlation, { applicationId: 'app-m', employerDomain: 'mockemployer.test', sessionId: 'sess-m' });
    assert.ok(!JSON.stringify(p).includes('@'), 'no e-mail address is written into the plan');
    assert.equal(p.safetyContract, 'NO_SUBMIT');
  });

  it('signs up, verifies and logs in with the managed address, reading the code from that mailbox', async () => {
    const url = await startMock(); resetEmployer();
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-managed-'));
    const identityCalls = []; const verificationCalls = []; const events = [];
    const worker = new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root, eventSink: (e) => events.push(e),
      identityResolver: async (b) => { identityCalls.push(b); return { email: MANAGED.toUpperCase(), mailbox: 'managed' }; },
      verificationResolver: async (b) => { verificationCalls.push(b); return { otp: '482913', provider: 'managed' }; } });
    const p = buildAccountLifecyclePlan({ baseUrl: url, employerDomain: new URL(url).hostname, applicationId: 'app-managed',
      sessionId: 'sess-managed', accountPassword: 'pw' });

    assert.equal((await worker.execute(p)).status, 'COMPLETED');
    assert.equal(employerSaw.signupEmail, MANAGED);
    assert.equal(employerSaw.loginEmail, MANAGED);
    assert.equal(submittedOtp, '482913');
    assert.deepEqual(identityCalls, [{ applicationId: 'app-managed', employerDomain: new URL(url).hostname, sessionId: 'sess-managed' }]);
    assert.equal(verificationCalls.length, 1);
    assert.equal(verificationCalls[0].mailboxAddress, MANAGED);
    assert.equal(verificationCalls[0].sessionId, 'sess-managed');
    assert.ok(events.some((e) => e.type === 'REGISTRATION_IDENTITY_RESOLVED' && e.mailbox === 'managed'));
    assert.ok(!JSON.stringify(events).includes(MANAGED), 'the address never appears in an event');
    assert.ok(!JSON.stringify(events).includes('482913'), 'the code never appears in an event');
    const state = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[p.planId];
    assert.equal(state.verification, null);
    fs.rmSync(root, { recursive: true, force: true });
  });

  it('pauses before touching the employer when the managed mailbox is not ready', async () => {
    const url = await startMock(); resetEmployer();
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-notready-'));
    const worker = new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root,
      identityResolver: async () => { throw new Error('REGISTRATION_IDENTITY_FAILED:409'); },
      verificationResolver: async () => { throw new Error('must not be asked'); } });
    const p = buildAccountLifecyclePlan({ baseUrl: url, employerDomain: new URL(url).hostname, applicationId: 'app-notready', accountPassword: 'pw' });
    await assert.rejects(() => worker.execute(p), (e) => e.name === 'ApprovalRequiredError');
    const state = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[p.planId];
    assert.equal(state.status, 'PAUSED');
    assert.equal(state.pauseReason, 'MANAGED_MAILBOX_NOT_READY');
    assert.deepEqual(employerSaw.paths, [], 'no employer page was opened');
    fs.rmSync(root, { recursive: true, force: true });
  });

  it('pauses when no identity resolver is configured or it returns no usable address', async () => {
    const url = await startMock();
    for (const [identityResolver, reason] of [
      [null, 'REGISTRATION_IDENTITY_UNAVAILABLE'],
      [async () => { throw new Error('REGISTRATION_IDENTITY_FAILED:503'); }, 'REGISTRATION_IDENTITY_UNAVAILABLE'],
      [async () => ({ email: 'not-an-address', mailbox: 'managed' }), 'REGISTRATION_IDENTITY_INVALID'],
      [async () => ({ email: 'x@evil.example" onerror="1', mailbox: 'managed' }), 'REGISTRATION_IDENTITY_INVALID'],
    ]) {
      resetEmployer();
      const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-identity-'));
      const worker = new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root, identityResolver });
      const p = buildAccountLifecyclePlan({ baseUrl: url, employerDomain: new URL(url).hostname, applicationId: 'app-identity', accountPassword: 'pw' });
      await assert.rejects(() => worker.execute(p), (e) => e.name === 'ApprovalRequiredError');
      const state = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[p.planId];
      assert.equal(state.pauseReason, reason);
      assert.deepEqual(employerSaw.paths, []);
      fs.rmSync(root, { recursive: true, force: true });
    }
  });

  it('a restarted run logs in with the address it signed up with and never asks for a new one', async () => {
    const url = await startMock(); resetEmployer();
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-restart-'));
    let identityCalls = 0;
    const options = () => ({ statePath: path.join(root, 'state.json'), artifactDir: root,
      identityResolver: async () => { identityCalls += 1; return { email: identityCalls === 1 ? MANAGED : 'different@mail.robin.example', mailbox: 'managed' }; },
      verificationResolver: async () => ({ otp: '482913' }) });
    const p = buildAccountLifecyclePlan({ baseUrl: url, employerDomain: new URL(url).hostname, applicationId: 'app-restart', accountPassword: 'pw' });
    await assert.rejects(() => new BrowserWorker(options()).execute(p, { failAfterStep: 'signup-submit' }),
      (e) => String(e.message).startsWith('SIMULATED_WORKER_CRASH'));
    assert.equal(employerSaw.signupEmail, MANAGED);
    assert.equal((await new BrowserWorker(options()).execute(p)).status, 'COMPLETED');
    assert.equal(identityCalls, 1);
    assert.equal(employerSaw.loginEmail, MANAGED);
    assert.equal(submittedOtp, '482913');
    fs.rmSync(root, { recursive: true, force: true });
  });
});

// ── Phase 8.5 continuation: EmployerCredentialService wired as the password source ──
describe('account lifecycle with a backend-resolved portal password (Phase 8.5 continuation)', () => {
  it('asks the backend for the password instead of baking one into the plan', () => {
    const p = buildAccountLifecyclePlan({ baseUrl: 'https://mockemployer.test', employerDomain: 'mockemployer.test',
      applicationId: 'app-cred', accountEmail: 'candidate@example.com' });
    assert.ok(p.steps.some((s) => s.id === 'account-resolve-credential' && s.type === 'ACCOUNT_RESOLVE_CREDENTIAL'));
    for (const id of ['signup-password', 'login-password']) {
      const field = p.steps.find((s) => s.id === id);
      assert.equal(field.params.valueFrom, 'credential.password');
      assert.ok(!('value' in field.params), 'no literal password value is written into the plan');
    }
  });

  it('still bakes in a literal password when one is explicitly given (backward compatible)', () => {
    const p = buildAccountLifecyclePlan({ baseUrl: 'https://mockemployer.test', employerDomain: 'mockemployer.test',
      applicationId: 'app-cred-literal', accountEmail: 'candidate@example.com', accountPassword: 'literal-pw' });
    assert.ok(!p.steps.some((s) => s.type === 'ACCOUNT_RESOLVE_CREDENTIAL'));
    assert.equal(p.steps.find((s) => s.id === 'signup-password').params.value, 'literal-pw');
  });

  it('signs up and logs in using the resolved credential, and never records it via a dedicated store field or an event', async () => {
    const url = await startMock(); resetEmployer();
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-credential-'));
    const credentialCalls = []; const events = [];
    const worker = new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root, eventSink: (e) => events.push(e),
      credentialResolver: async (b) => { credentialCalls.push(b); return { username: 'candidate@example.com', password: 'resolved-secret-pw' }; } });
    const p = buildAccountLifecyclePlan({ baseUrl: url, employerDomain: new URL(url).hostname, applicationId: 'app-cred-run',
      accountEmail: 'candidate@example.com', verifyOtp: false });
    assert.equal((await worker.execute(p)).status, 'COMPLETED');
    assert.equal(employerSaw.signupPassword, 'resolved-secret-pw');
    assert.equal(employerSaw.loginPassword, 'resolved-secret-pw');
    assert.deepEqual(credentialCalls, [{ applicationId: 'app-cred-run', employerDomain: new URL(url).hostname }]);
    assert.ok(events.some((e) => e.type === 'EMPLOYER_CREDENTIAL_RESOLVED'));
    assert.ok(!JSON.stringify(events).includes('resolved-secret-pw'), 'the password never appears in an event');
    // Unlike identity/verification, there is no dedicated this.store.credential()
    // persistence method at all — the resolved value lives only in this run's
    // in-memory `values`. The mock employer's forms submit over GET for test
    // simplicity, so page.url() immediately after the signup/login submit
    // would otherwise carry the password in its query string — this is the
    // exact GET-form leak the state-persistence layer (store.js's markStep,
    // via secret_redaction.js's sanitizeUrl) now closes at the source, so the
    // raw password must not appear ANYWHERE in the on-disk state file, not
    // merely absent from a dedicated field.
    const stateRaw = fs.readFileSync(path.join(root, 'state.json'), 'utf8');
    assert.ok(!stateRaw.includes('resolved-secret-pw'),
      'the password must never appear anywhere in the on-disk state file, including inside a recorded page URL');
    const state = JSON.parse(stateRaw).plans[p.planId];
    assert.equal(state.credential, undefined, 'no dedicated credential field is ever written to the state file');
    // Prove the redaction actually fired (rather than the password simply
    // never having been in a URL to begin with): the GET-submitted signup
    // click navigates to /verify?email=...&password=..., and that is the
    // step whose recorded URL must carry [REDACTED] in place of the password.
    const signupSubmitStep = state.steps['signup-submit'];
    assert.ok(signupSubmitStep?.currentUrl, 'the signup-submit step should have recorded a current URL');
    assert.match(signupSubmitStep.currentUrl, /password=.*REDACTED/i);
    fs.rmSync(root, { recursive: true, force: true });
  });

  it('pauses when no credential resolver is configured, without touching the employer', async () => {
    const url = await startMock(); resetEmployer();
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-cred-none-'));
    const worker = new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root });
    const p = buildAccountLifecyclePlan({ baseUrl: url, employerDomain: new URL(url).hostname, applicationId: 'app-cred-none',
      accountEmail: 'c@x.test', verifyOtp: false });
    await assert.rejects(() => worker.execute(p), (e) => e.name === 'ApprovalRequiredError');
    const state = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[p.planId];
    assert.equal(state.status, 'PAUSED');
    assert.equal(state.pauseReason, 'EMPLOYER_CREDENTIAL_UNAVAILABLE');
    assert.deepEqual(employerSaw.paths, [], 'no employer page was opened');
    fs.rmSync(root, { recursive: true, force: true });
  });

  it('pauses with the specific reason for a missing credential, a provider failure, or an empty password', async () => {
    const url = await startMock();
    for (const [credentialResolver, reason] of [
      [async () => { throw new Error('EMPLOYER_CREDENTIAL_FAILED:404'); }, 'EMPLOYER_CREDENTIAL_NOT_FOUND'],
      [async () => { throw new Error('EMPLOYER_CREDENTIAL_FAILED:503'); }, 'EMPLOYER_CREDENTIAL_UNAVAILABLE'],
      [async () => ({ username: 'c@x.test', password: '' }), 'EMPLOYER_CREDENTIAL_INVALID'],
    ]) {
      resetEmployer();
      const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lifecycle-cred-fail-'));
      const worker = new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root, credentialResolver });
      const p = buildAccountLifecyclePlan({ baseUrl: url, employerDomain: new URL(url).hostname, applicationId: 'app-cred-fail',
        accountEmail: 'c@x.test', verifyOtp: false });
      await assert.rejects(() => worker.execute(p), (e) => e.name === 'ApprovalRequiredError');
      const state = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[p.planId];
      assert.equal(state.pauseReason, reason);
      assert.deepEqual(employerSaw.paths, []);
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
});
