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

export const MOCK_PREFIXES = Object.freeze(['MOCK-', 'TEST-', 'SAMPLE-']);

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
 * Prefixes MOCK-*, TEST-*, SAMPLE-* are rejected case-insensitively.
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
 * Rejects mock/synthetic prefixes in confirmation ID.
 *
 * @param {object} params
 * @returns {object}
 */
export function normalizeConfirmationEvidence({
  employerConfirmationId,
  confirmationNumber,
  requisitionId,
  receiptId,
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
} = {}) {
  const confId = employerConfirmationId || confirmationNumber || requisitionId || receiptId || '';
  const trimmedConfId = String(confId).trim();

  if (isMockOrSynthetic(trimmedConfId)) {
    throw new InvalidReceiptError(
      `Mock or synthetic confirmation ID prefix is rejected: ${trimmedConfId}`,
      'MOCK_PREFIX_REJECTED'
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
    employerConfirmationId: trimmedConfId,
    observedSignals: signals,
    profileId: profileId ? String(profileId).trim() : '',
    rawEvidence: rawEvidence ? canonicalJson(rawEvidence) : null,
    submittedAt: isoSubmittedAt,
    summary: summary ? String(summary).trim() : '',
  };
}

/**
 * Computes deterministic SHA-256 confirmation evidence digest.
 *
 * @param {object} evidence
 * @returns {string} 64-character lowercase hexadecimal hash
 */
export function hashConfirmationEvidence(evidence) {
  const normalized = normalizeConfirmationEvidence(evidence);
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
  const receiptSha256 = hashConfirmationEvidence(normalized);

  if (isMockOrSynthetic(receiptSha256)) {
    throw new InvalidReceiptError(
      `Mock prefix in receipt SHA256 is rejected: ${receiptSha256}`,
      'MOCK_PREFIX_REJECTED'
    );
  }

  return {
    ...normalized,
    receiptSha256,
  };
}

/**
 * Validates all 7 genuine receipt invariants.
 *
 * @param {object} receipt
 * @returns {boolean} true if all invariants pass
 * @throws {InvalidReceiptError} if any invariant fails
 */
export function verifyGenuineReceiptInvariants(receipt = {}) {
  const errors = [];

  // 1. Employer confirmation ID
  const confId = receipt.employerConfirmationId;
  if (!confId || typeof confId !== 'string' || confId.trim().length === 0) {
    errors.push('Missing employer confirmation ID');
  } else if (isMockOrSynthetic(confId)) {
    throw new InvalidReceiptError(
      `Mock prefix in employer confirmation ID rejected: ${confId}`,
      'MOCK_CONFIRMATION_ID_REJECTED'
    );
  }

  // 2. receiptSha256
  const sha = receipt.receiptSha256;
  if (!sha || typeof sha !== 'string' || !/^[0-9a-fA-F]{64}$/.test(sha.trim())) {
    errors.push('Missing or invalid 64-character hex receiptSha256');
  } else if (isMockOrSynthetic(sha)) {
    throw new InvalidReceiptError(
      `Mock prefix in receiptSha256 rejected: ${sha}`,
      'MOCK_SHA256_REJECTED'
    );
  }

  // 3. atsPlatform
  const platform = receipt.atsPlatform ? String(receipt.atsPlatform).trim().toUpperCase() : '';
  if (!platform || !GENUINE_ATS_PLATFORMS.includes(platform)) {
    errors.push(`Invalid or unsupported ATS platform: ${receipt.atsPlatform}`);
  }

  // 4. submittedAt
  if (!receipt.submittedAt) {
    errors.push('Missing submittedAt timestamp');
  } else {
    const time = new Date(receipt.submittedAt).getTime();
    if (Number.isNaN(time)) {
      errors.push('Invalid submittedAt timestamp format');
    } else if (time > Date.now() + 86400000) {
      errors.push('submittedAt cannot be in the future');
    }
  }

  // 5. profileId
  if (!receipt.profileId || String(receipt.profileId).trim().length === 0) {
    errors.push('Missing profileId');
  }

  // 6. applicationId
  if (!receipt.applicationId || String(receipt.applicationId).trim().length === 0) {
    errors.push('Missing applicationId');
  }

  // 7. attestationConfirmed
  if (receipt.attestationConfirmed !== true) {
    errors.push('Candidate attestation must be explicitly confirmed');
  }

  if (errors.length > 0) {
    throw new InvalidReceiptError(
      `Genuine receipt invariant validation failed: ${errors.join('; ')}`,
      'INVARIANTS_FAILED'
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
