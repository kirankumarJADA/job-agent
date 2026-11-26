import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { canonicalJson } from '../src/receipt.js';
import {
  UNIVERSAL_EVIDENCE_SCHEMA_VERSION,
  UniversalSubmitCategory,
  attachedArtifactsInForm,
  classifyUniversalSubmitOutcome,
  executeAndVerifyUniversalSubmit,
  multipartFileNames,
  redactPersonalData,
  sealUniversalEvidence,
  summarizeDocumentUploads,
  verifyUniversalEvidenceDigest,
} from '../src/universal_confirmation.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const TARGET = 'https://careers.acme.example/jobs/505/apply';
const CONFIRM = 'https://careers.acme.example/jobs/505/confirmation';
const IDS = {
  planId: '00000000-0000-7000-8000-000000000001',
  applicationId: '00000000-0000-7000-8000-000000000002',
  jobId: '00000000-0000-7000-8000-000000000003',
};

function confirmed(overrides = {}) {
  return classifyUniversalSubmitOutcome({
    expectedUrl: TARGET,
    finalUrl: CONFIRM,
    networkResponses: [{ method: 'POST', url: TARGET, status: 201, observedAt: '2026-11-26T10:00:00.000Z' }],
    domState: {
      formCount: 0,
      controlsCount: 0,
      hasConfirmationContainer: true,
      containerText: 'Thank you for applying. Reference: EMP-REAL-4201',
      containerHtml: '<div class="confirmation">Thank you for applying. Reference: EMP-REAL-4201</div>',
      bodyText: 'Thank you for applying.',
    },
    ...IDS,
    ...overrides,
  });
}

test('v2 evidence carries correlation, redirect chain, final origin and a verifiable canonical digest', () => {
  const out = confirmed({
    navigationChain: [{ url: TARGET, status: 303 }, { url: CONFIRM, status: 200 }],
  });
  assert.equal(out.confirmed, true);
  const ev = JSON.parse(out.rawEvidence);
  assert.equal(ev.schemaVersion, UNIVERSAL_EVIDENCE_SCHEMA_VERSION);
  assert.deepEqual(ev.attemptCorrelation, IDS);
  assert.deepEqual(ev.redirectChain, [{ host: 'careers.acme.example', status: 303 }, { host: 'careers.acme.example', status: 200 }]);
  assert.equal(ev.finalOrigin, 'https://careers.acme.example');
  assert.equal(ev.request.method, 'POST');
  assert.equal(ev.request.status, 201);
  assert.equal(ev.confirmationNumber, 'EMP-REAL-4201');
  assert.equal(ev.confirmationIdentifierSource, 'CONTAINER_TEXT_PATTERN');
  assert.equal(ev.employerVerification, 'NOT_VERIFIED_BY_BROWSER');
  assert.deepEqual(ev.observation, { source: 'PLAYWRIGHT_BROWSER', clock: 'WORKER_CLOCK' });
  // Without upload facts nothing is claimed about documents.
  assert.equal(ev.documents.cv.uploadVerification, 'NOT_RECORDED');
  assert.equal(ev.submittedCvSha256, null);
  // Self-digest: SHA-256 over the canonical JSON of every other field.
  const { evidenceSha256, ...body } = ev;
  assert.equal(evidenceSha256, crypto.createHash('sha256').update(canonicalJson(body), 'utf8').digest('hex'));
  assert.ok(verifyUniversalEvidenceDigest(out.rawEvidence));
  assert.equal(out.rawEvidence, canonicalJson(ev));
});

test('the same observation always serialises to the same evidence bytes', () => {
  const a = confirmed({ navigationChain: [{ url: CONFIRM, status: 200 }] });
  const b = confirmed({ navigationChain: [{ url: CONFIRM, status: 200 }] });
  assert.equal(a.rawEvidence, b.rawEvidence);
});

test('a tampered evidence record fails its digest', () => {
  const out = confirmed();
  assert.equal(verifyUniversalEvidenceDigest(out.rawEvidence.replace('EMP-REAL-4201', 'EMP-REAL-9999')), false);
  assert.equal(verifyUniversalEvidenceDigest(out.rawEvidence.replace(/,"evidenceSha256":"[0-9a-f]{64}"/, '')), false);
  assert.equal(verifyUniversalEvidenceDigest('not json'), false);
});

test('a cross-origin hop anywhere in the redirect chain is rejected, even if the chain ends on the target', () => {
  const out = confirmed({
    navigationChain: [
      { url: TARGET, status: 302 },
      { url: 'https://login.acme-sso.example/authorize', status: 302 },
      { url: CONFIRM, status: 200 },
    ],
  });
  assert.equal(out.confirmed, false);
  assert.equal(out.category, UniversalSubmitCategory.UNEXPECTED_ORIGIN_REDIRECT);
  assert.ok(out.signals.includes('UNIVERSAL_REDIRECT_CHAIN_ORIGIN_MISMATCH'));
  assert.equal(out.rawEvidence, undefined);
});

test('personal data is redacted before persistence while the genuine identifier is preserved', () => {
  const out = confirmed({
    domState: {
      formCount: 0,
      controlsCount: 0,
      hasConfirmationContainer: true,
      containerText: 'Thanks Ada Lovelace! We e-mailed ada@inbox.example and will call +44 7700 900077. Confirmation number: 7700900077',
      containerHtml: '<p>Thanks Ada Lovelace! We e-mailed ada@inbox.example. Confirmation number: 7700900077</p>',
      bodyText: 'Thank you for applying.',
    },
    identityValues: ['Ada Lovelace', 'Ada', 'Lovelace', 'ada@inbox.example', '+44 7700 900077'],
  });
  const ev = JSON.parse(out.rawEvidence);
  for (const field of [ev.confirmationText, ev.confirmationContainerHtml]) {
    assert.ok(!field.includes('Ada'), field);
    assert.ok(!field.includes('Lovelace'), field);
    assert.ok(!field.includes('ada@inbox.example'), field);
    assert.ok(!field.includes('+44 7700 900077'), field);
  }
  assert.equal(ev.confirmationNumber, '7700900077');
  assert.ok(ev.confirmationText.includes('7700900077'));
  assert.equal(redactPersonalData('mail bob@example.org or 020 7946 0958'), 'mail [email] or [phone]');
});

test('upload proof never claims more than the browser observed', () => {
  const pkg = { cv: { path: '/x/cv-ver-1.pdf', sha256: 'b'.repeat(64) }, coverLetter: { path: '/x/cl.pdf', sha256: 'c'.repeat(64) } };
  const sent = summarizeDocumentUploads({ pkg, uploadStates: { cv: 'ATTACHED', coverLetter: 'NO_FIELD' }, transmittedFileNames: ['cv-ver-1.pdf'] });
  assert.equal(sent.documents.cv.uploadVerification, 'FILE_PART_OBSERVED');
  assert.equal(sent.documents.cv.bytesTransmissionObserved, false);
  assert.equal(sent.submittedCvSha256, 'b'.repeat(64));
  assert.equal(sent.documents.coverLetter.uploadVerification, 'NO_FIELD');
  assert.equal(sent.submittedCoverLetterSha256, null);

  // Selected/attached, but no file part in the accepted request: attached, not confirmed — and no digest claimed.
  const attachedOnly = summarizeDocumentUploads({ pkg, uploadStates: { cv: 'ATTACHED' }, transmittedFileNames: [] });
  assert.equal(attachedOnly.documents.cv.uploadVerification, 'ATTACHED_NOT_CONFIRMED');
  assert.equal(attachedOnly.submittedCvSha256, null);
  // A file part with a different name is not this artifact.
  assert.equal(summarizeDocumentUploads({ pkg, uploadStates: { cv: 'ATTACHED' }, transmittedFileNames: ['other.pdf'] })
    .documents.cv.uploadVerification, 'ATTACHED_NOT_CONFIRMED');
  // Rejected, never attempted, or no artifact: not attached, never a digest.
  for (const state of ['REJECTED', 'NOT_ATTEMPTED', undefined]) {
    const r = summarizeDocumentUploads({ pkg, uploadStates: { cv: state }, transmittedFileNames: ['cv-ver-1.pdf'] });
    assert.equal(r.documents.cv.uploadVerification, 'NOT_ATTACHED', String(state));
    assert.equal(r.documents.cv.artifactSha256, null);
    assert.equal(r.submittedCvSha256, null);
  }
  assert.equal(summarizeDocumentUploads({ pkg: {}, uploadStates: { cv: 'ATTACHED' } }).documents.cv.formAttachment, 'NO_ARTIFACT');
});

test('multipart file parts are read by name only', () => {
  const boundary = '----RobinBoundary42';
  const body = Buffer.from([
    `--${boundary}`, 'Content-Disposition: form-data; name="first"', '', 'Ada',
    `--${boundary}`, 'Content-Disposition: form-data; name="cv"; filename="cv-ver-1.pdf"', 'Content-Type: application/pdf', '', '%PDF-1.4',
    `--${boundary}--`, '',
  ].join('\r\n'), 'latin1');
  assert.deepEqual(multipartFileNames(`multipart/form-data; boundary=${boundary}`, body), ['cv-ver-1.pdf']);
  assert.deepEqual(multipartFileNames(`multipart/form-data; boundary="${boundary}"`, body), ['cv-ver-1.pdf']);
  assert.deepEqual(multipartFileNames('application/json', body), []);
  assert.deepEqual(multipartFileNames(`multipart/form-data; boundary=${boundary}`, null), []);
});

test('a selected file is only "attached" when its name, size and digest match the package artifact', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-v2-attach-'));
  try {
    const cvPath = path.join(dir, 'cv-ver-1.pdf');
    fs.writeFileSync(cvPath, '%PDF-1.4 attached artifact');
    const size = fs.statSync(cvPath).size;
    const sha = crypto.createHash('sha256').update(fs.readFileSync(cvPath)).digest('hex');
    const page = (files) => ({ evaluate: async () => files });
    assert.deepEqual(await attachedArtifactsInForm(page([{ name: 'cv-ver-1.pdf', size }]), { cv: { path: cvPath, sha256: sha } }), { cv: true });
    assert.deepEqual(await attachedArtifactsInForm(page([{ name: 'cv-ver-1.pdf', size: size + 1 }]), { cv: { path: cvPath, sha256: sha } }), { cv: false });
    assert.deepEqual(await attachedArtifactsInForm(page([{ name: 'other.pdf', size }]), { cv: { path: cvPath, sha256: sha } }), { cv: false });
    // A local file that no longer matches the package digest is never reported as the attached artifact.
    assert.deepEqual(await attachedArtifactsInForm(page([{ name: 'cv-ver-1.pdf', size }]), { cv: { path: cvPath, sha256: 'f'.repeat(64) } }), {});
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('real Chromium: a multipart submission shows the attached file part, never its bytes', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'robin-v2-mp-'));
  const cvPath = path.join(dir, 'cv-ver-1.pdf');
  fs.writeFileSync(cvPath, `%PDF-1.4 ${'x'.repeat(4000)}`);
  const sha = crypto.createHash('sha256').update(fs.readFileSync(cvPath)).digest('hex');
  let received = 0;
  const html = `<!doctype html><html><body><form id="f"><input type="file" id="cv" name="cv"><input name="first" value="Ada"></form>
    <button id="go" data-robin-submit type="button">Submit application</button>
    <script>document.getElementById('go').onclick = async () => {
      const r = await fetch('/jobs/505/apply', { method: 'POST', body: new FormData(document.getElementById('f')) });
      if (r.ok) { document.body.innerHTML = '<div class="confirmation">Thank you for applying. Your application has been received.</div>'; }
    };</script></body></html>`;
  const server = http.createServer((req, res) => {
    if (req.method === 'POST') {
      req.on('data', (c) => { received += c.length; });
      req.on('end', () => { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"ok":true}'); });
      return;
    }
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end(html);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const target = `http://127.0.0.1:${server.address().port}/jobs/505/apply`;
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    // Production Universal pages always run behind a route guard (universal_form.js), which is also
    // what lets automation read the request body; the bytes of a file part are still not exposed.
    await page.route('**/*', (route) => route.continue());
    await page.goto(target);
    await page.setInputFiles('#cv', cvPath);
    const out = await executeAndVerifyUniversalSubmit({
      page,
      submitSelector: '#go',
      expectedUrl: target,
      allowLocalTestHosts: true,
      ...IDS,
      pkg: { cv: { path: cvPath, sha256: sha } },
      uploadStates: { cv: 'ATTACHED', coverLetter: 'NO_FIELD' },
      identityValues: ['Ada'],
    });
    assert.equal(out.confirmed, true, out.evidence);
    const ev = JSON.parse(out.rawEvidence);
    assert.equal(ev.documents.cv.formAttachment, 'ATTACHED');
    assert.equal(ev.documents.cv.uploadVerification, 'FILE_PART_OBSERVED');
    assert.equal(ev.documents.cv.bytesTransmissionObserved, false);
    assert.equal(ev.submittedCvSha256, sha);
    assert.ok(verifyUniversalEvidenceDigest(out.rawEvidence));
    assert.ok(received > 4000, 'the server did receive the file');
    assert.ok(!out.rawEvidence.includes('cv-ver-1.pdf'), 'file names are never persisted');
  } finally {
    await browser.close();
    server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('cross-language contract: canonical JSON and the sealed v2 record match the shared fixture', () => {
  const fixturePath = path.join(here, '..', '..', 'backend', 'src', 'test', 'resources', 'contracts', 'submissionEvidenceV2Fixtures.json');
  const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
  assert.ok(fixture.cases.length >= 5);
  for (const c of fixture.cases) {
    const canonical = canonicalJson(JSON.parse(c.json));
    assert.equal(canonical, c.canonical, c.name);
    assert.equal(crypto.createHash('sha256').update(canonical, 'utf8').digest('hex'), c.sha256, c.name);
  }
  assert.ok(verifyUniversalEvidenceDigest(fixture.sealedEvidence));
  const sealed = JSON.parse(fixture.sealedEvidence);
  const { evidenceSha256, ...body } = sealed;
  assert.equal(sealUniversalEvidence(body), fixture.sealedEvidence);
  assert.equal(sealed.documents.cv.uploadVerification, 'FILE_PART_OBSERVED');
  assert.ok(!sealed.confirmationText.includes('Ada'));
});
