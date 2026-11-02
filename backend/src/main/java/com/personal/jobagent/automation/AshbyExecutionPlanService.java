package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AshbyAdapter;
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
 * Phase 14: the first additional ATS application-execution integration added
 * since Lever/Zoho Recruit/Workday — and, unlike those three, one with a
 * genuine, guarded final-submission path, not merely form preparation.
 *
 * <p><b>Why this follows {@link ZohoRecruitExecutionPlanService}'s pattern,
 * not Greenhouse's.</b> {@link AshbyAdapter}'s own module comment already
 * documents the same boundary Zoho's own class comment explains at length:
 * {@code jobs.ashbyhq.com} serves a fully client-rendered React application
 * with no static {@code <form>}/{@code <input>} markup, so there is no
 * deterministic STATIC inspection path the way {@code GreenhouseAdapter}'s
 * plain HTTP GET + Jsoup parse has. This service therefore builds a plan
 * whose one preparation step ({@code ASHBY_APPLY}) runs entirely inside the
 * worker's live browser session, exactly like Zoho's — see
 * {@code worker/src/ashby.js}'s own module comment for the live-verified DOM
 * shape (a stable, platform-wide {@code data-field-path} attribute, far more
 * reliable than Zoho's label-text-only classification) this adapter relies
 * on.
 *
 * <p><b>Identity — deliberately NOT split.</b> Ashby's one stable name field
 * ({@code _systemfield_name}, labelled "Legal Name" on the sampled posting)
 * is a single full-name control, confirmed live — unlike every other adapter
 * here (Greenhouse/Lever/Zoho all split first/last), so this service passes
 * the candidate's {@code display_name} through verbatim via a single
 * {@code identity.name} key, never {@link GreenhouseFieldMapper#splitName}.
 * Phone and LinkedIn reuse the exact same {@code profiles.phone}/
 * {@code profiles.links} sources {@link LeverFieldMapper} and (as of Phase
 * 13) {@link ZohoRecruitExecutionPlanService} already draw from — the one
 * verified, non-guessed source Robin has for either, never invented.
 *
 * <p><b>Résumé only — no cover letter.</b> Re-inspecting the one live-sampled
 * Ashby posting found exactly one file-upload control
 * ({@code _systemfield_resume}, "Resume") and no cover-letter field, file or
 * text. {@link ApplyDocumentSelector} is reused exactly as Zoho's own service
 * reuses it, but only its {@code cv()} selection is ever embedded — there is
 * no matching field on this form for a cover-letter artifact to attach to,
 * and embedding one that can never be used would misrepresent what this
 * adapter actually does.
 *
 * <p><b>Final submission — the one genuine difference from Lever/Zoho/
 * Workday.</b> Ashby's own live-verified apply form carries no CAPTCHA and
 * no sign-in wall, so a guarded real submission is credible here (see this
 * project's Phase 14 investigation notes). This service never appends an
 * {@code ASHBY_SUBMIT_CLICK} step itself — only
 * {@link AutomationPlanRepository#prepareAshbySubmission} can ever do that,
 * mirroring {@link AutomationPlanRepository#prepareRealSubmission}'s exact
 * atomic safety shape (an already-approved, {@code READY_TO_SUBMIT} plan
 * only), and only after {@code AutomationController}'s own
 * {@code approvalPrecondition}/{@code ashbyApprovalPrecondition} gate has
 * independently re-verified the prepared plan has zero unresolved required
 * gaps and no forbidden step already present.
 *
 * <p><b>Recovery.</b> Mirrors every other adapter here exactly: a terminal
 * {@code FAILED}/{@code BLOCKED_ANTI_BOT} plan is requeued, never
 * automatically, only via the candidate's own explicit
 * {@code POST /applications/{id}/re-prepare} action reaching this method
 * again.
 */
@Service
public class AshbyExecutionPlanService {
    private static final Logger log = LoggerFactory.getLogger(AshbyExecutionPlanService.class);

    private final AutomationPlanRepository plans;
    private final AshbyAdapter ashbyAdapter;
    private final ApplyDocumentSelector documentSelector;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final com.personal.jobagent.mailbox.RegistrationIdentityService registrationIdentities;

    @org.springframework.beans.factory.annotation.Autowired
    public AshbyExecutionPlanService(AutomationPlanRepository plans, AshbyAdapter ashbyAdapter,
                                     ApplyDocumentSelector documentSelector, JdbcTemplate db, ObjectMapper json,
                                     org.springframework.beans.factory.ObjectProvider<com.personal.jobagent.mailbox.RegistrationIdentityService> registrationIdentities) {
        this.plans = plans;
        this.ashbyAdapter = ashbyAdapter;
        this.documentSelector = documentSelector;
        this.db = db;
        this.json = json;
        this.registrationIdentities = registrationIdentities == null ? null : registrationIdentities.getIfAvailable();
    }

    public AshbyExecutionPlanService(AutomationPlanRepository plans, AshbyAdapter ashbyAdapter,
                                     ApplyDocumentSelector documentSelector, JdbcTemplate db, ObjectMapper json) {
        this(plans, ashbyAdapter, documentSelector, db, json, null);
    }

    public boolean handles(String applicationUrl) {
        return ashbyAdapter.matchesUrl(applicationUrl);
    }

    /**
     * Ashby's own hosted board renders the apply form at a {@code
     * /application} sub-route of the job posting URL — live-verified to
     * render the full form immediately on direct navigation, no reveal
     * click needed (unlike Zoho Recruit). Idempotent: a URL that already
     * ends in {@code /application} is returned unchanged rather than
     * doubled.
     */
    private static String applyUrlOf(String applicationUrl) {
        String trimmed = applicationUrl.replaceAll("/+$", "");
        return trimmed.endsWith("/application") ? trimmed : trimmed + "/application";
    }

    @Transactional
    public Optional<UUID> createExecutionPlan(UUID profileId, UUID applicationId, UUID jobId) {
        if (profileId == null || applicationId == null || jobId == null) {
            throw new IllegalArgumentException("profile, application, and job correlation are required");
        }
        String applicationUrl = applicationUrlOf(profileId, applicationId, jobId);
        if (applicationUrl == null) return Optional.empty();
        return buildPlan(profileId, applicationId, jobId, applicationUrl, "ashby:" + applicationId, true);
    }

    /**
     * Phase 15: builds a brand-new, independent plan for a fresh attempt
     * after an earlier one's real submission outcome could never be
     * confirmed (status {@code RECONCILIATION_REQUIRED}) — called only from
     * {@code AutomationController#reconcile}, and only once that endpoint's
     * own evidence/authorization checks already passed. Uses a distinct
     * idempotency key keyed by the SUPERSEDED plan's own id — never the bare
     * {@code applicationId} key {@link #createExecutionPlan} uses — so this
     * can never collide with, resume, or silently overwrite the superseded
     * row; the existing {@code (profile_id, idempotency_key)} uniqueness
     * {@link AutomationPlanRepository#create} already enforces is what
     * guarantees a genuinely independent row here, not a new mechanism.
     * Idempotent itself: a second call for the same {@code supersededPlanId}
     * returns the SAME retry plan, never a third — so a candidate who
     * double-clicks "request a new attempt" can never create two.
     *
     * <p>Deliberately never requeues a {@code FAILED}/{@code
     * BLOCKED_ANTI_BOT} row the way {@link #createExecutionPlan} does — a
     * retry plan is, by construction, always a brand-new row with its own
     * fresh idempotency key, so there is never an existing row of this exact
     * key to requeue.
     */
    @Transactional
    public Optional<UUID> createRetryExecutionPlan(UUID profileId, UUID applicationId, UUID jobId, UUID supersededPlanId) {
        if (profileId == null || applicationId == null || jobId == null || supersededPlanId == null) {
            throw new IllegalArgumentException("profile, application, job and superseded-plan correlation are required");
        }
        String applicationUrl = applicationUrlOf(profileId, applicationId, jobId);
        if (applicationUrl == null) return Optional.empty();
        return buildPlan(profileId, applicationId, jobId, applicationUrl, "ashby-retry:" + supersededPlanId, false);
    }

    private String applicationUrlOf(UUID profileId, UUID applicationId, UUID jobId) {
        Map<String, Object> application = db.queryForMap("""
                select a.id, a.profile_id, a.job_id, j.application_url
                from applications a join jobs j on j.id = a.job_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String applicationUrl = (String) application.get("application_url");
        return handles(applicationUrl) ? applicationUrl : null;
    }

    private Optional<UUID> buildPlan(UUID profileId, UUID applicationId, UUID jobId, String applicationUrl,
                                     String idempotencyKey, boolean allowRequeue) {
        String applyUrl = applyUrlOf(applicationUrl);

        List<AutomationPlan.Step> baseSteps = List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO", Map.of("url", applyUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO", Map.of()));
        UUID planId = plans.create(profileId, applicationId, jobId, applyUrl, idempotencyKey, baseSteps);

        // Idempotent event replay must not rewrite a plan already claimed or
        // completed by a worker — same guard every other adapter here uses.
        AutomationPlanRepository.PlanRow current = plans.findById(planId).orElseThrow();
        if (allowRequeue && ("FAILED".equals(current.status()) || "BLOCKED_ANTI_BOT".equals(current.status()))) {
            // Review-queue recovery: the candidate's own explicit retry action
            // reached this — never automatic. Requeue the SAME plan id back to
            // PREPARED and fall through to rebuild it below with current data.
            if (!plans.requeueFailedPlan(planId)) return Optional.of(planId);
        } else if (!"PREPARED".equals(current.status())) {
            return Optional.of(planId);
        }

        Map<String, Object> candidateRow = db.queryForMap("""
                select u.display_name, u.email::text as email, p.phone::text as phone,
                       coalesce(p.links::text, '{}') as links
                from applications a join profiles p on p.id = a.profile_id
                join users u on u.id = p.user_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String displayName = (String) candidateRow.get("display_name");
        String email = (String) candidateRow.get("email");
        if (registrationIdentities != null) {
            email = registrationIdentities.resolveApplicationContactEmail(profileId, applyUrl, email);
        }
        String phone = (String) candidateRow.get("phone");

        Map<String, Object> identity = new LinkedHashMap<>();
        if (displayName != null && !displayName.isBlank()) {
            // Ashby's one stable name field is a SINGLE full-name control —
            // never split first/last, unlike every other adapter here (see
            // this class's own module comment).
            identity.put("name", displayName);
        }
        if (email != null && !email.isBlank()) {
            identity.put("email", email);
        }
        if (phone != null && !phone.isBlank()) {
            identity.put("phone", phone);
        }
        String linkedIn = linkedInOf(candidateRow.get("links"));
        if (linkedIn != null) {
            identity.put("linkedIn", linkedIn);
        }

        List<AutomationPlan.Step> steps = new java.util.ArrayList<>(baseSteps);
        steps.add(new AutomationPlan.Step("ashby-apply", "ASHBY_APPLY", "AUTO", Map.of("identity", identity)));

        ApplyDocumentSelector.DocumentSelection selection = selectDocuments(planId, profileId, applicationId, jobId);
        Map<String, Object> cvArtifact = selectCvArtifact(selection, planId, applicationId, jobId);

        persistWorkerPlan(planId, profileId, applicationId, jobId, applyUrl, steps, cvArtifact);
        log.info("ASHBY plan {} prepared for application {} (identity fields resolved: {}, cv: {})",
                planId, applicationId, identity.keySet(), cvArtifact != null);
        return Optional.of(planId);
    }

    /**
     * Best-effort, same discipline as {@link ZohoRecruitExecutionPlanService}:
     * a blocked or absent document selection never prevents the plan itself
     * from being created — the worker step already degrades gracefully to
     * "not uploaded, human required" when no artifact is embedded.
     */
    private ApplyDocumentSelector.DocumentSelection selectDocuments(UUID planId, UUID profileId, UUID applicationId, UUID jobId) {
        try {
            return documentSelector.select(profileId, jobId, applicationId);
        } catch (Exception e) {
            log.warn("Could not select documents for Ashby plan {}: {}", planId, e.getMessage());
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
     * Mirrors {@code LeverFieldMapper#linkOutcome}/{@code
     * ZohoRecruitExecutionPlanService#linkedInOf} exactly — the one verified,
     * non-guessed source Robin has for a candidate's LinkedIn URL.
     */
    private String linkedInOf(Object linksJson) {
        Map<String, Object> links;
        try {
            links = json.readValue(String.valueOf(linksJson), Map.class);
        } catch (Exception ignored) {
            return null;
        }
        if (links == null) return null;
        for (var entry : links.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(java.util.Locale.ROOT).contains("linkedin")
                    && entry.getValue() instanceof String url && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }

    private void persistWorkerPlan(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                   String targetUrl, List<AutomationPlan.Step> steps, Map<String, Object> cvArtifact) {
        try {
            Map<String, Object> workerPlan = new LinkedHashMap<>();
            workerPlan.put("planType", "ASHBY");
            workerPlan.put("version", 1);
            workerPlan.put("correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()));
            workerPlan.put("targetUrl", targetUrl);
            workerPlan.put("steps", steps.stream().map(step -> Map.of(
                    "id", step.id(), "type", step.type(), "policy", step.policy(), "params", step.params())).toList());
            workerPlan.put("safetyContract", AutomationPlan.SAFETY_CONTRACT);
            if (cvArtifact != null) {
                Map<String, Object> pkg = new LinkedHashMap<>();
                pkg.put("cv", cvArtifact);
                pkg.put("artifacts", List.of(Map.of(
                        "kind", "cv", "url", cvArtifact.get("url"), "versionId", cvArtifact.get("versionId"),
                        "sha256", cvArtifact.get("sha256"), "fileName", cvArtifact.get("fileName"),
                        "byteSize", cvArtifact.get("byteSize"))));
                workerPlan.put("package", pkg);
            }
            String body = json.writeValueAsString(workerPlan);
            int updated = db.update("update automation_plans set plan = ?::jsonb "
                    + "where id = ? and profile_id = ? and application_id = ? and status = 'PREPARED'",
                    body, planId, profileId, applicationId);
            if (updated != 1) throw new IllegalStateException("ASHBY_PLAN_NOT_PREPARED");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Could not persist complete Ashby plan payload", e);
        }
    }
}
