/**
 * Phase 3: Candidate-controlled ATS Browser Handoff Client.
 *
 * Safe handoff for security checkpoints (CAPTCHA, Workday MFA, Turnstile):
 * 1. Zero automated bypass: Robin stops immediately and yields to the candidate.
 * 2. Pauses worker execution and enters a guarded wait loop.
 * 3. Heartbeats continue to maintain the execution lease.
 * 4. Resumes execution only when candidate explicitly confirms resolution in the UI.
 * 5. Handles timeouts, candidate cancellations, and execution fencing.
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
  return /CAPTCHA|MFA|TURNSTILE|SECURITY_CHECK/i.test(reason)
    || reason === 'LEVER_CAPTCHA_PRESENT'
    || reason === 'WORKDAY_MFA_REQUIRED'
    || reason === 'WORKDAY_CAPTCHA_PRESENT';
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

/**
 * Initiates handoff session and waits for candidate confirmation or timeout.
 * Used when worker caller explicitly monitors session resolution.
 *
 * @param {object} options
 * @param {object} options.plan The automation plan being executed
 * @param {Error} options.error The hard stop error that triggered the handoff
 * @param {string} options.backendBase Base URL for Robin API
 * @param {Function} options.authHeaders Function returning { authorization: ... }
 * @param {Function} options.sendHeartbeat Heartbeat callback
 * @param {object} options.runtime Worker runtime state for cancellation
 * @param {number} [options.pollIntervalMs=5000] Interval between handoff status polls
 * @param {number} [options.timeoutMs=900000] Maximum wait time (default 15 minutes)
 * @param {Function} [options.fetchImpl=fetch] Custom fetch for testing
 * @returns {Promise<{resumed: boolean, status: string, sessionId?: string, reason?: string}>}
 */
export async function initiateHandoffAndWait({
  plan,
  error,
  backendBase,
  authHeaders,
  sendHeartbeat,
  runtime,
  pollIntervalMs = 5000,
  timeoutMs = 15 * 60 * 1000,
  fetchImpl = fetch,
}) {
  const initResult = await initiateHandoff({
    plan,
    error,
    backendBase,
    authHeaders,
    timeoutMs,
    fetchImpl,
  });

  const planId = plan.planId;
  const applicationId = plan.correlation?.applicationId;
  const sessionId = initResult.sessionId;

  // Poll loop: wait for candidate action while continuing heartbeats
  const statusUrl = `${backendBase.replace(/\/$/, '')}/api/v1/handoff/applications/${encodeURIComponent(applicationId)}`;
  const startTime = Date.now();
  let lastHeartbeat = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    if (runtime?.shuttingDown) {
      return { resumed: false, status: 'ABORTED', sessionId, reason: 'WORKER_SHUTDOWN' };
    }

    // Maintain heartbeat every 30s
    if (sendHeartbeat && Date.now() - lastHeartbeat >= 30000) {
      try {
        await sendHeartbeat(planId);
        lastHeartbeat = Date.now();
      } catch (hbErr) {
        // Log warning but continue wait unless abort requested
      }
    }

    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));

    try {
      const pollRes = await fetchImpl(statusUrl, {
        method: 'GET',
        headers: authHeaders ? authHeaders() : {},
      });

      if (pollRes.status === 404) {
        // Session was closed/completed or expired
        // Check plan status directly
        const planUrl = `${backendBase.replace(/\/$/, '')}/api/v1/automation/plans/${encodeURIComponent(planId)}`;
        const planRes = await fetchImpl(planUrl, { headers: authHeaders ? authHeaders() : {} });
        if (planRes.ok) {
          const planData = await planRes.json();
          if (planData.status === 'READY_TO_SUBMIT' || planData.status === 'COMPLETED') {
            return { resumed: true, status: HandoffStatus.COMPLETED, sessionId };
          }
          if (planData.status === 'FAILED' || planData.status === 'ABANDONED') {
            return { resumed: false, status: HandoffStatus.CANCELLED, sessionId, reason: 'PLAN_' + planData.status };
          }
        }
      } else if (pollRes.ok) {
        const currentSession = await pollRes.json();
        if (currentSession.status === HandoffStatus.COMPLETED) {
          return { resumed: true, status: HandoffStatus.COMPLETED, sessionId };
        }
        if (currentSession.status === HandoffStatus.CANCELLED) {
          return { resumed: false, status: HandoffStatus.CANCELLED, sessionId, reason: 'CANDIDATE_CANCELLED' };
        }
        if (currentSession.status === HandoffStatus.EXPIRED) {
          return { resumed: false, status: HandoffStatus.EXPIRED, sessionId, reason: 'SESSION_EXPIRED' };
        }
      }
    } catch (pollErr) {
      // Network jitter, continue polling
    }
  }

  return { resumed: false, status: HandoffStatus.EXPIRED, sessionId, reason: 'TIMEOUT' };
}
