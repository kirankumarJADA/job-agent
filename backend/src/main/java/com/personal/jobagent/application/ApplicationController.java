package com.personal.jobagent.application;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * /api/v1/applications — read and transition the caller's own applications.
 *
 * <p>Two things this controller deliberately does NOT take from the request:
 *
 * <ol>
 *   <li><b>whose application it is.</b> The owner is the caller's profile from
 *       the session; there is no profile parameter to tamper with, and a foreign
 *       application id resolves to 404 rather than to someone else's row.</li>
 *   <li><b>the actor recorded in the audit trail.</b> {@code
 *       TransitionRequest.actor} used to be passed straight through to
 *       application_events and audit_logs, so any authenticated caller could
 *       write an arbitrary actor string — including another account's email —
 *       into the permanent, insert-only audit log. The field is still accepted
 *       so existing clients keep working, but it is ignored; the actor is the
 *       authenticated principal. Because audit attribution is derived from the
 *       actor email, trusting that field would also have made foreign audit rows
 *       appear in the victim's Logs &amp; Audit page.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApplicationController.class);
    private final ApplicationStatusService service;
    private final OwnerContext ownerContext;
    private final NotificationService notifications;
    private final com.personal.jobagent.track.ApplicationTrackingService tracking;
    public ApplicationController(ApplicationStatusService service, OwnerContext ownerContext, NotificationService notifications,
                                 com.personal.jobagent.track.ApplicationTrackingService tracking){this.service=service;this.ownerContext=ownerContext;this.notifications=notifications;this.tracking=tracking;}
    public record TransitionRequest(String targetStatus,String eventKey,String actor,Map<String,Object> context){}
    /**
     * The caller's own applications, newest first. Each row carries {@code track}:
     * the evidence-reconciled status (Phase 8.3). {@code status} stays Robin's
     * internal workflow status, unchanged in meaning. If tracking cannot be
     * computed the list is still served, with {@code track.availability =
     * UNAVAILABLE} rather than a guessed status.
     */
    @GetMapping public ResponseEntity<?> list(){
        UUID profileId = ownerContext.profileIdOrNull();
        List<Map<String,Object>> items = service.listForOwner(profileId);
        Map<UUID, Map<String,Object>> track;
        try {
            track = tracking.summaries(profileId);
        } catch (RuntimeException e) {
            log.warn("Tracking summaries unavailable: {}", e.getClass().getSimpleName());
            track = null;
        }
        for (Map<String,Object> item : items) {
            Object id = item.get("id");
            Map<String,Object> summary = track == null ? null : track.get(id instanceof UUID u ? u : null);
            item.put("track", summary != null ? summary : Map.of("availability", "UNAVAILABLE"));
        }
        return ResponseEntity.ok(Map.of("items", items));
    }
    @GetMapping("/{id}") public ResponseEntity<?> get(@PathVariable UUID id){return service.find(ownerContext.profileIdOrNull(), id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
    @GetMapping("/{id}/timeline") public ResponseEntity<?> timeline(@PathVariable UUID id){UUID profileId=ownerContext.profileIdOrNull();if(!service.existsForOwner(profileId, id))return ResponseEntity.notFound().build();return ResponseEntity.ok(Map.of("items",service.timeline(profileId, id)));}
    @PostMapping("/{id}/status") public ResponseEntity<?> transition(@PathVariable UUID id,@RequestBody TransitionRequest r){
        try{return ResponseEntity.ok(service.apply(ownerContext.profileIdOrNull(), id, r.targetStatus(), r.eventKey(), ownerContext.actorOr("SYSTEM"), r.context()));}
        catch(NoSuchElementException e){return ResponseEntity.notFound().build();}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
        catch(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("error",e.getMessage()));}}

    /**
     * Owner-requested re-preparation. Preparation failures are consumed, not
     * automatically retried; this endpoint is the deliberate follow-up
     * action. It re-emits the preparation trigger through the transactional
     * outbox, so the pipeline handler re-runs exactly the never-run or FAILED
     * steps (already-succeeded steps are skipped — no duplicate artifacts)
     * and the automation plan is (re)created idempotently.
     */
    @PostMapping("/{id}/re-prepare") public ResponseEntity<?> rePrepare(@PathVariable UUID id){
        UUID profileId = ownerContext.profileIdOrNull();
        Map<String,Object> application = service.find(profileId, id).orElse(null);
        if (application == null || application.get("jobId") == null) return ResponseEntity.notFound().build();
        notifications.emit(new NotificationService.NotificationCommand(
                NotificationEvents.APPLICATION_REPREPARATION_REQUESTED,
                "APPLICATION",
                id,
                Map.of("application_id", id.toString(),
                        "profile_id", profileId.toString(),
                        "job_id", String.valueOf(application.get("jobId"))),
                UuidV7.generate(),
                null));
        return ResponseEntity.accepted().body(Map.of("status", "REPREPARATION_QUEUED"));
    }
}
