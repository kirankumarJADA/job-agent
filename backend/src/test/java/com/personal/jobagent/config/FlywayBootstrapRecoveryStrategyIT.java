package com.personal.jobagent.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the real guarded strategy against disposable Postgres for each
 * bootstrap state. The production condition is reproduced with the exact
 * object kind that the previous relation-only inspector missed: a
 * supabase_admin-owned bootstrap FUNCTION in public with no
 * flyway_schema_history — invisible to pg_class, visible to Flyway's
 * emptiness check via pg_proc.
 *
 * Verifies classification, selected action, baseline version 0, V001
 * execution, full V001..V019 chain, users table, final schema version 019.
 *
 * Testcontainers is used by default. When Testcontainers cannot reach a
 * Docker environment, an externally managed database can be supplied via
 * -Dflyway.it.jdbcUrl / flyway.it.username / flyway.it.password (the tests
 * wipe and rebuild the schema between scenarios, so it must be disposable).
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayBootstrapRecoveryStrategyIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static {
        if (System.getProperty("flyway.it.jdbcUrl") == null) {
            postgres.start();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("flyway.it.jdbcUrl");
        if (url != null) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username",
                    () -> System.getProperty("flyway.it.username", "postgres"));
            registry.add("spring.datasource.password",
                    () -> System.getProperty("flyway.it.password", "postgres"));
        } else {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @Autowired
    private FlywayMigrationStrategy strategy;

    @Autowired
    private JdbcTemplate jdbc;

    private Flyway newFlyway() {
        return Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("0")
                .load();
    }

    private void resetSchema() {
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
        // Recreate the roles used to own platform-style fixture objects when
        // the connected role has permission to do so (supabase_admin on real
        // Supabase). Best-effort: local fixtures may already have them.
        try {
            jdbc.execute("do $$ begin\n"
                    + "  if not exists (select from pg_roles where rolname = 'supabase_admin') then\n"
                    + "    create role supabase_admin nologin;\n"
                    + "  end if;\n"
                    + "end $$;");
        } catch (RuntimeException ignored) {
            // Role creation not permitted: fixtures then use plain postgres
            // ownership, which the type/relation fixtures do not rely on.
        }
    }

    private void assertFullyMigrated() {
        Integer migrationCount = jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where success and type <> 'BASELINE'",
                Integer.class);
        assertThat(migrationCount).isEqualTo(19);
        String current = jdbc.queryForObject(
                "select max(version) from public.flyway_schema_history where success", String.class);
        assertThat(current).isEqualTo("019");
        Boolean usersExists = jdbc.queryForObject(
                "select to_regclass('public.users') is not null", Boolean.class);
        assertThat(usersExists).isTrue();
    }

    @Test
    @Order(1)
    void productionReproductionSupabaseOwnedFunctionInPublicWithoutHistoryBaselinesAtZeroAndMigratesFully() {
        resetSchema();
        // Exact production condition: the object Flyway sees but a
        // relation-only inspector does not — a supabase_admin-owned function.
        jdbc.execute("create function public.platform_bootstrap() returns void "
                + "language plpgsql as $$ begin end $$");
        try {
            jdbc.execute("alter function public.platform_bootstrap() owner to supabase_admin");
        } catch (RuntimeException ownerFailure) {
            // Role ownership unavailable (e.g. hosted role restrictions):
            // the function alone still reproduces the pg_proc blind spot.
        }

        strategy.migrate(newFlyway());

        Integer baselineVersion = jdbc.queryForObject(
                "select version from public.flyway_schema_history where type = 'BASELINE'",
                Integer.class);
        assertThat(baselineVersion).isEqualTo(0);
        assertFullyMigrated();
    }

    @Test
    @Order(2)
    void freshDatabaseMigratesFullyWithoutBaselineRow() {
        resetSchema();

        strategy.migrate(newFlyway());

        Integer baselineRows = jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where type = 'BASELINE'",
                Integer.class);
        assertThat(baselineRows).isEqualTo(0);
        assertFullyMigrated();
    }

    @Test
    @Order(3)
    void extensionOwnedRelationWithoutHistoryAlsoBaselinesAtZero() {
        resetSchema();
        jdbc.execute("create table public.spatial_ref_sys (srid integer)");

        strategy.migrate(newFlyway());

        Integer baselineVersion = jdbc.queryForObject(
                "select version from public.flyway_schema_history where type = 'BASELINE'",
                Integer.class);
        assertThat(baselineVersion).isEqualTo(0);
        assertFullyMigrated();
    }

    @Test
    @Order(4)
    void invalidLoneBaselineHistoryIsRecoveredWithFullChain() {
        resetSchema();
        jdbc.execute("""
                create table public.flyway_schema_history (
                    installed_rank int not null, version varchar(50), description varchar(200) not null,
                    type varchar(20) not null, script varchar(1000) not null, checksum int,
                    installed_by varchar(100) not null, installed_on timestamptz not null default now(),
                    execution_time int not null, success boolean not null)
                """);
        jdbc.execute("""
                insert into public.flyway_schema_history
                    (installed_rank, version, description, type, script, installed_by, execution_time, success)
                values (1, '1', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', 'test', 0, true)
                """);

        strategy.migrate(newFlyway());

        Integer baselineVersion = jdbc.queryForObject(
                "select version from public.flyway_schema_history where type = 'BASELINE'",
                Integer.class);
        assertThat(baselineVersion).isEqualTo(0);
        assertFullyMigrated();
    }

    @Test
    @Order(5)
    void unexpectedApplicationTableWithoutHistoryFailsClosed() {
        resetSchema();
        jdbc.execute("create table public.customer_data (id integer)");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strategy.migrate(newFlyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing Flyway bootstrap recovery");

        // The unexpected table must not have been modified or removed.
        Boolean customerExists = jdbc.queryForObject(
                "select to_regclass('public.customer_data') is not null", Boolean.class);
        assertThat(customerExists).isTrue();
        Boolean historyExists = jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history') is not null", Boolean.class);
        assertThat(historyExists).isFalse();
    }

    @Test
    @Order(6)
    void unexpectedApplicationFunctionWithoutHistoryFailsClosed() {
        resetSchema();
        // Same object kind as the platform fixture but owned by the operator
        // role: must fail closed, never be silently dropped or baselined.
        jdbc.execute("create function public.operator_installed() returns void "
                + "language plpgsql as $$ begin end $$");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strategy.migrate(newFlyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing Flyway bootstrap recovery");

        Boolean fnExists = jdbc.queryForObject(
                "select to_regprocedure('public.operator_installed()') is not null", Boolean.class);
        assertThat(fnExists).isTrue();
        Boolean historyExists = jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history') is not null", Boolean.class);
        assertThat(historyExists).isFalse();
    }

    @Test
    @Order(7)
    void alreadyMigratedDatabaseIsLeftIntact() {
        // Build a fully migrated state, snapshot the history, run the
        // strategy again, and prove nothing changed.
        resetSchema();
        strategy.migrate(newFlyway());
        List<String> before = jdbc.queryForList(
                "select version from public.flyway_schema_history order by installed_rank", String.class);
        assertThat(before.size()).isEqualTo(19);
        strategy.migrate(newFlyway());
        List<String> after = jdbc.queryForList(
                "select version from public.flyway_schema_history order by installed_rank", String.class);
        assertThat(after).isEqualTo(before);
        assertFullyMigrated();
    }
}
