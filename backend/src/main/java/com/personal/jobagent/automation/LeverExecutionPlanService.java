package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapter.FormDescriptor;
import com.personal.jobagent.ats.AtsAdapters;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6: the third functional ATS adapter, for Lever apply-form
 * applications.
 *
 * <p><b>Why this follows {@link GreenhouseExecutionPlanService}'s pattern,
 * not {@link ZohoRecruitExecutionPlanService}'s.</b> Verified directly
 * against a real, live Lever posting ({@code jobs.lever.co/palantir/...}):
 * unlike Zoho Recruit, Lever renders its entire apply form as server-side
 * HTML on the job page itself — the same property that makes Greenhouse's
 * backend-side inspection possible. {@link AtsAdapters.LeverAdapter} performs
 * the same kind of real, deterministic, read-only inspection
 * {@code GreenhouseAdapter} does (a plain GET plus a static-HTML parse — see
 * its own class comment for the live evidence), so this service builds the
 * field-by-field classification entirely on the backend, exactly like
 * Greenhouse's {@link ExecutionPackageService}, rather than needing a
 * live-DOM, label-text classification step the way Zoho Recruit's
 * client-rendered form requires.
 *
 * <p><b>Why the worker side still stays thin, like Zoho's.</b> Rather than
 * emitting one {@code FILL_FIELD}/{@code SELECT}/{@code RADIO} step per
 * field (Greenhouse's approach, which relies on extensive
 * {@code planType === 'GREENHOUSE'}-specific safety logic already woven
 * through {@code browser_worker.js}), this service emits ONE consolidated
 * {@code LEVER_APPLY} step carrying the complete, backend-computed field
 * list as its params. The worker's own handler
 * ({@code browser_worker.js#runLeverApply}, see its module comment) executes
 * exactly what the backend already decided — selector-validated against a
 * Lever-specific allowlist in {@code plan.js}, bounded and reviewable the
 * same way Greenhouse's own selectors are — rather than re-deciding
 * anything live. This keeps the already-delicate generic step dispatch in
 * {@code browser_worker.js} untouched for Greenhouse and Zoho Recruit, while
 * still giving Lever the SAME backend-computed precision Greenhouse has.
 *
 * <p><b>Submission.</b> Lever's real submission is gated behind an hCaptcha
 * widget tied to its submit button (verified live) — Robin never attempts to
 * solve or bypass a CAPTCHA, so this plan type never includes a submit step
 * and never can: {@link AutomationPlanRepository#prepareRealSubmission}
 * already refuses any plan whose {@code planType} is not the literal string
 * {@code "GREENHOUSE"}, so a LEVER plan is structurally incapable of
 * reaching real submission, with no additional code required here.
 *
 * <p><b>Documents.</b> {@link ApplyDocumentSelector} is ATS-agnostic (same
 * reasoning as {@code ZohoRecruitExecutionPlanService}'s own comment) and is
 * reused directly rather than going through the Greenhouse-coupled
 * {@link ExecutionPackageService}.
 *
 * <p><b>Recovery.</b> Mirrors Zoho Recruit's: the candidate's existing
 * {@code POST /applications/{id}/re-prepare} action reaches this method
 * again, and a terminal {@code FAILED}/{@code BLOCKED_ANTI_BOT} plan is
 * requeued to {@code PREPARED} and rebuilt with current data — never
 * automatic, only via that deliberate human action.
 */
@Service
public class LeverExecutionPlanService {
    private static final Logger log = LoggerFactory.getLogger(LeverExecutionPlanService.class);

    private final AutomationPlanRepository plans;
    private final AtsAdapters.LeverAdapter leverAdapter;
    private final ApplyDocumentSelector documentSelector;
    private final CoverLetterRepository letters;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final com.personal.jobagent.mailbox.RegistrationIdentityService registrationIdentities;

    @org.springframework.beans.factory.annotation.Autowired
    public LeverExecutionPlanService(AutomationPlanRepository plans, AtsAdapters.LeverAdapter leverAdapter,
                                     ApplyDocumentSelector documentSelector, CoverLetterRepository letters,
                                     JdbcTemplate db, ObjectMapper json,
                                     org.springframework.beans.factory.ObjectProvider<com.personal.jobagent.mailbox.RegistrationIdentityService> registrationIdentities) {
        this.plans = plans;
        this.leverAdapter = leverAdapter;
        this.documentSelector = documentSelector;
        this.letters = letters;
        this.db = db;
        this.json = json;
        this.registrationIdentities = registrationIdentities == null ? null : registrationIdentities.getIfAvailable();
    }

    public LeverExecutionPlanService(AutomationPlanRepository plans, AtsAdapters.LeverAdapter leverAdapter,
                                     ApplyDocumentSelector documentSelector, CoverLetterRepository letters,
                                     JdbcTemplate db, ObjectMapper json) {
        this(plans, leverAdapter, documentSelector, letters, db, json, null);
    }

    public boolean handles(String applicationUrl) {
        return leverAdapter.matchesUrl(applicationUrl);
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

        List<AutomationPlan.Step> baseSteps = List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO", Map.of("url", applicationUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO", Map.of()));
        UUID planId = plans.create(profileId, applicationId, jobId, applicationUrl,
                "lever:" + applicationId, baseSteps);

        AutomationPlanRepository.PlanRow current = plans.findById(planId).orElseThrow();
        if ("FAILED".equals(current.status()) || "BLOCKED_ANTI_BOT".equals(current.status())) {
            if (!plans.requeueFailedPlan(planId)) return Optional.of(planId);
        } else if (!"PREPARED".equals(current.status())) {
            return Optional.of(planId);
        }

        FormDescriptor descriptor;
        try {
            descriptor = leverAdapter.inspectForm(applicationUrl);
        } catch (IllegalStateException e) {
            // Inspection failure is honest and recoverable (the same
            // LEVER_INSPECTION_UNAVAILABLE prefix GreenhouseAdapter uses) —
            // never silently degrade to a plan built from nothing.
            throw new IllegalStateException("LEVER_PLAN_INSPECTION_FAILED: " + e.getMessage(), e);
        }

        Map<String, Object> candidateRow = db.queryForMap("""
                select u.email::text as email, u.display_name, p.phone::text as phone,
                       p.location::text as location, coalesce(p.links::text, '{}') as links
                from applications a join profiles p on p.id = a.profile_id
                join users u on u.id = p.user_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);

        ApplyDocumentSelector.DocumentSelection selection = selectDocuments(planId, profileId, applicationId, jobId);
        Map<String, Object> cvArtifact = selectCvArtifact(selection, planId, applicationId, jobId);
        Map<String, Object> coverLetterArtifact = selectCoverLetterArtifact(selection, planId, applicationId, jobId);

        String rawEmail = (String) candidateRow.get("email");
        String effectiveEmail = registrationIdentities == null ? rawEmail
                : registrationIdentities.resolveApplicationContactEmail(profileId, applicationUrl, rawEmail);

        var candidate = new LeverFieldMapper.CandidateData(
                effectiveEmail, (String) candidateRow.get("display_name"),
                (String) candidateRow.get("phone"), (String) candidateRow.get("location"),
                readJson(candidateRow.get("links")),
                cvArtifact != null, coverLetterArtifact != null,
                coverLetterArtifact == null ? null : coverLetterBody(selection, profileId));

        List<LeverFieldMapper.Answer> storedAnswers = db.query("""
                select id, question_text, answer_text, status, human_confirmed from application_answers
                where application_id = ? and profile_id = ? and job_id = ?
                order by created_at, id
                """, (rs, row) -> new LeverFieldMapper.Answer(
                (UUID) rs.getObject("id"), rs.getString("question_text"),
                rs.getString("answer_text"), rs.getString("status"), rs.getBoolean("human_confirmed")),
                applicationId, profileId, jobId);

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        List<Map<String, Object>> requiredGaps = new ArrayList<>();
        for (var field : descriptor.fields()) {
            var outcome = LeverFieldMapper.classifyField(field, candidate, storedAnswers);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", field.key());
            row.put("label", field.label() == null ? "" : field.label());
            row.put("htmlType", field.htmlType());
            row.put("required", field.required());
            row.put("selector", field.selector());
            row.put("options", field.options());
            row.put("classification", outcome.classification());
            row.put("valueSource", outcome.valueSource() == null ? "" : outcome.valueSource());
            // File fields are materialized as a separate upload action, not a text value.
            boolean isFileField = "resume".equals(field.key());
            row.put("value", isFileField ? "" : (outcome.value() == null ? "" : outcome.value()));
            row.put("reason", outcome.reason() == null ? "" : outcome.reason());
            fieldRows.add(row);

            boolean requiredUploadMissing = field.required() && isFileField && cvArtifact == null;
            boolean missingValue = field.required() && !isFileField
                    && (outcome.value() == null || outcome.value().isBlank())
                    && !LeverFieldMapper.AUTO.equals(outcome.classification())
                    && !LeverFieldMapper.REVIEW.equals(outcome.classification());
            if (missingValue || requiredUploadMissing) {
                requiredGaps.add(Map.of("key", field.key(), "label", field.label() == null ? "" : field.label(),
                        "classification", outcome.classification(),
                        "reason", outcome.reason() == null ? "required field has no safe value" : outcome.reason()));
            }
        }

        List<AutomationPlan.Step> steps = new ArrayList<>(baseSteps);
        Map<String, Object> leverApplyParams = new LinkedHashMap<>();
        leverApplyParams.put("fields", fieldRows);
        steps.add(new AutomationPlan.Step("lever-apply", "LEVER_APPLY", "AUTO", leverApplyParams));

        persistWorkerPlan(planId, profileId, applicationId, jobId, applicationUrl, steps,
                fieldRows, requiredGaps, cvArtifact, coverLetterArtifact);
        log.info("LEVER plan {} prepared for application {} ({} field(s) inspected, {} required gap(s))",
                planId, applicationId, fieldRows.size(), requiredGaps.size());
        return Optional.of(planId);
    }

    /**
     * The mapper needs the actual cover-letter text to fill into Lever's
     * single "comments" field (unlike Greenhouse, Lever has no separate
     * cover-letter upload). {@link ApplyDocumentSelector} only proves WHICH
     * version is approved/current; the body itself is read from the owning
     * record the same way {@code ExecutionPackageService} already does for
     * its own cover-letter artifact. A null/blocked selection, or a record
     * that has since vanished, yields no body — never a fabricated one.
     */
    private String coverLetterBody(ApplyDocumentSelector.DocumentSelection selection, UUID profileId) {
        if (selection == null || selection.blocked() || selection.coverLetter() == null) return null;
        return letters.findByIdForProfile(selection.coverLetter().versionId(), profileId)
                .map(com.personal.jobagent.coverletter.CoverLetterRecord::bodyMarkdown)
                .orElse(null);
    }

    private ApplyDocumentSelector.DocumentSelection selectDocuments(UUID planId, UUID profileId, UUID applicationId, UUID jobId) {
        try {
            return documentSelector.select(profileId, jobId, applicationId);
        } catch (Exception e) {
            log.warn("Could not select documents for Lever plan {}: {}", planId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> selectCvArtifact(ApplyDocumentSelector.DocumentSelection selection,
                                                  UUID planId, UUID applicationId, UUID jobId) {
        if (selection == null || selection.blocked() || selection.cv() == null) return null;
        var cv = selection.cv();
        String version = cv.versionId().toString();
        if (cv.pdfSha256() == null || cv.byteSize() <= 0) return null;
        Map<String, Object> cvMap = new LinkedHashMap<>();
        cvMap.put("versionId", version);
        cvMap.put("sha256", cv.pdfSha256());
        cvMap.put("jobId", jobId.toString());
        cvMap.put("applicationId", applicationId.toString());
        cvMap.put("fileName", "cv-" + version + ".pdf");
        cvMap.put("url", "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version);
        cvMap.put("byteSize", cv.byteSize());
        return cvMap;
    }

    private Map<String, Object> selectCoverLetterArtifact(ApplyDocumentSelector.DocumentSelection selection,
                                                           UUID planId, UUID applicationId, UUID jobId) {
        if (selection == null || selection.blocked() || selection.coverLetter() == null) return null;
        var letter = selection.coverLetter();
        if (letter.bodySha256() == null) return null;
        String version = letter.versionId().toString();
        Map<String, Object> letterMap = new LinkedHashMap<>();
        letterMap.put("versionId", version);
        letterMap.put("sha256", letter.bodySha256());
        letterMap.put("jobId", jobId.toString());
        letterMap.put("applicationId", applicationId.toString());
        letterMap.put("fileName", "cover-letter-" + version + ".txt");
        letterMap.put("url", "/api/v1/automation/plans/" + planId + "/artifacts/cover-letter?versionId=" + version);
        return letterMap;
    }

    private void persistWorkerPlan(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                   String targetUrl, List<AutomationPlan.Step> steps,
                                   List<Map<String, Object>> fields, List<Map<String, Object>> requiredGaps,
                                   Map<String, Object> cvArtifact, Map<String, Object> coverLetterArtifact) {
        try {
            Map<String, Object> workerPlan = new LinkedHashMap<>();
            workerPlan.put("planType", "LEVER");
            workerPlan.put("version", 1);
            workerPlan.put("correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()));
            workerPlan.put("targetUrl", targetUrl);
            workerPlan.put("steps", steps.stream().map(step -> Map.of(
                    "id", step.id(), "type", step.type(), "policy", step.policy(), "params", step.params())).toList());
            workerPlan.put("fields", fields);
            workerPlan.put("requiredGaps", requiredGaps);
            workerPlan.put("safetyContract", AutomationPlan.SAFETY_CONTRACT);
            if (cvArtifact != null || coverLetterArtifact != null) {
                Map<String, Object> pkg = new LinkedHashMap<>();
                if (cvArtifact != null) pkg.put("cv", cvArtifact);
                if (coverLetterArtifact != null) pkg.put("coverLetter", coverLetterArtifact);
                workerPlan.put("package", pkg);
            }
            String body = json.writeValueAsString(workerPlan);
            int updated = db.update("update automation_plans set plan = ?::jsonb "
                    + "where id = ? and profile_id = ? and application_id = ? and status = 'PREPARED'",
                    body, planId, profileId, applicationId);
            if (updated != 1) throw new IllegalStateException("LEVER_PLAN_NOT_PREPARED");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Could not persist complete Lever plan payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Object value) {
        try { return json.readValue(String.valueOf(value), Map.class); }
        catch (Exception ignored) { return Map.of(); }
    }
}
