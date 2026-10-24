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
 * by itself, treated as proof the employer accepted the application.
 *
 * <p><b>One mandatory gate, then two ways to corroborate it — never two
 * independent paths.</b> {@code submissionRequestConfirmed} — a verified 2xx
 * response to THIS attempt's own application-submission request, gathered by
 * {@link browser_worker.js}'s `#captureGreenhouseSubmissionDuringClick` from
 * the live request/response Playwright observes during this exact click,
 * never a prior or unrelated one — is required for {@code confirmed: true}
 * through <em>either</em> corroborating signal below. It is never optional
 * and never bypassable: a page merely navigated or spoofed into a
 * confirmation shape, or one that happens to contain an employer's exact
 * configured confirmation text, with no such response ever observed for this
 * attempt, is never confirmed.
 * <ol>
 *   <li><b>The platform-structural signal (primary, needs no per-employer
 *   configuration at all).</b> Greenhouse's own job-board application route
 *   (`job-boards.greenhouse.io`) is a Remix app whose client-side submit
 *   handler (`app/utils/submit_application.ts`, read from Greenhouse's own
 *   publicly-served source map) issues exactly one real request for a
 *   genuine submission attempt: {@code POST} to the job posting's own
 *   pathname, {@code Content-Type: application/json}, body containing a
 *   {@code job_application} key — and only {@code if (response.ok)} does
 *   {@code application_form.tsx} navigate to the loader's fixed redirect
 *   target, {@code `/${urlToken}/jobs/${jobPostId}/confirmation`}, which
 *   always renders a {@code <div className="confirmation">} container
 *   (`app/shared/confirmation.tsx`). On top of the mandatory gate, both the
 *   confirmation URL ({@code confirmationUrlMatched}) and the confirmation
 *   container ({@code confirmationContainerPresent}) must hold together.</li>
 *   <li><b>An explicit, caller-supplied {@code expectedConfirmationText}</b> —
 *   a second, optional way to corroborate the mandatory gate with a specific
 *   employer's own verified confirmation wording, when one has actually been
 *   captured and configured (that text is genuinely employer-customisable,
 *   unlike the route above, so it is never assumed and never required). It
 *   supplements the submission-response evidence; it can never substitute
 *   for it — a matching text found on a page with no verified submission
 *   response for this attempt is exactly the same unconfirmed case as a bare
 *   navigation.</li>
 * </ol>
 * Neither corroborating signal is available, or the mandatory gate itself
 * never fired, the page is otherwise unremarkable: the attempt resolves to a
 * named, uncertain category instead of a guess — including immediately
 * after a crash/restart, where a fresh browser session has no network
 * evidence at all for what a prior process may have submitted, even if it
 * happens to already be sitting on the confirmation route. Recovering a
 * genuinely confirmed prior outcome after a restart is the durable-evidence
 * concern of `browser_worker.js`'s own step-skip/restore logic, never of a
 * fresh classification here (see its module comment).
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
  // Phase 1 final fix (duplicate-submission prevention): never returned by
  // classifySubmissionAttempt itself — this is a synthetic category
  // browser_worker.js's main loop assigns directly when it finds a
  // REAL_SUBMIT_CLICK step durably stuck at its pre-click ATTEMPTING marker
  // (a prior process crashed somewhere between "about to click" and
  // "outcome durably recorded"). Kept in this shared enum, not a private
  // string in browser_worker.js, so every consumer (outcome.js, tests) can
  // recognise it by the same name.
  PRIOR_ATTEMPT_UNVERIFIED: 'PRIOR_ATTEMPT_UNVERIFIED',
});

/**
 * @param {object} observation
 * @param {string} observation.bodyText - the page's current visible text
 * @param {boolean} observation.formFieldsPresent - whether the apply form's
 *   own required fields (e.g. #first_name, #email) are still on the page
 * @param {number} observation.invalidFieldCount - count of `:invalid` form
 *   controls currently on the page
 * @param {string|undefined} observation.expectedConfirmationText - an
 *   optional, employer-specific positive signal
 * @param {boolean} [observation.confirmationUrlMatched] - true when the
 *   post-click page URL is, verifiably, this exact job posting's own
 *   Greenhouse-computed confirmation route (see
 *   {@link greenhouseConfirmationPageMatches} in `plan.js`) — caller-computed,
 *   since only the caller knows the plan's `targetUrl` and the live
 *   `page.url()`
 * @param {boolean} [observation.confirmationContainerPresent] - true when the
 *   page's DOM contains Greenhouse's own fixed confirmation container
 *   (`.confirmation`, from `app/shared/confirmation.tsx`) — caller-computed
 *   from the real DOM, not from `bodyText`
 * @param {boolean} [observation.submissionRequestConfirmed] - true only when
 *   the caller actually observed, on the live network traffic for THIS
 *   click attempt, a `POST` to the job posting's own pathname carrying a
 *   `job_application` JSON body that resolved with a 2xx response — see
 *   `browser_worker.js`'s `#captureGreenhouseSubmissionDuringClick`. Never
 *   true for a pre-click inspection (no click has happened yet to observe),
 *   and never true after a crash/restart unless a fresh click in the new
 *   process observes it again — a resumed worker that merely finds itself
 *   already on the confirmation route carries no such evidence.
 * @returns {{category: string, confirmed: boolean, evidence: string}}
 */
export function classifySubmissionAttempt({
  bodyText = '',
  formFieldsPresent = false,
  invalidFieldCount = 0,
  expectedConfirmationText,
  confirmationUrlMatched = false,
  confirmationContainerPresent = false,
  submissionRequestConfirmed = false,
} = {}) {
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

  // submissionRequestConfirmed is the one mandatory gate for EVERY path to
  // CONFIRMED below — never optional, never bypassable. Without it, neither
  // the structural URL+DOM pair nor a matching expectedConfirmationText can
  // confirm anything: a direct navigation (or a spoofed/cached page) that
  // happens to show the right URL, DOM, or even the exact configured text,
  // with no verified 2xx response to THIS attempt's own submission request
  // ever observed, is never treated as proof.
  if (submissionRequestConfirmed) {
    // Path 1 (primary, needs no per-employer configuration): the
    // confirmation URL and DOM container together, on top of the now-proven
    // submission request/response pair.
    if (confirmationUrlMatched && confirmationContainerPresent) {
      return { category: ConfirmationCategory.CONFIRMED, confirmed: true,
        evidence: 'a 2xx response to this attempt\'s own application-submission request was observed, and the page navigated to this job posting\'s own Greenhouse-computed confirmation URL and rendered the platform\'s confirmation container' };
    }
    // Path 2 (secondary, optional, supplemental): an explicit,
    // caller-supplied, employer-specific confirmation text — still only
    // ever consulted once the submission request itself is already proven;
    // it can corroborate what the submission response already established,
    // never substitute for it.
    if (expectedConfirmationText) {
      const needle = String(expectedConfirmationText).toLowerCase().trim();
      if (needle && lower.includes(needle)) {
        return { category: ConfirmationCategory.CONFIRMED, confirmed: true,
          evidence: `a 2xx response to this attempt's own application-submission request was observed, and the page text matched the configured confirmation text: "${expectedConfirmationText}"` };
      }
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
