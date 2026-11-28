package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.security.SecretCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deployment configuration for Robin-managed email (Phase 8.5, Phase 20).
 *
 * <p>Supports inbound providers:
 * <ul>
 *   <li>{@code mailgun} — authenticated via Mailgun HMAC webhook signature.</li>
 *   <li>{@code cloudflare} — authenticated via Cloudflare Email Worker HMAC signature.</li>
 * </ul>
 */
@Component
public class ManagedMailSettings {

    public static final String PROVIDER_MAILGUN = "mailgun";
    public static final String PROVIDER_CLOUDFLARE = "cloudflare";

    private static final String DOMAIN_PATTERN = "^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$";
    private static final String ADDRESS_PREFIX_PATTERN = "^[a-z][a-z0-9+_-]{0,47}$";

    private final String domain;
    private final String provider;
    private final String mailgunSigningKey;
    private final Duration mailgunMaxAge;
    private final String cloudflareWebhookSecret;
    private final Duration cloudflareMaxAge;
    private final Set<String> cloudflareTrustedAuthServIds;
    private final String addressPrefix;
    private final Duration futureSkew;
    private final Duration contentRetention;
    private final Duration metadataRetention;
    private final SecretCipher cipher;

    @Autowired
    public ManagedMailSettings(@Value("${app.managed-mail.domain:}") String domain,
                               @Value("${app.managed-mail.provider:mailgun}") String provider,
                               @Value("${app.managed-mail.mailgun.webhook-signing-key:}") String mailgunSigningKey,
                               @Value("${app.managed-mail.mailgun.max-age-seconds:900}") long maxAgeSeconds,
                               @Value("${app.managed-mail.cloudflare.webhook-secret:}") String cloudflareWebhookSecret,
                               @Value("${app.managed-mail.cloudflare.max-age-seconds:900}") long cloudflareMaxAgeSeconds,
                               @Value("${app.managed-mail.cloudflare.trusted-authserv-ids:}") String cloudflareTrustedAuthServIds,
                               @Value("${app.managed-mail.address-prefix:}") String addressPrefix,
                               @Value("${app.managed-mail.content-retention-minutes:120}") long contentRetentionMinutes,
                               @Value("${app.managed-mail.metadata-retention-days:30}") long metadataRetentionDays,
                               SecretCipher cipher) {
        String normalisedDomain = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        this.domain = normalisedDomain.matches(DOMAIN_PATTERN) ? normalisedDomain : "";
        this.provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.mailgunSigningKey = mailgunSigningKey == null ? "" : mailgunSigningKey.trim();
        this.cloudflareWebhookSecret = cloudflareWebhookSecret == null ? "" : cloudflareWebhookSecret.trim();
        this.mailgunMaxAge = Duration.ofSeconds(Math.max(60, Math.min(maxAgeSeconds, 12 * 3600)));
        this.cloudflareMaxAge = Duration.ofSeconds(Math.max(60, Math.min(cloudflareMaxAgeSeconds, 12 * 3600)));
        this.cloudflareTrustedAuthServIds = parseAuthServIds(cloudflareTrustedAuthServIds);

        if (addressPrefix != null && !addressPrefix.isBlank()) {
            this.addressPrefix = addressPrefix.trim().toLowerCase(Locale.ROOT);
        } else if (PROVIDER_CLOUDFLARE.equals(this.provider)) {
            this.addressPrefix = "robin+";
        } else {
            this.addressPrefix = "";
        }

        this.futureSkew = Duration.ofMinutes(5);
        this.contentRetention = Duration.ofMinutes(Math.max(5, Math.min(contentRetentionMinutes, 7 * 24 * 60)));
        this.metadataRetention = Duration.ofDays(Math.max(1, Math.min(metadataRetentionDays, 365)));
        this.cipher = cipher;
    }

    /** Backward-compatible constructor preserving existing callers without trusted authserv list. */
    public ManagedMailSettings(String domain, String provider, String mailgunSigningKey,
                               long maxAgeSeconds, String cloudflareWebhookSecret,
                               long cloudflareMaxAgeSeconds, String addressPrefix,
                               long contentRetentionMinutes, long metadataRetentionDays,
                               SecretCipher cipher) {
        this(domain, provider, mailgunSigningKey, maxAgeSeconds, cloudflareWebhookSecret,
                cloudflareMaxAgeSeconds, "", addressPrefix, contentRetentionMinutes, metadataRetentionDays, cipher);
    }

    /** Backward-compatible constructor for existing tests and Mailgun-only usage. */
    public ManagedMailSettings(String domain, String provider, String mailgunSigningKey,
                               long maxAgeSeconds, long contentRetentionMinutes,
                               long metadataRetentionDays, SecretCipher cipher) {
        this(domain, provider, mailgunSigningKey, maxAgeSeconds, "", 900, "", "",
                contentRetentionMinutes, metadataRetentionDays, cipher);
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

    public String cloudflareWebhookSecret() {
        return cloudflareWebhookSecret;
    }

    public Duration cloudflareMaxAge() {
        return cloudflareMaxAge;
    }

    /**
     * Explicit allowlist of trusted Cloudflare/receiver {@code authserv-id} values.
     * Defaults to empty (fail-closed: inbound DKIM/DMARC assertions are never promoted).
     */
    public Set<String> cloudflareTrustedAuthServIds() {
        return cloudflareTrustedAuthServIds;
    }

    private static Set<String> parseAuthServIds(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> parsed = new LinkedHashSet<>();
        for (String token : raw.split("[,\\s]+")) {
            String cleaned = token.trim().toLowerCase(Locale.ROOT);
            while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
            if (!cleaned.isBlank() && cleaned.matches("^[a-z0-9._-]+$")) {
                parsed.add(cleaned);
            }
        }
        return Set.copyOf(parsed);
    }

    public String addressPrefix() {
        return addressPrefix;
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
        Duration maxAge = PROVIDER_CLOUDFLARE.equals(provider) ? cloudflareMaxAge : mailgunMaxAge;
        return maxAge.plus(futureSkew).multipliedBy(2);
    }

    public boolean configured() {
        return missing().isEmpty();
    }

    /** Names (never values) of the settings still needed, for operator-facing messages. */
    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (domain.isEmpty()) missing.add("APP_MANAGED_MAIL_DOMAIN");
        if (!PROVIDER_MAILGUN.equals(provider) && !PROVIDER_CLOUDFLARE.equals(provider)) {
            missing.add("APP_MANAGED_MAIL_PROVIDER=mailgun");
        } else if (PROVIDER_MAILGUN.equals(provider)) {
            if (mailgunSigningKey.isEmpty()) missing.add("MAILGUN_WEBHOOK_SIGNING_KEY");
        } else if (PROVIDER_CLOUDFLARE.equals(provider)) {
            if (cloudflareWebhookSecret.isEmpty()) missing.add("CLOUDFLARE_EMAIL_WORKER_SECRET");
        }
        if (!addressPrefix.isEmpty() && !addressPrefix.matches(ADDRESS_PREFIX_PATTERN)) {
            missing.add("APP_MANAGED_MAIL_ADDRESS_PREFIX");
        }
        if (cipher == null || !cipher.configured()) missing.add("APP_SECRETS_KEY");
        return List.copyOf(missing);
    }

    /** Settings missing for automated sender-authenticated OTP/link verification (never exposes secret values). */
    public List<String> verificationMissing() {
        List<String> items = new ArrayList<>();
        if (PROVIDER_CLOUDFLARE.equals(provider) && cloudflareTrustedAuthServIds.isEmpty()) {
            items.add("APP_MANAGED_MAIL_CLOUDFLARE_TRUSTED_AUTHSERV_IDS");
        }
        return List.copyOf(items);
    }
}
