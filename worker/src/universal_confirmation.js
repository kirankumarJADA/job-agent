import { sanitizeUrl, redactSecrets } from './secret_redaction.js';
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

const CONFIRMATION_TEXT_RE = /thank\s*you|application\s+(has\s+been\s+)?(submitted|received)|we\s+have\s+received\s+your\s+application|submission\s+confirmed/i;
const CONFIRMATION_PATH_RE = /\/(confirmation|submitted|thank-you|thanks|receipt)\b/i;
const CONFIRMATION_ID_RE = /\b(?:EMP|APP|REF|CONF)-[A-Z0-9-]{4,32}\b/i;

function isAnalyticsUrl(rawUrl) {
  return /\/analytics|\/metrics|\/telemetry|\/beacon|segment|gtag/i.test(rawUrl);
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
  const hasConfirmDom = Boolean(domState.hasConfirmationContainer) || CONFIRMATION_TEXT_RE.test(bodyText);
  const hasConfirmPath = Boolean(finalUrl && CONFIRMATION_PATH_RE.test(new URL(finalUrl).pathname));
  const formDisappeared = !domState.submitButton && (domState.formCount === 0 || domState.controlsCount === 0 || hasConfirmDom);

  if (okPost && (hasConfirmDom || hasConfirmPath) && formDisappeared) {
    const signals = ['UNIVERSAL_SUBMISSION_RESPONSE_2XX'];
    if (hasConfirmDom) signals.push('UNIVERSAL_CONFIRMATION_DOM_MATCHED');
    if (hasConfirmPath) signals.push('UNIVERSAL_CONFIRMATION_URL_MATCHED');
    if (formDisappeared) signals.push('UNIVERSAL_FORM_DISAPPEARED');
    const jsonRef = okPost.confirmationNumber;
    const domMatch = bodyText.match(CONFIRMATION_ID_RE);
    const confirmationNumber = jsonRef || (domMatch ? domMatch[0] : null);
    if (confirmationNumber) signals.push('EMPLOYER_CONFIRMATION_ID_OBSERVED');

    const rawObj = {
      schema: 'robin-submission-evidence-v1',
      planType: 'UNIVERSAL',
      finalUrl: sanitizeUrl(finalUrl || expectedUrl),
      httpStatus: okPost.status,
      confirmationNumber,
      signals,
      bodyExcerpt: redactSecrets(bodyText.replace(/\s+/g, ' ').trim().slice(0, 400)),
    };
    return {
      confirmed: true,
      category: UniversalSubmitCategory.CONFIRMED,
      confirmationNumber,
      signals,
      evidence: confirmationNumber
        ? `Confirmed Universal submission (${confirmationNumber}) via HTTP ${okPost.status} and confirmation DOM.`
        : `Confirmed Universal submission via HTTP ${okPost.status} and confirmation DOM.`,
      rawEvidence: JSON.stringify(rawObj),
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
    evidence: 'Submit button clicked, but no paired 2xx submission response + confirmation state could be verified.',
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
      let confirmationNumber = null;
      let bodyRejected = false;
      let errorMessage = null;
      const ct = (res.headers()['content-type'] || '').toLowerCase();
      if (ct.includes('application/json')) {
        const data = await res.json().catch(() => null);
        if (data && typeof data === 'object') {
          if (data.success === false || data.ok === false || data.error) {
            bodyRejected = true;
            errorMessage = String(data.error || data.message || 'rejected');
          }
          const rawId = data.reference ?? data.confirmationId ?? data.confirmationNumber ?? data.id;
          if (typeof rawId === 'string' && rawId.trim() && rawId.trim().length <= 80) {
            confirmationNumber = rawId.trim();
          }
        }
      }
      capturedResponses.push({ method, url: sanitizeUrl(reqUrl), status, confirmationNumber, bodyRejected, errorMessage });
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
    const hasConfirmationContainer = await page.locator('[data-confirmation], .confirmation, #confirmation').count().catch(() => 0) > 0;
    return classifyUniversalSubmitOutcome({
      expectedUrl,
      finalUrl: page.url(),
      allowLocalTestHosts,
      networkResponses: capturedResponses,
      domState: {
        ...inspected,
        controlsCount: (inspected.controls ?? []).length,
        bodyText,
        hasConfirmationContainer,
      },
    });
  } finally {
    page.off('response', onResponse);
  }
}
