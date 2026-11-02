package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedInboundForwardingTest {

    private static final String DOMAIN = "mail.aplivenosi.com";
    private static final String KEY = "test-signing-key";
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID ADDRESS_ID = UUID.randomUUID();

    private JdbcTemplate db;
    private ManagedMailSettings settings;
    private ManagedMailboxService mailboxes;
    private ManagedMessageContent content;
    private EmailIntelligenceService emailIntelligence;
    private ObjectProvider<EmailIntelligenceService> provider;
    private ManagedInboundService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        db = mock(JdbcTemplate.class);
        settings = new ManagedMailSettings(DOMAIN, "mailgun", KEY, 900, 120, 30,
                new SecretCipher("6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U="));
        mailboxes = mock(ManagedMailboxService.class);
        content = mock(ManagedMessageContent.class);
        emailIntelligence = mock(EmailIntelligenceService.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(emailIntelligence);

        service = new ManagedInboundService(db, settings, mailboxes, content, provider);
    }

    private MailgunInboundParser.Result validParsed(String from, String to, String subject, String body,
                                                   boolean authFailed, boolean spam) {
        InboundMailMessage msg = new InboundMailMessage(
                "msg-123",
                to.toLowerCase(),
                "bounces@employer.example",
                from,
                from,
                subject,
                body,
                List.of(),
                "<msg-123@employer.example>",
                "Sat, 11 Oct 2026 10:00:00 +0000",
                authFailed ? List.of("fail") : List.of("pass"),
                authFailed ? List.of("fail") : List.of("pass"),
                spam ? List.of("yes") : List.of("no")
        );
        return new MailgunInboundParser.Result(msg, null);
    }

    @Test
    void forwardsStoredMessageToEmailIntelligenceWithConnectorChannelAndSenderAuthentication() {
        String to = "alias123@" + DOMAIN;
        var parsed = validParsed("recruiter@employer.example", to, "Your Application at Acme",
                "Thank you for applying. We received your application.", false, false);

        when(db.update(contains("managed_mail_webhook_tokens"), any(), any(), any(), any())).thenReturn(1);
        when(mailboxes.routeEnvelopeRecipient(to.toLowerCase())).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "alias123", DOMAIN, "ACTIVE")
        ));
        when(content.encrypt(any())).thenReturn("encrypted-bytes");
        when(db.update(contains("insert into managed_inbound_messages"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        var outcome = service.accept(parsed, "token-1", Instant.now(), "sha-payload");

        assertThat(outcome).isEqualTo(ManagedInboundService.Outcome.STORED);

        ArgumentCaptor<EmailIntelligenceService.ConnectorMessage> captor =
                ArgumentCaptor.forClass(EmailIntelligenceService.ConnectorMessage.class);
        verify(emailIntelligence).ingestFromMailboxConnector(eq(PROFILE_ID), captor.capture());

        var forwarded = captor.getValue();
        assertThat(forwarded.providerId()).isEqualTo("msg-123");
        assertThat(forwarded.from()).isEqualTo("recruiter@employer.example");
        assertThat(forwarded.to()).isEqualTo(to.toLowerCase());
        assertThat(forwarded.subject()).isEqualTo("Your Application at Acme");
        assertThat(forwarded.body()).contains("Thank you for applying");
        assertThat(forwarded.authentication()).isNotNull();
        assertThat(forwarded.authentication().dkim()).isEqualTo("pass");
        assertThat(forwarded.authentication().dkimDomain()).isEqualTo("employer.example");
        assertThat(forwarded.authentication().spf()).isEqualTo("pass");
    }

    @Test
    void doesNotForwardWhenMessageFailedAuthenticationOrIsSpam() {
        String to = "alias123@" + DOMAIN;
        var parsed = validParsed("spammer@evil.example", to, "Spam Subject", "Click here", true, true);

        when(db.update(contains("managed_mail_webhook_tokens"), any(), any(), any(), any())).thenReturn(1);
        when(mailboxes.routeEnvelopeRecipient(to.toLowerCase())).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "alias123", DOMAIN, "ACTIVE")
        ));
        when(db.update(contains("insert into managed_inbound_messages"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        var outcome = service.accept(parsed, "token-2", Instant.now(), "sha-payload");

        assertThat(outcome).isEqualTo(ManagedInboundService.Outcome.STORED);
        verify(emailIntelligence, never()).ingestFromMailboxConnector(any(), any());
    }

    @Test
    void doesNotForwardWhenMessageIsDuplicate() {
        String to = "alias123@" + DOMAIN;
        var parsed = validParsed("recruiter@employer.example", to, "Duplicate Subject", "Body", false, false);

        when(db.update(contains("managed_mail_webhook_tokens"), any(), any(), any(), any())).thenReturn(1);
        when(mailboxes.routeEnvelopeRecipient(to.toLowerCase())).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(ADDRESS_ID, PROFILE_ID, "alias123", DOMAIN, "ACTIVE")
        ));
        when(content.encrypt(any())).thenReturn("encrypted-bytes");
        // stored returns 0 (duplicate message)
        when(db.update(contains("insert into managed_inbound_messages"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        var outcome = service.accept(parsed, "token-3", Instant.now(), "sha-payload");

        assertThat(outcome).isEqualTo(ManagedInboundService.Outcome.DUPLICATE_MESSAGE);
        verify(emailIntelligence, never()).ingestFromMailboxConnector(any(), any());
    }
}
