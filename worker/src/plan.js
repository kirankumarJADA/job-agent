import crypto from 'node:crypto';
import fs from 'node:fs';

export const StepType = Object.freeze({
  NAVIGATE: 'NAVIGATE',
  FILL_FIELD: 'FILL_FIELD',
  SELECT: 'SELECT',
  CHECK: 'CHECK',
  RADIO: 'RADIO',
  UPLOAD_FILE: 'UPLOAD_FILE',
  CLICK: 'CLICK',
  WAIT_FOR: 'WAIT_FOR',
  SCREENSHOT: 'SCREENSHOT',
  MAILBOX_VERIFY: 'MAILBOX_VERIFY',
  POLICY_CHECK: 'POLICY_CHECK',
  MOCK_SUBMIT: 'MOCK_SUBMIT',
});

export const Policy = Object.freeze({ AUTO: 'AUTO', REQUIRES_APPROVAL: 'REQUIRES_APPROVAL', FORBIDDEN: 'FORBIDDEN' });

const allowedSelectors = new Set([
  'input[name="full_name"]', 'input[name="email"]', 'input[name="password"]',
  'input[name="work_authorized"]', 'input[name="sponsorship"][value="none"]',
  'select[name="country"]', 'textarea[name="why_role"]',
  'input[type="file"][name="cv"]', 'input[type="file"][name="cover_letter"]',
  'button[type="submit"]', 'button[data-next="step2"]', 'button[data-next="submit"]',
  'input[name="otp"]',
]);

export function assertSafeSelector(selector) {
  if (!allowedSelectors.has(selector)) throw new Error(`UNALLOWLISTED_SELECTOR:${selector}`);
}

export function validatePackage(pkg) {
  if (!pkg?.jobId || !pkg?.applicationId || !pkg?.cv?.jobId || !pkg?.coverLetter?.jobId) {
    throw new Error('INVALID_APPLICATION_PACKAGE:missing correlation');
  }
  if (pkg.cv.jobId !== pkg.jobId || pkg.coverLetter.jobId !== pkg.jobId) {
    throw new Error('CROSS_JOB_FILE_CONTAMINATION:artifact job mismatch');
  }
  if ((pkg.cv.applicationId && pkg.cv.applicationId !== pkg.applicationId) ||
      (pkg.coverLetter.applicationId && pkg.coverLetter.applicationId !== pkg.applicationId)) {
    throw new Error('CROSS_APPLICATION_FILE_CONTAMINATION:artifact application mismatch');
  }
  if (!pkg.cv.path || !pkg.coverLetter.path) throw new Error('INVALID_APPLICATION_PACKAGE:missing files');
  if (!pkg.cv.versionId || !pkg.coverLetter.versionId) throw new Error('INVALID_APPLICATION_PACKAGE:missing immutable artifact version');
  if (pkg.cvVersionId && pkg.cv.versionId !== pkg.cvVersionId) throw new Error('CV_VERSION_LINK_MISMATCH:application package');
  if (pkg.coverLetterVersionId && pkg.coverLetter.versionId !== pkg.coverLetterVersionId) throw new Error('COVER_LETTER_VERSION_LINK_MISMATCH:application package');
  if (pkg.cv.versionId && pkg.coverLetter.versionId && pkg.cv.versionId === pkg.coverLetter.versionId) {
    throw new Error('INVALID_APPLICATION_PACKAGE:artifacts must be distinct');
  }
  for (const artifact of [pkg.cv, pkg.coverLetter]) {
    if (artifact.sha256) {
      if (!fs.existsSync(artifact.path)) throw new Error(`FILE_NOT_FOUND:${artifact.path}`);
      const actual = crypto.createHash('sha256').update(fs.readFileSync(artifact.path)).digest('hex');
      if (actual !== artifact.sha256) throw new Error(`PACKAGE_CHECKSUM_MISMATCH:${artifact.versionId ?? artifact.path}`);
    }
  }
  return true;
}

export function evaluatePolicy(step) {
  if (!Object.values(Policy).includes(step.policy)) throw new Error(`UNKNOWN_POLICY:${step.policy}`);
  if (step.policy === Policy.FORBIDDEN) return { action: 'BLOCK', reason: 'FORBIDDEN' };
  if (step.policy === Policy.REQUIRES_APPROVAL) return { action: 'PAUSE', reason: 'REQUIRES_APPROVAL' };
  return { action: 'EXECUTE' };
}

export function buildApplicationPlan({ baseUrl, mailboxUrl, package: pkg }) {
  validatePackage(pkg);
  const correlation = { jobId: pkg.jobId, applicationId: pkg.applicationId, accountId: pkg.accountId };
  const step = (id, type, policy, params = {}) => ({ id, type, policy, params, correlation });
  return {
    planId: `plan-${pkg.applicationId}`,
    version: 1,
    correlation,
    package: pkg,
    steps: [
      step('signup-navigate', StepType.NAVIGATE, Policy.AUTO, { url: `${baseUrl}/signup` }),
      step('signup-full-name', StepType.FILL_FIELD, Policy.AUTO, { selector: 'input[name="full_name"]', value: pkg.candidate.fullName }),
      step('signup-email', StepType.FILL_FIELD, Policy.AUTO, { selector: 'input[name="email"]', value: pkg.accountEmail }),
      step('signup-password', StepType.FILL_FIELD, Policy.AUTO, { selector: 'input[name="password"]', value: pkg.accountPassword }),
      step('signup-submit', StepType.CLICK, Policy.AUTO, { selector: 'button[type="submit"]' }),
      step('verification-email', StepType.MAILBOX_VERIFY, Policy.AUTO, { mailboxUrl, senderDomain: pkg.companyDomain, applicationId: pkg.applicationId }),
      step('verification-otp', StepType.FILL_FIELD, Policy.AUTO, { selector: 'input[name="otp"]', valueFrom: 'verification.otp' }),
      step('verification-submit', StepType.CLICK, Policy.AUTO, { selector: 'button[type="submit"]', expectedText: 'Mock Employer Login' }),
      step('login', StepType.NAVIGATE, Policy.AUTO, { url: `${baseUrl}/login` }),
      step('login-email', StepType.FILL_FIELD, Policy.AUTO, { selector: 'input[name="email"]', value: pkg.accountEmail }),
      step('login-password', StepType.FILL_FIELD, Policy.AUTO, { selector: 'input[name="password"]', value: pkg.accountPassword }),
      step('login-submit', StepType.CLICK, Policy.AUTO, { selector: 'button[type="submit"]' }),
      step('application-step-one', StepType.NAVIGATE, Policy.AUTO, { url: `${baseUrl}/apply/${pkg.jobId}` }),
      step('application-country', StepType.SELECT, Policy.AUTO, { selector: 'select[name="country"]', value: pkg.candidate.country }),
      step('application-authorized', StepType.CHECK, Policy.AUTO, { selector: 'input[name="work_authorized"]', checked: pkg.candidate.workAuthorized }),
      step('application-sponsorship', StepType.RADIO, Policy.AUTO, { selector: `input[name="sponsorship"][value="${pkg.candidate.sponsorship}"]`, value: pkg.candidate.sponsorship }),
      step('application-next', StepType.CLICK, Policy.AUTO, { selector: 'button[data-next="step2"]' }),
      step('application-answer', StepType.FILL_FIELD, Policy.AUTO, { selector: 'textarea[name="why_role"]', value: pkg.answer.text }),
      step('application-cv', StepType.UPLOAD_FILE, Policy.AUTO, { selector: 'input[type="file"][name="cv"]', path: pkg.cv.path, expectedJobId: pkg.jobId, expectedVersionId: pkg.cv.versionId }),
      step('application-cover-letter', StepType.UPLOAD_FILE, Policy.AUTO, { selector: 'input[type="file"][name="cover_letter"]', path: pkg.coverLetter.path, expectedJobId: pkg.jobId, expectedVersionId: pkg.coverLetter.versionId }),
      step('application-policy', StepType.POLICY_CHECK, Policy.AUTO, { action: 'MOCK_SUBMIT', applicationId: pkg.applicationId }),
      step('application-submit', StepType.MOCK_SUBMIT, Policy.REQUIRES_APPROVAL, { selector: 'button[data-next="submit"]', expectedText: 'Application submitted' }),
    ],
  };
}

export function planFingerprint(plan) {
  return crypto.createHash('sha256').update(JSON.stringify(plan)).digest('hex');
}
