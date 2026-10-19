/**
 * Phase 4 (real Greenhouse submission): classification of what a page looks
 * like immediately after a REAL_SUBMIT_CLICK attempt.
 *
 * <p>This module is deliberately pure — plain data in, a classification out,
 * no Playwright dependency — so every branch can be unit-tested directly
 * without a browser. {@link browser_worker.js}'s `#runRealSubmitClick` is the
 * only caller that gathers the real page data this takes as input.
 *
 * <p>The one rule every branch below exists to enforce: a disappeared
 * application form, a generic page change, or "no error occurred" is never,
 * by itself, treated as proof the employer accepted the application. The
 * ONLY way `confirmed: true` is ever returned is an explicit, caller-supplied
 * `expectedConfirmationText` actually being found on the page. Until a real
 * Greenhouse confirmation page's own text has been verified and configured
 * for a given plan, this function cannot return `confirmed: true` at all —
 * every attempt resolves to a named, uncertain category instead. This is a
 * deliberate, structural safety gate, not an oversight: see
 * `docs/ROBIN_BUILD_STATUS.md` (or the equivalent implementation notes) for
 * why real submission stays gated until that text is known.
 */

const ANTI_BOT_PATTERN = /captcha|cloudflare|are you human|anti[- ]bot|access denied/i;
const LOGIN_WALL_PATTERN = /sign in|log in|session expired|authentication required/i;

export const ConfirmationCategory = Object.freeze({
  CONFIRMED: 'CONFIRMED',
  ANTI_BOT: 'ANTI_BOT',
  LOGIN_WALL: 'LOGIN_WALL',
  VALIDATION_ERROR: 'VALIDATION_ERROR',
  FORM_STILL_PRESENT: 'FORM_STILL_PRESENT',
  AMBIGUOUS_NO_CONFIRMATION_CONFIGURED: 'AMBIGUOUS_NO_CONFIRMATION_CONFIGURED',
});

/**
 * @param {object} observation
 * @param {string} observation.bodyText - the page's current visible text
 * @param {boolean} observation.formFieldsPresent - whether the apply form's
 *   own required fields (e.g. #first_name, #email) are still on the page
 * @param {number} observation.invalidFieldCount - count of `:invalid` form
 *   controls currently on the page
 * @param {string|undefined} observation.expectedConfirmationText - the one
 *   positive signal this function will ever accept; optional and, by
 *   design, usually absent today
 * @returns {{category: string, confirmed: boolean, evidence: string}}
 */
export function classifySubmissionAttempt({ bodyText = '', formFieldsPresent = false, invalidFieldCount = 0, expectedConfirmationText } = {}) {
  const lower = String(bodyText).toLowerCase();

  // Anti-bot and login/session walls take priority over every other signal:
  // either one appearing after a click is anomalous (a correctly-filled,
  // already-VALIDATEd form should never hit either), so both are reported
  // as their own named category rather than folded into "ambiguous" —
  // callers are expected to treat these as hard stops, not as a retryable
  // uncertain outcome, exactly like the equivalent pre-existing checks in
  // the VALIDATE step.
  if (ANTI_BOT_PATTERN.test(lower)) {
    return { category: ConfirmationCategory.ANTI_BOT, confirmed: false,
      evidence: 'an anti-bot/CAPTCHA signal was detected on the page after the click' };
  }
  if (LOGIN_WALL_PATTERN.test(lower)) {
    return { category: ConfirmationCategory.LOGIN_WALL, confirmed: false,
      evidence: 'the page shows a login/session wall after the click, not a confirmation' };
  }

  // The one and only path to a positive result.
  if (expectedConfirmationText) {
    const needle = String(expectedConfirmationText).toLowerCase().trim();
    if (needle && lower.includes(needle)) {
      return { category: ConfirmationCategory.CONFIRMED, confirmed: true,
        evidence: `page text matched the configured confirmation text: "${expectedConfirmationText}"` };
    }
  }

  if (invalidFieldCount > 0) {
    return { category: ConfirmationCategory.VALIDATION_ERROR, confirmed: false,
      evidence: `${invalidFieldCount} invalid form field(s) remain on the page; the submission did not go through` };
  }
  if (formFieldsPresent) {
    return { category: ConfirmationCategory.FORM_STILL_PRESENT, confirmed: false,
      evidence: 'the application form is still present after the click; no submission is evidenced' };
  }
  // The form is gone, nothing looks wrong, but — the one deliberate
  // restriction this whole module exists to enforce — that alone is not
  // proof of anything. No configured confirmation text means there is
  // currently no verified positive signal for this ATS at all.
  return { category: ConfirmationCategory.AMBIGUOUS_NO_CONFIRMATION_CONFIGURED, confirmed: false,
    evidence: 'the application form is no longer present, but no verified confirmation signal is configured for this ATS yet; treated as unconfirmed by design, not retried automatically' };
}
