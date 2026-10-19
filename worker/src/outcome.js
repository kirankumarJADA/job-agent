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

export function computePlanOutcome(plan, runResult) {
  const isSubmitAttempt = plan.planType === 'GREENHOUSE' && plan.steps.at(-1)?.type === 'REAL_SUBMIT_CLICK';
  if (isSubmitAttempt) {
    const submitResult = runResult.submitResult;
    if (submitResult?.confirmed) {
      return { outcome: 'SUBMITTED', detail: `Submission confirmed: ${submitResult.evidence}`, result: null };
    }
    return {
      outcome: 'HUMAN_REQUIRED',
      detail: `Submit click completed but was not confirmed [${submitResult?.category ?? 'UNKNOWN'}]: ${submitResult?.evidence
        ?? 'no result was recorded'}; human verification is required before any further attempt.`,
      result: null,
    };
  }
  if (plan.planType === 'GREENHOUSE') {
    return {
      outcome: 'HUMAN_REQUIRED',
      detail: `Form preparation finished; human review required (${plan.requiredGaps.length} outstanding field(s))`,
      result: null,
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
