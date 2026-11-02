import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { BrowserWorker, HardStopError } from '../src/browser_worker.js';
import { StepType, zohoRecruitPageIdentityMatches } from '../src/plan.js';
import { classifyZohoField, planZohoFormActions, ZohoFieldCategory, ZohoDocumentUploadState } from '../src/zoho_recruit.js';

const URL = 'https://acme.zohorecruit.com/jobs/Careers/613047000062603183/JDE-Developer';
const hash = (content) => crypto.createHash('sha256').update(content).digest('hex');

function zohoPlan(root, { identity = {}, extraSteps = [], pkg = null } = {}) {
  const steps = [
    { id: 'navigate-application', type: StepType.NAVIGATE, policy: 'AUTO', params: { url: URL } },
    { id: 'screenshot-landing', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} },
    ...extraSteps,
  ];
  return {
    planId: 'plan-zoho', planType: 'ZOHO_RECRUIT', version: 1,
    correlation: { applicationId: 'app-1', jobId: 'job-1' }, targetUrl: URL,
    safetyContract: 'NO_SUBMIT', steps, package: pkg,
  };
}

function applyStep(identity = {}) {
  return { id: 'zoho-recruit-apply', type: StepType.ZOHO_RECRUIT_APPLY, policy: 'AUTO', params: { identity } };
}

function worker(root) {
  return new BrowserWorker({ statePath: path.join(root, 'state.json'), artifactDir: root });
}

function routedWorker(root, routeHandler) {
  return new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => { await page.route(`${URL}**`, routeHandler); },
  });
}

const FIELD = (label, { input = `<input>`, wrap = true } = {}) => wrap
  ? `<div class="field"><div class="lyteLabel">${label}</div>${input}</div>`
  : input;

// ── Pure classification (no browser) ─────────────────────────────────────

test('classifyZohoField maps the evidence-backed identity/resume/captcha labels exactly', () => {
  assert.equal(classifyZohoField('First Name *', 'input', 'text'), ZohoFieldCategory.IDENTITY_FIRST_NAME);
  assert.equal(classifyZohoField('Last Name *', 'input', 'text'), ZohoFieldCategory.IDENTITY_LAST_NAME);
  assert.equal(classifyZohoField('Email *', 'input', 'text'), ZohoFieldCategory.IDENTITY_EMAIL);
  assert.equal(classifyZohoField('Email Address *', 'input', 'text'), ZohoFieldCategory.IDENTITY_EMAIL);
  assert.equal(classifyZohoField('Resume *', 'input', 'file'), ZohoFieldCategory.RESUME_UPLOAD);
  assert.equal(classifyZohoField('CAPTCHA *', 'input', 'text'), ZohoFieldCategory.CAPTCHA);
});

test('classifyZohoField never auto-fills what it cannot confidently identify', () => {
  assert.equal(classifyZohoField('Current Job Title *', 'select', null), ZohoFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyZohoField('City *', 'input', 'text'), ZohoFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyZohoField('Country *', 'input', 'text'), ZohoFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyZohoField('Experience in Years', 'input', 'text'), ZohoFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyZohoField(null, 'input', 'text'), ZohoFieldCategory.UNRECOGNIZED);
});

test('classifyZohoField fills Mobile/Phone verbatim, but never the country-dial-code picker sharing its label', () => {
  assert.equal(classifyZohoField('Mobile *', 'input', 'text'), ZohoFieldCategory.IDENTITY_PHONE);
  assert.equal(classifyZohoField('Phone', 'input', 'tel'), ZohoFieldCategory.IDENTITY_PHONE);
  // The composite widget's search box inherits the same "Mobile *" label
  // from its shared group wrapper but must never be treated as fillable —
  // and, being a sub-control rather than its own field, never reported as
  // its own required gap either (planZohoFormActions covers that).
  assert.equal(classifyZohoField('Mobile *', 'input', 'text', 'Search country with dial code'), ZohoFieldCategory.COMPOSITE_WIDGET_PART);
  assert.equal(classifyZohoField(null, 'input', 'text', 'Search country with dial code'), ZohoFieldCategory.COMPOSITE_WIDGET_PART);
  // A field merely labelled "Mobile" that isn't a text/tel input (e.g. a
  // hidden composite control) is not safely fillable either.
  assert.equal(classifyZohoField('Mobile *', 'select', null), ZohoFieldCategory.REQUIRES_HUMAN);
});

test('classifyZohoField fills the LinkedIn field verbatim (Phase 13), but never a non-text control sharing its label', () => {
  // Live-verified (fulcrumdigital.zohorecruit.com, 2026-10-21): an optional
  // "Social Links" -> "LinkedIn" plain text field, distinct from the
  // unrelated "Apply with LinkedIn" OAuth-style button on the same page.
  assert.equal(classifyZohoField('LinkedIn', 'input', 'text'), ZohoFieldCategory.IDENTITY_LINKEDIN);
  assert.equal(classifyZohoField('LinkedIn', 'input', 'url'), ZohoFieldCategory.IDENTITY_LINKEDIN);
  // A field merely labelled "LinkedIn" that isn't a text/url input is not
  // safely fillable either.
  assert.equal(classifyZohoField('LinkedIn', 'select', null), ZohoFieldCategory.REQUIRES_HUMAN);
  assert.equal(classifyZohoField('LinkedIn', 'input', 'checkbox'), ZohoFieldCategory.REQUIRES_HUMAN);
});

test('classifyZohoField never auto-checks a consent/legal checkbox regardless of its label', () => {
  assert.equal(classifyZohoField('I confirm that I have read and understood the Privacy Notice...', 'input', 'checkbox'),
    ZohoFieldCategory.REQUIRES_HUMAN);
  // Even a checkbox that happens to be labelled exactly like an identity field is never auto-checked.
  assert.equal(classifyZohoField('Email', 'input', 'checkbox'), ZohoFieldCategory.REQUIRES_HUMAN);
});

test('classifyZohoField flags a differently-worded captcha label rather than missing it', () => {
  assert.equal(classifyZohoField('Please solve this CAPTCHA to continue', 'input', 'text'), ZohoFieldCategory.CAPTCHA);
});

test('planZohoFormActions fills only matched identity fields and reports every other required field as a gap', () => {
  const fields = [
    { index: 0, rawLabel: 'First Name *', tag: 'input', type: 'text', required: true },
    { index: 1, rawLabel: 'Last Name *', tag: 'input', type: 'text', required: true },
    { index: 2, rawLabel: 'Email *', tag: 'input', type: 'text', required: true },
    { index: 3, rawLabel: 'Mobile *', tag: 'input', type: 'text', required: true },
    { index: 4, rawLabel: 'LinkedIn', tag: 'input', type: 'text', required: false },
  ];
  const result = planZohoFormActions(fields, { firstName: 'Ada', lastName: 'Lovelace', email: 'ada@example.test' });
  assert.equal(result.captchaPresent, false);
  assert.deepEqual(result.toFill.map((f) => f.index), [0, 1, 2]);
  assert.deepEqual(result.requiredGaps.map((g) => g.index), [3]);
});

test('planZohoFormActions fills phone and LinkedIn (Phase 13) when the candidate has them, never invents either', () => {
  const fields = [
    { index: 0, rawLabel: 'Mobile *', tag: 'input', type: 'text', required: true },
    { index: 1, rawLabel: 'LinkedIn', tag: 'input', type: 'text', required: false },
  ];
  const withBoth = planZohoFormActions(fields, { phone: '+44 7700 900123', linkedIn: 'https://linkedin.com/in/ada' });
  assert.deepEqual(withBoth.toFill, [
    { index: 0, value: '+44 7700 900123', category: 'IDENTITY_PHONE' },
    { index: 1, value: 'https://linkedin.com/in/ada', category: 'IDENTITY_LINKEDIN' },
  ]);
  assert.deepEqual(withBoth.requiredGaps, []);

  // Without either value, phone (required) is a gap and LinkedIn (optional) is silently skipped.
  const withNeither = planZohoFormActions(fields, {});
  assert.deepEqual(withNeither.toFill, []);
  assert.deepEqual(withNeither.requiredGaps.map((g) => g.index), [0]);
});

test('planZohoFormActions fills nothing at all when a CAPTCHA is present anywhere on the form', () => {
  const fields = [
    { index: 0, rawLabel: 'First Name *', tag: 'input', type: 'text', required: true },
    { index: 1, rawLabel: 'CAPTCHA *', tag: 'input', type: 'text', required: true },
  ];
  const result = planZohoFormActions(fields, { firstName: 'Ada' });
  assert.equal(result.captchaPresent, true);
  assert.deepEqual(result.toFill, []);
});

test('planZohoFormActions never fills an identity field whose candidate value is missing', () => {
  const fields = [{ index: 0, rawLabel: 'First Name *', tag: 'input', type: 'text', required: true }];
  const result = planZohoFormActions(fields, {});
  assert.deepEqual(result.toFill, []);
  assert.deepEqual(result.requiredGaps.map((g) => g.index), [0]);
});

// ── Page identity (no browser) ────────────────────────────────────────────

test('Zoho Recruit identity requires an exact HTTPS zohorecruit.com/.in host', () => {
  assert.equal(zohoRecruitPageIdentityMatches(URL, URL), true);
  assert.equal(zohoRecruitPageIdentityMatches(URL, URL.replace('acme.zohorecruit.com', 'evil.example')), false);
  assert.equal(zohoRecruitPageIdentityMatches(URL, URL.replace('https:', 'http:')), false);
  assert.equal(zohoRecruitPageIdentityMatches(URL, URL.replace('acme.zohorecruit.com', 'other.zohorecruit.com')), false);
  assert.equal(zohoRecruitPageIdentityMatches('https://x.zohorecruit.in/jobs/Careers/1/A', 'https://x.zohorecruit.in/jobs/Careers/1/A'), true);
});

// ── Static plan-shape rejection (no browser) ──────────────────────────────

test('a ZOHO_RECRUIT plan can never carry a REAL_SUBMIT_CLICK step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-no-real-submit-'));
  const submit = { id: 'real-submit-click', type: StepType.REAL_SUBMIT_CLICK, policy: 'AUTO', params: { selector: 'button[type="submit"]' } };
  const plan = zohoPlan(root, { extraSteps: [applyStep(), submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_ZOHO_RECRUIT_SUBMIT_STEP');
});

test('a ZOHO_RECRUIT plan can never carry a MOCK_SUBMIT step either', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-no-mock-submit-'));
  const submit = { id: 'mock-submit', type: StepType.MOCK_SUBMIT, policy: 'AUTO', params: { selector: 'button[type="submit"]' } };
  const plan = zohoPlan(root, { extraSteps: [applyStep(), submit] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_ZOHO_RECRUIT_SUBMIT_STEP');
});

test('a step type outside the ZOHO_RECRUIT allowlist is rejected', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-forbidden-step-'));
  const fill = { id: 'fill-something', type: StepType.FILL_FIELD, policy: 'AUTO', params: { selector: '#x', value: 'y' } };
  const plan = zohoPlan(root, { extraSteps: [fill] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'FORBIDDEN_ZOHO_RECRUIT_STEP');
});

test('ZOHO_RECRUIT_APPLY must be the plan\'s last step', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-apply-not-last-'));
  const extra = { id: 'screenshot-extra', type: StepType.SCREENSHOT, policy: 'AUTO', params: {} };
  const plan = zohoPlan(root, { extraSteps: [applyStep(), extra] });
  return assert.rejects(() => worker(root).execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ZOHO_RECRUIT_APPLY_STEP_MUST_BE_LAST');
});

// ── Live (routed, zero-real-network) browser execution ────────────────────

test('a valid form fills only the matched identity fields and reports every other field honestly', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-valid-form-'));
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Last Name *', { input: '<input type="text">' })}
    ${FIELD('Email *', { input: '<input type="text">' })}
    ${FIELD('Mobile *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file">' })}
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada', lastName: 'Lovelace', email: 'ada@example.test' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  const zoho = result.zohoRecruitResult;
  assert.equal(zoho.captchaPresent, false);
  assert.deepEqual(zoho.filled.map((f) => f.category).sort(), [
    ZohoFieldCategory.IDENTITY_EMAIL, ZohoFieldCategory.IDENTITY_FIRST_NAME, ZohoFieldCategory.IDENTITY_LAST_NAME,
  ].sort());
  const gapLabels = zoho.requiredGaps.map((g) => g.label);
  assert.ok(gapLabels.includes('Mobile *'));
  assert.ok(gapLabels.includes('Resume *'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('a composite phone/mobile widget fills only the real number input, never the country-dial-code search box', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-phone-composite-'));
  // Mirrors the live structure: one shared "Mobile *" label wrapping both the
  // country-dial-code search box AND the plain number input as siblings —
  // both legitimately inherit the same label text.
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    <div class="field">
      <div class="lyteLabel">Mobile *</div>
      <input type="text" placeholder="Search country with dial code">
      <input type="text">
    </div>
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada', phone: '+44 20 7946 0000' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  const zoho = result.zohoRecruitResult;
  assert.deepEqual(zoho.filled.map((f) => f.category).sort(), [
    ZohoFieldCategory.IDENTITY_FIRST_NAME, ZohoFieldCategory.IDENTITY_PHONE,
  ].sort());
  assert.equal(zoho.requiredGaps.length, 0, 'the composite widget resolved cleanly, nothing should remain a gap');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a phone value the live form rejects as invalid is reported as a gap, never claimed as filled', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-phone-invalid-'));
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Mobile *', { input: '<input type="tel" pattern="^[0-9]{10}$" required>' })}
  </body></html>`;
  // A value the field's own pattern rejects — proves the post-fill validity
  // check (not just "did .fill() not throw") decides what counts as filled.
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada', phone: '+44 not a number' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  const zoho = result.zohoRecruitResult;
  assert.ok(!zoho.filled.some((f) => f.category === ZohoFieldCategory.IDENTITY_PHONE),
    'an invalid phone entry must never be reported as successfully filled');
  assert.ok(zoho.requiredGaps.some((g) => g.label === 'Mobile *'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('a résumé is uploaded only once its artifact correlation, type and checksum all verify', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-resume-upload-'));
  const file = path.join(root, 'cv.pdf');
  fs.writeFileSync(file, 'exact-cv-bytes');
  const digest = hash('exact-cv-bytes');
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file" accept="application/pdf,.pdf,.doc,.docx">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    pkg: { cv: { path: file, versionId: 'cv-v1', sha256: digest, jobId: 'job-1', applicationId: 'app-1' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  const zoho = result.zohoRecruitResult;
  // ATTACHED, never claimed as a confirmed "upload" — setInputFiles proves
  // only that the file was selected into the browser's own form.
  assert.equal(zoho.resumeUploadState, ZohoDocumentUploadState.ATTACHED);
  assert.ok(zoho.filled.some((f) => f.category === ZohoFieldCategory.RESUME_UPLOAD));
  assert.ok(!zoho.requiredGaps.some((g) => g.category === ZohoFieldCategory.RESUME_UPLOAD));
  fs.rmSync(root, { recursive: true, force: true });
});

test('résumé upload is skipped, honestly, when no reviewed artifact is available', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-resume-none-'));
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file">' })}
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] }); // no `pkg` at all
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.equal(zoho.resumeUploadState, ZohoDocumentUploadState.NO_ARTIFACT);
  assert.ok(zoho.resumeUploadReason);
  assert.ok(zoho.requiredGaps.some((g) => g.category === ZohoFieldCategory.RESUME_UPLOAD));
  fs.rmSync(root, { recursive: true, force: true });
});

test('résumé upload is refused when the field does not accept PDFs', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-resume-wrong-type-'));
  const file = path.join(root, 'cv.pdf');
  fs.writeFileSync(file, 'exact-cv-bytes');
  const digest = hash('exact-cv-bytes');
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file" accept="image/png,image/jpeg">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    pkg: { cv: { path: file, versionId: 'cv-v1', sha256: digest, jobId: 'job-1', applicationId: 'app-1' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.equal(zoho.resumeUploadState, ZohoDocumentUploadState.REJECTED);
  assert.match(zoho.resumeUploadReason, /PDF/);
  fs.rmSync(root, { recursive: true, force: true });
});

test('résumé upload is refused when the file no longer matches its reviewed checksum', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-resume-checksum-'));
  const file = path.join(root, 'cv.pdf');
  fs.writeFileSync(file, 'tampered-bytes');
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    pkg: { cv: { path: file, versionId: 'cv-v1', sha256: hash('original-bytes'), jobId: 'job-1', applicationId: 'app-1' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.equal(zoho.resumeUploadState, ZohoDocumentUploadState.REJECTED);
  assert.match(zoho.resumeUploadReason, /checksum/);
  fs.rmSync(root, { recursive: true, force: true });
});

test('résumé upload hard-stops on a cross-application artifact correlation mismatch', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-resume-wrong-app-'));
  const file = path.join(root, 'cv.pdf');
  fs.writeFileSync(file, 'exact-cv-bytes');
  const digest = hash('exact-cv-bytes');
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    // jobId/applicationId deliberately do not match the plan's own correlation.
    pkg: { cv: { path: file, versionId: 'cv-v1', sha256: digest, jobId: 'job-999', applicationId: 'app-999' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ZOHO_RECRUIT_RESUME_CORRELATION_MISMATCH');
  fs.rmSync(root, { recursive: true, force: true });
});

// ── Cover letter: a real field is handled exactly like résumé; its genuine
// absence on both sampled tenants is reported honestly, not as a gap ───────

test('a cover letter is uploaded only once its artifact correlation, type and checksum all verify', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-cover-letter-upload-'));
  const file = path.join(root, 'cover-letter.txt');
  fs.writeFileSync(file, 'exact-cover-letter-bytes');
  const digest = hash('exact-cover-letter-bytes');
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file" accept="application/pdf,.pdf">' })}
    ${FIELD('Cover Letter', { input: '<input type="file" accept="text/plain,.txt,.pdf,.doc,.docx">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    pkg: { coverLetter: { path: file, versionId: 'cl-v1', sha256: digest, jobId: 'job-1', applicationId: 'app-1' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  const zoho = result.zohoRecruitResult;
  // ATTACHED, never claimed as a confirmed "upload" — same honesty as the résumé.
  assert.equal(zoho.coverLetterUploadState, ZohoDocumentUploadState.ATTACHED);
  assert.equal(zoho.coverLetterUploadReason, null);
  assert.ok(zoho.filled.some((f) => f.category === ZohoFieldCategory.COVER_LETTER_UPLOAD));
  // Résumé was correctly reported separately — not found at all on this
  // fixture since no `cv` artifact was supplied, and the field itself
  // still renders, so it is a genuine (honest) required gap.
  assert.ok(zoho.requiredGaps.some((g) => g.category === ZohoFieldCategory.RESUME_UPLOAD));
  fs.rmSync(root, { recursive: true, force: true });
});

test('a form with no cover-letter field reports that honestly — never a fabricated gap', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-no-cover-letter-field-'));
  const file = path.join(root, 'cover-letter.txt');
  fs.writeFileSync(file, 'exact-cover-letter-bytes');
  const digest = hash('exact-cover-letter-bytes');
  // Matches both tenants actually sampled: exactly one file field, Resume only.
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    pkg: { coverLetter: { path: file, versionId: 'cl-v1', sha256: digest, jobId: 'job-1', applicationId: 'app-1' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.equal(zoho.coverLetterUploadState, ZohoDocumentUploadState.NO_FIELD, 'no cover-letter field exists on this form at all');
  assert.equal(zoho.coverLetterUploadReason, null, 'absence of the field is informational, never reported as a gap reason');
  assert.ok(!zoho.requiredGaps.some((g) => g.category === ZohoFieldCategory.COVER_LETTER_UPLOAD),
    'a field that does not exist must never appear as a required gap');
  fs.rmSync(root, { recursive: true, force: true });
});

test('cover letter upload is skipped, honestly, when no approved cover letter is available', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-cover-letter-none-'));
  // Marked required here specifically to exercise the required-gap-reporting
  // path too — no real sampled tenant has ever shown this field at all, so
  // whether a hypothetical tenant marks it required is itself unverified;
  // the "both upload" test below exercises the optional-field case.
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Cover Letter *', { input: '<input type="file" required>' })}
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] }); // no `pkg` at all
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.equal(zoho.coverLetterUploadState, ZohoDocumentUploadState.NO_ARTIFACT);
  assert.ok(zoho.coverLetterUploadReason);
  assert.ok(zoho.requiredGaps.some((g) => g.category === ZohoFieldCategory.COVER_LETTER_UPLOAD));
  fs.rmSync(root, { recursive: true, force: true });
});

test('cover letter upload is refused when the field does not accept a compatible file type', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-cover-letter-wrong-type-'));
  const file = path.join(root, 'cover-letter.txt');
  fs.writeFileSync(file, 'exact-cover-letter-bytes');
  const digest = hash('exact-cover-letter-bytes');
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Cover Letter', { input: '<input type="file" accept="image/png,image/jpeg">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    pkg: { coverLetter: { path: file, versionId: 'cl-v1', sha256: digest, jobId: 'job-1', applicationId: 'app-1' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.equal(zoho.coverLetterUploadState, ZohoDocumentUploadState.REJECTED);
  assert.match(zoho.coverLetterUploadReason, /file type/);
  fs.rmSync(root, { recursive: true, force: true });
});

test('cover letter upload hard-stops on a cross-application artifact correlation mismatch', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-cover-letter-wrong-app-'));
  const file = path.join(root, 'cover-letter.txt');
  fs.writeFileSync(file, 'exact-cover-letter-bytes');
  const digest = hash('exact-cover-letter-bytes');
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Cover Letter', { input: '<input type="file">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    pkg: { coverLetter: { path: file, versionId: 'cl-v1', sha256: digest, jobId: 'job-999', applicationId: 'app-999' } },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ZOHO_RECRUIT_COVER_LETTER_CORRELATION_MISMATCH');
  fs.rmSync(root, { recursive: true, force: true });
});

test('both résumé and cover letter upload independently in the same run', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-both-uploads-'));
  const cvFile = path.join(root, 'cv.pdf');
  fs.writeFileSync(cvFile, 'exact-cv-bytes');
  const clFile = path.join(root, 'cover-letter.txt');
  fs.writeFileSync(clFile, 'exact-cover-letter-bytes');
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Resume *', { input: '<input type="file" accept="application/pdf,.pdf">' })}
    ${FIELD('Cover Letter', { input: '<input type="file" accept="text/plain,.txt">' })}
  </body></html>`;
  const plan = zohoPlan(root, {
    extraSteps: [applyStep({ firstName: 'Ada' })],
    pkg: {
      cv: { path: cvFile, versionId: 'cv-v1', sha256: hash('exact-cv-bytes'), jobId: 'job-1', applicationId: 'app-1' },
      coverLetter: { path: clFile, versionId: 'cl-v1', sha256: hash('exact-cover-letter-bytes'), jobId: 'job-1', applicationId: 'app-1' },
    },
  });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.equal(zoho.resumeUploadState, ZohoDocumentUploadState.ATTACHED);
  assert.equal(zoho.coverLetterUploadState, ZohoDocumentUploadState.ATTACHED);
  assert.equal(zoho.requiredGaps.length, 0);
  fs.rmSync(root, { recursive: true, force: true });
});

test('a missing identity value is never guessed — the field is reported as a gap instead', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-missing-identity-'));
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Email *', { input: '<input type="text">' })}
  </body></html>`;
  // No lastName/email supplied at all.
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.deepEqual(zoho.filled.map((f) => f.category), [ZohoFieldCategory.IDENTITY_FIRST_NAME]);
  assert.ok(zoho.requiredGaps.some((g) => g.label === 'Email *'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('an unsupported custom question (a non-native dropdown) is reported, never guessed or filled', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-custom-question-'));
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Current Job Title *', { input: '<input type="text" readonly>' })}
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.ok(zoho.requiredGaps.some((g) => g.label === 'Current Job Title *' && g.category === ZohoFieldCategory.REQUIRES_HUMAN));
  fs.rmSync(root, { recursive: true, force: true });
});

test('a required consent checkbox is reported but never auto-checked', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-consent-'));
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Declaration of Accuracy *', { input: '<input type="checkbox">' })}
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  const zoho = result.zohoRecruitResult;
  assert.equal(result.status, 'COMPLETED', 'an unresolved consent checkbox is a reported gap, not a run failure');
  assert.ok(zoho.requiredGaps.some((g) => g.label === 'Declaration of Accuracy *'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('a CAPTCHA already visible on first load is a hard stop at the page-safety layer — nothing is filled', async () => {
  // The generic page-safety check every NAVIGATE already runs
  // (#assertSafePage, shared with every other plan type) sees the word
  // "CAPTCHA" in the body text and hard-stops before ZOHO_RECRUIT_APPLY ever
  // starts — a real instance of the two independent safety layers this
  // adapter relies on (see #runZohoRecruitApply's own javadoc): either one
  // alone is enough to guarantee nothing gets filled.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-captcha-on-load-'));
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Email *', { input: '<input type="text">' })}
    ${FIELD('CAPTCHA *', { input: '<input type="text">' })}
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada', email: 'ada@example.test' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'BLOCKED_ANTI_BOT');
  assert.equal(instance.lastZohoRecruitResult, null, 'a captcha hard stop must never report a (partial) fill result');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a CAPTCHA that only appears once the form is revealed is still a hard stop — nothing is filled', async () => {
  // Matches what was actually observed live: the job-detail landing page
  // carries no "captcha" text at all; the CAPTCHA only appears inside the
  // form the "I'm interested" click reveals, below the identity fields and
  // directly above Submit. #assertSafePage never sees this page (it only
  // runs on NAVIGATE/CLICK, and this adapter never uses a CLICK step — the
  // click happens inside #runZohoRecruitApply itself), so this specifically
  // exercises that method's OWN CAPTCHA check, not the generic safety net.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-captcha-revealed-'));
  const revealedForm = `${FIELD('First Name *', { input: '<input type="text">' })}`
    + `${FIELD('Email *', { input: '<input type="text">' })}`
    + `${FIELD('CAPTCHA *', { input: '<input type="text">' })}`;
  const html = `<!doctype html><html><body>
    <button class="lyte-button lyteBackgroundColorBtn lyteSuccess">I'm interested</button>
    <script>
      document.querySelector('.lyteSuccess').addEventListener('click', function () {
        document.body.innerHTML = ${JSON.stringify(revealedForm)};
      });
    </script>
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada', email: 'ada@example.test' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ZOHO_RECRUIT_CAPTCHA_PRESENT');
  assert.equal(instance.lastZohoRecruitResult, null, 'a captcha hard stop must never report a (partial) fill result');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a login wall revealed by the apply click is a hard stop, not a retryable uncertain result', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-login-wall-'));
  const html = `<!doctype html><html><body>
    <button class="lyte-button lyteBackgroundColorBtn lyteSuccess">I'm interested</button>
    <script>
      document.querySelector('.lyteSuccess').addEventListener('click', function () {
        document.body.innerHTML = '<main>Please sign in to continue</main>';
      });
    </script>
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'ZOHO_RECRUIT_LOGIN_REQUIRED');
  fs.rmSync(root, { recursive: true, force: true });
});

test('an ambiguous page with neither a form nor a recognisable apply trigger fails honestly, never a fabricated success', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-ambiguous-'));
  const html = `<!doctype html><html><body><main>This posting is no longer accepting applications.</main></body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error.message === 'ZOHO_RECRUIT_APPLY_TRIGGER_NOT_FOUND');
  fs.rmSync(root, { recursive: true, force: true });
});

test('clicking the "I\'m interested" trigger reveals the form, which is then filled normally', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-click-reveals-form-'));
  const revealedForm = `${FIELD('First Name *', { input: '<input type="text">' })}${FIELD('Email *', { input: '<input type="text">' })}`;
  // Two identical "I'm interested" buttons, exactly like the live pages
  // sampled (a page-top and a page-bottom copy of the same CTA) — proves the
  // worker's `.first()` locator handles the real duplicate without a
  // Playwright strict-mode violation.
  const html = `<!doctype html><html><body>
    <button class="lyte-button lyteBackgroundColorBtn lyteSuccess">I'm interested</button>
    <button class="lyte-button lyteBackgroundColorBtn lyteSuccess">I'm interested</button>
    <script>
      document.querySelectorAll('.lyteSuccess').forEach(function (btn) {
        btn.addEventListener('click', function () {
          document.body.innerHTML = ${JSON.stringify(revealedForm)};
        });
      });
    </script>
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada', email: 'ada@example.test' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  const result = await instance.execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.deepEqual(result.zohoRecruitResult.filled.map((f) => f.category).sort(), [
    ZohoFieldCategory.IDENTITY_EMAIL, ZohoFieldCategory.IDENTITY_FIRST_NAME,
  ].sort());
  fs.rmSync(root, { recursive: true, force: true });
});

test('a click whose form never appears times out as a genuine failure, never a fabricated success', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-click-no-form-'));
  const html = `<!doctype html><html><body>
    <button class="lyte-button lyteBackgroundColorBtn lyteSuccess" onclick="void 0">I'm interested</button>
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] });
  const instance = routedWorker(root, (route) => route.fulfill({ contentType: 'text/html', body: html }));
  await assert.rejects(() => instance.execute(plan), (error) => error.message === 'ZOHO_RECRUIT_FORM_NOT_FOUND');
  fs.rmSync(root, { recursive: true, force: true });
});

test('a redirect away from the expected Zoho Recruit host is rejected, not silently followed', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-identity-mismatch-'));
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] });
  const instance = new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => {
      await page.route(`${URL}**`, (route) => route.fulfill({
        contentType: 'text/html', body: '<!doctype html><html><body>ok</body></html>',
      }));
      await page.route('https://evil.example/**', (route) => route.fulfill({
        contentType: 'text/html', body: '<!doctype html><html><body>ok</body></html>',
      }));
    },
  });
  // Rewriting the navigation step's own target after plan construction would
  // be caught by #validateZohoRecruitPlan's own target/navigation-identity
  // check before any browser work happens at all.
  plan.steps[0].params.url = 'https://evil.example/jobs/Careers/1/x';
  await assert.rejects(() => instance.execute(plan), (error) => error instanceof HardStopError
    && error.reason === 'INVALID_ZOHO_RECRUIT_TARGET');
  fs.rmSync(root, { recursive: true, force: true });
});

// ── Recovery: a crashed worker must resume, never re-click or lose outcomes ─

test('a worker that crashes after navigating resumes without re-navigating and completes normally', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-crash-resume-'));
  const html = `<!doctype html><html><body>
    ${FIELD('First Name *', { input: '<input type="text">' })}
    ${FIELD('Email *', { input: '<input type="text">' })}
  </body></html>`;
  const plan = zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada', email: 'ada@example.test' })] });
  const options = () => ({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => { await page.route(`${URL}**`, (route) => route.fulfill({ contentType: 'text/html', body: html })); },
  });

  // Simulated crash right after the navigate step completes, before the
  // worker does anything else — the exact scenario a container restart or a
  // lost lease produces.
  await assert.rejects(() => new BrowserWorker(options()).execute(plan, { failAfterStep: 'navigate-application' }),
    (error) => String(error.message).startsWith('SIMULATED_WORKER_CRASH'));
  const stateAfterCrash = JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8')).plans[plan.planId];
  assert.equal(stateAfterCrash.steps['navigate-application'].status, 'COMPLETED');
  assert.equal(stateAfterCrash.status, 'RUNNING', 'a simulated crash must leave the plan resumable, not FAILED');

  // A fresh BrowserWorker instance (same state file — the real restart
  // scenario) resumes and completes without re-running navigate/screenshot.
  const result = await new BrowserWorker(options()).execute(plan);
  assert.equal(result.status, 'COMPLETED');
  assert.deepEqual(result.zohoRecruitResult.filled.map((f) => f.category).sort(), [
    ZohoFieldCategory.IDENTITY_EMAIL, ZohoFieldCategory.IDENTITY_FIRST_NAME,
  ].sort());
  fs.rmSync(root, { recursive: true, force: true });
});

test('a plan already marked COMPLETED is never re-entered — a replayed claim is a true no-op', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zoho-replay-noop-'));
  const html = `<!doctype html><html><body>${FIELD('First Name *', { input: '<input type="text">' })}</body></html>`;
  let navigations = 0;
  const instance = new BrowserWorker({
    statePath: path.join(root, 'state.json'), artifactDir: root,
    onPageCreated: async (page) => {
      await page.route(`${URL}**`, (route) => { navigations += 1; return route.fulfill({ contentType: 'text/html', body: html }); });
    },
  });

  const first = await instance.execute(zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] }));
  assert.equal(first.status, 'COMPLETED');
  const navigationsAfterFirstRun = navigations;

  // A genuinely independent plan object with identical content — exactly
  // what an outbox/claim-next replay reconstructs from a database row, never
  // the same mutated in-memory object — is recognised by its matching
  // fingerprint as already complete and recovered without touching the
  // browser again.
  const second = await instance.execute(zohoPlan(root, { extraSteps: [applyStep({ firstName: 'Ada' })] }));
  assert.equal(second.status, 'COMPLETED');
  assert.equal(second.recovered, true);
  assert.equal(navigations, navigationsAfterFirstRun, 'a replay must never re-navigate or re-fill an already-completed plan');
});
