package com.personal.jobagent.notifications;

import com.personal.jobagent.events.Envelope;
import com.personal.jobagent.events.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * The outbox consumer that turns business events into idempotent
 * notifications. One handler for the whole catalogue: supports() covers
 * every type in NotificationEvents, handle() maps the envelope to a
 * category + severity + copy + correlation IDs and calls
 * NotificationService.deliver() (dedup via dedup_key; audit only on first
 * delivery — replay of the same outbox event is a silent no-op, enforced
 * by consumed_events AND the notification partial UNIQUE index).
 *
 * This is the single place a new pipeline event needs touching:
 * NotificationEvents holds the type, this class holds the mapping.
 *
 * Payload contract (all keys optional, producer decides what to include):
 *   job_id, application_id, job_title, company, message, link,
 *   dedup_key (override), severity (override), plus event-specific ids
 *   (cv_version_id, cover_letter_id, email_message_id, reason, step, ...).
 * Secrets must never be placed in the payload — the handler copies only
 * the correlation fields it knows into the notification.
 */
@Component
public class NotificationEventHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventHandler.class);

    private final NotificationService notificationService;

    public NotificationEventHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public String consumerName() {
        return NotificationService.CONSUMER_NAME;
    }

    @Override
    public boolean supports(String eventType) {
        return switch (eventType) {
            case NotificationEvents.JOB_DISCOVERED,
                 NotificationEvents.JOB_MATCHED,
                 NotificationEvents.CV_GENERATED,
                 NotificationEvents.COVER_LETTER_GENERATED,
                 NotificationEvents.APPLICATION_ANSWER_DRAFTED,
                 NotificationEvents.APPLICATION_PREPARED,
                 NotificationEvents.APPLICATION_SUBMITTED,
                 NotificationEvents.APPLICATION_STATUS_CHANGED,
                 NotificationEvents.SIGNUP_STARTED,
                 NotificationEvents.SIGNUP_COMPLETED,
                 NotificationEvents.VERIFICATION_EMAIL_RECEIVED,
                 NotificationEvents.VERIFICATION_COMPLETED,
                 NotificationEvents.CONFIRMATION_EMAIL_RECEIVED,
                 NotificationEvents.RECRUITER_REPLY,
                 NotificationEvents.INTERVIEW_INVITATION,
                 NotificationEvents.ASSESSMENT_RECEIVED,
                 NotificationEvents.REJECTION_RECEIVED,
                 NotificationEvents.OFFER_RECEIVED,
                 NotificationEvents.AUTOMATION_FAILURE,
                 NotificationEvents.HARD_STOP,
                 NotificationEvents.APPROVAL_REQUIRED -> true;
            default -> false;
        };
    }

    @Override
    public void handle(Envelope envelope) {
        Map<String, Object> payload = asMap(envelope.payload());

        UUID jobId = uuid(payload.get("job_id"));
        UUID applicationId = uuid(payload.get("application_id"));
        // Aggregate fallback: an event for a JOB aggregate carries the job id,
        // one for an APPLICATION aggregate carries the application id.
        if (jobId == null && "JOB".equals(envelope.aggregateType())) {
            jobId = envelope.aggregateId();
        }
        if (applicationId == null && "APPLICATION".equals(envelope.aggregateType())) {
            applicationId = envelope.aggregateId();
        }

        String jobTitle = str(payload.get("job_title"));
        String company = str(payload.get("company"));
        String context = (company != null ? company : "") + (jobTitle != null ? " — " + jobTitle : "");

        String category = categoryFor(envelope.type());
        String severity = str(payload.get("severity")) != null ? str(payload.get("severity")) : severityFor(envelope.type());
        String title = str(payload.get("message")) != null
                ? str(payload.get("message"))
                : defaultTitle(envelope.type(), context);
        String body = str(payload.get("detail"));
        String link = str(payload.get("link")) != null ? str(payload.get("link")) : defaultLink(envelope.type(), jobId, applicationId);
        String dedupKey = str(payload.get("dedup_key")) != null
                ? str(payload.get("dedup_key"))
                : defaultDedupKey(envelope, payload);

        NotificationService.Delivery delivery = new NotificationService.Delivery(
                envelope.id(),
                envelope.type(),
                jobId,
                applicationId,
                envelope.correlationId(),
                dedupKey,
                severity,
                category,
                title,
                body,
                link,
                payload
        );

        try {
            notificationService.deliver(delivery);
        } catch (Exception e) {
            // Surface for outbox retry/DLQ handling rather than silently
            // dropping the notification — but never crash the dispatch loop.
            log.error("Notification delivery failed for event {}: {}", envelope.id(), e.getMessage());
            throw new IllegalStateException("Notification delivery failed for event " + envelope.id(), e);
        }
    }

    // ── mapping ──────────────────────────────────────────────────────

    private static String categoryFor(String eventType) {
        return switch (eventType) {
            case NotificationEvents.JOB_DISCOVERED -> "JOB_DISCOVERED";
            case NotificationEvents.JOB_MATCHED -> "JOB_MATCHED";
            case NotificationEvents.CV_GENERATED -> "CV_GENERATED";
            case NotificationEvents.COVER_LETTER_GENERATED -> "COVER_LETTER_GENERATED";
            case NotificationEvents.APPLICATION_ANSWER_DRAFTED -> "APPLICATION_ANSWER_DRAFTED";
            case NotificationEvents.APPLICATION_PREPARED -> "APPLICATION_PREPARED";
            case NotificationEvents.APPLICATION_SUBMITTED -> "APPLICATION_SUBMITTED";
            case NotificationEvents.APPLICATION_STATUS_CHANGED -> "APPLICATION_STATUS_CHANGED";
            case NotificationEvents.SIGNUP_STARTED -> "SIGNUP_STARTED";
            case NotificationEvents.SIGNUP_COMPLETED -> "SIGNUP_COMPLETED";
            case NotificationEvents.VERIFICATION_EMAIL_RECEIVED -> "VERIFICATION_EMAIL";
            case NotificationEvents.VERIFICATION_COMPLETED -> "VERIFICATION_COMPLETED";
            case NotificationEvents.CONFIRMATION_EMAIL_RECEIVED -> "CONFIRMATION_EMAIL";
            case NotificationEvents.RECRUITER_REPLY -> "RECRUITER_REPLY";
            case NotificationEvents.INTERVIEW_INVITATION -> "INTERVIEW_INVITATION";
            case NotificationEvents.ASSESSMENT_RECEIVED -> "ASSESSMENT";
            case NotificationEvents.REJECTION_RECEIVED -> "REJECTION";
            case NotificationEvents.OFFER_RECEIVED -> "OFFER";
            case NotificationEvents.AUTOMATION_FAILURE -> "AUTOMATION_FAILURE";
            case NotificationEvents.HARD_STOP -> "HARD_STOP";
            case NotificationEvents.APPROVAL_REQUIRED -> "APPROVAL_REQUIRED";
            default -> "EVENT";
        };
    }

    private static String severityFor(String eventType) {
        return switch (eventType) {
            case NotificationEvents.AUTOMATION_FAILURE, NotificationEvents.HARD_STOP -> "ERROR";
            case NotificationEvents.APPROVAL_REQUIRED,
                 NotificationEvents.REJECTION_RECEIVED,
                 NotificationEvents.APPLICATION_STATUS_CHANGED -> "WARN";
            default -> "INFO";
        };
    }

    private static String defaultTitle(String eventType, String context) {
        String suffix = context == null || context.isBlank() ? "" : " — " + context;
        return switch (eventType) {
            case NotificationEvents.JOB_DISCOVERED -> "New job discovered" + suffix;
            case NotificationEvents.JOB_MATCHED -> "New job match" + suffix;
            case NotificationEvents.CV_GENERATED -> "Job-specific CV generated" + suffix;
            case NotificationEvents.COVER_LETTER_GENERATED -> "Cover letter generated" + suffix;
            case NotificationEvents.APPLICATION_ANSWER_DRAFTED -> "Application answer drafted" + suffix;
            case NotificationEvents.APPLICATION_PREPARED -> "Application prepared" + suffix;
            case NotificationEvents.APPLICATION_SUBMITTED -> "Application submitted" + suffix;
            case NotificationEvents.APPLICATION_STATUS_CHANGED -> "Application status changed" + suffix;
            case NotificationEvents.SIGNUP_STARTED -> "Employer signup started" + suffix;
            case NotificationEvents.SIGNUP_COMPLETED -> "Employer signup completed" + suffix;
            case NotificationEvents.VERIFICATION_EMAIL_RECEIVED -> "Verification email received" + suffix;
            case NotificationEvents.VERIFICATION_COMPLETED -> "Employer verification completed" + suffix;
            case NotificationEvents.CONFIRMATION_EMAIL_RECEIVED -> "Application confirmation received" + suffix;
            case NotificationEvents.RECRUITER_REPLY -> "Recruiter reply received" + suffix;
            case NotificationEvents.INTERVIEW_INVITATION -> "Interview invitation" + suffix;
            case NotificationEvents.ASSESSMENT_RECEIVED -> "Assessment received" + suffix;
            case NotificationEvents.REJECTION_RECEIVED -> "Application rejected" + suffix;
            case NotificationEvents.OFFER_RECEIVED -> "Offer received" + suffix;
            case NotificationEvents.AUTOMATION_FAILURE -> "Automation step failed" + suffix;
            case NotificationEvents.HARD_STOP -> "Hard stop — human attention required" + suffix;
            case NotificationEvents.APPROVAL_REQUIRED -> "Approval required" + suffix;
            default -> "Pipeline event" + suffix;
        };
    }

    private static String defaultLink(String eventType, UUID jobId, UUID applicationId) {
        if (jobId != null) {
            return "/jobs/" + jobId;
        }
        if (applicationId != null) {
            return "/applications/" + applicationId;
        }
        return null;
    }

    /**
     * Default idempotency key per business occurrence. Per-message classes
     * (recruiter replies, verification emails) key on the email message id
     * so distinct messages stay distinct; lifecycle classes key on the
     * aggregate id so replays collapse.
     */
    private static String defaultDedupKey(Envelope envelope, Map<String, Object> payload) {
        String emailMessageId = str(payload.get("email_message_id"));
        String status = str(payload.get("status"));
        String reason = str(payload.get("reason"));
        String step = str(payload.get("step"));
        String approvalId = str(payload.get("approval_id"));
        String newStatus = str(payload.get("new_status"));

        return switch (envelope.type()) {
            case NotificationEvents.JOB_DISCOVERED -> "job-discovered:" + envelope.aggregateId();
            case NotificationEvents.JOB_MATCHED -> "job-matched:" + envelope.aggregateId();
            case NotificationEvents.CV_GENERATED -> "cv-generated:" + orAggregate(payload.get("cv_version_id"), envelope);
            case NotificationEvents.COVER_LETTER_GENERATED ->
                    "cover-letter-generated:" + orAggregate(payload.get("cover_letter_id"), envelope);
            case NotificationEvents.APPLICATION_ANSWER_DRAFTED ->
                    "answer-drafted:" + orAggregate(payload.get("answer_id"), envelope);
            case NotificationEvents.APPLICATION_PREPARED -> "app-prepared:" + envelope.aggregateId();
            case NotificationEvents.APPLICATION_SUBMITTED -> "app-submitted:" + envelope.aggregateId();
            case NotificationEvents.APPLICATION_STATUS_CHANGED ->
                    "app-status:" + envelope.aggregateId() + ":" + (newStatus != null ? newStatus : status);
            case NotificationEvents.SIGNUP_STARTED -> "signup-started:" + envelope.aggregateId();
            case NotificationEvents.SIGNUP_COMPLETED -> "signup-completed:" + envelope.aggregateId();
            case NotificationEvents.VERIFICATION_EMAIL_RECEIVED ->
                    "verification-received:" + envelope.aggregateId() + ":" + safe(emailMessageId);
            case NotificationEvents.VERIFICATION_COMPLETED -> "verification-completed:" + envelope.aggregateId();
            case NotificationEvents.CONFIRMATION_EMAIL_RECEIVED ->
                    "confirmation-received:" + envelope.aggregateId() + ":" + safe(emailMessageId);
            case NotificationEvents.RECRUITER_REPLY -> "recruiter-reply:" + envelope.aggregateId() + ":" + safe(emailMessageId);
            case NotificationEvents.INTERVIEW_INVITATION -> "interview:" + envelope.aggregateId() + ":" + safe(emailMessageId);
            case NotificationEvents.ASSESSMENT_RECEIVED -> "assessment:" + envelope.aggregateId() + ":" + safe(emailMessageId);
            case NotificationEvents.REJECTION_RECEIVED -> "rejection:" + envelope.aggregateId() + ":" + safe(emailMessageId);
            case NotificationEvents.OFFER_RECEIVED -> "offer:" + envelope.aggregateId() + ":" + safe(emailMessageId);
            case NotificationEvents.AUTOMATION_FAILURE ->
                    "automation-failure:" + envelope.aggregateId() + ":" + safe(step);
            case NotificationEvents.HARD_STOP -> "hard-stop:" + envelope.aggregateId() + ":" + safe(reason);
            case NotificationEvents.APPROVAL_REQUIRED -> "approval-required:" + safe(approvalId) + ":" + envelope.aggregateId();
            default -> "event:" + envelope.id();
        };
    }

    // ── helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private static String safe(String value) {
        return value != null ? value : "none";
    }

    private static String orAggregate(Object value, Envelope envelope) {
        return value != null ? value.toString() : envelope.aggregateId().toString();
    }

    private static UUID uuid(Object value) {
        String s = str(value);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
