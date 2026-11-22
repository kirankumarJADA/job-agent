package com.personal.jobagent.automation.handoff;

import com.personal.jobagent.security.OwnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HandoffControllerTest {

    private AtsHandoffService handoffService;
    private OwnerContext ownerContext;
    private HandoffController controller;

    private final UUID profileId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handoffService = mock(AtsHandoffService.class);
        ownerContext = mock(OwnerContext.class);
        controller = new HandoffController(handoffService, ownerContext);
        when(ownerContext.profileIdOrNull()).thenReturn(profileId);
    }

    @Test
    @DisplayName("getActiveHandoff returns session when active")
    void testGetActiveHandoff() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );

        when(handoffService.getActiveHandoffForApplication(profileId, applicationId)).thenReturn(Optional.of(record));

        ResponseEntity<?> response = controller.getActiveHandoff(applicationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(record);
    }

    @Test
    @DisplayName("confirmCompletion returns 200 on successful candidate attestation")
    void testConfirmCompletionSuccess() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );
        when(handoffService.findSessionAnyOwner(sessionId)).thenReturn(Optional.of(record));
        when(handoffService.confirmHandoffCompletion(profileId, sessionId)).thenReturn(true);

        var req = new HandoffController.CompleteRequest(true, "I submitted via employer portal");
        ResponseEntity<?> response = controller.confirmCompletion(sessionId, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("confirmCompletion rejects request when submittedExternally is false")
    void testConfirmCompletionRejectedWithoutAttestation() {
        var req = new HandoffController.CompleteRequest(false, "Did not submit");
        ResponseEntity<?> response = controller.confirmCompletion(sessionId, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(handoffService, never()).confirmHandoffCompletion(any(), any());
    }

    @Test
    @DisplayName("confirmCompletion returns 403 on IDOR cross-profile attempt")
    void testConfirmCompletionIdorForbidden() {
        UUID otherProfileId = UUID.randomUUID();
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, otherProfileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );
        when(handoffService.findSessionAnyOwner(sessionId)).thenReturn(Optional.of(record));

        ResponseEntity<?> response = controller.confirmCompletion(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("confirmCompletion returns 409 CONFLICT on expired or failed completion")
    void testConfirmCompletionFailure() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );
        when(handoffService.findSessionAnyOwner(sessionId)).thenReturn(Optional.of(record));
        when(handoffService.confirmHandoffCompletion(profileId, sessionId)).thenReturn(false);

        ResponseEntity<?> response = controller.confirmCompletion(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("cancelHandoff returns 200 on successful cancel")
    void testCancelHandoffSuccess() {
        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );
        when(handoffService.findSessionAnyOwner(sessionId)).thenReturn(Optional.of(record));
        when(handoffService.cancelHandoff(profileId, sessionId)).thenReturn(true);

        ResponseEntity<?> response = controller.cancelHandoff(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("initiateHandoff with worker token resolves profileId and creates session")
    void testInitiateHandoffWorkerToken() {
        when(ownerContext.profileIdOrNull()).thenReturn(null);
        when(ownerContext.isWorkerRequest()).thenReturn(true);
        when(handoffService.resolveProfileId(planId, applicationId)).thenReturn(Optional.of(profileId));

        HandoffSessionRecord record = new HandoffSessionRecord(
                sessionId, planId, applicationId, profileId,
                HandoffStatus.PENDING, HandoffBlockReason.CAPTCHA,
                "https://jobs.lever.co/example", UUID.randomUUID(),
                Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now()
        );
        when(handoffService.initiateHandoff(eq(profileId), eq(planId), eq(applicationId), any(), any(), eq(15)))
                .thenReturn(record);

        var req = new HandoffController.InitiateRequest(
                planId, applicationId, "LEVER", "https://jobs.lever.co/example",
                "LEVER_CAPTCHA_PRESENT", "evidence/screenshot.png", 15
        );
        ResponseEntity<?> response = controller.initiateHandoff(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(record);
    }
}
