import { canonicalJson } from './receipt.js';
import { sanitizeUrl } from './secret_redaction.js';
import {
  EVIDENCE_SCHEMA_VERSION,
  MAX_RESPONSE_SNIPPET_CHARS,
  extractGenuineConfirmationId,
  isDisqualifiedIdentifier,
  sanitizeContainerHtml,
  sanitizeEvidenceText,
} from './greenhouse_evidence.js';
import { universalPageIdentityMatches, inspectLiveFormDom } from './universal_form.js';

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
  const fromSharedExtractor = extractGenuineConfirmationId({
    responseBodyText: okPost?.responseBodyText ?? null,
    containerDataId: domState?.confirmationIdAttr ?? null,
    containerText: confirmationText,
    extraJsonKeys: ['reference', 'referenceId', 'reference_id'],
    ...ctx,
  });
  if (fromSharedExtractor) return fromSharedExtractor;

  if (typeof okPost?.confirmationNumber === 'string' && !isDisqualifiedIdentifier(okPost.confirmationNumber, ctx)) {
    return okPost.confirmationNumber.trim();
  }

  for (const match of String(confirmationText ?? '').matchAll(CONFIRMATION_ID_RE)) {
    const candidate = match[0];
    const idx = match.index ?? 0;
    const windowBefore = String(confirmationText ?? '').slice(Math.max(0, idx - 32), idx).toLowerCase();
    if (/\b(requisition|job\s*id|req\s*id|application\s*id|plan\s*id)\b/.test(windowBefore)) {
      continue;
    }
    if (!isDisqualifiedIdentifier(candidate, ctx)) {
      return candidate;
    }
  }
  return undefined;
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

    const confirmationText = sanitizeEvidenceText(domState.containerText || bodyText);
    const confirmationContainerHtml = sanitizeContainerHtml(domState.containerHtml ?? '');
    const confirmationPageUrl = sanitizeUrl(finalUrl || expectedUrl || '');
    const observedAt = okPost.observedAt || new Date().toISOString();

    const confirmationNumber = extractSafeUniversalConfirmationNumber({
      okPost,
      domState,
      confirmationText,
      jobId,
      applicationId,
      planId,
      targetUrl: expectedUrl || finalUrl,
    });
    if (confirmationNumber) signals.push('EMPLOYER_CONFIRMATION_ID_OBSERVED');

    const rawEvidenceObject = {
      schemaVersion: EVIDENCE_SCHEMA_VERSION,
      atsPlatform: 'UNIVERSAL',
      attemptCorrelation: {
        planId: planId ?? null,
        applicationId: applicationId ?? null,
        jobId: jobId ?? null,
      },
      observedAt,
      request: {
        method: okPost.method ?? 'POST',
        url: sanitizeUrl(okPost.url || expectedUrl || ''),
        status: okPost.status ?? null,
      },
      confirmationPageUrl,
      confirmationContainerHtml,
      confirmationText,
      confirmationNumber: confirmationNumber ?? null,
      signals,
    };

    return {
      confirmed: true,
      category: UniversalSubmitCategory.CONFIRMED,
      ...(confirmationNumber ? { confirmationNumber } : {}),
      signals,
      evidence: confirmationNumber
        ? `Browser observed Universal submission (${confirmationNumber}) via HTTP ${okPost.status} and confirmation DOM.`
        : `Browser observed Universal submission via HTTP ${okPost.status} and confirmation DOM.`,
      rawEvidence: canonicalJson(rawEvidenceObject),
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
}) {
  const capturedResponses = [];
  const onResponse = async (res) => {
    try {
      const req = res.request();
      const method = req.method();
      if (!['POST', 'PUT', 'PATCH'].includes(method)) return;
      const reqUrl = req.url();
      if (isAnalyticsUrl(reqUrl)) return;
      if (!universalPageIdentityMatches(expectedUrl, reqUrl, { allowLocalTestHosts })) return;
      const status = res.status();
      const observedAt = new Date().toISOString();
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
      });
    } catch {
      // Never crash response observer on stream close
    }
  };

  page.on('response', onResponse);
  try {
    if (beforeClickHook) await beforeClickHook();
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

    return classifyUniversalSubmitOutcome({
      expectedUrl,
      finalUrl: page.url(),
      allowLocalTestHosts,
      jobId,
      applicationId,
      planId,
      networkResponses: capturedResponses,
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
