package com.personal.jobagent.track.receipt;

import com.personal.jobagent.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC repository for {@code candidate_sandbox_registrations} (V069).
 *
 * <p>Every read and mutation is owner-scoped by {@code profile_id}. Registrations are never
 * hard-deleted as part of revocation: revoking sets {@code revoked_at} and keeps full audit history.
 */
@Repository
public class SandboxRegistrationRepository {

    public record CandidateSandboxRegistration(
            UUID id,
            UUID profileId,
            String atsPlatform,
            String postingUrl,
            String permittedDomain,
            String approvedBy,
            Instant approvedAt,
            Instant revokedAt
    ) {
        public boolean isActive() {
            return revokedAt == null;
        }
    }

    private final JdbcTemplate db;

    public SandboxRegistrationRepository(JdbcTemplate db) {
        this.db = db;
    }

    public CandidateSandboxRegistration insert(
            UUID profileId,
            String atsPlatform,
            String canonicalPostingUrl,
            String permittedDomain,
            String approvedBy) {
        UUID id = UuidV7.generate();
        db.update("""
                insert into candidate_sandbox_registrations
                    (id, profile_id, ats_platform, posting_url, permitted_domain, approved_by)
                values (?, ?, ?, ?, ?, ?)
                """, id, profileId, atsPlatform, canonicalPostingUrl, permittedDomain, approvedBy);
        return findOwnedById(profileId, id)
                .orElseThrow(() -> new IllegalStateException("sandbox registration vanished after insert"));
    }

    public List<CandidateSandboxRegistration> listForProfile(UUID profileId) {
        if (profileId == null) {
            return List.of();
        }
        return db.query("""
                select id, profile_id, ats_platform, posting_url, permitted_domain, approved_by, approved_at, revoked_at
                from candidate_sandbox_registrations
                where profile_id = ?
                order by approved_at desc, id desc
                """, (rs, n) -> map(rs), profileId);
    }

    public Optional<CandidateSandboxRegistration> findOwnedById(UUID profileId, UUID id) {
        if (profileId == null || id == null) {
            return Optional.empty();
        }
        return db.query("""
                select id, profile_id, ats_platform, posting_url, permitted_domain, approved_by, approved_at, revoked_at
                from candidate_sandbox_registrations
                where id = ? and profile_id = ?
                """, (rs, n) -> map(rs), id, profileId).stream().findFirst();
    }

    public int markRevoked(UUID profileId, UUID id) {
        if (profileId == null || id == null) {
            return 0;
        }
        return db.update("""
                update candidate_sandbox_registrations
                set revoked_at = clock_timestamp()
                where id = ? and profile_id = ? and revoked_at is null
                """, id, profileId);
    }

    private static CandidateSandboxRegistration map(ResultSet rs) throws SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new CandidateSandboxRegistration(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("profile_id"),
                rs.getString("ats_platform"),
                rs.getString("posting_url"),
                rs.getString("permitted_domain"),
                rs.getString("approved_by"),
                rs.getTimestamp("approved_at").toInstant(),
                revoked == null ? null : revoked.toInstant()
        );
    }
}
