/**
 * Stage 3G — evidence recording with the four categories kept strictly apart.
 *
 * Every scenario result is recorded per category; a local or Postgres pass can
 * never be filed as an external one. Records are redacted on the way in:
 * URLs keep only origin + path, and any field that looks like a credential,
 * cookie, OTP, token or document content is dropped.
 */
import crypto from 'node:crypto';
import { sanitizeUrlForDiagnostic } from '../../worker/src/universal_form.js';

export const EvidenceCategory = Object.freeze({
  LOCAL_FIXTURE: 'LOCAL_FIXTURE',
  POSTGRES_TESTCONTAINERS: 'POSTGRES_TESTCONTAINERS',
  EXTERNAL_SYNTHETIC_STAGING: 'EXTERNAL_SYNTHETIC_STAGING',
  REAL_EMPLOYER: 'REAL_EMPLOYER',
});

export const Status = Object.freeze({ PASS: 'PASS', FAIL: 'FAIL', BLOCKED: 'BLOCKED', NOT_RUN: 'NOT_RUN' });

const SENSITIVE_KEY = /pass(word)?|secret|token|cookie|authori[sz]ation|otp|mfa.?code|session|credential|cv.?content|cover.?letter.?body|ciphertext|raw.?evidence/i;

export function newRunId(now = new Date()) {
  return `3g-${now.toISOString().slice(0, 10).replace(/-/g, '')}-${crypto.randomBytes(4).toString('hex')}`;
}

export function redact(value, depth = 0) {
  if (depth > 6) return '[depth]';
  if (typeof value === 'string') {
    if (/^https?:\/\//i.test(value)) return sanitizeUrlForDiagnostic(value);
    return value.length > 500 ? `${value.slice(0, 500)}…` : value;
  }
  if (Array.isArray(value)) return value.slice(0, 50).map((v) => redact(v, depth + 1));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = SENSITIVE_KEY.test(k) ? '[redacted]' : redact(v, depth + 1);
    }
    return out;
  }
  return value;
}

export class EvidenceLedger {
  constructor({ runId = newRunId(), commitSha, environmentHost = null } = {}) {
    if (!/^[0-9a-f]{40}$/.test(String(commitSha))) throw new Error('EVIDENCE_REQUIRES_EXACT_COMMIT_SHA');
    this.runId = runId;
    this.commitSha = commitSha;
    this.environmentHost = environmentHost;
    this.records = [];
  }

  record({ scenario, category, status, preconditions, execution, expected, actual, eventIds = [], cleanup, notes }) {
    if (!Object.values(EvidenceCategory).includes(category)) throw new Error('UNKNOWN_EVIDENCE_CATEGORY');
    if (category === EvidenceCategory.REAL_EMPLOYER) throw new Error('REAL_EMPLOYER_EVIDENCE_IS_NOT_PERMITTED_IN_STAGE_3G');
    if (!Object.values(Status).includes(status)) throw new Error('UNKNOWN_STATUS');
    if (!Number.isInteger(scenario) || scenario < 1 || scenario > 15) throw new Error('UNKNOWN_SCENARIO');
    if (category === EvidenceCategory.EXTERNAL_SYNTHETIC_STAGING && status === Status.PASS && cleanup !== 'VERIFIED') {
      throw new Error('EXTERNAL_PASS_REQUIRES_VERIFIED_CLEANUP');
    }
    const entry = redact({
      runId: this.runId, commitSha: this.commitSha, scenario, category, status,
      environmentHost: category === EvidenceCategory.EXTERNAL_SYNTHETIC_STAGING ? this.environmentHost : null,
      preconditions, execution, expected, actual, eventIds, cleanup: cleanup ?? 'NOT_APPLICABLE', notes,
      recordedAt: new Date().toISOString(),
    });
    this.records.push(entry);
    return entry;
  }

  /** Per-category tallies; categories are never merged. */
  summary() {
    const out = {};
    for (const c of Object.values(EvidenceCategory)) {
      const rs = this.records.filter((r) => r.category === c);
      out[c] = Object.fromEntries(Object.values(Status).map((s) => [s, rs.filter((r) => r.status === s).length]));
    }
    return out;
  }
}
