package com.personal.jobagent.adversarial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.gmail.GmailConnectionStore;
import com.personal.jobagent.mailbox.managed.ManagedMailboxService;
import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.track.ApplicationTrackingService;
import com.personal.jobagent.track.TrackEvidenceRecorder;
import com.personal.jobagent.track.TrackModels.MessageRecord;
import com.personal.jobagent.track.TrackRepository;
import com.personal.jobagent.track.TrackRepository.ApplicationHeader;
import com.personal.jobagent.track.TrackRepository.SnapshotParts;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("Adversarial QA Suite: Recruiter Tracking, Cross-Tenant Isolation & Follow-Up Lifecycle")
class RecruiterTrackingAdversarialTest {

    private TrackRepository repository;
    private TrackEvidenceRecorder recorder;
    private AuditLogWriter audit;
    private OwnerContext ownerContext;
    private GmailConnectionStore gmailConnections;
    private ApplicationTrackingService trackingService;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TrackRepository.class);
        recorder = Mockito.mock(TrackEvidenceRecorder.class);
        audit = Mockito.mock(AuditLogWriter.class);
        ownerContext = Mockito.mock(OwnerContext.class);
        gmailConnections = Mockito.mock(GmailConnectionStore.class);

        trackingService = new ApplicationTrackingService(
                repository,
                recorder,
                audit,
                ownerContext,
                new ObjectMapper(),
                gmailConnections
        );
    }

    private SnapshotParts snapshotWith(UUID appId, ApplicationHeader header, List<MessageRecord> messages) {
        return new SnapshotParts(
                header != null ? Map.of(appId, header) : Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                messages != null ? Map.of(appId, messages) : Map.of()
        );
    }

    // ── Cross-Tenant Isolation ───────────────────────────────────────────

    @Test
    @DisplayName("Adversarial: Cross-tenant application lookup returns Optional.empty, preventing metadata leakage")
    void testCrossTenantApplicationLookupBlocked() {
        UUID profileA = UUID.randomUUID();
        UUID profileB = UUID.randomUUID();
        UUID appOfB = UUID.randomUUID();

        // Repository returns empty when profileA attempts to access appOfB
        when(repository.snapshotParts(eq(profileA), eq(appOfB)))
                .thenReturn(snapshotWith(appOfB, null, null));

        Optional<Map<String, Object>> result = trackingService.view(profileA, appOfB);
        assertThat(result).isEmpty();

        // Null guards
        assertThat(trackingService.view(null, appOfB)).isEmpty();
        assertThat(trackingService.view(profileA, null)).isEmpty();
    }

    // ── Follow-Up Lifecycle Invariants ───────────────────────────────────

    @Test
    @DisplayName("Adversarial: Follow-up calculation triggers DUE alert after 14 days without employer response")
    void testFollowUpDueAfter14DaysWithoutResponse() {
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        Instant createdAt15DaysAgo = Instant.now().minus(Duration.ofDays(15));

        ApplicationHeader header = new ApplicationHeader(
                appId, UUID.randomUUID(), "APPLIED", createdAt15DaysAgo,
                "Senior Engineer", "Acme Corp", "London"
        );

        when(repository.snapshotParts(eq(profileId), eq(appId)))
                .thenReturn(snapshotWith(appId, header, List.of()));
        when(repository.receipts(any(), any())).thenReturn(List.of());
        when(repository.reviewQueue(any(), any(Integer.class))).thenReturn(List.of());
        when(repository.threadsForApplication(any(), any())).thenReturn(List.of());

        Optional<Map<String, Object>> view = trackingService.view(profileId, appId);
        assertThat(view).isPresent();

        @SuppressWarnings("unchecked")
        Map<String, Object> followUp = (Map<String, Object>) view.get().get("followUp");
        assertThat(followUp).isNotNull();
        assertThat(followUp.get("due")).isEqualTo(true);
        assertThat(followUp.get("status")).isEqualTo("DUE");
        assertThat(followUp.get("dueDate")).isEqualTo(createdAt15DaysAgo.plus(Duration.ofDays(14)).toString());
    }

    @Test
    @DisplayName("Adversarial: Follow-up is PENDING within 14 days without employer response")
    void testFollowUpPendingWithin14Days() {
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        Instant createdAt5DaysAgo = Instant.now().minus(Duration.ofDays(5));

        ApplicationHeader header = new ApplicationHeader(
                appId, UUID.randomUUID(), "APPLIED", createdAt5DaysAgo,
                "Senior Engineer", "Acme Corp", "London"
        );

        when(repository.snapshotParts(eq(profileId), eq(appId)))
                .thenReturn(snapshotWith(appId, header, List.of()));
        when(repository.receipts(any(), any())).thenReturn(List.of());
        when(repository.reviewQueue(any(), any(Integer.class))).thenReturn(List.of());
        when(repository.threadsForApplication(any(), any())).thenReturn(List.of());

        Optional<Map<String, Object>> view = trackingService.view(profileId, appId);
        assertThat(view).isPresent();

        @SuppressWarnings("unchecked")
        Map<String, Object> followUp = (Map<String, Object>) view.get().get("followUp");
        assertThat(followUp).isNotNull();
        assertThat(followUp.get("due")).isEqualTo(false);
        assertThat(followUp.get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Adversarial: Once recruiter outreach or interview occurs, follow-up status transitions to NOT_NEEDED")
    void testFollowUpTransitionsToNotNeededOnRecruiterActivity() {
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        Instant createdAt20DaysAgo = Instant.now().minus(Duration.ofDays(20));

        ApplicationHeader header = new ApplicationHeader(
                appId, UUID.randomUUID(), "APPLIED", createdAt20DaysAgo,
                "Senior Engineer", "Acme Corp", "London"
        );

        MessageRecord recruiterMessage = new MessageRecord(
                UUID.randomUUID(), LinkStatus.LINKED, true, true,
                EmailCategory.INTERVIEW_INVITATION, EmailCategory.INTERVIEW_INVITATION,
                Instant.now().minus(Duration.ofDays(2)), true, Instant.now()
        );

        when(repository.snapshotParts(eq(profileId), eq(appId)))
                .thenReturn(snapshotWith(appId, header, List.of(recruiterMessage)));
        when(repository.receipts(any(), any())).thenReturn(List.of());
        when(repository.reviewQueue(any(), any(Integer.class))).thenReturn(List.of());
        when(repository.threadsForApplication(any(), any())).thenReturn(List.of());

        Optional<Map<String, Object>> view = trackingService.view(profileId, appId);
        assertThat(view).isPresent();

        @SuppressWarnings("unchecked")
        Map<String, Object> followUp = (Map<String, Object>) view.get().get("followUp");
        assertThat(followUp).isNotNull();
        assertThat(followUp.get("due")).isEqualTo(false);
        assertThat(followUp.get("status")).isEqualTo("NOT_NEEDED");
    }

    @Test
    @DisplayName("Gate 0D: mailboxConnected recognises active owner-scoped Managed Mail and fails closed when disabled/misconfigured/cross-tenant")
    void testMailboxConnectedRecognizesActiveManagedMailAndFailsClosed() {
        UUID profileId = UUID.randomUUID();
        UUID otherProfileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        ManagedMailboxService mailboxes = Mockito.mock(ManagedMailboxService.class);
        ApplicationTrackingService serviceWithManaged = new ApplicationTrackingService(
                repository, recorder, audit, ownerContext, new ObjectMapper(), gmailConnections, mailboxes);

        when(ownerContext.profileIdOrNull()).thenReturn(profileId);
        when(gmailConnections.find(profileId)).thenReturn(Optional.empty());

        // 1. Active domain-configured Managed Mail for owner -> connected = true
        when(mailboxes.status(profileId)).thenReturn(new ManagedMailboxService.Status(
                ManagedMailboxService.Readiness.ACTIVE,
                "mail.aplivenosi.com",
                new ManagedMailboxService.ManagedAddress(UUID.randomUUID(), profileId, "robin+abc", "mail.aplivenosi.com", "ACTIVE"),
                null, List.of()));
        assertThat(serviceWithManaged.mailboxConnected()).isTrue();

        ApplicationHeader header = new ApplicationHeader(
                appId, UUID.randomUUID(), "SUBMITTED", Instant.now().minus(Duration.ofDays(1)),
                "Engineer", "Acme", "Remote");
        when(repository.snapshotParts(eq(profileId), eq(appId))).thenReturn(snapshotWith(appId, header, List.of()));
        when(repository.receipts(any(), any())).thenReturn(List.of());
        when(repository.reviewQueue(any(), any(Integer.class))).thenReturn(List.of());
        when(repository.threadsForApplication(any(), any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> mailboxView = (Map<String, Object>) serviceWithManaged.view(profileId, appId).orElseThrow().get("mailbox");
        assertThat(mailboxView.get("connected")).isEqualTo(true);
        assertThat(String.valueOf(mailboxView.get("note"))).contains("Robin-managed");

        // 2. DISABLED address -> fail closed (false)
        when(mailboxes.status(profileId)).thenReturn(new ManagedMailboxService.Status(
                ManagedMailboxService.Readiness.ACTIVE,
                "mail.aplivenosi.com",
                new ManagedMailboxService.ManagedAddress(UUID.randomUUID(), profileId, "robin+abc", "mail.aplivenosi.com", "DISABLED"),
                null, List.of()));
        assertThat(serviceWithManaged.mailboxConnected()).isFalse();

        // 3. NOT_CONFIGURED domain -> fail closed (false)
        when(mailboxes.status(profileId)).thenReturn(new ManagedMailboxService.Status(
                ManagedMailboxService.Readiness.NOT_CONFIGURED,
                "",
                new ManagedMailboxService.ManagedAddress(UUID.randomUUID(), profileId, "robin+abc", "mail.aplivenosi.com", "ACTIVE"),
                null, List.of("APP_MANAGED_MAIL_DOMAIN")));
        assertThat(serviceWithManaged.mailboxConnected()).isFalse();

        // 4. Another profile's address leaking back -> fail closed (false)
        when(mailboxes.status(profileId)).thenReturn(new ManagedMailboxService.Status(
                ManagedMailboxService.Readiness.ACTIVE,
                "mail.aplivenosi.com",
                new ManagedMailboxService.ManagedAddress(UUID.randomUUID(), otherProfileId, "robin+other", "mail.aplivenosi.com", "ACTIVE"),
                null, List.of()));
        assertThat(serviceWithManaged.mailboxConnected()).isFalse();
    }
}
