package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapter.FormDescriptor;
import com.personal.jobagent.ats.GreenhouseAdapter;
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

/** Builds an owner-scoped, read-only execution package for Greenhouse plans. */
@Service
public class ExecutionPackageService {
    public record ArtifactMeta(String kind, String url, String versionId, String sha256,
                               String fileName, long byteSize) {}
    public record ExecutionPackage(UUID planId, UUID applicationId, UUID jobId, String expectedUrl,
                                   Map<String, Object> candidate, Map<String, Object> cv,
                                   Map<String, Object> coverLetter, List<ArtifactMeta> artifacts,
                                   List<Map<String, Object>> answers, List<Map<String, Object>> fields,
                                   List<Map<String, Object>> humanRequired,
                                   List<Map<String, Object>> unsupported,
                                   List<Map<String, Object>> requiredGaps, String safetyContract) {}
    public record ArtifactBytes(byte[] content, String sha256, String contentType, String fileName) {}

    private final JdbcTemplate db;
    private final GreenhouseAdapter greenhouseAdapter;
    private final ObjectMapper json;

    public ExecutionPackageService(JdbcTemplate db, GreenhouseAdapter greenhouseAdapter, ObjectMapper json) {
        this.db = db;
        this.greenhouseAdapter = greenhouseAdapter;
        this.json = json;
    }

    /** Re-inspects the target and binds all candidate/artifact data to this exact application. */
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
                """, (rs, row) -> new GreenhouseFieldMapper.Answer(
                (UUID) rs.getObject("id"), rs.getString("question_text"),
                rs.getString("answer_text"), rs.getString("status"), rs.getBoolean("human_confirmed")), applicationId, profileId, jobId);

        UUID cvVersionId = (UUID) app.get("cv_version_id");
        List<Map<String, Object>> cvRows = cvVersionId == null ? List.of() : db.queryForList("""
                select v.id::text as version_id, f.sha256, f.byte_size
                from cv_versions v join files f on f.id = v.pdf_file_id
                where v.id = ? and v.profile_id = ? and v.application_id = ? and v.job_id = ?
                  and v.immutable = true and f.content is not null
                """, cvVersionId, profileId, applicationId, jobId);
        List<Map<String, Object>> coverRows = db.queryForList("""
                select id::text as version_id, body_markdown from cover_letters
                where profile_id = ? and job_id = ? and application_id = ?
                order by version desc limit 1
                """, profileId, jobId, applicationId);
        String coverText = coverRows.isEmpty() ? null : (String) coverRows.getFirst().get("body_markdown");
        var candidate = new GreenhouseFieldMapper.CandidateData(
                (String) candidateRow.get("email"), (String) candidateRow.get("display_name"),
                (String) candidateRow.get("phone"), (String) candidateRow.get("location"),
                readJson(candidateRow.get("links")), readJson(candidateRow.get("work_eligibility")),
                !cvRows.isEmpty(), cvRows.isEmpty() ? null : UUID.fromString((String) cvRows.getFirst().get("version_id")),
                cvRows.isEmpty() ? null : (String) cvRows.getFirst().get("sha256"),
                !coverRows.isEmpty(), coverRows.isEmpty() ? null : UUID.fromString((String) coverRows.getFirst().get("version_id")),
                coverText);
        GreenhouseFieldMapper.MappingResult mapping = GreenhouseFieldMapper.classify(descriptor.fields(), candidate, storedAnswers);

        List<ArtifactMeta> artifacts = new ArrayList<>();
        Map<String, Object> cv = null;
        if (!cvRows.isEmpty()) {
            Map<String, Object> row = cvRows.getFirst();
            String version = (String) row.get("version_id");
            String sha = (String) row.get("sha256");
            if (sha == null || row.get("byte_size") == null) throw new IllegalStateException("CV_ARTIFACT_METADATA_MISSING");
            artifacts.add(new ArtifactMeta("cv", "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version,
                    version, sha, "cv-" + version + ".pdf", ((Number) row.get("byte_size")).longValue()));
            cv = Map.of("versionId", version, "sha256", sha, "jobId", jobId.toString(),
                    "applicationId", applicationId.toString(), "fileName", "cv-" + version + ".pdf",
                    "url", "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version);
        }
        Map<String, Object> coverLetter = null;
        if (!coverRows.isEmpty()) {
            String version = (String) coverRows.getFirst().get("version_id");
            byte[] bytes = coverText.getBytes(StandardCharsets.UTF_8);
            artifacts.add(new ArtifactMeta("coverLetter", "/api/v1/automation/plans/" + planId
                    + "/artifacts/cover-letter?versionId=" + version, version, Sha256.of(bytes),
                    "cover-letter-" + version + ".txt", bytes.length));
            coverLetter = Map.of("versionId", version, "sha256", Sha256.of(bytes), "jobId", jobId.toString(),
                    "applicationId", applicationId.toString(), "fileName", "cover-letter-" + version + ".txt",
                    "url", "/api/v1/automation/plans/" + planId + "/artifacts/cover-letter?versionId=" + version);
        }

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
            fieldRow.put("htmlType", field.htmlType()); fieldRow.put("required", field.required());
            fieldRow.put("selector", field.selector()); fieldRow.put("options", field.options());
            fieldRow.put("classification", classification); fieldRow.put("valueSource", outcome.valueSource() == null ? "" : outcome.valueSource());
            fieldRow.put("value", value); fieldRow.put("reason", outcome.reason() == null ? "" : outcome.reason());
            fields.add(fieldRow);
            boolean requiredUploadMissing = field.required() && "file".equals(field.htmlType())
                    && ("resume".equals(field.key()) ? cv == null
                    : "cover_letter".equals(field.key()) ? coverLetter == null : true);
            if ((field.required() && value.isBlank() && !"file".equals(field.htmlType()))
                    || requiredUploadMissing || GreenhouseFieldMapper.HARD_STOP.equals(classification)
                    || (isQuestion && field.required())) {
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
        return new ExecutionPackage(planId, applicationId, jobId, targetUrl,
                Map.of("email", candidate.email() == null ? "" : candidate.email(),
                        "fullName", candidate.fullName() == null ? "" : candidate.fullName(),
                        "phone", candidate.phone() == null ? "" : candidate.phone(),
                        "location", candidate.location() == null ? "" : candidate.location()),
                cv, coverLetter, artifacts, answers, fields, human, unsupported, requiredGaps,
                AutomationPlan.SAFETY_CONTRACT);
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
                        join cv_versions v on v.id = a.cv_version_id join files f on f.id = v.pdf_file_id
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
