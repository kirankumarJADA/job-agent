package com.personal.jobagent.apply;

import com.personal.jobagent.application.ApplicationDecisionService;
import com.personal.jobagent.automation.ApplyDocumentSelector;
import com.personal.jobagent.ats.AtsAdapter.RequiredState;
import com.personal.jobagent.ats.JobFormQuestionService;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.qa.ApplicationAnswerRecord;
import com.personal.jobagent.qa.ApplicationAnswerRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Pre-approval APPLY readiness (Phase 8.2): everything the review workspace
 * and the approval gate need, derived ONLY from records that exist.
 *
 * <p>Covers ownership of the job/application/CV/letter/answers, the correct
 * job↔application association, the exact reviewed CV version and digest, the
 * cover-letter requirement and approval state, current validation results and
 * file integrity, required screening questions and confirmed answers,
 * duplicate-application detection, and the existing approval-rule decision,
 * quota and review-queue state.
 *
 * <p>Unknown information is reported as unknown — never as success. A
 * dependency failure is an explicit UNAVAILABLE outcome that the controller
 * turns into a 503; it is never a false "ready".
 */
@Service
public class ApplyReadinessService {

    public static final String UNAVAILABLE = "UNAVAILABLE";

    private final JdbcTemplate db;
    private final ApplyDocumentSelector documentSelector;
    private final JobFormQuestionService formQuestions;
    private final ApplicationAnswerRepository answers;
    private final ApplicationIdentityService identity;
    private final ApplicationDecisionService decisions;
    private final QuotaService quotas;

    public ApplyReadinessService(JdbcTemplate db, ApplyDocumentSelector documentSelector,
                                 JobFormQuestionService formQuestions, ApplicationAnswerRepository answers,
                                 ApplicationIdentityService identity, ApplicationDecisionService decisions,
                                 QuotaService quotas) {
        this.db = db;
        this.documentSelector = documentSelector;
        this.formQuestions = formQuestions;
        this.answers = answers;
        this.identity = identity;
        this.decisions = decisions;
        this.quotas = quotas;
    }

    /** Dependency failure: the state could not be established at all. */
    public static class ReadinessUnavailableException extends RuntimeException {
        public ReadinessUnavailableException(String cause) { super(cause); }
    }

    public Map<String, Object> evaluate(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null) throw new NoSuchElementException("Application not found");
        Map<String, Object> app;
        try {
            app = db.queryForMap("""
                    select a.id, a.job_id, a.status, a.cv_version_id,
                           j.title, j.company_name_raw, j.application_url, j.last_seen_at, j.deleted_at
                    from applications a join jobs j on j.id = a.job_id
                    where a.id = ? and a.profile_id = ?
                    """, applicationId, profileId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // A foreign application id takes the same path as a missing one.
            throw new NoSuchElementException("Application not found");
        } catch (org.springframework.dao.DataAccessException e) {
            // A dependency failure is explicit: never a false "not ready" and
            // never a false "ready".
            throw new ReadinessUnavailableException("APPLICATION_LOOKUP_FAILED");
        }
        UUID jobId = (UUID) app.get("job_id");

        List<Map<String, Object>> blockers = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<String> unknowns = new ArrayList<>();

        ApplyDocumentSelector.DocumentSelection selection = documentSelector.select(profileId, jobId, applicationId);
        blockers.addAll(selection.blockers());
        warnings.addAll(selection.warnings());

        Map<String, Object> questionsSection = questionSection(profileId, jobId, applicationId, blockers, warnings, unknowns);
        Map<String, Object> duplicateSection = duplicateSection(profileId, jobId, blockers);
        Map<String, Object> decisionSection = decisionSection(profileId, jobId, warnings, unknowns);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("availability", "AVAILABLE");
        out.put("application", Map.of(
                "applicationId", String.valueOf(app.get("id")),
                "jobId", String.valueOf(jobId),
                "jobTitle", app.get("title") == null ? "" : app.get("title"),
                "company", app.get("company_name_raw") == null ? "" : app.get("company_name_raw"),
                "applicationUrl", app.get("application_url") == null ? "" : app.get("application_url"),
                "status", app.get("status") == null ? "" : app.get("status")));
        Map<String, Object> documents = new LinkedHashMap<>();
        documents.put("cv", selection.cv() == null ? null : Map.of(
                "versionId", String.valueOf(selection.cv().versionId()),
                "pdfSha256", String.valueOf(selection.cv().pdfSha256()),
                "byteSize", selection.cv().byteSize(),
                "reviewedAt", selection.cv().reviewDecidedAt() == null ? "" : selection.cv().reviewDecidedAt()));
        documents.put("coverLetter", selection.coverLetter() == null ? null : Map.of(
                "versionId", String.valueOf(selection.coverLetter().versionId()),
                "version", selection.coverLetter().version(),
                "origin", selection.coverLetter().origin() == null ? "" : selection.coverLetter().origin(),
                "bodySha256", String.valueOf(selection.coverLetter().bodySha256()),
                "pdfSha256", selection.coverLetter().pdfSha256() == null ? "" : selection.coverLetter().pdfSha256()));
        documents.put("coverLetterRequirement", selection.coverLetterRequirement().name());
        documents.put("selectionBlocked", selection.blocked());
        out.put("documents", documents);
        out.put("questions", questionsSection);
        out.put("duplicates", duplicateSection);
        out.put("decision", decisionSection);
        out.put("blockers", blockers);
        out.put("warnings", warnings);
        out.put("unknowns", unknowns);
        out.put("packageReady", blockers.isEmpty());
        Map<String, Object> previewDocuments = new LinkedHashMap<>();
        previewDocuments.put("cv", selection.cv() == null ? null : String.valueOf(selection.cv().versionId()));
        previewDocuments.put("coverLetter",
                selection.coverLetter() == null ? null : String.valueOf(selection.coverLetter().versionId()));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("documents", previewDocuments);
        preview.put("coverLetterRequirement", selection.coverLetterRequirement().name());
        preview.put("note", "The exact immutable versions above are what an execution package would carry. "
                + "Nothing here submits an application.");
        out.put("packagePreview", preview);
        out.put("note", "Derived from your stored records at request time. READY_TO_APPLY, an approval or a "
                + "prepared package is never evidence of a submission. REAL_SUBMIT remains hard-stopped.");
        out.put("computedAt", Instant.now().toString());
        return out;
    }

    private Map<String, Object> questionSection(UUID profileId, UUID jobId, UUID applicationId,
                                                List<Map<String, Object>> blockers,
                                                List<Map<String, Object>> warnings,
                                                List<String> unknowns) {
        List<JobFormQuestionService.CapturedQuestion> captured = formQuestions.questionsForJob(jobId);
        List<ApplicationAnswerRecord> stored = answers.findByJobIdForProfile(jobId, profileId).stream()
                .filter(a -> applicationId.equals(a.applicationId()) || a.applicationId() == null)
                .toList();
        if (captured.isEmpty()) {
            unknowns.add("The employer's application form has not been captured for this job, so required "
                    + "questions and the cover-letter requirement are unknown — not assumed satisfied.");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (var question : captured) {
            if (!question.questionKey().startsWith("question_")) continue; // contact/upload fields are mapped, not answered
            ApplicationAnswerRecord answer = stored.stream()
                    .filter(a -> matches(a, question))
                    .findFirst().orElse(null);
            String answerState = answer == null ? "MISSING"
                    : !"ANSWERED".equals(answer.status()) ? answer.status()
                    : !answer.humanConfirmed() ? "UNCONFIRMED"
                    : "CONFIRMED";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionKey", question.questionKey());
            item.put("questionText", question.questionText() == null ? "" : question.questionText());
            item.put("requiredState", question.requiredState().name());
            item.put("answerType", question.answerType());
            item.put("options", question.options());
            item.put("answerState", answerState);
            item.put("answerOrigin", answer == null ? null : answer.answerOrigin());
            item.put("answerId", answer == null ? null : String.valueOf(answer.id()));
            item.put("source", question.source());
            item.put("formUrl", question.formUrl());
            items.add(item);

            if (question.requiredState() == RequiredState.REQUIRED) {
                if (answer == null) {
                    blockers.add(item("QUESTIONS", "REQUIRED_QUESTION_UNANSWERED",
                            "\"" + text(question) + "\" is required by this employer's form and has no answer."));
                } else if (!"ANSWERED".equals(answer.status())) {
                    blockers.add(item("QUESTIONS", "REQUIRED_QUESTION_" + answer.status(),
                            "\"" + text(question) + "\" is required but its draft answer is " + answer.status() + "."));
                } else if (!answer.humanConfirmed()) {
                    blockers.add(item("QUESTIONS", "REQUIRED_ANSWER_NOT_CONFIRMED",
                            "Your answer to \"" + text(question) + "\" is a draft suggestion. Confirm it before approval."));
                }
            } else if (question.requiredState() == RequiredState.UNKNOWN) {
                if (answer == null || !answer.humanConfirmed()) {
                    unknowns.add("The form does not say whether \"" + text(question) + "\" is required, and no "
                            + "confirmed answer exists. It is not assumed optional.");
                }
            } else if (answer != null && !answer.humanConfirmed()) {
                warnings.add(item("QUESTIONS", "ANSWER_NOT_CONFIRMED",
                        "Your answer to \"" + text(question) + "\" is a generated suggestion and has not been confirmed."));
            }
        }
        for (ApplicationAnswerRecord answer : stored) {
            if ("NEEDS_USER_INPUT".equals(answer.status()) || "HARD_STOP".equals(answer.status())) {
                blockers.add(item("QUESTIONS", "ANSWER_" + answer.status(),
                        "\"" + answer.questionText() + "\" has a drafted answer that is " + answer.status() + "."));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formCaptured", !captured.isEmpty());
        out.put("source", captured.isEmpty() ? null : captured.get(0).source());
        out.put("formUrl", captured.isEmpty() ? null : captured.get(0).formUrl());
        out.put("items", items);
        out.put("storedAnswerCount", stored.size());
        out.put("confirmedCount", stored.stream().filter(a -> a.humanConfirmed() && "ANSWERED".equals(a.status())).count());
        return out;
    }

    private Map<String, Object> duplicateSection(UUID profileId, UUID jobId, List<Map<String, Object>> blockers) {
        List<ApplicationIdentityService.ExistingApplication> duplicates = identity.findDuplicates(profileId, jobId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", true);
        out.put("status", duplicates.isEmpty() ? "NONE" : "DUPLICATE");
        out.put("items", duplicates.stream()
                .map(d -> Map.of("applicationId", String.valueOf(d.applicationId()),
                        "jobId", String.valueOf(d.jobId()), "matchReason", d.matchReason()))
                .toList());
        for (var duplicate : duplicates) {
            blockers.add(item("DUPLICATES", "DUPLICATE_APPLICATION",
                    "You already have an application for this role (" + duplicate.matchReason() + ")."));
        }
        return out;
    }

    private Map<String, Object> decisionSection(UUID profileId, UUID jobId,
                                                List<Map<String, Object>> warnings, List<String> unknowns) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationMode", decisions.effectiveApplicationMode(profileId));
        ApplicationDecisionService.RuleState rule = decisions.ruleState(profileId);
        out.put("ruleAvailability", rule.availability().name());
        if (rule.availability() == ApplicationDecisionService.RuleAvailability.UNREADABLE) {
            warnings.add(item("DECISION", "RULE_UNREADABLE",
                    "Your auto-approval rule cannot be read, so automatic approval is withheld and everything "
                            + "requires human review."));
        }
        try {
            db.query("""
                    select decision, reason, recommendation, match_score from application_decisions
                    where profile_id = ? and job_id = ?
                    """, (rs, n) -> Map.<String, Object>of(
                    "decision", rs.getString("decision"),
                    "reason", rs.getString("reason") == null ? "" : rs.getString("reason"),
                    "recommendation", rs.getString("recommendation") == null ? "" : rs.getString("recommendation"),
                    "matchScore", rs.getInt("match_score")), profileId, jobId)
                    .stream().findFirst()
                    .ifPresentOrElse(out::putAll, () -> unknowns.add(
                            "No decision record exists for this job, so the decision rules have not been evaluated for it."));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new ReadinessUnavailableException("DECISION_LOOKUP_FAILED");
        }
        try {
            QuotaService.QuotaCheckResult quota = quotas.check(profileId, "application_daily");
            out.put("quota", Map.of("allowed", quota.allowed(), "used", quota.used(),
                    "limit", quota.limit(), "unit", quota.unit()));
            if (!quota.allowed()) {
                warnings.add(item("DECISION", "QUOTA_EXHAUSTED",
                        "Your daily automatic-application quota is exhausted, so no automatic approval can proceed."));
            }
        } catch (org.springframework.dao.DataAccessException e) {
            throw new ReadinessUnavailableException("QUOTA_LOOKUP_FAILED");
        }
        return out;
    }

    private static boolean matches(ApplicationAnswerRecord answer, JobFormQuestionService.CapturedQuestion question) {
        if (answer.formQuestionId() != null) return answer.formQuestionId().equals(question.id());
        return question.questionText() != null && normalize(question.questionText()).equals(normalize(answer.questionText()));
    }

    private static String text(JobFormQuestionService.CapturedQuestion question) {
        return question.questionText() == null || question.questionText().isBlank()
                ? question.questionKey() : question.questionText();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Map<String, Object> item(String area, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("area", area);
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
