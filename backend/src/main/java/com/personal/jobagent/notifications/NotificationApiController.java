package com.personal.jobagent.notifications;

import com.personal.jobagent.security.OwnerContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notifications API for the frontend bell + Logs & Audit page.
 *
 *   GET  /api/v1/notifications?unread=true&limit=50
 *   GET  /api/v1/notifications/unread-count
 *   POST /api/v1/notifications/{id}/read
 *   POST /api/v1/notifications/read-all
 *
 * <p>Every response is limited to the calling account's own notifications. The
 * owner is the caller's profile from the session — there is no query parameter
 * or path segment anywhere in this controller that selects whose notifications
 * to read, which is the point: there is nothing to tamper with.
 *
 * <p>An account with no profile yet (created but never set up) has no
 * notifications rather than an error: the bell must not 500 during onboarding,
 * and "you have none" is the truthful answer.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationApiController {

    private final NotificationRepository repository;
    private final OwnerContext ownerContext;

    public NotificationApiController(NotificationRepository repository, OwnerContext ownerContext) {
        this.repository = repository;
        this.ownerContext = ownerContext;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String unread,
            @RequestParam(defaultValue = "50") int limit) {

        UUID profileId = ownerContext.profileIdOrNull();
        List<NotificationRecord> rows = "true".equalsIgnoreCase(unread)
                ? repository.findRecentUnread(profileId, limit)
                : repository.findRecent(profileId, limit);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows.stream().map(NotificationApiController::toSnakeCase).toList());
        body.put("count", rows.size());
        body.put("unread_count", repository.countUnread(profileId));
        return ResponseEntity.ok(body);
    }

    /**
     * Wire format is snake_case (this endpoint's original contract and the
     * convention of every raw-SQL surface the frontend consumes —
     * /audit, /notifications). NotificationRecord is a Java record with
     * camelCase components, so the mapping is explicit here rather than
     * relying on a global Jackson naming strategy the rest of the API
     * doesn't share.
     *
     * <p>profile_id is deliberately NOT serialised: the client already knows
     * whose notifications it asked for, and echoing ownership ids back is free
     * reconnaissance for no benefit.
     */
    private static Map<String, Object> toSnakeCase(NotificationRecord n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("severity", n.severity());
        m.put("category", n.category());
        m.put("title", n.title());
        m.put("body", n.body());
        m.put("link", n.link());
        m.put("dedup_key", n.dedupKey());
        m.put("metadata", n.metadata());
        m.put("job_id", n.jobId());
        m.put("application_id", n.applicationId());
        m.put("read_at", n.readAt());
        m.put("created_at", n.createdAt());
        return m;
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> unreadCount() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unread_count", repository.countUnread(ownerContext.profileIdOrNull()));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(@PathVariable UUID id) {
        UUID profileId = ownerContext.profileIdOrNull();
        boolean changed = repository.markRead(profileId, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("changed", changed);
        body.put("unread_count", repository.countUnread(profileId));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllRead() {
        UUID profileId = ownerContext.profileIdOrNull();
        int changed = repository.markAllRead(profileId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changed", changed);
        body.put("unread_count", repository.countUnread(profileId));
        return ResponseEntity.ok(body);
    }
}
