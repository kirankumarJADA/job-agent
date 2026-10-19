package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The candidate's own view of Robin-managed email (Phase 8.5).
 *
 * <ul>
 *   <li>{@code GET /address} — readiness, the reserved alias (if any), and the
 *       receiving evidence. {@code usableForRegistration} is true only when the
 *       domain has been proven by a signed delivery; the UI must not present the
 *       address as working otherwise.</li>
 *   <li>{@code POST /address} — reserve the alias (idempotent).</li>
 *   <li>{@code GET /messages} — recent deliveries as metadata only: sender,
 *       redacted subject, time, whether content is still held and whether the
 *       message was used for a verification. Never a body, code or link.</li>
 * </ul>
 * Every call is scoped to the caller's own profile, resolved from the session.
 */
@RestController
@RequestMapping("/api/v1/managed-mail")
public class ManagedMailboxController {

    private final ManagedMailboxService mailboxes;
    private final OwnerContext ownerContext;
    private final JdbcTemplate db;

    public ManagedMailboxController(ManagedMailboxService mailboxes, OwnerContext ownerContext, JdbcTemplate db) {
        this.mailboxes = mailboxes;
        this.ownerContext = ownerContext;
        this.db = db;
    }

    @GetMapping("/address")
    public ResponseEntity<?> address(HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return noProfile(request);
        return ResponseEntity.ok(view(mailboxes.status(profileId)));
    }

    @PostMapping("/address")
    public ResponseEntity<?> reserve(HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return noProfile(request);
        ManagedMailboxService.Status status = mailboxes.status(profileId);
        if (status.readiness() == ManagedMailboxService.Readiness.NOT_CONFIGURED) {
            return ResponseEntity.status(409).body(view(status));
        }
        mailboxes.ensureAddress(profileId);
        return ResponseEntity.ok(view(mailboxes.status(profileId)));
    }

    @GetMapping("/messages")
    public ResponseEntity<?> messages(@RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        UUID profileId = ownerContext.profileIdOrNull();
        if (profileId == null) return noProfile(request);
        int bounded = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = db.queryForList("""
                select id, from_address, sender_domain, subject_redacted, received_at, consumed_at,
                       content_encrypted is not null as content_held, authentication_failed, spam_flagged
                from managed_inbound_messages
                where profile_id = ?
                order by received_at desc
                limit ?
                """, profileId, bounded);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(row.get("id")));
            item.put("from", row.get("from_address"));
            item.put("senderDomain", row.get("sender_domain"));
            item.put("subject", row.get("subject_redacted"));
            item.put("receivedAt", ((Timestamp) row.get("received_at")).toInstant().toString());
            item.put("usedForVerification", row.get("consumed_at") != null);
            item.put("contentHeld", row.get("content_held"));
            item.put("senderCheckFailed", row.get("authentication_failed"));
            item.put("spamFlagged", row.get("spam_flagged"));
            items.add(item);
        }
        return ResponseEntity.ok(Map.of("items", items));
    }

    private Map<String, Object> view(ManagedMailboxService.Status status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("readiness", status.readiness().name());
        body.put("provider", mailboxes.settings().providerId());
        body.put("domain", status.domain() == null || status.domain().isEmpty() ? null : status.domain());
        body.put("address", status.address() == null ? null : status.address().address());
        body.put("addressStatus", status.address() == null ? null : status.address().status());
        body.put("usableForRegistration", status.usableForRegistration());
        body.put("receivingVerifiedAt", status.evidence() == null ? null : status.evidence().receivingVerifiedAt().toString());
        body.put("lastInboundAt", status.evidence() == null ? null : status.evidence().lastInboundAt().toString());
        body.put("missing", status.missing());
        return body;
    }

    private ResponseEntity<?> noProfile(HttpServletRequest request) {
        return ResponseEntity.status(404).body(ApiError.of(404, "Not Found", "No profile exists for this account.",
                request.getRequestURI(), String.valueOf(org.slf4j.MDC.get("correlation_id"))));
    }
}
