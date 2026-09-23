package com.personal.jobagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ApplicationStatusService {
    public record TransitionResult(UUID applicationId, String previousStatus, String status, boolean changed, boolean replayed) {}
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "READY_TO_APPLY", Set.of("APPLICATION_STARTED", "FAILED", "WITHDRAWN"),
            "APPLICATION_STARTED", Set.of("OTP_PENDING", "APPLICATION_SUBMITTED", "FAILED", "WITHDRAWN"),
            "OTP_PENDING", Set.of("APPLICATION_STARTED", "APPLICATION_SUBMITTED", "FAILED", "WITHDRAWN"),
            "APPLICATION_SUBMITTED", Set.of("CONFIRMATION_RECEIVED", "RECRUITER_CONTACT", "INTERVIEW", "ASSESSMENT", "REJECTED", "OFFER"),
            "CONFIRMATION_RECEIVED", Set.of("RECRUITER_CONTACT", "INTERVIEW", "ASSESSMENT", "REJECTED", "OFFER"),
            "RECRUITER_CONTACT", Set.of("INTERVIEW", "ASSESSMENT", "REJECTED", "OFFER"),
            "INTERVIEW", Set.of("ASSESSMENT", "REJECTED", "OFFER"),
            "ASSESSMENT", Set.of("REJECTED", "OFFER")
    );
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuditLogWriter audit;
    private final NotificationService notifications;
    public ApplicationStatusService(JdbcTemplate db, ObjectMapper json, AuditLogWriter audit, NotificationService notifications) { this.db=db; this.json=json; this.audit=audit; this.notifications=notifications; }

    @Transactional
    public TransitionResult apply(UUID applicationId, String target, String eventKey, String actor, Map<String,Object> context) {
        if (applicationId == null || target == null || eventKey == null || eventKey.isBlank()) throw new IllegalArgumentException("application, target, and event key are required");
        Optional<String> already = db.query("select payload->>'event_key' from application_events where application_id=? and payload->>'event_key'=? limit 1", (rs,n)->rs.getString(1), applicationId, eventKey).stream().findFirst();
        String current = db.queryForObject("select status from applications where id=? for update", String.class, applicationId);
        if (already.isPresent()) return new TransitionResult(applicationId,current,current,false,true);
        if (current.equals(target)) throw new IllegalStateException("duplicate status transition is not allowed");
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) throw new IllegalStateException("invalid application transition " + current + " -> " + target);
        Map<String,Object> payload = new LinkedHashMap<>(); payload.put("event_key",eventKey); payload.put("from",current); payload.put("to",target); if(context!=null) payload.putAll(context);
        try {
            db.update("update applications set status=?,updated_at=now() where id=? and status=?", target, applicationId, current);
            db.update("insert into application_events(id,application_id,type,payload,actor) values(?,?,?,?::jsonb,?)", UuidV7.generate(), applicationId, "STATUS_CHANGED", json.writeValueAsString(payload), actor == null ? "SYSTEM" : actor);
        } catch (Exception e) { throw new IllegalStateException("could not persist application transition: " + e.getMessage(), e); }
        audit.write(new AuditEntry(actor == null ? "SYSTEM" : actor, "APPLICATION_STATUS_CHANGED", "APPLICATION", applicationId, Map.of("status",current), Map.of("status",target,"event_key",eventKey), null, UuidV7.generate()));
        String event = notificationEvent(target);
        if (event != null) notifications.emit(new NotificationService.NotificationCommand(event,"APPLICATION",applicationId,Map.of("event_key",eventKey,"from",current,"to",target),UuidV7.generate(),null));
        return new TransitionResult(applicationId,current,target,true,false);
    }
    public Optional<Map<String,Object>> find(UUID id) { return db.query("select id,job_id,status,mode,created_at,updated_at from applications where id=?",(rs,n)->Map.of("id",rs.getObject("id"),"jobId",rs.getObject("job_id"),"status",rs.getString("status"),"mode",rs.getString("mode"),"createdAt",rs.getTimestamp("created_at").toInstant(),"updatedAt",rs.getTimestamp("updated_at").toInstant()),id).stream().findFirst(); }
    // application_events.payload is jsonb: queryForList handed the driver's PGobject to Jackson,
    // which rendered it as {"type":"jsonb","value":"..."} instead of the event payload itself.
    // Cast to text and parse it back into JSON at the JDBC boundary.
    public List<Map<String,Object>> timeline(UUID id) {
        return db.query("select id,type,payload::text as payload,actor,occurred_at from application_events where application_id=? order by occurred_at,id",
                (rs,n) -> {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("id",rs.getObject("id"));
                    row.put("type",rs.getString("type"));
                    row.put("payload",JdbcConversions.readJson(rs,"payload",json));
                    row.put("actor",rs.getString("actor"));
                    row.put("occurred_at",rs.getObject("occurred_at"));
                    return row;
                }, id);
    }
    private String notificationEvent(String target) { return switch(target) { case "APPLICATION_SUBMITTED" -> NotificationEvents.APPLICATION_SUBMITTED; case "CONFIRMATION_RECEIVED" -> NotificationEvents.CONFIRMATION_EMAIL_RECEIVED; case "RECRUITER_CONTACT" -> NotificationEvents.RECRUITER_REPLY; case "INTERVIEW" -> NotificationEvents.INTERVIEW_INVITATION; case "ASSESSMENT" -> NotificationEvents.ASSESSMENT_RECEIVED; case "REJECTED" -> NotificationEvents.REJECTION_RECEIVED; case "OFFER" -> NotificationEvents.OFFER_RECEIVED; default -> null; }; }
}
