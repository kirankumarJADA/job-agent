package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.personal.jobagent.ats.AtsAdapter.FormDescriptor;
import com.personal.jobagent.ats.GreenhouseAdapter;
import com.personal.jobagent.ats.JobFormQuestionService;
import com.personal.jobagent.coverletter.CoverLetterRecord;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds an owner-scoped, read-only execution package for Greenhouse plans.
 *
 * <p>Phase 8.2: the documents in the package are chosen by
 * {@link ApplyDocumentSelector} — exact, immutable, reviewed and digest-bound
 * versions, or the package is BLOCKED. The pre-8.2 defect this replaces
 * attached the latest cover letter regardless of approval. The package
 * records the exact document ids/versions/digests it selected and a
 * {@code packageDigest} over the document selection, answers and field
 * values, so approval and execution can detect any drift afterwards.
 */
@Service
public class ExecutionPackageService {
    public record ArtifactMeta(String kind, String url, String versionId, String sha256,
                               String fileName, long byteSize) {}
    public record ExecutionPackage(UUID planId, UUID applicationId, UUID jobId, String expectedUrl,
                                   Map<String, Object> candidate, Map<String, Object> cv,
                                   Map<String, Object> coverLetter, List<ArtifactMeta> artifacts,
                                   List<Map<String, Object>> answers, List<Map<String, Object>> fields,
                                   List<Map<String, Object>> humanRequired,
                                   List<Map<String, Object>> unsupported, List<Map<String, Object>> requiredGaps,
                                   String safetyContract, Map<String, Object> documentSelection,
                                   String packageDigest) {}
    public record ArtifactBytes(byte[] content, String sha256, String contentType, String fileName) {}

    private final JdbcTemplate db;
    private final GreenhouseAdapter greenhouseAdapter;
    private final ApplyDocumentSelector documentSelector;
    private final JobFormQuestionService formQuestions;
    private final CoverLetterRepository letters;
    private final ObjectMapper json;

    public ExecutionPackageService(JdbcTemplate db, GreenhouseAdapter greenhouseAdapter,
                                   ApplyDocumentSelector documentSelector,
                                   JobFormQuestionService formQuestions,
                                   CoverLetterRepository letters, ObjectMapper json) {
        this.db = db;
        this.greenhouseAdapter = greenhouseAdapter;
        this.documentSelector = documentSelector;
        this.formQuestions = formQuestions;
        this.letters = letters;
        this.json = json;
    }

    /**
     * Re-inspects the target and binds all candidate/artifact data to this
     * exact application.
     *
     * @throws ExecutionPackageBlockedException when the exact document
     *         versions cannot be selected fail-closed (missing review,
     *         integrity failure, legacy or stale validation, digest
     *         mismatch). The package is then not built at all — a wrong or
     *         unreviewed document is never substituted.
     */
    public ExecutionPackage build(UUID planId, UUID profileId, UUID applicationId, UUID jobId) {
        Map<String, Object> app = db.queryForMap("""
                select a.id, a.profile_id, a.job_id, a.cv_version_id, j.application_url
                from applications a join jobs j on j.id = a.job_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String targetUrl = (String) app.get("application_url");
        if (targetUrl == null || !greenhouseAdapter.matchesUrl(targetUrl)) {
            throw new IllegalStateException("GREENHOUSE_TARGET_MISMATCH");
        }
        FormDescriptor descriptor = greenhouseAdapter.inspectForm(targetUrl);

        // Durable, provenance-carrying capture of the employer's own form
        // questions (required/optional/unknown as the form actually says).
        formQuestions.capture(profileId, jobId, targetUrl, descriptor.fields());

        // Fail-closed document selection: exact reviewed/validated/intact
        // versions, or a blocked package.
        ApplyDocumentSelector.DocumentSelection selection =
                documentSelector.select(profileId, jobId, applicationId);
        if (selection.blocked()) {
            throw new ExecutionPackageBlockedException(
                    "EXECUTION_PACKAGE_BLOCKED: the exact document versions for this application cannot be selected",
                    selection.blockers());
        }

        Map<String, Object> candidateRow = db.queryForMap("""
                select u.email::text as email, u.display_name, p.phone::text as phone,
                       p.location::text as location, coalesce(p.links::text, '{}') as links,
                       coalesce(p.work_eligibility::text, '{}') as work_eligibility
                from applications a join profiles p on p.id = a.profile_id
                join users u on u.id = p.user_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        List<GreenhouseFieldMapper.Answer> storedAnswers = db.query("""
                select id, question_text, answer_text, status, human_confirmed from application_answers
                where application_id = ? and profile_id = ? and job_id = ?
                order by created_at, id
                """, (rs, row) -> new GreenhouseFieldMapper.Answer(
                (UUID) rs.getObject("id"), rs.getString("question_text"),
                rs.getString("answer_text"), rs.getString("status"), rs.getBoolean("human_confirmed")), applicationId, profileId, jobId);

        // The exact selected documents (ApplyDocumentSelector has proven
        // ownership, review/approval, current validation and integrity).
        List<ArtifactMeta> artifacts = new ArrayList<>();
        Map<String, Object> cv = null;
        if (selection.cv() != null) {
            var selected = selection.cv();
            String version = selected.versionId().toString();
            String sha = selected.pdfSha256();
            if (sha == null || selected.byteSize() <= 0) throw new IllegalStateException("CV_ARTIFACT_METADATA_MISSING");
            artifacts.add(new ArtifactMeta("cv", "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version,
                    version, sha, "cv-" + version + ".pdf", selected.byteSize()));
            Map<String, Object> cvMap = new LinkedHashMap<>();
            cvMap.put("versionId", version);
            cvMap.put("sha256", sha);
            cvMap.put("jobId", jobId.toString());
            cvMap.put("applicationId", applicationId.toString());
            cvMap.put("fileName", "cv-" + version + ".pdf");
            cvMap.put("url", "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version);
            cvMap.put("byteSize", selected.byteSize());
            cvMap.put("reviewedAt", selected.reviewDecidedAt());
            cv = cvMap;
        }

        Map<String, Object> coverLetter = null;
        String coverText = null;
        if (selection.coverLetter() != null) {
            var selected = selection.coverLetter();
            CoverLetterRecord record = letters.findByIdForProfile(selected.versionId(), profileId)
                    .orElseThrow(() -> new IllegalStateException("COVER_LETTER_VANISHED"));
            coverText = record.bodyMarkdown();
            byte[] bytes = coverText.getBytes(StandardCharsets.UTF_8);
            String bodySha = Sha256.of(bytes);
            if (!bodySha.equals(selected.bodySha256())) {
                throw new ExecutionPackageBlockedException("COVER_LETTER_CHECKSUM_MISMATCH",
                        List.of(Map.of("area", "COVER_LETTER", "code", "COVER_LETTER_INTEGRITY_FAILED",
                                "message", "The selected cover letter no longer matches its recorded digest.")));
            }
            String version = selected.versionId().toString();
            artifacts.add(new ArtifactMeta("coverLetter", "/api/v1/automation/plans/" + planId
                    + "/artifacts/cover-letter?versionId=" + version, version, bodySha,
                    "cover-letter-" + version + ".txt", bytes.length));
            Map<String, Object> letterMap = new LinkedHashMap<>();
            letterMap.put("versionId", version);
            letterMap.put("version", selected.version());
            letterMap.put("origin", selected.origin());
            letterMap.put("sha256", bodySha);
            letterMap.put("jobId", jobId.toString());
            letterMap.put("applicationId", applicationId.toString());
            letterMap.put("fileName", "cover-letter-" + version + ".txt");
            letterMap.put("url", "/api/v1/automation/plans/" + planId + "/artifacts/cover-letter?versionId=" + version);
            letterMap.put("pdfSha256", selected.pdfSha256());
            letterMap.put("pdfStored", selected.pdfStored());
            coverLetter = letterMap;
        }

        var candidate = new GreenhouseFieldMapper.CandidateData(
                (String) candidateRow.get("email"), (String) candidateRow.get("display_name"),
                (String) candidateRow.get("phone"), (String) candidateRow.get("location"),
                readJson(candidateRow.get("links")), readJson(candidateRow.get("work_eligibility")),
                selection.cv() != null, selection.cv() == null ? null : selection.cv().versionId(),
                selection.cv() == null ? null : selection.cv().pdfSha256(),
                selection.coverLetter() != null,
                selection.coverLetter() == null ? null : selection.coverLetter().versionId(),
                coverText);
        GreenhouseFieldMapper.MappingResult mapping = GreenhouseFieldMapper.classify(descriptor.fields(), candidate, storedAnswers);

        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> requiredGaps = new ArrayList<>();
        for (var field : descriptor.fields()) {
            var outcome = GreenhouseFieldMapper.classifyField(field, candidate, storedAnswers);
            String classification = outcome.classification();
            boolean contact = List.of("first_name", "last_name", "email", "phone", "candidate-location", "location").contains(field.key());
            boolean isQuestion = field.key() != null && field.key().startsWith("question_");
            String value = "";
            if (contact && List.of("text", "tel", "email").contains(field.htmlType())
                    && GreenhouseFieldMapper.AUTO.equals(classification) && outcome.value() != null
                    && field.selector() != null && field.selector().equals("#" + field.key())) {
                value = outcome.value();
            } else if (GreenhouseFieldMapper.AUTO.equals(classification)
                    && outcome.value() != null && "application_answers (human-confirmed)".equals(outcome.valueSource())
                    && uniqueConfirmedQuestion(field, descriptor.fields(), storedAnswers)
                    && (List.of("text", "email", "tel", "textarea").contains(field.htmlType())
                    || (List.of("select", "radio").contains(field.htmlType()) && field.options().contains(outcome.value())))) {
                value = outcome.value();
            } else if (GreenhouseFieldMapper.AUTO.equals(classification)
                    && "file".equals(field.htmlType())
                    && (("resume".equals(field.key()) && cv != null)
                    || ("cover_letter".equals(field.key()) && coverLetter != null))) {
                // Binary actions are materialized as separate upload steps.
            } else if (GreenhouseFieldMapper.AUTO.equals(classification)) {
                classification = GreenhouseFieldMapper.HUMAN;
            }
            Map<String, Object> fieldRow = new LinkedHashMap<>();
            fieldRow.put("key", field.key()); fieldRow.put("label", field.label() == null ? "" : field.label());
            fieldRow.put("htmlType", field.htmlType());
            fieldRow.put("required", field.required());
            fieldRow.put("requiredState", field.requiredState().name());
            fieldRow.put("selector", field.selector());
            fieldRow.put("options", field.options());
            fieldRow.put("classification", classification);
            fieldRow.put("valueSource", outcome.valueSource() == null ? "" : outcome.valueSource());
            fieldRow.put("value", value);
            fieldRow.put("reason", outcome.reason() == null ? "" : outcome.reason());
            fields.add(fieldRow);
            boolean requiredUploadMissing = field.required() && "file".equals(field.htmlType())
                    && ("resume".equals(field.key()) ? cv == null
                    : "cover_letter".equals(field.key()) ? coverLetter == null : true);
            // A gap exists only while a field has no safe value: a required
            // employer question with a candidate-confirmed, safely mapped
            // answer is resolved (it is still shown in the package preview);
            // without one it stays a human-required gap, and approval refuses
            // to proceed while any gap remains.
            if ((field.required() && value.isBlank() && !"file".equals(field.htmlType()))
                    || requiredUploadMissing || GreenhouseFieldMapper.HARD_STOP.equals(classification)) {
                requiredGaps.add(Map.of("key", field.key(), "label", field.label() == null ? "" : field.label(),
                        "classification", classification, "reason", isQuestion ? "application question requires human review"
                                : outcome.reason() == null ? "required field has no safe value" : outcome.reason()));
            }
        }

        List<Map<String, Object>> human = mapping.humanRequired().stream().map(item -> Map.<String, Object>of(
                "key", item.key(), "label", item.label() == null ? "" : item.label(),
                "classification", item.classification(), "reason", item.reason() == null ? "" : item.reason())).toList();
        List<Map<String, Object>> unsupported = mapping.unsupported().stream().map(item -> Map.<String, Object>of(
                "key", item.key(), "label", item.label() == null ? "" : item.label(),
                "classification", item.classification(), "reason", item.reason() == null ? "" : item.reason())).toList();
        List<Map<String, Object>> answers = new ArrayList<>();
        for (var answer : storedAnswers) {
            if (!"ANSWERED".equals(answer.status()) || !answer.humanConfirmed()
                    || answer.answerText() == null || answer.answerText().isBlank()) continue;
            long exactMatches = descriptor.fields().stream().filter(field -> field.label() != null
                    && normalize(field.label()).equals(normalize(answer.questionText()))).count();
            if (exactMatches == 1 && mapping.mapped().stream().anyMatch(mapped -> normalize(mapped.field().label())
                    .equals(normalize(answer.questionText())) && mapped.classification().startsWith("SUPPORTED"))) {
                answers.add(Map.of("questionText", answer.questionText(), "answerText", answer.answerText(), "source", "application_answers (human-confirmed)"));
            }
        }

        Map<String, Object> documentSelection = new LinkedHashMap<>();
        documentSelection.put("cv", cv);
        documentSelection.put("coverLetter", coverLetter);
        documentSelection.put("coverLetterRequirement", selection.coverLetterRequirement().name());
        documentSelection.put("warnings", selection.warnings());
        documentSelection.put("rule", "Exact, immutable, reviewed/validated versions bound to this application; "
                + "no version is ever substituted when a document fails its checks.");
        String packageDigest = digestOf(cv, coverLetter, answers, fields);

        return new ExecutionPackage(planId, applicationId, jobId, targetUrl,
                Map.of("email", candidate.email() == null ? "" : candidate.email(),
                        "fullName", candidate.fullName() == null ? "" : candidate.fullName(),
                        "phone", candidate.phone() == null ? "" : candidate.phone(),
                        "location", candidate.location() == null ? "" : candidate.location()),
                cv, coverLetter, artifacts, answers, fields, human, unsupported, requiredGaps,
                AutomationPlan.SAFETY_CONTRACT, documentSelection, packageDigest);
    }

    /**
     * Reproducibility digest over the exact document selection, the confirmed
     * answers and the per-field values/classifications. Approval and execution
     * recompute it: if any record moved underneath the package, the digest
     * differs and the stale approval must be re-evaluated.
     */
    private String digestOf(Map<String, Object> cv, Map<String, Object> coverLetter,
                            List<Map<String, Object>> answers, List<Map<String, Object>> fields) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("cv", cv == null ? null : Map.of(
                "versionId", String.valueOf(cv.get("versionId")), "sha256", String.valueOf(cv.get("sha256"))));
        basis.put("coverLetter", coverLetter == null ? null : Map.of(
                "versionId", String.valueOf(coverLetter.get("versionId")),
                "sha256", String.valueOf(coverLetter.get("sha256")),
                "pdfSha256", String.valueOf(coverLetter.get("pdfSha256"))));
        basis.put("answers", answers);
        basis.put("fieldValues", fields.stream()
                .map(f -> Map.of("key", String.valueOf(f.get("key")), "value", String.valueOf(f.get("value")),
                        "classification", String.valueOf(f.get("classification")))).toList());
        try {
            String canonical = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(basis);
            return Sha256.of(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("PACKAGE_DIGEST_UNAVAILABLE", e);
        }
    }

    /**
     * True when every confirmed answer the package carries is STILL the
     * current, human-confirmed answer for its question. An edit, an
     * unconfirmation or a deleted answer makes the package stale (Phase 8.2).
     */
    public boolean answersStillCurrent(UUID profileId, UUID applicationId, UUID jobId,
                                       List<Map<String, Object>> packagedAnswers) {
        if (packagedAnswers == null || packagedAnswers.isEmpty()) return true;
        List<GreenhouseFieldMapper.Answer> current = db.query("""
                select id, question_text, answer_text, status, human_confirmed from application_answers
                where application_id = ? and profile_id = ? and job_id = ?
                order by created_at, id
                """, (rs, row) -> new GreenhouseFieldMapper.Answer(
                (UUID) rs.getObject("id"), rs.getString("question_text"),
                rs.getString("answer_text"), rs.getString("status"), rs.getBoolean("human_confirmed")),
                applicationId, profileId, jobId);
        for (Map<String, Object> packaged : packagedAnswers) {
            String question = String.valueOf(packaged.getOrDefault("questionText", ""));
            String answer = String.valueOf(packaged.getOrDefault("answerText", ""));
            boolean stillCurrent = current.stream().anyMatch(c -> normalize(c.questionText()).equals(normalize(question))
                    && "ANSWERED".equals(c.status()) && c.humanConfirmed() && answer.equals(c.answerText()));
            if (!stillCurrent) return false;
        }
        return true;
    }

    /** Returns only the exact, application-linked version named in the package. */
    public ArtifactBytes artifactBytes(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                       String kind, UUID versionId) {
        if (planId == null || profileId == null || applicationId == null || jobId == null || versionId == null)
            throw new IllegalArgumentException("artifact correlation and immutable version are required");
        if (!jobId.equals(db.queryForObject("select job_id from applications where id = ? and profile_id = ?", UUID.class, applicationId, profileId)))
            throw new IllegalArgumentException("artifact application/job mismatch");
        return switch (kind) {
            case "cv" -> {
                List<Map<String, Object>> rows = db.queryForList("""
                        select f.content, f.sha256, f.content_type from applications a
                        join cv_versions v on v.application_id = a.id join files f on f.id = v.pdf_file_id
                        where a.id = ? and a.profile_id = ? and a.job_id = ? and v.id = ?
                          and v.profile_id = ? and v.application_id = ? and v.job_id = ?
                          and v.immutable = true and f.content is not null
                        """, applicationId, profileId, jobId, versionId, profileId, applicationId, jobId);
                if (rows.isEmpty()) yield null;
                byte[] content = (byte[]) rows.getFirst().get("content");
                String sha = (String) rows.getFirst().get("sha256");
                if (content == null || sha == null || !sha.equals(Sha256.of(content))) throw new IllegalStateException("CV_ARTIFACT_CHECKSUM_MISMATCH");
                yield new ArtifactBytes(content, sha, (String) rows.getFirst().get("content_type"), "cv-" + versionId + ".pdf");
            }
            case "cover-letter" -> {
                List<Map<String, Object>> rows = db.queryForList("""
                        select body_markdown from cover_letters
                        where id = ? and profile_id = ? and job_id = ? and application_id = ?
                        """, versionId, profileId, jobId, applicationId);
                if (rows.isEmpty()) yield null;
                byte[] content = String.valueOf(rows.getFirst().get("body_markdown")).getBytes(StandardCharsets.UTF_8);
                yield new ArtifactBytes(content, Sha256.of(content), "text/plain; charset=utf-8", "cover-letter-" + versionId + ".txt");
            }
            default -> throw new IllegalArgumentException("unknown artifact kind");
        };
    }

    private static boolean uniqueConfirmedQuestion(com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor field,
                                                   List<com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor> fields,
                                                   List<GreenhouseFieldMapper.Answer> answers) {
        if (field.label() == null || fields.stream().filter(other -> normalize(other.label()).equals(normalize(field.label()))).count() != 1) {
            return false;
        }
        return answers.stream().filter(answer -> normalize(answer.questionText()).equals(normalize(field.label()))
                && "ANSWERED".equals(answer.status()) && answer.humanConfirmed()
                && answer.answerText() != null && !answer.answerText().isBlank()).count() == 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Object value) {
        try { return json.readValue(String.valueOf(value), Map.class); }
        catch (Exception ignored) { return Map.of(); }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " "); }

    public static final class Sha256 {
        private Sha256() {}
        public static String of(byte[] bytes) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
        }
        public static String of(String value) { return of(value.getBytes(StandardCharsets.UTF_8)); }
    }
}
