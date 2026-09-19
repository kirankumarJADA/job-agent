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
 * bootstrap state, reproducing the exact production condition from Render:
 * no flyway_schema_history, public.rls_auto_enable() (the documented
 * Supabase RLS bootstrap routine, guides/database/postgres/event-triggers)
 * with its ensure_rls ddl_command_end event trigger, and no application
 * tables. That state must classify PLATFORM_BOOTSTRAP_ONLY and baseline at
 * version 0 before migrating the full chain.
 *
 * Also proves the matcher is structural, not name-based: a same-named
 * routine without the documented signature/trigger configuration fails
 * closed, as does any unrelated postgres-owned routine.
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
        // Event triggers live outside public (pg_event_trigger) and would
        // dangle after drop schema cascade, so remove them first.
        jdbc.execute("drop event trigger if exists ensure_rls");
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
    }

    /** Verbatim from Supabase docs (guides/database/postgres/event-triggers). */
    private void createDocumentedRlsBootstrap() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION rls_auto_enable()
                RETURNS EVENT_TRIGGER
                LANGUAGE plpgsql
                SECURITY DEFINER
                SET search_path = pg_catalog
                AS $fn$
                DECLARE
                cmd record;
                BEGIN
                FOR cmd IN
                SELECT *
                FROM pg_event_trigger_ddl_commands()
                WHERE command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
                AND object_type IN ('table','partitioned table')
                LOOP
                IF cmd.schema_name IS NOT NULL AND cmd.schema_name IN ('public')
                   AND cmd.schema_name NOT IN ('pg_catalog','information_schema')
                   AND cmd.schema_name NOT LIKE 'pg_toast%'
                   AND cmd.schema_name NOT LIKE 'pg_temp%' THEN
                BEGIN
                EXECUTE format('alter table if exists %s enable row level security', cmd.object_identity);
                EXCEPTION
                WHEN OTHERS THEN
                RAISE;
                END;
                END IF;
                END LOOP;
                END;
                $fn$
                """);
        jdbc.execute("CREATE EVENT TRIGGER ensure_rls ON ddl_command_end "
                + "WHEN TAG IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO') "
                + "EXECUTE FUNCTION rls_auto_enable()");
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
    void productionReproductionSupabaseRlsBootstrapRoutineAndTriggerBaselinesAtZeroAndMigratesFully() {
        resetSchema();
        // Exact production state: documented bootstrap routine + its
        // ensure_rls event trigger, no application objects, no history.
        createDocumentedRlsBootstrap();

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
    void sameNameButWrongSignatureWithoutTriggerFailsClosed() {
        resetSchema();
        // Same routine name, none of the documented structural
        // characteristics: wrong return type, wrong language, no SECURITY
        // DEFINER, no search_path, and no ensure_rls event trigger.
        jdbc.execute("create function public.rls_auto_enable() returns int language sql "
                + "as $$ select 1 $$");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strategy.migrate(newFlyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing Flyway bootstrap recovery");
        Boolean fnExists = jdbc.queryForObject(
                "select to_regprocedure('public.rls_auto_enable()') is not null", Boolean.class);
        assertThat(fnExists).isTrue();
        Boolean historyExists = jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history') is not null", Boolean.class);
        assertThat(historyExists).isFalse();
    }

    @Test
    @Order(4)
    void sameSignatureButMissingEnsureRlsTriggerFailsClosed() {
        resetSchema();
        // Structurally correct routine, but no ensure_rls event trigger:
        // trigger linkage is mandatory for platform classification.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION rls_auto_enable()
                RETURNS EVENT_TRIGGER
                LANGUAGE plpgsql
                SECURITY DEFINER
                SET search_path = pg_catalog
                AS $fn$ BEGIN END; $fn$
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strategy.migrate(newFlyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing Flyway bootstrap recovery");
        Boolean historyExists = jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history') is not null", Boolean.class);
        assertThat(historyExists).isFalse();
    }

    @Test
    @Order(5)
    void unrelatedPostgresOwnedRoutineFailsClosed() {
        resetSchema();
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
    @Order(6)
    void unexpectedApplicationTableWithoutHistoryFailsClosed() {
        resetSchema();
        jdbc.execute("create table public.customer_data (id integer)");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strategy.migrate(newFlyway()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing Flyway bootstrap recovery");

        Boolean customerExists = jdbc.queryForObject(
                "select to_regclass('public.customer_data') is not null", Boolean.class);
        assertThat(customerExists).isTrue();
        Boolean historyExists = jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history') is not null", Boolean.class);
        assertThat(historyExists).isFalse();
    }

    @Test
    @Order(7)
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
    @Order(8)
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
