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
after(async () => { if (server?.listening) await new Promise((resolve) => server.close(resolve)); });
async function startMock() {
  if (server?.listening) return mockUrl;
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    res.writeHead(200, { 'Content-Type': 'text/html' });
    if (url.pathname === '/signup') res.end('<form action="/verify" method="get"><input name="email"><input name="password"><button type="submit">Sign up</button></form>');
    else if (url.pathname === '/verify') res.end('<form action="/verified" method="get"><input name="otp"><button type="submit">Verify</button></form>');
    else if (url.pathname === '/verified') { submittedOtp = url.searchParams.get('otp'); res.end('<main>Verification successful</main>'); }
    else if (url.pathname === '/login') res.end('<form action="/dashboard" method="get"><input name="email"><input name="password"><button type="submit">Log in</button></form>');
    else if (url.pathname === '/dashboard') res.end('<main>Dashboard</main>');
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
