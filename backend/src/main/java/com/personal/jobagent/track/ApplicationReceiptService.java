package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.receipt.BrowserObservationReader;
import com.personal.jobagent.track.receipt.BrowserObservationReader.Observation;
import com.personal.jobagent.track.receipt.InvalidReceiptException;
import com.personal.jobagent.track.receipt.JdbcReceiptTrustStore;
import com.personal.jobagent.track.receipt.UniversalSubmissionEvidence;
import com.personal.jobagent.track.receipt.ReceiptCaptureService;
import com.personal.jobagent.track.receipt.ReceiptCaptureService.CaptureReceiptCommand;
import com.personal.jobagent.track.receipt.ReceiptStore.StoredReceipt;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository.StoredEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 20: Post-submission evidence and comprehensive application receipts.
 *
 * <p>Provides durable, immutable, evidence-backed receipts for application attempts.
 * Preserves:
 * <ul>
 *   <li>Candidate identity, employer details, target URL and ATS platform.</li>
 *   <li>Exact submitted identity fields, custom question answers and document provenance (SHA-256).</li>
 *   <li>Candidate authorization record and explicit approval timestamps.</li>
 *   <li>Platform-specific submission confirmation evidence or reconciliation required details.</li>
 *   <li>Full attempt history, linking initial attempts, failures, and subsequent retries.</li>
 * </ul>
 */
@Service
public class ApplicationReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationReceiptService.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ReceiptCaptureService receiptCaptureService;
    private final SubmissionEvidenceRepository evidenceRepository;
    private final BrowserObservationReader observationReader;

    @Autowired
    public ApplicationReceiptService(JdbcTemplate db, ObjectMapper json,
                                     @Autowired(required = false) ReceiptCaptureService receiptCaptureService,
                                     @Autowired(required = false) SubmissionEvidenceRepository evidenceRepository,
                                     @Autowired(required = false) BrowserObservationReader observationReader) {
        this.db = db;
        this.json = json;
        this.receiptCaptureService = receiptCaptureService;
        this.evidenceRepository = evidenceRepository;
        this.observationReader = observationReader != null ? observationReader
                : evidenceRepository != null ? new BrowserObservationReader(db, json, evidenceRepository) : null;
    }

    public ApplicationReceiptService(JdbcTemplate db, ObjectMapper json, ReceiptCaptureService receiptCaptureService,
                                     SubmissionEvidenceRepository evidenceRepository) {
        this(db, json, receiptCaptureService, evidenceRepository, null);
    }

    /** Without the capture service and evidence store, no submission can ever be shown as confirmed. */
    public ApplicationReceiptService(JdbcTemplate db, ObjectMapper json) {
        this(db, json, null, null, null);
    }

    public record DocumentReceipt(
            UUID versionId,
            String title,
            String kind,
            String sha256,
            Long byteSize,
            String purpose,
            String fileName,
            Boolean validationPassed
    ) {}

    /**
     * The identity Robin used for the attempt.
     *
     * @param source where these values come from (Stage 3I Phase 6): {@code SUBMIT_STEP_IDENTITY} (the
     *     submit step's own identity), {@code SUBMIT_STEP_FIELDS} (the submit step's field values),
     *     {@code PREPARED_PACKAGE_PROFILE_FACTS} (the Universal package's verified profile facts that the
     *     browser filled from — fields the form did not ask for were not sent), {@code CURRENT_PROFILE}
     *     (no attempt yet), or null when nothing was recorded. Never the employer's copy.
     */
    public record IdentityReceipt(
            String name,
            String firstName,
            String lastName,
            String email,
            String phone,
            String addressLine1,
            String linkedIn,
            String source
    ) {
        public IdentityReceipt(String name, String firstName, String lastName, String email, String phone,
                               String addressLine1, String linkedIn) {
            this(name, firstName, lastName, email, phone, addressLine1, linkedIn, null);
        }
    }

    public record AnswerReceipt(
            String question,
            String answer
    ) {}

    public record ConfirmationEvidence(
            boolean confirmed,
            String confirmationId,
            List<String> observedSignals,
            String summary
    ) {}

    public record ReconciliationDetails(
            boolean required,
            String reason,
            String suggestedAction
    ) {}

    public record FailureDetails(
            boolean failed,
            String reason,
            String code
    ) {}

    public record AuthorizationRecord(
            boolean approved,
            String policy,
            Instant approvedAt,
            String actor
    ) {}

    public record AttemptSummary(
            UUID attemptId,
            int attemptNumber,
            String atsKind,
            String idempotencyKey,
            String status,
            boolean submitted,
            Instant createdAt,
            Instant updatedAt,
            boolean isLatest
    ) {}

    public record SubmissionReceipt(
            UUID receiptId,
            UUID applicationId,
            UUID jobId,
            String jobTitle,
            String company,
            String applicationUrl,
            String atsKind,
            int attemptNumber,
            boolean isLatest,
            String idempotencyKey,
            String status,
            boolean submitted,
            Instant submittedAt,
            Instant createdAt,
            Instant updatedAt,
            AuthorizationRecord authorization,
            IdentityReceipt submittedIdentity,
            List<AnswerReceipt> submittedAnswers,
            DocumentReceipt cv,
            DocumentReceipt coverLetter,
            ConfirmationEvidence confirmation,
            ReconciliationDetails reconciliation,
            FailureDetails failure,
            List<AttemptSummary> attemptHistory,
            String verificationLevel,
            BrowserEvidenceView browserEvidence
    ) {
        /** Backward-compatible 25-arg constructor: no browser evidence section. */
        public SubmissionReceipt(
                UUID receiptId, UUID applicationId, UUID jobId, String jobTitle, String company,
                String applicationUrl, String atsKind, int attemptNumber, boolean isLatest,
                String idempotencyKey, String status, boolean submitted, Instant submittedAt,
                Instant createdAt, Instant updatedAt, AuthorizationRecord authorization,
                IdentityReceipt submittedIdentity, List<AnswerReceipt> submittedAnswers,
                DocumentReceipt cv, DocumentReceipt coverLetter, ConfirmationEvidence confirmation,
                ReconciliationDetails reconciliation, FailureDetails failure, List<AttemptSummary> attemptHistory,
                String verificationLevel) {
            this(receiptId, applicationId, jobId, jobTitle, company, applicationUrl, atsKind, attemptNumber,
                    isLatest, idempotencyKey, status, submitted, submittedAt, createdAt, updatedAt,
                    authorization, submittedIdentity, submittedAnswers, cv, coverLetter, confirmation,
                    reconciliation, failure, attemptHistory, verificationLevel, null);
        }

        /** Backward-compatible 24-arg constructor deriving verificationLevel conservatively. */
        public SubmissionReceipt(
                UUID receiptId, UUID applicationId, UUID jobId, String jobTitle, String company,
                String applicationUrl, String atsKind, int attemptNumber, boolean isLatest,
                String idempotencyKey, String status, boolean submitted, Instant submittedAt,
                Instant createdAt, Instant updatedAt, AuthorizationRecord authorization,
                IdentityReceipt submittedIdentity, List<AnswerReceipt> submittedAnswers,
                DocumentReceipt cv, DocumentReceipt coverLetter, ConfirmationEvidence confirmation,
                ReconciliationDetails reconciliation, FailureDetails failure, List<AttemptSummary> attemptHistory) {
            this(receiptId, applicationId, jobId, jobTitle, company, applicationUrl, atsKind, attemptNumber,
                    isLatest, idempotencyKey, status, submitted, submittedAt, createdAt, updatedAt,
                    authorization, submittedIdentity, submittedAnswers, cv, coverLetter, confirmation,
                    reconciliation, failure, attemptHistory,
                    deriveFallbackVerificationLevel(status, submitted, confirmation, failure));
        }

        private static String deriveFallbackVerificationLevel(String status, boolean submitted,
                                                              ConfirmationEvidence confirmation,
                                                              FailureDetails failure) {
            if (submitted && confirmation != null && confirmation.confirmed()) return "SANDBOX_VERIFIED";
            if ((failure != null && failure.failed()) || "FAILED".equals(status) || "BLOCKED_ANTI_BOT".equals(status)) {
                return "FAILED";
            }
            if (submitted || "RECONCILIATION_REQUIRED".equals(status) || "PRE_SUBMIT_ATTEMPTING".equals(status)
                    || "HANDOFF_REQUIRED".equals(status) || "RUNNING".equals(status)) {
                return "ATTEMPT_RECORDED";
            }
            return "NOT_ATTEMPTED";
        }
    }

    /**
     * Stage 3I Phase 2: what the browser evidence of a Universal attempt shows, for display. Never a
     * claim of employer verification: {@code employerVerification} is always NOT_VERIFIED_BY_BROWSER.
     */
    public record BrowserEvidenceView(
            String schemaVersion,
            String schemaKind,
            boolean browserObserved,
            String assessment,
            String assessmentReason,
            List<Map<String, Object>> redirectChain,
            String finalOrigin,
            List<DocumentUploadView> documents,
            String observedAt,
            String identifierSource,
            String employerVerification
    ) {}

    /**
     * Upload proof for one document. {@code uploadVerification} is FILE_PART_OBSERVED (the attached
     * artifact's file part was in the accepted submission request; its bytes cannot be observed by a
     * browser), ATTACHED_NOT_CONFIRMED, NOT_ATTACHED, NO_FIELD, or NOT_RECORDED (evidence before v2).
     */
    public record DocumentUploadView(String kind, String uploadVerification, String label, String attachedSha256,
                                     boolean bytesObserved) {}

    private record AppHeader(UUID applicationId, UUID jobId, String jobTitle, String company, String applicationUrl,
                             String status, UUID cvVersionId, Instant createdAt) {}

    private record PlanRow(UUID id, UUID applicationId, String targetUrl, String status, String planJson,
                           String resultJson, boolean submitApproved, String idempotencyKey,
                           Instant createdAt, Instant updatedAt) {}

    public Optional<SubmissionReceipt> getReceipt(UUID profileId, UUID applicationId, UUID attemptId) {
        if (profileId == null || applicationId == null) {
            return Optional.empty();
        }

        AppHeader app;
        try {
            app = db.queryForObject("""
                    select a.id as application_id, a.job_id, j.title as job_title, j.company_name_raw as company,
                           j.application_url, a.status, a.cv_version_id, a.created_at
                    from applications a
                    join jobs j on j.id = a.job_id
                    where a.id = ? and a.profile_id = ?
                    """, (rs, n) -> new AppHeader(
                    (UUID) rs.getObject("application_id"),
                    (UUID) rs.getObject("job_id"),
                    rs.getString("job_title"),
                    rs.getString("company"),
                    rs.getString("application_url"),
                    rs.getString("status"),
                    (UUID) rs.getObject("cv_version_id"),
                    instant(rs, "created_at")
            ), applicationId, profileId);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }

        List<PlanRow> plans = db.query("""
                select p.id, p.application_id, p.target_url, p.status, p.plan::text as plan_json,
                       p.result::text as result_json, p.submit_approved, p.idempotency_key,
                       p.created_at, p.updated_at
                from automation_plans p
                where p.application_id = ? and p.profile_id = ?
                order by p.created_at asc, p.id asc
                """, (rs, n) -> new PlanRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("application_id"),
                rs.getString("target_url"),
                rs.getString("status"),
                rs.getString("plan_json"),
                rs.getString("result_json"),
                rs.getBoolean("submit_approved"),
                rs.getString("idempotency_key"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        ), applicationId, profileId);

        if (plans.isEmpty()) {
            return Optional.of(buildEmptyReceipt(profileId, app));
        }

        List<AttemptSummary> summaries = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            PlanRow p = plans.get(i);
            String atsKind = extractPlanType(p.planJson);
            boolean submitted = "SUBMITTED".equals(p.status);
            boolean isLatest = (i == plans.size() - 1);
            summaries.add(new AttemptSummary(
                    p.id,
                    i + 1,
                    atsKind,
                    p.idempotencyKey,
                    p.status,
                    submitted,
                    p.createdAt,
                    p.updatedAt,
                    isLatest
            ));
        }

        PlanRow selected = null;
        int selectedIndex = plans.size() - 1;
        if (attemptId != null) {
            for (int i = 0; i < plans.size(); i++) {
                if (plans.get(i).id.equals(attemptId)) {
                    selected = plans.get(i);
                    selectedIndex = i;
                    break;
                }
            }
        }
        if (attemptId != null && selected == null) {
            // An attempt id that is not one of this application's plans is "not found" —
            // never silently answered with a different attempt's receipt.
            return Optional.empty();
        }
        if (selected == null) {
            // Stage 3I Phase 6 (P2-8): by default show the attempt that submitted the application, when one
            // did; a later failed or re-prepared attempt never hides it (attempt history still lists all).
            selectedIndex = plans.size() - 1;
            for (int i = plans.size() - 1; i >= 0; i--) {
                if ("SUBMITTED".equals(plans.get(i).status)) {
                    selectedIndex = i;
                    break;
                }
            }
            selected = plans.get(selectedIndex);
        }

        return Optional.of(buildPlanReceipt(profileId, app, selected, selectedIndex + 1,
                selectedIndex == plans.size() - 1, summaries));
    }

    public Optional<List<AttemptSummary>> getAttempts(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null) {
            return Optional.empty();
        }
        Integer appCount = db.queryForObject(
                "select count(*) from applications where id = ? and profile_id = ?",
                Integer.class, applicationId, profileId);
        if (appCount == null || appCount == 0) {
            return Optional.empty();
        }
        List<PlanRow> plans = db.query("""
                select p.id, p.application_id, p.target_url, p.status, p.plan::text as plan_json,
                       p.result::text as result_json, p.submit_approved, p.idempotency_key,
                       p.created_at, p.updated_at
                from automation_plans p
                where p.application_id = ? and p.profile_id = ?
                order by p.created_at asc, p.id asc
                """, (rs, n) -> new PlanRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("application_id"),
                rs.getString("target_url"),
                rs.getString("status"),
                rs.getString("plan_json"),
                rs.getString("result_json"),
                rs.getBoolean("submit_approved"),
                rs.getString("idempotency_key"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        ), applicationId, profileId);

        List<AttemptSummary> summaries = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            PlanRow p = plans.get(i);
            String atsKind = extractPlanType(p.planJson);
            boolean submitted = "SUBMITTED".equals(p.status);
            boolean isLatest = (i == plans.size() - 1);
            summaries.add(new AttemptSummary(
                    p.id,
                    i + 1,
                    atsKind,
                    p.idempotencyKey,
                    p.status,
                    submitted,
                    p.createdAt,
                    p.updatedAt,
                    isLatest
            ));
        }
        return Optional.of(summaries);
    }

    @SuppressWarnings("unchecked")
    private SubmissionReceipt buildPlanReceipt(UUID profileId, AppHeader app, PlanRow plan, int attemptNumber,
                                               boolean isLatest, List<AttemptSummary> summaries) {
        Map<String, Object> planMap = readJsonMap(plan.planJson);
        String atsKind = String.valueOf(planMap.getOrDefault("planType", "UNKNOWN"));
        boolean submitted = "SUBMITTED".equals(plan.status);
        Instant submittedAt = submitted ? plan.updatedAt : null;

        IdentityReceipt identity = extractIdentity(planMap);
        List<AnswerReceipt> answers = extractAnswers(app.applicationId, planMap);

        Map<String, Object> pkg = planMap.get("package") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        DocumentReceipt cvReceipt = extractDocumentReceipt(profileId, pkg.get("cv"), "TAILORED_CV");
        DocumentReceipt clReceipt = extractDocumentReceipt(profileId, pkg.get("coverLetter"), "COVER_LETTER");

        AuthorizationRecord auth = extractAuthorization(plan.id, plan.applicationId, profileId, plan.submitApproved, plan.updatedAt);
        ConfirmationOutcome confirmationOutcome = extractConfirmationOutcome(profileId, app, plan, atsKind, auth, submitted,
                identity, answers, cvReceipt, clReceipt, attemptNumber);
        ConfirmationEvidence confirmation = confirmationOutcome.evidence();
        ReconciliationDetails reconciliation = extractReconciliation(plan.status, plan.resultJson);
        FailureDetails failure = extractFailure(plan.status, plan.resultJson);

        // If submitted without confirmed employer/sandbox proof:
        // - Clean browser-observed UNIVERSAL submissions stay unconfirmed (confirmed=false) at level
        //   BROWSER_OBSERVED WITHOUT flagging a reconciliation error (reconciliation.required=false).
        // - Missing/tampered/unapproved evidence or unverified ATS submissions fail closed with reconciliation.required=true.
        if (submitted && !confirmation.confirmed() && !confirmationOutcome.cleanBrowserObserved() && !reconciliation.required()) {
            reconciliation = new ReconciliationDetails(true, "UNVERIFIED_RECEIPT_EVIDENCE",
                    "Genuine employer confirmation evidence is unavailable or unverified. Verify directly with the employer.");
        }

        String verificationLevel = resolveVerificationLevel(plan.status, submitted, confirmation,
                confirmationOutcome.cleanBrowserObserved(), failure);

        return new SubmissionReceipt(
                plan.id,
                app.applicationId,
                app.jobId,
                app.jobTitle,
                app.company,
                plan.targetUrl != null ? plan.targetUrl : app.applicationUrl,
                atsKind,
                attemptNumber,
                isLatest,
                plan.idempotencyKey,
                plan.status,
                submitted,
                submittedAt,
                plan.createdAt,
                plan.updatedAt,
                auth,
                identity,
                answers,
                cvReceipt,
                clReceipt,
                confirmation,
                reconciliation,
                failure,
                summaries,
                verificationLevel,
                confirmationOutcome.browserEvidence()
        );
    }

    private SubmissionReceipt buildEmptyReceipt(UUID profileId, AppHeader app) {
        DocumentReceipt cvReceipt = fetchCurrentCvReceipt(profileId, app.applicationId, app.cvVersionId);
        DocumentReceipt clReceipt = fetchCurrentCoverLetterReceipt(profileId, app.applicationId, app.jobId);
        IdentityReceipt identity = fetchProfileIdentity(profileId);
        List<AnswerReceipt> answers = fetchApplicationAnswers(app.applicationId);

        return new SubmissionReceipt(
                null,
                app.applicationId,
                app.jobId,
                app.jobTitle,
                app.company,
                app.applicationUrl,
                "NOT_ATTEMPTED",
                0,
                true,
                null,
                app.status,
                false,
                null,
                app.createdAt,
                app.createdAt,
                new AuthorizationRecord(false, "EXPLICIT_CANDIDATE_APPROVAL", null, null),
                identity,
                answers,
                cvReceipt,
                clReceipt,
                new ConfirmationEvidence(false, null, List.of(), "No submission attempt has been executed yet."),
                new ReconciliationDetails(false, null, null),
                new FailureDetails(false, null, null),
                List.of(),
                "NOT_ATTEMPTED",
                null
        );
    }

    @SuppressWarnings("unchecked")
    private IdentityReceipt extractIdentity(Map<String, Object> planMap) {
        if (!(planMap.get("steps") instanceof List<?> steps)) {
            return preparedPackageIdentity(planMap);
        }
        String firstName = null;
        String lastName = null;
        String name = null;
        String email = null;
        String phone = null;
        String addressLine1 = null;
        String linkedIn = null;
        for (Object s : steps) {
            if (s instanceof Map<?, ?> step) {
                String type = String.valueOf(step.get("type"));
                if (type.endsWith("_APPLY") && step.get("params") instanceof Map<?, ?> params) {
                    if (params.get("identity") instanceof Map<?, ?> idMap) {
                        return new IdentityReceipt(
                                nonBlankString(idMap.get("name")),
                                nonBlankString(idMap.get("firstName")),
                                nonBlankString(idMap.get("lastName")),
                                nonBlankString(idMap.get("email")),
                                nonBlankString(idMap.get("phone")),
                                nonBlankString(idMap.get("addressLine1")),
                                nonBlankString(idMap.get("linkedIn")),
                                "SUBMIT_STEP_IDENTITY"
                        );
                    }
                    if ("LEVER_APPLY".equals(type) && params.get("fields") instanceof List<?> fields) {
                        for (Object fObj : fields) {
                            if (fObj instanceof Map<?, ?> f) {
                                String key = nonBlankString(f.get("key"));
                                String val = nonBlankString(f.get("value"));
                                if (key == null || val == null) continue;
                                switch (key) {
                                    case "name" -> name = val;
                                    case "email" -> email = val;
                                    case "phone" -> phone = val;
                                    case "location" -> addressLine1 = val;
                                    case "urls[LinkedIn]" -> linkedIn = val;
                                    default -> {}
                                }
                            }
                        }
                    }
                } else if ("FILL_FIELD".equals(type) && step.get("params") instanceof Map<?, ?> params) {
                    String stepId = nonBlankString(step.get("id"));
                    String val = nonBlankString(params.get("value"));
                    if (stepId != null && val != null) {
                        switch (stepId) {
                            case "fill-first_name" -> firstName = val;
                            case "fill-last_name" -> lastName = val;
                            case "fill-email" -> email = val;
                            case "fill-phone" -> phone = val;
                            case "fill-candidate-location", "fill-location" -> addressLine1 = val;
                            default -> {}
                        }
                    }
                }
            }
        }
        if (name == null && (firstName != null || lastName != null)) {
            name = String.join(" ", firstName == null ? List.of(lastName)
                    : lastName == null ? List.of(firstName) : List.of(firstName, lastName));
        }
        boolean anyField = name != null || firstName != null || lastName != null || email != null || phone != null
                || addressLine1 != null || linkedIn != null;
        if (anyField) {
            return new IdentityReceipt(name, firstName, lastName, email, phone, addressLine1, linkedIn, "SUBMIT_STEP_FIELDS");
        }
        return preparedPackageIdentity(planMap);
    }

    /**
     * Stage 3I Phase 6 (P2-7): a Universal plan carries its identity in {@code package.profileFacts}, the
     * server-built facts from the candidate's verified profile that the browser fills from (never anything
     * read from the employer's page). Only identity fields are used; cover-letter text, portfolio and other
     * facts are never copied into the receipt.
     */
    private static IdentityReceipt preparedPackageIdentity(Map<String, Object> planMap) {
        Object pkgObj = planMap.get("package");
        if (!"UNIVERSAL".equalsIgnoreCase(String.valueOf(planMap.get("planType")))
                || !(pkgObj instanceof Map<?, ?> pkg) || !(pkg.get("profileFacts") instanceof Map<?, ?> facts)) {
            return new IdentityReceipt(null, null, null, null, null, null, null, null);
        }
        String first = nonBlankString(facts.get("firstName"));
        String last = nonBlankString(facts.get("lastName"));
        String full = nonBlankString(facts.get("fullName"));
        String email = nonBlankString(facts.get("email"));
        String phone = nonBlankString(facts.get("phone"));
        String location = nonBlankString(facts.get("location"));
        String linkedIn = nonBlankString(facts.get("linkedin"));
        if (full == null && first == null && last == null && email == null && phone == null && location == null && linkedIn == null) {
            return new IdentityReceipt(null, null, null, null, null, null, null, null);
        }
        return new IdentityReceipt(full, first, last, email, phone, location, linkedIn, "PREPARED_PACKAGE_PROFILE_FACTS");
    }

    @SuppressWarnings("unchecked")
    private List<AnswerReceipt> extractAnswers(UUID applicationId, Map<String, Object> planMap) {
        List<AnswerReceipt> fromDb = fetchApplicationAnswers(applicationId);
        if (!fromDb.isEmpty()) {
            return fromDb;
        }
        if (!(planMap.get("steps") instanceof List<?> steps)) {
            return List.of();
        }
        List<AnswerReceipt> answers = new ArrayList<>();
        for (Object s : steps) {
            if (s instanceof Map<?, ?> step && step.get("params") instanceof Map<?, ?> params) {
                if (params.get("answers") instanceof List<?> list) {
                    for (Object a : list) {
                        if (a instanceof Map<?, ?> m) {
                            String q = (String) m.get("question");
                            String ans = (String) m.get("answer");
                            if (q != null && ans != null) {
                                answers.add(new AnswerReceipt(q, ans));
                            }
                        }
                    }
                }
            }
        }
        return answers;
    }

    @SuppressWarnings("unchecked")
    private DocumentReceipt extractDocumentReceipt(UUID profileId, Object docObj, String defaultPurpose) {
        if (!(docObj instanceof Map<?, ?> m)) {
            return null;
        }
        UUID versionId = m.get("versionId") != null ? parseUuid(String.valueOf(m.get("versionId"))) : null;
        String sha256 = nonBlankString(m.get("sha256"));
        Long byteSize = m.get("byteSize") instanceof Number n ? n.longValue() : null;
        String fileName = nonBlankString(m.get("fileName"));
        String purpose = nonBlankString(m.get("purpose"));
        if (purpose == null) {
            purpose = defaultPurpose;
        }
        boolean isCoverLetter = "COVER_LETTER".equalsIgnoreCase(purpose);

        String title = null;
        Boolean validationPassed = null;
        boolean hasPdfFile = !isCoverLetter || Boolean.TRUE.equals(m.get("pdfStored"))
                || (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf"));

        if (versionId != null) {
            try {
                if (isCoverLetter) {
                    Map<String, Object> clRow = db.queryForMap("""
                            select cl.title, cl.claims_validation::text as val, cl.body_markdown, cl.content_sha256,
                                   f.byte_size as pdf_byte_size, f.sha256 as pdf_sha256
                            from cover_letters cl
                            left join files f on f.id = cl.pdf_file_id
                            where cl.id = ? and cl.profile_id = ?
                            """, versionId, profileId);
                    title = (String) clRow.get("title");
                    validationPassed = parseValidationPassed((String) clRow.get("val"));
                    String pdfSha = nonBlankString(clRow.get("pdf_sha256"));
                    Long pdfBytes = clRow.get("pdf_byte_size") instanceof Number p ? p.longValue() : null;
                    if (pdfSha != null || pdfBytes != null) {
                        hasPdfFile = true;
                    }
                    String kind = documentFormat(fileName, hasPdfFile ? "PDF" : "TXT");
                    if (byteSize == null) {
                        if ("PDF".equals(kind) && pdfBytes != null) {
                            byteSize = pdfBytes;
                        } else if (clRow.get("body_markdown") instanceof String body) {
                            byteSize = (long) body.getBytes(StandardCharsets.UTF_8).length;
                        }
                    }
                    if (sha256 == null) {
                        sha256 = "PDF".equals(kind) && pdfSha != null ? pdfSha : nonBlankString(clRow.get("content_sha256"));
                    }
                    return new DocumentReceipt(versionId, title, kind, sha256, byteSize, purpose, fileName, validationPassed);
                } else {
                    Map<String, Object> vRow = db.queryForMap(
                            "select title, claims_validation::text as val from cv_versions where id = ? and profile_id = ?",
                            versionId, profileId);
                    title = (String) vRow.get("title");
                    validationPassed = parseValidationPassed((String) vRow.get("val"));
                }
            } catch (EmptyResultDataAccessException ignored) {
            }
        }
        String kind = documentFormat(fileName, hasPdfFile ? "PDF" : "TXT");
        return new DocumentReceipt(versionId, title, kind, sha256, byteSize, purpose, fileName, validationPassed);
    }

    private AuthorizationRecord extractAuthorization(UUID planId, UUID applicationId, UUID profileId, boolean approved, Instant updatedAt) {
        if (!approved) {
            return new AuthorizationRecord(false, "EXPLICIT_CANDIDATE_APPROVAL", null, null);
        }
        try {
            // The one strict query (shared with receipt verification): this exact plan's
            // <PLANTYPE>_SUBMIT_APPROVED row, approvedForSubmission=true, written by the
            // owning candidate's own account. There is deliberately no looser fallback.
            List<Map<String, Object>> rows = db.queryForList(JdbcReceiptTrustStore.STRICT_APPROVAL_SQL,
                    planId, applicationId, profileId);
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                Object actor = r.get("actor");
                Instant approvedAt = instantFromTimestamp(r.get("created_at"));
                if (actor instanceof String a && !a.isBlank() && approvedAt != null) {
                    return new AuthorizationRecord(true, "EXPLICIT_CANDIDATE_APPROVAL", approvedAt, a);
                }
            }
        } catch (Exception e) {
            log.warn("Could not read audit log for authorization: {}", e.getMessage());
        }
        // Do NOT synthesize authorization when no audit log attestation record exists
        return new AuthorizationRecord(false, "MISSING_ATTESTATION", null, null);
    }

    /** What the worker reported in the plan's result JSON. Display only — never proof of anything. */
    private record ReportedConfirmation(String confirmationId, List<String> signals, String summary) {}

    private ReportedConfirmation reportedConfirmation(String resultJson) {
        String confirmationId = null;
        List<String> signals = new ArrayList<>();
        String summary = null;
        for (Object r : readJsonList(resultJson)) {
            if (r instanceof Map<?, ?> m) {
                Object number = m.get("confirmationNumber");
                if (number != null && !String.valueOf(number).isBlank()) {
                    confirmationId = String.valueOf(number);
                }
                if (m.get("signals") instanceof List<?> sigs) {
                    for (Object s : sigs) {
                        if (s != null) signals.add(String.valueOf(s));
                    }
                }
                if (m.get("summary") != null) {
                    summary = String.valueOf(m.get("summary"));
                }
            }
        }
        return new ReportedConfirmation(confirmationId, List.copyOf(signals), summary);
    }

    /**
     * Fail-closed confirmation. {@code confirmed=true} is returned in exactly
     * one case: a durable, verified receipt exists for this plan — either one
     * captured earlier, or one captured now from the evidence the authenticated
     * worker recorded at completion time. Plan status {@code SUBMITTED} alone,
     * a confirmation number in the result JSON, a missing capture service,
     * missing or unreadable raw evidence, and any verification or persistence
     * failure all yield {@code confirmed=false}. Observed signals are reported
     * exactly as recorded and are never synthesised.
     */
    private record ConfirmationOutcome(ConfirmationEvidence evidence, boolean cleanBrowserObserved,
                                       BrowserEvidenceView browserEvidence) {
        ConfirmationOutcome(ConfirmationEvidence evidence, boolean cleanBrowserObserved) {
            this(evidence, cleanBrowserObserved, null);
        }
    }

    private static String resolveVerificationLevel(String status, boolean submitted, ConfirmationEvidence confirmation,
                                                   boolean cleanBrowserObserved, FailureDetails failure) {
        if (submitted && confirmation.confirmed()) {
            return "SANDBOX_VERIFIED";
        }
        if (submitted && cleanBrowserObserved) {
            return "BROWSER_OBSERVED";
        }
        if ((failure != null && failure.failed()) || "FAILED".equals(status) || "BLOCKED_ANTI_BOT".equals(status)) {
            return "FAILED";
        }
        if (submitted || "RECONCILIATION_REQUIRED".equals(status) || "PRE_SUBMIT_ATTEMPTING".equals(status)
                || "HANDOFF_REQUIRED".equals(status) || "RUNNING".equals(status)) {
            return "ATTEMPT_RECORDED";
        }
        return "NOT_ATTEMPTED";
    }

    private ConfirmationOutcome extractConfirmationOutcome(UUID profileId, AppHeader app, PlanRow plan, String atsKind,
                                                             AuthorizationRecord auth, boolean isSubmitted,
                                                             IdentityReceipt identity, List<AnswerReceipt> answers,
                                                             DocumentReceipt cvReceipt, DocumentReceipt clReceipt,
                                                             int attemptNumber) {
        if (!isSubmitted) {
            return new ConfirmationOutcome(
                    new ConfirmationEvidence(false, null, List.of(), "Application has not been confirmed as submitted."),
                    false);
        }
        ReportedConfirmation reported = reportedConfirmation(plan.resultJson);
        String reportedId = redactSensitive(reported.confirmationId());
        List<String> reportedSignals = reported.signals();

        if ("UNIVERSAL".equalsIgnoreCase(atsKind)) {
            return evaluateUniversalObservation(profileId, app, plan, auth, reported, reportedId, reportedSignals);
        }

        if (receiptCaptureService == null || evidenceRepository == null) {
            return new ConfirmationOutcome(unverified(reportedId, reportedSignals,
                    "receipt verification is unavailable, so this submission cannot be shown as confirmed."), false);
        }

        ReceiptCaptureService.StoredReceiptVerification storedVerification;
        try {
            storedVerification = receiptCaptureService.revalidateStoredReceipt(profileId, app.applicationId, plan.id);
        } catch (RuntimeException e) {
            log.warn("Could not revalidate stored receipt for plan {}: {}", plan.id, e.getMessage());
            return new ConfirmationOutcome(
                    unverified(reportedId, reportedSignals, "the stored receipt could not be verified."), false);
        }
        if (storedVerification.isVerified()) {
            StoredReceipt verifiedReceipt = storedVerification.verifiedReceipt().orElseThrow();
            return new ConfirmationOutcome(new ConfirmationEvidence(true, redactSensitive(verifiedReceipt.employerConfirmationId()),
                    verifiedReceipt.observedSignals(),
                    redactSensitive(reported.summary() != null ? reported.summary()
                            : "Submission confirmed with verified " + verifiedReceipt.atsPlatform() + " platform evidence.")),
                    false);
        }
        if (storedVerification.isInvalid()) {
            return new ConfirmationOutcome(unverified(reportedId, reportedSignals, storedVerification.failureReason()), false);
        }

        StoredEvidence evidence;
        String rawEvidence;
        try {
            evidence = evidenceRepository.findForPlan(profileId, app.applicationId, plan.id).orElse(null);
            if (evidence == null) {
                return new ConfirmationOutcome(unverified(reportedId, reportedSignals,
                        "no evidence was recorded by the authenticated worker for this attempt."), false);
            }
            rawEvidence = evidenceRepository.decryptRawEvidence(evidence).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Stored evidence for plan {} could not be read: {}", plan.id, e.getMessage());
            return new ConfirmationOutcome(unverified(reportedId, reportedSignals,
                    "the stored evidence could not be decrypted or failed its integrity check."), false);
        }
        if (rawEvidence == null) {
            return new ConfirmationOutcome(unverified(reportedId, reportedSignals,
                    "no raw DOM/screenshot evidence was stored for this attempt (" + evidence.rawEvidenceStatus() + ")."),
                    false);
        }

        try {
            CaptureReceiptCommand cmd = CaptureReceiptCommand.builder()
                    .profileId(profileId)
                    .applicationId(app.applicationId)
                    .planId(plan.id)
                    .jobId(app.jobId)
                    .jobTitle(app.jobTitle)
                    .company(app.company)
                    .applicationUrl(plan.targetUrl != null ? plan.targetUrl : app.applicationUrl)
                    .atsPlatform(evidence.atsPlatform())
                    .employerConfirmationId(evidence.employerConfirmationId())
                    .rawEvidencePayload(rawEvidence)
                    .receiptSha256(evidence.rawEvidenceSha256())
                    .submittedAt(evidence.recordedAt())
                    .submittedIdentity(identity)
                    .submittedAnswers(answers)
                    .cv(cvReceipt)
                    .coverLetter(clReceipt)
                    .observedSignals(evidence.observedSignals())
                    .summary(reported.summary())
                    .attemptNumber(attemptNumber)
                    .build();
            SubmissionReceipt captured = receiptCaptureService.captureReceipt(cmd);
            return new ConfirmationOutcome(new ConfirmationEvidence(true, redactSensitive(captured.confirmation().confirmationId()),
                    captured.confirmation().observedSignals(), redactSensitive(captured.confirmation().summary())), false);
        } catch (InvalidReceiptException e) {
            log.warn("Receipt verification failed for plan {} [{}]: {}", plan.id, e.getReasonCode(), e.getMessage());
            return new ConfirmationOutcome(unverified(reportedId, evidence.observedSignals(),
                    "[" + e.getReasonCode() + "] " + e.getMessage()), false);
        } catch (RuntimeException e) {
            log.error("Receipt verification errored for plan {}: {}", plan.id, e.getMessage());
            return new ConfirmationOutcome(
                    unverified(reportedId, evidence.observedSignals(), "receipt verification failed."), false);
        }
    }

    private ConfirmationOutcome evaluateUniversalObservation(UUID profileId, AppHeader app, PlanRow plan,
                                                             AuthorizationRecord auth, ReportedConfirmation reported,
                                                             String reportedId, List<String> reportedSignals) {
        if (evidenceRepository == null || observationReader == null) {
            return new ConfirmationOutcome(unverified(reportedId, reportedSignals,
                    "submission evidence storage is unavailable, so browser observation cannot be verified."), false);
        }
        if (auth == null || !auth.approved()) {
            return new ConfirmationOutcome(unverified(reportedId, reportedSignals,
                    "explicit candidate submission approval attestation is missing."), false);
        }
        Observation observation;
        try {
            observation = observationReader.read(profileId, app.applicationId, plan.id, app.jobId,
                    plan.targetUrl != null ? plan.targetUrl : app.applicationUrl, true);
        } catch (RuntimeException e) {
            log.warn("Stored evidence for UNIVERSAL plan {} could not be read: {}", plan.id, e.getMessage());
            return new ConfirmationOutcome(unverified(reportedId, reportedSignals,
                    "the stored evidence could not be decrypted or failed its integrity check."), false);
        }
        StoredEvidence evidence = observation.evidence();
        BrowserEvidenceView view = browserEvidenceView(observation);
        if (!observation.assessment().clean()) {
            List<String> signals = evidence == null || "NO_SIGNALS".equals(observation.assessment().code())
                    || "EVIDENCE_INTEGRITY_FAILED".equals(observation.assessment().code())
                    ? reportedSignals : evidence.observedSignals();
            return new ConfirmationOutcome(unverified(reportedId, signals, observation.assessment().message()), false, view);
        }
        String obsId = redactSensitive(evidence.employerConfirmationId() != null
                ? evidence.employerConfirmationId()
                : reportedId);
        String summary = reported.summary() != null && !reported.summary().isBlank()
                ? reported.summary()
                : "Browser observed submission on employer form. Employer receipt has not yet been independently verified.";
        return new ConfirmationOutcome(
                new ConfirmationEvidence(false, obsId, evidence.observedSignals(), redactSensitive(summary)),
                true, view);
    }

    /** The display view of a Universal attempt's browser evidence; never a claim of employer verification. */
    private static BrowserEvidenceView browserEvidenceView(Observation observation) {
        if (observation == null || observation.evidence() == null) return null;
        UniversalSubmissionEvidence.Parsed parsed = observation.parsed();
        boolean v2 = parsed != null && parsed.isV2();
        List<Map<String, Object>> hops = new ArrayList<>();
        List<DocumentUploadView> documents = new ArrayList<>();
        if (v2) {
            for (UniversalSubmissionEvidence.Hop hop : parsed.redirectChain()) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("host", hop.host());
                h.put("status", hop.status());
                hops.add(h);
            }
            for (String kind : List.of("cv", "coverLetter")) {
                UniversalSubmissionEvidence.Document d = parsed.documents().get(kind);
                String state = d == null || d.uploadVerification() == null ? "NOT_RECORDED" : d.uploadVerification();
                documents.add(new DocumentUploadView(kind, state, uploadLabel(kind, state),
                        "FILE_PART_OBSERVED".equals(state) || "ATTACHED_NOT_CONFIRMED".equals(state)
                                ? d.artifactSha256() : null,
                        false));
            }
        } else {
            for (String kind : List.of("cv", "coverLetter")) {
                documents.add(new DocumentUploadView(kind, "NOT_RECORDED", uploadLabel(kind, "NOT_RECORDED"), null, false));
            }
        }
        return new BrowserEvidenceView(
                parsed == null ? null : parsed.schemaVersion(),
                parsed == null ? "UNREADABLE" : parsed.kind().name(),
                observation.assessment().clean(),
                observation.assessment().code(),
                redactSensitive(observation.assessment().message()),
                List.copyOf(hops),
                v2 ? parsed.finalOrigin() : null,
                List.copyOf(documents),
                v2 ? parsed.observedAt() : null,
                v2 ? parsed.identifierSource() : null,
                "NOT_VERIFIED_BY_BROWSER");
    }

    private static String uploadLabel(String kind, String state) {
        String doc = "cv".equals(kind) ? "CV" : "Cover letter";
        String noun = "cv".equals(kind) ? "CV" : "cover letter";
        return switch (state) {
            case "FILE_PART_OBSERVED" -> doc + " was attached and included in the submitted form; a browser cannot "
                    + "confirm the employer received the file itself.";
            case "ATTACHED_NOT_CONFIRMED" -> doc + " was attached to the form, but Robin could not confirm it was "
                    + "included when the form was submitted.";
            case "NOT_ATTACHED" -> doc + " was not attached to the form.";
            case "NO_FIELD" -> "The form had no field for a " + noun + ".";
            default -> "Upload proof for the " + noun + " was not recorded for this attempt.";
        };
    }

    private static ConfirmationEvidence unverified(String reportedId, List<String> signals, String why) {
        return new ConfirmationEvidence(false, reportedId, signals == null ? List.of() : signals,
                redactSensitive("Unverified submission: " + why));
    }

    /**
     * Called after the worker completion transaction commits: attempts to
     * capture the verified receipt for this plan. Idempotent. Returns whether
     * a verified receipt now exists; never throws.
     */
    public boolean tryCaptureForPlan(UUID profileId, UUID applicationId, UUID planId) {
        try {
            return getReceipt(profileId, applicationId, planId)
                    .filter(r -> planId.equals(r.receiptId()))
                    .map(r -> r.confirmation() != null && r.confirmation().confirmed())
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("Post-completion receipt capture failed for plan {}: {}", planId, e.getMessage());
            return false;
        }
    }

    /** The raw evidence the worker recorded for one attempt, decrypted and digest-checked. */
    public record RawEvidence(UUID evidenceId, UUID attemptId, String sha256, int byteLength,
                              Instant recordedAt, String payload) {}

    /**
     * Owner-scoped access to the decrypted raw evidence of one attempt. Empty
     * when the attempt does not belong to this profile and application, or no
     * raw evidence was stored for it.
     *
     * <p>Digest integrity is verified against the stored SHA-256 over the exact
     * encrypted plaintext first; any credentials/tokens/cookies/userinfo that
     * appeared in the payload are then redacted before returning the view.
     *
     * @throws com.personal.jobagent.track.receipt.EvidenceIntegrityException when the stored ciphertext fails decryption or its integrity check
     */
    public Optional<RawEvidence> getRawEvidence(UUID profileId, UUID applicationId, UUID attemptId) {
        if (evidenceRepository == null || profileId == null || applicationId == null || attemptId == null) {
            return Optional.empty();
        }
        Optional<StoredEvidence> evidence = evidenceRepository.findForPlan(profileId, applicationId, attemptId);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        StoredEvidence e = evidence.get();
        return evidenceRepository.decryptRawEvidence(e).map(payload -> new RawEvidence(
                e.id(), e.planId(), e.rawEvidenceSha256(),
                e.rawEvidenceByteLength() == null ? 0 : e.rawEvidenceByteLength(),
                e.recordedAt(), redactRawEvidenceSecrets(payload)));
    }

    @SuppressWarnings("unchecked")
    private ReconciliationDetails extractReconciliation(String status, String resultJson) {
        if (!"RECONCILIATION_REQUIRED".equals(status)) {
            return new ReconciliationDetails(false, null, null);
        }
        List<Object> results = readJsonList(resultJson);
        String reason = "Submission attempt was made but employer acknowledgment response was unobserved or timed out.";
        for (Object r : results) {
            if (r instanceof Map<?, ?> m) {
                if ("SUBMISSION_RECONCILIATION".equals(m.get("type"))) {
                    String category = nonBlankString(m.get("category"));
                    String evidence = nonBlankString(m.get("evidence"));
                    String legacyReason = nonBlankString(m.get("reason"));
                    if (category != null || evidence != null) {
                        String catLabel = category != null ? category : "UNKNOWN";
                        String detail = evidence != null ? evidence : legacyReason;
                        reason = detail != null ? ("[" + catLabel + "] " + detail) : ("[" + catLabel + "]");
                    } else if (legacyReason != null) {
                        reason = legacyReason;
                    }
                } else {
                    String compat = nonBlankString(m.get("reconciliationReason"));
                    if (compat != null) {
                        reason = compat;
                    }
                }
            }
        }
        return new ReconciliationDetails(true, redactSensitive(reason),
                "Verify directly with the employer whether your application was received, or click Reconcile to trigger a fresh independent submission attempt.");
    }

    @SuppressWarnings("unchecked")
    private FailureDetails extractFailure(String status, String resultJson) {
        if (!"FAILED".equals(status) && !"BLOCKED_ANTI_BOT".equals(status)) {
            return new FailureDetails(false, null, null);
        }
        List<Object> results = readJsonList(resultJson);
        String reason = "BLOCKED_ANTI_BOT".equals(status) ? "Honeypot or CAPTCHA detected on application form." : "Execution attempt failed.";
        String code = status;
        for (Object r : results) {
            if (r instanceof Map<?, ?> m) {
                if ("PLAN_ERROR".equals(m.get("type"))) {
                    String msg = nonBlankString(m.get("message"));
                    if (msg == null) msg = nonBlankString(m.get("error"));
                    if (msg != null) reason = msg;
                    String errCode = nonBlankString(m.get("reason"));
                    if (errCode == null) errCode = nonBlankString(m.get("errorCode"));
                    if (errCode != null) code = errCode;
                } else {
                    String err = nonBlankString(m.get("error"));
                    if (err == null) err = nonBlankString(m.get("message"));
                    if (err != null) reason = err;
                    String errCode = nonBlankString(m.get("errorCode"));
                    if (errCode != null) code = errCode;
                }
            }
        }
        return new FailureDetails(true, redactSensitive(reason), redactSensitive(code));
    }

    static String redactSensitive(String val) {
        if (val == null) return null;
        String redacted = redactRawEvidenceSecrets(val);
        if (redacted.length() > 256) {
            return redacted.substring(0, 253) + "...";
        }
        return redacted;
    }

    static String redactRawEvidenceSecrets(String val) {
        if (val == null) return null;
        return val
                .replaceAll("(?i)(https?://)([^/\\s:@\"'<>]+):([^/\\s@\"'<>]+)@", "$1[REDACTED]@")
                .replaceAll("(?i)\\b(cookie|set-cookie)\\s*:\\s*[^\\r\\n<>\"']+", "$1: [REDACTED]")
                .replaceAll("(?i)\\bauthorization\\s*:\\s*(?:bearer|basic|token|apikey)\\s+[A-Za-z0-9._~+/=-]+", "Authorization: [REDACTED]")
                .replaceAll("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("(?i)\"([A-Za-z0-9_-]*(?:password|passwd|pwd|otp|token|cookie|secret|api[_-]?key|apikey|credential|session_id|auth(?:orization)?)[A-Za-z0-9_-]*)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"", "\"$1\": \"[REDACTED]\"")
                .replaceAll("(?i)\\b([A-Za-z0-9_-]*(?:password|passwd|pwd|otp|token|cookie|secret|api[_-]?key|apikey|credential|session(?:_id)?|auth(?:orization)?))=([^\\s,;&\"'<>]+)", "$1=[REDACTED]")
                .replaceAll("(?i)\\b([A-Za-z0-9_-]*(?:password|passwd|pwd|secret|token|cookie|api[_-]?key|apikey|credential|session_id|otp))\\s*:\\s*([^\\s,;\"'<>]+)", "$1: [REDACTED]");
    }

    private DocumentReceipt fetchCurrentCvReceipt(UUID profileId, UUID applicationId, UUID cvVersionId) {
        if (cvVersionId == null) {
            return null;
        }
        try {
            return db.queryForObject("""
                    select cv.id, cv.title, cv.claims_validation::text as val,
                           coalesce(f.sha256, cv.content_sha256) as sha256, f.byte_size
                    from cv_versions cv
                    left join files f on f.id = cv.pdf_file_id
                    where cv.id = ? and cv.profile_id = ?
                    """, (rs, n) -> new DocumentReceipt(
                    (UUID) rs.getObject("id"),
                    rs.getString("title"),
                    "PDF",
                    rs.getString("sha256"),
                    rs.getObject("byte_size") != null ? rs.getLong("byte_size") : null,
                    "TAILORED_CV",
                    "tailored-cv.pdf",
                    parseValidationPassed(rs.getString("val"))
            ), cvVersionId, profileId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private DocumentReceipt fetchCurrentCoverLetterReceipt(UUID profileId, UUID applicationId, UUID jobId) {
        try {
            return db.queryForObject("""
                    select cl.id, cl.title, cl.claims_validation::text as val, cl.body_markdown, cl.content_sha256,
                           cl.pdf_file_id, f.sha256 as pdf_sha256, f.byte_size as pdf_byte_size
                    from cover_letters cl
                    left join files f on f.id = cl.pdf_file_id
                    where cl.profile_id = ? and (cl.application_id = ? or (cl.application_id is null and cl.job_id = ?))
                    order by cl.application_id is null asc, cl.is_approved desc, cl.created_at desc, cl.id desc limit 1
                    """, (rs, n) -> {
                String pdfSha = nonBlankString(rs.getString("pdf_sha256"));
                Long pdfBytes = rs.getObject("pdf_byte_size") != null ? rs.getLong("pdf_byte_size") : null;
                boolean hasPdf = rs.getObject("pdf_file_id") != null && pdfSha != null;
                String body = rs.getString("body_markdown");
                String sha = hasPdf ? pdfSha : nonBlankString(rs.getString("content_sha256"));
                Long bytes = hasPdf ? pdfBytes : (body != null ? (long) body.getBytes(StandardCharsets.UTF_8).length : null);
                return new DocumentReceipt(
                        (UUID) rs.getObject("id"),
                        rs.getString("title"),
                        hasPdf ? "PDF" : "TXT",
                        sha,
                        bytes,
                        "COVER_LETTER",
                        hasPdf ? "cover-letter.pdf" : "cover-letter.txt",
                        parseValidationPassed(rs.getString("val"))
                );
            }, profileId, applicationId, jobId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Boolean parseValidationPassed(String valJson) {
        if (valJson == null || valJson.isBlank()) {
            return null;
        }
        Map<String, Object> valMap = readJsonMap(valJson);
        Object passed = valMap.get("passed");
        return passed instanceof Boolean b ? b : null;
    }

    private static String documentFormat(String fileName, String defaultFormat) {
        if (fileName == null || fileName.isBlank()) {
            return defaultFormat;
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".txt")) return "TXT";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MARKDOWN";
        if (lower.endsWith(".docx")) return "DOCX";
        return defaultFormat;
    }

    private static String nonBlankString(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private IdentityReceipt fetchProfileIdentity(UUID profileId) {
        try {
            return db.queryForObject("""
                    select u.display_name, u.email, p.phone, p.location, coalesce(p.links::text, '{}') as links
                    from profiles p
                    join users u on u.id = p.user_id
                    where p.id = ?
                    """, (rs, n) -> {
                String displayName = rs.getString("display_name");
                String firstName = null;
                String lastName = null;
                if (displayName != null) {
                    String[] parts = displayName.trim().split("\\s+", 2);
                    firstName = parts[0];
                    if (parts.length > 1) lastName = parts[1];
                }
                String links = rs.getString("links");
                String linkedIn = null;
                if (links != null && links.contains("linkedin.com")) {
                    Map<String, Object> map = readJsonMap(links);
                    linkedIn = (String) map.get("linkedin");
                }
                return new IdentityReceipt(
                        displayName,
                        firstName,
                        lastName,
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("location"),
                        linkedIn,
                        "CURRENT_PROFILE"
                );
            }, profileId);
        } catch (EmptyResultDataAccessException e) {
            return new IdentityReceipt(null, null, null, null, null, null, null);
        }
    }

    private List<AnswerReceipt> fetchApplicationAnswers(UUID applicationId) {
        return db.query("""
                select question_text, answer_text from application_answers
                where application_id = ?
                order by created_at asc
                """, (rs, n) -> new AnswerReceipt(
                rs.getString("question_text"),
                rs.getString("answer_text")
        ), applicationId);
    }

    private String extractPlanType(String planJson) {
        Map<String, Object> map = readJsonMap(planJson);
        return String.valueOf(map.getOrDefault("planType", "UNKNOWN"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return Map.of();
        try {
            return json.readValue(jsonStr, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> readJsonList(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return List.of();
        try {
            return json.readValue(jsonStr, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Instant instantFromTimestamp(Object o) {
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
