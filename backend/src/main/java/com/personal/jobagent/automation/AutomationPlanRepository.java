package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for automation plans, their steps and worker events.
 *
 * <p><b>Two families of method, and the difference matters.</b>
 *
 * <ul>
 *   <li>{@code find(ownerProfileId, id)} — owner-scoped. This is what a user
 *       session may use, and the only one it may use. A plan id belonging to
 *       another account reads as "not found".</li>
 *   <li>{@code findById(id)} and the mutators ({@code claim}, {@code heartbeat},
 *       {@code appendStep}, {@code transition}, {@code approveSubmit}) — internal,
 *       unfiltered. These exist for the Phase 6 worker, which authenticates with
 *       its own bearer token ({@code WorkerEventTokenFilter}) and legitimately
 *       acts on plans regardless of owner. Every caller must first establish
 *       that the request is either a worker request or is acting on a plan the
 *       caller owns; {@link AutomationController} is the only caller and does
 *       exactly that.</li>
 * </ul>
 *
 * <p>They are kept as separate methods rather than one method taking a
 * "nullable profile means no filter" argument, because that shape turns a
 * missing profile into a silent authorization bypass. Making the unscoped call
 * a differently-named method means it can be grepped, reviewed and tested.
 *
 * <p>{@code idempotency_key} is unique <em>per owner</em> (V022). It used to be
 * unique globally, which meant a second candidate using the same key collided
 * with the first — and worse, {@code create} read the plan back by key alone, so
 * one account's create call could return another account's plan id.
 */
@Repository
public class AutomationPlanRepository {

    public record PlanRow(UUID id, UUID applicationId, String targetUrl, String status,
                          Map<String, Object> plan, List<Object> result, boolean submitApproved,
                          Instant heartbeatAt, Instant updatedAt) {
    }

    private static final String PLAN_COLUMNS =
            "id, application_id, target_url, status, plan, result, submit_approved, heartbeat_at, updated_at";

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public AutomationPlanRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public UUID create(UUID ownerProfileId, UUID applicationId, UUID jobId, String targetUrl,
                       String key, List<AutomationPlan.Step> steps) {
        UUID id = UuidV7.generate();
        Map<String, Object> body = Map.of(
                "applicationId", applicationId,
                "jobId", jobId,
                "targetUrl", targetUrl,
                "steps", steps,
                "safetyContract", AutomationPlan.SAFETY_CONTRACT);
        try {
            db.update("""
                    insert into automation_plans(id,application_id,target_url,plan,idempotency_key,profile_id,created_at,updated_at)
                    values(?,?,?,?::jsonb,?,?,now(),now())
                    on conflict (profile_id, idempotency_key) do nothing
                    """, id, applicationId, targetUrl, json.writeValueAsString(body), key, ownerProfileId);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist automation plan", e);
        }
        return db.queryForObject(
                "select id from automation_plans where idempotency_key=? and profile_id=?",
                UUID.class, key, ownerProfileId);
    }

    /** Owner-scoped read: a foreign plan id is reported as absent. */
    public Optional<PlanRow> find(UUID ownerProfileId, UUID id) {
        if (ownerProfileId == null || id == null) {
            return Optional.empty();
        }
        return db.query("select " + PLAN_COLUMNS + " from automation_plans where id=? and profile_id=?",
                (rs, n) -> mapRow(rs), id, ownerProfileId).stream().findFirst();
    }

    /** Internal read. Caller must have established worker authentication. */
    public Optional<PlanRow> findById(UUID id) {
        return db.query("select " + PLAN_COLUMNS + " from automation_plans where id=?",
                (rs, n) -> mapRow(rs), id).stream().findFirst();
    }

    public boolean owns(UUID ownerProfileId, UUID planId) {
        if (ownerProfileId == null || planId == null) {
            return false;
        }
        Integer count = db.queryForObject(
                "select count(*) from automation_plans where id=? and profile_id=?",
                Integer.class, planId, ownerProfileId);
        return count != null && count > 0;
    }

    public long countForOwner(UUID ownerProfileId) {
        if (ownerProfileId == null) {
            return 0;
        }
        Long count = db.queryForObject("select count(*) from automation_plans where profile_id=?", Long.class, ownerProfileId);
        return count != null ? count : 0;
    }

    public boolean claim(UUID id) {
        return db.update("update automation_plans set status='RUNNING',heartbeat_at=now(),updated_at=now() where id=? and status='PREPARED'", id) == 1;
    }

    public boolean heartbeat(UUID id) {
        return db.update("update automation_plans set heartbeat_at=now(),updated_at=now() where id=? and status='RUNNING'", id) == 1;
    }

    public boolean transition(UUID id, String from, String to) {
        return db.update("update automation_plans set status=?,updated_at=now() where id=? and status=?", to, id, from) == 1;
    }

    public boolean approveSubmit(UUID id) {
        return db.update("update automation_plans set submit_approved=true,status='RUNNING',updated_at=now() where id=? and status='AWAITING_SUBMIT_APPROVAL' and submit_approved=false", id) == 1;
    }

    public boolean appendStep(UUID id, int index, String stepId, String status, Map<String, Object> result, String screenshot) {
        try {
            String value = json.writeValueAsString(result);
            return db.update("""
                    insert into automation_steps(id,plan_id,step_key,step_index,status,result,screenshot_ref)
                    values(?,?,?,?,?,?::jsonb,?)
                    on conflict(plan_id,step_key) do update set
                        status=excluded.status,result=excluded.result,screenshot_ref=excluded.screenshot_ref,updated_at=now()
                    """, UuidV7.generate(), id, stepId, index, status, value, screenshot) > 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public int recoverStale(Instant cutoff) {
        return db.update("update automation_plans set status='PREPARED',updated_at=now() where status='RUNNING' and heartbeat_at < ?",
                java.sql.Timestamp.from(cutoff));
    }

    /**
     * Records a worker event, attributing it to the plan's owner (falling back to
     * the referenced application's owner) so worker telemetry can be read back
     * per candidate. Unattributable events are stored with a null owner and are
     * therefore invisible to users — see V022.
     */
    public boolean recordWorkerEvent(String eventId, UUID planId, UUID applicationId, UUID jobId,
                                     String type, Map<String, Object> payload) {
        try {
            UUID owner = ownerOfPlan(planId).orElseGet(() -> ownerOfApplication(applicationId).orElse(null));
            return db.update("""
                    insert into worker_events(id,event_id,plan_id,application_id,job_id,event_type,payload,profile_id,processed_at)
                    values(?,?,?,?,?,?,?::jsonb,?,now())
                    on conflict(event_id) do nothing
                    """, UuidV7.generate(), eventId, planId, applicationId, jobId, type,
                    json.writeValueAsString(payload), owner) > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist worker event", e);
        }
    }

    private Optional<UUID> ownerOfPlan(UUID planId) {
        if (planId == null) {
            return Optional.empty();
        }
        return db.query("select profile_id from automation_plans where id=?", (rs, n) -> (UUID) rs.getObject(1), planId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private Optional<UUID> ownerOfApplication(UUID applicationId) {
        if (applicationId == null) {
            return Optional.empty();
        }
        return db.query("select profile_id from applications where id=?", (rs, n) -> (UUID) rs.getObject(1), applicationId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private PlanRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlanRow(
                (UUID) rs.getObject(1),
                (UUID) rs.getObject(2),
                rs.getString(3),
                rs.getString(4),
                readMap(rs.getString(5)),
                readList(rs.getString(6)),
                rs.getBoolean(7),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9).toInstant());
    }

    private Map<String, Object> readMap(String s) {
        try {
            return s == null ? Map.of() : json.readValue(s, Map.class);
        } catch (Exception e) {
            return Map.of("corrupt", true);
        }
    }

    private List<Object> readList(String s) {
        try {
            return s == null ? List.of() : json.readValue(s, List.class);
        } catch (Exception e) {
            return List.of(Map.of("corrupt", true));
        }
    }
}
