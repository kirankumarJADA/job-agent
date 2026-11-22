package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.config.FlywayTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("Phase 2B Hardened: Database Migration from V064 through V065 to V066 IT")
public class V064ToV065MigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static Flyway flyway(String target) {
        return FlywayTestSupport.load(Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("remove_seed_dev_account", "false"))
                .target(target));
    }

    @Test
    @DisplayName("Migrates cleanly through V064 -> V065 -> V066 verifying schema immutability and lease additions")
    void testMigrationFromV064ThroughV066() {
        // Step 1: Migrate up to V064 (Wave 2 F9 baseline)
        flyway("64").migrate();

        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Verify search_plans table does not exist at V064
        Integer tableCountV64 = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'search_plans'
                """, Integer.class);
        assertThat(tableCountV64).isEqualTo(0);

        // Step 2: Migrate to V065 (Candidate search plans base table)
        flyway("65").migrate();

        // Verify search_plans table exists now
        Integer tableCountV65 = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'search_plans'
                """, Integer.class);
        assertThat(tableCountV65).isEqualTo(1);

        // Verify lease columns do NOT exist in original V065
        Integer colOwnerTokenBefore = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'search_plans' AND column_name = 'execution_owner_token'
                """, Integer.class);
        assertThat(colOwnerTokenBefore).isEqualTo(0);

        Integer colLeaseExpiresBefore = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'search_plans' AND column_name = 'execution_lease_expires_at'
                """, Integer.class);
        assertThat(colLeaseExpiresBefore).isEqualTo(0);

        Integer indexCountBefore = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'search_plans' AND indexname = 'search_plans_lease_idx'
                """, Integer.class);
        assertThat(indexCountBefore).isEqualTo(0);

        // Step 3: Migrate to V066 (Execution leases & fencing index additive migration)
        flyway("66").migrate();

        // Verify columns added by V066
        Integer colOwnerTokenAfter = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'search_plans' AND column_name = 'execution_owner_token'
                """, Integer.class);
        assertThat(colOwnerTokenAfter).isEqualTo(1);

        Integer colLeaseExpiresAfter = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'search_plans' AND column_name = 'execution_lease_expires_at'
                """, Integer.class);
        assertThat(colLeaseExpiresAfter).isEqualTo(1);

        Integer indexCountAfter = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'search_plans' AND indexname = 'search_plans_lease_idx'
                """, Integer.class);
        assertThat(indexCountAfter).isEqualTo(1);

        // Step 4: Insert test record to verify constraints and columns
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID ownerToken = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, email, password_hash, display_name) VALUES (?, 'mig2b@test.local', 'h', 'Mig2B')", userId);
        jdbc.update("INSERT INTO profiles (id, user_id, updated_at) VALUES (?, ?, now())", profileId, userId);

        jdbc.update("""
                INSERT INTO search_plans (
                    id, profile_id, name, target_titles, query_facets, target_sources,
                    search_frequency, max_results_per_run, active, last_execution_status,
                    execution_owner_token, execution_lease_expires_at
                ) VALUES (
                    ?, ?, 'Migration 2B Test Plan', '{Engineer}', '{"keywords":["Java"]}'::jsonb, '{ADZUNA}',
                    'DAILY', 50, true, 'RUNNING', ?, now() + INTERVAL '1 hour'
                )
                """, planId, profileId, ownerToken);

        Integer planCount = jdbc.queryForObject("SELECT count(*) FROM search_plans WHERE id = ?", Integer.class, planId);
        assertThat(planCount).isEqualTo(1);
    }
}
