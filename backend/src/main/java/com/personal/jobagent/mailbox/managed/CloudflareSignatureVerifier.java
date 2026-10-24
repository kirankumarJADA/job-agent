package com.personal.jobagent.mailbox.managed;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cloudflare Email Worker webhook signature verification (Phase 20).
 *
 * <p>Authentication is performed by computing the HMAC-SHA256 over:
 * {@code timestamp + "." + token + "." + payloadSha256}
 * keyed with the shared secret {@code CLOUDFLARE_EMAIL_WORKER_SECRET}.
 *
 * <p>The signature binds the timestamp, token (nonce), and the exact payload digest,
 * preventing tampering and replay attacks. Comparison is constant-time.
 */
public final class CloudflareSignatureVerifier {

    public enum Outcome {
        VALID,
        /** A required header is absent. */
        MISSING,
        /** A header is present but malformed. */
        MALFORMED,
        /** Well-formed, but HMAC does not match. */
        INVALID_SIGNATURE,
        /** Authentic, but older than the allowed maxAge window. */
        STALE,
        /** Authentic, but timestamp is too far in the future. */
        FUTURE;

        public boolean authenticated() {
            return this == VALID || this == STALE || this == FUTURE;
        }
    }

    private static final Pattern TIMESTAMP = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{16,256}$");
    private static final Pattern SIGNATURE = Pattern.compile("^[0-9a-fA-F]{64}$");

    private CloudflareSignatureVerifier() {
    }

    public static Outcome verify(String sharedSecret, String timestamp, String token, String payloadSha256,
                                 String signature, Instant now, Duration maxAge, Duration maxFutureSkew) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            throw new IllegalStateException("The Cloudflare webhook secret is not configured");
        }
        if (isBlank(timestamp) || isBlank(token) || isBlank(payloadSha256) || isBlank(signature)) {
            return Outcome.MISSING;
        }
        if (!TIMESTAMP.matcher(timestamp).matches() || !TOKEN.matcher(token).matches()
                || !SIGNATURE.matcher(signature).matches()) {
            return Outcome.MALFORMED;
        }
        String data = timestamp + "." + token + "." + payloadSha256.toLowerCase(Locale.ROOT);
        byte[] expected = hmacHex(sharedSecret, data).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) return Outcome.INVALID_SIGNATURE;

        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (signedAt.isBefore(now.minus(maxAge))) return Outcome.STALE;
        if (signedAt.isAfter(now.plus(maxFutureSkew))) return Outcome.FUTURE;
        return Outcome.VALID;
    }

    static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
