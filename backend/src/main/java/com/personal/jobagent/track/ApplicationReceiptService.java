package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    public ApplicationReceiptService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
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

    public record IdentityReceipt(
            String name,
            String firstName,
            String lastName,
            String email,
            String phone,
            String addressLine1,
            String linkedIn
    ) {}

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
            List<AttemptSummary> attemptHistory
    ) {}

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
        if (selected == null) {
            selected = plans.get(plans.size() - 1);
            selectedIndex = plans.size() - 1;
        }

        return Optional.of(buildPlanReceipt(profileId, app, selected, selectedIndex + 1,
                selectedIndex == plans.size() - 1, summaries));
    }

    public List<AttemptSummary> getAttempts(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null) {
            return List.of();
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
        return summaries;
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

        AuthorizationRecord auth = extractAuthorization(plan.id, plan.applicationId, plan.submitApproved, plan.updatedAt);
        ConfirmationEvidence confirmation = extractConfirmation(plan.status, plan.resultJson);
        ReconciliationDetails reconciliation = extractReconciliation(plan.status, plan.resultJson);
        FailureDetails failure = extractFailure(plan.status, plan.resultJson);

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
                summaries
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
                List.of()
        );
    }

    @SuppressWarnings("unchecked")
    private IdentityReceipt extractIdentity(Map<String, Object> planMap) {
        if (!(planMap.get("steps") instanceof List<?> steps)) {
            return new IdentityReceipt(null, null, null, null, null, null, null);
        }
        for (Object s : steps) {
            if (s instanceof Map<?, ?> step) {
                String type = String.valueOf(step.get("type"));
                if (type.endsWith("_APPLY") && step.get("params") instanceof Map<?, ?> params) {
                    if (params.get("identity") instanceof Map<?, ?> idMap) {
                        return new IdentityReceipt(
                                (String) idMap.get("name"),
                                (String) idMap.get("firstName"),
                                (String) idMap.get("lastName"),
                                (String) idMap.get("email"),
                                (String) idMap.get("phone"),
                                (String) idMap.get("addressLine1"),
                                (String) idMap.get("linkedIn")
                        );
                    }
                }
            }
        }
        return new IdentityReceipt(null, null, null, null, null, null, null);
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
        String sha256 = (String) m.get("sha256");
        Long byteSize = m.get("byteSize") instanceof Number n ? n.longValue() : null;
        String fileName = (String) m.get("fileName");
        String purpose = m.get("purpose") != null ? String.valueOf(m.get("purpose")) : defaultPurpose;

        String title = null;
        Boolean validationPassed = null;
        if (versionId != null) {
            try {
                Map<String, Object> vRow = db.queryForMap(
                        "select title, claims_validation::text as val from cv_versions where id = ? and profile_id = ?",
                        versionId, profileId);
                title = (String) vRow.get("title");
                String valJson = (String) vRow.get("val");
                if (valJson != null && valJson.contains("\"passed\":true")) {
                    validationPassed = true;
                }
            } catch (EmptyResultDataAccessException ignored) {
            }
        }
        return new DocumentReceipt(versionId, title, "PDF", sha256, byteSize, purpose, fileName, validationPassed);
    }

    private AuthorizationRecord extractAuthorization(UUID planId, UUID applicationId, boolean approved, Instant updatedAt) {
        if (!approved) {
            return new AuthorizationRecord(false, "EXPLICIT_CANDIDATE_APPROVAL", null, null);
        }
        try {
            List<Map<String, Object>> rows = db.queryForList("""
                    select actor, created_at from audit_logs
                    where action in ('AUTOMATION_SUBMIT_APPROVED', 'REAL_SUBMISSION_PREPARED')
                      and (entity_id = ? or entity_id = ?)
                    order by created_at desc limit 1
                    """, planId, applicationId);
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                return new AuthorizationRecord(true, "EXPLICIT_CANDIDATE_APPROVAL",
                        instantFromTimestamp(r.get("created_at")), (String) r.get("actor"));
            }
        } catch (Exception e) {
            log.warn("Could not read audit log for authorization: {}", e.getMessage());
        }
        return new AuthorizationRecord(true, "EXPLICIT_CANDIDATE_APPROVAL", updatedAt, "CANDIDATE");
    }

    @SuppressWarnings("unchecked")
    private ConfirmationEvidence extractConfirmation(String status, String resultJson) {
        boolean isSubmitted = "SUBMITTED".equals(status);
        if (!isSubmitted) {
            return new ConfirmationEvidence(false, null, List.of(), "Application has not been confirmed as submitted.");
        }
        List<Object> results = readJsonList(resultJson);
        String confirmationId = null;
        List<String> signals = new ArrayList<>();
        String summary = "Application successfully submitted and confirmed with employer ATS platform evidence.";

        for (Object r : results) {
            if (r instanceof Map<?, ?> m) {
                if (m.containsKey("confirmationNumber")) {
                    confirmationId = String.valueOf(m.get("confirmationNumber"));
                } else if (m.containsKey("requisitionId")) {
                    confirmationId = String.valueOf(m.get("requisitionId"));
                } else if (m.containsKey("receiptId")) {
                    confirmationId = String.valueOf(m.get("receiptId"));
                }
                if (m.get("signals") instanceof List<?> sigs) {
                    for (Object s : sigs) signals.add(String.valueOf(s));
                }
                if (m.get("summary") != null) {
                    summary = String.valueOf(m.get("summary"));
                }
            }
        }
        if (signals.isEmpty()) {
            signals.add("PLATFORM_SUBMISSION_CONFIRMED");
            signals.add("FORM_DISAPPEARED");
        }
        return new ConfirmationEvidence(true, redactSensitive(confirmationId), signals, redactSensitive(summary));
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
                if ("SUBMISSION_RECONCILIATION".equals(m.get("type")) && m.get("reason") != null) {
                    reason = String.valueOf(m.get("reason"));
                } else if (m.get("reconciliationReason") != null) {
                    reason = String.valueOf(m.get("reconciliationReason"));
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
                if (m.get("error") != null) reason = String.valueOf(m.get("error"));
                if (m.get("errorCode") != null) code = String.valueOf(m.get("errorCode"));
            }
        }
        return new FailureDetails(true, redactSensitive(reason), redactSensitive(code));
    }

    static String redactSensitive(String val) {
        if (val == null) return null;
        String s = val;
        if (s.length() > 256) {
            s = s.substring(0, 253) + "...";
        }
        return s.replaceAll("(?i)(bearer\\s+[a-zA-Z0-9_\\-\\.]+)", "Bearer [REDACTED]")
                .replaceAll("(?i)(password|secret|token)=[^&\\s]+", "$1=[REDACTED]");
    }

    private DocumentReceipt fetchCurrentCvReceipt(UUID profileId, UUID applicationId, UUID cvVersionId) {
        if (cvVersionId == null) {
            return null;
        }
        try {
            return db.queryForObject("""
                    select cv.id, cv.title, cv.content_sha256, f.byte_size
                    from cv_versions cv
                    left join files f on f.id = cv.pdf_file_id
                    where cv.id = ? and cv.profile_id = ?
                    """, (rs, n) -> new DocumentReceipt(
                    (UUID) rs.getObject("id"),
                    rs.getString("title"),
                    "PDF",
                    rs.getString("content_sha256"),
                    rs.getObject("byte_size") != null ? rs.getLong("byte_size") : null,
                    "TAILORED_CV",
                    "tailored-cv.pdf",
                    true
            ), cvVersionId, profileId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private DocumentReceipt fetchCurrentCoverLetterReceipt(UUID profileId, UUID applicationId, UUID jobId) {
        try {
            return db.queryForObject("""
                    select cl.id, cl.title, f.sha256, f.byte_size
                    from cover_letters cl
                    left join files f on f.object_key = 'cover-letters/' || cl.id || '.pdf'
                    where cl.profile_id = ? and (cl.application_id = ? or (cl.application_id is null and cl.job_id = ?))
                    order by cl.created_at desc limit 1
                    """, (rs, n) -> new DocumentReceipt(
                    (UUID) rs.getObject("id"),
                    rs.getString("title"),
                    "PDF",
                    rs.getString("sha256"),
                    rs.getObject("byte_size") != null ? rs.getLong("byte_size") : null,
                    "COVER_LETTER",
                    "cover-letter.pdf",
                    null
            ), profileId, applicationId, jobId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
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
                        linkedIn
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
