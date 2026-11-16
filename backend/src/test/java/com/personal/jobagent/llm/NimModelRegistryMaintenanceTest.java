package com.personal.jobagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.MaintenanceModeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The NIM catalogue refresh on ApplicationReadyEvent is an outbound HTTP call followed by
 * an llm_models write. With a configured key it must stay idle in maintenance mode and run
 * once the mode is off. No network is touched: refresh() is stubbed on the spy.
 */
class NimModelRegistryMaintenanceTest {

    private final JdbcTemplate db = mock(JdbcTemplate.class);

    private NimModelRegistry registry(boolean maintenance) {
        return spy(new NimModelRegistry(new ObjectMapper(), db, "http://127.0.0.1:9", "configured-test-key",
                new MaintenanceModeService(maintenance)));
    }

    @Test
    @DisplayName("maintenance mode on: the startup refresh is skipped (no catalogue call, no database access)")
    void startupRefreshIsSuppressedInMaintenanceMode() {
        NimModelRegistry registry = registry(true);

        registry.refreshOnReady();

        verify(registry, never()).refresh();
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("maintenance mode off: the startup refresh runs when a key is configured")
    void startupRefreshRunsWhenMaintenanceModeIsOff() {
        NimModelRegistry registry = registry(false);
        doReturn(List.of()).when(registry).refresh();

        registry.refreshOnReady();

        verify(registry).refresh();
    }
}
