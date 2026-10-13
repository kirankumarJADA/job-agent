package com.personal.jobagent.ats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapter.RequiredState;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Durable capture of EMPLOYER application-form questions (Phase 8.2).
 *
 * <p>Robin only ever records what a real, read-only inspection of the job's
 * application form actually exposed: the stable control key, the question
 * text, the answer type, the options that were statically present, and what
 * the source said about required-ness. Required-ness is tri-state on purpose:
 * {@code REQUIRED} when the form marks the field required, {@code OPTIONAL}
 * only when the form positively rendered the field optional, and
 * {@code UNKNOWN} when the metadata is simply absent. A field is
 * NEVER assumed optional because the metadata is missing, and no question is
 * ever invented.
 *
 * <p>Questions are captured per job (the employer's form is a property of the
 * posting), while answers stay per candidate and are linked to the question
 * they answer through {@code application_answers.form_question_id}.
 */
@Service
public class JobFormQuestionService {

    public static final String SOURCE_GREENHOUSE = "GREENHOUSE_PUBLIC_FORM";

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public JobFormQuestionService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** The employer's cover-letter requirement, evidence-backed or UNKNOWN. */
    public enum CoverLetterRequirement { REQUIRED, OPTIONAL, UNKNOWN }

    public record CapturedQuestion(UUID id, String questionKey, String questionText, RequiredState requiredState,
                                   String answerType, List<String> options, String source, String formUrl,
                                   String captureStatus, java.time.Instant capturedAt) {}

    /**
     * Persists the inspected form's fields for one job and binds this
     * candidate's stored answers to the question they answer. Idempotent:
     * re-inspection updates the captured metadata in place.
     */
    public List<CapturedQuestion> capture(UUID profileId, UUID jobId, String formUrl,
                                          List<AtsAdapter.FormFieldDescriptor> fields) {
        List<CapturedQuestion> captured = new ArrayList<>();
        if (fields == null) return captured;
        for (AtsAdapter.FormFieldDescriptor field : fields) {
            if (field == null || field.key() == null || field.key().isBlank()) continue;
            // The parser reports exactly what the employer's form said,
            // tri-state: absent metadata stays UNKNOWN and is never assumed
            // optional.
            RequiredState required = field.requiredState();
            UUID id = UuidV7.generate();
            try {
                db.update("""
                        insert into job_form_questions
                            (id, job_id, source, form_url, question_key, question_text, required_state,
                             answer_type, options, capture_status, captured_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'INSPECTED', now())
                        on conflict (job_id, source, question_key) do update set
                            question_text = excluded.question_text,
                            required_state = excluded.required_state,
                            answer_type = excluded.answer_type,
                            options = excluded.options,
                            form_url = excluded.form_url,
                            capture_status = 'INSPECTED',
                            captured_at = now()
                        """, id, jobId, SOURCE_GREENHOUSE, formUrl, field.key(),
                        field.label(), required.name(), field.htmlType(),
                        json.writeValueAsString(field.options() == null ? List.of() : field.options()));
            } catch (Exception e) {
                throw new IllegalStateException("FORM_QUESTION_CAPTURE_FAILED", e);
            }
            UUID storedId = db.query("""
                    select id from job_form_questions where job_id = ? and source = ? and question_key = ?
                    """, (rs, n) -> (UUID) rs.getObject(1), jobId, SOURCE_GREENHOUSE, field.key())
                    .stream().findFirst().orElse(id);
            captured.add(new CapturedQuestion(storedId, field.key(), field.label(), required,
                    field.htmlType(), field.options() == null ? List.of() : field.options(),
                    SOURCE_GREENHOUSE, formUrl, "INSPECTED", java.time.Instant.now()));
            bindAnswers(profileId, jobId, storedId, field.label());
        }
        return captured;
    }

    /** Links the candidate's stored answers to the captured question they answer. */
    private void bindAnswers(UUID profileId, UUID jobId, UUID questionId, String questionText) {
        if (questionText == null || questionText.isBlank()) return;
        db.update("""
                update application_answers set form_question_id = ?
                where profile_id = ? and job_id = ? and form_question_id is null
                  and lower(trim(regexp_replace(question_text, '\\s+', ' ', 'g'))) = ?
                """, questionId, profileId, jobId, normalize(questionText));
    }

    public List<CapturedQuestion> questionsForJob(UUID jobId) {
        return db.query("""
                select id, question_key, question_text, required_state, answer_type, options, source, form_url,
                       capture_status, captured_at
                from job_form_questions where job_id = ? order by captured_at, question_key
                """, (rs, n) -> {
            List<String> options;
            try {
                options = json.readValue(rs.getString("options"), List.class);
            } catch (Exception e) {
                options = List.of();
            }
            return new CapturedQuestion((UUID) rs.getObject("id"), rs.getString("question_key"),
                    rs.getString("question_text"), RequiredState.valueOf(rs.getString("required_state")),
                    rs.getString("answer_type"), options, rs.getString("source"), rs.getString("form_url"),
                    rs.getString("capture_status"),
                    rs.getTimestamp("captured_at") == null ? null : rs.getTimestamp("captured_at").toInstant());
        }, jobId);
    }

    /**
     * The employer's cover-letter requirement, from the captured form only.
     * UNKNOWN when no form has been captured for this job — never invented.
     */
    public CoverLetterRequirement coverLetterRequirement(UUID jobId) {
        return questionsForJob(jobId).stream()
                .filter(q -> "cover_letter".equals(q.questionKey()))
                .map(q -> switch (q.requiredState()) {
                    case REQUIRED -> CoverLetterRequirement.REQUIRED;
                    case OPTIONAL -> CoverLetterRequirement.OPTIONAL;
                    case UNKNOWN -> CoverLetterRequirement.UNKNOWN;
                })
                .findFirst()
                .orElse(CoverLetterRequirement.UNKNOWN);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
