package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AtsHandoffServiceTest {

    private HandoffSessionRepository handoffRepository;
    private AutomationPlanRepository planRepository;
    private AuditLogWriter audit;
    private AtsHandoffService service;

    private final UUID profileId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handoffRepository = mock(HandoffSessionRepository.class);
        planRepository = mock(AutomationPlanRepository.class);
        audit = mock(AuditLogWriter.class);
        service = new AtsHandoffService(handoffRepository, planRepository, audit, null);
    }

    @Test
    @DisplayName("initiateHandoff creates session and transitions plan to HANDOFF_REQUIRED")
    void testInitiateHandoff() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example/123", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffRepository.createSession(eq(profileId), eq(planId), eq(applicationId), anyString(), eq(HandoffBlockReason.CAPTCHA), eq(15)))
                .thenReturn(record);

        HandoffSessionRecord created = service.initiateHandoff(
                profileId, planId, applicationId, "https://jobs.lever.co/example/123", HandoffBlockReason.CAPTCHA, 15
        );

        assertThat(created).isEqualTo(record);
        verify(planRepository).transition(planId, "RUNNING", "HANDOFF_REQUIRED");
        verify(audit).write(argThat(entry -> "ATS_HANDOFF_INITIATED".equals(entry.action())));
    }

    @Test
    @DisplayName("confirmHandoffCompletion succeeds for valid pending session and resumes plan to READY_TO_SUBMIT")
    void testConfirmHandoffCompletionSuccess() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example/123", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffRepository.findById(profileId, sessionId)).thenReturn(Optional.of(record));
        when(handoffRepository.completeSession(profileId, sessionId)).thenReturn(true);
        when(planRepository.transition(planId, "HANDOFF_REQUIRED", "READY_TO_SUBMIT")).thenReturn(true);

        boolean result = service.confirmHandoffCompletion(profileId, sessionId);

        assertThat(result).isTrue();
        verify(handoffRepository).completeSession(profileId, sessionId);
        verify(planRepository).transition(planId, "HANDOFF_REQUIRED", "READY_TO_SUBMIT");
        verify(audit).write(argThat(entry -> "ATS_HANDOFF_COMPLETED".equals(entry.action())));
    }

    @Test
    @DisplayName("confirmHandoffCompletion refuses expired session and triggers stale expiry")
    void testConfirmHandoffCompletionExpired() {
        HandoffSessionRecord expiredRecord = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example/123", UUID.randomUUID(),
                Instant.now().minusSeconds(10), null, null, Instant.now().minusSeconds(900), Instant.now()
        );

        when(handoffRepository.findById(profileId, sessionId)).thenReturn(Optional.of(expiredRecord));

        boolean result = service.confirmHandoffCompletion(profileId, sessionId);

        assertThat(result).isFalse();
        verify(handoffRepository).expireStaleSessions();
        verify(handoffRepository, never()).completeSession(any(), any());
        verify(planRepository, never()).transition(any(), any(), any());
    }

    @Test
    @DisplayName("cancelHandoff marks session CANCELLED and plan FAILED")
    void testCancelHandoff() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.MFA,
                "https://workday.example/apply", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffRepository.findById(profileId, sessionId)).thenReturn(Optional.of(record));
        when(handoffRepository.cancelSession(profileId, sessionId)).thenReturn(true);

        boolean result = service.cancelHandoff(profileId, sessionId);

        assertThat(result).isTrue();
        verify(handoffRepository).cancelSession(profileId, sessionId);
        verify(planRepository).transition(planId, "HANDOFF_REQUIRED", "FAILED");
        verify(audit).write(argThat(entry -> "ATS_HANDOFF_CANCELLED".equals(entry.action())));
    }
}
