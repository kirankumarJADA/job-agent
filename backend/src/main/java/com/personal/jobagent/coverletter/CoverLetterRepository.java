package com.personal.jobagent.coverletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.JdbcConversions;
import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CoverLetterRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CoverLetterRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private RowMapper<CoverLetterRecord> rowMapper() {
        return (rs, rowNum) -> new CoverLetterRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("profile_id"),
                (UUID) rs.getObject("job_id"),
                (UUID) rs.getObject("application_id"),
                rs.getInt("version"),
                rs.getString("title"),
                rs.getString("body_markdown"),
                JdbcConversions.readJsonMap(rs, "claims_validation", objectMapper),
                rs.getBoolean("is_approved"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        );
    }

    public Optional<CoverLetterRecord> findById(UUID id) {
        return jdbcTemplate.query("select * from cover_letters where id = ?", rowMapper(), id)
                .stream().findFirst();
    }

    /**
     * Ownership-scoped read. Cover letters hang off {@code profiles.profile_id},
     * so filtering by the caller's profile id is what stops one account reading
     * another's letter (which contains tailored resume content).
     *
     * <p>An id belonging to someone else simply does not match, so the caller
     * sees a plain 404 — the same response as a non-existent id, which avoids
     * confirming that the id exists at all.
     */
    public Optional<CoverLetterRecord> findByIdForProfile(UUID id, UUID profileId) {
        return jdbcTemplate.query(
                        "select * from cover_letters where id = ? and profile_id = ?", rowMapper(), id, profileId)
                .stream().findFirst();
    }

    public List<CoverLetterRecord> findByJobId(UUID jobId) {
        return jdbcTemplate.query("select * from cover_letters where job_id = ? order by version desc", rowMapper(), jobId);
    }

    /** Ownership-scoped variant of {@link #findByJobId(UUID)}. */
    public List<CoverLetterRecord> findByJobIdForProfile(UUID jobId, UUID profileId) {
        return jdbcTemplate.query(
                "select * from cover_letters where job_id = ? and profile_id = ? order by version desc",
                rowMapper(), jobId, profileId);
    }

    public Optional<CoverLetterRecord> findLatestByJobIdForProfile(UUID jobId, UUID profileId) {
        return jdbcTemplate.query(
                        "select * from cover_letters where job_id = ? and profile_id = ? order by version desc limit 1",
                        rowMapper(), jobId, profileId)
                .stream().findFirst();
    }

    /**
     * Next version number for this profile's letters on this job.
     *
     * <p>Scoped by profile because {@code cover_letters} carries
     * {@code unique(job_id, version)}: a global counter would hand a second
     * account a version number already taken by the first, turning generation
     * into a constraint violation.
     */
    public int getNextVersion(UUID jobId, UUID profileId) {
        Integer max = jdbcTemplate.queryForObject(
                "select coalesce(max(version), 0) from cover_letters where job_id = ? and profile_id = ?",
                Integer.class, jobId, profileId);
        return (max != null ? max : 0) + 1;
    }

    public UUID insert(UUID profileId, UUID jobId, UUID applicationId, int version,
                       String title, String bodyMarkdown, Map<String, Object> claimsValidation, boolean isApproved) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                insert into cover_letters (id, profile_id, job_id, application_id, version, title, body_markdown, claims_validation, is_approved, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now(), now())
                """,
                id, profileId, jobId, applicationId, version, title, bodyMarkdown,
                JdbcConversions.toJson(claimsValidation, objectMapper), isApproved);
        return id;
    }

    public void setApproved(UUID id, boolean approved) {
        jdbcTemplate.update("update cover_letters set is_approved = ?, updated_at = now() where id = ?", approved, id);
    }

    /**
     * Ownership-scoped approval update. The {@code profile_id} predicate is the
     * authorization check: a letter owned by another account is not updated,
     * and the caller gets the same 404 as for an unknown id.
     *
     * @return true when a row the caller owns was updated
     */
    public boolean setApprovedForProfile(UUID id, boolean approved, UUID profileId) {
        return jdbcTemplate.update(
                "update cover_letters set is_approved = ?, updated_at = now() where id = ? and profile_id = ?",
                approved, id, profileId) > 0;
    }
}
