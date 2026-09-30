package com.personal.jobagent.automation;

import com.personal.jobagent.common.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Creates inspection-only automation plans from prepared applications.
 *
 * <p>An inspection plan navigates to the job's application URL, takes a
 * screenshot, and stops. It never fills a form, uploads a file, or submits
 * anything. This is the Phase 2 execution foundation: it proves the
 * queue → claim → execute → report path works end-to-end without touching
 * a real employer's ATS.
 *
 * <p>The plan is stored in {@code automation_plans} in the same format the
 * existing worker expects, so no worker-side schema changes are needed
 * beyond recognising the {@code INSPECTION} plan type (which skips package
 * validation, since inspection plans do not interact with files).
 *
 * <p>Idempotent: the idempotency key is {@code inspect:<applicationId>},
 * scoped per profile (V022), so re-running preparation for the same
 * application never creates a duplicate plan.
 */
@Service
public class InspectionPlanService {

    private static final Logger log = LoggerFactory.getLogger(InspectionPlanService.class);

    private final AutomationPlanRepository plans;
    private final JdbcTemplate db;

    public InspectionPlanService(AutomationPlanRepository plans, JdbcTemplate db) {
        this.plans = plans;
        this.db = db;
    }

    /**
     * Creates a PREPARED inspection plan for the given application, or returns
     * the existing plan id if one was already created (idempotency key
     * collision).
     *
     * @return the plan id, or empty if the application has no usable
     *         application URL (nothing to inspect)
     */
    public Optional<UUID> createInspectionPlan(UUID profileId, UUID applicationId, UUID jobId) {
        String applicationUrl = applicationUrl(jobId);
        if (applicationUrl == null || applicationUrl.isBlank()) {
            log.info("No application URL for job {} — skipping inspection plan", jobId);
            return Optional.empty();
        }

        List<AutomationPlan.Step> steps = List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO",
                        Map.of("url", applicationUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO",
                        Map.of()),
                new AutomationPlan.Step("safety-check", "POLICY_CHECK", "AUTO",
                        Map.of("action", "INSPECT_ONLY"))
        );

        String idempotencyKey = "inspect:" + applicationId;

        UUID planId = plans.create(profileId, applicationId, jobId,
                applicationUrl, idempotencyKey, steps);

        // Enrich the stored plan JSONB with the worker-compatible envelope so
        // the claim-next endpoint can return a self-contained plan object.
        enrichPlanPayload(planId, applicationId, jobId, applicationUrl, steps);

        log.info("Inspection plan {} created for application {} (job={}, url={})",
                planId, applicationId, jobId, applicationUrl);
        return Optional.of(planId);
    }

    private void enrichPlanPayload(UUID planId, UUID applicationId, UUID jobId,
                                    String targetUrl, List<AutomationPlan.Step> steps) {
        try {
            // Build the worker-compatible plan envelope. The plan column was
            // written by AutomationPlanRepository.create() with the generic
            // format; overwrite it with the richer structure the worker needs.
            List<Map<String, Object>> stepMaps = steps.stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", s.id());
                        m.put("type", s.type());
                        m.put("policy", s.policy());
                        m.put("params", s.params());
                        return m;
                    })
                    .toList();

            Map<String, Object> workerPlan = new LinkedHashMap<>();
            workerPlan.put("planType", "INSPECTION");
            workerPlan.put("version", 1);
            workerPlan.put("correlation", Map.of(
                    "jobId", jobId.toString(),
                    "applicationId", applicationId.toString()));
            workerPlan.put("steps", stepMaps);
            workerPlan.put("safetyContract", AutomationPlan.SAFETY_CONTRACT);

            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            db.update("update automation_plans set plan = ?::jsonb where id = ?",
                    json.writeValueAsString(workerPlan), planId);
        } catch (Exception e) {
            log.warn("Could not enrich plan payload for {}: {}", planId, e.getMessage());
        }
    }

    private String applicationUrl(UUID jobId) {
        return db.query("select application_url from jobs where id = ?",
                (rs, n) -> rs.getString(1), jobId)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
}
