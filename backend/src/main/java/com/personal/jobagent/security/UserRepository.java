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

    private static final RowMapper<UserRecord> ROW_MAPPER = (rs, rowNum) -> new UserRecord(
            (UUID) rs.getObject("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name")
    );

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserRecord> findByEmail(String email) {
        // citext column: Postgres itself does the case-insensitive compare,
        // no need for lower()/upper() gymnastics here.
        List<UserRecord> results = jdbcTemplate.query(
                "select id, email, password_hash, display_name from users where email = ?",
                ROW_MAPPER, email);
        return results.stream().findFirst();
    }

    public Optional<UserRecord> findById(UUID id) {
        List<UserRecord> results = jdbcTemplate.query(
                "select id, email, password_hash, display_name from users where id = ?",
                ROW_MAPPER, id);
        return results.stream().findFirst();
    }
}
