import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {
  EVIDENCE_SCHEMA_VERSION,
  extractGenuineConfirmationId,
  sanitizeContainerHtml,
  sanitizeEvidenceText,
  buildGreenhouseSubmissionEvidence,
} from '../src/greenhouse_evidence.js';
import { InvalidReceiptError, verifyGenuineReceiptInvariants } from '../src/receipt.js';

const PLAN = {
  planId: 'plan-gh-42',
  targetUrl: 'https://boards.greenhouse.io/robin-sandbox/jobs/8200681',
  correlation: {
    jobId: 'job-uuid-8200',
    applicationId: 'app-uuid-9100',
  },
};

test('extracts confirmation ID from response JSON or confirmation container and builds verifiable canonical evidence', () => {
  const result = buildGreenhouseSubmissionEvidence({
    plan: PLAN,
    classification: { category: 'CONFIRMED', confirmed: true, evidence: 'Submission confirmed cleanly' },
    networkCapture: {
      confirmed: true,
      method: 'POST',
      url: 'https://boards.greenhouse.io/robin-sandbox/jobs/8200681?token=secret_tok',
      status: 200,
      responseBodyText: JSON.stringify({ ok: true, confirmation_id: 'GH-SBX-749201' }),
      observedAt: '2026-11-25T00:05:00.000Z',
    },
    confirmationUrlMatched: true,
    confirmationContainerPresent: true,
    pageUrl: 'https://boards.greenhouse.io/robin-sandbox/jobs/8200681/confirmation?session=sess_xyz',
    domCapture: {
      confirmationIdAttr: 'GH-SBX-749201',
      containerHtml: '<div class="confirmation" onclick="evil()">Thank you! Confirmation ID: GH-SBX-749201<script>alert(1)</script><input value="secret"></div>',
      containerText: 'Thank you! Confirmation ID: GH-SBX-749201 Authorization: Bearer super_secret_jwt_123',
    },
  });

  assert.equal(result.confirmed, true);
  assert.equal(result.confirmationNumber, 'GH-SBX-749201');
  assert.deepEqual(result.signals, [
    'GREENHOUSE_SUBMISSION_RESPONSE_2XX',
    'GREENHOUSE_CONFIRMATION_URL_MATCHED',
    'GREENHOUSE_CONFIRMATION_CONTAINER_PRESENT',
    'EMPLOYER_CONFIRMATION_ID_OBSERVED',
  ]);
  assert.doesNotMatch(result.confirmationPageUrl, /sess_xyz/);
  assert.match(result.confirmationPageUrl, /session=%5BREDACTED%5D|session=\[REDACTED\]/);

  const raw = JSON.parse(result.rawEvidence);
  assert.equal(raw.schemaVersion, EVIDENCE_SCHEMA_VERSION);
  assert.equal(raw.confirmationNumber, 'GH-SBX-749201');
  assert.doesNotMatch(raw.request.url, /secret_tok/);
  assert.doesNotMatch(raw.confirmationContainerHtml, /<script|onclick|<input/);
  assert.doesNotMatch(raw.confirmationText, /super_secret_jwt_123/);

  const sha256 = crypto.createHash('sha256').update(result.rawEvidence, 'utf8').digest('hex');
  assert.equal(
    verifyGenuineReceiptInvariants({
      employerConfirmationId: result.confirmationNumber,
      receiptSha256: sha256,
      rawEvidence: result.rawEvidence,
      atsPlatform: 'GREENHOUSE',
      submittedAt: result.observedAt,
      profileId: 'prof-1',
      applicationId: PLAN.correlation.applicationId,
      attestationConfirmed: true,
      observedSignals: result.signals,
      evidenceProvenance: 'AUTHENTICATED_BROWSER_WORKER',
      sandboxPosting: true,
      candidateAuthorizedSandbox: true,
    }),
    true,
  );
});

test('never confuses requisition, job, URL posting, or application IDs for employer confirmation ID', () => {
  const ctx = {
    jobId: 'job-uuid-8200',
    applicationId: 'app-uuid-9100',
    targetUrl: PLAN.targetUrl,
  };

  assert.equal(extractGenuineConfirmationId({ ...ctx, containerText: 'Requisition ID: REQ-4421' }), undefined);
  assert.equal(extractGenuineConfirmationId({ ...ctx, containerText: 'Confirmation ID: REQ-99812' }), undefined);
  assert.equal(extractGenuineConfirmationId({ ...ctx, containerDataId: 'job-uuid-8200' }), undefined);
  assert.equal(extractGenuineConfirmationId({ ...ctx, containerDataId: 'app-uuid-9100' }), undefined);
  assert.equal(extractGenuineConfirmationId({ ...ctx, containerText: 'Confirmation ID: 8200681' }), undefined);
  assert.equal(
    extractGenuineConfirmationId({ ...ctx, responseBodyText: JSON.stringify({ id: '999', job_id: '8200681', confirmation_id: 'REQUISITION-77' }) }),
    undefined,
  );
});

test('omits confirmationNumber and EMPLOYER_CONFIRMATION_ID_OBSERVED when no genuine ID exists', () => {
  const result = buildGreenhouseSubmissionEvidence({
    plan: PLAN,
    classification: { category: 'CONFIRMED', confirmed: true, evidence: 'Structural match' },
    networkCapture: { confirmed: true, method: 'POST', url: PLAN.targetUrl, status: 200, observedAt: '2026-11-25T00:05:00.000Z' },
    confirmationUrlMatched: true,
    confirmationContainerPresent: true,
    pageUrl: `${PLAN.targetUrl}/confirmation`,
    domCapture: {
      containerHtml: '<div class="confirmation">Application received! Thank you for applying.</div>',
      containerText: 'Application received! Thank you for applying.',
    },
  });

  assert.equal('confirmationNumber' in result, false);
  assert.equal(result.signals.includes('EMPLOYER_CONFIRMATION_ID_OBSERVED'), false);
  assert.equal(JSON.parse(result.rawEvidence).confirmationNumber, null);
});

test('sanitization removes tokens, cookies, headers, JSON secrets, dangerous tags, and oversized payloads cleanly', () => {
  assert.equal(sanitizeEvidenceText('ok Bearer eyJhbGciOi.payload.sig token=abc cookie=xyz'), 'ok Bearer [REDACTED] token=[REDACTED] cookie=[REDACTED]');
  const headerAndJson = sanitizeEvidenceText(
    'Cookie: sid=topsecret_cookie Authorization: Basic dXNlcjpwYXNz x-api-key: key_123 access_token=tok_a csrf_token=tok_b csrftoken=tok_c session_id=sid_d {"secret":"hidden_val","api_key":"ak_99","access_token":"at_88","session_id":"sid_77"}'
  );
  assert.doesNotMatch(headerAndJson, /topsecret_cookie|dXNlcjpwYXNz|key_123|tok_a|tok_b|tok_c|sid_d|hidden_val|ak_99|at_88|sid_77/);

  const html = '<div class="confirmation"><a href="https://boards.greenhouse.io?token=s3cr3t">Receipt</a><a href="javascript:alert(1)">bad</a><iframe src="evil"></iframe><span data-auth-token="secret123">Confirmed</span></div>';
  const cleanedHtml = sanitizeContainerHtml(html);
  assert.match(cleanedHtml, /href="https:\/\/boards\.greenhouse\.io\?token=\[REDACTED\]">Receipt<\/a>/);
  assert.doesNotMatch(cleanedHtml, /s3cr3t|secret123|<iframe|javascript:|data-auth-token/);

  const oversized = '<div class="confirmation">' + 'a'.repeat(50000) + '</div>';
  assert.ok(sanitizeContainerHtml(oversized).length <= 32768);
});

test('disqualifies mock/synthetic prefixes in extraction and refuses unconfirmed builder input or internal IDs in receipt invariants', () => {
  assert.equal(extractGenuineConfirmationId({ containerText: 'Confirmation ID: MOCK-4421', targetUrl: PLAN.targetUrl }), undefined);
  assert.equal(extractGenuineConfirmationId({ containerText: 'Confirmation ID: FIXTURE-4421', targetUrl: PLAN.targetUrl }), undefined);

  assert.throws(
    () => buildGreenhouseSubmissionEvidence({
      plan: PLAN,
      classification: { category: 'CONFIRMED', confirmed: true, evidence: 'DOM only' },
      networkCapture: { confirmed: false },
      confirmationUrlMatched: true,
      confirmationContainerPresent: true,
    }),
    /UNCONFIRMED_SUBMISSION_EVIDENCE_FORBIDDEN/,
  );

  const rawEvidence = JSON.stringify({ confirmationContainerHtml: '<div class="confirmation">GH</div>' });
  const sha = crypto.createHash('sha256').update(rawEvidence, 'utf8').digest('hex');
  for (const internalId of ['app-uuid-9100', 'job-uuid-8200', 'plan-gh-42', '8200681']) {
    assert.throws(
      () => verifyGenuineReceiptInvariants({
        employerConfirmationId: internalId,
        receiptSha256: sha,
        rawEvidence,
        atsPlatform: 'GREENHOUSE',
        submittedAt: '2026-11-25T00:05:00.000Z',
        profileId: 'prof-1',
        applicationId: 'app-uuid-9100',
        jobId: 'job-uuid-8200',
        planId: 'plan-gh-42',
        targetUrl: PLAN.targetUrl,
        attestationConfirmed: true,
        observedSignals: ['GREENHOUSE_SUBMISSION_RESPONSE_2XX'],
        evidenceProvenance: 'AUTHENTICATED_BROWSER_WORKER',
        sandboxPosting: true,
        candidateAuthorizedSandbox: true,
      }),
      (err) => err instanceof InvalidReceiptError && err.reasonCode === 'INTERNAL_ID_AS_CONFIRMATION_REJECTED',
    );
  }
});
