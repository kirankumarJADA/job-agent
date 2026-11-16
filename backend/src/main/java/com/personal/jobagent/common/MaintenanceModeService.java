package com.personal.jobagent.common;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Central maintenance-mode control for safe database migration, staging rehearsals,
 * and controlled maintenance windows.
 *
 * <p>When maintenance mode is enabled ({@code app.maintenance.enabled=true}):
 * <ul>
 *   <li>Scheduled recovery and reconciliation sweepers skip execution.</li>
 *   <li>Startup-triggered background updates and plan reclaims are suppressed.</li>
 *   <li>Mail retention sweeps skip purge and deletion operations.</li>
 *   <li>Inbound mail webhooks refuse deliveries with HTTP 503 (retry) before touching the database.</li>
 *   <li>Outbox dispatch and background board discovery remain paused.</li>
 * </ul>
 *
 * <p>Normal production default is {@code false}, preserving standard autonomous execution.
 */
@Service
public class MaintenanceModeService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceModeService.class);

    private final boolean maintenanceMode;

    public MaintenanceModeService(
            @Value("${app.maintenance.enabled:${app.maintenance-mode:false}}") boolean maintenanceMode) {
        this.maintenanceMode = maintenanceMode;
    }

    @PostConstruct
    void init() {
        if (maintenanceMode) {
            log.warn("CENTRAL MAINTENANCE MODE IS ACTIVE (app.maintenance.enabled=true): "
                    + "background jobs, retention sweeps, and inbound writes are suspended.");
        }
    }

    /**
     * Whether central maintenance mode is enabled.
     *
     * @return true if maintenance mode is active, false in normal production operation.
     */
    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }
}
