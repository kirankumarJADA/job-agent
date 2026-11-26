import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { canonicalJson } from './receipt.js';
import { sanitizeUrl } from './secret_redaction.js';
import {
  MAX_RESPONSE_SNIPPET_CHARS,
  extractGenuineConfirmationId,
  isDisqualifiedIdentifier,
  sanitizeContainerHtml,
  sanitizeEvidenceText,
} from './greenhouse_evidence.js';
import { universalPageIdentityMatches, inspectLiveFormDom } from './universal_form.js';

/**
 * Stage 3I Phase 2: the Universal browser evidence contract. v2 adds, to the v1 fields, the redirect
 * chain (each observed host and status), the final page origin, the source of a confirmation
 * identifier, per-document upload proof, personal-data redaction and a canonical self-digest
 * (`evidenceSha256` = SHA-256 of the canonical JSON of every other field). The backend re-derives
 * the digest (CanonicalJson.java) and re-checks correlation and host policy. Browser evidence is
 * never employer verification: `employerVerification` is always NOT_VERIFIED_BY_BROWSER.
 */
export const UNIVERSAL_EVIDENCE_SCHEMA_VERSION = 'robin-submission-evidence-v2';

const EMAIL_RE = /[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,24}/g;
const PHONE_RE = /(?<![A-Za-z0-9-])\+?\d[\d\s().-]{7,}\d(?![A-Za-z0-9-])/g;
const SHA256_RE = /^[0-9a-f]{64}$/;

export const UniversalSubmitCategory = Object.freeze({
  CONFIRMED: 'CONFIRMED',
  ANTI_BOT: 'ANTI_BOT',
  LOGIN_WALL: 'LOGIN_WALL',
  SUBMISSION_REJECTED: 'SUBMISSION_REJECTED',
  VALIDATION_ERROR: 'VALIDATION_ERROR',
  FORM_STILL_PRESENT: 'FORM_STILL_PRESENT',
  UNEXPECTED_ORIGIN_REDIRECT: 'UNEXPECTED_ORIGIN_REDIRECT',
  CONFIRMATION_UNOBSERVED: 'CONFIRMATION_UNOBSERVED',
  PRIOR_ATTEMPT_UNVERIFIED: 'PRIOR_ATTEMPT_UNVERIFIED',
});

const GENUINE_CONFIRMATION_TEXT_RE =
  /thank\s*you\s+(?:very\s+much\s+)?for\s+(?:applying|your\s+application|submitting)|(?:your\s+)?application\s+(?:has\s+been\s+|was\s+)?(?:successfully\s+)?(?:submitted|received|confirmed)|we(?:'ve|\s+have)\s+received\s+your\s+application|submission\s+(?:has\s+been\s+)?(?:received|confirmed)|application\s+confirmation/i;
const NEGATIVE_OUTCOME_RE =
  /\b(?:not\s+(?:been\s+)?submitted|unable\s+to\s+submit|could\s+not\s+(?:be\s+)?submitted|failed\s+to\s+submit|submission\s+failed|error\s+submitting|validation\s+error|please\s+(?:fix|correct|complete|try\s+again)|something\s+went\s+wrong|unable\s+to\s+process|did\s+not\s+go\s+through)\b/i;
const CONFIRMATION_PATH_RE = /\/(confirmation|submitted|thank-you|thanks|receipt)\b/i;
const CONFIRMATION_ID_RE = /\b(?:EMP|APP|REF|CONF)-[A-Z0-9-]{4,32}\b/gi;

function isAnalyticsUrl(rawUrl) {
  return /\/analytics|\/metrics|\/telemetry|\/beacon|segment|gtag/i.test(rawUrl);
}

/**
 * The genuine employer confirmation identifier and where it was observed, or undefined. Sources are
 * tried in the shared extractor's order: the 2xx response JSON, the confirmation container's data
 * attribute, labelled container text; then the worker-reported number and a prefixed pattern.
 */
function extractSafeUniversalConfirmationNumber({
  okPost,
  domState,
  confirmationText,
  jobId,
  applicationId,
  planId,
  targetUrl,
}) {
  const ctx = { jobId, applicationId, planId, targetUrl };
  const shared = { extraJsonKeys: ['reference', 'referenceId', 'reference_id'], ...ctx };
  const fromJson = extractGenuineConfirmationId({ responseBodyText: okPost?.responseBodyText ?? null, ...shared });
  if (fromJson) return { value: fromJson, source: 'RESPONSE_JSON' };
  const fromAttr = extractGenuineConfirmationId({ containerDataId: domState?.confirmationIdAttr ?? null, ...shared });
  if (fromAttr) return { value: fromAttr, source: 'CONTAINER_ATTRIBUTE' };
  const fromText = extractGenuineConfirmationId({ containerText: confirmationText, ...shared });
  if (fromText) return { value: fromText, source: 'CONTAINER_TEXT' };

  if (typeof okPost?.confirmationNumber === 'string' && !isDisqualifiedIdentifier(okPost.confirmationNumber, ctx)) {
    return { value: okPost.confirmationNumber.trim(), source: 'RESPONSE_JSON' };
  }

  for (const match of String(confirmationText ?? '').matchAll(CONFIRMATION_ID_RE)) {
    const candidate = match[0];
    const idx = match.index ?? 0;
    const windowBefore = String(confirmationText ?? '').slice(Math.max(0, idx - 32), idx).toLowerCase();
    if (/\b(requisition|job\s*id|req\s*id|application\s*id|plan\s*id)\b/.test(windowBefore)) {
      continue;
    }
    if (!isDisqualifiedIdentifier(candidate, ctx)) {
      return { value: candidate, source: 'CONTAINER_TEXT_PATTERN' };
    }
  }
  return undefined;
}

/**
 * Removes the candidate's personal data from evidence text before it is persisted: e-mail
 * addresses, phone numbers and the candidate's own profile values (name, e-mail, phone). Values in
 * `keep` (the genuine confirmation identifier) are protected so redaction never alters them.
 */
export function redactPersonalData(text, identityValues = [], keep = []) {
  if (text === null || text === undefined) return '';
  let out = String(text);
  const placeholders = [];
  for (const k of keep) {
    if (typeof k !== 'string' || !k) continue;
    const token = `\u0000KEEP${placeholders.length}\u0000`;
    placeholders.push([token, k]);
    out = out.split(k).join(token);
  }
  const values = (identityValues ?? [])
    .filter((v) => typeof v === 'string' && v.trim().length >= 2)
    .map((v) => v.trim())
    .sort((a, b) => b.length - a.length);
  for (const v of values) {
    const escaped = v.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(`(?<![A-Za-z0-9])${escaped}(?![A-Za-z0-9])`, 'gi'), '[candidate]');
  }
  out = out.replace(EMAIL_RE, '[email]').replace(PHONE_RE, '[phone]');
  for (const [token, k] of placeholders) out = out.split(token).join(k);
  return out;
}

/** The candidate's own values to redact, from the plan's profile facts. Never logged. */
export function identityValuesOf(pkg = {}) {
  const f = pkg?.profileFacts ?? {};
  return [f.fullName, f.name, f.firstName, f.lastName, f.email, f.phone, f.linkedIn, f.linkedin]
    .filter((v) => typeof v === 'string' && v.trim());
}

/** File names of the file parts in a multipart/form-data body (Content-Disposition `filename`). */
export function multipartFileNames(contentType, body) {
  if (!body || typeof contentType !== 'string' || !/multipart\/form-data/i.test(contentType)) return [];
  const m = contentType.match(/boundary=(?:"([^"]+)"|([^;\s]+))/i);
  const boundary = m ? (m[1] || m[2]) : null;
  if (!boundary) return [];
  const text = Buffer.isBuffer(body) ? body.toString('latin1') : String(body);
  const names = [];
  for (const part of text.split(`--${boundary}`)) {
    const headerEnd = part.indexOf('\r\n\r\n');
    const headers = headerEnd >= 0 ? part.slice(0, headerEnd) : part;
    const disposition = headers.match(/content-disposition:[^\r\n]*/i);
    if (!disposition) continue;
    const fn = disposition[0].match(/filename="([^"]*)"|filename=([^;\s]+)/i);
    if (fn) names.push((fn[1] ?? fn[2] ?? '').trim());
  }
  return names.filter(Boolean);
}

/**
 * Upload proof for the plan's documents, never claiming more than the browser saw:
 * - formAttachment: what the form held (ATTACHED only when the DOM held the exact artifact, whose
 *   bytes the worker hashed and matched to the package digest before attaching);
 * - submissionRequestFilePart: whether the accepted submission request carried a file part with
 *   that artifact's file name (Chromium never exposes multipart file bytes to automation, so the
 *   bytes themselves are not observable: bytesTransmissionObserved is always false);
 * - uploadVerification: FILE_PART_OBSERVED | ATTACHED_NOT_CONFIRMED | NOT_ATTACHED | NO_FIELD.
 * A submitted-document digest is reported only for FILE_PART_OBSERVED.
 */
export function summarizeDocumentUploads({ pkg = null, uploadStates = {}, attachedNow = {}, transmittedFileNames = [] } = {}) {
  const documents = {};
  for (const kind of ['cv', 'coverLetter']) {
    const artifact = pkg?.[kind] ?? null;
    const hasArtifact = Boolean(artifact?.path);
    const recorded = uploadStates?.[kind];
    let formAttachment;
    if (attachedNow?.[kind] === true) formAttachment = 'ATTACHED';
    else if (!hasArtifact) formAttachment = 'NO_ARTIFACT';
    else if (['ATTACHED', 'REJECTED', 'NO_FIELD', 'NO_ARTIFACT'].includes(recorded)) formAttachment = recorded;
    else formAttachment = 'NOT_ATTEMPTED';
    const baseName = hasArtifact ? path.basename(String(artifact.path)) : null;
    const filePart = formAttachment === 'ATTACHED' && baseName && transmittedFileNames.includes(baseName)
      ? 'FILE_PART_OBSERVED' : 'NOT_OBSERVED';
    const uploadVerification = formAttachment === 'NO_FIELD' ? 'NO_FIELD'
      : formAttachment === 'ATTACHED' ? (filePart === 'FILE_PART_OBSERVED' ? 'FILE_PART_OBSERVED' : 'ATTACHED_NOT_CONFIRMED')
        : 'NOT_ATTACHED';
    const sha = typeof artifact?.sha256 === 'string' && SHA256_RE.test(artifact.sha256) ? artifact.sha256 : null;
    documents[kind] = {
      artifactSha256: formAttachment === 'ATTACHED' ? sha : null,
      formAttachment,
      submissionRequestFilePart: filePart,
      bytesTransmissionObserved: false,
      uploadVerification,
    };
  }
  return {
    documents,
    submittedCvSha256: documents.cv.uploadVerification === 'FILE_PART_OBSERVED' ? documents.cv.artifactSha256 : null,
    submittedCoverLetterSha256: documents.coverLetter.uploadVerification === 'FILE_PART_OBSERVED'
      ? documents.coverLetter.artifactSha256 : null,
  };
}

const NOT_RECORDED_DOCUMENTS = Object.freeze({
  documents: {
    cv: { artifactSha256: null, formAttachment: 'NOT_RECORDED', submissionRequestFilePart: 'NOT_OBSERVED', bytesTransmissionObserved: false, uploadVerification: 'NOT_RECORDED' },
    coverLetter: { artifactSha256: null, formAttachment: 'NOT_RECORDED', submissionRequestFilePart: 'NOT_OBSERVED', bytesTransmissionObserved: false, uploadVerification: 'NOT_RECORDED' },
  },
  submittedCvSha256: null,
  submittedCoverLetterSha256: null,
});

function hostOf(url) {
  try { return new URL(url).host.toLowerCase(); } catch { return null; }
}

function originOf(url) {
  try { return new URL(url).origin; } catch { return null; }
}

/** Seals a v2 evidence body: canonical JSON with its own SHA-256 digest. Deterministic. */
export function sealUniversalEvidence(body) {
  const evidenceSha256 = crypto.createHash('sha256').update(canonicalJson(body), 'utf8').digest('hex');
  return canonicalJson({ ...body, evidenceSha256 });
}

/** True when a sealed v2 evidence string's digest matches its other fields. */
export function verifyUniversalEvidenceDigest(rawEvidence) {
  try {
    const parsed = JSON.parse(rawEvidence);
    const { evidenceSha256, ...body } = parsed;
    if (typeof evidenceSha256 !== 'string') return false;
    return crypto.createHash('sha256').update(canonicalJson(body), 'utf8').digest('hex') === evidenceSha256;
  } catch {
    return false;
  }
}

/**
 * Pure classification matrix over observed network submission responses,
 * final page URL/origin, DOM confirmation signals, and security banners.
 */
export function classifyUniversalSubmitOutcome({
  expectedUrl,
  finalUrl,
  allowLocalTestHosts = false,
  networkResponses = [],
  domState = {},
  jobId = null,
  applicationId = null,
  planId = null,
  navigationChain = [],
  documentFacts = null,
  identityValues = [],
}) {
  const security = domState.securitySignals ?? {};
  if (security.captchaPresent) {
    return {
      confirmed: false,
      category: UniversalSubmitCategory.ANTI_BOT,
      evidence: 'CAPTCHA or anti-bot challenge appeared during submission.',
      signals: ['UNIVERSAL_ANTI_BOT_SIGNAL'],
    };
  }
  if (security.loginWallPresent || security.mfaPresent) {
    return {
      confirmed: false,
      category: UniversalSubmitCategory.LOGIN_WALL,
      evidence: 'Employer sign-in or MFA prompt intercepted submission.',
      signals: ['UNIVERSAL_LOGIN_OR_MFA_INTERCEPT'],
    };
  }
  if (finalUrl && !universalPageIdentityMatches(expectedUrl, finalUrl, { allowLocalTestHosts })) {
    return {
      confirmed: false,
      category: UniversalSubmitCategory.UNEXPECTED_ORIGIN_REDIRECT,
      evidence: `Unexpected cross-origin redirect to ${sanitizeUrl(finalUrl)} during submission.`,
      signals: ['UNIVERSAL_ORIGIN_MISMATCH'],
    };
  }
  // Stage 3I Phase 2: every main-frame hop observed during submission must stay on the target origin
  // (the same exact-origin rule as the final page), not only the page the chain ended on.
  const offOrigin = (navigationChain ?? []).find((hop) => hop?.url && !universalPageIdentityMatches(expectedUrl, hop.url, { allowLocalTestHosts }));
  if (offOrigin) {
    return {
      confirmed: false,
      category: UniversalSubmitCategory.UNEXPECTED_ORIGIN_REDIRECT,
      evidence: `Submission was redirected through ${sanitizeUrl(offOrigin.url)}, outside the employer's application origin.`,
      signals: ['UNIVERSAL_REDIRECT_CHAIN_ORIGIN_MISMATCH'],
    };
  }
  if (networkResponses.some((r) => r.status === 403 || r.status === 429)) {
    return {
      confirmed: false,
      category: UniversalSubmitCategory.ANTI_BOT,
      evidence: 'Employer returned HTTP 403/429 rate-limit or bot-protection response.',
      signals: ['UNIVERSAL_HTTP_403_OR_429'],
    };
  }
  const serverRejected = networkResponses.find((r) => r.status >= 400 || r.bodyRejected === true);
  if (serverRejected || (Array.isArray(domState.validationErrors) && domState.validationErrors.length > 0)) {
    const errText = domState.validationErrors?.[0] || serverRejected?.errorMessage || `HTTP ${serverRejected?.status}`;
    return {
      confirmed: false,
      category: serverRejected?.status >= 400 && !(domState.validationErrors?.length > 0)
        ? UniversalSubmitCategory.SUBMISSION_REJECTED
        : UniversalSubmitCategory.VALIDATION_ERROR,
      evidence: `Employer rejected submission: ${String(errText).slice(0, 200)}`,
      signals: ['UNIVERSAL_SUBMISSION_VALIDATION_OR_4XX'],
    };
  }

  const okPost = networkResponses.find((r) => ['POST', 'PUT', 'PATCH'].includes(r.method) && r.status >= 200 && r.status < 300);
  const bodyText = String(domState.bodyText ?? '');
  const containerTextRaw = String(domState.containerText ?? '');
  const combinedText = `${containerTextRaw}\n${bodyText}`.trim();

  if (NEGATIVE_OUTCOME_RE.test(combinedText)) {
    return {
      confirmed: false,
      category: UniversalSubmitCategory.CONFIRMATION_UNOBSERVED,
      evidence: 'Page text contained negative or error phrasing instead of an unambiguous confirmation.',
      signals: ['UNIVERSAL_NEGATIVE_PHRASING_PRESENT'],
    };
  }

  const hasGenuineText = GENUINE_CONFIRMATION_TEXT_RE.test(combinedText);
  let hasConfirmPath = false;
  if (finalUrl) {
    try {
      hasConfirmPath = CONFIRMATION_PATH_RE.test(new URL(finalUrl).pathname);
    } catch {
      hasConfirmPath = false;
    }
  }
  // Never accept an unverified generic `.confirmation` banner lacking genuine application
  // confirmation wording (unless a dedicated confirmation URL route itself also matched).
  const hasConfirmDom = hasGenuineText;
  const formDisappeared = !domState.submitButton && (domState.formCount === 0 || domState.controlsCount === 0 || hasConfirmDom);

  if (okPost && (hasConfirmDom || hasConfirmPath) && formDisappeared) {
    const signals = ['UNIVERSAL_SUBMISSION_RESPONSE_2XX'];
    if (hasConfirmDom) signals.push('UNIVERSAL_CONFIRMATION_DOM_MATCHED');
    if (domState.hasConfirmationContainer && hasConfirmDom) signals.push('UNIVERSAL_CONFIRMATION_CONTAINER_PRESENT');
    if (hasConfirmPath) signals.push('UNIVERSAL_CONFIRMATION_URL_MATCHED');
    if (formDisappeared) signals.push('UNIVERSAL_FORM_DISAPPEARED');

    const secretFreeText = sanitizeEvidenceText(domState.containerText || bodyText);
    const confirmationPageUrl = sanitizeUrl(finalUrl || expectedUrl || '');
    const observedAt = okPost.observedAt || new Date().toISOString();

    const identifier = extractSafeUniversalConfirmationNumber({
      okPost,
      domState,
      confirmationText: secretFreeText,
      jobId,
      applicationId,
      planId,
      targetUrl: expectedUrl || finalUrl,
    });
    const confirmationNumber = identifier?.value;
    if (confirmationNumber) signals.push('EMPLOYER_CONFIRMATION_ID_OBSERVED');
    // Secrets are removed first; then the candidate's personal data, keeping the genuine identifier intact.
    const keep = confirmationNumber ? [confirmationNumber] : [];
    const confirmationText = redactPersonalData(secretFreeText, identityValues, keep);
    const confirmationContainerHtml = redactPersonalData(sanitizeContainerHtml(domState.containerHtml ?? ''), identityValues, keep);
    const docs = documentFacts ?? NOT_RECORDED_DOCUMENTS;

    const rawEvidence = sealUniversalEvidence({
      schemaVersion: UNIVERSAL_EVIDENCE_SCHEMA_VERSION,
      atsPlatform: 'UNIVERSAL',
      attemptCorrelation: {
        planId: planId ?? null,
        applicationId: applicationId ?? null,
        jobId: jobId ?? null,
      },
      observedAt,
      observation: { source: 'PLAYWRIGHT_BROWSER', clock: 'WORKER_CLOCK' },
      request: {
        method: okPost.method ?? 'POST',
        url: sanitizeUrl(okPost.url || expectedUrl || ''),
        status: okPost.status ?? null,
        observedAt,
      },
      redirectChain: (navigationChain ?? []).map((hop) => ({ host: hostOf(hop.url), status: Number.isInteger(hop.status) ? hop.status : null })),
      finalOrigin: originOf(finalUrl || expectedUrl || ''),
      confirmationPageUrl,
      confirmationContainerHtml,
      confirmationText,
      confirmationNumber: confirmationNumber ?? null,
      confirmationIdentifierSource: identifier?.source ?? null,
      signals,
      documents: docs.documents,
      submittedCvSha256: docs.submittedCvSha256,
      submittedCoverLetterSha256: docs.submittedCoverLetterSha256,
      employerVerification: 'NOT_VERIFIED_BY_BROWSER',
      redaction: { secrets: 'REDACTED', personalData: 'REDACTED' },
    });

    return {
      confirmed: true,
      category: UniversalSubmitCategory.CONFIRMED,
      ...(confirmationNumber ? { confirmationNumber } : {}),
      signals,
      evidence: confirmationNumber
        ? `Browser observed Universal submission (${confirmationNumber}) via HTTP ${okPost.status} and confirmation DOM.`
        : `Browser observed Universal submission via HTTP ${okPost.status} and confirmation DOM.`,
      rawEvidence,
    };
  }

  if (domState.submitButton && domState.formCount > 0) {
    return {
      confirmed: false,
      category: UniversalSubmitCategory.FORM_STILL_PRESENT,
      evidence: 'Application form is still present without confirmation after clicking Submit.',
      signals: ['UNIVERSAL_FORM_STILL_PRESENT'],
    };
  }

  return {
    confirmed: false,
    category: UniversalSubmitCategory.CONFIRMATION_UNOBSERVED,
    evidence: 'Submit button clicked, but no paired 2xx submission response + genuine confirmation state could be verified.',
    signals: [],
  };
}

/**
 * Which package artifacts the live form holds right now: a file input (in the document or an open
 * shadow root) holding a file with the artifact's exact name and byte length, where the local file's
 * SHA-256 equals the package digest. Selection alone is all this proves — never that it was uploaded.
 */
export async function attachedArtifactsInForm(page, pkg) {
  const out = {};
  if (!pkg || typeof page?.evaluate !== 'function') return out;
  let held = [];
  try {
    held = await page.evaluate(() => {
      const files = [];
      const visit = (root) => {
        for (const el of root.querySelectorAll('input[type="file"]')) {
          for (const f of Array.from(el.files ?? [])) files.push({ name: f.name, size: f.size });
        }
        for (const host of root.querySelectorAll('*')) {
          if (host.shadowRoot) visit(host.shadowRoot);
        }
      };
      visit(document);
      return files;
    });
  } catch {
    return out;
  }
  for (const kind of ['cv', 'coverLetter']) {
    const artifact = pkg[kind];
    if (!artifact?.path) continue;
    try {
      const resolved = path.resolve(String(artifact.path));
      const bytes = fs.readFileSync(resolved);
      const sha = crypto.createHash('sha256').update(bytes).digest('hex');
      if (artifact.sha256 && sha !== artifact.sha256) continue;
      const name = path.basename(resolved);
      out[kind] = held.some((f) => f.name === name && f.size === bytes.length);
    } catch {
      out[kind] = false;
    }
  }
  return out;
}

/**
 * Attaches network response listener before clicking the final Submit button
 * and inspects DOM + responses immediately afterwards.
 */
export async function executeAndVerifyUniversalSubmit({
  page,
  submitSelector,
  expectedUrl,
  allowLocalTestHosts = false,
  jobId = null,
  applicationId = null,
  planId = null,
  retryFn = (fn) => fn(),
  beforeClickHook = null,
  pkg = null,
  uploadStates = {},
  identityValues = [],
}) {
  const capturedResponses = [];
  const navigationChain = [];
  const seenNavigation = new Set();
  const recordNavigation = async (req, res) => {
    // Earlier redirect hops first, then this response; each request counted once.
    const hops = [];
    let prev = req.redirectedFrom?.();
    while (prev) {
      hops.unshift(prev);
      prev = prev.redirectedFrom?.();
    }
    for (const hop of hops) {
      if (seenNavigation.has(hop)) continue;
      seenNavigation.add(hop);
      const hopRes = await hop.response?.().catch(() => null);
      navigationChain.push({ url: sanitizeUrl(hop.url()), status: hopRes?.status?.() ?? null });
    }
    if (!seenNavigation.has(req)) {
      seenNavigation.add(req);
      navigationChain.push({ url: sanitizeUrl(res.url()), status: res.status() });
    }
  };
  const onResponse = async (res) => {
    try {
      const req = res.request();
      if (req.isNavigationRequest?.() && (!res.frame || res.frame() === page.mainFrame?.())) {
        await recordNavigation(req, res);
      }
      const method = req.method();
      if (!['POST', 'PUT', 'PATCH'].includes(method)) return;
      const reqUrl = req.url();
      if (isAnalyticsUrl(reqUrl)) return;
      if (!universalPageIdentityMatches(expectedUrl, reqUrl, { allowLocalTestHosts })) return;
      const status = res.status();
      const observedAt = new Date().toISOString();
      // File names in a multipart body are used only to compare with the artifact; never persisted.
      let fileNames = [];
      try {
        const headers = req.headers?.() ?? {};
        fileNames = multipartFileNames(headers['content-type'], req.postDataBuffer?.() ?? null);
      } catch {
        fileNames = [];
      }
      let bodyRejected = false;
      let errorMessage = null;
      let responseBodyText = null;
      const ct = (res.headers()['content-type'] || '').toLowerCase();
      if (ct.includes('application/json')) {
        const rawText = await res.text().catch(() => null);
        if (typeof rawText === 'string' && rawText.trim()) {
          responseBodyText = rawText.slice(0, MAX_RESPONSE_SNIPPET_CHARS);
          try {
            const data = JSON.parse(responseBodyText);
            if (data && typeof data === 'object') {
              if (data.success === false || data.ok === false || data.error) {
                bodyRejected = true;
                errorMessage = String(data.error || data.message || 'rejected');
              }
            }
          } catch {
            // Non-JSON or truncated payload ignored cleanly
          }
        }
      }
      capturedResponses.push({
        method,
        url: sanitizeUrl(reqUrl),
        status,
        observedAt,
        responseBodyText,
        bodyRejected,
        errorMessage,
        fileNames,
      });
    } catch {
      // Never crash response observer on stream close
    }
  };

  page.on('response', onResponse);
  try {
    if (beforeClickHook) await beforeClickHook();
    const attachedNow = await attachedArtifactsInForm(page, pkg);
    await retryFn(() => page.locator(submitSelector).first().click());
    await page.waitForLoadState('domcontentloaded').catch(() => {});
    await page.waitForTimeout(400);

    const inspected = await inspectLiveFormDom(page);
    const bodyText = await page.locator('body').innerText().catch(() => '');
    const containerLoc = page.locator('[data-confirmation], .confirmation, #confirmation').first();
    const hasConfirmationContainer = (await page.locator('[data-confirmation], .confirmation, #confirmation').count().catch(() => 0)) > 0;
    const containerText = hasConfirmationContainer ? await containerLoc.innerText().catch(() => '') : '';
    const containerHtml = hasConfirmationContainer ? await containerLoc.innerHTML().catch(() => '') : '';
    const confirmationIdAttr = hasConfirmationContainer
      ? ((await containerLoc.getAttribute('data-confirmation-id').catch(() => null))
        || (await containerLoc.getAttribute('data-confirmation-number').catch(() => null)))
      : null;

    const accepted = capturedResponses.filter((r) => r.status >= 200 && r.status < 300 && !r.bodyRejected);
    const documentFacts = pkg ? summarizeDocumentUploads({
      pkg,
      uploadStates,
      attachedNow,
      transmittedFileNames: accepted.flatMap((r) => r.fileNames ?? []),
    }) : null;
    return classifyUniversalSubmitOutcome({
      expectedUrl,
      finalUrl: page.url(),
      allowLocalTestHosts,
      jobId,
      applicationId,
      planId,
      navigationChain,
      documentFacts,
      identityValues,
      networkResponses: capturedResponses.map(({ fileNames, ...rest }) => rest),
      domState: {
        ...inspected,
        controlsCount: (inspected.controls ?? []).length,
        bodyText,
        hasConfirmationContainer,
        containerText,
        containerHtml,
        confirmationIdAttr,
      },
    });
  } finally {
    page.off('response', onResponse);
  }
}
