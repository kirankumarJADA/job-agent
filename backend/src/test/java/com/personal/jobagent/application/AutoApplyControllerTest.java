package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.security.OwnerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 5 (Robin x Tsenta continuation): REST API for the Auto Apply batch
 * workflow — settings, preview, explicit run start, and run history. Every
 * operation is owner-scoped via {@link OwnerContext}; the controller itself
 * never creates an application — it only validates input and delegates to
 * {@link AutoApplySettingsService} / {@link AutoApplyBatchService}.
 */
class AutoApplyControllerTest {

    private static final UUID PROFILE = UUID.randomUUID();

    private final OwnerContext ownerContext = mock(OwnerContext.class);
    private final AutoApplySettingsService settingsService = mock(AutoApplySettingsService.class);
    private final AutoApplyBatchService batchService = mock(AutoApplyBatchService.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    private AutoApplyController controller;

    @BeforeEach
    void setUp() {
        when(ownerContext.profileIdOrNull()).thenReturn(PROFILE);
        when(ownerContext.actorOr("user")).thenReturn("test@example.com");
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        controller = new AutoApplyController(ownerContext, settingsService, batchService, audit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Nested
    class Authorization {
        @Test
        void everyEndpointRefusesWithoutAProfileRatherThanGuessingOne() {
            when(ownerContext.profileIdOrNull()).thenReturn(null);

            assertThat(controller.getSettings().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.putSettings(new AutoApplyController.SettingsRequest(
                    true, 70, 10, 10, null, null, null, null, false), httpRequest).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.preview().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.startRun().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.history(20).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.getRun(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            verifyNoInteractions(batchService);
        }
    }

    @Nested
    class Settings {
        @Test
        void getReportsCurrentSettings() {
            when(settingsService.get(PROFILE)).thenReturn(
                    new AutoApplySettingsService.Settings(true, 75, 8, 20, "REMOTE", null, null, null, true));

            ResponseEntity<?> response = controller.getSettings();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response))
                    .containsEntry("enabled", true)
                    .containsEntry("minScore", 75)
                    .containsEntry("dailyCap", 8)
                    .containsEntry("maxBatchSize", 20)
                    .containsEntry("remoteType", "REMOTE");
        }

        @Test
        void putSavesAndAudits() {
            when(settingsService.save(eq(PROFILE), eq(true), eq(80), eq(5), eq(15), isNull(), isNull(), isNull(), isNull(), eq(false)))
                    .thenReturn(new AutoApplySettingsService.Settings(true, 80, 5, 15, null, null, null, null, true));

            ResponseEntity<?> response = controller.putSettings(
                    new AutoApplyController.SettingsRequest(true, 80, 5, 15, null, null, null, null, false), httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response)).containsEntry("minScore", 80).containsEntry("dailyCap", 5);
            verify(audit).write(any());
        }

        @Test
        void putRejectsInvalidValuesWithoutWritingAnAuditEntry() {
            when(settingsService.save(eq(PROFILE), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenThrow(new IllegalArgumentException("min_score must be between 1 and 100"));

            ResponseEntity<?> response = controller.putSettings(
                    new AutoApplyController.SettingsRequest(true, 999, 5, 15, null, null, null, null, false), httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(audit, never()).write(any());
        }
    }

    @Nested
    class Preview {
        @Test
        void returnsThePredictionWithoutStartingARun() {
            when(batchService.preview(PROFILE)).thenReturn(
                    new AutoApplyBatchService.PreviewResult(3, 2, java.util.List.of(
                            new AutoApplyBatchService.PreviewItem(UUID.randomUUID(), "Role", "Co", 90, "WOULD_PREPARE", null))));

            ResponseEntity<?> response = controller.preview();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response)).containsEntry("jobsConsidered", 3).containsEntry("jobsEligible", 2);
            verify(batchService, never()).runBatch(any());
        }
    }

    @Nested
    class StartRun {
        @Test
        void startsAnExplicitRunAndReturnsItsSummary() {
            UUID runId = UUID.randomUUID();
            when(batchService.runBatch(PROFILE)).thenReturn(runId);
            when(batchService.findRun(PROFILE, runId)).thenReturn(Optional.of(
                    new AutoApplyBatchService.RunSummary(runId, "COMPLETED", 3, 2, 1, 1, 1, 0, 0, 0, 0,
                            java.time.Instant.now(), java.time.Instant.now(), null)));

            ResponseEntity<?> response = controller.startRun();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response)).containsEntry("status", "COMPLETED").containsEntry("prepared", 1);
        }

        @Test
        void disabledConfigurationRefusesWithAStableErrorCode() {
            when(batchService.runBatch(PROFILE)).thenThrow(
                    new AutoApplyBatchService.BatchDisabledException("Auto Apply batch preparation is disabled."));

            ResponseEntity<?> response = controller.startRun();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bodyOf(response)).containsEntry("code", "AUTO_APPLY_DISABLED");
        }

        @Test
        void incompleteProfileRefusesWithAStableErrorCode() {
            when(batchService.runBatch(PROFILE)).thenThrow(
                    new AutoApplyBatchService.ProfileNotReadyException("Your profile setup is incomplete."));

            ResponseEntity<?> response = controller.startRun();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bodyOf(response)).containsEntry("code", "PROFILE_NOT_READY");
        }
    }

    @Nested
    class RunHistory {
        @Test
        void listsHistoryForTheCaller() {
            UUID runId = UUID.randomUUID();
            when(batchService.history(PROFILE, 20)).thenReturn(java.util.List.of(
                    new AutoApplyBatchService.RunSummary(runId, "COMPLETED", 1, 1, 1, 0, 0, 0, 0, 0, 0,
                            java.time.Instant.now(), java.time.Instant.now(), null)));

            ResponseEntity<?> response = controller.history(20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void getRunReturns404WhenNotOwnedOrMissing() {
            UUID runId = UUID.randomUUID();
            when(batchService.findRun(PROFILE, runId)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getRun(runId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void getRunIncludesItemsWhenFound() {
            UUID runId = UUID.randomUUID();
            when(batchService.findRun(PROFILE, runId)).thenReturn(Optional.of(
                    new AutoApplyBatchService.RunSummary(runId, "COMPLETED", 1, 1, 1, 0, 0, 0, 0, 0, 0,
                            java.time.Instant.now(), java.time.Instant.now(), null)));
            when(batchService.findRunItems(PROFILE, runId)).thenReturn(java.util.List.of(
                    new AutoApplyBatchService.RunItem(UUID.randomUUID(), "Role", "Co", UUID.randomUUID(), 90,
                            "PREPARED", null, java.time.Instant.now())));

            ResponseEntity<?> response = controller.getRun(runId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response)).containsKey("items");
        }
    }
}
