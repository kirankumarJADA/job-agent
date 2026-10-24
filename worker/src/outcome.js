import { ZohoDocumentUploadState } from './zoho_recruit.js';

/**
 * Robin x Tsenta continuation: the one decision every worker run ends with —
 * what outcome, human-readable detail, and structured result the backend's
 * `/complete` endpoint should receive — extracted into a pure function so it
 * can be unit-tested directly, without driving worker.js's full HTTP polling
 * loop (which has no other test surface at all).
 *
 * <p>The property this function exists to guarantee: {@code COMPLETED} is
 * reserved for plan types with no human-review concept whatsoever (a plain
 * screenshot-only inspection plan) and must never be returned for a plan
 * type that prepared something a human still needs to review, finish, or
 * submit — GREENHOUSE and ZOHO_RECRUIT both always resolve to
 * {@code HUMAN_REQUIRED} on a successful run (or, for GREENHOUSE's one real
 * submission attempt, to {@code SUBMITTED} only when the worker's own
 * evidence-based confirmation says so). {@code COMPLETED} is never a
 * synonym for "submitted," and the backend's `/complete` endpoint itself
 * also independently refuses to let a non-worker caller ever report
 * {@code SUBMITTED} at all.
 *
 * <p>The same honesty applies one level down, inside the ZOHO_RECRUIT
 * result: {@link zohoDocumentNote} never phrases {@code ATTACHED} as
 * "uploaded" — it is a file selected into the browser's own form, nothing an
 * employer's server has necessarily seen, and this adapter never submits —
 * so the detail text says so explicitly, every time.
 */
function zohoDocumentNote(kind, state, reason) {
  switch (state) {
    case ZohoDocumentUploadState.NO_FIELD:
      // Informational absence — this form never asked for one. Only the
      // résumé case names it (cover letter's absence stays silent entirely,
      // handled by the caller, since most tenants never show that field).
      return kind === 'résumé' ? `no ${kind} field found on this form` : null;
    case ZohoDocumentUploadState.NO_ARTIFACT:
    case ZohoDocumentUploadState.REJECTED:
      return `${kind} not attached (${reason})`;
    case ZohoDocumentUploadState.ATTACHED:
      return `${kind} attached to the form — you still need to finish and submit this application yourself on the employer's page`;
    case ZohoDocumentUploadState.CONFIRMED:
      return `${kind} uploaded and confirmed by the employer's site`;
    default:
      return null;
  }
}

/**
 * Phase 15: the one shared shape both submit-attempt branches below persist
 * when a real click did NOT reach a confirmed outcome. Deliberately a
 * structured `result` entry (not `null`, as this unconfirmed case used to
 * return) — the backend's new RECONCILIATION_REQUIRED status has nothing
 * else to show the candidate why Robin can never click again automatically,
 * or to decide (in `AutomationController#reconcile`) whether the persisted
 * category is one of the narrow, platform-confirmed-non-acceptance shapes a
 * fresh, separately-authorized attempt is ever permitted from.
 */
function reconciliationResult(planType, submitResult) {
  return [{
    type: 'SUBMISSION_RECONCILIATION', planType,
    category: submitResult?.category ?? 'UNKNOWN',
    evidence: submitResult?.evidence ?? 'no result was recorded',
  }];
}

export function computePlanOutcome(plan, runResult) {
  const isSubmitAttempt = plan.planType === 'GREENHOUSE' && plan.steps.at(-1)?.type === 'REAL_SUBMIT_CLICK';
  if (isSubmitAttempt) {
    const submitResult = runResult.submitResult;
    if (submitResult?.confirmed) {
      return { outcome: 'SUBMITTED', detail: `Submission confirmed: ${submitResult.evidence}`, result: null };
    }
    // Phase 15: never HUMAN_REQUIRED (which the backend's own precondition
    // now treats as a plain, re-approvable preparation outcome) — a real
    // click already happened and may have reached the employer, so this
    // plan can only ever reach RECONCILIATION_REQUIRED, never approved or
    // clicked again from here. See AutomationController#approvalPrecondition
    // and the new /plans/{id}/reconcile endpoint for what happens next.
    return {
      outcome: 'RECONCILIATION_REQUIRED',
      detail: `Submit click completed but was not confirmed [${submitResult?.category ?? 'UNKNOWN'}]: ${submitResult?.evidence
        ?? 'no result was recorded'}; this cannot be retried automatically — human reconciliation is required.`,
      result: reconciliationResult('GREENHOUSE', submitResult),
    };
  }
  // Phase 14: Ashby is the second plan type (after Greenhouse) whose last
  // step can be a real submission click — same honest contract: SUBMITTED
  // is reported only when the worker's own ashbySubmitResult says confirmed,
  // never from a bare click/response alone.
  const isAshbySubmitAttempt = plan.planType === 'ASHBY' && plan.steps.at(-1)?.type === 'ASHBY_SUBMIT_CLICK';
  if (isAshbySubmitAttempt) {
    const submitResult = runResult.ashbySubmitResult;
    if (submitResult?.confirmed) {
      return { outcome: 'SUBMITTED', detail: `Submission confirmed: ${submitResult.evidence}`, result: null };
    }
    // Phase 15: same reasoning as the GREENHOUSE branch above.
    return {
      outcome: 'RECONCILIATION_REQUIRED',
      detail: `Submit click completed but was not confirmed [${submitResult?.category ?? 'UNKNOWN'}]: ${submitResult?.evidence
        ?? 'no result was recorded'}; this cannot be retried automatically — human reconciliation is required.`,
      result: reconciliationResult('ASHBY', submitResult),
    };
  }
  // Phase 19: Workday guarded submission attempt
  const isWorkdaySubmitAttempt = plan.planType === 'WORKDAY' && plan.steps.at(-1)?.type === 'WORKDAY_SUBMIT_CLICK';
  if (isWorkdaySubmitAttempt) {
    const submitResult = runResult.workdaySubmitResult;
    if (submitResult?.confirmed) {
      return { outcome: 'SUBMITTED', detail: `Submission confirmed: ${submitResult.evidence}`, result: null };
    }
    return {
      outcome: 'RECONCILIATION_REQUIRED',
      detail: `Submit click completed but was not confirmed [${submitResult?.category ?? 'UNKNOWN'}]: ${submitResult?.evidence
        ?? 'no result was recorded'}; this cannot be retried automatically — human reconciliation is required.`,
      result: reconciliationResult('WORKDAY', submitResult),
    };
  }
  if (plan.planType === 'ASHBY') {
    const ashby = runResult.ashbyResult ?? {};
    const filledCount = (ashby.filled ?? []).length;
    const gapCount = (ashby.requiredGaps ?? []).length;
    const resumeState = ashby.resumeUploadState ?? ZohoDocumentUploadState.NO_FIELD;
    const resumeNote = zohoDocumentNote('résumé', resumeState, ashby.resumeUploadReason) ?? 'no résumé field found on this form';
    return {
      outcome: 'HUMAN_REQUIRED',
      detail: `Ashby form preparation finished: ${filledCount} field(s) filled automatically, `
        + `${gapCount} field(s) need your review before you can authorize submission; ${resumeNote}.`,
      result: [{
        type: 'ASHBY_OUTCOME', planType: 'ASHBY',
        filled: ashby.filled ?? [], requiredGaps: ashby.requiredGaps ?? [], fields: ashby.fields ?? [],
        resumeUploadState: resumeState, resumeUploadReason: ashby.resumeUploadReason ?? null,
        formFound: ashby.formFound ?? true, applicationUrl: plan.targetUrl,
      }],
    };
  }
  if (plan.planType === 'GREENHOUSE') {
    return {
      outcome: 'HUMAN_REQUIRED',
      detail: `Form preparation finished; human review required (${plan.requiredGaps.length} outstanding field(s))`,
      result: null,
    };
  }
  if (plan.planType === 'WORKDAY') {
    const isApplicationForm = plan.steps.some((s) => s.type === 'WORKDAY_APPLY');
    if (isApplicationForm) {
      const workday = runResult.workdayResult ?? {};
      const filledCount = (workday.filledCategories ?? []).length;
      const gapCount = (workday.requiredGaps ?? []).length;
      return {
        outcome: 'HUMAN_REQUIRED',
        detail: `Workday application preparation finished: ${filledCount} field(s) filled automatically, `
          + `${gapCount} item(s) need your review before you can authorize submission.`,
        result: [{
          type: 'WORKDAY_APPLICATION_OUTCOME',
          planType: 'WORKDAY',
          filledCategories: workday.filledCategories ?? [],
          requiredGaps: workday.requiredGaps ?? [],
          step: workday.step ?? 'APPLICATION_STEP',
          cvUploadState: workday.cvUploadState ?? 'NO_FIELD',
          coverLetterUploadState: workday.coverLetterUploadState ?? 'NO_FIELD',
          applicationUrl: plan.targetUrl,
        }],
      };
    }
    const credentialSaved = plan.credentialSaved === true;
    const accountCreationAttempted = plan.accountCreationAttempted === true;
    const workday = runResult.workdayResult ?? null;
    let loginStatus;
    let detail;
    if (accountCreationAttempted) {
      // Phase 11: an account-creation plan never carries a WORKDAY_LOGIN
      // step (see #validateWorkdayPlan's login/create-account conflict
      // check), so `workday` here is always this run's WORKDAY_CREATE_ACCOUNT
      // result, never a login result.
      if (!workday) {
        loginStatus = 'FORM_NOT_FOUND';
        detail = 'Account creation was authorized, but the attempt did not produce a result.';
      } else if (workday.status === 'ACCOUNT_CREATED') {
        loginStatus = 'ACCOUNT_CREATED';
        detail = 'Robin created a new Workday account for this employer using your registration e-mail and a '
          + 'freshly generated password. Check your mailbox for a verification message, then re-prepare this '
          + 'application so Robin can confirm the saved credential actually works.';
      } else if (workday.status === 'TERMS_NOT_AUTHORIZED') {
        loginStatus = 'TERMS_NOT_AUTHORIZED';
        detail = workday.reason ?? 'Account creation was not explicitly authorized, so nothing was submitted.';
      } else {
        loginStatus = workday.status;
        detail = workday.reason ?? 'Workday account creation did not succeed.';
      }
    } else if (!credentialSaved) {
      loginStatus = 'CREDENTIAL_REQUIRED';
      detail = 'No saved Workday credential exists for this employer, so Robin could not verify sign-in. '
        + 'Save your Workday username and password for this employer, then re-prepare this application.';
    } else if (!workday) {
      loginStatus = 'FORM_NOT_FOUND';
      detail = 'A saved credential exists, but the sign-in attempt did not produce a result.';
    } else if (workday.status === 'LOGIN_SUCCEEDED') {
      loginStatus = 'LOGIN_SUCCEEDED';
      detail = 'Your saved Workday credential was verified against this employer’s real sign-in page. '
        + 'Robin does not fill or submit the Workday application form itself — finish it yourself on the employer’s site.';
    } else {
      loginStatus = workday.status;
      detail = workday.reason ?? 'Workday sign-in did not succeed.';
    }
    return {
      outcome: 'HUMAN_REQUIRED',
      detail: `Workday readiness check: ${detail}`,
      result: [{
        type: 'WORKDAY_OUTCOME', planType: 'WORKDAY',
        credentialSaved, accountCreationAttempted, loginStatus, reason: workday?.reason ?? null,
        applicationUrl: plan.targetUrl,
      }],
    };
  }
  if (plan.planType === 'LEVER') {
    const lever = runResult.leverResult ?? {};
    const filledCount = (lever.filled ?? []).length;
    const gapCount = (lever.requiredGaps ?? []).length;
    const resumeState = lever.resumeUploadState ?? 'NO_FIELD';
    const resumeNote = resumeState === 'NO_FIELD' ? 'no résumé field found on this form'
      : resumeState === 'ATTACHED' ? 'résumé attached to the form — you still need to finish and submit this application yourself on the employer\'s page'
      : `résumé not attached (${lever.resumeUploadReason ?? 'unknown reason'})`;
    return {
      outcome: 'HUMAN_REQUIRED',
      detail: `Lever form preparation finished: ${filledCount} field(s) filled automatically, `
        + `${gapCount} field(s) need your review before you can finish and submit this on the employer's own site; ${resumeNote}.`,
      result: [{
        type: 'LEVER_OUTCOME', planType: 'LEVER',
        filled: lever.filled ?? [], requiredGaps: lever.requiredGaps ?? [], fields: lever.fields ?? [],
        resumeUploadState: resumeState, resumeUploadReason: lever.resumeUploadReason ?? null,
        formFound: lever.formFound ?? true, applicationUrl: plan.targetUrl,
      }],
    };
  }
  if (plan.planType === 'ZOHO_RECRUIT') {
    const zoho = runResult.zohoRecruitResult ?? {};
    const filledCount = (zoho.filled ?? []).length;
    const gapCount = (zoho.requiredGaps ?? []).length;
    const resumeState = zoho.resumeUploadState ?? ZohoDocumentUploadState.NO_FIELD;
    const resumeNote = zohoDocumentNote('résumé', resumeState, zoho.resumeUploadReason) ?? 'no résumé field found on this form';
    // Cover letter's NO_FIELD note is deliberately dropped (stays silent) —
    // most tenants never show this field, and naming its absence every time
    // would read as a fabricated requirement the employer's form never made.
    const coverLetterState = zoho.coverLetterUploadState ?? ZohoDocumentUploadState.NO_FIELD;
    const coverLetterNote = zohoDocumentNote('cover letter', coverLetterState, zoho.coverLetterUploadReason);
    return {
      outcome: 'HUMAN_REQUIRED',
      detail: `Zoho Recruit form preparation finished: ${filledCount} field(s) filled automatically, `
        + `${gapCount} field(s) need your review before you can finish and submit this on the employer's own site; ${resumeNote}`
        + (coverLetterNote ? `; ${coverLetterNote}.` : '.'),
      result: [{
        type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT',
        filled: zoho.filled ?? [], requiredGaps: zoho.requiredGaps ?? [], fields: zoho.fields ?? [],
        resumeUploadState: resumeState, resumeUploadReason: zoho.resumeUploadReason ?? null,
        coverLetterUploadState: coverLetterState, coverLetterUploadReason: zoho.coverLetterUploadReason ?? null,
        formFound: zoho.formFound ?? true, applicationUrl: plan.targetUrl,
      }],
    };
  }
  return { outcome: 'COMPLETED', detail: 'Inspection completed', result: null };
}
