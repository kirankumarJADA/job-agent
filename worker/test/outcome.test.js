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

test('an unconfirmed real-submit click reports HUMAN_REQUIRED, never a fabricated SUBMITTED', () => {
  const plan = { planType: 'GREENHOUSE', steps: [{ type: 'VALIDATE' }, { type: 'REAL_SUBMIT_CLICK' }], requiredGaps: [] };
  const runResult = { submitResult: { confirmed: false, category: 'FORM_STILL_PRESENT', evidence: 'the form is still on the page' } };
  const { outcome, detail } = computePlanOutcome(plan, runResult);
  assert.equal(outcome, 'HUMAN_REQUIRED');
  assert.match(detail, /FORM_STILL_PRESENT/);
});

test('a missing submit result (e.g. a crash before the click recorded anything) is never silently treated as success', () => {
  const plan = { planType: 'GREENHOUSE', steps: [{ type: 'VALIDATE' }, { type: 'REAL_SUBMIT_CLICK' }], requiredGaps: [] };
  const { outcome, detail } = computePlanOutcome(plan, {});
  assert.equal(outcome, 'HUMAN_REQUIRED');
  assert.match(detail, /no result was recorded/);
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
  for (const planType of ['GREENHOUSE', 'ZOHO_RECRUIT', 'INSPECTION']) {
    const plan = { planType, steps: [], requiredGaps: [] };
    assert.doesNotThrow(() => computePlanOutcome(plan, {}));
  }
});
