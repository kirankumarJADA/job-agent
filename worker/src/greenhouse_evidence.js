import { canonicalJson, isMockOrSynthetic, isRequisitionOrReceiptFallback } from './receipt.js';
import { sanitizeUrl, redactSecrets } from './secret_redaction.js';
import { greenhouseConfirmationPath } from './plan.js';

export const EVIDENCE_SCHEMA_VERSION = 'robin-submission-evidence-v1';
export const MAX_CONTAINER_HTML_CHARS = 32768;
export const MAX_CONFIRMATION_TEXT_CHARS = 4000;
export const MAX_RESPONSE_SNIPPET_CHARS = 8192;

const CONFIRMATION_ID_LABELS =
  /\b(?<!requisition\s)(?<!job\s)(?<!req\s)(?:application\s+)?(?:confirmation|reference)\s*(?:id|number|no\.?|code|#)\s*(?:is|:|#|-)?\s*([A-Za-z0-9][A-Za-z0-9_-]{3,63})\b/i;

const FALLBACK_REF_LABELS =
  /(?:^|[\n\r.;,])\s*(?:confirmation\s*(?:id|number|no\.?|code|#)|reference\s*(?:id|number|no\.?|code|#))\s*(?:is|:|#|-)?\s*([A-Za-z0-9][A-Za-z0-9_-]{3,63})/i;

/**
 * Redacts credentials, tokens, cookies and bearer headers from extracted DOM or text snippets.
 * Never alters normal confirmation wording or genuine confirmation identifiers.
 */
export function sanitizeEvidenceText(rawText, maxChars = MAX_CONFIRMATION_TEXT_CHARS) {
  if (rawText === null || rawText === undefined) return '';
  let text = redactSecrets(String(rawText));
  text = text.replace(/\bBearer\s+[A-Za-z0-9._~+/=-]{6,}/gi, 'Bearer [REDACTED]');
  text = text.trim();
  if (text.length > maxChars) {
    text = text.slice(0, maxChars);
  }
  return text;
}

/**
 * Redacts sensitive patterns inside bounded confirmation container HTML and caps size cleanly.
 */
export function sanitizeContainerHtml(rawHtml, maxChars = MAX_CONTAINER_HTML_CHARS) {
  if (rawHtml === null || rawHtml === undefined) return '';
  let html = String(rawHtml).trim();
  // Strip any leftover inline scripts/styles, interactive/embedded controls, inline event handlers,
  // javascript: URIs, and sensitive data-* attributes cleanly in pure string fallback too.
  html = html
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '')
    .replace(/<noscript\b[^>]*>[\s\S]*?<\/noscript>/gi, '')
    .replace(/<iframe\b[^>]*>[\s\S]*?<\/iframe>/gi, '')
    .replace(/<textarea\b[^>]*>[\s\S]*?<\/textarea>/gi, '')
    .replace(/<select\b[^>]*>[\s\S]*?<\/select>/gi, '')
    .replace(/<input\b[^>]*>/gi, '')
    .replace(/\s(?:on[a-z]+|data-[a-z0-9-]*(?:token|secret|cookie|password|auth|session|csrf|xsrf|key)[a-z0-9-]*)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    .replace(/\s(href|src|action)\s*=\s*(['"])\s*javascript:[^'"]*\2/gi, '');
  html = sanitizeEvidenceText(html, maxChars);
  return html;
}

function postingJobIdFromUrl(targetUrl) {
  const confPath = greenhouseConfirmationPath(targetUrl);
  if (!confPath) return null;
  const match = confPath.match(/^\/[^/]+\/jobs\/([^/]+)\/confirmation$/);
  return match ? match[1] : null;
}

function isDisqualifiedIdentifier(candidate, { jobId, applicationId, targetUrl } = {}) {
  if (!candidate || typeof candidate !== 'string') return true;
  const trimmed = candidate.trim();
  if (trimmed.length < 3 || trimmed.length > 128) return true;
  if (isMockOrSynthetic(trimmed)) return true;
  if (isRequisitionOrReceiptFallback(trimmed)) return true;
  const lower = trimmed.toLowerCase();
  // Never confuse job ID, application ID, or URL job posting ID with an employer confirmation ID.
  if (jobId && lower === String(jobId).trim().toLowerCase()) return true;
  if (applicationId && lower === String(applicationId).trim().toLowerCase()) return true;
  const urlJobId = postingJobIdFromUrl(targetUrl);
  if (urlJobId && lower === urlJobId.trim().toLowerCase()) return true;
  return false;
}

/**
 * Extracts a genuine employer confirmation identifier strictly from verified 2xx response JSON,
 * explicit confirmation container attributes, or explicit labeled confirmation text inside the
 * captured confirmation container. Never manufactures an identifier and never accepts job,
 * application, or requisition IDs as confirmation identifiers.
 */
export function extractGenuineConfirmationId({
  responseBodyText = null,
  containerDataId = null,
  containerText = '',
  jobId = null,
  applicationId = null,
  targetUrl = null,
} = {}) {
  const ctx = { jobId, applicationId, targetUrl };

  // 1. Explicit confirmation keys in the 2xx submission JSON response (when JSON was returned).
  if (typeof responseBodyText === 'string' && responseBodyText.trim().startsWith('{')) {
    try {
      const parsed = JSON.parse(responseBodyText.slice(0, MAX_RESPONSE_SNIPPET_CHARS));
      if (parsed && typeof parsed === 'object') {
        for (const key of [
          'confirmationId',
          'confirmation_id',
          'confirmationNumber',
          'confirmation_number',
          'applicationConfirmationId',
          'application_confirmation_id',
        ]) {
          const val = parsed[key];
          if (typeof val === 'string' && !isDisqualifiedIdentifier(val, ctx)) {
            return val.trim();
          }
        }
      }
    } catch {
      // Non-JSON or truncated body is safely ignored.
    }
  }

  // 2. Explicit data-confirmation-id / data-confirmation-number attribute on the confirmation container.
  if (typeof containerDataId === 'string' && containerDataId.trim()) {
    if (!isDisqualifiedIdentifier(containerDataId, ctx)) {
      return containerDataId.trim();
    }
  }

  // 3. Explicit labeled identifier inside the bounded confirmation container text.
  const text = String(containerText ?? '');
  const lineMatch = text.match(FALLBACK_REF_LABELS) || text.match(CONFIRMATION_ID_LABELS);
  if (lineMatch && lineMatch[1]) {
    const candidate = lineMatch[1].replace(/[.,;)]+$/, '');
    // Reject if the label context itself mentions requisition or job ID.
    const idx = lineMatch.index ?? 0;
    const labelWindow = text.slice(Math.max(0, idx - 24), idx + lineMatch[0].length).toLowerCase();
    if (!/\b(requisition|job\s*id|req\s*id|application\s*id)\b/.test(labelWindow)
        && !isDisqualifiedIdentifier(candidate, ctx)) {
      return candidate;
    }
  }

  return undefined;
}

/**
 * Pure builder producing canonical submission evidence for verified Greenhouse confirmations.
 *
 * <p>Implements the shared {@code SUBMISSION_EVIDENCE} worker contract:
 * <ul>
 *   <li>Only actual runtime observations are included; missing confirmation IDs remain absent.</li>
 *   <li>No provenance, approval or sandbox booleans are ever emitted by the worker.</li>
 *   <li>Canonical JSON ensures deterministic SHA-256 computation by the backend.</li>
 * </ul>
 */
export function buildGreenhouseSubmissionEvidence({
  plan,
  classification,
  networkCapture,
  confirmationUrlMatched = false,
  confirmationContainerPresent = false,
  expectedConfirmationText = null,
  pageUrl = null,
  domCapture = {},
} = {}) {
  if (!classification?.confirmed || !networkCapture?.confirmed) {
    throw new Error('UNCONFIRMED_SUBMISSION_EVIDENCE_FORBIDDEN');
  }
  const confirmationPageUrl = sanitizeUrl(pageUrl || plan?.targetUrl || '');
  const containerHtml = sanitizeContainerHtml(domCapture.containerHtml ?? '');
  const confirmationText = sanitizeEvidenceText(domCapture.containerText ?? '');
  const observedAt = networkCapture?.observedAt || new Date().toISOString();

  const confirmationNumber = extractGenuineConfirmationId({
    responseBodyText: networkCapture?.responseBodyText ?? null,
    containerDataId: domCapture.confirmationIdAttr ?? null,
    containerText: confirmationText,
    jobId: plan?.correlation?.jobId ?? null,
    applicationId: plan?.correlation?.applicationId ?? null,
    targetUrl: plan?.targetUrl ?? null,
  });

  const signals = [];
  if (networkCapture?.confirmed) {
    signals.push('GREENHOUSE_SUBMISSION_RESPONSE_2XX');
  }
  if (confirmationUrlMatched) {
    signals.push('GREENHOUSE_CONFIRMATION_URL_MATCHED');
  }
  if (confirmationContainerPresent) {
    signals.push('GREENHOUSE_CONFIRMATION_CONTAINER_PRESENT');
  }
  if (expectedConfirmationText) {
    signals.push('EXPECTED_CONFIRMATION_TEXT_MATCHED');
  }
  if (confirmationNumber) {
    signals.push('EMPLOYER_CONFIRMATION_ID_OBSERVED');
  }

  const rawEvidenceObject = {
    schemaVersion: EVIDENCE_SCHEMA_VERSION,
    atsPlatform: 'GREENHOUSE',
    attemptCorrelation: {
      planId: plan?.planId ?? null,
      applicationId: plan?.correlation?.applicationId ?? null,
      jobId: plan?.correlation?.jobId ?? null,
    },
    observedAt,
    request: {
      method: networkCapture?.method ?? 'POST',
      url: sanitizeUrl(networkCapture?.url || plan?.targetUrl || ''),
      status: networkCapture?.status ?? null,
    },
    confirmationPageUrl,
    confirmationContainerHtml: containerHtml,
    confirmationText,
    confirmationNumber: confirmationNumber ?? null,
    signals,
  };

  const rawEvidence = canonicalJson(rawEvidenceObject);

  return {
    category: classification.category,
    confirmed: true,
    evidence: classification.evidence,
    confirmationPageUrl,
    observedAt,
    signals,
    ...(confirmationNumber ? { confirmationNumber } : {}),
    rawEvidence,
  };
}
