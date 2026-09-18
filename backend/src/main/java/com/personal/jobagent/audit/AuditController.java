package com.personal.jobagent.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Audit Controller: surfaces the immutable audit log for the frontend
 * Logs & Audit page.
 *
 * (The /notifications read surface moved to the notifications module's
 * NotificationApiController in Feature 8 — richer rows: dedup_key,
 * metadata, job/application correlation, read-state — and the old
 * duplicate GET /api/v1/notifications mapping here collided with it at
 * context startup. LogsPage now consumes the new endpoint.)
 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final JdbcTemplate jdbcTemplate;

    public AuditController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> listAuditLogs(@RequestParam(defaultValue = "50") int limit) {
        return jdbcTemplate.queryForList("""
                select id, actor, action, entity_type, entity_id,
                       before_state::text as before_state,
                       after_state::text as after_state,
                       correlation_id, created_at
                from audit_logs
                order by created_at desc
                limit ?
                """, Math.min(limit, 100));
    }

}
