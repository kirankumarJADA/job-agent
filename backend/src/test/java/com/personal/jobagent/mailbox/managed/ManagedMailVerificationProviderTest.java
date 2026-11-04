package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.mailbox.MailboxVerificationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedMailVerificationProviderTest {

    private static final String DOMAIN = "mail.aplivenosi.com";
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID ADDRESS_ID = UUID.randomUUID();
    private static final String ALIAS_ADDRESS = "robin+test123456789@" + DOMAIN;

    private JdbcTemplate db;
    private ManagedMailboxService mailboxes;
    private ManagedMessageContent content;
    private ManagedMailVerificationProvider provider;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        mailboxes = mock(ManagedMailboxService.class);
        content = mock(ManagedMessageContent.class);
        provider = new ManagedMailVerificationProvider(db, mailboxes, content);
    }

    @Test
    void providerIdReturnsManaged() {
        assertThat(provider.providerId()).isEqualTo("managed");
    }

    @Test
    void connectedChecksUsableAddress() {
        when(mailboxes.usableAddress(PROFILE_ID)).thenReturn(Optional.empty());
        assertThat(provider.connected(PROFILE_ID)).isFalse();

        when(mailboxes.usableAddress(PROFILE_ID)).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "robin+test", DOMAIN, "ACTIVE")
        ));
        assertThat(provider.connected(PROFILE_ID)).isTrue();
    }

    @Test
    void handlesVerifiesAddressBelongsToOwner() {
        when(mailboxes.findAddress(PROFILE_ID)).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "robin+test123456789", DOMAIN, "ACTIVE")
        ));

        assertThat(provider.handles(PROFILE_ID, ALIAS_ADDRESS)).isTrue();
        assertThat(provider.handles(PROFILE_ID, "other@" + DOMAIN)).isFalse();
    }

    @Test
    void findVerificationReturnsCodeWhenMessageMatches() {
        UUID msgId = UUID.randomUUID();
        Instant now = Instant.now();

        when(mailboxes.usableAddress(PROFILE_ID)).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "robin+test123456789", DOMAIN, "ACTIVE")
        ));

        Map<String, Object> row = Map.of(
                "id", msgId,
                "envelope_recipient", ALIAS_ADDRESS,
                "sender_domain", "employer.example",
                "content_encrypted", "cipher-data",
                "received_at", Timestamp.from(now)
        );

        when(db.queryForList(contains("from managed_inbound_messages"), eq(PROFILE_ID), eq(ADDRESS_ID),
                any(Timestamp.class), any(Timestamp.class), anyInt())).thenReturn(List.of(row));

        when(content.decrypt("cipher-data")).thenReturn(
                new ManagedMessageContent.Content("Your verification code",
                        "Your verification code is 492817. Please enter it to complete sign-in.", List.of())
        );

        Optional<MailboxVerificationProvider.VerificationHit> hit = provider.findVerification(
                PROFILE_ID, "employer.example", ALIAS_ADDRESS, now.minusSeconds(60));

        assertThat(hit).isPresent();
        assertThat(hit.get().otp()).isEqualTo("492817");
        assertThat(hit.get().link()).isNull();
        assertThat(hit.get().sourceManagedMessageId()).isEqualTo(msgId);
    }

    @Test
    void findVerificationReturnsLinkWhenMessageHasNoCodeButValidLink() {
        UUID msgId = UUID.randomUUID();
        Instant now = Instant.now();

        when(mailboxes.usableAddress(PROFILE_ID)).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "robin+test123456789", DOMAIN, "ACTIVE")
        ));

        Map<String, Object> row = Map.of(
                "id", msgId,
                "envelope_recipient", ALIAS_ADDRESS,
                "sender_domain", "jobs.acme.com",
                "content_encrypted", "cipher-link",
                "received_at", Timestamp.from(now)
        );

        when(db.queryForList(contains("from managed_inbound_messages"), eq(PROFILE_ID), eq(ADDRESS_ID),
                any(Timestamp.class), any(Timestamp.class), anyInt())).thenReturn(List.of(row));

        when(content.decrypt("cipher-link")).thenReturn(
                new ManagedMessageContent.Content("Confirm your account",
                        "Click the link below to confirm your account.",
                        List.of("https://jobs.acme.com/confirm?token=secure123"))
        );

        Optional<MailboxVerificationProvider.VerificationHit> hit = provider.findVerification(
                PROFILE_ID, "acme.com", ALIAS_ADDRESS, now.minusSeconds(60));

        assertThat(hit).isPresent();
        assertThat(hit.get().otp()).isNull();
        assertThat(hit.get().link()).isEqualTo("https://jobs.acme.com/confirm?token=secure123");
        assertThat(hit.get().sourceManagedMessageId()).isEqualTo(msgId);
    }

    @Test
    void findVerificationRefusesAmbiguousMessageWithMultipleCodes() {
        UUID msgId = UUID.randomUUID();
        Instant now = Instant.now();

        when(mailboxes.usableAddress(PROFILE_ID)).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "robin+test123456789", DOMAIN, "ACTIVE")
        ));

        Map<String, Object> row = Map.of(
                "id", msgId,
                "envelope_recipient", ALIAS_ADDRESS,
                "sender_domain", "employer.example",
                "content_encrypted", "cipher-multi",
                "received_at", Timestamp.from(now)
        );

        when(db.queryForList(contains("from managed_inbound_messages"), eq(PROFILE_ID), eq(ADDRESS_ID),
                any(Timestamp.class), any(Timestamp.class), anyInt())).thenReturn(List.of(row));

        when(content.decrypt("cipher-multi")).thenReturn(
                new ManagedMessageContent.Content("Verification code",
                        "Your verification code is 123456 or confirmation code 654321.", List.of())
        );

        Optional<MailboxVerificationProvider.VerificationHit> hit = provider.findVerification(
                PROFILE_ID, "employer.example", ALIAS_ADDRESS, now.minusSeconds(60));

        assertThat(hit).isEmpty();
    }

    @Test
    void findVerificationRefusesMultipleMatchingCandidateMessages() {
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        Instant now = Instant.now();

        when(mailboxes.usableAddress(PROFILE_ID)).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "robin+test123456789", DOMAIN, "ACTIVE")
        ));

        Map<String, Object> row1 = Map.of(
                "id", msg1,
                "envelope_recipient", ALIAS_ADDRESS,
                "sender_domain", "employer.example",
                "content_encrypted", "cipher-1",
                "received_at", Timestamp.from(now)
        );
        Map<String, Object> row2 = Map.of(
                "id", msg2,
                "envelope_recipient", ALIAS_ADDRESS,
                "sender_domain", "employer.example",
                "content_encrypted", "cipher-2",
                "received_at", Timestamp.from(now)
        );

        when(db.queryForList(contains("from managed_inbound_messages"), eq(PROFILE_ID), eq(ADDRESS_ID),
                any(Timestamp.class), any(Timestamp.class), anyInt())).thenReturn(List.of(row1, row2));

        when(content.decrypt("cipher-1")).thenReturn(
                new ManagedMessageContent.Content("Code 1", "Your code is 111111.", List.of())
        );
        when(content.decrypt("cipher-2")).thenReturn(
                new ManagedMessageContent.Content("Code 2", "Your code is 222222.", List.of())
        );

        Optional<MailboxVerificationProvider.VerificationHit> hit = provider.findVerification(
                PROFILE_ID, "employer.example", ALIAS_ADDRESS, now.minusSeconds(60));

        assertThat(hit).isEmpty();
    }

    @Test
    void findVerificationSkipsMismatchedSenderDomain() {
        UUID msgId = UUID.randomUUID();
        Instant now = Instant.now();

        when(mailboxes.usableAddress(PROFILE_ID)).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "robin+test123456789", DOMAIN, "ACTIVE")
        ));

        Map<String, Object> row = Map.of(
                "id", msgId,
                "envelope_recipient", ALIAS_ADDRESS,
                "sender_domain", "unrelated-phishing.example",
                "content_encrypted", "cipher-data",
                "received_at", Timestamp.from(now)
        );

        when(db.queryForList(contains("from managed_inbound_messages"), eq(PROFILE_ID), eq(ADDRESS_ID),
                any(Timestamp.class), any(Timestamp.class), anyInt())).thenReturn(List.of(row));

        Optional<MailboxVerificationProvider.VerificationHit> hit = provider.findVerification(
                PROFILE_ID, "employer.example", ALIAS_ADDRESS, now.minusSeconds(60));

        assertThat(hit).isEmpty();
    }

    @Test
    void markResolvedPurgesEncryptedContent() {
        UUID msgId = UUID.randomUUID();
        MailboxVerificationProvider.VerificationHit hit = new MailboxVerificationProvider.VerificationHit(
                "managed", msgId.toString(), null, "492817", null, Instant.now(), msgId);

        provider.markResolved(PROFILE_ID, hit);

        verify(db).update(contains("update managed_inbound_messages"), eq(msgId), eq(PROFILE_ID));
    }
}
