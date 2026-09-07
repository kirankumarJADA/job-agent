package com.personal.jobagent.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Audit Controller: surfaces immutable audit logs and outbox DLQ notifications
 * for the frontend Logs & Audit page.
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

    @GetMapping("/notifications")
    public List<Map<String, Object>> listNotifications(@RequestParam(defaultValue = "50") int limit) {
        return jdbcTemplate.queryForList("""
                select id, severity, category, title, body, link, read_at, created_at
                from notifications
                order by created_at desc
                limit ?
                """, Math.min(limit, 100));
    }
}
