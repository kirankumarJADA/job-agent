package com.personal.jobagent.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC, matching the persistence approach established in P1-b's
 * AuditLogWriter (see that class's javadoc for the reasoning — applies
 * equally here, though this table has no jsonb/array columns at all).
 */
@Repository
public class UserRepository {

    private static final String COLUMNS =
            "id, email, password_hash, display_name, firebase_uid, auth_provider";

    private static final RowMapper<UserRecord> ROW_MAPPER = (rs, rowNum) -> new UserRecord(
            (UUID) rs.getObject("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("firebase_uid"),
            rs.getString("auth_provider")
    );

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserRecord> findByEmail(String email) {
        // citext column: Postgres itself does the case-insensitive compare,
        // no need for lower()/upper() gymnastics here.
        List<UserRecord> results = jdbcTemplate.query(
                "select " + COLUMNS + " from users where email = ?",
                ROW_MAPPER, email);
        return results.stream().findFirst();
    }

    public Optional<UserRecord> findById(UUID id) {
        List<UserRecord> results = jdbcTemplate.query(
                "select " + COLUMNS + " from users where id = ?",
                ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    /**
     * Looks a local account up by its verified Firebase UID.
     *
     * <p>This is the primary lookup for a Firebase sign-in: the UID comes from
     * a signed token, so it — never a request-body value — is what authorises
     * the caller.
     */
    public Optional<UserRecord> findByFirebaseUid(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return Optional.empty();
        }
        List<UserRecord> results = jdbcTemplate.query(
                "select " + COLUMNS + " from users where firebase_uid = ?",
                ROW_MAPPER, firebaseUid);
        return results.stream().findFirst();
    }

    /**
     * Links an existing local account to a Firebase identity.
     *
     * <p>Used when someone signs in with Firebase using the email of an account
     * that already existed (for example the seeded local account). The account's
     * id, and therefore all of its profile data, is preserved. The existing
     * {@code password_hash} is deliberately left in place so the legacy
     * {@code POST /auth/login} path keeps working for that account.
     *
     * @return true when a row was updated
     */
    public boolean linkFirebaseUid(UUID userId, String firebaseUid, String displayName) {
        return jdbcTemplate.update(
                "update users set firebase_uid = ?, "
                        + "display_name = coalesce(nullif(?, ''), display_name), "
                        + "updated_at = now() "
                        + "where id = ? and (firebase_uid is null or firebase_uid = ?)",
                firebaseUid, displayName, userId, firebaseUid) > 0;
    }

    /**
     * Creates a brand-new Firebase-backed account. {@code password_hash} is
     * explicitly NULL: Firebase owns the credential, and a NULL hash can never
     * be mistaken for a matching password by the encoder.
     *
     * @return the id of the newly inserted row
     */
    public UUID insertFirebaseUser(UUID id, String email, String displayName, String firebaseUid) {
        jdbcTemplate.update(
                "insert into users (id, email, password_hash, display_name, firebase_uid, auth_provider) "
                        + "values (?, ?, null, ?, ?, 'FIREBASE')",
                id, email, displayName, firebaseUid);
        return id;
    }

    /** Bumps {@code updated_at} on a successful Firebase sign-in. Best-effort. */
    public void touchLastLogin(UUID userId) {
        jdbcTemplate.update("update users set updated_at = now() where id = ?", userId);
    }
}
