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
    @DisplayName("confirmCompletion returns 200 on successful resume")
    void testConfirmCompletionSuccess() {
        when(handoffService.confirmHandoffCompletion(profileId, sessionId)).thenReturn(true);

        ResponseEntity<?> response = controller.confirmCompletion(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("confirmCompletion returns 409 CONFLICT on expired or unauthorized session")
    void testConfirmCompletionFailure() {
        when(handoffService.confirmHandoffCompletion(profileId, sessionId)).thenReturn(false);

        ResponseEntity<?> response = controller.confirmCompletion(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("cancelHandoff returns 200 on successful cancel")
    void testCancelHandoffSuccess() {
        when(handoffService.cancelHandoff(profileId, sessionId)).thenReturn(true);

        ResponseEntity<?> response = controller.cancelHandoff(sessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
