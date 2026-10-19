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
 * Mailgun webhook signature check, as documented by Mailgun ("Securing
 * webhooks"): the signature is the hex HMAC-SHA256 of {@code timestamp}
 * concatenated with {@code token}, keyed with the account's Webhook Signing Key.
 *
 * <p>The signature covers only the timestamp and token, not the message
 * fields. That is why the caller must also (a) refuse a token that has already
 * been accepted and (b) refuse timestamps outside a bounded window: together
 * they stop a captured request from being replayed with a different payload.
 *
 * <p>The signature is checked before the timestamp, so an unauthenticated
 * request learns nothing about the window. Comparison is constant-time.
 */
public final class MailgunSignatureVerifier {

    /** Outcome of a check. Only {@link #VALID} authenticates the request. */
    public enum Outcome {
        VALID,
        /** A signature field is absent: unauthenticated. */
        MISSING,
        /** A signature field is present but not in the documented shape: unauthenticated. */
        MALFORMED,
        /** Well formed, but the HMAC does not match: unauthenticated. */
        INVALID_SIGNATURE,
        /** Authentic but older than the accepted window. */
        STALE,
        /** Authentic but dated too far in the future. */
        FUTURE;

        public boolean authenticated() {
            return this == VALID || this == STALE || this == FUTURE;
        }
    }

    private static final Pattern TIMESTAMP = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9]{16,256}$");
    private static final Pattern SIGNATURE = Pattern.compile("^[0-9a-fA-F]{64}$");

    private MailgunSignatureVerifier() {
    }

    public static Outcome verify(String signingKey, String timestamp, String token, String signature,
                                 Instant now, Duration maxAge, Duration maxFutureSkew) {
        if (signingKey == null || signingKey.isEmpty()) {
            throw new IllegalStateException("The Mailgun webhook signing key is not configured");
        }
        if (isBlank(timestamp) || isBlank(token) || isBlank(signature)) return Outcome.MISSING;
        if (!TIMESTAMP.matcher(timestamp).matches() || !TOKEN.matcher(token).matches()
                || !SIGNATURE.matcher(signature).matches()) {
            return Outcome.MALFORMED;
        }
        byte[] expected = hmacHex(signingKey, timestamp + token).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) return Outcome.INVALID_SIGNATURE;

        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (signedAt.isBefore(now.minus(maxAge))) return Outcome.STALE;
        if (signedAt.isAfter(now.plus(maxFutureSkew))) return Outcome.FUTURE;
        return Outcome.VALID;
    }

    /** Lower-case hex HMAC-SHA256 of {@code data} under {@code key}. */
    static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
