package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AutomationPlanRepository.class);

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
        return db.update("update automation_plans set status=?,submit_approved=false,heartbeat_at=null,updated_at=now() where id=? and status=?", to, id, from) == 1;
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
                    update automation_plans set status=?, result=?::jsonb, submit_approved=false, heartbeat_at=null, updated_at=now()
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
    @Transactional
    public boolean requeueFailedPlan(UUID id) {
        int updated = db.update("""
                update automation_plans set status='PREPARED', heartbeat_at=null, result='[]'::jsonb, updated_at=now()
                where id=? and status in ('FAILED','BLOCKED_ANTI_BOT')
                """, id);
        if (updated == 1) {
            db.update("""
                    insert into worker_events(id, event_id, plan_id, application_id, job_id, event_type, payload, profile_id, processed_at)
                    select ?, ?, id, application_id, null, 'PLAN_MANUAL_REQUEUE', '{}'::jsonb, profile_id, now()
                    from automation_plans where id=?
                    """, UuidV7.generate(), "manual-requeue:" + id + ":" + UuidV7.generate(), id);
        }
        return updated == 1;
    }

    public int manualRequeueCount(UUID planId) {
        if (planId == null) {
            return 0;
        }
        Integer count = db.queryForObject(
                "select count(*) from worker_events where plan_id = ? and event_type = 'PLAN_MANUAL_REQUEUE'",
                Integer.class, planId);
        return count != null ? count : 0;
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
     * Phase 14: the Ashby counterpart of {@link #prepareRealSubmission},
     * mirroring its exact safety shape (same {@code READY_TO_SUBMIT and
     * submit_approved=true} atomic guard, same "return to PREPARED so the
     * existing claim-next/resume path picks it up" behaviour) but gated on
     * {@code "ASHBY".equals(planType)} and appending a single, parameter-less
     * {@code ASHBY_SUBMIT_CLICK} step (id {@code ashby-submit-click}) — the
     * worker's own {@code #runAshbySubmit} locates the real submit button by
     * a fixed, live-verified class name
     * ({@code button.ashby-application-form-submit-button}), never a
     * caller-supplied selector, so no params are needed here. The worker's
     * own {@code #validateAshbyPlan} independently re-checks the same shape
     * (last step, exactly one, preceded by {@code ASHBY_APPLY}) before ever
     * running it. {@link #completeRealSubmissionWithResult} and
     * {@code AutomationController}'s {@code /complete} endpoint need no
     * changes at all — both are already ATS-agnostic (no {@code planType}
     * check), so Ashby's real submission inherits every downstream safety
     * guarantee (the single-submission-per-application unique index, the
     * worker-request/non-blank-evidence guards) with zero changes to shared
     * infrastructure.
     *
     * @return false if the plan was not in that exact state, or is not an
     *         ASHBY plan
     */
    public boolean prepareAshbySubmission(UUID id) {
        PlanRow current = findById(id).orElse(null);
        if (current == null || !"READY_TO_SUBMIT".equals(current.status()) || !current.submitApproved()) {
            return false;
        }
        Map<String, Object> stored = current.plan() == null ? Map.of() : current.plan();
        if (!"ASHBY".equals(stored.get("planType")) || !(stored.get("steps") instanceof List<?> existingSteps)) {
            return false;
        }
        List<Object> newSteps = new java.util.ArrayList<>(existingSteps);
        newSteps.add(Map.of(
                "id", "ashby-submit-click",
                "type", "ASHBY_SUBMIT_CLICK",
                "policy", "AUTO",
                "params", Map.of()));
        Map<String, Object> updated = new java.util.LinkedHashMap<>(stored);
        updated.put("steps", newSteps);
        try {
            return db.update("""
                    update automation_plans set plan=?::jsonb, status='PREPARED', updated_at=now()
                    where id=? and status='READY_TO_SUBMIT' and submit_approved=true
                    """, json.writeValueAsString(updated), id) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist Ashby submission step", e);
        }
    }

    /**
     * Phase 19: Workday submission preparation. Appends a WORKDAY_SUBMIT_CLICK step
     * to an approved Workday application plan and transitions back to PREPARED.
     */
    public boolean prepareWorkdaySubmission(UUID id) {
        PlanRow current = findById(id).orElse(null);
        if (current == null || !"READY_TO_SUBMIT".equals(current.status()) || !current.submitApproved()) {
            return false;
        }
        Map<String, Object> stored = current.plan() == null ? Map.of() : current.plan();
        if (!"WORKDAY".equals(stored.get("planType")) || !(stored.get("steps") instanceof List<?> existingSteps)) {
            return false;
        }
        List<Object> newSteps = new java.util.ArrayList<>(existingSteps);
        newSteps.add(Map.of(
                "id", "workday-submit-click",
                "type", "WORKDAY_SUBMIT_CLICK",
                "policy", "AUTO",
                "params", Map.of()));
        Map<String, Object> updated = new java.util.LinkedHashMap<>(stored);
        updated.put("steps", newSteps);
        try {
            return db.update("""
                    update automation_plans set plan=?::jsonb, status='PREPARED', updated_at=now()
                    where id=? and status='READY_TO_SUBMIT' and submit_approved=true
                    """, json.writeValueAsString(updated), id) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist Workday submission step", e);
        }
    }

    /**
     * Stage 3B: atomic transition queuing {@code UNIVERSAL_SUBMIT_CLICK} on an
     * explicitly approved UNIVERSAL plan. Refuses if any submit step or prior
     * {@code pre-submit-attempting} checkpoint already exists.
     */
    @Transactional
    public boolean prepareUniversalSubmission(UUID id) {
        var plan = findById(id).orElse(null);
        if (plan == null || !"READY_TO_SUBMIT".equals(plan.status()) || !plan.submitApproved()) {
            return false;
        }
        if (hasPreSubmitAttemptCheckpoint(id)) {
            return false;
        }
        Map<String, Object> stored = plan.plan() == null ? Map.of() : plan.plan();
        if (!"UNIVERSAL".equals(stored.get("planType"))) {
            return false;
        }
        List<Object> currentSteps = stored.get("steps") instanceof List<?> s ? List.copyOf(s) : List.of();
        for (Object o : currentSteps) {
            if (o instanceof Map<?, ?> step) {
                Object type = step.get("type");
                if ("UNIVERSAL_SUBMIT_CLICK".equals(type) || "REAL_SUBMIT_CLICK".equals(type) || "MOCK_SUBMIT".equals(type)) {
                    return false;
                }
            }
        }
        List<Object> newSteps = new java.util.ArrayList<>(currentSteps);
        newSteps.add(Map.of(
                "id", "universal-submit-click",
                "type", "UNIVERSAL_SUBMIT_CLICK",
                "policy", "AUTO",
                "params", Map.of("selector", "[data-robin-submit]", "phase", UniversalExecutionPhase.PRE_SUBMIT_ATTEMPTING.name())));
        Map<String, Object> updated = new java.util.LinkedHashMap<>(stored);
        updated.put("steps", newSteps);
        try {
            return db.update("""
                    update automation_plans set plan=?::jsonb, status='PREPARED', updated_at=now()
                    where id=? and status='READY_TO_SUBMIT' and submit_approved=true
                      and not """ + "\n" + SUBMIT_STEP_EXISTS_SQL,
                    json.writeValueAsString(updated), id) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist Universal submission step", e);
        }
    }

    /**
     * Stage 3B: atomically resumes a paused UNIVERSAL plan after the candidate
     * resolves discovered blockers and calls {@code /resume}. Refuses if
     * submit-approved or if any submit attempt exists.
     */
    @Transactional
    public boolean resumeUniversalPlan(UUID id, Map<String, Object> updatedPlan) {
        if (id == null || updatedPlan == null || hasPreSubmitAttemptCheckpoint(id)) {
            return false;
        }
        try {
            return db.update("""
                    update automation_plans
                    set plan=?::jsonb, status='PREPARED', result='[]'::jsonb, heartbeat_at=null, updated_at=now()
                    where id=? and status='AWAITING_APPROVAL' and submit_approved=false
                      and not """ + "\n" + SUBMIT_STEP_EXISTS_SQL + APPLICATION_NOT_ALREADY_SUBMITTED_SQL,
                    json.writeValueAsString(updatedPlan), id) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist resumed Universal plan", e);
        }
    }

    /**
     * Stage 3D: atomically returns a paused pre-submit plan from {@code HANDOFF_REQUIRED}
     * back to {@code PREPARED} after candidate login/security intervention. Refuses if any
     * submit approval or pre-submit checkpoint/step already exists.
     */
    @Transactional
    public boolean resumeFromHandoff(UUID id, Map<String, Object> updatedPlan) {
        if (id == null || updatedPlan == null || hasPreSubmitAttemptCheckpoint(id)) {
            return false;
        }
        try {
            return db.update("""
                    update automation_plans
                    set plan=?::jsonb, status='PREPARED', result='[]'::jsonb, heartbeat_at=null, updated_at=now()
                    where id=? and status='HANDOFF_REQUIRED' and submit_approved=false
                      and not """ + "\n" + SUBMIT_STEP_EXISTS_SQL + APPLICATION_NOT_ALREADY_SUBMITTED_SQL,
                    json.writeValueAsString(updatedPlan), id) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resume plan after handoff", e);
        }
    }

    /**
     * Reads durable {@code automation_steps} checkpoints for a plan in step order.
     */
    public List<Map<String, Object>> findSteps(UUID planId) {
        if (planId == null) return List.of();
        return db.query("""
                select step_key, step_index, status, result::text as result_json, screenshot_ref, created_at, updated_at
                from automation_steps
                where plan_id = ?
                order by step_index, created_at, id
                """, (rs, rowNum) -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("stepKey", rs.getString("step_key"));
            row.put("stepIndex", rs.getInt("step_index"));
            row.put("status", rs.getString("status"));
            row.put("result", readMap(rs.getString("result_json")));
            row.put("screenshotRef", rs.getString("screenshot_ref"));
            row.put("createdAt", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant().toString() : null);
            row.put("updatedAt", rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant().toString() : null);
            return row;
        }, planId);
    }

    /**
     * Stage 3F (Workstream G): records the {@code PRE_SUBMIT_ATTEMPTING} fence in ONE statement,
     * only while the plan is RUNNING and only if no fence exists yet. Always stored under the
     * fixed step key {@code pre-submit-attempting}, so concurrent attempts collide on the
     * unique (plan_id, step_key) index and exactly one can win.
     *
     * <p>Stage 3I Phase 6: the fence also requires the human submit approval to still stand
     * ({@code submit_approved=true}), read under a lock on the plan row, so an approval withdrawn
     * by {@link #invalidateApproval} before the fence is always seen (re-checked on the updated
     * row) and the click is refused.
     *
     * @return true only for the single caller whose fence was inserted
     */
    public boolean recordPreSubmitAttemptIfAbsent(UUID planId, int index, String status, Map<String, Object> result,
                                                  String screenshot) {
        if (planId == null) return false;
        try {
            String value = json.writeValueAsString(result == null ? Map.of() : result);
            return db.update("""
                    insert into automation_steps(id, plan_id, step_key, step_index, status, result, screenshot_ref)
                    select ?, ?, 'pre-submit-attempting', ?, ?, ?::jsonb, ?
                    where exists (select 1 from automation_plans
                                  where id = ? and status = 'RUNNING' and submit_approved = true
                                  for update)
                      and not exists (select 1 from automation_steps
                                      where plan_id = ?
                                        and (step_key = 'pre-submit-attempting'
                                             or coalesce(result->>'phase', '') = 'PRE_SUBMIT_ATTEMPTING'))
                    on conflict (plan_id, step_key) do nothing
                    """, UuidV7.generate(), planId, index, status, value, screenshot, planId, planId) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to record pre-submit attempt", e);
        }
    }

    /** True once a synchronous {@code PRE_SUBMIT_ATTEMPTING} checkpoint was persisted for this plan. */
    public boolean hasPreSubmitAttemptCheckpoint(UUID planId) {
        if (planId == null) return false;
        Integer n = db.queryForObject("""
                select count(*) from automation_steps
                where plan_id = ?
                  and (step_key = 'pre-submit-attempting'
                       or coalesce(result->>'phase', '') = 'PRE_SUBMIT_ATTEMPTING')
                """, Integer.class, planId);
        return n != null && n > 0;
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
     *
     * <p><b>Sprint 2.2.1:</b> the status and the worker's structured outcome
     * evidence are written by ONE guarded statement. There is no variant that
     * reaches {@code SUBMITTED} without a result, and no path that writes the
     * result in a separate statement after the transition. Callers run this
     * inside the same transaction that records the worker's evidence row
     * ({@code SubmissionCompletionService}).
     *
     * @return false (nothing written) unless the plan was RUNNING with submit_approved=true
     */
    public boolean completeRealSubmissionWithResult(UUID id, List<Object> result) {
        if (result == null || result.isEmpty()) {
            throw new IllegalArgumentException("a real submission cannot be recorded without its outcome evidence");
        }
        String value;
        try {
            value = json.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialise submission outcome result", e);
        }
        return db.update("""
                update automation_plans set status='SUBMITTED', result=?::jsonb, heartbeat_at=null, updated_at=now()
                where id=? and status='RUNNING' and submit_approved=true
                """, value, id) == 1;
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
     * <p>Stage 3I Phase 6: a UNIVERSAL submit pass that a worker already claimed (RUNNING) is
     * still before the click until its {@code PRE_SUBMIT_ATTEMPTING} fence is recorded, so its
     * approval is withdrawn too ({@code submit_approved=false}; the status and steps stay, the
     * worker owns the run). The fence insert requires {@code submit_approved=true} under the same
     * plan-row lock, so the click is refused. Once the fence exists the attempt is in flight and
     * the approval is left as it was: it cannot be un-clicked, and the receipt records the
     * documents that were sent.
     *
     * @return how many approvals were withdrawn
     */
    @Transactional
    public int invalidateApproval(UUID applicationId) {
        if (applicationId == null) return 0;
        int withdrawn = withdrawQueuedApproval(applicationId);
        // Stage 3I Phase 7: enforce symmetrically across ALL submit-capable ATS adapters
        // (GREENHOUSE, ASHBY, WORKDAY, UNIVERSAL), not only UNIVERSAL.
        // Row lock first, then the fence check in a new statement (READ COMMITTED: it sees a
        // fence committed by a concurrent recordPreSubmitAttemptIfAbsent that held the lock).
        List<UUID> running = db.query("""
                /* invalidate-running */
                select id from automation_plans
                where application_id = ? and status = 'RUNNING' and submit_approved = true
                for update
                """, (rs, n) -> (UUID) rs.getObject(1), applicationId);
        for (UUID planId : running) {
            if (hasPreSubmitAttemptCheckpoint(planId)) continue;
            withdrawn += db.update("""
                    update automation_plans set submit_approved = false, updated_at = now()
                    where id = ? and status = 'RUNNING' and submit_approved = true
                    """, planId);
        }
        return withdrawn;
    }

    /** Invalidates queued and un-fenced RUNNING approvals across every plan owned by a profile. */
    @Transactional
    public int invalidateApprovalsForProfile(UUID profileId) {
        if (profileId == null) return 0;
        int withdrawn = db.update("""
                update automation_plans
                set submit_approved = false,
                    status = 'AWAITING_APPROVAL',
                    plan = case
                        when jsonb_typeof(plan->'steps') = 'array' then
                            jsonb_set(plan, '{steps}',
                                coalesce((
                                    select jsonb_agg(s order by ord)
                                    from jsonb_array_elements(plan->'steps') with ordinality as t(s, ord)
                                    where s->>'type' not in ('REAL_SUBMIT_CLICK', 'ASHBY_SUBMIT_CLICK', 'WORKDAY_SUBMIT_CLICK', 'UNIVERSAL_SUBMIT_CLICK', 'MOCK_SUBMIT')
                                ), '[]'::jsonb))
                        else plan
                    end,
                    updated_at = now()
                where profile_id = ? and submit_approved = true and status in ('READY_TO_SUBMIT', 'PREPARED')
                """, profileId);
        List<UUID> running = db.query("""
                select id from automation_plans
                where profile_id = ? and status = 'RUNNING' and submit_approved = true
                for update
                """, (rs, n) -> (UUID) rs.getObject(1), profileId);
        for (UUID planId : running) {
            if (hasPreSubmitAttemptCheckpoint(planId)) continue;
            withdrawn += db.update("""
                    update automation_plans set submit_approved = false, updated_at = now()
                    where id = ? and status = 'RUNNING' and submit_approved = true
                    """, planId);
        }
        return withdrawn;
    }

    public enum WithdrawalStatus {
        WITHDRAWN,
        ALREADY_FENCED,
        NOT_WITHDRAWABLE,
        NOT_FOUND
    }

    /**
     * Explicit candidate withdrawal of a plan's submit approval under plan-row lock.
     * Queued plans return to {@code AWAITING_APPROVAL} with submit steps removed; running
     * pre-fence plans clear {@code submit_approved=false}; plans already past the fence
     * return {@link WithdrawalStatus#ALREADY_FENCED}.
     */
    @Transactional
    public WithdrawalStatus withdrawPlanApproval(UUID planId, UUID profileId) {
        if (planId == null || profileId == null) return WithdrawalStatus.NOT_FOUND;
        List<PlanRow> locked = db.query(
                "select " + PLAN_COLUMNS + " from automation_plans where id = ? and profile_id = ? for update",
                (rs, n) -> mapRow(rs), planId, profileId);
        if (locked.isEmpty()) return WithdrawalStatus.NOT_FOUND;
        PlanRow plan = locked.get(0);
        if (hasPreSubmitAttemptCheckpoint(planId) || "SUBMITTED".equals(plan.status())) {
            return WithdrawalStatus.ALREADY_FENCED;
        }
        if ("READY_TO_SUBMIT".equals(plan.status()) || "PREPARED".equals(plan.status())) {
            int updated = db.update("""
                    update automation_plans
                    set submit_approved = false,
                        status = 'AWAITING_APPROVAL',
                        plan = case
                            when jsonb_typeof(plan->'steps') = 'array' then
                                jsonb_set(plan, '{steps}',
                                    coalesce((
                                        select jsonb_agg(s order by ord)
                                        from jsonb_array_elements(plan->'steps') with ordinality as t(s, ord)
                                        where s->>'type' not in ('REAL_SUBMIT_CLICK', 'ASHBY_SUBMIT_CLICK', 'WORKDAY_SUBMIT_CLICK', 'UNIVERSAL_SUBMIT_CLICK', 'MOCK_SUBMIT')
                                    ), '[]'::jsonb))
                            else plan
                        end,
                        updated_at = now()
                    where id = ? and profile_id = ? and submit_approved = true and status in ('READY_TO_SUBMIT', 'PREPARED')
                    """, planId, profileId);
            return updated == 1 ? WithdrawalStatus.WITHDRAWN : WithdrawalStatus.NOT_WITHDRAWABLE;
        }
        if ("RUNNING".equals(plan.status()) && plan.submitApproved()) {
            int updated = db.update("""
                    update automation_plans set submit_approved = false, updated_at = now()
                    where id = ? and profile_id = ? and status = 'RUNNING' and submit_approved = true
                    """, planId, profileId);
            return updated == 1 ? WithdrawalStatus.WITHDRAWN : WithdrawalStatus.NOT_WITHDRAWABLE;
        }
        return WithdrawalStatus.NOT_WITHDRAWABLE;
    }

    private int withdrawQueuedApproval(UUID applicationId) {
        return db.update("""
                update automation_plans
                set submit_approved = false,
                    status = 'AWAITING_APPROVAL',
                    plan = case
                        when jsonb_typeof(plan->'steps') = 'array' then
                            jsonb_set(plan, '{steps}',
                                coalesce((
                                    select jsonb_agg(s order by ord)
                                    from jsonb_array_elements(plan->'steps') with ordinality as t(s, ord)
                                    where s->>'type' not in ('REAL_SUBMIT_CLICK', 'ASHBY_SUBMIT_CLICK', 'WORKDAY_SUBMIT_CLICK', 'UNIVERSAL_SUBMIT_CLICK', 'MOCK_SUBMIT')
                                ), '[]'::jsonb))
                        else plan
                    end,
                    updated_at = now()
                where application_id = ? and submit_approved = true and status in ('READY_TO_SUBMIT', 'PREPARED')
                """, applicationId);
    }

    public record RecoveryResult(int recovered, int quarantined) {}

    /**
     * Stage 3F (Workstream G): a plan is never resumed when its application has already
     * been submitted by any attempt (another plan in SUBMITTED, or the application itself
     * recorded as APPLICATION_SUBMITTED). Appended to the guarded resume UPDATEs.
     */
    private static final String APPLICATION_NOT_ALREADY_SUBMITTED_SQL = """

              and not exists (select 1 from automation_plans other
                              where other.application_id = automation_plans.application_id and other.status = 'SUBMITTED')
              and not exists (select 1 from applications app
                              where app.id = automation_plans.application_id and app.status = 'APPLICATION_SUBMITTED')""";

    private static final String SUBMIT_STEP_EXISTS_SQL = """
            exists (
                select 1
                from jsonb_array_elements(
                    case when jsonb_typeof(plan->'steps') = 'array' then plan->'steps' else '[]'::jsonb end
                ) s
                where s->>'type' in ('REAL_SUBMIT_CLICK', 'ASHBY_SUBMIT_CLICK', 'WORKDAY_SUBMIT_CLICK', 'UNIVERSAL_SUBMIT_CLICK', 'MOCK_SUBMIT')
            )""";

    private static final String STALE_SUBMIT_RECONCILIATION_JSON =
            "[{\"type\":\"SUBMISSION_RECONCILIATION\",\"category\":\"STALE_SUBMIT_EXECUTION\","
            + "\"evidence\":\"Worker heartbeat expired during a submit-capable run; moved to RECONCILIATION_REQUIRED instead of auto-requeueing.\"}]";

    @Transactional
    public int recoverStale(Instant cutoff) {
        return recoverStaleDetailed(cutoff).recovered();
    }

    @Transactional
    public RecoveryResult recoverStaleDetailed(Instant cutoff) {
        java.sql.Timestamp ts = java.sql.Timestamp.from(cutoff);
        int reclaimed = db.update("""
                update automation_plans
                set status='PREPARED', heartbeat_at=null, updated_at=now()
                where status='RUNNING' and heartbeat_at < ?
                  and submit_approved = false
                  and not """ + "\n" + SUBMIT_STEP_EXISTS_SQL,
                ts);
        int quarantined = db.update("""
                update automation_plans
                set status='RECONCILIATION_REQUIRED', submit_approved=false, heartbeat_at=null,
                    result=?::jsonb, updated_at=now()
                where status='RUNNING' and heartbeat_at < ?
                  and (submit_approved = true or """ + "\n" + SUBMIT_STEP_EXISTS_SQL + ")",
                STALE_SUBMIT_RECONCILIATION_JSON, ts);
        if (quarantined > 0) {
            log.warn("Quarantined {} stale submit-capable automation plan(s) to RECONCILIATION_REQUIRED instead of PREPARED",
                    quarantined);
        }
        return new RecoveryResult(reclaimed, quarantined);
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
