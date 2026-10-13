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
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null,
                rs.getString("content_sha256"),
                (UUID) rs.getObject("parent_version_id"),
                rs.getString("origin"),
                rs.getObject("pdf_file_id") != null
        );
    }

    /**
     * True when {@code applicationId} is the caller's own application for this
     * job. Request bodies carry the application id, so this is the check that
     * stops one account attaching a letter to another account's application,
     * or to an application for a different job.
     */
    public boolean applicationBelongs(UUID profileId, UUID jobId, UUID applicationId) {
        if (applicationId == null) return true;
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from applications where id = ? and profile_id = ? and job_id = ?",
                Integer.class, applicationId, profileId, jobId);
        return n != null && n > 0;
    }

    /** A user correction: a NEW version that records which version it corrects. */
    public UUID insertCorrection(UUID profileId, UUID jobId, UUID applicationId, int version, String title,
                                 String bodyMarkdown, String contentSha256, Map<String, Object> claimsValidation,
                                 UUID parentVersionId) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                insert into cover_letters (id, profile_id, job_id, application_id, version, title, body_markdown,
                                           content_sha256, claims_validation, is_approved, parent_version_id, origin,
                                           created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, false, ?, 'USER_CORRECTED', now(), now())
                """,
                id, profileId, jobId, applicationId, version, title, bodyMarkdown, contentSha256,
                JdbcConversions.toJson(claimsValidation, objectMapper), parentVersionId);
        return id;
    }

    /** Replaces only the stored validation metadata (never the letter body). */
    public void updateValidationForProfile(UUID id, UUID profileId, Map<String, Object> claimsValidation) {
        jdbcTemplate.update("update cover_letters set claims_validation = ?::jsonb, updated_at = now() where id = ? and profile_id = ?",
                JdbcConversions.toJson(claimsValidation, objectMapper), id, profileId);
    }

    /**
     * Stores the letter's PDF once. The bytes go to {@code files} with their
     * digest; the letter row is linked only if it has no PDF yet, so an
     * existing artifact is never replaced.
     */
    @org.springframework.transaction.annotation.Transactional
    public void attachPdf(UUID id, UUID profileId, byte[] pdf, String sha256) {
        if (pdf == null || pdf.length == 0) return;
        Integer unlinked = jdbcTemplate.queryForObject(
                "select count(*) from cover_letters where id = ? and profile_id = ? and pdf_file_id is null",
                Integer.class, id, profileId);
        if (unlinked == null || unlinked == 0) return; // already has a PDF, or not the caller's letter
        UUID fileId = UuidV7.generate();
        jdbcTemplate.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) "
                        + "values (?, ?, ?, 'application/pdf', ?, 'COVER_LETTER', ?)",
                fileId, "cover-letter/" + id + "/" + fileId + ".pdf", sha256, (long) pdf.length, pdf);
        jdbcTemplate.update("update cover_letters set pdf_file_id = ? where id = ? and profile_id = ? and pdf_file_id is null",
                fileId, id, profileId);
    }

    public record StoredPdf(byte[] bytes, String recordedSha256, String actualSha256) {
        public boolean intact() { return bytes != null && actualSha256 != null && actualSha256.equals(recordedSha256); }
    }

    public Optional<StoredPdf> pdfForProfile(UUID id, UUID profileId) {
        return jdbcTemplate.query("""
                select f.content, f.sha256 from cover_letters c join files f on f.id = c.pdf_file_id
                where c.id = ? and c.profile_id = ?
                """, (rs, n) -> {
            byte[] content = rs.getBytes("content");
            return new StoredPdf(content, rs.getString("sha256"), content == null ? null : sha256Hex(content));
        }, id, profileId).stream().findFirst();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
                       String title, String bodyMarkdown, String contentSha256,
                       Map<String, Object> claimsValidation, boolean isApproved) {
        UUID id = UuidV7.generate();
        jdbcTemplate.update("""
                insert into cover_letters (id, profile_id, job_id, application_id, version, title, body_markdown,
                                           content_sha256, claims_validation, is_approved, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now(), now())
                """,
                id, profileId, jobId, applicationId, version, title, bodyMarkdown, contentSha256,
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
