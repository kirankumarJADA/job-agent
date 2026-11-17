package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.common.MaintenanceModeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The startup identity backfill (ApplicationReadyEvent) writes job_identity_keys and
 * job_identity_backfill_skips; it must not touch the database in maintenance mode and
 * must resume normally once the mode is off.
 */
class JobIdentityBackfillMaintenanceTest {

    private final JdbcTemplate db = mock(JdbcTemplate.class);

    @Test
    @DisplayName("maintenance mode on: onReady performs no database access")
    void startupBackfillIsSuppressedInMaintenanceMode() {
        new JobIdentityBackfill(db, true, new MaintenanceModeService(true)).onReady();

        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("maintenance mode off: onReady runs the batched backfill as before")
    void startupBackfillRunsWhenMaintenanceModeIsOff() {
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        new JobIdentityBackfill(db, true, new MaintenanceModeService(false)).onReady();

        verify(db).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("the existing flag still wins: disabled backfill never runs, whatever the mode")
    void disabledFlagStillSuppresses() {
        new JobIdentityBackfill(db, false, new MaintenanceModeService(false)).onReady();

        verifyNoInteractions(db);
    }
}
