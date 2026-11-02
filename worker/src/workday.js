/**
 * Workday career-site application form classification, multi-step navigation,
 * and submission-outcome classification (Phase 19).
 *
 * <p>Extends Robin's existing Workday sign-in and account-readiness integration
 * into a complete, dependable multi-step application workflow.
 *
 * <p><b>Workday DOM & Field Architecture (Platform-Wide Standards).</b>
 * Workday uses consistent {@code data-automation-id} attributes across its
 * candidate application wizard steps:
 * <ul>
 *   <li><b>Personal Information:</b> {@code legalNameSection_firstName},
 *       {@code legalNameSection_lastName}, {@code email}, {@code phone-number} /
 *       {@code phoneNumber}, {@code addressSection_primaryAddress_addressLine1},
 *       {@code addressSection_primaryAddress_city}, {@code addressSection_primaryAddress_postalCode},
 *       {@code addressSection_primaryAddress_country}.</li>
 *   <li><b>Experience & Documents:</b> {@code file-upload-input-file} /
 *       {@code resume-upload} for CV/résumé attachment; secondary file input for
 *       cover letter.</li>
 *   <li><b>Multi-Step Wizard Controls:</b> {@code bottom-navigation-next-button} /
 *       {@code next-button} for advancing steps; {@code review-submit-button} /
 *       {@code bottom-navigation-submit-button} on the final review screen.</li>
 *   <li><b>Anti-Bot Honeypot:</b> {@code [data-automation-id="beecatcher"]} /
 *       {@code input[name="website"]} — strictly avoided and never filled.</li>
 *   <li><b>CAPTCHA / MFA:</b> Platform anti-bot challenges trigger an immediate
 *       hard stop to protect candidate accounts and security boundaries.</li>
 * </ul>
 *
 * <p><b>Truthfulness & Safety Contract:</b>
 * Only verified candidate identity fields (First Name, Last Name, Email, Phone,
 * Address, LinkedIn) and approved documents (CV, Cover Letter) are auto-filled.
 * Custom questions, voluntary disclosures, and legal/consent checkboxes are
 * never guessed or auto-checked; they are surfaced as explicit human-required gaps.
 */

export const WorkdayFieldCategory = Object.freeze({
  FIRST_NAME: 'FIRST_NAME',
  LAST_NAME: 'LAST_NAME',
  EMAIL: 'EMAIL',
  PHONE: 'PHONE',
  ADDRESS_LINE1: 'ADDRESS_LINE1',
  CITY: 'CITY',
  POSTAL_CODE: 'POSTAL_CODE',
  COUNTRY: 'COUNTRY',
  LINKEDIN: 'LINKEDIN',
  RESUME_UPLOAD: 'RESUME_UPLOAD',
  COVER_LETTER_UPLOAD: 'COVER_LETTER_UPLOAD',
  CAPTCHA: 'CAPTCHA',
  REQUIRES_HUMAN: 'REQUIRES_HUMAN',
  UNRECOGNIZED: 'UNRECOGNIZED',
});

export const WorkdayDocumentUploadState = Object.freeze({
  NO_FIELD: 'NO_FIELD',
  NO_ARTIFACT: 'NO_ARTIFACT',
  REJECTED: 'REJECTED',
  ATTACHED: 'ATTACHED',
  CONFIRMED: 'CONFIRMED',
});

export const WorkdaySubmissionCategory = Object.freeze({
  CONFIRMED: 'CONFIRMED',
  SUBMISSION_REJECTED: 'SUBMISSION_REJECTED',
  VALIDATION_ERROR: 'VALIDATION_ERROR',
  BLOCKED: 'BLOCKED',
  FORM_STILL_PRESENT: 'FORM_STILL_PRESENT',
  ANTI_BOT: 'ANTI_BOT',
  UNKNOWN: 'UNKNOWN',
});

export const AUTO_FILLABLE_WORKDAY_CATEGORIES = Object.freeze([
  WorkdayFieldCategory.FIRST_NAME,
  WorkdayFieldCategory.LAST_NAME,
  WorkdayFieldCategory.EMAIL,
  WorkdayFieldCategory.PHONE,
  WorkdayFieldCategory.ADDRESS_LINE1,
  WorkdayFieldCategory.CITY,
  WorkdayFieldCategory.POSTAL_CODE,
  WorkdayFieldCategory.COUNTRY,
  WorkdayFieldCategory.LINKEDIN,
]);

/**
 * Checks whether an element is Workday's honeypot trap field.
 */
export function isWorkdayHoneypot(field) {
  const automationId = (field?.automationId || field?.getAttribute?.('data-automation-id') || '').toLowerCase();
  const name = (field?.name || field?.getAttribute?.('name') || '').toLowerCase();
  return automationId === 'beecatcher' || name === 'website';
}

/**
 * Classifies a Workday form field by its automation ID, input type, name, and visible label.
 */
export function classifyWorkdayField({ automationId = '', name = '', type = '', label = '', required = false, isFileInput = false } = {}) {
  const autoIdLower = (automationId || '').toLowerCase();
  const nameLower = (name || '').toLowerCase();
  const labelLower = (label || '').toLowerCase();
  const typeLower = (type || '').toLowerCase();

  // 1. Honeypot check: beecatcher must NEVER be matched to an auto-fillable category
  if (autoIdLower === 'beecatcher' || nameLower === 'website') {
    return WorkdayFieldCategory.REQUIRES_HUMAN;
  }

  // 2. CAPTCHA / bot detection
  if (autoIdLower.includes('captcha') || labelLower.includes('captcha') || nameLower.includes('captcha')) {
    return WorkdayFieldCategory.CAPTCHA;
  }

  // 3. Document upload fields
  if (isFileInput || typeLower === 'file' || autoIdLower.includes('file-upload') || autoIdLower.includes('upload')) {
    if (autoIdLower.includes('cover') || labelLower.includes('cover letter') || nameLower.includes('cover')) {
      return WorkdayFieldCategory.COVER_LETTER_UPLOAD;
    }
    if (autoIdLower.includes('resume') || autoIdLower.includes('file-upload') || labelLower.includes('resume') || labelLower.includes('cv') || nameLower.includes('resume')) {
      return WorkdayFieldCategory.RESUME_UPLOAD;
    }
  }

  // 4. Any checkbox or radio button on Workday application forms represents consent,
  // acknowledgements, or survey choices — NEVER auto-check or fabricate answers
  if (typeLower === 'checkbox' || typeLower === 'radio') {
    return WorkdayFieldCategory.REQUIRES_HUMAN;
  }

  // 5. Explicit automation-id mapping (Workday platform standard)
  if (autoIdLower === 'legalnamesection_firstname' || autoIdLower === 'firstname' || (autoIdLower.includes('firstname') && !autoIdLower.includes('last'))) {
    return WorkdayFieldCategory.FIRST_NAME;
  }
  if (autoIdLower === 'legalnamesection_lastname' || autoIdLower === 'lastname' || autoIdLower.includes('lastname')) {
    return WorkdayFieldCategory.LAST_NAME;
  }
  if (autoIdLower === 'email' || autoIdLower.includes('emailaddress') || nameLower === 'email') {
    return WorkdayFieldCategory.EMAIL;
  }
  if (autoIdLower === 'phone-number' || autoIdLower === 'phonenumber' || autoIdLower.includes('phone') || typeLower === 'tel') {
    return WorkdayFieldCategory.PHONE;
  }
  if (autoIdLower.includes('primaryaddress_addressline1') || autoIdLower === 'addressline1') {
    return WorkdayFieldCategory.ADDRESS_LINE1;
  }
  if (autoIdLower.includes('primaryaddress_city') || autoIdLower === 'city') {
    return WorkdayFieldCategory.CITY;
  }
  if (autoIdLower.includes('primaryaddress_postalcode') || autoIdLower === 'postalcode' || autoIdLower === 'zipcode') {
    return WorkdayFieldCategory.POSTAL_CODE;
  }
  if (autoIdLower.includes('primaryaddress_country') || autoIdLower === 'country') {
    return WorkdayFieldCategory.COUNTRY;
  }
  if (autoIdLower.includes('linkedin') || nameLower.includes('linkedin')) {
    return WorkdayFieldCategory.LINKEDIN;
  }

  // 6. Label-based fallback for variations
  if (/^first\s*name/i.test(labelLower)) return WorkdayFieldCategory.FIRST_NAME;
  if (/^last\s*name/i.test(labelLower)) return WorkdayFieldCategory.LAST_NAME;
  if (/^email(\s*address)?/i.test(labelLower)) return WorkdayFieldCategory.EMAIL;
  if (/^phone(\s*number)?|^mobile/i.test(labelLower)) return WorkdayFieldCategory.PHONE;
  if (/^address\s*(line\s*1)?/i.test(labelLower)) return WorkdayFieldCategory.ADDRESS_LINE1;
  if (/^city/i.test(labelLower)) return WorkdayFieldCategory.CITY;
  if (/^postal\s*code|^zip\s*code/i.test(labelLower)) return WorkdayFieldCategory.POSTAL_CODE;
  if (/^country/i.test(labelLower)) return WorkdayFieldCategory.COUNTRY;
  if (/linkedin/i.test(labelLower)) return WorkdayFieldCategory.LINKEDIN;

  // 7. If required but unclassified, it requires human attention
  if (required) {
    return WorkdayFieldCategory.REQUIRES_HUMAN;
  }

  return WorkdayFieldCategory.UNRECOGNIZED;
}

/**
 * Plans form fill actions for a given set of classified Workday fields against verified candidate facts.
 * Produces actions to execute and reports required gaps.
 */
export function planWorkdayFormActions(fields = [], candidate = {}, { hasCvArtifact = false, hasCoverLetterArtifact = false } = {}) {
  const actions = [];
  const requiredGaps = [];
  const filledCategories = new Set();

  for (const field of fields) {
    if (isWorkdayHoneypot(field)) {
      continue;
    }

    const category = classifyWorkdayField(field);

    if (category === WorkdayFieldCategory.CAPTCHA) {
      requiredGaps.push({
        id: field.automationId || field.name || 'captcha',
        label: field.label || 'CAPTCHA verification',
        category,
        reason: 'A CAPTCHA challenge is present and requires human completion',
      });
      continue;
    }

    if (category === WorkdayFieldCategory.RESUME_UPLOAD) {
      if (!hasCvArtifact) {
        if (field.required) {
          requiredGaps.push({
            id: field.automationId || 'resume-upload',
            label: field.label || 'Resume / CV',
            category,
            reason: 'A reviewed CV artifact is required for upload',
          });
        }
      }
      continue;
    }

    if (category === WorkdayFieldCategory.COVER_LETTER_UPLOAD) {
      if (!hasCoverLetterArtifact && field.required) {
        requiredGaps.push({
          id: field.automationId || 'cover-letter-upload',
          label: field.label || 'Cover Letter',
          category,
          reason: 'An approved cover letter artifact is required for upload',
        });
      }
      continue;
    }

    if (category === WorkdayFieldCategory.REQUIRES_HUMAN) {
      if (field.required) {
        requiredGaps.push({
          id: field.automationId || field.name || 'custom-question',
          label: field.label || 'Custom Question / Declaration',
          category,
          reason: 'This question or declaration requires candidate review and cannot be safely automated',
        });
      }
      continue;
    }

    // Auto-fillable category
    let val = null;
    switch (category) {
      case WorkdayFieldCategory.FIRST_NAME:
        val = candidate.firstName || (candidate.name ? candidate.name.split(' ')[0] : null);
        break;
      case WorkdayFieldCategory.LAST_NAME:
        val = candidate.lastName || (candidate.name ? candidate.name.split(' ').slice(1).join(' ') : null);
        break;
      case WorkdayFieldCategory.EMAIL:
        val = candidate.email;
        break;
      case WorkdayFieldCategory.PHONE:
        val = candidate.phone;
        break;
      case WorkdayFieldCategory.ADDRESS_LINE1:
        val = candidate.addressLine1 || candidate.address;
        break;
      case WorkdayFieldCategory.CITY:
        val = candidate.city;
        break;
      case WorkdayFieldCategory.POSTAL_CODE:
        val = candidate.postalCode;
        break;
      case WorkdayFieldCategory.COUNTRY:
        val = candidate.country;
        break;
      case WorkdayFieldCategory.LINKEDIN:
        val = candidate.linkedIn;
        break;
      default:
        val = null;
    }

    if (val && String(val).trim()) {
      actions.push({
        selector: field.selector || (field.automationId ? `[data-automation-id="${field.automationId}"]` : `[name="${field.name}"]`),
        automationId: field.automationId,
        category,
        value: String(val).trim(),
      });
      filledCategories.add(category);
    } else if (field.required) {
      requiredGaps.push({
        id: field.automationId || field.name || category,
        label: field.label || category,
        category,
        reason: `Missing candidate profile value for required field: ${field.label || category}`,
      });
    }
  }

  return {
    actions,
    requiredGaps,
    filledCategories: Array.from(filledCategories),
  };
}

/**
 * Detects the current step in the Workday multi-step application wizard.
 */
export function detectWorkdayStep(pageText = '', automationIds = []) {
  const textLower = pageText.toLowerCase();
  const idSet = new Set((automationIds || []).map((id) => (id || '').toLowerCase()));

  if (idSet.has('review-submit-button') || idSet.has('bottom-navigation-submit-button') || /review your application|review and submit/i.test(textLower)) {
    return 'REVIEW';
  }
  if (idSet.has('legalnamesection_firstname') || /my information|contact information|personal information/i.test(textLower)) {
    return 'MY_INFORMATION';
  }
  if (idSet.has('file-upload-input-file') || idSet.has('resume-upload') || /my experience|work experience|resume\/cv/i.test(textLower)) {
    return 'MY_EXPERIENCE';
  }
  if (/application questions|voluntary disclosures|self identify/i.test(textLower)) {
    return 'APPLICATION_QUESTIONS';
  }

  return 'APPLICATION_STEP';
}

/**
 * Classifies a submission outcome from observed page elements and network response.
 */
export function classifyWorkdaySubmission({ pageText = '', currentUrl = '', hasValidationErrors = false, responseStatus = null, responseBody = null } = {}) {
  const textLower = (pageText || '').toLowerCase();

  // 1. Definite platform confirmation
  if (
    /thank you for applying|application submitted|your application has been submitted|congratulations/i.test(textLower)
    || (currentUrl && (currentUrl.includes('/submitted') || currentUrl.includes('/confirmation') || currentUrl.includes('/thank-you')))
  ) {
    return WorkdaySubmissionCategory.CONFIRMED;
  }

  // 2. Clear validation error
  if (hasValidationErrors || /error|please correct the following|required field|invalid value/i.test(textLower)) {
    return WorkdaySubmissionCategory.VALIDATION_ERROR;
  }

  // 3. Blocked / Anti-bot
  if (/captcha|access denied|blocked|incident id/i.test(textLower)) {
    return WorkdaySubmissionCategory.ANTI_BOT;
  }

  // 4. Form is still present with submit button
  if (/review and submit|review-submit-button/i.test(textLower)) {
    return WorkdaySubmissionCategory.FORM_STILL_PRESENT;
  }

  // 5. Response-based rejection
  if (responseStatus && responseStatus >= 400) {
    return WorkdaySubmissionCategory.SUBMISSION_REJECTED;
  }

  return WorkdaySubmissionCategory.UNKNOWN;
}
