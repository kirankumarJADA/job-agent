package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Generic outcome transition, always from {@code RUNNING}. Also clears
     * {@code submit_approved}: for every call site that existed before Phase
     * 4 that flag was already false at this point (it is only ever set by
     * {@link #approveForSubmission}, strictly after a RUNNING pass
     * completes), so clearing it here is a no-op for them. For a real
     * submission attempt that did not reach {@code SUBMITTED} (uncertain or
     * failed), it is exactly the behavior required: the plan lands back in
     * AWAITING_APPROVAL/FAILED/BLOCKED_ANTI_BOT with approval withdrawn, so a
     * fresh, deliberate human re-approval — not a silent retry — is required
     * before any further attempt.
     */
    public boolean transition(UUID id, String from, String to) {
        return db.update("update automation_plans set status=?,submit_approved=false,updated_at=now() where id=? and status=?", to, id, from) == 1;
    }

    /**
     * Robin x Tsenta continuation: the same transition as {@link #transition},
     * but also persists a structured outcome into {@code automation_plans.result}
     * — a column that already existed (V009) but nothing previously wrote to.
     * Built for adapters like Zoho Recruit, where (unlike Greenhouse) the
     * backend does not know the form's fields ahead of execution: the only way
     * the review queue can show what was actually filled, what still needs the
     * candidate, and why is for the worker to report it back here, once, at the
     * one terminal point every run reaches. {@code result} is stored verbatim
     * (plan ownership/authorization for who may call this is enforced by the
     * controller, exactly as for {@link #transition}).
     */
    public boolean transitionWithResult(UUID id, String from, String to, List<Object> result) {
        try {
            String value = json.writeValueAsString(result == null ? List.of() : result);
            return db.update("""
                    update automation_plans set status=?, result=?::jsonb, submit_approved=false, updated_at=now()
                    where id=? and status=?
                    """, to, value, id, from) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist plan outcome result", e);
        }
    }

    /**
     * Robin x Tsenta continuation: the retry half of review-queue recovery.
     * Without this, a plan that reaches {@code FAILED} or
     * {@code BLOCKED_ANTI_BOT} is stuck there forever — {@link #create}'s own
     * idempotency-key lookup always resolves back to the SAME row regardless
     * of its current status, so a terminal-failed plan's id is simply handed
     * back unchanged on every later call, and {@link #claimNext} only ever
     * serves {@code PREPARED} rows. This is the one, deliberate way out:
     * atomically moves a plan from exactly one of those two terminal
     * failure states back to {@code PREPARED} — never from any other status
     * (an {@code AWAITING_APPROVAL}, {@code RUNNING} or already-{@code
     * SUBMITTED} plan is untouched) — clearing its heartbeat so a stale
     * lease cannot linger. The candidate's own existing, explicit retry
     * action ({@code POST /applications/{id}/re-prepare}) is what reaches
     * this; nothing calls it automatically.
     *
     * <p><b>Also clears the stale {@code result}.</b> Robin x Tsenta
     * hardening: without this, the PREVIOUS attempt's persisted outcome (a
     * {@code PLAN_ERROR} entry, or in principle any earlier structured
     * result) would sit in the row — stale, attempt-specific state — from
     * the moment this requeue runs until the retried worker pass eventually
     * calls {@code /complete} again. A candidate who refreshes the review
     * panel in that window must never see a leftover result from the run
     * that just failed. Cleared to an empty JSON array in the SAME atomic
     * update, guarded by the identical {@code WHERE} clause — this can never
     * wipe the result of a plan that is not actually being requeued.
     *
     * @return false if the plan was not in one of those two exact states
     */
    public boolean requeueFailedPlan(UUID id) {
        return db.update("""
                update automation_plans set status='PREPARED', heartbeat_at=null, result='[]'::jsonb, updated_at=now()
                where id=? and status in ('FAILED','BLOCKED_ANTI_BOT')
                """, id) == 1;
    }

    public boolean approveSubmit(UUID id) {
        return db.update("update automation_plans set submit_approved=true,status='RUNNING',updated_at=now() where id=? and status='AWAITING_SUBMIT_APPROVAL' and submit_approved=false", id) == 1;
    }

    /**
     * Explicit owner approval of a validated plan: AWAITING_APPROVAL →
     * READY_TO_SUBMIT with {@code submit_approved=true}. Atomic and
     * single-shot — a second approval, or one racing a state change, no-ops.
     * READY_TO_SUBMIT by itself still does not submit anything: no worker
     * claims a READY_TO_SUBMIT plan (claim-next only serves PREPARED), and no
     * plan contains a submit-capable step at this point. Phase 4 adds the one
     * deliberate next action, {@link #prepareRealSubmission}, which is a
     * separate, explicit call a human must still make.
     */
    public boolean approveForSubmission(UUID id) {
        return db.update("update automation_plans set submit_approved=true,status='READY_TO_SUBMIT',updated_at=now() where id=? and status='AWAITING_APPROVAL' and submit_approved=false", id) == 1;
    }

    /**
     * Phase 4: the only method that can introduce a real-submission step into
     * a plan, and it only ever does so starting from an already-approved
     * plan. Appends exactly one {@code REAL_SUBMIT_CLICK} step (id
     * {@code real-submit-click}) to the plan's own stored JSON — the worker's
     * own {@code #validateGreenhousePlan} independently re-checks this same
     * shape before ever running it — and returns the plan to {@code PREPARED}
     * so the existing claim-next/resume path picks it up; every step before
     * this one is already recorded {@code COMPLETED}, so the worker's
     * existing per-step skip logic carries straight to the new step without
     * re-running anything already done. Guarded atomically by {@code where
     * status='READY_TO_SUBMIT' and submit_approved=true}: a plan that has not
     * been through explicit human approval can never reach this method
     * successfully.
     *
     * @return false if the plan was not in that exact state (already queued,
     *         already submitted, approval withdrawn by drift, etc.)
     */
    public boolean prepareRealSubmission(UUID id) {
        PlanRow current = findById(id).orElse(null);
        if (current == null || !"READY_TO_SUBMIT".equals(current.status()) || !current.submitApproved()) {
            return false;
        }
        Map<String, Object> stored = current.plan() == null ? Map.of() : current.plan();
        if (!"GREENHOUSE".equals(stored.get("planType")) || !(stored.get("steps") instanceof List<?> existingSteps)) {
            return false;
        }
        List<Object> newSteps = new java.util.ArrayList<>(existingSteps);
        newSteps.add(Map.of(
                "id", "real-submit-click",
                "type", "REAL_SUBMIT_CLICK",
                "policy", "AUTO",
                "params", Map.of("selector", "button[type=\"submit\"]")));
        Map<String, Object> updated = new java.util.LinkedHashMap<>(stored);
        updated.put("steps", newSteps);
        try {
            return db.update("""
                    update automation_plans set plan=?::jsonb, status='PREPARED', updated_at=now()
                    where id=? and status='READY_TO_SUBMIT' and submit_approved=true
                    """, json.writeValueAsString(updated), id) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist real-submission step", e);
        }
    }

    /**
     * Phase 4: the one, narrowly-scoped transition to {@code SUBMITTED}.
     * Reachable only from {@code RUNNING} (the second, post-approval worker
     * pass started by {@link #prepareRealSubmission}'s own {@code PREPARED}
     * state), and — unlike {@link #transition} — deliberately leaves {@code
     * submit_approved=true} as a durable record that this terminal state was
     * reached only after an explicit human approval. {@code SUBMITTED} is a
     * true dead end: no method in this class ever transitions out of it, and
     * {@code claimNext}/{@code claim} never select it.
     */
    public boolean completeRealSubmission(UUID id) {
        return db.update("update automation_plans set status='SUBMITTED',updated_at=now() where id=? and status='RUNNING' and submit_approved=true", id) == 1;
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

    /**
     * Phase 8.2: withdraws an approved package when the records it was built
     * from changed (document replacement, approval withdrawal, answer edits).
     * READY_TO_SUBMIT returns to AWAITING_APPROVAL with {@code
     * submit_approved=false} — the approval statement is no longer true, so a
     * human must re-approve the rebuilt package. Only the rows for this
     * application are touched.
     *
     * @return how many approvals were withdrawn
     */
    public int invalidateApproval(UUID applicationId) {
        if (applicationId == null) return 0;
        return db.update("""
                update automation_plans set submit_approved = false, status = 'AWAITING_APPROVAL', updated_at = now()
                where application_id = ? and status = 'READY_TO_SUBMIT' and submit_approved = true
                """, applicationId);
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

    /** Internal read of the plan's job id (worker package/artifact endpoints). */
    public Optional<UUID> jobIdOf(UUID planId) {
        return db.query("""
                        select a.job_id
                        from automation_plans p join applications a
                          on a.id = p.application_id and a.profile_id = p.profile_id
                        where p.id = ?
                        """, (rs, n) -> (UUID) rs.getObject(1), planId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public Optional<UUID> ownerOfPlan(UUID planId) {
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

    /**
     * Atomically claims the oldest PREPARED plan, returning the full plan row
     * now in RUNNING state. {@code FOR UPDATE SKIP LOCKED} ensures two
     * concurrent workers never claim the same plan — the loser sees no row
     * and gets {@code Optional.empty()}.
     *
     * <p>Must run inside a transaction for the lock to be released correctly;
     * the {@code @Transactional} annotation is on this method so callers
     * (the controller) do not need their own.
     */
    @Transactional
    public Optional<PlanRow> claimNext() {
        List<UUID> ids = db.query("""
                select id from automation_plans
                where status = 'PREPARED'
                order by created_at
                limit 1
                for update skip locked
                """, (rs, n) -> (UUID) rs.getObject(1));
        if (ids.isEmpty()) return Optional.empty();
        UUID id = ids.get(0);
        db.update("update automation_plans set status='RUNNING', heartbeat_at=now(), updated_at=now() where id=?", id);
        return findById(id);
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
