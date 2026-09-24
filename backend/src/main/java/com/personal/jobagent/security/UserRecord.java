package com.personal.jobagent.security;

import java.util.UUID;

/**
 * Plain projection of the users table (V001, extended by V020) — deliberately
 * not a JPA entity, consistent with P1-b's audit writer: JDBC directly, no ORM
 * mapping question to resolve for a small table with no jsonb/array columns to
 * begin with.
 *
 * @param id           local application user id — the value every owned-data
 *                     table keys off, and the only id handed to other modules
 * @param email        account email ({@code citext}, so matching is
 *                     case-insensitive in the database)
 * @param passwordHash Argon2id hash of a locally-managed password, or
 *                     {@code null} for accounts whose credential lives in
 *                     Firebase. Never a plaintext password.
 * @param displayName  human-readable name shown in the UI
 * @param firebaseUid  the Firebase identity this row is linked to, or
 *                     {@code null} for pre-Firebase/local-only accounts
 * @param authProvider {@code LOCAL} when this app owns the credential,
 *                     {@code FIREBASE} when it was created through Firebase
 */
public record UserRecord(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        String firebaseUid,
        String authProvider
) {

    /** True when this account authenticates through Firebase rather than a local password. */
    public boolean isFirebaseAccount() {
        return "FIREBASE".equals(authProvider);
    }

    /** True when a local password exists and can be compared. */
    public boolean hasLocalPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}
