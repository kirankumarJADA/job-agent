package com.personal.jobagent.automation;

/**
 * Phase 10: the one honest, user-facing label a platform's full
 * {@link AtsCapabilities} row reduces to. Ordered from least to most capable;
 * {@link AtsCapabilities#summary()} picks the highest one the platform's
 * actual, verified dimensions support — never the highest one its name or
 * hostname match alone might suggest.
 */
public enum AtsCapabilitySummary {
    /** Robin finds postings here but has no application-side integration at all. */
    DISCOVERY_ONLY,
    /** A job-specific application URL resolves, but no form/account/submission support exists; the candidate must apply by hand. */
    MANUAL_APPLICATION_REQUIRED,
    /** Robin prepares a tailored CV, cover letter and answers for this application, but does not touch the employer's own form. */
    PREPARATION_AVAILABLE,
    /** Robin can verify a saved credential signs in on the employer's real sign-in page, but never reaches the application form itself. */
    SIGN_IN_READINESS_ONLY,
    /** Robin inspects and fills the real application form and attaches documents, but never clicks submit. */
    FORM_FILL_AVAILABLE,
    /** Robin can, after explicit human approval, click the real submit action on the employer's own form. */
    SUBMISSION_AVAILABLE,
    /**
     * Reserved for a platform where a real submission has been independently
     * confirmed against a live employer tenant. No platform in this codebase
     * currently qualifies — Phase 10 explicitly never attempts a real
     * submission as a test, so this level is unused by design, not an
     * oversight; see {@code AtsCapabilityRegistry}'s own class comment.
     */
    SUBMISSION_VERIFIED
}
