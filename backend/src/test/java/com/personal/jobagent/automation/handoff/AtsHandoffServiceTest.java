package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AtsHandoffServiceTest {

    private HandoffSessionRepository handoffRepository;
    private AutomationPlanRepository planRepository;
    private AuditLogWriter audit;
    private ApplicationStatusService applicationStatus;
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
        applicationStatus = mock(ApplicationStatusService.class);
        service = new AtsHandoffService(handoffRepository, planRepository, audit, null, applicationStatus);

        var planRow = new AutomationPlanRepository.PlanRow(
                planId, applicationId, "https://jobs.lever.co/example/123", "RUNNING",
                Map.of(), List.of(), false, Instant.now(), Instant.now()
        );
        when(planRepository.findById(planId)).thenReturn(Optional.of(planRow));
        when(planRepository.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));
        when(handoffRepository.findProfileIdForApplication(applicationId)).thenReturn(Optional.of(profileId));
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
        when(planRepository.transition(planId, "RUNNING", "HANDOFF_REQUIRED")).thenReturn(true);

        HandoffSessionRecord created = service.initiateHandoff(
                profileId, planId, applicationId, "https://jobs.lever.co/example/123", HandoffBlockReason.CAPTCHA, 15
        );

        assertThat(created).isEqualTo(record);
        verify(planRepository).transition(planId, "RUNNING", "HANDOFF_REQUIRED");
        verify(audit).write(argThat(entry -> "ATS_HANDOFF_INITIATED".equals(entry.action())
                && HandoffSessionResponse.from(record).equals(entry.afterState())));
    }

    @Test
    @DisplayName("initiateHandoff falls back to BLOCKED_ANTI_BOT transition when RUNNING transition returns false")
    void testInitiateHandoffFallbackBlockedAntiBot() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example/123", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffRepository.createSession(eq(profileId), eq(planId), eq(applicationId), anyString(), eq(HandoffBlockReason.CAPTCHA), eq(15)))
                .thenReturn(record);
        when(planRepository.transition(planId, "RUNNING", "HANDOFF_REQUIRED")).thenReturn(false);
        when(planRepository.transition(planId, "BLOCKED_ANTI_BOT", "HANDOFF_REQUIRED")).thenReturn(true);

        HandoffSessionRecord created = service.initiateHandoff(
                profileId, planId, applicationId, "https://jobs.lever.co/example/123", HandoffBlockReason.CAPTCHA, 15
        );

        assertThat(created).isEqualTo(record);
        verify(planRepository).transition(planId, "RUNNING", "HANDOFF_REQUIRED");
        verify(planRepository).transition(planId, "BLOCKED_ANTI_BOT", "HANDOFF_REQUIRED");
    }

    @Test
    @DisplayName("isPlanOwnedBy delegates to planRepository.ownerOfPlan")
    void testIsPlanOwnedBy() {
        when(planRepository.ownerOfPlan(planId)).thenReturn(Optional.of(profileId));

        assertThat(service.isPlanOwnedBy(planId, profileId)).isTrue();
        assertThat(service.isPlanOwnedBy(planId, UUID.randomUUID())).isFalse();
        assertThat(service.isPlanOwnedBy(null, profileId)).isFalse();
    }

    @Test
    @DisplayName("confirmHandoffCompletion succeeds for valid pending session, transitions plan to terminal COMPLETED and applies application status machine")
    void testConfirmHandoffCompletionSuccess() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example/123", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffRepository.findById(profileId, sessionId)).thenReturn(Optional.of(record));
        when(handoffRepository.completeSession(profileId, sessionId)).thenReturn(true);
        when(handoffRepository.getApplicationStatus(applicationId)).thenReturn(Optional.of("READY_TO_APPLY"));
        when(planRepository.transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("COMPLETED"), anyList())).thenReturn(true);

        boolean result = service.confirmHandoffCompletion(profileId, sessionId);

        assertThat(result).isTrue();
        verify(handoffRepository).completeSession(profileId, sessionId);
        verify(applicationStatus).apply(eq(profileId), eq(applicationId), eq("APPLICATION_STARTED"), anyString(), eq("candidate"), anyMap());
        verify(applicationStatus).apply(eq(profileId), eq(applicationId), eq("APPLICATION_SUBMITTED"), anyString(), eq("candidate"), anyMap());
        verify(handoffRepository).recordSubmissionTimestamp(applicationId);
        verify(planRepository).transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("COMPLETED"), anyList());
        verify(audit).write(argThat(entry -> "ATS_HANDOFF_COMPLETED".equals(entry.action())));
    }

    @Test
    @DisplayName("confirmHandoffCompletion for already-submitted application maintains terminal status without state regression")
    void testConfirmHandoffCompletionAlreadySubmittedApplication() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example/123", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffRepository.findById(profileId, sessionId)).thenReturn(Optional.of(record));
        when(handoffRepository.completeSession(profileId, sessionId)).thenReturn(true);
        when(handoffRepository.getApplicationStatus(applicationId)).thenReturn(Optional.of("APPLICATION_SUBMITTED"));
        when(planRepository.transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("COMPLETED"), anyList())).thenReturn(true);

        boolean result = service.confirmHandoffCompletion(profileId, sessionId);

        assertThat(result).isTrue();
        verify(applicationStatus, never()).apply(any(), any(), any(), any(), any(), any());
        verify(handoffRepository).recordSubmissionTimestamp(applicationId);
        verify(planRepository).transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("COMPLETED"), anyList());
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
        verify(handoffRepository).expireStaleSessionsAndGetPlanIds();
        verify(handoffRepository, never()).completeSession(any(), any());
        verify(planRepository, never()).transitionWithResult(any(), any(), any(), any());
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
        when(planRepository.transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("FAILED"), anyList())).thenReturn(true);

        boolean result = service.cancelHandoff(profileId, sessionId);

        assertThat(result).isTrue();
        verify(handoffRepository).cancelSession(profileId, sessionId);
        verify(planRepository).transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("FAILED"), anyList());
        verify(audit).write(argThat(entry -> "ATS_HANDOFF_CANCELLED".equals(entry.action())));
    }

    @Test
    @DisplayName("confirmHandoffCompletion throws IllegalStateException when plan transition fails")
    void testConfirmHandoffCompletionThrowsWhenPlanTransitionFails() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example/123", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffRepository.findById(profileId, sessionId)).thenReturn(Optional.of(record));
        when(handoffRepository.completeSession(profileId, sessionId)).thenReturn(true);
        when(handoffRepository.getApplicationStatus(applicationId)).thenReturn(Optional.of("READY_TO_APPLY"));
        when(planRepository.transitionWithResult(eq(planId), anyString(), eq("COMPLETED"), anyList())).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirmHandoffCompletion(profileId, sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to transition automation plan");
    }

    @Test
    @DisplayName("cancelHandoff throws IllegalStateException when plan transition fails")
    void testCancelHandoffThrowsWhenPlanTransitionFails() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.MFA,
                "https://workday.example/apply", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffRepository.findById(profileId, sessionId)).thenReturn(Optional.of(record));
        when(handoffRepository.cancelSession(profileId, sessionId)).thenReturn(true);
        when(planRepository.transitionWithResult(eq(planId), anyString(), eq("FAILED"), anyList())).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelHandoff(profileId, sessionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to transition automation plan");
    }

    @Test
    @DisplayName("expireStaleSessions transitions affected plans to FAILED")
    void testExpireStaleSessions() {
        when(handoffRepository.expireStaleSessionsAndGetPlanIds()).thenReturn(List.of(planId));
        when(planRepository.transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("FAILED"), anyList())).thenReturn(true);

        int expiredCount = service.expireStaleSessions();

        assertThat(expiredCount).isEqualTo(1);
        verify(planRepository).transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("FAILED"), anyList());
        verify(audit).write(argThat(entry -> "ATS_HANDOFF_EXPIRED".equals(entry.action())
                && "HANDOFF_REQUIRED".equals(entry.beforeState())
                && "FAILED".equals(entry.afterState())));
    }

    @Test
    @DisplayName("expireStaleSessions falls back to BLOCKED_ANTI_BOT transition when HANDOFF_REQUIRED returns false")
    void testExpireStaleSessionsFallbackBlockedAntiBot() {
        when(handoffRepository.expireStaleSessionsAndGetPlanIds()).thenReturn(List.of(planId));
        when(planRepository.transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("FAILED"), anyList())).thenReturn(false);
        when(planRepository.transitionWithResult(eq(planId), eq("BLOCKED_ANTI_BOT"), eq("FAILED"), anyList())).thenReturn(true);

        int expiredCount = service.expireStaleSessions();

        assertThat(expiredCount).isEqualTo(1);
        verify(planRepository).transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("FAILED"), anyList());
        verify(planRepository).transitionWithResult(eq(planId), eq("BLOCKED_ANTI_BOT"), eq("FAILED"), anyList());
        verify(audit).write(argThat(entry -> "ATS_HANDOFF_EXPIRED".equals(entry.action())
                && "BLOCKED_ANTI_BOT".equals(entry.beforeState())
                && "FAILED".equals(entry.afterState())));
    }

    @Test
    @DisplayName("expireStaleSessions is idempotent when plan is already FAILED and does not write false audit record")
    void testExpireStaleSessionsIdempotentAlreadyFailed() {
        when(handoffRepository.expireStaleSessionsAndGetPlanIds()).thenReturn(List.of(planId));
        when(planRepository.transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("FAILED"), anyList())).thenReturn(false);
        when(planRepository.transitionWithResult(eq(planId), eq("BLOCKED_ANTI_BOT"), eq("FAILED"), anyList())).thenReturn(false);

        var planRow = mock(com.personal.jobagent.automation.AutomationPlanRepository.PlanRow.class);
        when(planRow.status()).thenReturn("FAILED");
        when(planRepository.findById(planId)).thenReturn(Optional.of(planRow));

        int expiredCount = service.expireStaleSessions();

        assertThat(expiredCount).isEqualTo(1);
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("expireStaleSessions throws IllegalStateException when plan is in unexpected state and does not write audit record")
    void testExpireStaleSessionsThrowsWhenUnexpectedStatus() {
        when(handoffRepository.expireStaleSessionsAndGetPlanIds()).thenReturn(List.of(planId));
        when(planRepository.transitionWithResult(eq(planId), eq("HANDOFF_REQUIRED"), eq("FAILED"), anyList())).thenReturn(false);
        when(planRepository.transitionWithResult(eq(planId), eq("BLOCKED_ANTI_BOT"), eq("FAILED"), anyList())).thenReturn(false);

        var planRow = mock(com.personal.jobagent.automation.AutomationPlanRepository.PlanRow.class);
        when(planRow.status()).thenReturn("RUNNING");
        when(planRepository.findById(planId)).thenReturn(Optional.of(planRow));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.expireStaleSessions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to transition expired plan " + planId + " to FAILED");

        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("validateTargetUrl accepts valid HTTPS URLs")
    void testValidateTargetUrlSuccess() {
        AtsHandoffService.validateTargetUrl("https://jobs.lever.co/example/123");
        AtsHandoffService.validateTargetUrl("https://workday.com/apply");
        AtsHandoffService.validateTargetUrl("https://sub.domain.co.uk/apply?job=456#form");
    }

    @Test
    @DisplayName("validateTargetUrl rejects invalid, malformed, or dangerous URLs")
    void testValidateTargetUrlRejections() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl(""))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("   "))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("http://insecure.com/apply"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("data:text/html,<script>alert(1)</script>"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("blob:https://jobs.lever.co/uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("//jobs.lever.co/example"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("https://user:password@jobs.lever.co/example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedded credentials");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("https://user@jobs.lever.co/example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedded credentials");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("https://"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtsHandoffService.validateTargetUrl("https:///path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid host");
    }

    @Test
    @DisplayName("initiateHandoff rejects invalid target URL and creates no session or transition")
    void testInitiateHandoffRejectsInvalidUrl() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.initiateHandoff(profileId, planId, applicationId, "javascript:alert(1)", HandoffBlockReason.CAPTCHA, 15)
        ).isInstanceOf(IllegalArgumentException.class);

        verify(handoffRepository, never()).createSession(any(), any(), any(), any(), any(), anyInt());
        verify(planRepository, never()).transition(any(), any(), any());
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("initiateHandoff throws when plan is not found and creates no session or transition")
    void testInitiateHandoffThrowsWhenPlanNotFound() {
        when(planRepository.findById(planId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.initiateHandoff(profileId, planId, applicationId, "https://jobs.lever.co/apply", HandoffBlockReason.CAPTCHA, 15)
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Automation plan not found");

        verify(handoffRepository, never()).createSession(any(), any(), any(), any(), any(), anyInt());
        verify(planRepository, never()).transition(any(), any(), any());
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("initiateHandoff throws when plan belongs to a different application")
    void testInitiateHandoffThrowsWhenPlanBelongsToDifferentApplication() {
        UUID otherAppId = UUID.randomUUID();
        var mismatchedPlan = new AutomationPlanRepository.PlanRow(
                planId, otherAppId, "https://jobs.lever.co/example/123", "RUNNING",
                Map.of(), List.of(), false, Instant.now(), Instant.now()
        );
        when(planRepository.findById(planId)).thenReturn(Optional.of(mismatchedPlan));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.initiateHandoff(profileId, planId, applicationId, "https://jobs.lever.co/apply", HandoffBlockReason.CAPTCHA, 15)
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Mismatched plan and application");

        verify(handoffRepository, never()).createSession(any(), any(), any(), any(), any(), anyInt());
        verify(planRepository, never()).transition(any(), any(), any());
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("initiateHandoff throws SecurityException when plan is owned by another profile")
    void testInitiateHandoffThrowsWhenPlanOwnedByAnotherProfile() {
        UUID otherProfileId = UUID.randomUUID();
        when(planRepository.ownerOfPlan(planId)).thenReturn(Optional.of(otherProfileId));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.initiateHandoff(profileId, planId, applicationId, "https://jobs.lever.co/apply", HandoffBlockReason.CAPTCHA, 15)
        ).isInstanceOf(SecurityException.class).hasMessageContaining("Plan " + planId + " does not belong to profile");

        verify(handoffRepository, never()).createSession(any(), any(), any(), any(), any(), anyInt());
        verify(planRepository, never()).transition(any(), any(), any());
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("initiateHandoff throws SecurityException when application is owned by another profile")
    void testInitiateHandoffThrowsWhenApplicationOwnedByAnotherProfile() {
        UUID otherProfileId = UUID.randomUUID();
        when(handoffRepository.findProfileIdForApplication(applicationId)).thenReturn(Optional.of(otherProfileId));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.initiateHandoff(profileId, planId, applicationId, "https://jobs.lever.co/apply", HandoffBlockReason.CAPTCHA, 15)
        ).isInstanceOf(SecurityException.class).hasMessageContaining("Application " + applicationId + " does not belong to profile");

        verify(handoffRepository, never()).createSession(any(), any(), any(), any(), any(), anyInt());
        verify(planRepository, never()).transition(any(), any(), any());
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("isPlanAssociatedWithApplication returns true only for matching plan and application")
    void testIsPlanAssociatedWithApplication() {
        assertThat(service.isPlanAssociatedWithApplication(planId, applicationId)).isTrue();
        assertThat(service.isPlanAssociatedWithApplication(planId, UUID.randomUUID())).isFalse();
        assertThat(service.isPlanAssociatedWithApplication(UUID.randomUUID(), applicationId)).isFalse();
        assertThat(service.isPlanAssociatedWithApplication(null, applicationId)).isFalse();
        assertThat(service.isPlanAssociatedWithApplication(planId, null)).isFalse();
    }

    @Test
    @DisplayName("resolveProfileId enforces matching plan-application association and ownership")
    void testResolveProfileId() {
        // Matching: returns profileId
        assertThat(service.resolveProfileId(planId, applicationId)).contains(profileId);

        // Plan belongs to other application: returns empty
        UUID otherAppId = UUID.randomUUID();
        var mismatchedPlan = new AutomationPlanRepository.PlanRow(
                planId, otherAppId, "https://jobs.lever.co/example/123", "RUNNING",
                Map.of(), List.of(), false, Instant.now(), Instant.now()
        );
        when(planRepository.findById(planId)).thenReturn(Optional.of(mismatchedPlan));
        assertThat(service.resolveProfileId(planId, applicationId)).isEmpty();

        // Application owned by different profile: returns empty
        when(planRepository.findById(planId)).thenReturn(Optional.of(
                new AutomationPlanRepository.PlanRow(planId, applicationId, "https://jobs.lever.co/apply", "RUNNING", Map.of(), List.of(), false, Instant.now(), Instant.now())
        ));
        when(handoffRepository.findProfileIdForApplication(applicationId)).thenReturn(Optional.of(UUID.randomUUID()));
        assertThat(service.resolveProfileId(planId, applicationId)).isEmpty();

        // Nulls: returns empty
        assertThat(service.resolveProfileId(null, applicationId)).isEmpty();
        assertThat(service.resolveProfileId(planId, null)).isEmpty();
    }
}
