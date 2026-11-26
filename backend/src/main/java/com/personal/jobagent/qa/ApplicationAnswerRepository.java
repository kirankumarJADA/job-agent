package com.personal.jobagent.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApplicationAnswerRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ApplicationAnswerRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private RowMapper<ApplicationAnswerRecord> rowMapper() {
        return (rs, rowNum) -> new ApplicationAnswerRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("profile_id"),
                (UUID) rs.getObject("job_id"),
                (UUID) rs.getObject("application_id"),
                rs.getString("question_text"),
                rs.getString("question_type"),
                rs.getString("answer_text"),
                rs.getBigDecimal("confidence"),
                rs.getString("status"),
                JdbcConversions.readJsonMap(rs, "validation_notes", objectMapper),
                rs.getBoolean("human_confirmed"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null,
                (UUID) rs.getObject("form_question_id"),
                rs.getString("answer_origin")
        );
    }

    /** True when the application is the caller's own application for this job (or none was given). */
    public boolean applicationBelongs(UUID profileId, UUID jobId, UUID applicationId) {
        if (applicationId == null) return true;
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from applications where id = ? and profile_id = ? and job_id = ?",
                Integer.class, applicationId, profileId, jobId);
        return n != null && n > 0;
    }

    public Optional<ApplicationAnswerRecord> findById(UUID id) {
        return jdbcTemplate.query("select * from application_answers where id = ?", rowMapper(), id)
                .stream().findFirst();
    }

    /**
     * Ownership-scoped read. Answers are keyed by {@code profile_id}, so this is
     * what prevents one account reading another's drafted answers. A foreign id
     * does not match and the caller sees a plain 404.
     */
    public Optional<ApplicationAnswerRecord> findByIdForProfile(UUID id, UUID profileId) {
        return jdbcTemplate.query(
                        "select * from application_answers where id = ? and profile_id = ?",
                        rowMapper(), id, profileId)
                .stream().findFirst();
    }

    public List<ApplicationAnswerRecord> findByJobId(UUID jobId) {
        return jdbcTemplate.query("select * from application_answers where job_id = ? order by created_at desc", rowMapper(), jobId);
    }

    /** Ownership-scoped variant of {@link #findByJobId(UUID)}. */
    public List<ApplicationAnswerRecord> findByJobIdForProfile(UUID jobId, UUID profileId) {
        return jdbcTemplate.query(
                "select * from application_answers where job_id = ? and profile_id = ? order by created_at desc",
                rowMapper(), jobId, profileId);
    }

    /**
     * Scoped answers visible for one application: application-specific rows come first,
     * followed by job-level answers ({@code application_id is null}).
     */
    public List<ApplicationAnswerRecord> findByJobAndProfileScoped(UUID profileId, UUID jobId, UUID applicationId) {
        return jdbcTemplate.query("""
                select * from application_answers
                where profile_id = ? and job_id = ? and (application_id = ? or application_id is null)
                order by case when application_id = ? then 0 else 1 end, created_at desc, id
                """, rowMapper(), profileId, jobId, applicationId, applicationId);
    }

    /**
     * Previously confirmed, candidate-validated answers across jobs that were marked reusable
     * in {@code validation_notes->>'reusable' = 'true'}. Excludes essay/company-specific types.
     */
    public List<ApplicationAnswerRecord> findReusableConfirmedAnswers(UUID profileId) {
        return jdbcTemplate.query("""
                select * from application_answers
                where profile_id = ?
                  and status = 'ANSWERED'
                  and human_confirmed = true
                  and answer_origin in ('CANDIDATE_CONFIRMED', 'CANDIDATE_EDITED')
                  and coalesce(validation_notes->>'reusable', 'false') = 'true'
                  and question_type not in ('WHY_COMPANY', 'WHY_ROLE')
                order by updated_at desc, id
                """, rowMapper(), profileId);
    }

    /**
     * Idempotently persists a discovered mandatory form question into {@code application_answers}
     * with {@code NEEDS_USER_INPUT} unless a candidate-confirmed answer already exists for that
     * question on this application/job. Preserves V036's {@code answer_origin} check constraint.
     */
    public UUID upsertUniversalDiscoveredQuestion(UUID profileId,
                                                  UUID jobId,
                                                  UUID applicationId,
                                                  String questionKey,
                                                  String questionText,
                                                  String htmlType,
                                                  Map<String, Object> validationNotes) {
        String normalizedText = questionText == null ? "" : questionText.trim().toLowerCase().replaceAll("\\s+", " ");
        List<ApplicationAnswerRecord> existing = findByJobAndProfileScoped(profileId, jobId, applicationId);
        for (ApplicationAnswerRecord row : existing) {
            String rowNormalized = row.questionText() == null ? ""
                    : row.questionText().trim().toLowerCase().replaceAll("\\s+", " ");
            Object existingKey = row.validationNotes() == null ? null : row.validationNotes().get("questionKey");
            boolean sameQuestion = (!normalizedText.isEmpty() && normalizedText.equals(rowNormalized))
                    || (questionKey != null && !questionKey.isBlank() && questionKey.equals(String.valueOf(existingKey)));
            if (sameQuestion) {
                return row.id();
            }
        }
        String questionType = "select".equalsIgnoreCase(htmlType) || "radio".equalsIgnoreCase(htmlType)
                || "checkbox".equalsIgnoreCase(htmlType) ? "STRUCTURED_FIELD" : "UNIVERSAL_DISCOVERED";
        return insert(profileId, jobId, applicationId, questionText, questionType, "",
                new BigDecimal("0.00"), "NEEDS_USER_INPUT", validationNotes);
    }

    public UUID insert(UUID profileId, UUID jobId, UUID applicationId,
                       String questionText, String questionType, String answerText,
                       BigDecimal confidence, String status, Map<String, Object> validationNotes) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                insert into application_answers (id, profile_id, job_id, application_id, question_text, question_type, answer_text, confidence, status, validation_notes, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), now())
                """,
                id, profileId, jobId, applicationId, questionText, questionType, answerText,
                confidence, status, JdbcConversions.toJson(validationNotes, objectMapper));
        return id;
    }

    public void updateStatus(UUID id, String status, String updatedAnswer) {
        if (updatedAnswer != null) {
            jdbcTemplate.update("update application_answers set status = ?, answer_text = ?, updated_at = now() where id = ?", status, updatedAnswer, id);
        } else {
            jdbcTemplate.update("update application_answers set status = ?, updated_at = now() where id = ?", status, id);
        }
    }

    /**
     * Ownership-scoped update. The {@code profile_id} predicate is the
     * authorization check: another account's answer is never written, and the
     * caller gets the same 404 as for an unknown id.
     *
     * @return true when a row the caller owns was updated
     */
    public boolean updateStatusForProfile(UUID id, String status, String updatedAnswer,
                                           boolean humanConfirmed, UUID profileId) {
        // Provenance (Phase 8.2): an explicit candidate confirmation is never
        // recorded as a model draft, and a candidate-edited answer says so.
        String origin = updatedAnswer != null ? "CANDIDATE_EDITED" : "CANDIDATE_CONFIRMED";
        if (updatedAnswer != null) {
            return jdbcTemplate.update(
                    "update application_answers set status = ?, answer_text = ?, human_confirmed = ?, "
                            + "answer_origin = case when ? then ? else answer_origin end, updated_at = now() "
                            + "where id = ? and profile_id = ?",
                    status, updatedAnswer, humanConfirmed, humanConfirmed, origin, id, profileId) > 0;
        }
        return jdbcTemplate.update(
                "update application_answers set status = ?, human_confirmed = ?, "
                        + "answer_origin = case when ? then ? else answer_origin end, updated_at = now() "
                        + "where id = ? and profile_id = ?",
                status, humanConfirmed, humanConfirmed, origin, id, profileId) > 0;
    }

}