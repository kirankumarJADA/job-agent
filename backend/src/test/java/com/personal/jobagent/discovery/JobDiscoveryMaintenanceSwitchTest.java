package com.personal.jobagent.discovery;

import com.personal.jobagent.events.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** The migration switch {@code app.discovery.maintenance-enabled} (plan §10.7). */
class JobDiscoveryMaintenanceSwitchTest {

    @Test
    void pausedMaintenanceArchivesNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JobDiscoveryService service = new JobDiscoveryService(jdbc, mock(OutboxWriter.class));
        ReflectionTestUtils.setField(service, "maintenanceEnabled", false);

        service.scheduledDiscoveryMaintenance();

        verifyNoInteractions(jdbc);
    }

    @Test
    void maintenanceRunsUnlessTurnedOff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // This sweep wins the single-sweep advisory lock.
        when(jdbc.queryForObject(contains("pg_try_advisory_xact_lock"), eq(Boolean.class))).thenReturn(true);
        JobDiscoveryService service = new JobDiscoveryService(jdbc, mock(OutboxWriter.class));

        service.scheduledDiscoveryMaintenance();

        verify(jdbc).queryForObject(contains("pg_try_advisory_xact_lock"), eq(Boolean.class));
        verify(jdbc).execute(contains("create temporary table if not exists maintenance_locked_job_ids"));
        verify(jdbc).execute(contains("create temporary table if not exists expired_job_ids"));
        verify(jdbc).update("truncate table maintenance_locked_job_ids");
        verify(jdbc).update("truncate table expired_job_ids");
        // F9: affected jobs are locked, ascending, before any observation is expired.
        verify(jdbc).update(contains("insert into maintenance_locked_job_ids"), eq(7));
        verify(jdbc).update(contains("insert into expired_job_ids"), eq(7));
        verify(jdbc).update(contains("status = 'ARCHIVED'"));
    }

    @Test
    void aSweepThatLosesTheAdvisoryLockDoesNothingElse() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("pg_try_advisory_xact_lock"), eq(Boolean.class))).thenReturn(false);
        JobDiscoveryService service = new JobDiscoveryService(jdbc, mock(OutboxWriter.class));

        service.scheduledDiscoveryMaintenance();

        verify(jdbc).queryForObject(contains("pg_try_advisory_xact_lock"), eq(Boolean.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void pausedWhenCentralMaintenanceModeActive() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JobDiscoveryService service = new JobDiscoveryService(
                jdbc, mock(OutboxWriter.class), new com.personal.jobagent.common.MaintenanceModeService(true));

        service.scheduledDiscoveryMaintenance();

        verifyNoInteractions(jdbc);
    }
}
