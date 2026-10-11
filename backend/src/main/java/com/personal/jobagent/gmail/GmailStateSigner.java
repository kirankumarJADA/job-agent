package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Signed OAuth {@code state} values for the Gmail connection flow.
 *
 * <p>The state binds the consent round-trip to the signed-in profile: it
 * carries the profile id and an expiry, HMAC-signed with the server secret.
 * The callback verifies profile/signature/expiry and consumes a database-backed
 * state hash exactly once, preventing replay as well as account-linking CSRF.
 */
@Component
public class GmailStateSigner {

    private static final long TTL_SECONDS = 600;

    private final byte[] secret;
    private final ObjectMapper json = new ObjectMapper();

    public GmailStateSigner(@Value("${app.gmail.state-secret:}") String configuredSecret) {
        // Fail-closed at USE, not at construction: deployments without Gmail
        // configuration must still start (every other feature works), and the
        // authorize endpoint answers 503 instead of signing states.
        this.secret = configuredSecret == null || configuredSecret.isBlank()
                ? null : configuredSecret.trim().getBytes(StandardCharsets.UTF_8);
    }

    public boolean configured() {
        return secret != null;
    }

    public String sign(UUID profileId) {
        if (secret == null) {
            throw new IllegalStateException("APP_GMAIL_STATE_SECRET must be configured before Gmail "
                    + "connections can be initiated: generate one with `openssl rand -base64 32`");
        }
        try {
            String payload = json.writeValueAsString(new StatePayload(
                    profileId.toString(), Instant.now().plusSeconds(TTL_SECONDS).getEpochSecond(),
                    UUID.randomUUID().toString()));
            String body = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            return body + "." + hmac(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign OAuth state", e);
        }
    }

    /**
     * Verifies signature and expiry; returns the bound profile id, or null
     * when the state is invalid for any reason.
     */
    public UUID verify(String state, UUID expectedProfileId) {
        if (secret == null || expectedProfileId == null || state == null || !state.contains(".")) return null;
        int dot = state.lastIndexOf('.');
        String body = state.substring(0, dot);
        String signature = state.substring(dot + 1);
        if (!MessageDigest.isEqual(hmac(body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(body));
            if (!expectedProfileId.toString().equals(payload.path("profileId").asText())) return null;
            if (payload.path("exp").asLong(0) < Instant.now().getEpochSecond()) return null;
            return expectedProfileId;
        } catch (Exception e) {
            return null;
        }
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private record StatePayload(String profileId, long exp, String nonce) {}
}
