package com.personal.jobagent.gmail;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailConnectionStoreOAuthStateTest {

    @Test
    void storesOnlyStateHashAndConsumesItOnceForTheOwner() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        GmailConnectionStore store = new GmailConnectionStore(db);
        UUID profileId = UUID.randomUUID();
        String state = "opaque-state-secret";
        String expectedHash = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));

        store.registerOAuthState(profileId, state);

        verify(db).update("delete from gmail_oauth_states where expires_at < now()");
        verify(db).update(contains("insert into gmail_oauth_states"), eq(expectedHash), eq(profileId));
        when(db.update(contains("where state_hash = ?"), eq(expectedHash), eq(profileId))).thenReturn(1, 0);

        assertThat(store.consumeOAuthState(profileId, state)).isTrue();
        assertThat(store.consumeOAuthState(profileId, state)).isFalse();
        verify(db, org.mockito.Mockito.times(2)).update(contains("where state_hash = ?"), eq(expectedHash), eq(profileId));
    }

    @Test
    void aDifferentOwnerCannotConsumeTheStoredState() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        GmailConnectionStore store = new GmailConnectionStore(db);
        UUID anotherOwner = UUID.randomUUID();
        String state = "opaque-state-secret";
        String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
        when(db.update(contains("where state_hash = ?"), eq(hash), eq(anotherOwner))).thenReturn(0);

        assertThat(store.consumeOAuthState(anotherOwner, state)).isFalse();
        verify(db).update(contains("where state_hash = ?"), eq(hash), eq(anotherOwner));
    }
}
