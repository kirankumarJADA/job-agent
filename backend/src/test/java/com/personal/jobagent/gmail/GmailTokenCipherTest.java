package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The refresh token never exists in plaintext at rest and survives round-trips. */
class GmailTokenCipherTest {

    private final GmailTokenCipher cipher = new GmailTokenCipher("unit-test-key-not-a-real-secret-0123456789");

    @Test
    void roundTripsTheRefreshToken() {
        String token = "1//0g-refresh-token-abc";
        assertThat(cipher.decrypt(cipher.encrypt(token))).isEqualTo(token);
    }

    @Test
    void everyCiphertextUsesAFreshIv() {
        assertThat(cipher.encrypt("same-token")).isNotEqualTo(cipher.encrypt("same-token"));
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        String encrypted = cipher.encrypt("1//super-secret-refresh-token");
        assertThat(encrypted).doesNotContain("super-secret");
    }

    @Test
    void decryptionWithADifferentKeyFailsClosed() {
        GmailTokenCipher other = new GmailTokenCipher("a-completely-different-key-value-1234567890");
        assertThatThrownByOther(other, cipher.encrypt("token"));
    }

    private static void assertThatThrownByOther(GmailTokenCipher other, String encrypted) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    void aMissingKeyRefusesToEncryptRatherThanStoringPlaintext() {
        GmailTokenCipher unconfigured = new GmailTokenCipher("  ");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> unconfigured.encrypt("token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_GMAIL_TOKEN_KEY");
    }
}
