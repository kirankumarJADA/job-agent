package com.personal.jobagent.audit;

import com.personal.jobagent.common.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NOTE ON VERIFICATION STATUS: the SQL/parameter pattern this class exercises
 * (jsonb before/after state, inet ip, the null-heavy case a login row
 * actually produces) was independently verified with a raw JDBC program
 * against a live Postgres 16 instance during implementation — see the P1-b
 * notes. This test file itself, wired through Spring Boot + Testcontainers,
 * has NOT been executed in that environment (no Maven Central access there).
 * Run this for real via `mvn verify` before treating AuditLogWriter as done.
 */
@SpringBootTest
@Testcontainers
class JdbcAuditLogWriterIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AuditLogWriter auditLogWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void writesFullRowWithJsonbAndInet() {
        UUID correlationId = UuidV7.generate();
        UUID entityId = UuidV7.generate();

        auditLogWriter.write(new AuditEntry(
                "dev@example.local",
                "PROFILE_UPDATED",
                "PROFILE",
                entityId,
                Map.of("headline", "old"),
                Map.of("headline", "new"),
                "192.168.1.10",
                correlationId
        ));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select actor, action, entity_type, before_state::text as before_state, "
                        + "after_state::text as after_state, ip::text as ip "
                        + "from audit_logs where correlation_id = ?",
                correlationId);

        assertThat(row.get("actor")).isEqualTo("dev@example.local");
        assertThat(row.get("action")).isEqualTo("PROFILE_UPDATED");
        assertThat(row.get("entity_type")).isEqualTo("PROFILE");
        assertThat((String) row.get("before_state")).contains("\"headline\":\"old\"");
        assertThat((String) row.get("after_state")).contains("\"headline\":\"new\"");
        assertThat((String) row.get("ip")).startsWith("192.168.1.10");
    }

    @Test
    void writesSimpleRowWithNulls() {
        UUID correlationId = UuidV7.generate();

        auditLogWriter.write(AuditEntry.simple("dev@example.local", "LOGIN_SUCCESS", null, correlationId));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select actor, action, entity_type, before_state, after_state, ip "
                        + "from audit_logs where correlation_id = ?",
                correlationId);

        assertThat(row.get("actor")).isEqualTo("dev@example.local");
        assertThat(row.get("action")).isEqualTo("LOGIN_SUCCESS");
        assertThat(row.get("entity_type")).isNull();
        assertThat(row.get("before_state")).isNull();
        assertThat(row.get("ip")).isNull();
    }
}
