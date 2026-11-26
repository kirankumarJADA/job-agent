/**
 * Stage 3G — the operator authorisation record and the gate built on it.
 *
 * Nothing in the external harness runs unless a record passes every check
 * below AND the current time is inside its test window. A reachable URL, a DNS
 * record or an environment variable is never treated as permission.
 *
 * The record is a JSON file kept OUTSIDE the repository (it names an operator);
 * `authorisation.example.json` documents the shape with placeholders only.
 */
import net from 'node:net';

export const MAX_WINDOW_DAYS = 14;
export const MAX_RETENTION_AFTER_WINDOW_DAYS = 7;
const DAY = 86_400_000;

const HOST_RE = /^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
const FORBIDDEN_HOST_PARTS = ['localhost', '.local', '.internal', '.lan', '.home.arpa', 'workers.dev', 'pages.dev',
  'example.com', 'example.net', 'example.org'];

function iso(v) {
  const t = Date.parse(v);
  return Number.isFinite(t) ? t : null;
}

/**
 * Validates an authorisation record. Returns { valid, reasons, record } where
 * `reasons` lists every failed check (never any secret or contact detail).
 */
export function validateAuthorisation(record, { now = Date.now() } = {}) {
  const reasons = [];
  if (!record || typeof record !== 'object') {
    return { valid: false, reasons: ['AUTHORISATION_RECORD_ABSENT'], record: null };
  }
  if (record.version !== 1) reasons.push('UNSUPPORTED_RECORD_VERSION');

  const host = String(record.hostname ?? '');
  if (host !== host.toLowerCase() || !HOST_RE.test(host) || net.isIP(host) || host.includes('*')
      || FORBIDDEN_HOST_PARTS.some((p) => host === p.replace(/^\./, '') || host.endsWith(p))) {
    reasons.push('HOSTNAME_NOT_AN_EXACT_OPERATOR_FQDN');
  }
  const prefix = String(record.pathPrefix ?? '');
  if (!/^\/[a-z0-9-]+(\/[a-z0-9-]+)*\/$/.test(prefix)) reasons.push('PATH_PREFIX_INVALID');

  if (record.nonProduction !== true) reasons.push('NON_PRODUCTION_NOT_CONFIRMED');
  if (record.syntheticDataOnly !== true) reasons.push('SYNTHETIC_DATA_ONLY_NOT_CONFIRMED');

  const start = iso(record.window?.start);
  const end = iso(record.window?.end);
  if (start === null || end === null || end <= start) reasons.push('TEST_WINDOW_INVALID');
  else {
    if (end - start > MAX_WINDOW_DAYS * DAY) reasons.push('TEST_WINDOW_TOO_LONG');
    if (now < start) reasons.push('TEST_WINDOW_NOT_STARTED');
    if (now > end) reasons.push('TEST_WINDOW_EXPIRED');
  }

  if (!record.operator?.name || !record.operator?.contact) reasons.push('TEST_OPERATOR_MISSING');
  if (!record.approval?.approvedBy || iso(record.approval?.approvedAt) === null) reasons.push('WRITTEN_APPROVAL_MISSING');
  else if (start !== null && iso(record.approval.approvedAt) > start) reasons.push('APPROVAL_AFTER_WINDOW_START');

  if (!record.syntheticCandidate?.label || record.syntheticCandidate?.realPersonalData !== false) {
    reasons.push('SYNTHETIC_CANDIDATE_NOT_DECLARED');
  }
  if (!record.syntheticDocuments?.cv || record.syntheticDocuments?.generatedFromSyntheticProfile !== true) {
    reasons.push('SYNTHETIC_DOCUMENTS_NOT_DECLARED');
  }
  if (record.testAccount) {
    if (record.testAccount.host !== host || record.testAccount.storedVia !== 'employer-credentials-api') {
      reasons.push('TEST_ACCOUNT_NOT_SCOPED_TO_HOST');
    }
    if ('password' in record.testAccount || 'secret' in record.testAccount) reasons.push('SECRET_IN_AUTHORISATION_RECORD');
  }
  if (record.mailbox && !/^[^@\s]+@[a-z0-9.-]+$/.test(String(record.mailbox.address ?? ''))) reasons.push('MAILBOX_INVALID');

  const retention = iso(record.teardown?.retentionDeadline);
  if (!record.teardown?.mechanism || retention === null) reasons.push('TEARDOWN_NOT_DEFINED');
  else if (end !== null && retention > end + MAX_RETENTION_AFTER_WINDOW_DAYS * DAY) reasons.push('RETENTION_TOO_LONG');

  const scenarios = record.scenariosAuthorised;
  if (!Array.isArray(scenarios) || scenarios.length === 0
      || scenarios.some((n) => !Number.isInteger(n) || n < 1 || n > 15) || new Set(scenarios).size !== scenarios.length) {
    reasons.push('SCENARIOS_NOT_AUTHORISED');
  }
  return { valid: reasons.length === 0, reasons, record: reasons.length === 0 ? record : null };
}

/** Scenario-level gate: submission scenarios (13–15) must be explicitly listed. */
export function scenarioPermitted(validation, scenario) {
  return Boolean(validation?.valid && validation.record.scenariosAuthorised.includes(scenario));
}
