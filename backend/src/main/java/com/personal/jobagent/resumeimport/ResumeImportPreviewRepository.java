package com.personal.jobagent.resumeimport;

import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Backs the preview token that proves a "confirm" call follows a "preview"
 * call the same user just made. Stores nothing about the resume or the
 * extracted data — see V053's migration comment.
 */
@Repository
public class ResumeImportPreviewRepository {

    private final JdbcTemplate jdbcTemplate;

    public ResumeImportPreviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Created(UUID id, Instant expiresAt) {
    }

    public record Claimed(UUID id, UUID profileId) {
    }

    /**
     * Issues a new token. The caller's own spent (expired or consumed) tokens are
     * removed first: a deleted token can never be claimed, so this keeps the
     * table bounded without weakening the single-use guarantee.
     */
    public Created create(UUID userId, UUID profileId) {
        jdbcTemplate.update("""
                delete from resume_import_previews
                where user_id = ? and (consumed_at is not null or expires_at <= now())
                """, userId);
        UUID id = UuidV7.generate();
        Instant expiresAt = jdbcTemplate.queryForObject("""
                        insert into resume_import_previews (id, user_id, profile_id, expires_at)
                        values (?, ?, ?, now() + (? * interval '1 second'))
                        returning expires_at
                        """,
                (rs, n) -> rs.getTimestamp("expires_at").toInstant(),
                // float8 binds straight to PostgreSQL's float8 * interval operator.
                id, userId, profileId, (double) ResumeImportLimits.PREVIEW_TTL.toSeconds());
        return new Created(id, expiresAt);
    }

    /**
     * Atomically marks a live token belonging to this user as used and returns
     * it, or returns empty when the token is unknown, someone else's, expired or
     * already used. Run inside the confirm transaction: a concurrent second claim
     * blocks on the row lock and then sees it consumed, and a rollback of the
     * import un-claims it.
     */
    public Optional<Claimed> claim(UUID previewToken, UUID userId) {
        return jdbcTemplate.query("""
                        update resume_import_previews set consumed_at = now()
                        where id = ? and user_id = ? and consumed_at is null and expires_at > now()
                        returning id, profile_id
                        """,
                        (rs, n) -> new Claimed((UUID) rs.getObject("id"), (UUID) rs.getObject("profile_id")),
                        previewToken, userId)
                .stream().findFirst();
    }
}
