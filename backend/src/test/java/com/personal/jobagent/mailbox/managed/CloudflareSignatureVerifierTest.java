package com.personal.jobagent.mailbox.managed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudflareSignatureVerifierTest {

    private static final String SECRET = "test-secret-key-1234567890-abcdef";
    private static final Duration MAX_AGE = Duration.ofSeconds(900);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(120);

    @Test
    void validSignatureAndFreshTimestampPassesVerification() {
        Instant now = Instant.ofEpochSecond(1729731600);
        String timestamp = "1729731550";
        String token = "abcd1234efgh5678ijkl";
        String payloadSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String data = timestamp + "." + token + "." + payloadSha256;
        String signature = CloudflareSignatureVerifier.hmacHex(SECRET, data);

        CloudflareSignatureVerifier.Outcome outcome = CloudflareSignatureVerifier.verify(
                SECRET, timestamp, token, payloadSha256, signature, now, MAX_AGE, MAX_FUTURE_SKEW);

        assertThat(outcome).isEqualTo(CloudflareSignatureVerifier.Outcome.VALID);
        assertThat(outcome.authenticated()).isTrue();
    }

    @Test
    void missingOrBlankFieldsAreRejected() {
        Instant now = Instant.now();
        assertThat(CloudflareSignatureVerifier.verify(SECRET, "", "token", "hash", "sig", now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.MISSING);
        assertThat(CloudflareSignatureVerifier.verify(SECRET, "123", "", "hash", "sig", now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.MISSING);
        assertThat(CloudflareSignatureVerifier.verify(SECRET, "123", "token", "", "sig", now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.MISSING);
        assertThat(CloudflareSignatureVerifier.verify(SECRET, "123", "token", "hash", "", now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.MISSING);
    }

    @Test
    void malformedFieldsAreRejected() {
        Instant now = Instant.now();
        // Malformed timestamp (contains letters)
        assertThat(CloudflareSignatureVerifier.verify(SECRET, "123abc", "token123456789012", "hash", "sig", now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.MALFORMED);
        // Short token (< 16 chars)
        assertThat(CloudflareSignatureVerifier.verify(SECRET, "1729731550", "short", "hash", "sig", now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.MALFORMED);
        // Invalid signature length
        assertThat(CloudflareSignatureVerifier.verify(SECRET, "1729731550", "token123456789012", "hash", "shortsig", now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.MALFORMED);
    }

    @Test
    void wrongSecretOrTamperedDataYieldsInvalidSignature() {
        Instant now = Instant.ofEpochSecond(1729731600);
        String timestamp = "1729731550";
        String token = "abcd1234efgh5678ijkl";
        String payloadSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String signature = CloudflareSignatureVerifier.hmacHex(SECRET, timestamp + "." + token + "." + payloadSha256);

        // Wrong secret
        assertThat(CloudflareSignatureVerifier.verify("wrong-secret-key-123", timestamp, token, payloadSha256, signature, now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.INVALID_SIGNATURE);

        // Tampered payload hash
        String tamperedHash = "a" + payloadSha256.substring(1);
        assertThat(CloudflareSignatureVerifier.verify(SECRET, timestamp, token, tamperedHash, signature, now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.INVALID_SIGNATURE);
    }

    @Test
    void staleAndFutureTimestampsAreRefused() {
        Instant now = Instant.ofEpochSecond(1729731600);
        String token = "abcd1234efgh5678ijkl";
        String payloadSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // Stale timestamp (older than 900 seconds)
        String staleTimestamp = String.valueOf(now.getEpochSecond() - 901);
        String staleSig = CloudflareSignatureVerifier.hmacHex(SECRET, staleTimestamp + "." + token + "." + payloadSha256);
        assertThat(CloudflareSignatureVerifier.verify(SECRET, staleTimestamp, token, payloadSha256, staleSig, now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.STALE);

        // Future timestamp (greater than 120 seconds in future)
        String futureTimestamp = String.valueOf(now.getEpochSecond() + 121);
        String futureSig = CloudflareSignatureVerifier.hmacHex(SECRET, futureTimestamp + "." + token + "." + payloadSha256);
        assertThat(CloudflareSignatureVerifier.verify(SECRET, futureTimestamp, token, payloadSha256, futureSig, now, MAX_AGE, MAX_FUTURE_SKEW))
                .isEqualTo(CloudflareSignatureVerifier.Outcome.FUTURE);
    }

    @Test
    void unconfiguredSecretThrowsIllegalStateException() {
        assertThatThrownBy(() -> CloudflareSignatureVerifier.verify("", "123", "token", "hash", "sig", Instant.now(), MAX_AGE, MAX_FUTURE_SKEW))
                .isInstanceOf(IllegalStateException.class);
    }
}
