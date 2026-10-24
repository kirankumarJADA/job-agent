import test from 'node:test';
import assert from 'node:assert/strict';
import { computePlanOutcome } from '../src/outcome.js';

test('COMPLETED is reserved for a plain inspection plan — never a stand-in for submitted', () => {
  const plan = { planType: 'INSPECTION', steps: [] };
  const { outcome, detail, result } = computePlanOutcome(plan, {});
  assert.equal(outcome, 'COMPLETED');
  assert.equal(detail, 'Inspection completed');
  assert.equal(result, null);
});

test('a GREENHOUSE plan that never attempted REAL_SUBMIT_CLICK always ends HUMAN_REQUIRED, never COMPLETED', () => {
  const plan = { planType: 'GREENHOUSE', steps: [{ type: 'VALIDATE' }], requiredGaps: [{ key: 'phone' }] };
  const { outcome, detail } = computePlanOutcome(plan, {});
  assert.equal(outcome, 'HUMAN_REQUIRED');
  assert.match(detail, /1 outstanding field/);
});

test('a confirmed real submission reports SUBMITTED with the actual evidence, never a guess', () => {
  const plan = { planType: 'GREENHOUSE', steps: [{ type: 'VALIDATE' }, { type: 'REAL_SUBMIT_CLICK' }], requiredGaps: [] };
  const runResult = { submitResult: { confirmed: true, evidence: 'page text matched "Thanks for applying"' } };
  const { outcome, detail, result } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'SUBMITTED');
  assert.match(detail, /Thanks for applying/);
  assert.equal(result, null);
});

test('an unconfirmed real-submit click reports RECONCILIATION_REQUIRED, never a fabricated SUBMITTED', () => {
  const plan = { planType: 'GREENHOUSE', steps: [{ type: 'VALIDATE' }, { type: 'REAL_SUBMIT_CLICK' }], requiredGaps: [] };
  const runResult = { submitResult: { confirmed: false, category: 'FORM_STILL_PRESENT', evidence: 'the form is still on the page' } };
  const { outcome, detail, result } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'RECONCILIATION_REQUIRED');
  assert.match(detail, /FORM_STILL_PRESENT/);
  assert.deepEqual(result, [{
    type: 'SUBMISSION_RECONCILIATION',
    planType: 'GREENHOUSE',
    category: 'FORM_STILL_PRESENT',
    evidence: 'the form is still on the page',
  }]);
});

test('a missing submit result (e.g. a crash before the click recorded anything) is never silently treated as success', () => {
  const plan = { planType: 'GREENHOUSE', steps: [{ type: 'VALIDATE' }, { type: 'REAL_SUBMIT_CLICK' }], requiredGaps: [] };
  const { outcome, detail, result } = computePlanOutcome(plan, {});
  assert.equal(outcome, 'RECONCILIATION_REQUIRED');
  assert.match(detail, /no result was recorded/);
  assert.deepEqual(result, [{
    type: 'SUBMISSION_RECONCILIATION',
    planType: 'GREENHOUSE',
    category: 'UNKNOWN',
    evidence: 'no result was recorded',
  }]);
});

test('a ZOHO_RECRUIT plan always ends HUMAN_REQUIRED, with a structured result the backend can persist', () => {
  const plan = { planType: 'ZOHO_RECRUIT', steps: [], targetUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x' };
  const runResult = {
    zohoRecruitResult: {
      filled: [{ label: 'First Name *', category: 'IDENTITY_FIRST_NAME' }],
      requiredGaps: [{ label: 'Mobile *', category: 'COMPOSITE_WIDGET_PART' }],
      fields: [{ label: 'First Name *', category: 'IDENTITY_FIRST_NAME', required: true }],
      resumeUploadState: 'ATTACHED', resumeUploadReason: null, formFound: true,
    },
  };
  const { outcome, detail, result } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'HUMAN_REQUIRED');
  assert.match(detail, /1 field\(s\) filled automatically/);
  assert.match(detail, /1 field\(s\) need your review/);
  // ATTACHED is never phrased as a completed "uploaded" fact — the detail
  // text must say the candidate still has to submit it themselves.
  assert.match(detail, /résumé attached to the form.*still need to finish and submit/);
  assert.equal(result.length, 1);
  assert.equal(result[0].type, 'ZOHO_RECRUIT_OUTCOME');
  assert.equal(result[0].resumeUploadState, 'ATTACHED');
  assert.deepEqual(result[0].requiredGaps, [{ label: 'Mobile *', category: 'COMPOSITE_WIDGET_PART' }]);
});

test('a ZOHO_RECRUIT plan with no résumé field reports that honestly, not as a gap', () => {
  const plan = { planType: 'ZOHO_RECRUIT', steps: [], targetUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x' };
  const runResult = { zohoRecruitResult: { filled: [], requiredGaps: [], fields: [], resumeUploadState: 'NO_FIELD', resumeUploadReason: null, formFound: true } };
  const { detail } = computePlanOutcome(plan, runResult);
  assert.match(detail, /no résumé field found on this form/);
});

test('a ZOHO_RECRUIT plan whose résumé upload was skipped reports why, not just that it failed', () => {
  const plan = { planType: 'ZOHO_RECRUIT', steps: [], targetUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x' };
  const runResult = {
    zohoRecruitResult: {
      filled: [], requiredGaps: [{ label: 'Resume *', category: 'RESUME_UPLOAD' }], fields: [],
      resumeUploadState: 'NO_ARTIFACT', resumeUploadReason: 'no reviewed résumé is available for this application', formFound: true,
    },
  };
  const { detail } = computePlanOutcome(plan, runResult);
  assert.match(detail, /résumé not attached \(no reviewed résumé is available for this application\)/);
});

test('a ZOHO_RECRUIT plan whose résumé was rejected by validation reports the real reason, not a generic failure', () => {
  const plan = { planType: 'ZOHO_RECRUIT', steps: [], targetUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x' };
  const runResult = {
    zohoRecruitResult: {
      filled: [], requiredGaps: [{ label: 'Resume *', category: 'RESUME_UPLOAD' }], fields: [],
      resumeUploadState: 'REJECTED', resumeUploadReason: 'the file no longer matches its reviewed checksum', formFound: true,
    },
  };
  const { detail } = computePlanOutcome(plan, runResult);
  assert.match(detail, /résumé not attached \(the file no longer matches its reviewed checksum\)/);
});

test('a ZOHO_RECRUIT plan with no cover-letter field on the form stays silent about it, never a fabricated shortfall', () => {
  const plan = { planType: 'ZOHO_RECRUIT', steps: [], targetUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x' };
  const runResult = {
    zohoRecruitResult: {
      filled: [], requiredGaps: [], fields: [], resumeUploadState: 'NO_FIELD', resumeUploadReason: null,
      coverLetterUploadState: 'NO_FIELD', coverLetterUploadReason: null, formFound: true,
    },
  };
  const { detail, result } = computePlanOutcome(plan, runResult);
  assert.doesNotMatch(detail, /cover letter/);
  assert.equal(result[0].coverLetterUploadState, 'NO_FIELD');
});

test('a ZOHO_RECRUIT plan whose cover letter was attached reports it alongside the résumé note, never phrased as a completed upload', () => {
  const plan = { planType: 'ZOHO_RECRUIT', steps: [], targetUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x' };
  const runResult = {
    zohoRecruitResult: {
      filled: [{ label: 'Resume *', category: 'RESUME_UPLOAD' }, { label: 'Cover Letter', category: 'COVER_LETTER_UPLOAD' }],
      requiredGaps: [], fields: [],
      resumeUploadState: 'ATTACHED', resumeUploadReason: null,
      coverLetterUploadState: 'ATTACHED', coverLetterUploadReason: null, formFound: true,
    },
  };
  const { detail, result } = computePlanOutcome(plan, runResult);
  assert.match(detail, /résumé attached to the form/);
  assert.match(detail, /cover letter attached to the form/);
  assert.doesNotMatch(detail, /uploaded automatically/);
  assert.equal(result[0].coverLetterUploadState, 'ATTACHED');
});

test('a ZOHO_RECRUIT plan whose cover letter could not be attached reports why, not just that it failed', () => {
  const plan = { planType: 'ZOHO_RECRUIT', steps: [], targetUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x' };
  const runResult = {
    zohoRecruitResult: {
      filled: [], requiredGaps: [{ label: 'Cover Letter *', category: 'COVER_LETTER_UPLOAD' }], fields: [],
      resumeUploadState: 'NO_FIELD', resumeUploadReason: null,
      coverLetterUploadState: 'NO_ARTIFACT',
      coverLetterUploadReason: 'no approved cover letter is available for this application', formFound: true,
    },
  };
  const { detail } = computePlanOutcome(plan, runResult);
  assert.match(detail, /cover letter not attached \(no approved cover letter is available for this application\)/);
});

test('an absent runResult never crashes the outcome computation for any plan type', () => {
  for (const planType of ['GREENHOUSE', 'ZOHO_RECRUIT', 'ASHBY', 'INSPECTION']) {
    const plan = { planType, steps: [], requiredGaps: [] };
    assert.doesNotThrow(() => computePlanOutcome(plan, {}));
  }
});

// ── Phase 14: ASHBY preparation and real-submission outcomes ────────────

test('an ASHBY plan with no submit step always ends HUMAN_REQUIRED, with a structured result the backend can persist', () => {
  const plan = { planType: 'ASHBY', steps: [{ type: 'ASHBY_APPLY' }], targetUrl: 'https://jobs.ashbyhq.com/ramp/x/application' };
  const runResult = {
    ashbyResult: {
      filled: [{ label: 'Legal Name', category: 'IDENTITY_NAME' }],
      requiredGaps: [{ label: 'Where do you plan on working from?', category: 'REQUIRES_HUMAN' }],
      fields: [{ label: 'Legal Name', category: 'IDENTITY_NAME', required: true }],
      resumeUploadState: 'ATTACHED', resumeUploadReason: null, formFound: true,
    },
  };
  const { outcome, detail, result } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'HUMAN_REQUIRED');
  assert.match(detail, /1 field\(s\) filled automatically/);
  assert.match(detail, /1 field\(s\) need your review/);
  assert.match(detail, /résumé attached to the form.*still need to finish and submit/);
  assert.equal(result.length, 1);
  assert.equal(result[0].type, 'ASHBY_OUTCOME');
  assert.equal(result[0].resumeUploadState, 'ATTACHED');
  assert.deepEqual(result[0].requiredGaps, [{ label: 'Where do you plan on working from?', category: 'REQUIRES_HUMAN' }]);
});

test('an ASHBY plan with no résumé field reports that honestly, not as a gap', () => {
  const plan = { planType: 'ASHBY', steps: [{ type: 'ASHBY_APPLY' }], targetUrl: 'https://jobs.ashbyhq.com/ramp/x/application' };
  const runResult = { ashbyResult: { filled: [], requiredGaps: [], fields: [], resumeUploadState: 'NO_FIELD', resumeUploadReason: null, formFound: true } };
  const { detail } = computePlanOutcome(plan, runResult);
  assert.match(detail, /no résumé field found on this form/);
});

test('an ASHBY plan whose résumé upload was skipped reports why, not just that it failed', () => {
  const plan = { planType: 'ASHBY', steps: [{ type: 'ASHBY_APPLY' }], targetUrl: 'https://jobs.ashbyhq.com/ramp/x/application' };
  const runResult = {
    ashbyResult: {
      filled: [], requiredGaps: [{ label: 'Resume', category: 'RESUME_UPLOAD' }], fields: [],
      resumeUploadState: 'NO_ARTIFACT', resumeUploadReason: 'no reviewed résumé is available for this application', formFound: true,
    },
  };
  const { detail } = computePlanOutcome(plan, runResult);
  assert.match(detail, /résumé not attached \(no reviewed résumé is available for this application\)/);
});

test('a confirmed Ashby real submission reports SUBMITTED with the actual evidence, never a guess', () => {
  const plan = { planType: 'ASHBY', steps: [{ type: 'ASHBY_APPLY' }, { type: 'ASHBY_SUBMIT_CLICK' }], targetUrl: 'https://jobs.ashbyhq.com/ramp/x/application' };
  const runResult = { ashbySubmitResult: { confirmed: true, evidence: 'Ashby’s own submission response reported success:true, not blocked, and the application form is no longer present' } };
  const { outcome, detail, result } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'SUBMITTED');
  assert.match(detail, /success:true, not blocked/);
  assert.equal(result, null);
});

test('an unconfirmed Ashby submit click reports RECONCILIATION_REQUIRED, never a fabricated SUBMITTED', () => {
  const plan = { planType: 'ASHBY', steps: [{ type: 'ASHBY_APPLY' }, { type: 'ASHBY_SUBMIT_CLICK' }], targetUrl: 'https://jobs.ashbyhq.com/ramp/x/application' };
  const runResult = { ashbySubmitResult: { confirmed: false, category: 'UNKNOWN', evidence: 'no matching applicationForm.submit response was observed for this click' } };
  const { outcome, detail, result } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'RECONCILIATION_REQUIRED');
  assert.match(detail, /UNKNOWN/);
  assert.match(detail, /no matching applicationForm\.submit response/);
  assert.deepEqual(result, [{
    type: 'SUBMISSION_RECONCILIATION',
    planType: 'ASHBY',
    category: 'UNKNOWN',
    evidence: 'no matching applicationForm.submit response was observed for this click',
  }]);
});

test('a missing Ashby submit result (e.g. a crash before the click recorded anything) is never silently treated as success', () => {
  const plan = { planType: 'ASHBY', steps: [{ type: 'ASHBY_APPLY' }, { type: 'ASHBY_SUBMIT_CLICK' }], targetUrl: 'https://jobs.ashbyhq.com/ramp/x/application' };
  const { outcome, detail, result } = computePlanOutcome(plan, {});
  assert.equal(outcome, 'RECONCILIATION_REQUIRED');
  assert.match(detail, /no result was recorded/);
  assert.deepEqual(result, [{
    type: 'SUBMISSION_RECONCILIATION',
    planType: 'ASHBY',
    category: 'UNKNOWN',
    evidence: 'no result was recorded',
  }]);
});

test('an ASHBY plan whose last step is ASHBY_APPLY (not a submit click) never reports SUBMITTED even with a stray submitResult', () => {
  const plan = { planType: 'ASHBY', steps: [{ type: 'ASHBY_APPLY' }], targetUrl: 'https://jobs.ashbyhq.com/ramp/x/application' };
  const runResult = { ashbySubmitResult: { confirmed: true, evidence: 'should never be read' }, ashbyResult: { filled: [], requiredGaps: [], fields: [] } };
  const { outcome } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'HUMAN_REQUIRED');
});

// ── Phase 11: WORKDAY account-creation outcomes ──────────────────────────

test('a successful WORKDAY_CREATE_ACCOUNT reports ACCOUNT_CREATED, HUMAN_REQUIRED, and points to the mailbox', () => {
  const plan = { planType: 'WORKDAY', steps: [], accountCreationAttempted: true, targetUrl: 'https://acme.wd1.myworkdayjobs.com/x/apply' };
  const runResult = { workdayResult: { status: 'ACCOUNT_CREATED', reason: null } };
  const { outcome, detail, result } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'HUMAN_REQUIRED');
  assert.match(detail, /created a new Workday account/);
  assert.match(detail, /Check your mailbox/);
  assert.equal(result[0].accountCreationAttempted, true);
  assert.equal(result[0].loginStatus, 'ACCOUNT_CREATED');
});

test('an unauthorized WORKDAY_CREATE_ACCOUNT reports TERMS_NOT_AUTHORIZED with the worker\'s own reason, never a generic failure', () => {
  const plan = { planType: 'WORKDAY', steps: [], accountCreationAttempted: true };
  const runResult = { workdayResult: { status: 'TERMS_NOT_AUTHORIZED', reason: 'account creation was not explicitly authorized for this step' } };
  const { detail, result } = computePlanOutcome(plan, runResult);
  assert.match(detail, /not explicitly authorized/);
  assert.equal(result[0].loginStatus, 'TERMS_NOT_AUTHORIZED');
});

test('a failed WORKDAY_CREATE_ACCOUNT reports the worker\'s own reason honestly', () => {
  const plan = { planType: 'WORKDAY', steps: [], accountCreationAttempted: true };
  const runResult = { workdayResult: { status: 'ACCOUNT_CREATE_FAILED', reason: 'the employer’s site reported that an account already exists for this email' } };
  const { detail, result } = computePlanOutcome(plan, runResult);
  assert.match(detail, /account already exists/);
  assert.equal(result[0].loginStatus, 'ACCOUNT_CREATE_FAILED');
});

test('a missing account-creation result (e.g. a crash) is never silently treated as success', () => {
  const plan = { planType: 'WORKDAY', steps: [], accountCreationAttempted: true };
  const { outcome, detail, result } = computePlanOutcome(plan, {});
  assert.equal(outcome, 'HUMAN_REQUIRED');
  assert.match(detail, /did not produce a result/);
  assert.equal(result[0].loginStatus, 'FORM_NOT_FOUND');
});

test('an account-creation plan never falls back to the CREDENTIAL_REQUIRED login-check phrasing', () => {
  const plan = { planType: 'WORKDAY', steps: [], accountCreationAttempted: true, credentialSaved: false };
  const runResult = { workdayResult: { status: 'ACCOUNT_CREATED', reason: null } };
  const { detail } = computePlanOutcome(plan, runResult);
  assert.doesNotMatch(detail, /CREDENTIAL_REQUIRED/);
  assert.doesNotMatch(detail, /No saved Workday credential exists/);
});
