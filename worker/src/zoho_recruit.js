/**
 * Zoho Recruit career-site application form classification (Robin x Tsenta
 * continuation — second functional ATS adapter).
 *
 * <p>Verified against two independent live Zoho Recruit tenants
 * (`fulcrumdigital.zohorecruit.com`, `execo.zohorecruit.com`, 2026-10-16):
 * a plain HTTP GET of a job's application page returns no {@code <form>} or
 * {@code <input>} markup at all — the apply form is rendered entirely
 * client-side after the visitor clicks the page's "I'm interested" control.
 * Unlike Greenhouse, there is therefore no deterministic STATIC inspection
 * path (see {@code GreenhouseAdapter}'s javadoc for that contrast); field
 * identification can only happen against the live, rendered DOM, which is
 * why this module's counterpart runs inside the worker's browser session
 * (`browser_worker.js#runZohoRecruitApply`) rather than as a backend
 * {@code AtsAdapter.inspectForm()} HTTP call.
 *
 * <p>The field {@code name} attributes Zoho Recruit generates
 * (`rec-form_<tenant-specific-numeric-id>`) are per-tenant — confirmed
 * different between the two sampled tenants for the same semantic field —
 * so they cannot be a stable cross-board contract the way Greenhouse's
 * `#id`s are. This module classifies a field by its RENDERED LABEL TEXT
 * instead: the one thing that stayed consistent (modulo minor wording, e.g.
 * "Email" vs "Email Address") across both tenants sampled.
 *
 * <p>Deliberately conservative. First name, last name, email and — new this
 * continuation — phone/mobile are the only fields ever auto-filled, and
 * phone only the way the candidate's own record already has it: transcribed
 * verbatim, never reformatted or guessed at. The composite phone/mobile
 * widget observed live pairs a country-dial-code search box (placeholder
 * "Search country with dial code") with a separate plain number input;
 * because both sit inside the same labelled group, a naive label match would
 * class BOTH as the phone field — `isCountryDialCodePicker` below excludes
 * the search box by its placeholder before any label match runs, so only the
 * real number input is ever a fill candidate. City, country (no structured
 * per-field candidate data exists for either — Robin's profile stores one
 * free-text `location` string, not separate city/country columns, and
 * splitting it would itself be a guess), a custom non-native "Current Job
 * Title" dropdown, "Experience in Years", "Skill Set", per-tenant salary
 * questions, and LinkedIn (no established, non-guessed source column exists
 * for it in Robin's schema) are all honestly reported as REQUIRES_HUMAN
 * rather than guessed at. A required consent/legal-agreement checkbox
 * (observed on one sampled tenant: data privacy, declaration of accuracy,
 * background-check consent, each with its own per-tenant legal text) is
 * always REQUIRES_HUMAN — Robin never fabricates a candidate's legal
 * consent. A CAPTCHA (also observed on one sampled tenant, directly above
 * the Submit button) is its own category so the worker can hard-stop on it
 * immediately — Robin never attempts to solve or bypass a CAPTCHA.
 *
 * <p>Résumé upload is identified (category RESUME_UPLOAD) and actually
 * attempted when a reviewed CV artifact is available:
 * `browser_worker.js#runZohoRecruitApply` validates the file's
 * accepted-type list, a conservative size cap, and the same
 * job/application/version/sha256 correlation Greenhouse's UPLOAD_FILE step
 * already enforces, before ever calling `setInputFiles`.
 *
 * <p><b>Document-state honesty (Robin x Tsenta hardening).</b> A successful
 * `setInputFiles` call proves only that the browser's own file input now
 * holds the reviewed bytes — it is NOT proof the employer's server received
 * or stored anything, and this adapter never clicks Submit (see
 * `#validateZohoRecruitPlan`), so there is never a point at which Robin
 * could observe real server-side acceptance either. {@link ZohoDocumentUploadState}
 * is the honest state machine this module and `browser_worker.js` report
 * instead of a single overclaiming boolean: `NO_FIELD` (the form has no such
 * control at all — informational, never a gap), `NO_ARTIFACT` (the field
 * exists but no reviewed/approved document is available), `REJECTED` (a
 * real artifact exists but failed validation — wrong accepted type,
 * oversized, checksum mismatch, or the file went missing from disk),
 * `ATTACHED` (the reviewed file was successfully selected into the live
 * browser form — the actual, honest terminal state every successful upload
 * reaches today), and `CONFIRMED` (reserved: would mean a separately
 * verified, employer-side acceptance signal was observed — never produced
 * by any code in this adapter today, since no such signal has ever been
 * traced or tested against a live tenant, and Robin never reaches a submit
 * step to look for one). A candidate-facing surface must always treat
 * `ATTACHED` as "selected, pending your own submission" — never as
 * "uploaded" or "done".
 *
 * <p><b>Cover letter (verified absent on both sampled tenants, 2026-10-18
 * re-check).</b> Re-inspecting both live forms field-by-field found exactly
 * one file-upload control on each, always labelled "Resume *" — no
 * "Cover Letter" field, file or text, exists on either sampled tenant's
 * form. `COVER_LETTER_UPLOAD` is still a real category (matched the same
 * way as `RESUME_UPLOAD`, by an exact "cover letter" label on a file
 * input), so a tenant that DOES expose one is identified and safely
 * handled — but on the two forms actually verified, no such field is ever
 * found, and `#runZohoRecruitApply` reports that honestly
 * (`coverLetterUploadState: 'NO_FIELD'`) rather than inventing a requirement
 * the employer's own form never asked for.
 */

export const ZohoFieldCategory = Object.freeze({
  IDENTITY_FIRST_NAME: 'IDENTITY_FIRST_NAME',
  IDENTITY_LAST_NAME: 'IDENTITY_LAST_NAME',
  IDENTITY_EMAIL: 'IDENTITY_EMAIL',
  IDENTITY_PHONE: 'IDENTITY_PHONE',
  RESUME_UPLOAD: 'RESUME_UPLOAD',
  COVER_LETTER_UPLOAD: 'COVER_LETTER_UPLOAD',
  CAPTCHA: 'CAPTCHA',
  REQUIRES_HUMAN: 'REQUIRES_HUMAN',
  UNRECOGNIZED: 'UNRECOGNIZED',
  // Not a field in its own right from the candidate's point of view — a
  // sub-control of whichever composite widget it belongs to (today: only the
  // phone/mobile country-dial-code search box). Never auto-filled and never
  // reported as its own required gap, since its sibling control already
  // represents that same logical field to the human.
  COMPOSITE_WIDGET_PART: 'COMPOSITE_WIDGET_PART',
});

/**
 * Honest document-upload state (Robin x Tsenta hardening) — see this
 * module's own doc comment for what each value means and why `CONFIRMED` is
 * never produced today. Shared by `browser_worker.js#tryUploadZohoArtifact`
 * (which decides it) and `outcome.js`/the frontend review panel (which
 * render it) — a single source of truth so no caller can reinvent its own,
 * looser notion of "uploaded".
 */
export const ZohoDocumentUploadState = Object.freeze({
  NO_FIELD: 'NO_FIELD',
  NO_ARTIFACT: 'NO_ARTIFACT',
  REJECTED: 'REJECTED',
  ATTACHED: 'ATTACHED',
  CONFIRMED: 'CONFIRMED',
});

/** Categories this module will ever fill automatically — the SUPPORTED_AUTO set. */
export const AUTO_FILLABLE_CATEGORIES = Object.freeze([
  ZohoFieldCategory.IDENTITY_FIRST_NAME,
  ZohoFieldCategory.IDENTITY_LAST_NAME,
  ZohoFieldCategory.IDENTITY_EMAIL,
  ZohoFieldCategory.IDENTITY_PHONE,
]);

/**
 * The composite phone/mobile widget's country-dial-code search box, verified
 * live on two independent tenants with this exact placeholder. Excluded from
 * every auto-fill category unconditionally — it is never the number field,
 * and typing a phone number into it would corrupt the widget's own country
 * picker rather than record a phone number anywhere.
 */
function isCountryDialCodePicker(placeholder) {
  return /search.*country.*dial|country.*dial.*code/i.test(String(placeholder ?? ''));
}

function normalizeLabel(label) {
  return String(label ?? '')
    .replace(/\*\s*$/, '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, ' ');
}

// Exact matches only — evidence-backed label text from the two sampled
// tenants, never a substring guess for the identity/resume/captcha
// categories (a substring match is used only for the deliberately broad
// "captcha" fallback below, since a CAPTCHA must never be missed).
const EXACT_MATCHES = new Map([
  ['first name', ZohoFieldCategory.IDENTITY_FIRST_NAME],
  ['last name', ZohoFieldCategory.IDENTITY_LAST_NAME],
  ['email', ZohoFieldCategory.IDENTITY_EMAIL],
  ['email address', ZohoFieldCategory.IDENTITY_EMAIL],
  ['mobile', ZohoFieldCategory.IDENTITY_PHONE],
  ['phone', ZohoFieldCategory.IDENTITY_PHONE],
  ['mobile number', ZohoFieldCategory.IDENTITY_PHONE],
  ['phone number', ZohoFieldCategory.IDENTITY_PHONE],
  ['resume', ZohoFieldCategory.RESUME_UPLOAD],
  ['résumé', ZohoFieldCategory.RESUME_UPLOAD],
  ['cover letter', ZohoFieldCategory.COVER_LETTER_UPLOAD],
  ['coverletter', ZohoFieldCategory.COVER_LETTER_UPLOAD],
  ['captcha', ZohoFieldCategory.CAPTCHA],
]);

/**
 * @param {string|null} rawLabel the field's rendered label text, with any
 *   trailing required marker (e.g. a `*`) intact
 * @param {string} tag lower-cased tag name: input/select/textarea
 * @param {string|null} htmlType the input's `type` attribute, when it is an input
 * @param {string|null} placeholder the input's `placeholder` attribute
 * @returns {string} one of {@link ZohoFieldCategory}
 */
export function classifyZohoField(rawLabel, tag, htmlType, placeholder = null) {
  // The country-dial-code search box shares its group's "Mobile"/"Phone"
  // label with the real number input (see this module's own comment) — it
  // must never be treated as fillable regardless of what label it inherits.
  if (isCountryDialCodePicker(placeholder)) return ZohoFieldCategory.COMPOSITE_WIDGET_PART;
  const label = normalizeLabel(rawLabel);
  // A required legal/consent checkbox is never auto-checked, regardless of
  // its label wording — checked here before label matching so no label
  // dictionary entry could ever accidentally fill one.
  if (htmlType === 'checkbox') return ZohoFieldCategory.REQUIRES_HUMAN;
  if (!label) return ZohoFieldCategory.UNRECOGNIZED;
  if (EXACT_MATCHES.has(label)) {
    const category = EXACT_MATCHES.get(label);
    if ((category === ZohoFieldCategory.RESUME_UPLOAD || category === ZohoFieldCategory.COVER_LETTER_UPLOAD)
        && htmlType !== 'file') {
      // A field merely labelled "Resume"/"Cover Letter" that isn't a file
      // input is not the upload control itself (could be a text question).
      return ZohoFieldCategory.REQUIRES_HUMAN;
    }
    if (category === ZohoFieldCategory.IDENTITY_PHONE && !['text', 'tel'].includes(htmlType)) {
      return ZohoFieldCategory.REQUIRES_HUMAN;
    }
    return category;
  }
  // Broad, deliberately over-inclusive: any label mentioning "captcha" is
  // treated as a CAPTCHA even if the exact wording differs from what was
  // sampled — missing one would risk an automated solve attempt.
  if (label.includes('captcha')) return ZohoFieldCategory.CAPTCHA;
  return ZohoFieldCategory.REQUIRES_HUMAN;
}

/**
 * Classifies a full inspected field list and decides what is safe to fill.
 * Résumé upload is reported as its own field (category RESUME_UPLOAD,
 * included in `fields`/`requiredGaps` like any other) but never appears in
 * `toFill` — it is not a text value, and whether it can actually be
 * uploaded depends on artifact availability and file-type/size validation
 * browser_worker.js performs separately, with its own I/O.
 *
 * @param {Array<{index:number, rawLabel:string|null, tag:string, type:string|null, placeholder?:string|null, required:boolean}>} fields
 * @param {{firstName?:string, lastName?:string, email?:string, phone?:string}} identity
 * @returns {{captchaPresent: boolean, fields: Array<object>, toFill: Array<{index:number, value:string, category:string}>, requiredGaps: Array<object>}}
 */
export function planZohoFormActions(fields, identity = {}) {
  const valueFor = {
    [ZohoFieldCategory.IDENTITY_FIRST_NAME]: identity.firstName,
    [ZohoFieldCategory.IDENTITY_LAST_NAME]: identity.lastName,
    [ZohoFieldCategory.IDENTITY_EMAIL]: identity.email,
    [ZohoFieldCategory.IDENTITY_PHONE]: identity.phone,
  };
  const classified = fields.map((field) => ({
    ...field,
    category: classifyZohoField(field.rawLabel, field.tag, field.type, field.placeholder),
  }));
  const captchaPresent = classified.some((f) => f.category === ZohoFieldCategory.CAPTCHA);
  const toFill = [];
  const requiredGaps = [];
  if (!captchaPresent) {
    for (const field of classified) {
      const value = valueFor[field.category];
      const fillable = AUTO_FILLABLE_CATEGORIES.includes(field.category)
        && field.category !== ZohoFieldCategory.RESUME_UPLOAD;
      if (fillable && value && String(value).trim()) {
        toFill.push({ index: field.index, value: String(value), category: field.category });
      } else if (field.required && field.category !== ZohoFieldCategory.COMPOSITE_WIDGET_PART) {
        requiredGaps.push({ index: field.index, label: field.rawLabel ?? null, category: field.category });
      }
    }
  }
  return { captchaPresent, fields: classified, toFill, requiredGaps };
}
