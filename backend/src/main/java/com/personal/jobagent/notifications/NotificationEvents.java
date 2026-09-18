package com.personal.jobagent.notifications;

/**
 * The canonical event catalogue (event_type values as written to
 * outbox_events) mapped to the notification category each fans out to.
 *
 * Adding a new pipeline event = add a constant here + let the
 * NotificationEventHandler map it to a category. Event types follow the
 * architecture's aggregate.event naming.
 */
public final class NotificationEvents {

    private NotificationEvents() {
    }

    // ── discovery / matching ─────────────────────────────────────────
    public static final String JOB_DISCOVERED = "job.discovered";
    public static final String JOB_MATCHED = "job.matched";

    // ── job-specific package generation ──────────────────────────────
    public static final String CV_GENERATED = "cv.generated";
    public static final String COVER_LETTER_GENERATED = "cover_letter.generated";
    public static final String APPLICATION_ANSWER_DRAFTED = "application_answer.drafted";

    // ── application lifecycle ────────────────────────────────────────
    public static final String APPLICATION_PREPARED = "application.prepared";
    public static final String APPLICATION_SUBMITTED = "application.submitted";
    public static final String APPLICATION_STATUS_CHANGED = "application.status_changed";

    // ── employer signup / verification (Phase 6 contract names) ─────
    public static final String SIGNUP_STARTED = "signup.started";
    public static final String SIGNUP_COMPLETED = "signup.completed";
    public static final String VERIFICATION_EMAIL_RECEIVED = "verification_email.received";
    public static final String VERIFICATION_COMPLETED = "verification.completed";
    public static final String CONFIRMATION_EMAIL_RECEIVED = "confirmation_email.received";
    public static final String RECRUITER_REPLY = "recruiter.reply";
    public static final String INTERVIEW_INVITATION = "interview.invitation";
    public static final String ASSESSMENT_RECEIVED = "assessment.received";
    public static final String REJECTION_RECEIVED = "rejection.received";
    public static final String OFFER_RECEIVED = "offer.received";

    // ── policy / automation / ops ────────────────────────────────────
    public static final String AUTOMATION_FAILURE = "automation.failure";
    public static final String HARD_STOP = "policy.hard_stop";
    public static final String APPROVAL_REQUIRED = "approval.required";
}
