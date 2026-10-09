package com.personal.jobagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.application.ApplicationDecisionService;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.events.Envelope;
import com.personal.jobagent.events.EventHandler;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.automation.GreenhouseExecutionPlanService;
import com.personal.jobagent.automation.InspectionPlanService;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.qa.ApplicationAnswerService;
import com.personal.jobagent.resume.ResumeAtsIntelligenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The pipeline's event-driven half, consuming the two events the match and
 * creation stages emit through the existing transactional outbox:
 *
 * <ul>
 *   <li>{@code job.matched} with recommendation APPLY → create exactly one
 *       live application per (profile, job). Idempotent: replays and
 *       re-discoveries resolve to the existing row (partial unique index
 *       {@code applications_profile_job_live_uq} + pre-check).</li>
 *   <li>{@code application.created} → run the existing preparation services
 *       against the new application (tailored CV → cover letter → answer
 *       draft) and mark preparation outcomes on the application timeline.
 *       Each step is independent: one failing step is recorded and does not
 *       block the others or corrupt the application's state.</li>
 * </ul>
 *
 * <p>Both event types are emitted transactionally with the state that
 * triggered them (the match upsert + the application insert), so at-least-once
 * delivery plus the idempotency above yields exactly-once effect. Dispatch to
 * this handler happens alongside the existing NotificationEventHandler — the
 * in-process publisher invokes every handler that supports a type.
 *
 * <p>Failure policy: creation failures rethrow (the outbox retries up to its
 * attempt budget, and creation is idempotent under retry); preparation
 * failures are recorded on the application timeline and consumed — an LLM
 * outage must not put the dispatch loop into a hot retry loop, and
 * re-preparation is a deliberate follow-up action, not an automatic one.
 */
@Component
public class ApplicationPipelineEventHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(ApplicationPipelineEventHandler.class);
    static final String CONSUMER_NAME = "application-pipeline";

    private final ApplicationPipelineService pipeline;
    private final InspectionPlanService inspectionPlanService;
    private final GreenhouseExecutionPlanService greenhousePlanService;
    private final ResumeAtsIntelligenceService resumeService;
    private final CoverLetterService coverLetterService;
    private final ApplicationAnswerService answerService;
    private final JobRepository jobRepository;
    private final NotificationService notifications;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AutomationMetrics metrics;
    private final ApplicationDecisionService decisionService;

    public ApplicationPipelineEventHandler(ApplicationPipelineService pipeline,
                                           InspectionPlanService inspectionPlanService,
                                           GreenhouseExecutionPlanService greenhousePlanService,
                                           ResumeAtsIntelligenceService resumeService,
                                           CoverLetterService coverLetterService,
                                           ApplicationAnswerService answerService,
                                           JobRepository jobRepository,
                                           NotificationService notifications,
                                           JdbcTemplate db,
                                           ObjectMapper json,
                                           AutomationMetrics metrics,
                                           ApplicationDecisionService decisionService) {
        this.pipeline = pipeline;
        this.inspectionPlanService = inspectionPlanService;
        this.greenhousePlanService = greenhousePlanService;
        this.resumeService = resumeService;
        this.coverLetterService = coverLetterService;
        this.answerService = answerService;
        this.jobRepository = jobRepository;
        this.notifications = notifications;
        this.db = db;
        this.json = json;
        this.metrics = metrics;
        this.decisionService = decisionService;
    }

    @Override public String consumerName() { return CONSUMER_NAME; }

    @Override
    public boolean supports(String eventType) {
        return NotificationEvents.JOB_MATCHED.equals(eventType)
                || NotificationEvents.APPLICATION_CREATED.equals(eventType)
                || NotificationEvents.JOB_DISCOVERED.equals(eventType)
                || NotificationEvents.APPLICATION_REPREPARATION_REQUESTED.equals(eventType);
    }

    @Override
    public void handle(Envelope envelope) {
        Map<String, Object> payload = asMap(envelope.payload());
        switch (envelope.type()) {
            case NotificationEvents.JOB_DISCOVERED -> pipeline.onJobIngested(
                    uuid(payload.get("job_id")), String.valueOf(payload.getOrDefault("action", "INSERTED")));
            case NotificationEvents.JOB_MATCHED -> handleMatched(payload);
            case NotificationEvents.APPLICATION_CREATED -> handleCreated(payload);
            case NotificationEvents.APPLICATION_REPREPARATION_REQUESTED -> {
                metrics.rePreparationRequested();
                handleCreated(payload);
            }
            default -> log.debug("Ignoring unsupported event {}", envelope.type());
        }
    }

    private void handleMatched(Map<String, Object> payload) {
        // REVIEW/SKIP are valid match decisions: the score and recommendation
        // are already stored in job_matches for the candidate to act on — no
        // application is created for them by design.
        if (!"APPLY".equals(payload.get("recommendation"))) {
            log.debug("Match recommendation {} — no automatic application", payload.get("recommendation"));
            return;
        }
        UUID profileId = uuid(payload.get("profile_id"));
        UUID jobId = uuid(payload.get("job_id"));
        if (profileId == null || jobId == null) {
            log.warn("job.matched event without usable ids (profile_id/job_id) — skipping creation");
            return;
        }
        // Phase 5: the decision engine sits between the scorer and the
        // creator. Mode (MANUAL/ASSISTED/CONTROLLED_AUTO) and the per-profile
        // quota decide whether an APPLY match auto-applies or queues for
        // human review; every outcome is persisted in application_decisions.
        int score = payload.get("score") instanceof Number n ? n.intValue() : 0;
        var decision = decisionService.decide(profileId, jobId, score, "APPLY");

        if (java.util.Set.of("APPROVED", "REJECTED", "EXPIRED", "PAUSED", "SKIP").contains(decision.decision())) {
            log.debug("Match replay for profile={} job={} ignored because decision is terminal ({})",
                    profileId, jobId, decision.decision());
            return;
        }

        if (!"AUTO_APPLY".equals(decision.decision())) {
            log.info("Match profile={} job={} queued for review ({}): {}",
                    profileId, jobId, decision.decision(), decision.reason());
            try {
                notifications.emit(new NotificationService.NotificationCommand(
                        NotificationEvents.APPROVAL_REQUIRED,
                        "APPLICATION_DECISION",
                        jobId,
                        Map.of("profile_id", profileId.toString(),
                                "job_id", jobId.toString(),
                                "score", score,
                                "decision", decision.decision(),
                                "reason", decision.reason()),
                        UuidV7.generate(),
                        null));
            } catch (Exception e) {
                log.warn("Failed to emit {} notification: {}", NotificationEvents.APPROVAL_REQUIRED, e.getMessage());
            }
            return;
        }

        var created = pipeline.createApplicationFromMatch(profileId, jobId);
        if (created == null || created.applicationId() == null) {
            log.warn("Pipeline returned no application for profile={} job={} — skipping", profileId, jobId);
            return;
        }
        log.info("APPLY match processed: application={} created={}", created.applicationId(), created.created());
    }

    private void handleCreated(Map<String, Object> payload) {
        UUID applicationId = uuid(payload.get("application_id"));
        UUID profileId = uuid(payload.get("profile_id"));
        UUID jobId = uuid(payload.get("job_id"));
        if (applicationId == null || profileId == null || jobId == null) {
            log.warn("application.created event without usable ids — skipping preparation");
            return;
        }
        if (!applicationExists(applicationId)) {
            log.warn("application.created for missing application {} — skipping preparation", applicationId);
            return;
        }

        String company = jobRepository.findById(jobId)
                .map(JobRecord::companyNameRaw)
                .filter(c -> c != null && !c.isBlank())
                .orElse("the company");

        log.info("Preparing application {} (profile={} job={})", applicationId, profileId, jobId);
        // Retry safety: on a re-preparation replay (or an outbox redelivery of
        // application.created), a step whose latest timeline status is OK has
        // already committed its artifact and MUST NOT run again — cover
        // letters, for instance, are append-only versions, so re-running the
        // step would silently produce a duplicate. Only never-run or FAILED
        // steps execute.
        Map<String, String> lastStatus = lastPreparationStatusByStep(applicationId);
        boolean cv = runStep(applicationId, "CV", lastStatus,
                () -> resumeService.tailor(profileId, jobId, applicationId));
        boolean cover = runStep(applicationId, "COVER_LETTER", lastStatus,
                () -> coverLetterService.generateCoverLetter(profileId, jobId, applicationId));
        boolean answer = runStep(applicationId, "ANSWERS", lastStatus,
                () -> answerService.draftAnswer(profileId, jobId, applicationId,
                        "Why do you want to work at " + company + "?"));
        metrics.preparationStep("CV", cv ? "OK" : "FAILED");
        metrics.preparationStep("COVER_LETTER", cover ? "OK" : "FAILED");
        metrics.preparationStep("ANSWERS", answer ? "OK" : "FAILED");

        if (cv && cover && answer) {
            notifications.emit(new NotificationService.NotificationCommand(
                    NotificationEvents.APPLICATION_PREPARED,
                    "APPLICATION",
                    applicationId,
                    Map.of(
                            "application_id", applicationId.toString(),
                            "profile_id", profileId.toString(),
                            "job_id", jobId.toString(),
                            "message", "Application package ready",
                            "detail", "Tailored CV, cover letter and draft answers are attached to the application.",
                            "dedup_key", "application-prepared:" + applicationId
                    ),
                    UuidV7.generate(),
                    null));
            // Greenhouse applications use the controlled, allowlisted form
            // plan; every other connector retains the inspection-only path.
            try {
                String applicationUrl = jobRepository.findById(jobId)
                        .map(JobRecord::applicationUrl).orElse(null);
                if (greenhousePlanService.handles(applicationUrl)) {
                    greenhousePlanService.createExecutionPlan(profileId, applicationId, jobId)
                            .ifPresent(planId -> {
                                record(applicationId, "GREENHOUSE_PLAN_CREATED",
                                        Map.of("plan_id", planId.toString()));
                                log.info("Greenhouse plan {} queued for application {}", planId, applicationId);
                            });
                } else {
                    inspectionPlanService.createInspectionPlan(profileId, applicationId, jobId)
                            .ifPresent(planId -> {
                                record(applicationId, "INSPECTION_PLAN_CREATED",
                                        Map.of("plan_id", planId.toString()));
                                log.info("Inspection plan {} queued for application {}", planId, applicationId);
                            });
                }
            } catch (Exception e) {
                record(applicationId, "AUTOMATION_PLAN_FAILED",
                        Map.of("error", e.getClass().getSimpleName()));
                log.warn("Automation plan creation failed for application {}: {}",
                        applicationId, e.getMessage());
            }

            log.info("Application {} fully prepared and queued (READY_TO_APPLY)", applicationId);
        } else {
            log.warn("Application {} preparation incomplete (cv={} cover={} answers={}) — state unchanged, "
                    + "failures recorded on the timeline", applicationId, cv, cover, answer);
        }
    }

    /** Runs one preparation step, recording its outcome on the application timeline. */
    private boolean step(UUID applicationId, String step, Runnable action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step);
        try {
            action.run();
            payload.put("status", "OK");
            record(applicationId, "PREPARATION", payload);
            return true;
        } catch (Exception e) {
            payload.put("status", "FAILED");
            payload.put("error", e.getClass().getSimpleName());
            record(applicationId, "PREPARATION", payload);
            log.warn("Preparation step {} failed for application {}: {}", step, applicationId, e.getMessage());
            return false;
        }
    }

    /** Runs one preparation step unless its latest timeline status is already OK. */
    private boolean runStep(UUID applicationId, String step, Map<String, String> lastStatus, Runnable action) {
        if ("OK".equals(lastStatus.get(step))) {
            log.info("Preparation step {} already succeeded for application {} — skipping", step, applicationId);
            return true;
        }
        return step(applicationId, step, action);
    }

    /**
     * Latest PREPARATION outcome per step (CV / COVER_LETTER / ANSWERS) from
     * the application timeline. Empty on a first preparation; after a
     * re-preparation it carries the surviving OK statuses and the FAILED ones
     * that must be retried.
     */
    private Map<String, String> lastPreparationStatusByStep(UUID applicationId) {
        try {
            List<Map<String, Object>> rows = db.queryForList("""
                    select distinct on (payload->>'step') payload->>'step' as step, payload->>'status' as status
                    from application_events
                    where application_id = ? and type = 'PREPARATION'
                    order by payload->>'step', occurred_at desc
                    """, applicationId);
            Map<String, String> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Object step = row.get("step");
                Object status = row.get("status");
                if (step != null && status != null) result.put(String.valueOf(step), String.valueOf(status));
            }
            return result;
        } catch (Exception e) {
            // If the timeline cannot be read, the safe behaviour is to run the
            // steps: every preparation service is itself idempotent or
            // append-only, so a redundant run never loses data.
            log.warn("Could not read preparation timeline for {}: {}", applicationId, e.getMessage());
            return Map.of();
        }
    }

    private void record(UUID applicationId, String type, Map<String, Object> payload) {
        try {
            db.update("insert into application_events (id, application_id, type, payload, actor) values (?, ?, ?, ?::jsonb, 'SYSTEM')",
                    UuidV7.generate(), applicationId, type, json.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Could not record application event {}: {}", type, e.getMessage());
        }
    }

    private boolean applicationExists(UUID applicationId) {
        Integer count = db.queryForObject("select count(*) from applications where id = ?", Integer.class, applicationId);
        return count != null && count > 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object payload) {
        return payload instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static UUID uuid(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
