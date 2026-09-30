package com.personal.jobagent.automation;

import com.personal.jobagent.ats.GreenhouseAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the worker's READ-ONLY execution package for a GREENHOUSE plan
 * (Phase 3B).
 *
 * <p>The package is the single self-contained description the worker needs:
 * candidate identity/contact fields, artifact metadata (with download URLs
 * served by {@code AutomationController}), ANSWERED application answers,
 * the inspected Greenhouse form metadata with per-field classification, the
 * safety contract and the plan fingerprint. It contains NO credentials, NO
 * unapproved answers, and NO candidate values beyond what the classified
 * fields carry.
 *
 * <p>Artifacts are NOT written to disk by the backend: the worker downloads
 * them from the artifact endpoints (worker-authenticated) into its own
 * artifact directory and verifies sha256 itself. That keeps materialization
 * on the side that owns the files and avoids shared-volume requirements.
 */
@Service
public class ExecutionPackageService {

    public record ArtifactMeta(String kind, String url, String versionId, String sha256,
                               String fileName, long byteSize) {}

    public record ExecutionPackage(
            UUID planId,
            UUID applicationId,
            UUID jobId,
            String expectedUrlIncludes,
            Map<String, Object> candidate,
            Map<String, Object> cv,
            Map<String, Object> coverLetter,
            List<ArtifactMeta> artifacts,
            List<Map<String, Object>> answers,
            List<Map<String, Object>> fields,
            List<Map<String, Object>> humanRequired,
            List<Map<String, Object>> unsupported,
            String safetyContract
    ) {}

    private final JdbcTemplate db;
    private final GreenhouseAdapter greenhouseAdapter;

    public ExecutionPackageService(JdbcTemplate db, GreenhouseAdapter greenhouseAdapter) {
        this.db = db;
        this.greenhouseAdapter = greenhouseAdapter;
    }

    /**
     * Read-only. Re-inspects the live apply page at build time so the package
     * reflects the page the worker will actually see, then classifies every
     * inspected field deterministically via {@link GreenhouseFieldMapper}.
     */
    public ExecutionPackage build(UUID planId, UUID profileId, UUID applicationId, UUID jobId) {
        Map<String, Object> app = db.queryForMap("""
                select a.job_id, j.application_url
                from applications a join jobs j on j.id = a.job_id
                where a.id = ?
                """, applicationId);
        UUID jobIdResolved = ((UUID) app.get("job_id"));
        String applicationUrl = (String) app.get("application_url");
        if (applicationUrl == null || applicationUrl.isBlank()) {
            throw new IllegalStateException("application has no application_url");
        }

        com.personal.jobagent.ats.AtsAdapter.FormDescriptor descriptor = greenhouseAdapter.inspectForm(applicationUrl);

        Map<String, Object> candidateRow = db.queryForMap("""
                select u.email::text as email, u.display_name,
                       p.phone::text as phone, p.location::text as location,
                       coalesce(p.links::text, '{}') as links,
                       coalesce(p.work_eligibility::text, '{}') as work_eligibility
                from applications a
                join profiles p on p.id = a.profile_id
                join users u on u.id = p.user_id
                where a.id = ?
                """, applicationId);

        List<GreenhouseFieldMapper.Answer> answers = db.query("""
                select id, question_text, answer_text, status
                from application_answers
                where application_id = ? and profile_id = ? and job_id = ?
                """, (rs, n) -> new GreenhouseFieldMapper.Answer(
                        (UUID) rs.getObject("id"), rs.getString("question_text"),
                        rs.getString("answer_text"), rs.getString("status")),
                applicationId, profileId, jobIdResolved);

        GreenhouseFieldMapper.CandidateData candidate = new GreenhouseFieldMapper.CandidateData(
                (String) candidateRow.get("email"),
                (String) candidateRow.get("display_name"),
                (String) candidateRow.get("phone"),
                (String) candidateRow.get("location"),
                readJson((String) candidateRow.get("links")),
                readJson((String) candidateRow.get("work_eligibility")),
                false, null, null,
                false, null, null);

        // Tailored CV artifact (latest for profile+job with a rendered PDF).
        List<Map<String, Object>> cvRows = db.queryForList("""
                select r.cv_version_id::text as version_id, f.sha256, f.byte_size,
                       coalesce(v.title, 'tailored-cv') as title
                from resume_ats_analyses r
                join cv_versions v on v.id = r.cv_version_id
                join files f on f.id = v.pdf_file_id
                where r.profile_id = ? and r.job_id = ? and r.cv_version_id is not null
                order by r.id desc
                limit 1
                """, profileId, jobIdResolved);
        boolean cvAvailable = !cvRows.isEmpty();

        // Cover letter (latest for profile+job tied to this application).
        List<Map<String, Object>> clRows = db.queryForList("""
                select c.id::text as cover_letter_id, c.body_markdown
                from cover_letters c
                where c.profile_id = ? and c.job_id = ? and c.application_id = ?
                order by c.version desc
                limit 1
                """, profileId, jobIdResolved, applicationId);
        boolean coverAvailable = !clRows.isEmpty();
        String coverMarkdown = coverAvailable ? (String) clRows.get(0).get("body_markdown") : null;

        GreenhouseFieldMapper.CandidateData candidateWithArtifacts = new GreenhouseFieldMapper.CandidateData(
                candidate.email(), candidate.fullName(), candidate.phone(), candidate.location(),
                candidate.links(), candidate.workEligibility(),
                cvAvailable,
                cvAvailable ? UUID.fromString((String) cvRows.get(0).get("version_id")) : null,
                cvAvailable ? (String) cvRows.get(0).get("sha256") : null,
                coverAvailable, coverAvailable ? UUID.fromString((String) clRows.get(0).get("cover_letter_id")) : null,
                coverMarkdown);

        GreenhouseFieldMapper.MappingResult mapping = GreenhouseFieldMapper.classify(
                descriptor.fields(), candidateWithArtifacts, answers);

        List<ArtifactMeta> artifacts = new ArrayList<>();
        Map<String, Object> cvMeta = null;
        if (cvAvailable) {
            Map<String, Object> cvRow = cvRows.get(0);
            String versionId = (String) cvRow.get("version_id");
            String sha256 = (String) cvRow.get("sha256");
            artifacts.add(new ArtifactMeta("cv",
                    "/api/v1/automation/plans/" + planId + "/artifacts/cv",
                    versionId, sha256, "cv-" + versionId + ".pdf",
                    ((Number) cvRow.get("byte_size")).longValue()));
            cvMeta = new LinkedHashMap<>();
            cvMeta.put("versionId", versionId);
            cvMeta.put("sha256", sha256);
            cvMeta.put("jobId", jobIdResolved.toString());
            cvMeta.put("applicationId", applicationId.toString());
            cvMeta.put("fileName", "cv-" + versionId + ".pdf");
        }
        Map<String, Object> coverMeta = null;
        if (coverAvailable) {
            String coverId = (String) clRows.get(0).get("cover_letter_id");
            String txt = coverMarkdown == null ? "" : coverMarkdown;
            String sha256 = Sha256.of(txt);
            artifacts.add(new ArtifactMeta("coverLetter",
                    "/api/v1/automation/plans/" + planId + "/artifacts/cover-letter",
                    coverId, sha256, "cover-letter-" + coverId + ".txt",
                    txt.length()));
            coverMeta = new LinkedHashMap<>();
            coverMeta.put("versionId", coverId);
            coverMeta.put("sha256", sha256);
            coverMeta.put("jobId", jobIdResolved.toString());
            coverMeta.put("applicationId", applicationId.toString());
            coverMeta.put("fileName", "cover-letter-" + coverId + ".txt");
        }

        List<Map<String, Object>> answerRows = new ArrayList<>();
        for (GreenhouseFieldMapper.Answer answer : answers) {
            if (!"ANSWERED".equals(answer.status())) continue;
            String matchedKey = matchKey(mapping.mapped(), answer);
            if (matchedKey != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", matchedKey);
                row.put("questionText", answer.questionText());
                row.put("answerText", answer.answerText());
                row.put("source", "application_answers (ANSWERED)");
                answerRows.add(row);
            }
        }

        List<Map<String, Object>> fieldRows = new ArrayList<>();
        for (GreenhouseFieldMapper.MappedField mapped : mapping.mapped()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", mapped.field().key());
            row.put("label", mapped.field().label());
            row.put("htmlType", mapped.field().htmlType());
            row.put("required", mapped.field().required());
            row.put("selector", mapped.field().selector());
            row.put("options", mapped.field().options());
            row.put("classification", mapped.classification());
            row.put("valueSource", mapped.valueSource());
            row.put("reason", mapped.reason());
            fieldRows.add(row);
        }
        List<Map<String, Object>> humanRequired = new ArrayList<>();
        for (GreenhouseFieldMapper.HumanItem item : mapping.humanRequired()) {
            humanRequired.add(Map.of("key", item.key(), "label", item.label(),
                    "classification", item.classification(), "reason", item.reason()));
        }
        List<Map<String, Object>> unsupported = new ArrayList<>();
        for (GreenhouseFieldMapper.HumanItem item : mapping.unsupported()) {
            unsupported.add(Map.of("key", item.key(), "label", item.label(),
                    "classification", item.classification(), "reason", item.reason()));
        }

        String expectedUrlIncludes = "greenhouse.io";
        return new ExecutionPackage(planId, applicationId, jobIdResolved, expectedUrlIncludes,
                Map.of(
                        "email", candidate.email() == null ? "" : candidate.email(),
                        "fullName", candidate.fullName() == null ? "" : candidate.fullName(),
                        "phone", candidate.phone() == null ? "" : candidate.phone(),
                        "location", candidate.location() == null ? "" : candidate.location()
                ),
                cvMeta, coverMeta, artifacts, answerRows, fieldRows, humanRequired, unsupported,
                "Execute only listed deterministic steps; never bypass CAPTCHA or anti-bot; "
                        + "never invent facts or credentials; stop before submission unless explicitly approved.");
    }

    /** Deterministic artifact bytes for one download. Returns null when absent. */
    public ArtifactBytes artifactBytes(UUID planId, UUID profileId, UUID applicationId, UUID jobId, String kind) {
        return switch (kind) {
            case "cv" -> {
                List<Map<String, Object>> rows = db.queryForList("""
                        select f.content, f.sha256 from resume_ats_analyses r
                        join cv_versions v on v.id = r.cv_version_id
                        join files f on f.id = v.pdf_file_id
                        where r.profile_id = ? and r.job_id = ? and r.cv_version_id is not null
                        order by r.id desc limit 1
                        """, profileId, jobIdResolved(applicationId));
                if (rows.isEmpty()) yield null;
                yield new ArtifactBytes((byte[]) rows.get(0).get("content"),
                        (String) rows.get(0).get("sha256"), "application/pdf",
                        "cv-" + applicationId + ".pdf");
            }
            case "cover-letter" -> {
                List<Map<String, Object>> rows = db.queryForList("""
                        select c.body_markdown from cover_letters c
                        where c.profile_id = ? and c.job_id = ? and c.application_id = ?
                        order by c.version desc limit 1
                        """, profileId, jobIdResolved(applicationId));
                if (rows.isEmpty()) yield null;
                String markdown = (String) rows.get(0).get("body_markdown");
                byte[] bytes = markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                yield new ArtifactBytes(bytes, Sha256.of(bytes), "text/plain; charset=utf-8",
                        "cover-letter-" + applicationId + ".txt");
            }
            default -> throw new IllegalArgumentException("unknown artifact kind " + kind);
        };
    }

    public record ArtifactBytes(byte[] content, String sha256, String contentType, String fileName) {}

    private UUID jobIdResolved(UUID applicationId) {
        return db.queryForObject("select job_id from applications where id = ?", UUID.class, applicationId);
    }

    private static String matchKey(List<GreenhouseFieldMapper.MappedField> mapped,
                                   GreenhouseFieldMapper.Answer answer) {
        String normalized = answer.questionText() == null ? "" : answer.questionText().trim().toLowerCase();
        for (GreenhouseFieldMapper.MappedField field : mapped) {
            if (field.classification().startsWith("SUPPORTED")
                    && field.field().label() != null
                    && field.field().label().trim().toLowerCase().replaceAll("\\s+", " ")
                            .equals(normalized)) {
                return field.field().key();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Small sha256 helper so packages and downloads share one implementation. */
    public static final class Sha256 {
        private Sha256() {}
        public static String of(byte[] bytes) {
            try {
                return java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        public static String of(String text) {
            return of(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
