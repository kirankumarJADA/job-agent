package com.personal.jobagent.mailbox;

import com.personal.jobagent.mailbox.MailboxVerificationProvider.VerificationHit;
import com.personal.jobagent.mailbox.managed.ManagedMailVerificationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The mailbox a verification is read from is the one the account was registered with. */
class RoutingMailboxVerificationProviderTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final String MANAGED = "u7kq2m9x4vb3tn5p@mail.robin.example";
    private static final String GMAIL = "candidate@gmail.example";
    private static final Instant AFTER = Instant.parse("2026-10-11T10:00:00Z");

    private ManagedMailVerificationProvider managed;
    private GmailMailboxVerificationProvider gmail;
    private RoutingMailboxVerificationProvider router;

    @BeforeEach
    void setUp() {
        managed = mock(ManagedMailVerificationProvider.class);
        gmail = mock(GmailMailboxVerificationProvider.class);
        when(managed.providerId()).thenReturn(ManagedMailVerificationProvider.PROVIDER_ID);
        when(gmail.providerId()).thenReturn("gmail");
        when(managed.handles(OWNER, MANAGED)).thenReturn(true);
        when(gmail.handles(OWNER, GMAIL)).thenReturn(true);
        router = new RoutingMailboxVerificationProvider(managed, gmail);
    }

    @Test
    void withoutAMailboxTheManagedAddressIsTheDefaultAndGmailIsNeverAsked() {
        VerificationHit hit = new VerificationHit("managed", "m-1", null, "482913", null, AFTER, UUID.randomUUID());
        when(managed.findVerification(OWNER, "employer.example", null, AFTER)).thenReturn(Optional.of(hit));

        assertThat(router.findVerification(OWNER, "employer.example", null, AFTER)).contains(hit);
        assertThat(router.providerId()).isEqualTo("managed");
        verify(gmail, never()).findVerification(any(), anyString(), any(), any());
    }

    @Test
    void eachMailboxIsAnsweredOnlyByTheProviderThatOwnsIt() {
        router.findVerification(OWNER, "employer.example", MANAGED, AFTER);
        verify(managed).findVerification(OWNER, "employer.example", MANAGED, AFTER);
        verify(gmail, never()).findVerification(any(), anyString(), eq(MANAGED), any());

        router.findVerification(OWNER, "employer.example", GMAIL, AFTER);
        verify(gmail).findVerification(OWNER, "employer.example", GMAIL, AFTER);
        verify(managed, never()).findVerification(any(), anyString(), eq(GMAIL), any());
    }

    @Test
    void aMailboxNoProviderCanReadPausesInsteadOfGuessing() {
        assertThat(router.findVerification(OWNER, "employer.example", "someone@elsewhere.example", AFTER)).isEmpty();
        assertThat(router.handles(OWNER, "someone@elsewhere.example")).isFalse();
        verify(managed, never()).findVerification(any(), anyString(), any(), any());
        verify(gmail, never()).findVerification(any(), anyString(), any(), any());
    }

    @Test
    void resolutionIsRetiredByTheProviderTheHitCameFrom() {
        VerificationHit managedHit = new VerificationHit("managed", "m-1", null, "482913", null, AFTER, UUID.randomUUID());
        VerificationHit gmailHit = new VerificationHit("gmail", "g-1", UUID.randomUUID(), "551200", null, AFTER);
        router.markResolved(OWNER, managedHit);
        router.markResolved(OWNER, gmailHit);
        verify(managed).markResolved(OWNER, managedHit);
        verify(managed, never()).markResolved(OWNER, gmailHit);
        verify(gmail).markResolved(OWNER, gmailHit);
        verify(gmail, never()).markResolved(OWNER, managedHit);
    }

    @Test
    void connectedWhenEitherMailboxCanBeRead() {
        when(managed.connected(OWNER)).thenReturn(false);
        when(gmail.connected(OWNER)).thenReturn(false);
        assertThat(router.connected(OWNER)).isFalse();
        when(managed.connected(OWNER)).thenReturn(true);
        assertThat(router.connected(OWNER)).isTrue();
    }
}
