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
  ACCOUNT_RESOLVE_IDENTITY: 'ACCOUNT_RESOLVE_IDENTITY',
  // Phase 8.5 continuation: asks the backend (EmployerCredentialService, via
  // the worker-authenticated resolve endpoint) for this candidate's saved
  // portal password for this employer domain, instead of a plaintext
  // password being baked into the plan at build time.
  ACCOUNT_RESOLVE_CREDENTIAL: 'ACCOUNT_RESOLVE_CREDENTIAL',
  ACCOUNT_VERIFY_OTP: 'ACCOUNT_VERIFY_OTP',
  POLICY_CHECK: 'POLICY_CHECK',
  MOCK_SUBMIT: 'MOCK_SUBMIT',
  VALIDATE: 'VALIDATE',
  // Phase 4: the one real, external form-submission action. Distinct from
  // MOCK_SUBMIT (mock-environment only) and from the POLICY_CHECK{action:
  // REAL_SUBMIT} marker (an unconditional hard-stop, unchanged, still fires
  // for anything still using that old marker). This step type is only ever
  // appended to an already-approved plan by the backend
  // (AutomationPlanRepository#prepareRealSubmission) and only ever accepted
  // into a GREENHOUSE plan as the last step, immediately after validate-form
  // — enforced in #validateGreenhousePlan below, not merely by convention.
  REAL_SUBMIT_CLICK: 'REAL_SUBMIT_CLICK',
  // Robin x Tsenta continuation: the one step a ZOHO_RECRUIT plan ever runs
  // beyond NAVIGATE/SCREENSHOT. Zoho Recruit's apply form is rendered
  // entirely client-side (verified against two live tenants — see
  // zoho_recruit.js's module comment), so unlike Greenhouse there are no
  // pre-known field selectors to drive with FILL_FIELD/UPLOAD_FILE: this one
  // step finds the live form, classifies every field by its rendered label,
  // fills only the identity fields it is confident about, and never submits
  // — see browser_worker.js#runZohoRecruitApply.
  ZOHO_RECRUIT_APPLY: 'ZOHO_RECRUIT_APPLY',
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

// Greenhouse apply-form selectors (Phase 3C): exact ids for the verified
// standard fields, closed patterns for Greenhouse's generated question ids
// and radio-by-value groups. Everything else stays rejected.
const greenhouseSelectors = new Set([
  '#first_name', '#last_name', '#email', '#phone', '#country',
  '#candidate-location', '#resume', '#cover_letter', '#portfolio', '#work_auth',
  'input[type=file]#resume', 'input[type=file]#cover_letter',
]);
const greenhousePatterns = [
  /^#[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/,
  /^input\[type=radio\]\[name="[A-Za-z0-9_-]{1,80}"\]\[value="[A-Za-z0-9_. -]{1,120}"\]$/,
];

export function greenhousePageIdentityMatches(expectedUrl, actualUrl) {
  try {
    const expected = new URL(expectedUrl);
    const actual = new URL(actualUrl);
    const allowedHosts = new Set(['boards.greenhouse.io', 'job-boards.greenhouse.io']);
    const defaultPort = (url) => !url.port || url.port === '443';
    return expected.protocol === 'https:' && actual.protocol === 'https:'
      && !expected.username && !expected.password && !actual.username && !actual.password
      && defaultPort(expected) && defaultPort(actual)
      && allowedHosts.has(expected.hostname.toLowerCase())
      && allowedHosts.has(actual.hostname.toLowerCase())
      && expected.pathname.replace(/\/$/, '') === actual.pathname.replace(/\/$/, '');
  } catch {
    return false;
  }
}

/**
 * Same shape and purpose as {@link greenhousePageIdentityMatches}: re-verify,
 * after a navigation, that the browser is actually still on the expected
 * Zoho Recruit host (defence against a redirect to an attacker-controlled
 * page). Zoho Recruit tenants each get their own subdomain
 * (`{tenant}.zohorecruit.com` / `.zohorecruit.in`), so — unlike Greenhouse's
 * two fixed hostnames — this checks a host suffix instead, mirroring the
 * same suffix rule `AtsAdapters.ZohoAdapter#matchesUrl` already enforces
 * server-side.
 */
export function zohoRecruitPageIdentityMatches(expectedUrl, actualUrl) {
  try {
    const expected = new URL(expectedUrl);
    const actual = new URL(actualUrl);
    const defaultPort = (url) => !url.port || url.port === '443';
    const allowedHost = (host) => {
      const h = host.toLowerCase();
      return h === 'zohorecruit.com' || h.endsWith('.zohorecruit.com')
        || h === 'zohorecruit.in' || h.endsWith('.zohorecruit.in');
    };
    return expected.protocol === 'https:' && actual.protocol === 'https:'
      && !expected.username && !expected.password && !actual.username && !actual.password
      && defaultPort(expected) && defaultPort(actual)
      && allowedHost(expected.hostname) && allowedHost(actual.hostname)
      && expected.hostname.toLowerCase() === actual.hostname.toLowerCase();
  } catch {
    return false;
  }
}

export function assertSafeSelector(selector) {
  if (allowedSelectors.has(selector)) return;
  if (greenhouseSelectors.has(selector)) return;
  for (const pattern of greenhousePatterns) {
    if (pattern.test(selector)) return;
  }
  throw new Error(`UNALLOWLISTED_SELECTOR:${selector}`);
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

/**
 * Phase 8.5: employer account lifecycle plan (sign-up, e-mail verification,
 * login). No application is submitted (`safetyContract: 'NO_SUBMIT'`).
 *
 * The account e-mail defaults to the candidate's Robin-managed address: unless
 * the plan names an `accountEmail` explicitly, its first step asks the backend
 * which address to register with (ACCOUNT_RESOLVE_IDENTITY), so no address is
 * baked into the plan and the employer's verification mail reaches Robin
 * without the candidate's personal mailbox. The verification step then reads
 * the code from that same mailbox; anything missing or ambiguous pauses for the
 * human.
 *
 * <p>The account password works the same way: unless the plan names an
 * `accountPassword` explicitly, it is never baked into the plan at build
 * time. Instead an ACCOUNT_RESOLVE_CREDENTIAL step asks the backend
 * (EmployerCredentialService, via its worker-authenticated resolve endpoint)
 * for this candidate's saved portal password for this employer domain, and
 * the password-fill steps read it from that in-memory result
 * (`valueFrom: 'credential.password'`) rather than ever appearing in the
 * plan's own JSON.
 */
export function buildAccountLifecyclePlan({ baseUrl, employerDomain, applicationId, sessionId = null,
                                            accountEmail = null, accountPassword = null, verifyOtp = true }) {
  const step = (id, type, policy, params = {}) => ({ id, type, policy, params });
  const managedIdentity = !accountEmail;
  const resolveCredential = !accountPassword;
  const emailField = (id) => step(id, StepType.FILL_FIELD, Policy.AUTO, managedIdentity
    ? { selector: 'input[name="email"]', valueFrom: 'identity.email' }
    : { selector: 'input[name="email"]', value: accountEmail });
  const passwordField = (id) => step(id, StepType.FILL_FIELD, Policy.AUTO, resolveCredential
    ? { selector: 'input[name="password"]', valueFrom: 'credential.password' }
    : { selector: 'input[name="password"]', value: accountPassword });
  const steps = [];
  if (managedIdentity) {
    steps.push(step('account-resolve-identity', StepType.ACCOUNT_RESOLVE_IDENTITY, Policy.AUTO, { employerDomain, applicationId }));
  }
  if (resolveCredential) {
    steps.push(step('account-resolve-credential', StepType.ACCOUNT_RESOLVE_CREDENTIAL, Policy.AUTO, { employerDomain, applicationId }));
  }
  steps.push(
    step('signup-navigate', StepType.NAVIGATE, Policy.AUTO, { url: `${baseUrl}/signup` }),
    emailField('signup-email'),
    passwordField('signup-password'),
    step('signup-submit', StepType.CLICK, Policy.AUTO, { selector: 'button[type="submit"]' }),
  );
  if (verifyOtp) {
    steps.push(
      step('account-verify-otp', StepType.ACCOUNT_VERIFY_OTP, Policy.AUTO, { employerDomain, applicationId }),
      step('account-verify-otp-fill', StepType.FILL_FIELD, Policy.AUTO, {
        selector: 'input[name="otp"]', valueFrom: 'verification.otp', skipWhenMissing: true,
      }),
      step('account-verify-otp-submit', StepType.CLICK, Policy.AUTO, {
        selector: 'button[type="submit"]', onlyWhenVerificationOtp: true,
      }),
    );
  }
  steps.push(step('login-navigate', StepType.NAVIGATE, Policy.AUTO, { url: `${baseUrl}/login` }),
    emailField('login-email'),
    passwordField('login-password'),
    step('login-submit', StepType.CLICK, Policy.AUTO, { selector: 'button[type="submit"]' }));
  const correlation = { applicationId, employerDomain };
  if (sessionId) correlation.sessionId = sessionId;
  if (!managedIdentity) correlation.mailboxAddress = accountEmail;
  return { planId: `plan-lifecycle-${applicationId}`, version: 1, correlation, steps,
    safetyContract: 'NO_SUBMIT' };
}
