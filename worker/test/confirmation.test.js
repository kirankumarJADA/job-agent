import test from 'node:test';
import assert from 'node:assert/strict';
import { classifySubmissionAttempt, ConfirmationCategory } from '../src/confirmation.js';

// 1. Genuine positive confirmation — confirmed:true is only ever reachable
// through the mandatory submission-evidence gate (submissionRequestConfirmed),
// corroborated by EITHER the structural URL+DOM pair OR a matching
// expectedConfirmationText. Neither corroborating signal is ever a bypass
// for that gate on its own — see confirmation.js's module comment.
test('employer-specific confirmation text, without matching submission-request evidence, is NOT confirmed', () => {
  const result = classifySubmissionAttempt({
    bodyText: '<body>Thanks for applying! Application submitted — reference GH-9921.</body>',
    formFieldsPresent: false,
    invalidFieldCount: 0,
    expectedConfirmationText: 'Application submitted',
    submissionRequestConfirmed: false,
  });
  assert.equal(result.category, ConfirmationCategory.AMBIGUOUS_NO_CONFIRMATION_CONFIGURED);
  assert.equal(result.confirmed, false);
});

test('employer-specific confirmation text, WITH matching submission-request evidence and the expected confirmation page, IS confirmed', () => {
  const result = classifySubmissionAttempt({
    bodyText: '<body>Thanks for applying! Application submitted — reference GH-9921.</body>',
    formFieldsPresent: false,
    invalidFieldCount: 0,
    expectedConfirmationText: 'Application submitted',
    submissionRequestConfirmed: true,
  });
  assert.equal(result.category, ConfirmationCategory.CONFIRMED);
  assert.equal(result.confirmed, true);
  assert.match(result.evidence, /Application submitted/);
});

test('confirmation text matching is case-insensitive, once the submission-request evidence gate is satisfied', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'APPLICATION SUBMITTED SUCCESSFULLY',
    expectedConfirmationText: 'application submitted',
    submissionRequestConfirmed: true,
  });
  assert.equal(result.confirmed, true);
});

test('a rejected (non-2xx) submission response is NOT confirmed, regardless of matching text or a confirmation-looking page', () => {
  // submissionRequestConfirmed is honestly false here — browser_worker.js's
  // #captureGreenhouseSubmissionDuringClick never sets it true for anything
  // but a verified 2xx response to this exact attempt's own request — so a
  // rejected submission can never pass just because the page it ends up on
  // happens to match the configured text AND the structural URL/DOM pair.
  const result = classifySubmissionAttempt({
    bodyText: 'Thanks for applying! Application submitted — reference GH-9921.',
    formFieldsPresent: false,
    expectedConfirmationText: 'Application submitted',
    confirmationUrlMatched: true,
    confirmationContainerPresent: true,
    submissionRequestConfirmed: false,
  });
  assert.equal(result.confirmed, false);
  assert.notEqual(result.category, ConfirmationCategory.CONFIRMED);
});

// 2. Form disappearance without confirmation — must NOT be treated as proof.
test('the form disappearing, with nothing configured, is ambiguous — never confirmed', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Thank you for your interest.', // generic, not a configured confirmation
    formFieldsPresent: false,
    invalidFieldCount: 0,
    expectedConfirmationText: undefined,
  });
  assert.equal(result.category, ConfirmationCategory.AMBIGUOUS_NO_CONFIRMATION_CONFIGURED);
  assert.equal(result.confirmed, false);
});

test('the form disappearing is still ambiguous even when confirmation text IS configured but not found', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Thank you for your interest.',
    formFieldsPresent: false,
    invalidFieldCount: 0,
    expectedConfirmationText: 'Your application has been received',
    submissionRequestConfirmed: true,
  });
  assert.equal(result.category, ConfirmationCategory.AMBIGUOUS_NO_CONFIRMATION_CONFIGURED);
  assert.equal(result.confirmed, false);
});

// 3. Validation errors after a click.
test('invalid fields remaining on the page classify as VALIDATION_ERROR, never confirmed', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Please correct the errors below.',
    formFieldsPresent: true,
    invalidFieldCount: 2,
    expectedConfirmationText: 'Application submitted',
  });
  assert.equal(result.category, ConfirmationCategory.VALIDATION_ERROR);
  assert.equal(result.confirmed, false);
  assert.match(result.evidence, /2 invalid/);
});

// 4. Login or session walls.
test('a login wall after the click is its own category, not folded into ambiguous', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Please sign in to continue.',
    formFieldsPresent: false,
    invalidFieldCount: 0,
  });
  assert.equal(result.category, ConfirmationCategory.LOGIN_WALL);
  assert.equal(result.confirmed, false);
});

test('a login wall takes priority over a matching confirmation text', () => {
  // Defends against a page that happens to echo configured text inside a
  // login/session-expired interstitial.
  const result = classifySubmissionAttempt({
    bodyText: 'Your session expired. Application submitted status could not be verified — please sign in.',
    expectedConfirmationText: 'Application submitted',
  });
  assert.equal(result.category, ConfirmationCategory.LOGIN_WALL);
  assert.equal(result.confirmed, false);
});

// 5. CAPTCHA and anti-bot pages.
test('an anti-bot/CAPTCHA page is its own category and takes priority over everything else', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Please complete the CAPTCHA to continue. Are you human?',
    expectedConfirmationText: 'Application submitted',
  });
  assert.equal(result.category, ConfirmationCategory.ANTI_BOT);
  assert.equal(result.confirmed, false);
});

test('anti-bot takes priority over a login wall when both patterns are present', () => {
  const result = classifySubmissionAttempt({ bodyText: 'Access denied. Please sign in and complete the CAPTCHA.' });
  assert.equal(result.category, ConfirmationCategory.ANTI_BOT);
});

// Form still present, no errors, no confirmation — the other uncertain case.
test('a form still visibly present with no errors is FORM_STILL_PRESENT, never confirmed', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Application for Software Engineer',
    formFieldsPresent: true,
    invalidFieldCount: 0,
    expectedConfirmationText: 'Application submitted',
  });
  assert.equal(result.category, ConfirmationCategory.FORM_STILL_PRESENT);
  assert.equal(result.confirmed, false);
});

// Defaults / missing input shape.
test('missing/empty input defaults to the safest (ambiguous, unconfirmed) classification', () => {
  const result = classifySubmissionAttempt({});
  assert.equal(result.confirmed, false);
  assert.equal(result.category, ConfirmationCategory.AMBIGUOUS_NO_CONFIRMATION_CONFIGURED);
});

test('blank/whitespace-only configured confirmation text is treated as not configured', () => {
  const result = classifySubmissionAttempt({ bodyText: 'Anything', expectedConfirmationText: '   ' });
  assert.equal(result.confirmed, false);
  assert.equal(result.category, ConfirmationCategory.AMBIGUOUS_NO_CONFIRMATION_CONFIGURED);
});

// Phase 1 blocker fix (false-positive confirmation): a genuinely observed,
// network-correlated 2xx response to this exact attempt's own submission
// request (submissionRequestConfirmed) is the one mandatory gate for EVERY
// path to CONFIRMED — the structural URL+DOM pair above, and
// expectedConfirmationText tested further up, are both only ever
// corroborating signals on top of it, never an independent bypass. These
// tests exercise that gate directly at the pure-function level;
// browser_worker.js's #captureGreenhouseSubmissionDuringClick is what
// gathers submissionRequestConfirmed from real Playwright network events
// (see greenhouse.test.js for the browser-level proof).
test('the confirmation URL and DOM container alone, with no submission request ever observed, are NOT confirmed — the fix for the direct-navigation false positive', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Thanks for applying!',
    formFieldsPresent: false,
    confirmationUrlMatched: true,
    confirmationContainerPresent: true,
    submissionRequestConfirmed: false,
  });
  assert.equal(result.confirmed, false);
  assert.equal(result.category, ConfirmationCategory.AMBIGUOUS_NO_CONFIRMATION_CONFIGURED);
});

test('a verified 2xx submission response plus the matching confirmation URL and DOM container together are CONFIRMED, with no text configured at all', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Thanks for applying!',
    formFieldsPresent: false,
    confirmationUrlMatched: true,
    confirmationContainerPresent: true,
    submissionRequestConfirmed: true,
  });
  assert.equal(result.confirmed, true);
  assert.equal(result.category, ConfirmationCategory.CONFIRMED);
});

test('a verified submission response alone, without landing on the real confirmation URL/DOM, is NOT confirmed', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Something else entirely',
    formFieldsPresent: false,
    confirmationUrlMatched: false,
    confirmationContainerPresent: false,
    submissionRequestConfirmed: true,
  });
  assert.equal(result.confirmed, false);
  assert.equal(result.category, ConfirmationCategory.AMBIGUOUS_NO_CONFIRMATION_CONFIGURED);
});

test('a process restart that finds the browser already sitting on the confirmation route, with no persisted submission evidence, stays ambiguous — never a false success', () => {
  // Mirrors exactly what a resumed worker observes: the live page already
  // shows Greenhouse's own confirmation URL and DOM container (a prior
  // process may genuinely have submitted it), but this fresh process has no
  // network record at all of that submission — submissionRequestConfirmed
  // is honestly false, not a guess either way. The result must require
  // human review (an uncertain category), not silently report SUBMITTED.
  const result = classifySubmissionAttempt({
    bodyText: 'Thanks for applying!',
    formFieldsPresent: false,
    confirmationUrlMatched: true,
    confirmationContainerPresent: true,
    submissionRequestConfirmed: false,
  });
  assert.equal(result.confirmed, false);
  assert.notEqual(result.category, ConfirmationCategory.CONFIRMED);
});

test('a login wall takes priority even when every structural confirmation signal is otherwise present', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'Please sign in to continue.',
    confirmationUrlMatched: true,
    confirmationContainerPresent: true,
    submissionRequestConfirmed: true,
  });
  assert.equal(result.category, ConfirmationCategory.LOGIN_WALL);
  assert.equal(result.confirmed, false);
});

// 8 (superseded). Crash-resume safety used to live here: a resumed run's
// pre-click re-inspection of the live page, with a matching configured
// text, used to classify CONFIRMED on text alone — exactly the
// direct-navigation-style false positive this phase closed. Recovering a
// genuinely confirmed prior outcome after a restart is no longer this
// module's job at all: browser_worker.js's own step-skip/restore logic
// (never re-entering #runRealSubmitClick for an already durably COMPLETED
// step, and restoring its exact persisted classification verbatim instead)
// is the sole legitimate path — see greenhouse.test.js for that proof. This
// module, asked to classify fresh with no submission-request evidence,
// must now stay unconfirmed even on an exact text match.
test('a page already showing the configured confirmation text, with no submission-request evidence for THIS attempt, is NOT confirmed — recovery belongs to persisted evidence, not re-inspection', () => {
  const alreadyConfirmedLookingPage = classifySubmissionAttempt({
    bodyText: 'Application submitted. Reference: GH-4471.',
    formFieldsPresent: false,
    expectedConfirmationText: 'Application submitted',
    submissionRequestConfirmed: false,
  });
  assert.equal(alreadyConfirmedLookingPage.confirmed, false);
});
