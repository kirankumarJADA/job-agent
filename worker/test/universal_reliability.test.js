import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { chromium } from '@playwright/test';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import {
  evaluateUniversalResourceRequest,
  installUniversalNavigationGuard,
  inspectLiveFormDom,
  planUniversalControls,
} from '../src/universal_form.js';
import { StepType } from '../src/plan.js';
import { computePlanOutcome } from '../src/outcome.js';
import { isHandoffEligibleError, normalizeHandoffReason } from '../src/handoff.js';

function createTempArtifact(root, name, bytes) {
  const filePath = path.join(root, name);
  fs.writeFileSync(filePath, bytes);
  const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
  return { filePath, sha256 };
}

function buildUniversalPlan(root, targetUrl, {
  planId = 'plan-rel-1',
  confirmedAnswers = [],
  includeCoverLetter = false,
  hasSavedCredential = false,
  allowLocalTestHosts = true,
} = {}) {
  const cv = createTempArtifact(root, 'cv-ver-1.pdf', Buffer.from('%PDF-1.4\n%% stage3d test cv\n', 'utf8'));
  const cl = includeCoverLetter
    ? createTempArtifact(root, 'cl-ver-1.pdf', Buffer.from('%PDF-1.4\n%% stage3d cover letter\n', 'utf8'))
    : null;
  const artifacts = [
    { kind: 'cv', versionId: 'cv-v-1', sha256: cv.sha256 },
    ...(cl ? [{ kind: 'coverLetter', versionId: 'cl-v-1', sha256: cl.sha256 }] : []),
  ];
  return {
    planId,
    planType: 'UNIVERSAL',
    version: 1,
    correlation: { applicationId: 'app-rel-1', jobId: 'job-rel-1' },
    targetUrl,
    safetyContract: 'NO_SUBMIT',
    steps: [
      { id: 'navigate-application', type: StepType.NAVIGATE, policy: 'AUTO', params: { url: targetUrl } },
      { id: 'screenshot-landing', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} },
      { id: 'universal-apply', type: StepType.UNIVERSAL_APPLY, policy: 'AUTO', params: { expectedUrl: targetUrl, hasSavedCredential } },
    ],
    package: {
      expectedUrl: targetUrl,
      allowLocalTestHosts,
      profileFacts: {
        fullName: 'Ada Lovelace',
        firstName: 'Ada',
        lastName: 'Lovelace',
        email: 'ada@inbox.example',
        phone: '+44 7700 900077',
        linkedin: 'https://linkedin.com/in/adalovelace',
        location: 'London, UK',
      },
      confirmedAnswers,
      cv: {
        kind: 'cv',
        versionId: 'cv-v-1',
        sha256: cv.sha256,
        jobId: 'job-rel-1',
        applicationId: 'app-rel-1',
        path: cv.filePath,
        filename: 'cv-ver-1.pdf',
        contentType: 'application/pdf',
      },
      coverLetter: cl
        ? {
            kind: 'coverLetter',
            versionId: 'cl-v-1',
            sha256: cl.sha256,
            jobId: 'job-rel-1',
            applicationId: 'app-rel-1',
            path: cl.filePath,
            filename: 'cl-ver-1.pdf',
            contentType: 'application/pdf',
          }
        : null,
      artifacts,
    },
  };
}

async function startHtmlServer(handler) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    port,
    origin: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve, reject) => server.close((err) => (err ? reject(err) : resolve()))),
  };
}

test('Workstream A: allows HTTPS CDN cross-origin assets while blocking private IPs, HTTP downgrades, and 302 metadata redirects', async () => {
  // 1. Unit policy proof (allowLocalTestHosts=false production mode)
  assert.equal(evaluateUniversalResourceRequest({
    reqUrl: 'https://cdn.example-assets.net/bundle.js',
    expectedUrl: 'https://careers.acme.com/apply',
    resourceType: 'script',
    isNavigation: false,
    allowLocalTestHosts: false,
  }).allowed, true);
  assert.equal(evaluateUniversalResourceRequest({
    reqUrl: 'http://169.254.169.254/latest/meta-data/iam',
    expectedUrl: 'https://careers.acme.com/apply',
    resourceType: 'fetch',
    isNavigation: false,
    allowLocalTestHosts: false,
  }).allowed, false);
  assert.equal(evaluateUniversalResourceRequest({
    reqUrl: 'data:text/html,<script>alert(1)</script>',
    expectedUrl: 'https://careers.acme.com/apply',
    resourceType: 'document',
    isNavigation: true,
    allowLocalTestHosts: false,
  }).allowed, false);

  // 2. Real Chromium test: cross-origin CDN server assets (script, CSS, font, image, JSON XHR) load,
  // while 302 redirect -> 169.254.169.254 and direct 10.0.0.1 fetch fail closed cleanly.
  const cdnServer = await startHtmlServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    if (req.url === '/app.js') {
      res.writeHead(200, { 'Content-Type': 'application/javascript' });
      res.end('window.__cdnLoaded = true; document.getElementById("cdn-status").dataset.js = "ok";');
      return;
    }
    if (req.url === '/style.css') {
      res.writeHead(200, { 'Content-Type': 'text/css' });
      res.end('@font-face { font-family: "TestFont"; src: url("/font.woff2"); } body { font-family: "TestFont", sans-serif; color: rgb(1, 2, 3); }');
      return;
    }
    if (req.url === '/font.woff2') {
      res.writeHead(200, { 'Content-Type': 'font/woff2' });
      res.end(Buffer.from('wOF2TEST'));
      return;
    }
    if (req.url === '/logo.svg') {
      res.writeHead(200, { 'Content-Type': 'image/svg+xml' });
      res.end('<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"></svg>');
      return;
    }
    if (req.url === '/config.json') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"apiReady":true}');
      return;
    }
    if (req.url === '/redirect-ssrf?token=secret-query') {
      res.writeHead(302, { Location: 'http://169.254.169.254/latest/meta-data' });
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  });

  const appServer = await startHtmlServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(`<!doctype html><html><head>
      <link rel="stylesheet" href="${cdnServer.origin}/style.css">
    </head><body>
      <img src="${cdnServer.origin}/logo.svg" alt="Logo">
      <div id="cdn-status">loading</div>
      <form><input type="text" name="first_name" placeholder="First Name"></form>
      <script src="${cdnServer.origin}/app.js"></script>
      <script src="${cdnServer.origin}/redirect-ssrf?token=secret-query"></script>
      <script>
        fetch('${cdnServer.origin}/config.json').then(r => r.json()).then(d => { window.__apiReady = d.apiReady; });
        fetch('https://10.0.0.1/internal?auth=dontleak').catch(() => { window.__privateBlocked = true; });
      </script>
    </body></html>`);
  });

  const browser = await chromium.launch();
  try {
    // Verify allowLocalTestHosts=false aborts localhost/private IP subresources and cross-origin documents
    const prodCtx = await browser.newContext();
    const prodPage = await prodCtx.newPage();
    await installUniversalNavigationGuard(prodPage, 'https://employer.example.test/apply', { allowLocalTestHosts: false });
    await assert.rejects(() => prodPage.goto('https://evil.example.net/steal', { waitUntil: 'commit' }));
    await assert.rejects(() => prodPage.goto(`http://127.0.0.1:${cdnServer.port}/app.js`, { waitUntil: 'commit' }));
    await assert.rejects(() => prodPage.goto('http://169.254.169.254/latest/meta-data', { waitUntil: 'commit' }));
    await prodCtx.close();

    // Real dual-server Chromium test: cross-origin CDN JS/CSS/font/img/XHR load while
    // 302 redirect -> 169.254.169.254 and direct 10.0.0.1 fetch abort BEFORE connecting.
    const page = await browser.newPage();
    const guard = await installUniversalNavigationGuard(page, `${appServer.origin}/apply`, { allowLocalTestHosts: true });
    await page.goto(`${appServer.origin}/apply`, { waitUntil: 'networkidle' });
    const state = await page.evaluate(() => ({
      cdnJs: window.__cdnLoaded === true,
      apiReady: window.__apiReady === true,
      privateBlocked: window.__privateBlocked === true,
    }));
    assert.equal(state.cdnJs, true);
    assert.equal(state.apiReady, true);
    assert.equal(state.privateBlocked, true);
    const diag = guard.getDiagnostics();
    assert.ok(diag.blockedResources.some((b) => b.url.includes('169.254.169.254')));
    assert.ok(diag.blockedResources.some((b) => b.url.includes('10.0.0.1')));
    assert.equal(JSON.stringify(diag).includes('secret-query'), false, 'Diagnostics must strip query tokens');
    assert.equal(JSON.stringify(diag).includes('dontleak'), false, 'Diagnostics must strip query tokens');
  } finally {
    await browser.close();
    await cdnServer.close();
    await appServer.close();
  }
});

test('Workstream B: discovers visually hidden custom file uploads, rejects honeypot decoys, distinguishes CV vs cover letter, and enforces accept types', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'univ-upload-'));
  const uploadsObserved = [];
  const server = await startHtmlServer((req, res) => {
    if (req.url === '/accept-mismatch') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(`<!doctype html><html><body><form>
        <label for="f1">Resume / CV*</label>
        <input id="f1" type="file" accept=".docx" required>
      </form></body></html>`);
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(`<!doctype html><html><body>
      <form id="app-form">
        <label for="first">First Name*</label>
        <input id="first" name="firstName" type="text" required>

        <!-- Custom visually-hidden CV uploader driven via visible <label for> -->
        <label for="hidden-cv">Upload Resume / CV*</label>
        <input id="hidden-cv" name="cv" type="file" accept=".pdf,application/pdf" style="display:none" required>

        <!-- Custom dropzone Cover Letter uploader driven via sibling button -->
        <div class="upload-dropzone">
          <button type="button">Attach Cover Letter</button>
          <input id="dropzone-cl" name="coverLetter" type="file" style="opacity:0; position:absolute; width:1px; height:1px;">
        </div>

        <!-- Genuine honeypot traps: MUST remain untouched -->
        <input id="hp-aria" name="hp_cv" type="file" aria-hidden="true" tabindex="-1">
        <div style="position:absolute; left:-5000px;"><input id="hp-offscreen" name="decoy_file" type="file"></div>
        <input id="hp-text" name="website" type="text" aria-hidden="true" tabindex="-1" style="display:none">
      </form>
    </body></html>`);
  });

  try {
    // 1. Hidden custom CV + cover letter dropzone attach verified files while honeypots stay empty
    const targetUrl = `${server.origin}/apply`;
    const plan = buildUniversalPlan(root, targetUrl, { includeCoverLetter: true });
    const worker = new BrowserWorker({ statePath: path.join(root, 's1.json'), artifactDir: root });
    const res = await worker.execute(plan, { approve: false, headless: true });
    const out = computePlanOutcome(plan, res);
    assert.equal(out.result[0].resumeUploadState, 'ATTACHED');
    assert.equal(out.result[0].coverLetterUploadState, 'ATTACHED');
    assert.equal(out.result[0].requiredGaps.length, 0);

    // Independently check live DOM file attachments via direct inspect/attach
    const browser = await chromium.launch();
    try {
      const p = await browser.newPage();
      await p.goto(targetUrl);
      const dom = await inspectLiveFormDom(p);
      const fileLabels = dom.controls.filter((c) => c.htmlType === 'file').map((c) => c.label);
      assert.ok(fileLabels.some((l) => /Upload Resume \/ CV/i.test(l)));
      assert.ok(fileLabels.some((l) => /Attach Cover Letter/i.test(l)));
      assert.equal(dom.controls.some((c) => c.nameAttr === 'hp_cv' || c.nameAttr === 'decoy_file' || c.nameAttr === 'website'), false);
    } finally {
      await browser.close();
    }

    // 2. Reject incompatible accept=".docx" cleanly with REJECTED + gap
    const badUrl = `${server.origin}/accept-mismatch`;
    const badPlan = buildUniversalPlan(root, badUrl, { planId: 'plan-accept-bad' });
    const badWorker = new BrowserWorker({ statePath: path.join(root, 's2.json'), artifactDir: root });
    const badRes = await badWorker.execute(badPlan, { approve: false, headless: true });
    const badOut = computePlanOutcome(badPlan, badRes);
    assert.equal(badOut.result[0].resumeUploadState, 'REJECTED');
    assert.equal(badOut.result[0].requiredGaps.length, 1);
    assert.match(badOut.result[0].requiredGaps[0].reason, /\.docx/);
  } finally {
    await server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
  assert.equal(uploadsObserved.length, 0);
});

test('Workstream C: traverses open Shadow DOM, reports closed Shadow DOM honestly, selects portal comboboxes, re-inspects conditional reveals, handles Apply Now button, and respects inline validation errors', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'univ-dyn-'));
  const submissions = [];
  const server = await startHtmlServer((req, res) => {
    if (req.url === '/shadow-apply' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(`<!doctype html><html><body>
        <div id="landing">
          <h1>Staff Engineer</h1>
          <button id="apply-now-btn" type="button">Apply Now</button>
        </div>
        <div id="app-mount" style="display:none"></div>
        <x-closed-widget></x-closed-widget>
        <open-apply-host id="shadow-host"></open-apply-host>
        <script>
          class ClosedWidget extends HTMLElement {
            constructor() {
              super();
              const sr = this.attachShadow({ mode: 'closed' });
              sr.innerHTML = '<input name="closedSecret" required>';
            }
          }
          customElements.define('x-closed-widget', ClosedWidget);

          class OpenApplyHost extends HTMLElement {
            connectedCallback() {
              if (this.shadowRoot) return;
              this.attachShadow({ mode: 'open' });
            }
          }
          customElements.define('open-apply-host', OpenApplyHost);

          document.getElementById('apply-now-btn').addEventListener('click', () => {
            document.getElementById('landing').style.display = 'none';
            const sr = document.getElementById('shadow-host').shadowRoot;
            sr.innerHTML = \`
              <form id="shadow-form">
                <label for="fn">First Name*</label>
                <input id="fn" name="firstName" type="text" required>

                <label for="spons">Do you require visa sponsorship?*</label>
                <select id="spons" name="sponsorship" required>
                  <option value="">Select...</option>
                  <option value="Yes">Yes</option>
                  <option value="No">No</option>
                </select>
                <div id="conditional-box"></div>

                <label id="cb-lbl">Work Eligibility*</label>
                <div id="combo" role="combobox" aria-labelledby="cb-lbl" aria-controls="portal-list" tabindex="0">Choose...</div>
                <button type="submit">Submit Application</button>
              </form>
            \`;
            sr.getElementById('spons').addEventListener('change', (e) => {
              if (e.target.value === 'Yes') {
                sr.getElementById('conditional-box').innerHTML =
                  '<label for="visa-detail">Which visa classification do you hold?*</label>'
                  + '<input id="visa-detail" name="visaType" type="text" required>';
              }
            });
            sr.getElementById('combo').addEventListener('click', () => {
              if (document.getElementById('portal-list')) return;
              const portal = document.createElement('div');
              portal.id = 'portal-list';
              portal.setAttribute('role', 'listbox');
              portal.innerHTML = '<div role="option" data-value="UK Right">Yes, full UK right to work</div><div role="option" data-value="No">No</div>';
              portal.querySelectorAll('[role="option"]').forEach((opt) => {
                opt.addEventListener('click', () => {
                  sr.getElementById('combo').textContent = opt.textContent;
                  sr.getElementById('combo').setAttribute('data-value', opt.getAttribute('data-value'));
                  portal.remove();
                });
              });
              document.body.appendChild(portal);
            });
            sr.getElementById('shadow-form').addEventListener('submit', (e) => {
              e.preventDefault();
            });
          });
        </script>
      </body></html>`);
      return;
    }
    if (req.url === '/validation-gate') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(`<!doctype html><html><body>
        <form>
          <label for="fn">First Name*</label>
          <input id="fn" name="firstName" type="text" required>
          <div role="alert" class="field-error">Employer site reports missing phone country prefix</div>
          <button type="button" onclick="window.__advanced = true">Continue</button>
        </form>
      </body></html>`);
      return;
    }
    res.writeHead(404);
    res.end();
  });

  try {
    // 1. Pre-form "Apply Now" reveal -> open Shadow DOM fill -> portal combobox selection -> conditional reveal pause + closed shadow host reported
    const shadowUrl = `${server.origin}/shadow-apply`;
    const plan = buildUniversalPlan(root, shadowUrl, {
      planId: 'plan-shadow-1',
      confirmedAnswers: [
        { answerId: 'a1', questionText: 'Do you require visa sponsorship?', answerText: 'Yes' },
        { answerId: 'a2', questionText: 'Work Eligibility', answerText: 'Yes, full UK right to work' },
      ],
    });
    const worker = new BrowserWorker({ statePath: path.join(root, 's-shadow.json'), artifactDir: root });
    const run = await worker.execute(plan, { approve: false, headless: true });
    const out = computePlanOutcome(plan, run);
    assert.equal(submissions.length, 0, 'Apply Now reveal must never be misclassified or clicked as submit');
    assert.ok(out.result[0].filled.some((f) => /First Name/i.test(f)));
    assert.ok(out.result[0].filled.some((f) => /Do you require visa sponsorship/i.test(f)));
    assert.ok(out.result[0].filled.some((f) => /Work Eligibility/i.test(f)));
    assert.ok(out.result[0].closedShadowHosts.includes('x-closed-widget'), 'Must report closed Shadow DOM honestly');
    assert.ok(out.result[0].requiredGaps.some((g) => /Which visa classification do you hold/i.test(g.questionText)),
      'Must re-inspect same page after fill and catch conditionally revealed mandatory question');

    // 2. Visible inline validation error blocks advancing Continue/Next
    const valUrl = `${server.origin}/validation-gate`;
    const valPlan = buildUniversalPlan(root, valUrl, { planId: 'plan-val-gate' });
    const valWorker = new BrowserWorker({ statePath: path.join(root, 's-val.json'), artifactDir: root });
    const valRun = await valWorker.execute(valPlan, { approve: false, headless: true });
    const valOut = computePlanOutcome(valPlan, valRun);
    assert.ok(valOut.result[0].validationErrors.some((v) => v.includes('Employer site reports missing phone country prefix')));
  } finally {
    await server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('Workstream D: authenticates saved-credential login wall securely, refuses ambiguous login selectors, and stops on MFA/missing credentials', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'univ-login-'));
  const server = await startHtmlServer((req, res) => {
    if (req.url === '/login-wall' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(`<!doctype html><html><body>
        <form action="/authenticated-apply" method="get">
          <label for="u">Email*</label>
          <input id="u" type="email" name="email" autocomplete="username" required>
          <label for="p">Password*</label>
          <input id="p" type="password" name="password" autocomplete="current-password" required>
          <button type="submit">Sign In</button>
        </form>
      </body></html>`);
      return;
    }
    if (req.url.startsWith('/authenticated-apply')) {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(`<!doctype html><html><body>
        <form>
          <label for="fn">First Name*</label>
          <input id="fn" type="text" name="firstName" required>
          <button type="submit">Submit Application</button>
        </form>
      </body></html>`);
      return;
    }
    if (req.url === '/ambiguous-login') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(`<!doctype html><html><body>
        <form><input type="password" name="pw1"><input type="password" name="pw2"><button type="submit">Login</button></form>
      </body></html>`);
      return;
    }
    if (req.url === '/mfa-after-login') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(`<!doctype html><html><body><p>Enter the two-factor authenticator code sent to your device.</p></body></html>`);
      return;
    }
    res.writeHead(404);
    res.end();
  });

  try {
    const events = [];
    const secretPass = 'SuperSecretPassword!99';
    const stateFile = path.join(root, 'login-state.json');

    // 1. Saved credential succeeds, proceeds into form fill, and NEVER persists/logs plaintext password
    const worker = new BrowserWorker({
      statePath: stateFile,
      artifactDir: root,
      eventSink: (ev) => { events.push(ev); },
      credentialResolver: async ({ employerDomain }) => {
        assert.equal(employerDomain, '127.0.0.1');
        return { username: 'ada@inbox.example', password: secretPass };
      },
    });
    const loginPlan = buildUniversalPlan(root, `${server.origin}/login-wall`, { hasSavedCredential: true });
    const run = await worker.execute(loginPlan, { approve: false, headless: true });
    const out = computePlanOutcome(loginPlan, run);
    assert.equal(out.result[0].loginSucceeded, true);
    assert.ok(out.result[0].filled.some((f) => /First Name/i.test(f)));
    assert.equal(fs.readFileSync(stateFile, 'utf8').includes(secretPass), false, 'Plaintext password must NEVER touch state file');
    assert.equal(JSON.stringify(events).includes(secretPass), false, 'Plaintext password must NEVER appear in worker events');
    assert.equal(JSON.stringify(out).includes(secretPass), false, 'Plaintext password must NEVER appear in outcome');

    // 2. Unverified/ambiguous login form refuses guessed selectors
    const ambPlan = buildUniversalPlan(root, `${server.origin}/ambiguous-login`, { planId: 'plan-amb', hasSavedCredential: true });
    const ambWorker = new BrowserWorker({
      statePath: path.join(root, 's-amb.json'),
      artifactDir: root,
      credentialResolver: async () => ({ username: 'u', password: secretPass }),
    });
    await assert.rejects(
      () => ambWorker.execute(ambPlan, { approve: false, headless: true }),
      (err) => {
        assert.equal(err.reason, 'UNIVERSAL_LOGIN_SELECTOR_UNVERIFIED');
        assert.equal(isHandoffEligibleError(err), true);
        assert.equal(normalizeHandoffReason(err, 'UNIVERSAL'), 'EMPLOYER_SECURITY_CHECK');
        return true;
      }
    );

    // 3. Missing saved credential & generic MFA classify accurately for handoff (never WORKDAY_MFA_REQUIRED)
    const noCredPlan = buildUniversalPlan(root, `${server.origin}/login-wall`, { planId: 'plan-nocred', hasSavedCredential: false });
    await assert.rejects(
      () => new BrowserWorker({ statePath: path.join(root, 's-nocred.json'), artifactDir: root }).execute(noCredPlan, { approve: false, headless: true }),
      (err) => {
        assert.equal(err.reason, 'UNIVERSAL_LOGIN_REQUIRED');
        assert.equal(normalizeHandoffReason(err, 'UNIVERSAL'), 'EMPLOYER_SECURITY_CHECK');
        return true;
      }
    );

    const mfaPlan = buildUniversalPlan(root, `${server.origin}/mfa-after-login`, { planId: 'plan-mfa' });
    await assert.rejects(
      () => new BrowserWorker({ statePath: path.join(root, 's-mfa.json'), artifactDir: root }).execute(mfaPlan, { approve: false, headless: true }),
      (err) => {
        assert.equal(err.reason, 'UNIVERSAL_MFA_REQUIRED');
        assert.equal(isHandoffEligibleError(err), true);
        assert.equal(normalizeHandoffReason(err, 'UNIVERSAL'), 'MFA');
        return true;
      }
    );
  } finally {
    await server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});
