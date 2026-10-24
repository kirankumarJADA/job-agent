package com.personal.jobagent.automation;

import java.util.List;

import static com.personal.jobagent.automation.AtsCapabilityLevel.FULL;
import static com.personal.jobagent.automation.AtsCapabilityLevel.NONE;
import static com.personal.jobagent.automation.AtsCapabilitySummary.*;

/**
 * Phase 10: one platform's honest, verified capability row — see {@code
 * AtsCapabilityRegistry}'s own class comment for how every field here was
 * established (read against the real adapter/worker code, never assumed from
 * a class name or a hostname match).
 *
 * @param kind                the persisted {@code application.planType} this row describes, or
 *                            {@code "GENERIC"} for every other recognised ATS/board/company site
 *                            that has no dedicated execution-plan service
 * @param hostPatterns        lower-cased hostname suffixes/exact-matches this platform's own real
 *                            adapter recognises (mirrors {@code AtsAdapters}'/the execution-plan
 *                            services' own patterns) — never a separate, invented classification
 * @param jobDiscovery        can Robin find postings on this platform at all
 * @param applicationUrlResolution a job-specific (not generic search-page) application URL resolves
 * @param formInspection       the real application form's fields are inspected (statically or live in-browser)
 * @param accountCreationReadiness Robin can create a NEW employer-site account on the candidate's behalf
 * @param signInReadiness     Robin can verify a candidate's SAVED credential signs in on the real sign-in page
 * @param requiredFieldValidation required vs. optional fields are distinguished before execution
 * @param questionAnswering    custom application questions are answered from verified data
 * @param documentAttachment   the reviewed CV/cover-letter PDF is attached to the form
 * @param formFilling          supported text fields/selections are actually filled in the browser
 * @param finalSubmission      a REAL submit action can be clicked, after explicit human approval
 * @param submissionConfirmation a clicked submission's success is verified from real page evidence, not assumed
 * @param recoveryAndDuplicatePrevention a crash/restart/replay cannot repeat a real submission or duplicate an application
 * @param verificationNote    what was and was not independently verified, and how (live capture vs. documentation vs. fixture)
 */
public record AtsCapabilities(
        String kind,
        String displayName,
        List<String> hostPatterns,
        AtsCapabilityLevel jobDiscovery,
        AtsCapabilityLevel applicationUrlResolution,
        AtsCapabilityLevel formInspection,
        AtsCapabilityLevel accountCreationReadiness,
        AtsCapabilityLevel signInReadiness,
        AtsCapabilityLevel requiredFieldValidation,
        AtsCapabilityLevel questionAnswering,
        AtsCapabilityLevel documentAttachment,
        AtsCapabilityLevel formFilling,
        AtsCapabilityLevel finalSubmission,
        AtsCapabilityLevel submissionConfirmation,
        AtsCapabilityLevel recoveryAndDuplicatePrevention,
        String verificationNote
) {
    /**
     * The single honest label this row reduces to — the HIGHEST level this
     * platform's own verified dimensions actually reach, walked from the top
     * down. A platform is never credited with a level its own fields do not
     * support, regardless of what its name or hostname pattern might imply.
     */
    public AtsCapabilitySummary summary() {
        if (finalSubmission == FULL && submissionConfirmation == FULL) return SUBMISSION_AVAILABLE;
        if (formFilling != NONE) return FORM_FILL_AVAILABLE;
        if (signInReadiness != NONE) return SIGN_IN_READINESS_ONLY;
        if (applicationUrlResolution != NONE) {
            // Document preparation (CV/cover letter/answers) runs for every
            // created application regardless of ATS — see
            // ApplicationPipelineEventHandler#handleCreated — so a platform
            // with no form-side integration at all still gets this label,
            // never DISCOVERY_ONLY, as long as a real application URL exists
            // for the candidate to finish on themselves.
            return formInspection != NONE || questionAnswering != NONE || documentAttachment != NONE
                    ? PREPARATION_AVAILABLE : MANUAL_APPLICATION_REQUIRED;
        }
        return DISCOVERY_ONLY;
    }
}
