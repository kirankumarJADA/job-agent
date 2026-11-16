package com.personal.jobagent.discovery;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.discovery.platform.BoardDiscoveryService;
import com.personal.jobagent.discovery.platform.PlatformRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The 60 s discovery sweep must stay completely idle while the central maintenance
 * mode is on, even with {@code app.discovery.scheduler-enabled=true}, and must run
 * exactly as before once it is off. The switch is consulted on every execution.
 */
class DiscoverySchedulerMaintenanceTest {

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final BoardDiscoveryService boards = mock(BoardDiscoveryService.class);
    private final PlatformRegistry registry = new PlatformRegistry(40);
    private final AutomationMetrics metrics = new AutomationMetrics(new SimpleMeterRegistry());

    private DiscoveryScheduler scheduler(MaintenanceModeService mode) {
        return new DiscoveryScheduler(db, boards, registry, 10, true, metrics, mode);
    }

    @Test
    @DisplayName("maintenance mode on: the sweep neither reads job_sources nor runs a board")
    void sweepIsSuppressedInMaintenanceMode() {
        scheduler(new MaintenanceModeService(true)).runDueSources();

        verifyNoInteractions(db, boards);
    }

    @Test
    @DisplayName("maintenance mode off: the sweep queries the due sources as before")
    void sweepRunsWhenMaintenanceModeIsOff() {
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        scheduler(new MaintenanceModeService(false)).runDueSources();

        verify(db).queryForList(anyString(), any(Object[].class));
        verifyNoInteractions(boards);
    }

    @Test
    @DisplayName("the switch is consulted on every execution, not once at construction")
    void switchIsCheckedPerExecution() {
        MaintenanceModeService mode = spy(new MaintenanceModeService(true));
        DiscoveryScheduler scheduler = scheduler(mode);

        scheduler.runDueSources();
        scheduler.runDueSources();

        verify(mode, times(2)).isMaintenanceMode();
        verifyNoInteractions(db, boards);
    }
}
