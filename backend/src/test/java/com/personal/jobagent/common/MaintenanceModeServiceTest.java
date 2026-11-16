package com.personal.jobagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceModeServiceTest {

    @Test
    @DisplayName("Default configuration should have maintenance mode disabled")
    void defaultsToDisabled() {
        MaintenanceModeService service = new MaintenanceModeService(false);
        assertThat(service.isMaintenanceMode()).isFalse();
    }

    @Test
    @DisplayName("Maintenance mode is true when explicitly enabled")
    void enabledWhenConfiguredTrue() {
        MaintenanceModeService service = new MaintenanceModeService(true);
        assertThat(service.isMaintenanceMode()).isTrue();
    }
}
