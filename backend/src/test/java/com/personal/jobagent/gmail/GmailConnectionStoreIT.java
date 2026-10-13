package com.personal.jobagent.gmail;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GmailConnectionStore against real PostgreSQL with the V039 schema: the sync
 * cursor, initial-list page token, pending history backlog and status are
 * persisted and read back exactly; failures leave them untouched; OAuth states
 * are single-use and owner-bound; and connecting a different mailbox never
 * reuses the previous mailbox's cursor or backlog.
 */
@Testcontainers
class GmailConnectionStoreIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static JdbcTemplate jdbc;
    private GmailConnectionStore store;
    private UUID profile;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("remove_seed_dev_account", "false"))
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
    }

    @BeforeEach
    void setUp() {
        store = new GmailConnectionStore(jdbc);
        profile = newProfile();
    }

    @Test
    void cursorPageTokenBacklogAndStatusRoundTripThroughV039() {
        store.upsertConnected(profile, "candidate@example.com", "cipher-1", GmailOAuthClient.SCOPE_READONLY);
        GmailConnectionStore.Connection fresh = store.find(profile).orElseThrow();
        assertThat(fresh.active()).isTrue();
        assertThat(fresh.historyId()).isNull();
        assertThat(store.initialPageToken(profile)).isNull();
        assertThat(store.pendingHistoryMessageIds(profile)).isEmpty();

        // Initial scan in progress: snapshot cursor and next page token persist together.
        store.updateInitialSyncState(profile, 500L, Instant.parse("2026-10-11T10:00:00Z"), "PARTIAL", null, "page-2");
        assertThat(store.find(profile).orElseThrow().historyId()).isEqualTo(500L);
        assertThat(store.initialPageToken(profile)).isEqualTo("page-2");
        assertThat(store.find(profile).orElseThrow().lastSyncStatus()).isEqualTo("PARTIAL");

        // A failure records the error but keeps cursor and page token for the retry.
        store.updateSyncState(profile, null, null, "FAILED", "Gmail API HTTP 500");
        GmailConnectionStore.Connection failed = store.find(profile).orElseThrow();
        assertThat(failed.historyId()).isEqualTo(500L);
        assertThat(failed.lastSyncStatus()).isEqualTo("FAILED");
        assertThat(store.initialPageToken(profile)).isEqualTo("page-2");

        // Last initial page: token cleared, cursor kept.
        store.updateInitialSyncState(profile, 500L, Instant.now(), "OK", null, null);
        assertThat(store.initialPageToken(profile)).isNull();

        // Oversized history record: cursor and tail persist atomically, in order.
        store.updateHistorySyncState(profile, 640L, Instant.now(), "PARTIAL", null, List.of("m-51", "m-52"));
        assertThat(store.find(profile).orElseThrow().historyId()).isEqualTo(640L);
        assertThat(store.pendingHistoryMessageIds(profile)).containsExactly("m-51", "m-52");
        store.updateSyncState(profile, null, null, "FAILED", "Gmail API HTTP 503");
        assertThat(store.pendingHistoryMessageIds(profile)).containsExactly("m-51", "m-52");
        store.updateHistorySyncState(profile, 640L, Instant.now(), "OK", null, List.of());
        assertThat(store.pendingHistoryMessageIds(profile)).isEmpty();

        store.markReauthRequired(profile, "Google refused the stored refresh token");
        GmailConnectionStore.Connection reauth = store.find(profile).orElseThrow();
        assertThat(reauth.active()).isFalse();
        assertThat(reauth.status()).isEqualTo("REAUTH_REQUIRED");
        assertThat(reauth.historyId()).isEqualTo(640L);
    }

    @Test
    void reconnectingTheSameMailboxKeepsItsCursorAndBacklog() {
        store.upsertConnected(profile, "candidate@example.com", "cipher-1", GmailOAuthClient.SCOPE_READONLY);
        store.updateHistorySyncState(profile, 700L, Instant.now(), "PARTIAL", null, List.of("m-1"));
        store.markReauthRequired(profile, "expired");

        store.upsertConnected(profile, "Candidate@Example.com", "cipher-2", GmailOAuthClient.SCOPE_READONLY);

        GmailConnectionStore.Connection again = store.find(profile).orElseThrow();
        assertThat(again.active()).isTrue();
        assertThat(again.encryptedRefreshToken()).isEqualTo("cipher-2");
        assertThat(again.historyId()).isEqualTo(700L);
        assertThat(store.pendingHistoryMessageIds(profile)).containsExactly("m-1");
    }

    @Test
    void connectingADifferentMailboxNeverReusesThePreviousMailboxsSyncState() {
        store.upsertConnected(profile, "first@example.com", "cipher-1", GmailOAuthClient.SCOPE_READONLY);
        store.updateInitialSyncState(profile, 800L, Instant.now(), "PARTIAL", null, "first-page-2");
        store.updateHistorySyncState(profile, 800L, Instant.now(), "PARTIAL", null, List.of("first-m-9"));
        jdbc.update("update gmail_connections set initial_page_token = 'first-page-2' where profile_id = ?", profile);

        store.upsertConnected(profile, "second@example.com", "cipher-2", GmailOAuthClient.SCOPE_READONLY);

        GmailConnectionStore.Connection second = store.find(profile).orElseThrow();
        assertThat(second.googleEmail()).isEqualTo("second@example.com");
        assertThat(second.active()).isTrue();
        // History IDs, page tokens and message IDs belong to one mailbox: the new
        // mailbox starts its own initial scan instead of inheriting them.
        assertThat(second.historyId()).isNull();
        assertThat(store.initialPageToken(profile)).isNull();
        assertThat(store.pendingHistoryMessageIds(profile)).isEmpty();
        assertThat(second.lastSyncAt()).isNull();
        assertThat(second.lastSyncStatus()).isNull();
    }

    @Test
    void oauthStatesAreSingleUseOwnerBoundAndExpire() {
        UUID other = newProfile();
        store.registerOAuthState(profile, "state-a");
        assertThat(store.consumeOAuthState(other, "state-a")).isFalse();
        assertThat(store.consumeOAuthState(profile, "state-a")).isTrue();
        assertThat(store.consumeOAuthState(profile, "state-a")).isFalse();

        store.registerOAuthState(profile, "state-b");
        jdbc.update("update gmail_oauth_states set expires_at = now() - interval '1 second' where profile_id = ?", profile);
        assertThat(store.consumeOAuthState(profile, "state-b")).isFalse();
        // Only a hash is stored, never the state itself.
        assertThat(jdbc.queryForObject("select count(*) from gmail_oauth_states where state_hash like 'state-%'",
                Integer.class)).isZero();
    }

    @Test
    void connectionsAreOwnerScopedAndPurgedWithTheProfile() {
        UUID other = newProfile();
        store.upsertConnected(profile, "candidate@example.com", "cipher-1", GmailOAuthClient.SCOPE_READONLY);
        assertThat(store.find(other)).isEmpty();

        jdbc.update("delete from profiles where id = ?", profile);
        assertThat(jdbc.queryForObject("select count(*) from gmail_connections where profile_id = ?", Integer.class,
                profile)).isZero();
    }

    private static UUID newProfile() {
        UUID user = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, 'Gmail IT', 'FIREBASE')",
                user, "gmail-it-" + user + "@example.test");
        jdbc.update("insert into profiles (id, user_id, work_eligibility, career_goals, links, master_revision, setup_status) "
                + "values (?, ?, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, 1, 'INCOMPLETE')", profileId, user);
        return profileId;
    }
}
