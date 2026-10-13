package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signed OAuth state binds the consent round-trip to the signed-in
 * profile: tampering, expiry and cross-account states are all refused, so
 * credentials can never be linked to the wrong user.
 */
class GmailStateSignerTest {

    private final GmailStateSigner signer = new GmailStateSigner("state-secret-for-tests-only");

    @Test
    void signsAndVerifiesForTheSameProfile() {
        UUID profileId = UUID.randomUUID();
        String state = signer.sign(profileId);
        assertThat(signer.verify(state, profileId)).isEqualTo(profileId);
    }

    @Test
    void aStateForAnotherProfileIsRefused() {
        String state = signer.sign(UUID.randomUUID());
        assertThat(signer.verify(state, UUID.randomUUID())).isNull();
    }

    @Test
    void aTamperedStateIsRefused() {
        String state = signer.sign(UUID.randomUUID());
        String tampered = state.substring(0, state.length() - 2) + "xx";
        assertThat(signer.verify(tampered, UUID.randomUUID())).isNull();
    }

    @Test
    void anExpiredButCorrectlySignedStateIsRefused() throws Exception {
        UUID profileId = UUID.randomUUID();
        String state = signPayload(profileId, Instant.now().minusSeconds(60).getEpochSecond(),
                "state-secret-for-tests-only");
        assertThat(signer.verify(state, profileId)).isNull();
    }

    private static String signPayload(UUID profileId, long expiry, String secret) throws Exception {
        String payload = new ObjectMapper().writeValueAsString(Map.of(
                "profileId", profileId.toString(), "exp", expiry, "nonce", "expiry-test"));
        String body = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        return body + "." + signature;
    }

    @Test
    void aMissingSecretRefusesToSignOrVerify() {
        GmailStateSigner unconfigured = new GmailStateSigner("  ");
        assertThat(unconfigured.configured()).isFalse();
        assertThatThrownBy(() -> unconfigured.sign(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_GMAIL_STATE_SECRET");
        assertThat(unconfigured.verify("body.signature", UUID.randomUUID())).isNull();
    }
}
