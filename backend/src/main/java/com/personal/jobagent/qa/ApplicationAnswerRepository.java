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
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        );
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
    public boolean updateStatusForProfile(UUID id, String status, String updatedAnswer, UUID profileId) {
        if (updatedAnswer != null) {
            return jdbcTemplate.update(
                    "update application_answers set status = ?, answer_text = ?, updated_at = now() "
                            + "where id = ? and profile_id = ?",
                    status, updatedAnswer, id, profileId) > 0;
        }
        return jdbcTemplate.update(
                "update application_answers set status = ?, updated_at = now() where id = ? and profile_id = ?",
                status, id, profileId) > 0;
    }
}