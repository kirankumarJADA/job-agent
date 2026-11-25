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
import { verifyGenuineReceiptInvariants } from '../src/receipt.js';

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

test('sanitization removes tokens, cookies, and oversized payloads cleanly', () => {
  assert.equal(sanitizeEvidenceText('ok Bearer eyJhbGciOi.payload.sig token=abc cookie=xyz'), 'ok Bearer [REDACTED] token=[REDACTED] cookie=[REDACTED]');
  const oversized = '<div class="confirmation">' + 'a'.repeat(50000) + '</div>';
  assert.ok(sanitizeContainerHtml(oversized).length <= 32768);
});
