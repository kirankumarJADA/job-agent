/**
 * Ashby career-site application form classification and submission-outcome
 * classification (Phase 14 — the first genuinely submission-capable ATS
 * path added since Greenhouse).
 *
 * <p>Verified live (2026-10-21) against a real, currently-open Ashby posting
 * (`jobs.ashbyhq.com/ramp/{jobId}`, direct navigation to its own
 * `/application` sub-route — confirmed to render the full form immediately,
 * no reveal click needed, unlike Zoho Recruit): the apply form is rendered
 * entirely client-side, same honest boundary `AshbyAdapter`'s own module
 * comment already documents for the backend's static-inspection path.
 *
 * <p><b>DOM shape, verified directly (not guessed).</b> Each question is
 * wrapped in a stable, platform-wide container class
 * (`ashby-application-form-field-entry`, alongside a redeploy-sensitive
 * CSS-module hash — matched here by substring, never the hash), carrying a
 * `data-field-path` attribute. Ashby's own public developer documentation
 * (`developers.ashbyhq.com/reference/applicationformsubmit`) names this same
 * `_systemfield_*` path convention for its `applicationFormValues` request
 * keys — confirming the native hosted board and the public integration API
 * share one real field-identity system, not two unrelated ones. Exactly
 * three STABLE, platform-wide system paths were directly confirmed on the
 * one sampled posting: `_systemfield_name` ("Legal Name", single full-name
 * field — NOT split first/last, unlike every other adapter here, Ashby's
 * own convention), `_systemfield_email` ("Email"), `_systemfield_resume`
 * ("Resume", file upload). A fourth, `_systemfield_location`, was also
 * confirmed present but with its visible label text CUSTOMIZED by this
 * employer to "Where do you plan on working from (for payroll tax
 * purposes)?" — proving the field-path, not the label, is the only reliable
 * identity signal for it, and that it is a consequential, legally-relevant
 * answer with no verified source in Robin's schema: always REQUIRES_HUMAN,
 * keyed by path, regardless of whatever label text an employer puts on it.
 * Phone and LinkedIn were configured on this posting as per-posting CUSTOM
 * questions (a random per-question UUID `field-path`, not a `_systemfield_*`
 * one) — classified by their rendered label text instead (the same
 * discipline `zoho_recruit.js` already uses for Zoho's own, differently
 * per-tenant, field identifiers), since a custom question's path cannot be a
 * stable cross-tenant contract. An SMS-consent Yes/No radio pair (optional,
 * no required marker observed) shared the same field-entry container as the
 * Phone question on this posting; a CAPTCHA was not present anywhere on it.
 *
 * <p>Deliberately conservative, same discipline as `zoho_recruit.js`: only
 * Legal Name, Email, Phone and LinkedIn are ever auto-filled, from the same
 * verified `profiles` sources Lever/Zoho already draw from. Any `<input
 * type="checkbox">` or `<input type="radio">` is always REQUIRES_HUMAN,
 * checked before any path or label match, so no dictionary entry could ever
 * accidentally fill or select one — Robin never fabricates a candidate's
 * consent either way. This was evidenced on exactly one tenant/posting —
 * stated precisely, not inflated into "verified across Ashby."
 *
 * <p><b>Submission (Phase 14, new).</b> Ashby's own public developer
 * documentation explicitly documents that `applicationForm.submit` returns
 * HTTP 200 for BOTH success and failure — the real signal is the response
 * BODY's own `success`/`results.formMessages.blocked` fields, never the bare
 * HTTP status. This project never submitted a real test application (per
 * the task's own explicit instruction), so the exact live network contract
 * the native hosted board's own click handler produces was never directly
 * observed — `classifyAshbySubmission` below therefore requires Ashby's own
 * documented success contract AND that the application form itself is no
 * longer present, never a bare 2xx and never page text alone. Anything that
 * does not match this exact, documented shape falls through to an honest
 * `UNKNOWN`, never a guessed success.
 */

export const AshbyFieldCategory = Object.freeze({
  IDENTITY_NAME: 'IDENTITY_NAME',
  IDENTITY_EMAIL: 'IDENTITY_EMAIL',
  IDENTITY_PHONE: 'IDENTITY_PHONE',
  IDENTITY_LINKEDIN: 'IDENTITY_LINKEDIN',
  RESUME_UPLOAD: 'RESUME_UPLOAD',
  CAPTCHA: 'CAPTCHA',
  REQUIRES_HUMAN: 'REQUIRES_HUMAN',
  UNRECOGNIZED: 'UNRECOGNIZED',
});

/** Mirrors `ZohoDocumentUploadState` exactly — see that module's own doc for what each value means and why CONFIRMED is never produced by this module. */
export const AshbyDocumentUploadState = Object.freeze({
  NO_FIELD: 'NO_FIELD',
  NO_ARTIFACT: 'NO_ARTIFACT',
  REJECTED: 'REJECTED',
  ATTACHED: 'ATTACHED',
  CONFIRMED: 'CONFIRMED',
});

export const AshbySubmissionCategory = Object.freeze({
  CONFIRMED: 'CONFIRMED',
  SUBMISSION_REJECTED: 'SUBMISSION_REJECTED',
  BLOCKED: 'BLOCKED',
  FORM_STILL_PRESENT: 'FORM_STILL_PRESENT',
  ANTI_BOT: 'ANTI_BOT',
  UNKNOWN: 'UNKNOWN',
});

/** Categories this module will ever fill automatically — the SUPPORTED_AUTO set. */
export const AUTO_FILLABLE_CATEGORIES = Object.freeze([
  AshbyFieldCategory.IDENTITY_NAME,
  AshbyFieldCategory.IDENTITY_EMAIL,
  AshbyFieldCategory.IDENTITY_PHONE,
  AshbyFieldCategory.IDENTITY_LINKEDIN,
]);

/** Live-verified stable `data-field-path` values — see this module's own doc comment. */
const SYSTEM_FIELD_PATHS = new Map([
  ['_systemfield_name', AshbyFieldCategory.IDENTITY_NAME],
  ['_systemfield_email', AshbyFieldCategory.IDENTITY_EMAIL],
  ['_systemfield_resume', AshbyFieldCategory.RESUME_UPLOAD],
]);

/**
 * `_systemfield_location` (Ashby's stable payroll-tax work-location
 * question) is deliberately never auto-filled, keyed by its field path —
 * not its label, which was live-verified to be employer-customizable. A
 * free-text `profiles.location` string is not the same as a payroll-tax
 * jurisdiction declaration, and this is exactly the kind of consequential
 * answer Robin must never guess.
 */
const ALWAYS_HUMAN_FIELD_PATHS = new Set(['_systemfield_location']);

function normalizeLabel(label) {
  return String(label ?? '')
    .replace(/\*\s*$/, '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, ' ');
}

// Exact matches only, for CUSTOM (per-posting) questions whose field path is
// not a stable cross-tenant contract — evidence-backed label text from the
// one sampled posting, never a substring guess for the identity/resume
// categories (a substring match is used only for the deliberately broad
// "captcha" fallback below, since a CAPTCHA must never be missed).
const LABEL_MATCHES = new Map([
  ['legal name', AshbyFieldCategory.IDENTITY_NAME],
  ['name', AshbyFieldCategory.IDENTITY_NAME],
  ['email', AshbyFieldCategory.IDENTITY_EMAIL],
  ['phone', AshbyFieldCategory.IDENTITY_PHONE],
  ['linkedin profile', AshbyFieldCategory.IDENTITY_LINKEDIN],
  ['linkedin', AshbyFieldCategory.IDENTITY_LINKEDIN],
  ['resume', AshbyFieldCategory.RESUME_UPLOAD],
  ['résumé', AshbyFieldCategory.RESUME_UPLOAD],
]);

/**
 * @param {string|null} fieldPath the enclosing field-entry's `data-field-path` attribute
 * @param {string|null} rawLabel the field's rendered label text, with any trailing required marker intact
 * @param {string} tag lower-cased tag name: input/select/textarea
 * @param {string|null} htmlType the input's `type` attribute, when it is an input
 * @returns {string} one of {@link AshbyFieldCategory}
 */
export function classifyAshbyField(fieldPath, rawLabel, tag, htmlType) {
  // A radio button (the SMS-consent Yes/No pair) or a checkbox is never
  // auto-selected, regardless of its path or label wording — checked before
  // any other match so nothing could ever accidentally fill or select one.
  // Robin never fabricates a candidate's consent either way.
  if (htmlType === 'radio' || htmlType === 'checkbox') return AshbyFieldCategory.REQUIRES_HUMAN;
  if (fieldPath && ALWAYS_HUMAN_FIELD_PATHS.has(fieldPath)) return AshbyFieldCategory.REQUIRES_HUMAN;
  if (fieldPath && SYSTEM_FIELD_PATHS.has(fieldPath)) {
    const category = SYSTEM_FIELD_PATHS.get(fieldPath);
    if (category === AshbyFieldCategory.RESUME_UPLOAD && htmlType !== 'file') return AshbyFieldCategory.REQUIRES_HUMAN;
    if (category === AshbyFieldCategory.IDENTITY_NAME && htmlType !== 'text') return AshbyFieldCategory.REQUIRES_HUMAN;
    return category;
  }
  const label = normalizeLabel(rawLabel);
  if (!label) return AshbyFieldCategory.UNRECOGNIZED;
  if (LABEL_MATCHES.has(label)) {
    const category = LABEL_MATCHES.get(label);
    if (category === AshbyFieldCategory.RESUME_UPLOAD && htmlType !== 'file') {
      // A field merely labelled "Resume" that isn't a file input is not the
      // upload control itself (could be a text question).
      return AshbyFieldCategory.REQUIRES_HUMAN;
    }
    if (category === AshbyFieldCategory.IDENTITY_PHONE && !['text', 'tel'].includes(htmlType)) {
      return AshbyFieldCategory.REQUIRES_HUMAN;
    }
    if (category === AshbyFieldCategory.IDENTITY_LINKEDIN && !['text', 'url'].includes(htmlType)) {
      return AshbyFieldCategory.REQUIRES_HUMAN;
    }
    if (category === AshbyFieldCategory.IDENTITY_NAME && htmlType !== 'text') {
      return AshbyFieldCategory.REQUIRES_HUMAN;
    }
    return category;
  }
  // Broad, deliberately over-inclusive: any label mentioning "captcha" is
  // treated as a CAPTCHA even if the exact wording differs from what was
  // sampled — missing one would risk an automated solve attempt.
  if (label.includes('captcha')) return AshbyFieldCategory.CAPTCHA;
  // Everything else (any other per-posting custom question, a `select`/
  // combobox) is honestly reported as REQUIRES_HUMAN rather than guessed at.
  return AshbyFieldCategory.REQUIRES_HUMAN;
}

/**
 * Classifies a full inspected field list and decides what is safe to fill.
 * Résumé upload is reported as its own field (category RESUME_UPLOAD,
 * included in `fields`/`requiredGaps` like any other) but never appears in
 * `toFill` — it is not a text value; whether it can actually be uploaded
 * depends on artifact availability and file-type/size validation
 * browser_worker.js performs separately, with its own I/O.
 *
 * @param {Array<{index:number, fieldPath:string|null, rawLabel:string|null, tag:string, type:string|null, required:boolean}>} fields
 * @param {{name?:string, email?:string, phone?:string, linkedIn?:string}} identity
 * @returns {{captchaPresent: boolean, fields: Array<object>, toFill: Array<{index:number, value:string, category:string}>, requiredGaps: Array<object>}}
 */
export function planAshbyFormActions(fields, identity = {}) {
  const valueFor = {
    [AshbyFieldCategory.IDENTITY_NAME]: identity.name,
    [AshbyFieldCategory.IDENTITY_EMAIL]: identity.email,
    [AshbyFieldCategory.IDENTITY_PHONE]: identity.phone,
    [AshbyFieldCategory.IDENTITY_LINKEDIN]: identity.linkedIn,
  };
  const classified = fields.map((field) => ({
    ...field,
    category: classifyAshbyField(field.fieldPath, field.rawLabel, field.tag, field.type),
  }));
  const captchaPresent = classified.some((f) => f.category === AshbyFieldCategory.CAPTCHA);
  const toFill = [];
  const requiredGaps = [];
  if (!captchaPresent) {
    for (const field of classified) {
      const value = valueFor[field.category];
      const fillable = AUTO_FILLABLE_CATEGORIES.includes(field.category);
      if (fillable && value && String(value).trim()) {
        toFill.push({ index: field.index, value: String(value), category: field.category });
      } else if (field.required) {
        requiredGaps.push({ index: field.index, label: field.rawLabel ?? null, category: field.category });
      }
    }
  }
  return { captchaPresent, fields: classified, toFill, requiredGaps };
}

/**
 * The one honest classifier for whether a real Ashby submit click actually
 * succeeded — see this module's own doc comment for the exact evidentiary
 * basis (Ashby's own documented response contract, never a live-observed
 * real submission). Requires BOTH the documented network success signal AND
 * the application form no longer being present — mirrors this codebase's
 * existing rule (`confirmation.js`, Greenhouse) that a network signal alone,
 * without corroborating page-state evidence, is never enough.
 *
 * @param {{networkSuccess: boolean|undefined, networkBlocked: boolean|undefined, formFieldsPresent: boolean, antiBotPresent: boolean}} params
 *   `networkSuccess`/`networkBlocked` are Ashby's own documented
 *   `success`/`results.formMessages.blocked` response fields, read from the
 *   real response this exact click caused — `undefined` when no matching
 *   response was ever observed at all (never a guess, never defaulted to
 *   either boolean).
 */
export function classifyAshbySubmission({ networkSuccess, networkBlocked, formFieldsPresent, antiBotPresent }) {
  if (antiBotPresent) {
    return {
      confirmed: false, category: AshbySubmissionCategory.ANTI_BOT,
      evidence: 'a CAPTCHA or anti-bot challenge appeared after the submit click',
    };
  }
  if (networkSuccess === undefined) {
    return {
      confirmed: false, category: AshbySubmissionCategory.UNKNOWN,
      evidence: 'no matching applicationForm.submit response was observed for this click — the outcome is unknown, never assumed',
    };
  }
  if (networkSuccess === false) {
    return {
      confirmed: false, category: AshbySubmissionCategory.SUBMISSION_REJECTED,
      evidence: 'Ashby’s own submission response reported success:false',
    };
  }
  if (networkBlocked === true) {
    return {
      confirmed: false, category: AshbySubmissionCategory.BLOCKED,
      evidence: 'Ashby’s own submission response reported success:true but formMessages.blocked:true',
    };
  }
  if (formFieldsPresent) {
    return {
      confirmed: false, category: AshbySubmissionCategory.FORM_STILL_PRESENT,
      evidence: 'the submission response reported success, but the application form is still present on the page',
    };
  }
  return {
    confirmed: true, category: AshbySubmissionCategory.CONFIRMED,
    evidence: 'Ashby’s own submission response reported success:true, not blocked, and the application form is no longer present',
  };
}
