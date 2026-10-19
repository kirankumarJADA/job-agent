import test from 'node:test';
import assert from 'node:assert/strict';
import { classifySubmissionAttempt, ConfirmationCategory } from '../src/confirmation.js';

// 1. Genuine positive confirmation — the only way confirmed:true is reachable.
test('a matched, configured confirmation text is the only path to CONFIRMED', () => {
  const result = classifySubmissionAttempt({
    bodyText: '<body>Thanks for applying! Application submitted — reference GH-9921.</body>',
    formFieldsPresent: false,
    invalidFieldCount: 0,
    expectedConfirmationText: 'Application submitted',
  });
  assert.equal(result.category, ConfirmationCategory.CONFIRMED);
  assert.equal(result.confirmed, true);
  assert.match(result.evidence, /Application submitted/);
});

test('confirmation text matching is case-insensitive', () => {
  const result = classifySubmissionAttempt({
    bodyText: 'APPLICATION SUBMITTED SUCCESSFULLY',
    expectedConfirmationText: 'application submitted',
  });
  assert.equal(result.confirmed, true);
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

// 8 (partial, data-level). A worker that crashes after a click but before
// persisting the step result resumes by re-inspecting the page BEFORE
// clicking again (browser_worker.js's #runRealSubmitClick). This proves the
// data-level half of that guarantee: if the page a resumed run observes
// already shows the (configured) confirmation, classification alone is
// enough to avoid a second click — #runRealSubmitClick's own pre-click
// `inspect()` call is what wires this in, and is not re-exercised against a
// live/stub browser in this suite (see the implementation report for why).
test('a page already showing the configured confirmation classifies as CONFIRMED without needing a click — the crash-resume safety property', () => {
  const alreadyConfirmedPage = classifySubmissionAttempt({
    bodyText: 'Application submitted. Reference: GH-4471.',
    formFieldsPresent: false,
    expectedConfirmationText: 'Application submitted',
  });
  assert.equal(alreadyConfirmedPage.confirmed, true);
});
