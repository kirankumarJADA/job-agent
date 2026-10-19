package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.security.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ManagedMailSettingsTest {

    private static final SecretCipher CIPHER = new SecretCipher("6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U=");

    private static ManagedMailSettings settings(String domain, String key, SecretCipher cipher) {
        return new ManagedMailSettings(domain, "mailgun", key, 900, 120, 30, cipher);
    }

    @Test
    void everyMissingPieceIsNamedWithoutItsValue() {
        ManagedMailSettings none = settings("", "", new SecretCipher(""));
        assertThat(none.configured()).isFalse();
        assertThat(none.missing()).containsExactly("APP_MANAGED_MAIL_DOMAIN", "MAILGUN_WEBHOOK_SIGNING_KEY", "APP_SECRETS_KEY");

        ManagedMailSettings all = settings(" Mail.Robin.Example ", "signing-key", CIPHER);
        assertThat(all.configured()).isTrue();
        assertThat(all.domain()).isEqualTo("mail.robin.example");
        assertThat(all.missing()).isEmpty();
        assertThat(all.toString()).doesNotContain("signing-key");
    }

    @Test
    void anInvalidDomainOrProviderIsTreatedAsUnset() {
        assertThat(settings("https://mail.robin.example", "k", CIPHER).missing()).contains("APP_MANAGED_MAIL_DOMAIN");
        assertThat(settings("localhost", "k", CIPHER).missing()).contains("APP_MANAGED_MAIL_DOMAIN");
        assertThat(new ManagedMailSettings("mail.robin.example", "sendgrid", "k", 900, 120, 30, CIPHER).missing())
                .containsExactly("APP_MANAGED_MAIL_PROVIDER=mailgun");
    }

    @Test
    void windowsAreBoundedSoMisconfigurationCannotDisableThem() {
        ManagedMailSettings tiny = new ManagedMailSettings("mail.robin.example", "mailgun", "k", 0, 0, 0, CIPHER);
        assertThat(tiny.mailgunMaxAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(tiny.contentRetention()).isEqualTo(Duration.ofMinutes(5));
        assertThat(tiny.metadataRetention()).isEqualTo(Duration.ofDays(1));
        ManagedMailSettings huge = new ManagedMailSettings("mail.robin.example", "mailgun", "k", 10_000_000, 10_000_000,
                10_000, CIPHER);
        assertThat(huge.mailgunMaxAge()).isEqualTo(Duration.ofHours(12));
        assertThat(huge.contentRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(huge.metadataRetention()).isEqualTo(Duration.ofDays(365));
        // Tokens outlive every request that could still pass the timestamp check.
        ManagedMailSettings normal = settings("mail.robin.example", "k", CIPHER);
        assertThat(normal.tokenRetention()).isGreaterThan(normal.mailgunMaxAge().plus(normal.futureSkew()));
    }

    @Test
    void aliasesAreRandomOpaqueAndFreeOfLookAlikeCharacters() {
        ManagedMailboxService service = new ManagedMailboxService(mock(JdbcTemplate.class), settings("mail.robin.example", "k", CIPHER));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String alias = service.newAlias();
            assertThat(alias).matches("^[a-km-np-z][a-km-np-z2-9]{15}$");
            seen.add(alias);
        }
        assertThat(seen).hasSize(2_000);
    }

    @Test
    void unconfiguredManagedMailNeitherTouchesTheDatabaseNorReservesAnything() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        ManagedMailboxService service = new ManagedMailboxService(db, settings("", "", new SecretCipher("")));
        ManagedMailboxService.Status status = service.status(java.util.UUID.randomUUID());
        assertThat(status.readiness()).isEqualTo(ManagedMailboxService.Readiness.NOT_CONFIGURED);
        assertThat(status.usableForRegistration()).isFalse();
        assertThatThrownBy(() -> service.ensureAddress(java.util.UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(db);
    }
}
