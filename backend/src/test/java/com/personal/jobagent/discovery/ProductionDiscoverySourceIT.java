package com.personal.jobagent.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Phase 17 production source row is genuinely SCHEDULABLE: after
 * Flyway runs, the Stripe Greenhouse board exists, is enabled, carries a cron
 * the scheduler can actually parse, and satisfies the DiscoveryScheduler's
 * exact eligibility query. No HTTP is performed here — board liveness was
 * verified when the migration was authored and is exercised by the
 * health-check endpoint in production.
 */
@SpringBootTest(properties = "app.discovery.scheduler-enabled=false")
@Testcontainers
class ProductionDiscoverySourceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;

    @Test
    void theProductionGreenhouseSourceIsSeededAndSchedulable() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, kind, org_identifier, display_name, enabled, schedule_cron, policy, rate_limit_per_min
                from job_sources
                where kind = 'GREENHOUSE' and org_identifier = 'stripe'
                """);
        assertThat(rows).hasSize(1);
        Map<String, Object> source = rows.get(0);
        assertThat(source.get("enabled")).isEqualTo(true);
        assertThat(source.get("schedule_cron")).isEqualTo("0 */15 * * * ?");
        assertThat(source.get("policy")).isEqualTo("DISCOVERY_ONLY");
        assertThat(source.get("display_name")).isEqualTo("Stripe (Greenhouse)");
    }

    @Test
    void theSeededCronIsParseableByTheScheduler() {
        String cron = jdbc.queryForObject(
                "select schedule_cron from job_sources where kind = 'GREENHOUSE' and org_identifier = 'stripe'",
                String.class);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        // A parse failure throws IllegalArgumentException; a healthy cron
        // always has a next fire time.
        assertThat(CronExpression.parse(cron.trim()).next(now)).isNotNull();
    }

    @Test
    void theSourceSatisfiesTheSchedulerEligibilityQuery() {
        // Mirrors DiscoveryScheduler.runDueSources' exact eligibility filter —
        // if this returns the row, the next sweep will dispatch it.
        List<Map<String, Object>> due = jdbc.queryForList("""
                select id, kind, org_identifier, schedule_cron, rate_limit_per_min, last_run_at
                from job_sources
                where enabled = true and policy <> 'DISABLED'
                  and kind in ('GREENHOUSE','ASHBY')
                  and coalesce(org_identifier, '') <> ''
                  and coalesce(schedule_cron, '') <> ''
                """);
        assertThat(due).anySatisfy(row -> {
            assertThat(row.get("org_identifier")).isEqualTo("stripe");
            assertThat(row.get("kind")).isEqualTo("GREENHOUSE");
        });
    }

    @Test
    void theSourceIsBoundToItsCompany() {
        Integer count = jdbc.queryForObject("""
                select count(*) from job_sources s join companies c on c.id = s.company_id
                where s.org_identifier = 'stripe' and c.slug = 'stripe'
                """, Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
