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
  // Phase 6: the one step a LEVER plan ever runs beyond NAVIGATE/SCREENSHOT.
  // Unlike Zoho Recruit, Lever's apply form IS server-rendered static HTML
  // (verified live — see ats/AtsAdapters.LeverAdapter's module comment), so
  // the backend already computed the complete, exact field list (selector,
  // value, classification) the same way it does for Greenhouse. This one
  // step executes exactly that backend-computed plan against the live DOM —
  // see browser_worker.js#runLeverApply — and never submits (Lever's real
  // submission is hCaptcha-gated; this adapter does not attempt it).
  LEVER_APPLY: 'LEVER_APPLY',
  // Phase 7: the one step a WORKDAY plan ever runs beyond NAVIGATE/SCREENSHOT/
  // ACCOUNT_RESOLVE_CREDENTIAL. Live inspection established that Workday's
  // apply form is reached only through a Workday-hosted sign-in, rendered
  // entirely client-side, and that the real multi-step APPLICATION form
  // beyond that sign-in was never observed (reaching it needs a real,
  // authenticated tenant session this investigation deliberately did not
  // create) — see ats/AtsAdapters.WorkdayAdapter's module comment and
  // automation/WorkdayExecutionPlanService's own doc for the exact live
  // evidence. This step therefore only verifies the candidate's saved
  // credential against the real sign-in form and stops — see
  // browser_worker.js#runWorkdayLogin.
  WORKDAY_LOGIN: 'WORKDAY_LOGIN',
  // Phase 11: the one step that creates a NEW Workday employer account, on
  // the same sign-in page WORKDAY_LOGIN already verifies against. Only ever
  // appended to a plan by the backend
  // (WorkdayExecutionPlanService#authorizeAccountCreation) after the
  // candidate has explicitly authorized account creation; the step's own
  // termsAuthorized param (never defaulted, never inferred) gates whether
  // the agreement checkbox is checked and the form submitted at all — see
  // browser_worker.js#runWorkdayCreateAccount.
  WORKDAY_CREATE_ACCOUNT: 'WORKDAY_CREATE_ACCOUNT',
  // Phase 14: the one step an ASHBY plan runs beyond NAVIGATE/SCREENSHOT to
  // prepare the application. Ashby's apply form is rendered entirely
  // client-side (live-verified, jobs.ashbyhq.com/ramp — see ashby.js's
  // module comment), reached via its own "Apply for this Job" reveal
  // control, same honest-reveal-then-classify-by-label pattern
  // ZOHO_RECRUIT_APPLY already uses — see browser_worker.js#runAshbyApply.
  ASHBY_APPLY: 'ASHBY_APPLY',
  // Phase 14: Ashby's own real submission action — unlike LEVER_APPLY/
  // ZOHO_RECRUIT_APPLY/WORKDAY_LOGIN, this ATS was found to have no CAPTCHA
  // and no sign-in wall on its public apply form, so a guarded real submit
  // is genuinely supported here. Only ever appended to an already-approved
  // plan by the backend (AutomationPlanRepository#prepareAshbySubmission,
  // mirroring #prepareRealSubmission's exact safety shape) and only ever
  // accepted into an ASHBY plan as the very last step — enforced in
  // #validateAshbyPlan, not merely by convention. See
  // browser_worker.js#runAshbySubmit for the confirmation-evidence model
  // (Ashby's own documented applicationForm.submit response contract, never
  // a live-observed real submission — this project does not submit a real
  // test application).
  ASHBY_SUBMIT_CLICK: 'ASHBY_SUBMIT_CLICK',
  // Phase 19: Workday application form filling and guarded submission
  WORKDAY_APPLY: 'WORKDAY_APPLY',
  WORKDAY_SUBMIT_CLICK: 'WORKDAY_SUBMIT_CLICK',
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

/** The only two hostnames a Greenhouse job-board URL can legitimately use (`boards.greenhouse.io` 301-redirects to `job-boards.greenhouse.io` — verified live). */
const GREENHOUSE_HOSTS = new Set(['boards.greenhouse.io', 'job-boards.greenhouse.io']);

/** The only two hostnames a Lever apply-form URL can legitimately use (EU tenants use the `.eu.` subdomain — verified live against `jobs.lever.co`). */
const LEVER_HOSTS = new Set(['jobs.lever.co', 'jobs.eu.lever.co']);

/**
 * Workday's real host shape (Phase 7): a per-tenant subdomain of
 * myworkdayjobs.com, with an OPTIONAL data-center infix
 * ({@code tenant.wdN.myworkdayjobs.com} or the bare {@code
 * tenant.myworkdayjobs.com}) — mirrors the same two real forms the
 * discovery-side WorkdayAdapter and ats/AtsAdapters.WorkdayAdapter already
 * recognise. Unlike Greenhouse/Lever's fixed host sets, this is tenant-open
 * by design (any employer's own Workday tenant), so identity is verified by
 * host SHAPE plus an exact match against the specific tenant this plan's
 * own targetUrl already named — never a bare suffix check alone.
 */
const WORKDAY_HOST = /^[a-z0-9-]{1,64}\.(?:wd\d{1,3}\.)?myworkdayjobs\.com$/;

// Lever apply-form selectors (Phase 6): the stable, platform-template `name`
// attributes every Lever-hosted form renders (verified live, see
// ats/AtsAdapters.LeverAdapter), plus a closed pattern for the per-posting
// custom-question controls Lever names cards[<cardId>][fieldN] — cardId is
// bounded to an exact UUID shape and fieldN to a small bounded integer, so
// no page-supplied text can ever widen this pattern into an arbitrary
// selector. A [value="..."] suffix (for a specific checkbox/radio option) is
// bounded the same way Greenhouse's own radio-by-value pattern already is.
const leverSelectors = new Set([
  '[name="name"]', '[name="email"]', '[name="phone"]', '[name="location"]', '[name="org"]',
  '[name="urls[LinkedIn]"]', '[name="urls[GitHub]"]', '[name="urls[Portfolio]"]', '[name="urls[Twitter]"]',
  '[name="comments"]', '[name="resume"]', '#resume-upload-input',
]);
const leverPatterns = [
  /^\[name="cards\[[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}]\[field\d{1,3}]"]$/,
  /^\[name="cards\[[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}]\[field\d{1,3}]"]\[value="[A-Za-z0-9_. -]{1,120}"]$/,
];

// Workday sign-in selectors (Phase 7): Workday's OWN platform-wide
// data-automation-id attributes — verified live on a real tenant's real
// sign-in form (nvidia.wd5.myworkdayjobs.com), stable across tenants, unlike
// Zoho Recruit's per-tenant-generated field names. Deliberately excludes
// `[data-automation-id="beecatcher"]` — the honeypot field the same live
// form carries (`name="website"`, labelled "for robots only") — from this
// allowlist entirely: it must never be selectable by any step, by
// construction, not merely by convention.
const workdaySelectors = new Set([
  '[data-automation-id="email"]',
  '[data-automation-id="password"]',
  '[data-automation-id="signInSubmitButton"]',
  // Phase 11: the create-account trio, verified live on the same sign-in
  // page/tenant as the three selectors above.
  '[data-automation-id="verifyPassword"]',
  '[data-automation-id="createAccountCheckbox"]',
  '[data-automation-id="createAccountSubmitButton"]',
  // Phase 19: Workday application navigation and review controls
  '[data-automation-id="bottom-navigation-next-button"]',
  '[data-automation-id="next-button"]',
  '[data-automation-id="review-submit-button"]',
  '[data-automation-id="bottom-navigation-submit-button"]',
]);
const workdayPatterns = [
  // Allows safe Workday [data-automation-id="..."] attributes, strictly excluding beecatcher honeypot
  /^\[data-automation-id="(?!beecatcher")[A-Za-z0-9_.-]{1,80}"\]$/,
  // Permits standard file upload input with data-automation-id
  /^input\[type="file"\]\[data-automation-id="[A-Za-z0-9_.-]{1,80}"\]$/,
];

export function greenhousePageIdentityMatches(expectedUrl, actualUrl) {
  try {
    const expected = new URL(expectedUrl);
    const actual = new URL(actualUrl);
    const defaultPort = (url) => !url.port || url.port === '443';
    return expected.protocol === 'https:' && actual.protocol === 'https:'
      && !expected.username && !expected.password && !actual.username && !actual.password
      && defaultPort(expected) && defaultPort(actual)
      && GREENHOUSE_HOSTS.has(expected.hostname.toLowerCase())
      && GREENHOUSE_HOSTS.has(actual.hostname.toLowerCase())
      && expected.pathname.replace(/\/$/, '') === actual.pathname.replace(/\/$/, '');
  } catch {
    return false;
  }
}

/**
 * Phase 4 hardening (confirmation signal): Greenhouse's own job-board
 * application route (`job-boards.greenhouse.io`, which `boards.greenhouse.io`
 * now 301-redirects to — verified live, 2026-10-19) is a Remix app whose
 * route loader computes a fixed, platform-level redirect target for a
 * successful submission:
 *
 * ```
 * const confirmationPath = `/${urlToken}/jobs/${jobPostId}/confirmation`;
 * ```
 *
 * — read directly from Greenhouse's own publicly-served source map
 * (`app/routes/$url_token_.jobs_.$job_post_id.tsx`, fetched from the live
 * `job-boards.cdn.greenhouse.io` CDN, not guessed or inferred from a single
 * capture). This is computed identically for every employer on the
 * platform — it depends only on the same `urlToken`/`jobPostId` already
 * present in the job's own application URL, never on anything an employer
 * can customise — unlike the confirmation *message* shown on that page
 * (`app/shared/confirmation.tsx`'s own `message` prop), which genuinely is
 * free text the employer configures and must never be hardcoded or guessed.
 *
 * Returns `null` when `url` is not a recognised Greenhouse job-posting URL
 * (`expectedConfirmationPath` is then simply absent, degrading safely to the
 * existing "no verified signal" outcome — never a guess).
 */
export function greenhouseConfirmationPath(url) {
  try {
    const parsed = new URL(url);
    const match = parsed.pathname.replace(/\/$/, '').match(/^\/([^/]+)\/jobs\/([^/]+)$/);
    if (!match) return null;
    const [, urlToken, jobPostId] = match;
    return `/${urlToken}/jobs/${jobPostId}/confirmation`;
  } catch {
    return null;
  }
}

/**
 * The single, self-contained check the worker calls after a real submit
 * click: true only when the live page is genuinely on Greenhouse's own
 * confirmation route for THIS SAME posting — same safety shape as
 * {@link greenhousePageIdentityMatches} (https, no credentials in the URL,
 * default port, an allow-listed Greenhouse host) so a redirect to an
 * attacker-controlled host can never satisfy this just by matching a path
 * string. Returns false (never throws) when `targetUrl` is not a recognised
 * Greenhouse job-posting URL at all.
 */
export function greenhouseConfirmationPageMatches(targetUrl, actualUrl) {
  const expectedPath = greenhouseConfirmationPath(targetUrl);
  if (!expectedPath) return false;
  try {
    const actual = new URL(actualUrl);
    const defaultPort = (url) => !url.port || url.port === '443';
    return actual.protocol === 'https:' && !actual.username && !actual.password
      && defaultPort(actual) && GREENHOUSE_HOSTS.has(actual.hostname.toLowerCase())
      && actual.pathname.replace(/\/$/, '') === expectedPath;
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
  if (leverSelectors.has(selector)) return;
  for (const pattern of leverPatterns) {
    if (pattern.test(selector)) return;
  }
  if (workdaySelectors.has(selector)) return;
  for (const pattern of workdayPatterns) {
    if (pattern.test(selector)) return;
  }
  throw new Error(`UNALLOWLISTED_SELECTOR:${selector}`);
}

/**
 * Same shape and purpose as {@link leverPageIdentityMatches}: re-verify,
 * after a navigation, that the browser is still on the SAME Workday tenant
 * this plan's own targetUrl named (defence against a redirect to an
 * attacker-controlled page) — host shape AND exact tenant match, since
 * Workday tenants are open-ended (any employer), unlike Lever's two fixed
 * hosts.
 */
export function workdayPageIdentityMatches(expectedUrl, actualUrl) {
  try {
    const expected = new URL(expectedUrl);
    const actual = new URL(actualUrl);
    const defaultPort = (url) => !url.port || url.port === '443';
    return expected.protocol === 'https:' && actual.protocol === 'https:'
      && !expected.username && !expected.password && !actual.username && !actual.password
      && defaultPort(expected) && defaultPort(actual)
      && WORKDAY_HOST.test(expected.hostname.toLowerCase())
      && WORKDAY_HOST.test(actual.hostname.toLowerCase())
      && expected.hostname.toLowerCase() === actual.hostname.toLowerCase();
  } catch {
    return false;
  }
}

/**
 * Same shape and purpose as {@link greenhousePageIdentityMatches}: re-verify,
 * after a navigation, that the browser is actually still on the expected
 * Lever host and exact posting path (defence against a redirect to an
 * attacker-controlled page). Lever has exactly two legitimate hostnames
 * (unlike Zoho Recruit's per-tenant subdomains), so this mirrors Greenhouse's
 * exact-path check rather than Zoho's host-suffix-only one.
 */
export function leverPageIdentityMatches(expectedUrl, actualUrl) {
  try {
    const expected = new URL(expectedUrl);
    const actual = new URL(actualUrl);
    const defaultPort = (url) => !url.port || url.port === '443';
    return expected.protocol === 'https:' && actual.protocol === 'https:'
      && !expected.username && !expected.password && !actual.username && !actual.password
      && defaultPort(expected) && defaultPort(actual)
      && LEVER_HOSTS.has(expected.hostname.toLowerCase())
      && LEVER_HOSTS.has(actual.hostname.toLowerCase())
      && expected.pathname.replace(/\/$/, '') === actual.pathname.replace(/\/$/, '');
  } catch {
    return false;
  }
}

/**
 * Same shape and purpose as {@link greenhousePageIdentityMatches}: re-verify,
 * after a navigation, that the browser is still on the real Ashby board host
 * (defence against a redirect to an attacker-controlled page). Ashby has
 * exactly one legitimate browsable hostname across every tenant/board
 * (`jobs.ashbyhq.com/{board}/...`, live-verified against `jobs.ashbyhq.com/ramp`)
 * — so, like Lever, an exact host match; unlike Lever's exact-path match,
 * the path is allowed to extend (e.g. `/apply` → `/apply/application`, the
 * real in-app route the "Apply for this Job" control itself navigates to)
 * since Ashby's own client-side router adds a path segment on reveal.
 */
export function ashbyPageIdentityMatches(expectedUrl, actualUrl) {
  try {
    const expected = new URL(expectedUrl);
    const actual = new URL(actualUrl);
    const defaultPort = (url) => !url.port || url.port === '443';
    const expectedPath = expected.pathname.replace(/\/$/, '');
    const actualPath = actual.pathname.replace(/\/$/, '');
    return expected.protocol === 'https:' && actual.protocol === 'https:'
      && !expected.username && !expected.password && !actual.username && !actual.password
      && defaultPort(expected) && defaultPort(actual)
      && expected.hostname.toLowerCase() === 'jobs.ashbyhq.com'
      && actual.hostname.toLowerCase() === 'jobs.ashbyhq.com'
      && (actualPath === expectedPath || actualPath === `${expectedPath}/application`);
  } catch {
    return false;
  }
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
