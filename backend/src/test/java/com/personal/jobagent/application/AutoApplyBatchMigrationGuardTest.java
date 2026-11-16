package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.profile.ProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database migration: while {@code app.migration.manual-review-only} is on, an
 * Auto Apply batch run is refused before anything is read, reserved or
 * created, because a batch creates applications without a review of each one.
 * The controller already turns the refusal into HTTP 409 AUTO_APPLY_DISABLED.
 */
class AutoApplyBatchMigrationGuardTest {

    private static final UUID PROFILE = UUID.randomUUID();

    private final AutoApplySettingsService settingsService = mock(AutoApplySettingsService.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final ApplicationPipelineService pipeline = mock(ApplicationPipelineService.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final AutoApplyBatchService service = new AutoApplyBatchService(settingsService, jobRepository,
            profileRepository, pipeline, quotaService, db, audit);

    private static AutoApplySettingsService.Settings enabledSettings() {
        return new AutoApplySettingsService.Settings(true, 85, 5, 5, null, null, null, null, true);
    }

    @Test
    void aBatchRunIsRefusedUnderTheGuardBeforeAnythingIsReadOrReserved() {
        ReflectionTestUtils.setField(service, "manualReviewOnly", true);
        when(settingsService.get(PROFILE)).thenReturn(enabledSettings());

        assertThatThrownBy(() -> service.runBatch(PROFILE))
                .isInstanceOf(AutoApplyBatchService.BatchDisabledException.class)
                .hasMessageContaining("database migration");

        verifyNoInteractions(settingsService, jobRepository, profileRepository, pipeline, quotaService, db, audit);
    }

    @Test
    void withTheGuardOffABatchRunGetsPastTheGuardToTheExistingChecks() {
        // Default off: the batch is enabled, so the run goes on to the profile
        // check (an unknown profile is not ready), exactly as before.
        when(settingsService.get(PROFILE)).thenReturn(enabledSettings());

        assertThatThrownBy(() -> service.runBatch(PROFILE))
                .isInstanceOf(AutoApplyBatchService.ProfileNotReadyException.class);

        verify(settingsService).get(PROFILE);
        verify(profileRepository).findById(PROFILE);
        verifyNoInteractions(pipeline, quotaService, audit);
    }
}
