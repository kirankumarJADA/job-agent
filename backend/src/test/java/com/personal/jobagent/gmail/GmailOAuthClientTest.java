package com.personal.jobagent.gmail;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GmailOAuthClientTest {

    @Test
    void apiPathsAreResolvedFromTheV1RootOnlyOnce() {
        URI uri = GmailOAuthClient.apiUri("/users/me/profile");
        assertThat(uri.toString()).isEqualTo("https://gmail.googleapis.com/gmail/v1/users/me/profile");
    }

    @Test
    void apiPathCannotReplaceTheFixedHostOrAddFragments() {
        assertThatThrownBy(() -> GmailOAuthClient.apiUri("//attacker.example/path"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GmailOAuthClient.apiUri("/users/me/profile#fragment"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyReadOnlyScopeIsAllowedAndAnOmittedScopeUsesTheOnlyRequestedScope() {
        GmailOAuthClient.assertReadonlyScopes(GmailOAuthClient.SCOPE_READONLY);
        GmailOAuthClient.assertReadonlyScopes(null);
        GmailOAuthClient.assertReadonlyScopes("   ");
        assertThatThrownBy(() -> GmailOAuthClient.assertReadonlyScopes(
                GmailOAuthClient.SCOPE_READONLY + " https://www.googleapis.com/auth/gmail.send"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GmailOAuthClient.assertReadonlyScopes("https://www.googleapis.com/auth/gmail.send"))
                .isInstanceOf(IllegalStateException.class);
    }
}
