package com.personal.jobagent.jobs;

import com.personal.jobagent.common.MaintenanceModeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The local-profile job seeder writes 25 jobs at startup when the table is nearly empty.
 * In maintenance mode it must not even count the rows; off, it behaves as before.
 */
class JobSeedServiceMaintenanceTest {

    private static final String COUNT_SQL = "select count(*) from jobs";

    private final JdbcTemplate db = mock(JdbcTemplate.class);

    private JobSeedService seeder(boolean maintenance) {
        JobSeedService service = new JobSeedService(db, new MaintenanceModeService(maintenance));
        ReflectionTestUtils.setField(service, "seedEnabled", true); // @Value field: injected by Spring only
        return service;
    }

    @Test
    @DisplayName("maintenance mode on: startup seeding performs no database access")
    void seedingIsSuppressedInMaintenanceMode() {
        seeder(true).onStartup();

        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("maintenance mode off: startup seeding counts the jobs as before (and seeds nothing when the table is populated)")
    void seedingRunsWhenMaintenanceModeIsOff() {
        when(db.queryForObject(COUNT_SQL, Integer.class)).thenReturn(100);

        seeder(false).onStartup();

        verify(db).queryForObject(COUNT_SQL, Integer.class);
        verifyNoMoreInteractions(db);
    }
}
