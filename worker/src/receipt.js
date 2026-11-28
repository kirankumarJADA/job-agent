import crypto from 'node:crypto';

/**
 * Phase 20 / Wave 3 Agent A: Authorised ATS Submission and Genuine Application Receipts.
 *
 * <p>Enforces zero synthetic or mock receipts in genuine submission paths.
 * Guarantees all 7 genuine receipt invariants:
 * <ol>
 *   <li>Employer confirmation ID (non-null, non-mock prefix)</li>
 *   <li>receiptSha256 (64 hex characters, SHA-256 over normalized confirmation evidence)</li>
 *   <li>atsPlatform (valid ATS platform: GREENHOUSE, WORKDAY, ASHBY, LEVER, ZOHO_RECRUIT)</li>
 *   <li>submittedAt (ISO-8601 timestamp, not future-dated)</li>
 *   <li>profileId (valid candidate profile identifier)</li>
 *   <li>applicationId (valid target application identifier)</li>
 *   <li>attestationConfirmed (explicitly true)</li>
 * </ol>
 */

export const MOCK_PREFIXES = Object.freeze([
  'MOCK-',
  'TEST-',
  'SAMPLE-',
  'SIM-',
  'FIXTURE-',
  'SYNTHETIC-',
  'DEMO-',
  'FAKE-',
]);
export const REQUISITION_PREFIXES = Object.freeze(['REQ-', 'REQUISITION-', 'JOB-REQ-', 'RECEIPT-']);

export const GENUINE_ATS_PLATFORMS = Object.freeze([
  'GREENHOUSE',
  'WORKDAY',
  'ASHBY',
  'LEVER',
  'ZOHO_RECRUIT',
]);

export class InvalidReceiptError extends Error {
  constructor(message, reasonCode = 'INVALID_RECEIPT') {
    super(message);
    this.name = 'InvalidReceiptError';
    this.reasonCode = reasonCode;
    this.status = 422;
  }
}

/**
 * Checks whether a given string value starts with any mock or synthetic prefix.
 * Prefixes MOCK-*, TEST-*, SAMPLE-*, SIM-* are rejected case-insensitively.
 *
 * @param {string|null|undefined} value
 * @returns {boolean}
 */
export function isMockOrSynthetic(value) {
  if (value === null || value === undefined) return false;
  const s = String(value).trim().toUpperCase();
  return MOCK_PREFIXES.some((prefix) => s.startsWith(prefix));
}

/**
 * Checks whether a given string value starts with any requisition or receipt fallback prefix.
 *
 * @param {string|null|undefined} value
 * @returns {boolean}
 */
export function isRequisitionOrReceiptFallback(value) {
  if (value === null || value === undefined) return false;
  const s = String(value).trim().toUpperCase();
  return REQUISITION_PREFIXES.some((prefix) => s.startsWith(prefix));
}

/**
 * Deterministic JSON serialization with recursively sorted object keys.
 * Ensures consistent canonical hashing across languages and runtimes.
 *
 * @param {*} value
 * @returns {string}
 */
export function canonicalJson(value) {
  if (value === null || value === undefined) {
    return 'null';
  }
  if (typeof value !== 'object') {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalJson(item)).join(',')}]`;
  }
  const keys = Object.keys(value).sort();
  const entries = keys.map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`);
  return `{${entries.join(',')}}`;
}

/**
 * Normalizes confirmation evidence into canonical receipt structure.
 * Rejects mock/synthetic prefixes in confirmation ID and requisition ID fallbacks.
 *
 * @param {object} params
 * @returns {object}
 */
export function normalizeConfirmationEvidence({
  employerConfirmationId,
  confirmationNumber,
  atsPlatform,
  planType,
  atsKind,
  submittedAt,
  profileId,
  applicationId,
  attestationConfirmed,
  observedSignals = [],
  summary = '',
  rawEvidence = null,
  sandboxPosting = false,
  candidateAuthorizedSandbox = false,
} = {}) {
  // Genuine employer confirmation identifier only: requisitionId and receiptId fallbacks are rejected
  const confId = employerConfirmationId || confirmationNumber || '';
  const trimmedConfId = String(confId).trim();

  if (isMockOrSynthetic(trimmedConfId)) {
    throw new InvalidReceiptError(
      `Mock or synthetic confirmation ID prefix is rejected: ${trimmedConfId}`,
      'MOCK_CONFIRMATION_ID_REJECTED'
    );
  }

  if (isRequisitionOrReceiptFallback(trimmedConfId)) {
    throw new InvalidReceiptError(
      `Requisition ID or receipt ID fallback cannot be substituted for employer confirmation ID: ${trimmedConfId}`,
      'REQUISITION_ID_AS_CONFIRMATION_REJECTED'
    );
  }

  const rawPlatform = atsPlatform || planType || atsKind || '';
  const platform = String(rawPlatform).trim().toUpperCase();

  if (isMockOrSynthetic(platform)) {
    throw new InvalidReceiptError(
      `Mock or synthetic ATS platform is rejected: ${platform}`,
      'MOCK_PLATFORM_REJECTED'
    );
  }

  let isoSubmittedAt = '';
  if (submittedAt) {
    const d = new Date(submittedAt);
    if (!Number.isNaN(d.getTime())) {
      isoSubmittedAt = d.toISOString();
    } else {
      isoSubmittedAt = String(submittedAt).trim();
    }
  }

  const signals = Array.isArray(observedSignals)
    ? [...observedSignals]
        .map((s) => String(s).trim())
        .filter(Boolean)
        .sort()
    : [];

  return {
    applicationId: applicationId ? String(applicationId).trim() : '',
    atsPlatform: platform,
    attestationConfirmed: attestationConfirmed === true,
    candidateAuthorizedSandbox: candidateAuthorizedSandbox === true,
    employerConfirmationId: trimmedConfId,
    observedSignals: signals,
    profileId: profileId ? String(profileId).trim() : '',
    rawEvidence: rawEvidence ? (typeof rawEvidence === 'string' ? rawEvidence.trim() : canonicalJson(rawEvidence)) : null,
    sandboxPosting: sandboxPosting === true,
    submittedAt: isoSubmittedAt,
    summary: summary ? String(summary).trim() : '',
  };
}

/**
 * Computes deterministic SHA-256 confirmation evidence digest directly from raw canonical evidence.
 *
 * @param {object} evidence
 * @returns {string} 64-character lowercase hexadecimal hash
 */
export function hashConfirmationEvidence(evidence) {
  const normalized = normalizeConfirmationEvidence(evidence);
  if (normalized.rawEvidence) {
    return crypto.createHash('sha256').update(normalized.rawEvidence, 'utf8').digest('hex');
  }
  const canonicalString = canonicalJson(normalized);
  return crypto.createHash('sha256').update(canonicalString, 'utf8').digest('hex');
}

/**
 * Normalizes a genuine submission receipt and computes its receiptSha256 hash.
 *
 * @param {object} data
 * @returns {object}
 */
export function normalizeReceipt(data) {
  const normalized = normalizeConfirmationEvidence(data);
  const receiptSha256 = data.receiptSha256 || hashConfirmationEvidence(normalized);

  if (isMockOrSynthetic(receiptSha256)) {
    throw new InvalidReceiptError(
      `Mock prefix in receipt SHA256 is rejected: ${receiptSha256}`,
      'MOCK_SHA256_REJECTED'
    );
  }

  return {
    ...normalized,
    receiptSha256,
  };
}

/**
 * Validates all 7 genuine receipt invariants and sandbox verification gates.
 *
 * @param {object} receipt
 * @returns {boolean} true if all invariants pass
 * @throws {InvalidReceiptError} if any invariant fails
 */
export function verifyGenuineReceiptInvariants(receipt = {}) {
  // 1. Employer confirmation ID
  const confId = receipt.employerConfirmationId;
  if (!confId || typeof confId !== 'string' || confId.trim().length === 0) {
    throw new InvalidReceiptError('Missing employer confirmation ID', 'MISSING_CONFIRMATION_ID');
  }
  if (isMockOrSynthetic(confId)) {
    throw new InvalidReceiptError(
      `Mock prefix in employer confirmation ID rejected: ${confId}`,
      'MOCK_CONFIRMATION_ID_REJECTED'
    );
  }
  if (isRequisitionOrReceiptFallback(confId)) {
    throw new InvalidReceiptError(
      `Requisition ID or receipt ID fallback cannot be substituted for employer confirmation ID: ${confId}`,
      'REQUISITION_ID_AS_CONFIRMATION_REJECTED'
    );
  }
  const trimmedConf = confId.trim().toLowerCase();
  for (const internalId of [receipt.applicationId, receipt.jobId, receipt.planId]) {
    if (internalId && trimmedConf === String(internalId).trim().toLowerCase()) {
      throw new InvalidReceiptError(
        `Internal identifier cannot be used as employer confirmation ID: ${confId}`,
        'INTERNAL_ID_AS_CONFIRMATION_REJECTED'
      );
    }
  }
  if (receipt.targetUrl) {
    try {
      const pathMatch = new URL(receipt.targetUrl).pathname.match(/\/jobs\/([^/?#]+)(?:\/|$)/);
      const postingJobId = pathMatch?.[1];
      if (postingJobId && !['apply', 'confirmation'].includes(postingJobId.toLowerCase())
          && trimmedConf === postingJobId.toLowerCase()) {
        throw new InvalidReceiptError(
          `Job posting URL identifier cannot be used as employer confirmation ID: ${confId}`,
          'INTERNAL_ID_AS_CONFIRMATION_REJECTED'
        );
      }
    } catch (err) {
      if (err instanceof InvalidReceiptError) throw err;
    }
  }

  // Raw confirmation evidence payload is required
  if (!receipt.rawEvidence || typeof receipt.rawEvidence !== 'string' || receipt.rawEvidence.trim().length === 0) {
    throw new InvalidReceiptError(
      'Confirmation evidence is required: canonical DOM evidence or screenshot bytes cannot be empty',
      'MISSING_RAW_EVIDENCE'
    );
  }
  if (receipt.rawEvidence.trim().startsWith('{')) {
    try {
      const parsedRaw = JSON.parse(receipt.rawEvidence);
      if (parsedRaw?.fixture === true || parsedRaw?.isFixture === true
          || parsedRaw?.synthetic === true || parsedRaw?.fixtureMode === true
          || /^(FIXTURE|SYNTHETIC)$/i.test(String(parsedRaw?.source ?? '').trim())) {
        throw new InvalidReceiptError(
          'Synthetic fixture evidence markers cannot be accepted as a genuine receipt',
          'FIXTURE_EVIDENCE_REJECTED'
        );
      }
    } catch (err) {
      if (err instanceof InvalidReceiptError) throw err;
    }
  }

  // 2. receiptSha256 - compute from raw evidence and compare with caller-supplied digest
  const sha = receipt.receiptSha256;
  if (!sha || typeof sha !== 'string' || !/^[0-9a-fA-F]{64}$/.test(sha.trim())) {
    throw new InvalidReceiptError(
      'Missing or invalid 64-character hex receiptSha256',
      'INVALID_SHA256_FORMAT'
    );
  }
  if (isMockOrSynthetic(sha)) {
    throw new InvalidReceiptError(
      `Mock prefix in receiptSha256 rejected: ${sha}`,
      'MOCK_SHA256_REJECTED'
    );
  }
  const computedSha = crypto.createHash('sha256').update(receipt.rawEvidence, 'utf8').digest('hex');
  if (sha.toLowerCase() !== computedSha.toLowerCase()) {
    throw new InvalidReceiptError(
      `Caller-supplied receiptSha256 does not match canonical raw evidence digest: expected ${computedSha} but was ${sha}`,
      'SHA256_DIGEST_MISMATCH'
    );
  }

  // 3. atsPlatform
  const platform = receipt.atsPlatform ? String(receipt.atsPlatform).trim().toUpperCase() : '';
  if (!platform || !GENUINE_ATS_PLATFORMS.includes(platform)) {
    throw new InvalidReceiptError(
      `Invalid or unsupported ATS platform: ${receipt.atsPlatform}`,
      'UNSUPPORTED_ATS_PLATFORM'
    );
  }

  // 4. submittedAt
  if (!receipt.submittedAt) {
    throw new InvalidReceiptError('Missing submittedAt timestamp', 'MISSING_SUBMITTED_AT');
  }
  const time = new Date(receipt.submittedAt).getTime();
  if (Number.isNaN(time)) {
    throw new InvalidReceiptError('Invalid submittedAt timestamp format', 'INVALID_SUBMITTED_AT');
  }
  if (time > Date.now() + 86400000) {
    throw new InvalidReceiptError('submittedAt cannot be in the future', 'FUTURE_SUBMITTED_AT');
  }

  // 5. profileId
  if (!receipt.profileId || String(receipt.profileId).trim().length === 0) {
    throw new InvalidReceiptError('Missing profileId', 'MISSING_PROFILE_ID');
  }

  // 6. applicationId
  if (!receipt.applicationId || String(receipt.applicationId).trim().length === 0) {
    throw new InvalidReceiptError('Missing applicationId', 'MISSING_APPLICATION_ID');
  }

  // 7. attestationConfirmed
  if (receipt.attestationConfirmed !== true) {
    throw new InvalidReceiptError(
      'Candidate attestation record is missing or not confirmed in audit log',
      'MISSING_CANDIDATE_ATTESTATION'
    );
  }

  // Sandbox Verification Phase Safeguards
  if (receipt.sandboxPosting !== true) {
    throw new InvalidReceiptError(
      'Real submission verification rejected: real submissions are disabled. Receipts are only accepted for candidate-owned sandbox postings',
      'NON_SANDBOX_RECEIPT_REJECTED'
    );
  }
  if (receipt.candidateAuthorizedSandbox !== true) {
    throw new InvalidReceiptError(
      'Unauthorized sandbox receipt rejected: candidate-owned sandbox must be explicitly verified and authorized',
      'UNAUTHORIZED_SANDBOX'
    );
  }

  return true;
}

/**
 * Factory to build and verify a genuine receipt.
 *
 * @param {object} params
 * @returns {object} verified genuine receipt
 */
export function createGenuineReceipt(params) {
  const normalized = normalizeReceipt(params);
  verifyGenuineReceiptInvariants(normalized);
  return normalized;
}
