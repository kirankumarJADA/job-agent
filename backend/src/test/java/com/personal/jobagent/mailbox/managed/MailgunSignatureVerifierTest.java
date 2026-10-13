package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.mailbox.managed.MailgunSignatureVerifier.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mailgun's documented scheme: hex HMAC-SHA256(signing key, timestamp + token).
 * The expected signature below was computed independently with Python's
 * {@code hmac} module, not with the code under test.
 */
class MailgunSignatureVerifierTest {

    private static final String KEY = "key-3ax6xnjp29jd6fds4gc373sgvjxteol0";
    private static final String TIMESTAMP = "1760184000";
    private static final String TOKEN = "c3a4f1d29b8e7f60a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9";
    private static final String SIGNATURE = "f102cbd9018b11e30820d735916beae558e4930e6379af3854d5cec7378f4a06";
    private static final Instant SIGNED = Instant.ofEpochSecond(1760184000L);
    private static final Duration MAX_AGE = Duration.ofMinutes(15);
    private static final Duration SKEW = Duration.ofMinutes(5);

    @Test
    void acceptsTheDocumentedSignatureComputedIndependently() {
        assertThat(MailgunSignatureVerifier.hmacHex(KEY, TIMESTAMP + TOKEN)).isEqualTo(SIGNATURE);
        assertThat(verify(TIMESTAMP, TOKEN, SIGNATURE, SIGNED.plusSeconds(30))).isEqualTo(Outcome.VALID);
        // Hex case is not significant.
        assertThat(verify(TIMESTAMP, TOKEN, SIGNATURE.toUpperCase(), SIGNED)).isEqualTo(Outcome.VALID);
    }

    @Test
    void refusesAWrongKeyOrAnyTamperedField() {
        assertThat(MailgunSignatureVerifier.verify("another-key", TIMESTAMP, TOKEN, SIGNATURE, SIGNED, MAX_AGE, SKEW))
                .isEqualTo(Outcome.INVALID_SIGNATURE);
        assertThat(verify("1760184001", TOKEN, SIGNATURE, SIGNED)).isEqualTo(Outcome.INVALID_SIGNATURE);
        assertThat(verify(TIMESTAMP, TOKEN.replace('c', 'd'), SIGNATURE, SIGNED)).isEqualTo(Outcome.INVALID_SIGNATURE);
        String flipped = (SIGNATURE.charAt(0) == 'f' ? "e" : "f") + SIGNATURE.substring(1);
        assertThat(verify(TIMESTAMP, TOKEN, flipped, SIGNED)).isEqualTo(Outcome.INVALID_SIGNATURE);
        assertThat(Outcome.INVALID_SIGNATURE.authenticated()).isFalse();
    }

    @Test
    void missingOrMalformedFieldsAreUnauthenticated() {
        assertThat(verify(null, TOKEN, SIGNATURE, SIGNED)).isEqualTo(Outcome.MISSING);
        assertThat(verify(TIMESTAMP, "", SIGNATURE, SIGNED)).isEqualTo(Outcome.MISSING);
        assertThat(verify(TIMESTAMP, TOKEN, "  ", SIGNED)).isEqualTo(Outcome.MISSING);
        assertThat(verify("17601840OO", TOKEN, SIGNATURE, SIGNED)).isEqualTo(Outcome.MALFORMED);
        assertThat(verify("-1760184000", TOKEN, SIGNATURE, SIGNED)).isEqualTo(Outcome.MALFORMED);
        assertThat(verify(TIMESTAMP, "short", SIGNATURE, SIGNED)).isEqualTo(Outcome.MALFORMED);
        assertThat(verify(TIMESTAMP, TOKEN + "!", SIGNATURE, SIGNED)).isEqualTo(Outcome.MALFORMED);
        assertThat(verify(TIMESTAMP, TOKEN, SIGNATURE.substring(2), SIGNED)).isEqualTo(Outcome.MALFORMED);
        assertThat(verify(TIMESTAMP, TOKEN, "z" + SIGNATURE.substring(1), SIGNED)).isEqualTo(Outcome.MALFORMED);
        assertThat(Outcome.MISSING.authenticated()).isFalse();
        assertThat(Outcome.MALFORMED.authenticated()).isFalse();
    }

    @Test
    void anAuthenticRequestOutsideTheWindowIsStaleOrFuture() {
        assertThat(verify(TIMESTAMP, TOKEN, SIGNATURE, SIGNED.plus(MAX_AGE))).isEqualTo(Outcome.VALID);
        assertThat(verify(TIMESTAMP, TOKEN, SIGNATURE, SIGNED.plus(MAX_AGE).plusSeconds(1))).isEqualTo(Outcome.STALE);
        assertThat(verify(TIMESTAMP, TOKEN, SIGNATURE, SIGNED.minus(SKEW))).isEqualTo(Outcome.VALID);
        assertThat(verify(TIMESTAMP, TOKEN, SIGNATURE, SIGNED.minus(SKEW).minusSeconds(1))).isEqualTo(Outcome.FUTURE);
        // Stale and future requests are authentic (the caller answers 406, not 401).
        assertThat(Outcome.STALE.authenticated()).isTrue();
        assertThat(Outcome.FUTURE.authenticated()).isTrue();
    }

    @Test
    void theWindowIsCheckedOnlyAfterTheSignature() {
        // A forged request far outside the window still learns only "unauthenticated".
        assertThat(verify(TIMESTAMP, TOKEN, "0".repeat(64), SIGNED.plus(Duration.ofDays(30))))
                .isEqualTo(Outcome.INVALID_SIGNATURE);
    }

    @Test
    void anUnconfiguredKeyIsAProgrammingErrorNotAPass() {
        assertThatThrownBy(() -> MailgunSignatureVerifier.verify("", TIMESTAMP, TOKEN, SIGNATURE, SIGNED, MAX_AGE, SKEW))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Outcome verify(String timestamp, String token, String signature, Instant now) {
        return MailgunSignatureVerifier.verify(KEY, timestamp, token, signature, now, MAX_AGE, SKEW);
    }
}
