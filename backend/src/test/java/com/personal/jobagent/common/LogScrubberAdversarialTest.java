package com.personal.jobagent.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3I Phase 6, A4: the backend log scrubber against the shapes secrets actually take in logs,
 * including the three an independent probe found unredacted after Phase 5 (unquoted {@code key: value},
 * credential headers other than Authorization/Cookie, and bare JWTs).
 */
class LogScrubberAdversarialTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GET https://x.example/verify?token=abc123SECRET&lang=en | abc123SECRET",
            "redirect to https://x.example/a?activation_code=99887766&next=/ | 99887766",
            "https://user:pw0rd@host.example/x | pw0rd",
            "{\"otp\":482913} | 482913",
            "{\"data\":{\"verification_code\":\"778899\"}} | 778899",
            "{\"leaseToken\":\"abcDEF123456\"} | abcDEF123456",
            "leaseToken=abcDEF123456 | abcDEF123456",
            "Authorization: Bearer abcdefghijklmnop | abcdefghijklmnop",
            "Cookie: sid=s3cr3tSession; theme=dark | s3cr3tSession",
            "sig=deadbeefcafe&pin=1234 | deadbeefcafe",
            "sig=deadbeefcafe&pin=1234 | =1234",
            // found unredacted after Phase 5:
            "otp: 482913 sent to the worker | 482913",
            "code: 556677 | 556677",
            "verification code: 1029384756 | 1029384756",
            "X-Api-Key: k_live_123456789 | k_live_123456789",
            "x-auth-token=tok_98765abcdef | tok_98765abcdef",
            "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlMTIz leaked | eyJhbGciOiJIUzI1NiJ9",
            "password: hunter2hunter2 | hunter2hunter2"
    })
    void removesTheSecret(String line, String secret) {
        assertThat(LogScrubber.scrub(line)).as(line).doesNotContain(secret).contains("REDACTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP status code: 500 from upstream", "plan 42 finished in 1200 ms", "code: 404",
            "pinned tunnel opened", "tokenizer ready"})
    void leavesOrdinaryDiagnosticsReadable(String line) {
        assertThat(LogScrubber.scrub(line)).isEqualTo(line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrapped: java.io.IOException: GET https://x.example/a?code=556677 failed; cause {\"otp\": 112233}"})
    void scrubsSecretsInsideNestedExceptionText(String text) {
        String out = LogScrubber.scrub(text);
        assertThat(out).doesNotContain("556677").doesNotContain("112233");
    }
}
