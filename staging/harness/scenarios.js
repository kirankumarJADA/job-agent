/**
 * The Stage 3E §5 controlled validation matrix, unchanged in intent, mapped to
 * the staging fixture's scenario slugs. Scenarios 1–12 must never submit;
 * 13–15 may submit only to the authorised synthetic fixture through the
 * existing approve-submit + prepare-submit workflow.
 */
export const SCENARIOS = Object.freeze([
  { id: 1, name: 'Ordinary employer multi-step form', slug: 's01-multistep', submits: false,
    preconditions: 'prepared UNIVERSAL plan; synthetic profile + PDF CV',
    expected: 'inspect, fill verified fields, upload CV, advance to page 2, pause; zero submit POSTs' },
  { id: 2, name: 'Third-party CDN resources', slug: 's02-cdn', submits: false,
    preconditions: 'fixture page referencing public HTTPS CDN assets plus hostile probes',
    expected: 'public HTTPS assets load; private/loopback/rebound/WS/SW/popup targets refused before connect' },
  { id: 3, name: 'Custom CV and cover-letter upload', slug: 's03-uploads', submits: false,
    preconditions: 'hidden file input behind visible dropzone; PDF-only and DOCX-only variants',
    expected: 'attach SHA-256-verified, <=10 MiB files via the visible path; refuse incompatible accept' },
  { id: 4, name: 'Dynamic conditional fields', slug: 's04-conditional', submits: false,
    preconditions: 'sponsorship answer reveals a mandatory visa field', expected: 're-inspect; new mandatory field never skipped' },
  { id: 5, name: 'Portal-based comboboxes', slug: 's05-combobox', submits: false,
    preconditions: 'ARIA combobox with body-portal listbox', expected: 'exact candidate-confirmed option or a required gap' },
  { id: 6, name: 'Open Shadow DOM', slug: 's06-shadow', submits: false,
    preconditions: 'open shadow root input + closed custom element', expected: 'open fields filled; closed host disclosed' },
  { id: 7, name: 'Login with approved test account', slug: 's07-login', submits: false,
    preconditions: 'encrypted test credential for the exact fixture host', expected: 'exact-host resolve; no secret in logs/state' },
  { id: 8, name: 'MFA handoff and recovery', slug: 's08-mfa', submits: false,
    preconditions: 'MFA challenge page', expected: 'pause, close browser, handoff; no bypass; safe resume' },
  { id: 9, name: 'Unknown mandatory screening questions', slug: 's09-unknown-question', submits: false,
    preconditions: 'mandatory question with no confirmed answer', expected: 'persist question, HUMAN_REQUIRED, approval refused' },
  { id: 10, name: 'Candidate-confirmed answer reuse', slug: 's10-answer-reuse', submits: false,
    preconditions: 'reusable factual answer + non-reusable essay/EEO', expected: 'only eligible factual answers reused' },
  { id: 11, name: 'CAPTCHA pause without bypass', slug: 's11-captcha', submits: false,
    preconditions: 'official Cloudflare Turnstile test-mode widget', expected: 'detect, close, handoff; never interact with the challenge' },
  { id: 12, name: 'HTTP redirect and SSRF protection', slug: 's12-redirect', submits: false,
    preconditions: 'apply URL redirects to the metadata address', expected: 'redirect refused; zero connections to internal ranges' },
  { id: 13, name: 'Submission confirmation detection', slug: 's13-confirmation', submits: true,
    preconditions: 'approve-submit + prepare-submit; negative variants s13-validation-error, s13-ok-false, s13-fake-banner',
    expected: 'SUBMITTED only for 2xx + confirmation page; negatives stay unconfirmed' },
  { id: 14, name: 'Duplicate submission prevention', slug: 's14-duplicate', submits: true,
    preconditions: 'idempotent fixture; crash after PRE_SUBMIT_ATTEMPTING; concurrent attempts',
    expected: 'fixture acceptedSubmissions <= 1; duplicates refused; plan RECONCILIATION_REQUIRED after crash' },
  { id: 15, name: 'Encrypted evidence and application tracking', slug: 's15-evidence', submits: true,
    preconditions: 'completed SUBMITTED run with APP_SECRETS_KEY', expected: 'encrypted evidence; Track APPLIED (Unverified) unless an independent verified receipt exists' },
]);
