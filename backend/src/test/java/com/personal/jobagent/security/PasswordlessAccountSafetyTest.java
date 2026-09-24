package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A Firebase-backed account has no local password hash (V020 makes the column
 * nullable). That absence has to behave safely everywhere the old
 * password-based paths still run:
 *
 * <ul>
 *   <li>{@code POST /auth/login} must reject such an account as bad credentials,
 *       not blow up with a null-pointer or a 500;</li>
 *   <li>the principal must still carry the user id, or every other endpoint
 *       would break for those accounts;</li>
 *   <li>{@code /auth/purge-my-data} must refuse the confirmation cleanly.</li>
 * </ul>
 *
 * The encoder assertion below is the load-bearing one: it is what
 * {@code DaoAuthenticationProvider} calls, so if it ever starts throwing instead
 * of returning false, a Firebase account attempting the legacy login would turn
 * a 401 into a 500.
 */
class PasswordlessAccountSafetyTest {

    @Test
    void theConfiguredEncoderRejectsANullHashInsteadOfThrowing() {
        PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        assertThatCode(() -> assertThat(encoder.matches("any-password", null)).isFalse())
                .as("a null hash must be a failed match, which the login path maps to 401")
                .doesNotThrowAnyException();
    }

    @Test
    void theConfiguredEncoderRejectsAnEmptyHashRatherThanTreatingItAsAMatch() {
        PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        assertThat(encoder.matches("", "")).isFalse();
        assertThat(encoder.matches("any-password", "")).isFalse();
    }

    @Test
    void aFirebaseAccountReportsThatItHasNoLocalPassword() {
        UserRecord account = new UserRecord(
                UUID.randomUUID(), "person@example.com", null, "Person", "uid-1", "FIREBASE");

        assertThat(account.isFirebaseAccount()).isTrue();
        assertThat(account.hasLocalPassword()).isFalse();
    }

    @Test
    void aBlankHashIsTreatedAsAbsentEvenForALocalAccount() {
        UserRecord account = new UserRecord(
                UUID.randomUUID(), "person@example.com", "   ", "Person", null, "LOCAL");

        assertThat(account.hasLocalPassword()).isFalse();
    }

    @Test
    void aLocallyHashedAccountStillCarriesAUsablePassword() {
        // The existing local account must not be broken by the nullability.
        UserRecord account = new UserRecord(
                UUID.randomUUID(), "dev@example.local", "$argon2id$v=19$m=19456,t=2,p=1$abc$def",
                "Dev User", null, "LOCAL");

        assertThat(account.hasLocalPassword()).isTrue();
        assertThat(account.isFirebaseAccount()).isFalse();
    }

    @Test
    void thePrincipalStillExposesTheUserIdAndUsernameWithoutAPassword() {
        UserRecord account = new UserRecord(
                UUID.randomUUID(), "person@example.com", null, "Person", "uid-1", "FIREBASE");
        AppUserDetails principal = new AppUserDetails(account);

        // Every controller resolves the caller through these; a null password
        // must not affect them.
        assertThat(principal.getPassword()).isNull();
        assertThat(principal.getUserId()).isEqualTo(account.id());
        assertThat(principal.getUsername()).isEqualTo("person@example.com");
        assertThat(principal.getAuthorities()).extracting(Object::toString).containsExactly("USER");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void anAccountCreatedBeforeFirebaseIsStillLinkable() {
        UserRecord legacy = new UserRecord(
                UUID.randomUUID(), "dev@example.local", "$argon2id$v=19$m=19456,t=2,p=1$abc$def",
                "Dev User", null, "LOCAL");

        // V020 leaves auth_provider as LOCAL and firebase_uid null for
        // pre-existing rows, so they remain ordinary local accounts until a
        // Firebase sign-in claims them.
        assertThat(legacy.isFirebaseAccount()).isFalse();
        assertThat(legacy.firebaseUid()).isNull();
        assertThat(legacy.hasLocalPassword()).isTrue();
    }
}
