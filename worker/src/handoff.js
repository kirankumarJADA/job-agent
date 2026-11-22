/**
 * Phase 3: Candidate-controlled ATS Browser Handoff Client (Option B: External Human Completion).
 *
 * Safe handoff for security checkpoints (CAPTCHA, Workday MFA, Turnstile):
 * 1. Zero automated bypass: Robin stops immediately and yields to the candidate.
 * 2. Playwright browser context closes and Chromium terminates cleanly.
 * 3. Execution lease is released immediately without polling loops or wait heartbeats.
 * 4. Control transfers to candidate to independently complete application on employer site.
 * 5. Candidate explicitly attests submission in Robin UI; plan reaches terminal COMPLETED.
 * 6. Playwright worker is never resumed after handoff.
 */

export const HandoffStatus = Object.freeze({
  PENDING: 'PENDING',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
  EXPIRED: 'EXPIRED',
});

export const HandoffReason = Object.freeze({
  LEVER_CAPTCHA_PRESENT: 'LEVER_CAPTCHA_PRESENT',
  WORKDAY_MFA_REQUIRED: 'WORKDAY_MFA_REQUIRED',
  WORKDAY_CAPTCHA_PRESENT: 'WORKDAY_CAPTCHA_PRESENT',
  CLOUDFLARE_TURNSTILE: 'CLOUDFLARE_TURNSTILE',
});

/**
 * Checks whether an error represents a handoff-eligible security checkpoint.
 */
export function isHandoffEligibleError(error) {
  if (!error) return false;
  const reason = String(error.reason || error.message || '');
  return /CAPTCHA|MFA|TURNSTILE|SECURITY_CHECK|ANTI[-_ ]BOT/i.test(reason)
    || reason === 'LEVER_CAPTCHA_PRESENT'
    || reason === 'WORKDAY_MFA_REQUIRED'
    || reason === 'WORKDAY_CAPTCHA_PRESENT'
    || reason === 'BLOCKED_ANTI_BOT';
}

/**
 * Normalizes an error reason into a standard platform handoff reason.
 */
export function normalizeHandoffReason(error, planType) {
  const reason = String(error?.reason || error?.message || '');
  if (/MFA|TWO[- ]FACTOR|OTP|AUTHENTICATOR/i.test(reason)) {
    return 'WORKDAY_MFA_REQUIRED';
  }
  if (planType === 'LEVER' || /LEVER/i.test(reason)) {
    return 'LEVER_CAPTCHA_PRESENT';
  }
  if (/WORKDAY/i.test(reason)) {
    return 'WORKDAY_CAPTCHA_PRESENT';
  }
  if (/TURNSTILE|CLOUDFLARE/i.test(reason)) {
    return 'CLOUDFLARE_TURNSTILE';
  }
  if (/ANTI[-_ ]BOT|SECURITY/i.test(reason)) {
    return 'EMPLOYER_SECURITY_CHECK';
  }
  return reason || 'CAPTCHA';
}

/**
 * Option B: Initiates candidate handoff session and returns immediately.
 * The headless browser context is closed; control is transferred to the human candidate.
 * The candidate completes and submits the application directly on the employer site.
 *
 * @param {object} options
 * @param {object} options.plan The automation plan being executed
 * @param {Error} options.error The hard stop error that triggered the handoff
 * @param {string} options.backendBase Base URL for Robin API
 * @param {Function} [options.authHeaders] Function returning { authorization: ... }
 * @param {number} [options.timeoutMs=900000] Maximum TTL for the handoff session
 * @param {Function} [options.fetchImpl=fetch] Custom fetch for testing
 * @returns {Promise<{initiated: boolean, sessionId: string, status: string, blockReason: string}>}
 */
export async function initiateHandoff({
  plan,
  error,
  backendBase,
  authHeaders,
  timeoutMs = 15 * 60 * 1000,
  fetchImpl = fetch,
}) {
  const planId = plan.planId;
  const applicationId = plan.correlation?.applicationId;
  const planType = plan.planType;
  const targetUrl = plan.targetUrl;
  const blockReason = normalizeHandoffReason(error, planType);
  const domScreenshotRef = error?.details?.screenshot || null;

  const initiateUrl = `${backendBase.replace(/\/$/, '')}/api/v1/handoff/initiate`;
  const initiateBody = {
    planId,
    applicationId,
    atsPlatform: planType,
    targetUrl,
    blockReason,
    domScreenshotRef,
    ttlMinutes: Math.round(timeoutMs / 60000),
  };

  const initResponse = await fetchImpl(initiateUrl, {
    method: 'POST',
    headers: { ...(authHeaders ? authHeaders() : {}), 'content-type': 'application/json' },
    body: JSON.stringify(initiateBody),
  });

  if (!initResponse.ok) {
    throw new Error(`HANDOFF_INITIATE_FAILED:${initResponse.status}`);
  }

  const session = await initResponse.json();
  return {
    initiated: true,
    sessionId: session.id,
    status: session.status || HandoffStatus.PENDING,
    blockReason,
  };
}
