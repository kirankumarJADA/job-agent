package com.personal.jobagent.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapter.FormDescriptor;
import com.personal.jobagent.ats.GreenhouseAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Creates controlled Greenhouse form-fill plans without ever submitting an application. */
@Service
public class GreenhouseExecutionPlanService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GreenhouseExecutionPlanService.class);
    private static final Set<String> CONTACT_KEYS = Set.of(
            "first_name", "last_name", "email", "phone", "candidate-location", "location");

    private final AutomationPlanRepository plans;
    private final GreenhouseAdapter greenhouseAdapter;
    private final ExecutionPackageService executionPackages;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public GreenhouseExecutionPlanService(AutomationPlanRepository plans,
                                          GreenhouseAdapter greenhouseAdapter,
                                          ExecutionPackageService executionPackages,
                                          JdbcTemplate db,
                                          ObjectMapper json) {
        this.plans = plans;
        this.greenhouseAdapter = greenhouseAdapter;
        this.executionPackages = executionPackages;
        this.db = db;
        this.json = json;
    }

    public boolean handles(String applicationUrl) {
        return greenhouseAdapter.matchesUrl(applicationUrl);
    }

    @Transactional
    public Optional<UUID> createExecutionPlan(UUID profileId, UUID applicationId, UUID jobId) {
        if (profileId == null || applicationId == null || jobId == null) {
            throw new IllegalArgumentException("profile, application, and job correlation are required");
        }
        Map<String, Object> application = db.queryForMap("""
                select a.id, a.profile_id, a.job_id, j.application_url
                from applications a join jobs j on j.id = a.job_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String applicationUrl = (String) application.get("application_url");
        if (!handles(applicationUrl)) return Optional.empty();

        // ExecutionPackageService performs read-only inspection after the
        // application owner/job correlation has been established above.
        List<AutomationPlan.Step> baseSteps = List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO", Map.of("url", applicationUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO", Map.of()));
        UUID planId = plans.create(profileId, applicationId, jobId, applicationUrl,
                "greenhouse:" + applicationId, baseSteps);

        // An idempotent event replay must not rewrite a plan already claimed or
        // completed by a worker. A PREPARED row is invisible until this transaction commits.
        AutomationPlanRepository.PlanRow current = plans.findById(planId).orElseThrow();
        if (!"PREPARED".equals(current.status())) return Optional.of(planId);

        ExecutionPackageService.ExecutionPackage executionPackage =
                executionPackages.build(planId, profileId, applicationId, jobId);
        if (!applicationUrl.equals(executionPackage.expectedUrl())
                || !applicationId.equals(executionPackage.applicationId())
                || !jobId.equals(executionPackage.jobId())) {
            throw new IllegalStateException("GREENHOUSE_PACKAGE_CORRELATION_MISMATCH");
        }

        List<Map<String, Object>> fields = executionPackage.fields();
        List<AutomationPlan.Step> steps = new ArrayList<>(baseSteps);
        for (Map<String, Object> field : fields) {
            String key = (String) field.get("key");
            String classification = (String) field.get("classification");
            String htmlType = (String) field.get("htmlType");
            String selector = (String) field.get("selector");
            Object value = field.get("value");
            boolean verifiedContact = CONTACT_KEYS.contains(key)
                    && Set.of("text", "tel", "email").contains(htmlType);
            boolean confirmedQuestion = key != null && key.startsWith("question_")
                    && "application_answers (human-confirmed)".equals(field.get("valueSource"));
            if (!"SUPPORTED_AUTO".equals(classification) || !(value instanceof String text)
                    || text.isBlank() || selector == null || !selector.equals("#" + key)) continue;
            if (verifiedContact) {
                steps.add(new AutomationPlan.Step("fill-" + key, "FILL_FIELD", "AUTO",
                        Map.of("selector", selector, "value", text)));
            } else if (confirmedQuestion && Set.of("text", "email", "tel", "textarea").contains(htmlType)) {
                steps.add(new AutomationPlan.Step("fill-" + key, "FILL_FIELD", "AUTO",
                        Map.of("selector", selector, "value", text)));
            } else if (confirmedQuestion && "select".equals(htmlType)
                    && ((List<?>) field.get("options")).contains(text)) {
                steps.add(new AutomationPlan.Step("select-" + key, "SELECT", "AUTO",
                        Map.of("selector", selector, "value", text, "allowedOptions", field.get("options"))));
            } else if (confirmedQuestion && "radio".equals(htmlType)
                    && ((List<?>) field.get("options")).contains(text)) {
                steps.add(new AutomationPlan.Step("radio-" + key, "RADIO", "AUTO",
                        Map.of("selector", selector, "value", text, "allowedOptions", field.get("options"))));
            }
        }
        appendUploadStep(steps, fields, executionPackage.cv(), "resume", "cv");
        appendUploadStep(steps, fields, executionPackage.coverLetter(), "cover_letter", "coverLetter");
        steps.add(new AutomationPlan.Step("validate-form", "VALIDATE", "AUTO", Map.of()));
        persistWorkerPlan(planId, profileId, applicationId, jobId, applicationUrl,
                steps, executionPackage);
        log.info("GREENHOUSE plan {} prepared for application {} ({} deterministic contact fills)",
                planId, applicationId, steps.size() - baseSteps.size() - 1);
        return Optional.of(planId);
    }

    private void appendUploadStep(List<AutomationPlan.Step> steps, List<Map<String, Object>> fields,
                                  Map<String, Object> artifact, String fieldKey, String artifactKind) {
        if (artifact == null) return;
        fields.stream().filter(field -> fieldKey.equals(field.get("key"))).findFirst().ifPresent(field -> {
            if (!"file".equals(field.get("htmlType"))
                    || !"SUPPORTED_AUTO".equals(field.get("classification"))
                    || !(field.get("selector") instanceof String selector)) return;
            String versionId = (String) artifact.get("versionId");
            String sha256 = (String) artifact.get("sha256");
            if (versionId == null || sha256 == null) return;
            steps.add(new AutomationPlan.Step("upload-" + fieldKey, "UPLOAD_FILE", "AUTO", Map.of(
                    "selector", selector, "artifact", artifactKind, "expectedJobId", artifact.get("jobId"),
                    "expectedApplicationId", artifact.get("applicationId"), "expectedVersionId", versionId,
                    "sha256", sha256)));
        });
    }

    private void persistWorkerPlan(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                   String targetUrl, List<AutomationPlan.Step> steps,
                                   ExecutionPackageService.ExecutionPackage executionPackage) {
        try {
            Map<String, Object> workerPlan = new LinkedHashMap<>();
            workerPlan.put("planType", "GREENHOUSE");
            workerPlan.put("version", 1);
            workerPlan.put("correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()));
            workerPlan.put("targetUrl", targetUrl);
            workerPlan.put("steps", steps.stream().map(step -> Map.of(
                    "id", step.id(), "type", step.type(), "policy", step.policy(), "params", step.params())).toList());
            workerPlan.put("fields", executionPackage.fields());
            workerPlan.put("requiredGaps", executionPackage.requiredGaps());
            workerPlan.put("unsupported", executionPackage.unsupported());
            workerPlan.put("package", json.convertValue(executionPackage, new TypeReference<Map<String, Object>>() {}));
            workerPlan.put("safetyContract", AutomationPlan.SAFETY_CONTRACT);
            String body = json.writeValueAsString(workerPlan);
            int updated = db.update("update automation_plans set plan = ?::jsonb "
                    + "where id = ? and profile_id = ? and application_id = ? and status = 'PREPARED'",
                    body, planId, profileId, applicationId);
            if (updated != 1) throw new IllegalStateException("GREENHOUSE_PLAN_NOT_PREPARED");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Could not persist complete Greenhouse plan payload", e);
        }
    }
}
