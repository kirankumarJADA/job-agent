package com.personal.jobagent.gmail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-scoped persistence for Gmail OAuth connections. One connection per
 * profile; the refresh token is stored encrypted by the caller.
 */
@Component
public class GmailConnectionStore {

    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public GmailConnectionStore(JdbcTemplate db) {
        this.db = db;
    }

    public record Connection(UUID id, UUID profileId, String googleEmail, String encryptedRefreshToken,
                             String scopes, String status, Long historyId,
                             java.time.Instant lastSyncAt, String lastSyncStatus, String lastSyncError) {

        public boolean active() {
            return "CONNECTED".equals(status);
        }
    }

    /** Store only a hash of each OAuth state, making each consent round-trip single-use. */
    public void registerOAuthState(UUID profileId, String state) {
        db.update("delete from gmail_oauth_states where expires_at < now()");
        db.update("""
                insert into gmail_oauth_states(state_hash, profile_id, expires_at)
                values (?, ?, now() + interval '10 minutes')
                """, stateHash(state), profileId);
    }

    /** Atomically consumes an unexpired, owner-bound OAuth state exactly once. */
    public boolean consumeOAuthState(UUID profileId, String state) {
        if (state == null || state.isBlank()) return false;
        int deleted = db.update("""
                delete from gmail_oauth_states
                where state_hash = ? and profile_id = ? and expires_at > now()
                """, stateHash(state), profileId);
        return deleted == 1;
    }

    private static String stateHash(String state) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash OAuth state", e);
        }
    }

    public void upsertConnected(UUID profileId, String googleEmail, String encryptedRefreshToken,
                                String scopes) {
        db.update("""
                insert into gmail_connections (id, profile_id, google_email, encrypted_refresh_token,
                                               scopes, status, connected_at, updated_at)
                values (?, ?, ?, ?, ?, 'CONNECTED', now(), now())
                on conflict (profile_id) do update set
                    google_email = excluded.google_email,
                    encrypted_refresh_token = excluded.encrypted_refresh_token,
                    scopes = excluded.scopes,
                    status = 'CONNECTED',
                    last_sync_error = null,
                    updated_at = now()
                """, UUID.randomUUID(), profileId, googleEmail, encryptedRefreshToken, scopes);
    }

    public Optional<Connection> find(UUID profileId) {
        List<Map<String, Object>> rows = db.queryForList("""
                select id, profile_id, google_email, encrypted_refresh_token, scopes, status,
                       history_id, last_sync_at, last_sync_status, last_sync_error
                from gmail_connections where profile_id = ?
                """, profileId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
    }

    public String initialPageToken(UUID profileId) {
        List<String> rows = db.query("""
                select initial_page_token from gmail_connections where profile_id = ?
                """, (rs, n) -> rs.getString(1), profileId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Pending message IDs left by a history record larger than one sync batch. */
    public List<String> pendingHistoryMessageIds(UUID profileId) {
        List<String> rows = db.query("""
                select history_pending_ids::text from gmail_connections where profile_id = ?
                """, (rs, n) -> rs.getString(1), profileId);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) return List.of();
        try {
            return json.readValue(rows.get(0), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Stored Gmail history backlog is invalid", e);
        }
    }

    /**
     * Atomically advances the cursor snapshot and initial-list page token.
     * Initial scanning is only entered when the incremental-history backlog is empty.
     */
    public void updateInitialSyncState(UUID profileId, Long historyId, java.time.Instant lastSyncAt,
                                       String status, String error, String nextPageToken) {
        db.update("""
                update gmail_connections
                set history_id = coalesce(?, history_id),
                    initial_page_token = ?,
                    history_pending_ids = '[]'::jsonb,
                    last_sync_at = coalesce(?, last_sync_at),
                    last_sync_status = ?,
                    last_sync_error = ?,
                    updated_at = now()
                where profile_id = ?
                """, historyId, nextPageToken, lastSyncAt == null ? null : Timestamp.from(lastSyncAt),
                status, error, profileId);
    }

    /** Cursor and the remaining IDs from one oversized history record are saved atomically. */
    public void updateHistorySyncState(UUID profileId, Long historyId, java.time.Instant lastSyncAt,
                                       String status, String error, List<String> pendingIds) {
        try {
            String pendingJson = json.writeValueAsString(pendingIds == null ? List.of() : pendingIds);
            db.update("""
                    update gmail_connections
                    set history_id = coalesce(?, history_id),
                        history_pending_ids = ?::jsonb,
                        last_sync_at = coalesce(?, last_sync_at),
                        last_sync_status = ?,
                        last_sync_error = ?,
                        updated_at = now()
                    where profile_id = ?
                    """, historyId, pendingJson,
                    lastSyncAt == null ? null : Timestamp.from(lastSyncAt), status, error, profileId);
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist Gmail history cursor", e);
        }
    }

    public void updateSyncState(UUID profileId, Long historyId, java.time.Instant lastSyncAt,
                                String status, String error) {
        db.update("""
                update gmail_connections
                set history_id = coalesce(?, history_id),
                    last_sync_at = coalesce(?, last_sync_at),
                    last_sync_status = ?,
                    last_sync_error = ?,
                    updated_at = now()
                where profile_id = ?
                """, historyId, lastSyncAt == null ? null : Timestamp.from(lastSyncAt), status, error, profileId);
    }

    public void markReauthRequired(UUID profileId, String error) {
        db.update("""
                update gmail_connections
                set status = 'REAUTH_REQUIRED', last_sync_status = 'FAILED', last_sync_error = ?,
                    updated_at = now()
                where profile_id = ?
                """, error, profileId);
    }

    public void delete(UUID profileId) {
        db.update("delete from gmail_connections where profile_id = ?", profileId);
    }

    private Connection map(Map<String, Object> r) {
        return new Connection(
                (UUID) r.get("id"), (UUID) r.get("profile_id"),
                (String) r.get("google_email"), (String) r.get("encrypted_refresh_token"),
                (String) r.get("scopes"), (String) r.get("status"),
                r.get("history_id") == null ? null : ((Number) r.get("history_id")).longValue(),
                r.get("last_sync_at") == null ? null : ((Timestamp) r.get("last_sync_at")).toInstant(),
                (String) r.get("last_sync_status"), (String) r.get("last_sync_error"));
    }
}
