package com.personal.jobagent.identity;

import com.personal.jobagent.security.SecretCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Employer-portal credential storage (Robin application-execution hardening).
 * {@link SecretCipher}'s own encryption guarantees (IV freshness, fail-closed,
 * tamper detection) are covered separately in {@code SecretCipherTest}; this
 * class covers what {@link EmployerCredentialService} adds on top of that
 * primitive — that a stored value is never the plaintext secret, that a
 * lookup is scoped to its own caller, and that a round trip recovers the
 * original value.
 *
 * <p>This class was previously unreferenced by any test or caller in the
 * codebase. It remains deliberately unwired to any controller or worker
 * endpoint in this change — no production credential flow exists yet to
 * attach it to, and inventing one is out of scope here (see
 * {@code ROBIN_APPLICATION_EXECUTION_IMPLEMENTATION_PLAN.md}, Section 4.5).
 * These tests establish that the encryption/scoping contract the service
 * already promises in its own javadoc actually holds, so whenever a real
 * caller is added, it is adding it to a verified foundation.
 */
class EmployerCredentialServiceTest {

    private final SecretCipher cipher = new SecretCipher("unit-test-key-not-a-real-secret-0123456789");

    @Test
    void savePersistsOnlyEncryptedValuesNeverThePlaintextSecret() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        EmployerCredentialService service = new EmployerCredentialService(db, cipher);
        String plainUsername = "candidate@example.com";
        String plainPassword = "hunter2-employer-portal-password";

        service.save(UUID.randomUUID(), "Example.com", plainUsername, plainPassword);

        ArgumentCaptor<Object> usernameParam = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> passwordParam = ArgumentCaptor.forClass(Object.class);
        verify(db).update(contains("insert into employer_credentials"),
                any(), any(), any(), usernameParam.capture(), passwordParam.capture());

        String storedUsername = String.valueOf(usernameParam.getValue());
        String storedPassword = String.valueOf(passwordParam.getValue());
        assertThat(storedUsername).doesNotContain(plainUsername);
        assertThat(storedPassword).doesNotContain(plainPassword);
        // The stored value is a genuine, independently-decryptable ciphertext
        // of the secret — not merely "different-looking" text.
        assertThat(cipher.decrypt(storedUsername)).isEqualTo(plainUsername);
        assertThat(cipher.decrypt(storedPassword)).isEqualTo(plainPassword);
    }

    @Test
    void saveLowercasesTheEmployerDomainForConsistentLookup() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        EmployerCredentialService service = new EmployerCredentialService(db, cipher);

        service.save(UUID.randomUUID(), "Example.COM", "u", "p");

        verify(db).update(anyString(), any(), any(), eq("example.com"), any(), any());
    }

    @Test
    void returnedStoredCredentialCarriesThePlaintextForImmediateUseOnly() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        EmployerCredentialService service = new EmployerCredentialService(db, cipher);

        EmployerCredentialService.StoredCredential stored =
                service.save(UUID.randomUUID(), "Example.com", "u", "p");

        assertThat(stored.username()).isEqualTo("u");
        assertThat(stored.password()).isEqualTo("p");
        assertThat(stored.employerDomain()).isEqualTo("example.com");
    }

    @Test
    void findDecryptsBackToTheOriginalPlaintext() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        EmployerCredentialService service = new EmployerCredentialService(db, cipher);
        UUID profileId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        String encryptedUsername = cipher.encrypt("candidate@example.com");
        String encryptedPassword = cipher.encrypt("hunter2-employer-portal-password");
        when(db.queryForList(contains("employer_domain = ?"), eq(profileId), eq("example.com")))
                .thenReturn(List.of(Map.of(
                        "id", rowId,
                        "username_encrypted", encryptedUsername,
                        "password_encrypted", encryptedPassword)));

        var found = service.find(profileId, "Example.com");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(rowId);
        assertThat(found.get().username()).isEqualTo("candidate@example.com");
        assertThat(found.get().password()).isEqualTo("hunter2-employer-portal-password");
    }

    @Test
    void findIsScopedToTheCallersOwnProfile() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        EmployerCredentialService service = new EmployerCredentialService(db, cipher);
        UUID profileId = UUID.randomUUID();
        when(db.queryForList(anyString(), any(UUID.class), any(String.class))).thenReturn(List.of());

        service.find(profileId, "example.com");

        // The read is a profile-scoped query: one candidate's stored
        // credential can never be returned for another's lookup.
        verify(db).queryForList(contains("profile_id = ?"), eq(profileId), eq("example.com"));
    }

    @Test
    void findReturnsEmptyWhenNoActiveCredentialExists() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        EmployerCredentialService service = new EmployerCredentialService(db, cipher);
        when(db.queryForList(anyString(), any(UUID.class), any(String.class))).thenReturn(List.of());

        assertThat(service.find(UUID.randomUUID(), "example.com")).isEmpty();
    }

    @Test
    void existsReflectsWhetherAnActiveCredentialIsStored() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        EmployerCredentialService service = new EmployerCredentialService(db, cipher);
        when(db.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);

        assertThat(service.exists(UUID.randomUUID(), "example.com")).isTrue();
    }

    @Test
    void disableSoftDeletesRatherThanRemovingTheRow() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        EmployerCredentialService service = new EmployerCredentialService(db, cipher);

        service.disable(UUID.randomUUID(), "example.com");

        verify(db).update(contains("status = 'DISABLED'"), any(UUID.class), any(String.class));
    }
}
