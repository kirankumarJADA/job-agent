package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Robin x Tsenta continuation: the second functional ATS adapter, for Zoho
 * Recruit career-site application forms.
 *
 * <p><b>Why this cannot follow {@link GreenhouseExecutionPlanService}'s own
 * pattern.</b> That service pre-computes every field's selector and value on
 * the backend, via {@code GreenhouseAdapter#inspectForm} — a plain HTTP GET
 * plus Jsoup parse, which works only because Greenhouse renders its apply
 * form as server-side HTML on the job page itself. Verified directly against
 * two independent live Zoho Recruit tenants
 * (`fulcrumdigital.zohorecruit.com`, `execo.zohorecruit.com`, 2026-10-16): a
 * plain HTTP GET of the same job page returns a ~1.7MB page with zero
 * {@code <form>}/{@code <input>} elements — the apply form is rendered
 * entirely client-side after the visitor clicks the page's own
 * "I'm interested" control, and the field {@code name} attributes it then
 * generates ({@code rec-form_<tenant-specific-numeric-id>}) differ between
 * tenants even for the same semantic field. There is therefore no
 * deterministic STATIC inspection path here — the same honest boundary
 * {@code AshbyAdapter} already documents for another client-rendered ATS.
 *
 * <p>This service instead builds a plan whose one real step
 * ({@code ZOHO_RECRUIT_APPLY}) runs entirely inside the worker's live
 * browser session: it reveals the form, reads every field directly off the
 * live DOM, classifies each by its RENDERED LABEL TEXT (the one thing that
 * stayed consistent, modulo minor wording, across both tenants sampled —
 * see {@code worker/src/zoho_recruit.js}'s module comment), and fills only
 * first name, last name and email — the fields unambiguous enough to never
 * guess. Everything else a real form also carries (a composite phone/mobile
 * widget, a custom "Current Job Title" dropdown, résumé upload, required
 * consent/legal checkboxes with per-tenant wording, and any CAPTCHA) is
 * reported to the human, never fabricated. This plan type never submits —
 * no {@code REAL_SUBMIT_CLICK} capability is ever offered for it, enforced
 * independently by both the worker ({@code #validateZohoRecruitPlan}) and
 * {@link AutomationPlanRepository#prepareRealSubmission}, which already
 * refuses any non-{@code GREENHOUSE} plan type.
 *
 * <p><b>Résumé upload.</b> {@link ApplyDocumentSelector} — the fail-closed
 * exact/reviewed/immutable CV selection Greenhouse's own execution package
 * uses — is itself ATS-agnostic (it depends only on {@code ResumeAtsRepository}
 * and {@code CoverLetterRepository}, never on {@code GreenhouseAdapter}), so
 * it is reused directly here rather than going through the Greenhouse-coupled
 * {@code ExecutionPackageService}. When a reviewed CV is selected, its
 * metadata (immutable version id, sha256, byte size, the same owner-scoped
 * download URL Greenhouse's artifact endpoint already serves generically) is
 * embedded in the plan's {@code package.cv}/{@code package.artifacts} — the
 * exact shape {@code worker.js#materializeArtifacts} already downloads and
 * verifies for Greenhouse, reused unchanged. The worker then validates the
 * live form's accepted file types and a conservative size cap, and
 * re-verifies the checksum against the downloaded bytes, before ever calling
 * {@code setInputFiles} (see {@code browser_worker.js#tryUploadZohoResume}).
 * When no CV is selected (none exists yet, or the selection is blocked), no
 * artifact is embedded at all — the field is simply reported as a required
 * gap for the human, never silently treated as uploaded.
 *
 * <p><b>Cover letter (verified absent on both sampled tenants, 2026-10-18
 * re-check).</b> {@link ApplyDocumentSelector} also selects an approved
 * cover letter when one exists, so its artifact is embedded exactly like
 * the résumé's, reusing {@link ExecutionPackageService}'s own established
 * convention for what a "cover letter artifact" is: the approved body
 * markdown served as plain text (`cover-letter-{version}.txt`,
 * {@code text/plain}) via the same generic
 * {@code /plans/{id}/artifacts/cover-letter} endpoint Greenhouse already
 * uses — not a PDF rendition, since that is not what Robin's existing
 * artifact pipeline serves for a cover letter today. Re-inspecting both
 * live Zoho Recruit tenants field-by-field (2026-10-18) found exactly one
 * file-upload control on each — always labelled "Resume *" — and no
 * cover-letter field, file or text, on either form. The worker therefore
 * never finds a matching field to upload to on either sampled tenant and
 * reports that honestly (see {@code zoho_recruit.js}'s module comment);
 * embedding the artifact regardless costs nothing and means a tenant that
 * DOES expose a cover-letter field is handled correctly the first time it
 * is encountered, with no further backend change.
 *
 * <p><b>Recovery.</b> A terminal {@code FAILED}/{@code BLOCKED_ANTI_BOT} plan
 * is not stuck forever: the candidate's existing, generic
 * {@code POST /applications/{id}/re-prepare} action reaches this method
 * again, and when the plan it finds (by its stable idempotency key) is in
 * one of those two states, {@link AutomationPlanRepository#requeueFailedPlan}
 * atomically returns it to {@code PREPARED} and the plan is rebuilt below
 * with current data — never automatic, only ever reached via that
 * deliberate human action.
 */
@Service
public class ZohoRecruitExecutionPlanService {
    private static final Logger log = LoggerFactory.getLogger(ZohoRecruitExecutionPlanService.class);

    private final AutomationPlanRepository plans;
    private final AtsAdapters.ZohoAdapter zohoAdapter;
    private final ApplyDocumentSelector documentSelector;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ZohoRecruitExecutionPlanService(AutomationPlanRepository plans, AtsAdapters.ZohoAdapter zohoAdapter,
                                           ApplyDocumentSelector documentSelector, JdbcTemplate db, ObjectMapper json) {
        this.plans = plans;
        this.zohoAdapter = zohoAdapter;
        this.documentSelector = documentSelector;
        this.db = db;
        this.json = json;
    }

    public boolean handles(String applicationUrl) {
        return zohoAdapter.matchesUrl(applicationUrl);
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
                "zoho-recruit:" + applicationId, baseSteps);

        // An idempotent event replay must not rewrite a plan already claimed or
        // completed by a worker — same guard GreenhouseExecutionPlanService uses.
        AutomationPlanRepository.PlanRow current = plans.findById(planId).orElseThrow();
        if ("FAILED".equals(current.status()) || "BLOCKED_ANTI_BOT".equals(current.status())) {
            // Review-queue recovery: the candidate's own explicit retry action
            // (POST /applications/{id}/re-prepare) reached this — a terminal
            // failure is not left stuck forever. Requeue the SAME plan id back
            // to PREPARED (AutomationPlanRepository#requeueFailedPlan — never
            // automatic, only ever reached via that deliberate human action)
            // and fall through to rebuild it below with current data (a
            // candidate who fixed what caused the failure, e.g. uploaded a
            // résumé, should have that reflected in the retried attempt).
            if (!plans.requeueFailedPlan(planId)) return Optional.of(planId);
        } else if (!"PREPARED".equals(current.status())) {
            return Optional.of(planId);
        }

        Map<String, Object> candidateRow = db.queryForMap("""
                select u.email::text as email, u.display_name
                from applications a join profiles p on p.id = a.profile_id
                join users u on u.id = p.user_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String email = (String) candidateRow.get("email");
        GreenhouseFieldMapper.NameSplit name = GreenhouseFieldMapper.splitName((String) candidateRow.get("display_name"));

        Map<String, Object> identity = new LinkedHashMap<>();
        if (name.deterministic()) {
            identity.put("firstName", name.firstName());
            identity.put("lastName", name.lastName());
        }
        if (email != null && !email.isBlank()) {
            identity.put("email", email);
        }

        List<AutomationPlan.Step> steps = new java.util.ArrayList<>(baseSteps);
        steps.add(new AutomationPlan.Step("zoho-recruit-apply", "ZOHO_RECRUIT_APPLY", "AUTO",
                Map.of("identity", identity)));

        ApplyDocumentSelector.DocumentSelection selection = selectDocuments(planId, profileId, applicationId, jobId);
        Map<String, Object> cvArtifact = selectCvArtifact(selection, planId, applicationId, jobId);
        Map<String, Object> coverLetterArtifact = selectCoverLetterArtifact(selection, planId, applicationId, jobId);

        persistWorkerPlan(planId, profileId, applicationId, jobId, applicationUrl, steps, cvArtifact, coverLetterArtifact);
        log.info("ZOHO_RECRUIT plan {} prepared for application {} (identity fields resolved: {}, cv: {}, coverLetter: {})",
                planId, applicationId, identity.keySet(), cvArtifact != null, coverLetterArtifact != null);
        return Optional.of(planId);
    }

    /**
     * Best-effort: a blocked or absent document selection never prevents the
     * plan itself from being created — unlike Greenhouse (where an unreviewed
     * document blocks the whole execution package), this adapter's worker
     * step already degrades gracefully to "not uploaded, human required" for
     * either document when no artifact is embedded, so there is nothing
     * unsafe about proceeding without one. Selected once and reused for both
     * the CV and cover-letter lookups below, rather than querying twice.
     */
    private ApplyDocumentSelector.DocumentSelection selectDocuments(UUID planId, UUID profileId, UUID applicationId, UUID jobId) {
        try {
            return documentSelector.select(profileId, jobId, applicationId);
        } catch (Exception e) {
            log.warn("Could not select documents for Zoho Recruit plan {}: {}", planId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> selectCvArtifact(ApplyDocumentSelector.DocumentSelection selection,
                                                  UUID planId, UUID applicationId, UUID jobId) {
        if (selection == null || selection.blocked() || selection.cv() == null) return null;
        var cv = selection.cv();
        String version = cv.versionId().toString();
        if (cv.pdfSha256() == null || cv.byteSize() <= 0) return null;
        String url = "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version;
        Map<String, Object> cvMap = new LinkedHashMap<>();
        cvMap.put("versionId", version);
        cvMap.put("sha256", cv.pdfSha256());
        cvMap.put("jobId", jobId.toString());
        cvMap.put("applicationId", applicationId.toString());
        cvMap.put("fileName", "cv-" + version + ".pdf");
        cvMap.put("url", url);
        cvMap.put("byteSize", cv.byteSize());
        return cvMap;
    }

    /**
     * Mirrors {@link #selectCvArtifact} exactly, for the cover letter — the
     * one difference is the checksum: a cover letter artifact is the
     * approved body markdown served as plain text ({@code bodySha256}), the
     * same content {@code ExecutionPackageService#artifactBytes}'s
     * {@code "cover-letter"} case already serves for Greenhouse, reused
     * unchanged — never a PDF rendition Robin's pipeline does not actually
     * produce for this artifact today.
     */
    private Map<String, Object> selectCoverLetterArtifact(ApplyDocumentSelector.DocumentSelection selection,
                                                           UUID planId, UUID applicationId, UUID jobId) {
        if (selection == null || selection.blocked() || selection.coverLetter() == null) return null;
        var letter = selection.coverLetter();
        if (letter.bodySha256() == null) return null;
        String version = letter.versionId().toString();
        String url = "/api/v1/automation/plans/" + planId + "/artifacts/cover-letter?versionId=" + version;
        Map<String, Object> letterMap = new LinkedHashMap<>();
        letterMap.put("versionId", version);
        letterMap.put("sha256", letter.bodySha256());
        letterMap.put("jobId", jobId.toString());
        letterMap.put("applicationId", applicationId.toString());
        letterMap.put("fileName", "cover-letter-" + version + ".txt");
        letterMap.put("url", url);
        return letterMap;
    }

    private void persistWorkerPlan(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                   String targetUrl, List<AutomationPlan.Step> steps, Map<String, Object> cvArtifact,
                                   Map<String, Object> coverLetterArtifact) {
        try {
            Map<String, Object> workerPlan = new LinkedHashMap<>();
            workerPlan.put("planType", "ZOHO_RECRUIT");
            workerPlan.put("version", 1);
            workerPlan.put("correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()));
            workerPlan.put("targetUrl", targetUrl);
            workerPlan.put("steps", steps.stream().map(step -> Map.of(
                    "id", step.id(), "type", step.type(), "policy", step.policy(), "params", step.params())).toList());
            workerPlan.put("safetyContract", AutomationPlan.SAFETY_CONTRACT);
            if (cvArtifact != null || coverLetterArtifact != null) {
                Map<String, Object> pkg = new LinkedHashMap<>();
                List<Object> artifacts = new java.util.ArrayList<>();
                if (cvArtifact != null) {
                    pkg.put("cv", cvArtifact);
                    artifacts.add(Map.of(
                            "kind", "cv", "url", cvArtifact.get("url"), "versionId", cvArtifact.get("versionId"),
                            "sha256", cvArtifact.get("sha256"), "fileName", cvArtifact.get("fileName"),
                            "byteSize", cvArtifact.get("byteSize")));
                }
                if (coverLetterArtifact != null) {
                    pkg.put("coverLetter", coverLetterArtifact);
                    artifacts.add(Map.of(
                            "kind", "coverLetter", "url", coverLetterArtifact.get("url"),
                            "versionId", coverLetterArtifact.get("versionId"), "sha256", coverLetterArtifact.get("sha256"),
                            "fileName", coverLetterArtifact.get("fileName")));
                }
                pkg.put("artifacts", artifacts);
                workerPlan.put("package", pkg);
            }
            String body = json.writeValueAsString(workerPlan);
            int updated = db.update("update automation_plans set plan = ?::jsonb "
                    + "where id = ? and profile_id = ? and application_id = ? and status = 'PREPARED'",
                    body, planId, profileId, applicationId);
            if (updated != 1) throw new IllegalStateException("ZOHO_RECRUIT_PLAN_NOT_PREPARED");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Could not persist complete Zoho Recruit plan payload", e);
        }
    }
}
