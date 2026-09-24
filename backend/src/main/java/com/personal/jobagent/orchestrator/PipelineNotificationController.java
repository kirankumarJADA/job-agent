package com.personal.jobagent.orchestrator;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Feature 8 producer for the Phase 6+ lifecycle events the Phase 1 backend
 * cannot yet originate (employer signup/verification, confirmation email,
 * recruiter reply, interview, assessment, rejection, offer, automation
 * failure, hard stop, approval required).
 *
 * Why an internal endpoint: the Phase 6 worker/automation modules that will
 * originate these events don't exist yet (worker/ is an explicit Phase 6
 * stub), so the orchestration boundary is exercised through an internal,
 * session-protected surface instead of stub code that would violate the
 * worker/README.md phase boundary. When P6 lands, its module calls the
 * same NotificationService.emit() seam — the notification contract does
 * not change.
 *
 * Auth: requires an authenticated session like every other business
 * endpoint (SecurityConfig anyRequest().authenticated()) — it is NOT in
 * the permit list.
 *
 * Correlation: jobId/applicationId are validated against the DB; unknown
 * ids are rejected with 400 — a notification pointing at a non-existent
 * job/application would be misleading, not merely useless.
 */
@RestController
@RequestMapping("/api/v1/pipeline/notifications")
public class PipelineNotificationController {

    private static final Set<String> SUPPORTED = Set.of(
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
            NotificationEvents.APPROVAL_REQUIRED
    );

    private final NotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OwnerContext ownerContext;

    public PipelineNotificationController(NotificationService notificationService,
                                          JdbcTemplate jdbcTemplate,
                                          PlatformTransactionManager transactionManager,
                                          OwnerContext ownerContext) {
        this.notificationService = notificationService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.ownerContext = ownerContext;
    }

    public record PipelineEventRequest(
            UUID jobId,
            UUID applicationId,
            String emailMessageId,
            String status,
            String reason,
            String step,
            String approvalId,
            String detail,
            String link
    ) {
    }

    /**
     * POST /api/v1/pipeline/notifications/{eventType}
     * eventType must be a NotificationEvents catalogue value.
     */
    @PostMapping("/{eventType}")
    public ResponseEntity<?> emitEvent(@PathVariable String eventType,
                                       @RequestBody(required = false) PipelineEventRequest request) {
        String uri = "/api/v1/pipeline/notifications";
        String correlation = String.valueOf(MDC.get("correlation_id"));

        if (!SUPPORTED.contains(eventType)) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Unknown event type",
                    "eventType must be one of the NotificationEvents catalogue values", uri, correlation));
        }
        if (request == null) {
            request = new PipelineEventRequest(null, null, null, null, null, null, null, null, null);
        }

        UUID jobId = request.jobId();
        UUID applicationId = request.applicationId();
        if (jobId != null && !jobExists(jobId)) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Unknown job",
                    "jobId does not reference an existing job", uri, correlation));
        }
        // An application reference must be the caller's own. This endpoint
        // previously accepted any existing application id, so any authenticated
        // account could emit lifecycle events against another candidate's
        // application — including REJECTED / OFFER, which the notification
        // fan-out turns into rows and the status machine honours. A foreign id is
        // answered as not found, matching the rest of the API.
        if (applicationId != null
                && ownerContext.ownerOfApplication(applicationId)
                        .filter(owner -> owner.equals(ownerContext.profileIdOrNull()))
                        .isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not found",
                    "No application with that id", uri, correlation));
        }
        if (applicationId != null && jobId == null) {
            jobId = jdbcTemplate.queryForObject(
                    "select job_id from applications where id = ?", UUID.class, applicationId);
        }
        if (jobId == null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Missing correlation",
                    "jobId or applicationId is required", uri, correlation));
        }

        String aggregateType = applicationId != null ? "APPLICATION" : "JOB";
        UUID aggregateId = applicationId != null ? applicationId : jobId;
        String dedupKey = buildDedupKey(eventType, request, aggregateId);
        UUID correlationId = UuidV7.generate();

        Map<String, Object> payload = new HashMap<>();
        payload.put("job_id", jobId.toString());
        // Explicit owner: some of these events aggregate on the shared JOB, so
        // the fan-out has no application to derive the recipient from and would
        // otherwise file the notification as an unseen system notice.
        UUID ownerProfileId = ownerContext.profileIdOrNull();
        putIfNotNull(payload, "profile_id", ownerProfileId == null ? null : ownerProfileId.toString());
        if (applicationId != null) {
            payload.put("application_id", applicationId.toString());
        }
        putIfNotNull(payload, "email_message_id", request.emailMessageId());
        putIfNotNull(payload, "status", request.status());
        putIfNotNull(payload, "new_status", request.status());
        putIfNotNull(payload, "reason", request.reason());
        putIfNotNull(payload, "step", request.step());
        putIfNotNull(payload, "approval_id", request.approvalId());
        putIfNotNull(payload, "detail", request.detail());
        putIfNotNull(payload, "link", request.link());
        putIfNotNull(payload, "job_title", jobTitle(jobId));
        putIfNotNull(payload, "company", companyName(jobId));
        payload.put("severity", severityFor(eventType));
        payload.put("dedup_key", dedupKey);

        UUID eventId = transactionTemplate.execute(tx -> notificationService.emit(
                new NotificationService.NotificationCommand(
                        eventType, aggregateType, aggregateId, payload, correlationId, null)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_id", eventId);
        body.put("event_type", eventType);
        body.put("dedup_key", dedupKey);
        body.put("job_id", jobId);
        body.put("application_id", applicationId);
        body.put("correlation_id", correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static void putIfNotNull(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String severityFor(String eventType) {
        if (NotificationEvents.AUTOMATION_FAILURE.equals(eventType)
                || NotificationEvents.HARD_STOP.equals(eventType)) {
            return "ERROR";
        }
        if (NotificationEvents.APPROVAL_REQUIRED.equals(eventType)
                || NotificationEvents.REJECTION_RECEIVED.equals(eventType)) {
            return "WARN";
        }
        return "INFO";
    }

    private static String buildDedupKey(String eventType, PipelineEventRequest request, UUID aggregateId) {
        String qualifier = request.emailMessageId() != null ? request.emailMessageId()
                : request.approvalId() != null ? request.approvalId()
                : request.step() != null ? request.step()
                : request.reason() != null ? request.reason()
                : request.status() != null ? request.status()
                : "none";
        // Full type, dots/underscores → dashes: "policy.hard_stop" →
        // "policy-hard-stop", "approval.required" → "approval-required".
        // (Substring-after-first-dot previously collapsed "approval.required"
        // to just "required" — caught in live verification.)
        String shortType = eventType.replace('.', '-').replace('_', '-');
        return "pipeline:" + shortType + ":" + aggregateId + ":" + qualifier;
    }

    private boolean jobExists(UUID jobId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from jobs where id = ?", Integer.class, jobId);
        return count != null && count > 0;
    }

    private String jobTitle(UUID jobId) {
        try {
            return jdbcTemplate.queryForObject("select title from jobs where id = ?", String.class, jobId);
        } catch (Exception e) {
            return null;
        }
    }

    private String companyName(UUID jobId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select company_name_raw from jobs where id = ?", String.class, jobId);
        } catch (Exception e) {
            return null;
        }
    }
}
