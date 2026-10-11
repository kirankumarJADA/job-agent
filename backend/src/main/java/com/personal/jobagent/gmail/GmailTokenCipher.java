package com.personal.jobagent.gmail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for Gmail OAuth refresh tokens at rest.
 *
 * <p>The key comes from {@code APP_GMAIL_TOKEN_KEY}: either a base64-encoded
 * 32-byte key (preferred) or any high-entropy passphrase, from which a 256-bit
 * key is derived with SHA-256. Fail-closed: without a configured key no Gmail
 * connection can be stored, so a deployment can never silently keep plaintext
 * credentials. Every ciphertext carries a fresh random 12-byte IV; the nonce
 * is never reused because a new SecureRandom IV is drawn per encryption.
 */
@Component
public class GmailTokenCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public GmailTokenCipher(@Value("${app.gmail.token-key:}") String configuredKey) {
        // Fail-closed at USE, not at construction (see GmailStateSigner).
        this.key = configuredKey == null || configuredKey.isBlank()
                ? null : deriveKey(configuredKey.trim());
    }

    public boolean configured() {
        return key != null;
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("APP_GMAIL_TOKEN_KEY must be configured before Gmail connections "
                    + "can be stored: generate one with `openssl rand -base64 32`");
        }
    }

    private static SecretKeySpec deriveKey(String configured) {
        try {
            byte[] raw;
            try {
                byte[] decoded = Base64.getDecoder().decode(configured);
                raw = decoded.length == 32 ? decoded : sha256(decoded);
            } catch (IllegalArgumentException parseFailure) {
                raw = sha256(configured.getBytes(StandardCharsets.UTF_8));
            }
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("APP_GMAIL_TOKEN_KEY could not be turned into an AES-256 key", e);
        }
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Gmail token encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Gmail token decryption failed — was APP_GMAIL_TOKEN_KEY rotated?", e);
        }
    }
}
