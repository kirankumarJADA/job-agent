package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Employer-portal credentials (Robin application-execution hardening): never
 * exist in plaintext at rest, survive round-trips, and fail closed on a
 * wrong key, a tampered ciphertext, or a missing key — mirroring
 * {@code gmail.GmailTokenCipherTest}, the sibling this class's javadoc names
 * as the same pattern.
 */
class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher("unit-test-key-not-a-real-secret-0123456789");

    @Test
    void configuredReportsTrueOnlyWhenAKeyIsPresent() {
        assertThat(cipher.configured()).isTrue();
        assertThat(new SecretCipher("  ").configured()).isFalse();
        assertThat(new SecretCipher(null).configured()).isFalse();
    }

    @Test
    void roundTripsThePlaintext() {
        String secret = "hunter2-employer-portal-password";
        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void everyCiphertextUsesAFreshIv() {
        assertThat(cipher.encrypt("same-credential")).isNotEqualTo(cipher.encrypt("same-credential"));
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        String encrypted = cipher.encrypt("super-secret-employer-password");
        assertThat(encrypted).doesNotContain("super-secret");
    }

    @Test
    void decryptionWithADifferentKeyFailsClosed() {
        SecretCipher other = new SecretCipher("a-completely-different-key-value-1234567890");
        String encrypted = cipher.encrypt("employer-credential");
        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void aTamperedCiphertextFailsClosedRatherThanReturningCorruptedData() {
        String encrypted = cipher.encrypt("untampered-credential");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01; // flip a bit inside the GCM auth tag
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void aTruncatedCiphertextFailsClosedRatherThanThrowingSomethingUnrelated() {
        String encrypted = cipher.encrypt("some-credential");
        String truncated = encrypted.substring(0, encrypted.length() - 4);
        assertThatThrownBy(() -> cipher.decrypt(truncated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void aMissingKeyRefusesToEncryptRatherThanStoringPlaintext() {
        SecretCipher unconfigured = new SecretCipher("  ");
        assertThatThrownBy(() -> unconfigured.encrypt("employer-credential"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SECRETS_KEY");
    }

    @Test
    void aMissingKeyRefusesToDecryptRatherThanThrowingSomethingUnrelated() {
        SecretCipher unconfigured = new SecretCipher("");
        assertThatThrownBy(() -> unconfigured.decrypt("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SECRETS_KEY");
    }
}
