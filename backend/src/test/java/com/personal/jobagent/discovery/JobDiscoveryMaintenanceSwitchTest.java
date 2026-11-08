package com.personal.jobagent.discovery;

import com.personal.jobagent.events.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
        JobDiscoveryService service = new JobDiscoveryService(jdbc, mock(OutboxWriter.class));

        service.scheduledDiscoveryMaintenance();

        verify(jdbc).update(contains("status = 'ARCHIVED'"));
    }
}
