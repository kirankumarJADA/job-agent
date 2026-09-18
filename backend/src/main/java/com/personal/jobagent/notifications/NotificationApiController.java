package com.personal.jobagent.notifications;

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
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationApiController {

    private final NotificationRepository repository;

    public NotificationApiController(NotificationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String unread,
            @RequestParam(defaultValue = "50") int limit) {

        List<NotificationRecord> rows = "true".equalsIgnoreCase(unread)
                ? repository.findRecentUnread(limit)
                : repository.findRecent(limit);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows.stream().map(NotificationApiController::toSnakeCase).toList());
        body.put("count", rows.size());
        body.put("unread_count", repository.countUnread());
        return ResponseEntity.ok(body);
    }

    /**
     * Wire format is snake_case (this endpoint's original contract and the
     * convention of every raw-SQL surface the frontend consumes —
     * /audit, /notifications). NotificationRecord is a Java record with
     * camelCase components, so the mapping is explicit here rather than
     * relying on a global Jackson naming strategy the rest of the API
     * doesn't share.
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
        body.put("unread_count", repository.countUnread());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(@PathVariable UUID id) {
        boolean changed = repository.markRead(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("changed", changed);
        body.put("unread_count", repository.countUnread());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllRead() {
        int changed = repository.markAllRead();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changed", changed);
        body.put("unread_count", repository.countUnread());
        return ResponseEntity.ok(body);
    }
}
