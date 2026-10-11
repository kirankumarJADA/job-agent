package com.personal.jobagent.security;

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
 * AES-256-GCM encryption for employer portal credentials at rest (Phase 8.5).
 *
 * <p>Key comes from {@code APP_SECRETS_KEY} (base64 32-byte key preferred,
 * otherwise SHA-256 of the value). Fail-closed at use: without a key, employer
 * credentials can be neither stored nor decrypted, and the account lifecycle
 * pauses for the human instead. Same pattern as GmailTokenCipher.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.secrets.encryption-key:}") String configuredKey) {
        this.key = configuredKey == null || configuredKey.isBlank()
                ? null : deriveKey(configuredKey.trim());
    }

    public boolean configured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        return encryptInternal(plaintext);
    }

    public String decrypt(String encoded) {
        requireKey();
        return decryptInternal(encoded);
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("APP_SECRETS_KEY must be configured before employer credentials "
                    + "can be stored or used: generate one with `openssl rand -base64 32`");
        }
    }

    private String encryptInternal(String plaintext) {
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
            throw new IllegalStateException("Credential encryption failed", e);
        }
    }

    private String decryptInternal(String encoded) {
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Credential decryption failed — was APP_SECRETS_KEY rotated?", e);
        }
    }

    private static SecretKeySpec deriveKey(String configured) {
        try {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(configured);
            } catch (IllegalArgumentException e) {
                decoded = configured.getBytes(StandardCharsets.UTF_8);
            }
            byte[] raw = decoded.length == 32 ? decoded
                    : MessageDigest.getInstance("SHA-256").digest(decoded);
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("APP_SECRETS_KEY could not be turned into an AES-256 key", e);
        }
    }
}
