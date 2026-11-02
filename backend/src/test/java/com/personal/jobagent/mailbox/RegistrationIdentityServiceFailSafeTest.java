package com.personal.jobagent.mailbox;

import com.personal.jobagent.mailbox.managed.ManagedMailVerificationProvider;
import com.personal.jobagent.mailbox.managed.ManagedMailboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationIdentityServiceFailSafeTest {

    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final String PERSONAL_EMAIL = "personal.candidate@gmail.com";
    private static final String MANAGED_EMAIL = "robin+cand123456789012@mail.aplivenosi.com";

    private JdbcTemplate db;
    private ManagedMailboxService mailboxes;
    private ManagedMailVerificationProvider managedProvider;
    private GmailMailboxVerificationProvider gmailProvider;
    private RegistrationIdentityService service;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        mailboxes = mock(ManagedMailboxService.class);
        managedProvider = mock(ManagedMailVerificationProvider.class);
        gmailProvider = mock(GmailMailboxVerificationProvider.class);
        service = new RegistrationIdentityService(db, mailboxes, managedProvider, gmailProvider);
    }

    @ParameterizedTest(name = "Fail-safe: {0} throws ManagedMailboxNotReadyException when pending receiving verification")
    @ValueSource(strings = {
            "https://boards.greenhouse.io/acmepayments/jobs/10001",
            "https://jobs.ashbyhq.com/acmepayments/20002",
            "https://jobs.lever.co/acmepayments/30003",
            "https://recruit.zoho.com/recruit/portal/acmepayments/jobs/40004",
            "https://acme.wd3.myworkdayjobs.com/en-US/AcmeCareers/job/50005"
    })
    @DisplayName("Fail-safe prevents personal email leakage across all 5 ATS platforms when receiving is unverified")
    void failSafePreventsPersonalEmailLeakageAcrossAll5AtsPlatformsWhenPendingVerification(String atsUrl) {
        // Candidate has reserved a managed address
        ManagedMailboxService.ManagedAddress candidateAddress = new ManagedMailboxService.ManagedAddress(
                UUID.randomUUID(), PROFILE_ID, "robin+cand123456789012", "mail.aplivenosi.com", "ACTIVE");
        when(mailboxes.findAddress(PROFILE_ID)).thenReturn(Optional.of(candidateAddress));

        // Deployment readiness is PENDING_RECEIVING_VERIFICATION
        when(mailboxes.readiness()).thenReturn(ManagedMailboxService.Readiness.PENDING_RECEIVING_VERIFICATION);

        // No pre-existing identity in db
        when(db.queryForList(anyString(), eq(PROFILE_ID), anyString())).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.resolveApplicationContactEmail(PROFILE_ID, atsUrl, PERSONAL_EMAIL))
                .isInstanceOf(RegistrationIdentityService.ManagedMailboxNotReadyException.class)
                .hasMessageContaining("PENDING_RECEIVING_VERIFICATION");
    }

    @Test
    @DisplayName("Fail-safe throws when managed mail is unconfigured and address was reserved")
    void failSafeThrowsWhenUnconfiguredAndAddressWasReserved() {
        ManagedMailboxService.ManagedAddress candidateAddress = new ManagedMailboxService.ManagedAddress(
                UUID.randomUUID(), PROFILE_ID, "robin+cand123456789012", "mail.aplivenosi.com", "ACTIVE");
        when(mailboxes.findAddress(PROFILE_ID)).thenReturn(Optional.of(candidateAddress));
        when(mailboxes.readiness()).thenReturn(ManagedMailboxService.Readiness.NOT_CONFIGURED);
        when(db.queryForList(anyString(), eq(PROFILE_ID), anyString())).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.resolveApplicationContactEmail(
                PROFILE_ID, "https://boards.greenhouse.io/acme/jobs/1", PERSONAL_EMAIL))
                .isInstanceOf(RegistrationIdentityService.ManagedMailboxNotReadyException.class)
                .hasMessageContaining("NOT_CONFIGURED");
    }

    @Test
    @DisplayName("Fail-safe throws when reserved managed address is disabled")
    void failSafeThrowsWhenAddressIsDisabled() {
        ManagedMailboxService.ManagedAddress candidateAddress = new ManagedMailboxService.ManagedAddress(
                UUID.randomUUID(), PROFILE_ID, "robin+cand123456789012", "mail.aplivenosi.com", "DISABLED");
        when(mailboxes.findAddress(PROFILE_ID)).thenReturn(Optional.of(candidateAddress));
        when(mailboxes.readiness()).thenReturn(ManagedMailboxService.Readiness.ACTIVE);
        when(db.queryForList(anyString(), eq(PROFILE_ID), anyString())).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.resolveApplicationContactEmail(
                PROFILE_ID, "https://jobs.ashbyhq.com/acme/1", PERSONAL_EMAIL))
                .isInstanceOf(RegistrationIdentityService.ManagedMailboxNotReadyException.class)
                .hasMessageContaining("ADDRESS_DISABLED");
    }

    @Test
    @DisplayName("Fail-safe throws when employer identity exists with ROBIN_MANAGED but readiness is not active")
    void failSafeThrowsWhenExistingIdentityIsManagedButReadinessNotActive() {
        when(db.queryForList(anyString(), eq(PROFILE_ID), eq("acme.com"))).thenReturn(List.of(
                Map.of(
                        "id", UUID.randomUUID(),
                        "applicant_email", MANAGED_EMAIL,
                        "account_status", "ACTIVE",
                        "provider", "ROBIN_MANAGED"
                )
        ));
        when(mailboxes.readiness()).thenReturn(ManagedMailboxService.Readiness.PENDING_RECEIVING_VERIFICATION);

        assertThatThrownBy(() -> service.resolveApplicationContactEmail(
                PROFILE_ID, "https://acme.com/jobs/123", PERSONAL_EMAIL))
                .isInstanceOf(RegistrationIdentityService.ManagedMailboxNotReadyException.class)
                .hasMessageContaining("PENDING_RECEIVING_VERIFICATION");
    }

    @Test
    @DisplayName("Returns managed address and records identity when active and address is reserved")
    void returnsManagedAddressWhenActive() {
        ManagedMailboxService.ManagedAddress candidateAddress = new ManagedMailboxService.ManagedAddress(
                UUID.randomUUID(), PROFILE_ID, "robin+cand123456789012", "mail.aplivenosi.com", "ACTIVE");
        when(mailboxes.findAddress(PROFILE_ID)).thenReturn(Optional.of(candidateAddress));
        when(mailboxes.readiness()).thenReturn(ManagedMailboxService.Readiness.ACTIVE);
        when(db.queryForList(anyString(), eq(PROFILE_ID), anyString())).thenReturn(Collections.emptyList());

        String email = service.resolveApplicationContactEmail(
                PROFILE_ID, "https://jobs.ashbyhq.com/acme/1", PERSONAL_EMAIL);

        assertThat(email).isEqualTo(MANAGED_EMAIL);
    }

    @Test
    @DisplayName("Candidate without reserved address falls back to personal email when unconfigured")
    void candidateWithoutReservedAddressFallsBackToPersonalEmailWhenUnconfigured() {
        when(mailboxes.findAddress(PROFILE_ID)).thenReturn(Optional.empty());
        when(mailboxes.readiness()).thenReturn(ManagedMailboxService.Readiness.NOT_CONFIGURED);
        when(db.queryForList(anyString(), eq(PROFILE_ID), anyString())).thenReturn(Collections.emptyList());

        String email = service.resolveApplicationContactEmail(
                PROFILE_ID, "https://jobs.ashbyhq.com/acme/1", PERSONAL_EMAIL);

        assertThat(email).isEqualTo(PERSONAL_EMAIL);
    }
}
