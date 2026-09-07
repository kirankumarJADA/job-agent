package com.personal.jobagent.security;

import java.util.UUID;

/**
 * Plain projection of the users table (V001) — deliberately not a JPA
 * entity, consistent with P1-b's audit writer: JDBC directly, no ORM
 * mapping question to resolve for a 4-column table with no jsonb/array
 * columns to begin with.
 */
public record UserRecord(
        UUID id,
        String email,
        String passwordHash,
        String displayName
) {
}
