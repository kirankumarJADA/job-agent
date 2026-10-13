package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.security.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deployment configuration for Robin-managed email (Phase 8.5).
 *
 * <p>Robin-managed email needs three things from the operator, and nothing
 * here works until all three exist:
 * <ul>
 *   <li>{@code APP_MANAGED_MAIL_DOMAIN} — a (sub)domain the operator owns whose
 *       MX records point at the inbound provider, e.g. {@code mail.robin.example};</li>
 *   <li>{@code MAILGUN_WEBHOOK_SIGNING_KEY} — the provider's webhook signing
 *       key, used to authenticate every inbound delivery (it is not the API key);</li>
 *   <li>{@code APP_SECRETS_KEY} — the AES-256-GCM key that encrypts message
 *       content at rest (shared with employer credentials).</li>
 * </ul>
 * Configuration alone never makes an address "ready": see
 * {@link ManagedMailboxService#status} for the delivery evidence that does.
 * Values are never logged; {@link #missing()} names the variables only.
 */
@Component
public class ManagedMailSettings {

    public static final String PROVIDER_MAILGUN = "mailgun";

    private static final String DOMAIN_PATTERN = "^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$";

    private final String domain;
    private final String provider;
    private final String mailgunSigningKey;
    private final Duration mailgunMaxAge;
    private final Duration futureSkew;
    private final Duration contentRetention;
    private final Duration metadataRetention;
    private final SecretCipher cipher;

    public ManagedMailSettings(@Value("${app.managed-mail.domain:}") String domain,
                               @Value("${app.managed-mail.provider:mailgun}") String provider,
                               @Value("${app.managed-mail.mailgun.webhook-signing-key:}") String mailgunSigningKey,
                               @Value("${app.managed-mail.mailgun.max-age-seconds:900}") long maxAgeSeconds,
                               @Value("${app.managed-mail.content-retention-minutes:120}") long contentRetentionMinutes,
                               @Value("${app.managed-mail.metadata-retention-days:30}") long metadataRetentionDays,
                               SecretCipher cipher) {
        String normalisedDomain = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        this.domain = normalisedDomain.matches(DOMAIN_PATTERN) ? normalisedDomain : "";
        this.provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.mailgunSigningKey = mailgunSigningKey == null ? "" : mailgunSigningKey.trim();
        // Bounded so a misconfiguration can neither disable the window nor keep
        // tokens (and therefore the replay table) forever.
        this.mailgunMaxAge = Duration.ofSeconds(Math.max(60, Math.min(maxAgeSeconds, 12 * 3600)));
        this.futureSkew = Duration.ofMinutes(5);
        this.contentRetention = Duration.ofMinutes(Math.max(5, Math.min(contentRetentionMinutes, 7 * 24 * 60)));
        this.metadataRetention = Duration.ofDays(Math.max(1, Math.min(metadataRetentionDays, 365)));
        this.cipher = cipher;
    }

    /** The configured receiving domain, lower-case, or "" when unset or invalid. */
    public String domain() {
        return domain;
    }

    public String providerId() {
        return provider;
    }

    String mailgunSigningKey() {
        return mailgunSigningKey;
    }

    public Duration mailgunMaxAge() {
        return mailgunMaxAge;
    }

    public Duration futureSkew() {
        return futureSkew;
    }

    public Duration contentRetention() {
        return contentRetention;
    }

    public Duration metadataRetention() {
        return metadataRetention;
    }

    /** Accepted webhook tokens are kept at least twice as long as any request could be accepted. */
    public Duration tokenRetention() {
        return mailgunMaxAge.plus(futureSkew).multipliedBy(2);
    }

    public boolean configured() {
        return missing().isEmpty();
    }

    /** Names (never values) of the settings still needed, for operator-facing messages. */
    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (domain.isEmpty()) missing.add("APP_MANAGED_MAIL_DOMAIN");
        if (!PROVIDER_MAILGUN.equals(provider)) missing.add("APP_MANAGED_MAIL_PROVIDER=mailgun");
        if (mailgunSigningKey.isEmpty()) missing.add("MAILGUN_WEBHOOK_SIGNING_KEY");
        if (cipher == null || !cipher.configured()) missing.add("APP_SECRETS_KEY");
        return List.copyOf(missing);
    }
}
