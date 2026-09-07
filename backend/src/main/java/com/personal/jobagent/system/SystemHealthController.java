package com.personal.jobagent.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/v1/system/health — per API contract §C3. Deliberately independent
 * of Spring Actuator's /actuator/health (which stays enabled for
 * Prometheus/ops tooling): this one is the contract the dashboard and the
 * docker-compose healthcheck both depend on, so its shape is ours to keep
 * stable regardless of Actuator upgrades.
 */
@RestController
public class SystemHealthController {

    private final DataSource dataSource;

    public SystemHealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/v1/system/health")
    public Map<String, Object> health() {
        boolean dbUp = databaseReachable();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DEGRADED");
        body.put("timestamp", Instant.now().toString());
        body.put("components", Map.of("database", dbUp ? "UP" : "DOWN"));
        return body;
    }

    private boolean databaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
