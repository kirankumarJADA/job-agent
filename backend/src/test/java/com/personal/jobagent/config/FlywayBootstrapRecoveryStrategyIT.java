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
import java.util.UUID;

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

    // Connect as postgres, as on a real Supabase project: the database owner
    // is the postgres role, the documented rls_auto_enable bootstrap routine
    // is owned by it, and every migration object is created by it. With the
    // Testcontainers default user the routine's SECURITY DEFINER alter-table
    // statements fail with "permission denied for schema public", which is a
    // container artifact, not a production condition.
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withUsername("postgres");

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
        // The guarded strategy classifies the routine as the Supabase platform
        // bootstrap artifact only when it is owned by the postgres role —
        // that is who owns it on a real Supabase project. The container
        // superuser is whoever Testcontainers created, so create the postgres
        // role (idempotent) and transfer ownership to reproduce the
        // production condition exactly.
        jdbc.execute("do $$ begin "
                + "if not exists (select 1 from pg_roles where rolname = 'postgres') then "
                + "create role postgres; end if; end $$");
        jdbc.execute("alter function public.rls_auto_enable() owner to postgres");
        jdbc.execute("CREATE EVENT TRIGGER ensure_rls ON ddl_command_end "
                + "WHEN TAG IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO') "
                + "EXECUTE FUNCTION rls_auto_enable()");
    }

    /**
     * The number of versioned migrations shipped with this build, from the
     * classpath — the same source the guarded strategy itself verifies
     * against. Asserting a hardcoded count here went stale the moment V020+
     * were added; deriving it keeps the test's meaning ("the full shipped
     * chain applied") independent of how long the chain is.
     */
    private static int expectedMigrationCount() {
        try {
            try (var stream = java.util.Arrays.stream(
                    new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                            .getResources("classpath*:db/migration/V*.sql"))) {
                return (int) stream
                        .filter(r -> r.getFilename() != null
                                && r.getFilename().matches("V\\d+__.*\\.sql"))
                        .count();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to enumerate classpath migrations", e);
        }
    }

    private static String expectedMaxVersion() {
        try {
            try (var stream = java.util.Arrays.stream(
                    new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                            .getResources("classpath*:db/migration/V*.sql"))) {
                int max = stream
                        .mapToInt(r -> {
                            var name = r.getFilename() == null ? "" : r.getFilename();
                            var matcher = java.util.regex.Pattern
                                    .compile("V(\\d+)__.*\\.sql").matcher(name);
                            return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
                        })
                        .filter(v -> v > 0)
                        .max()
                        .orElseThrow(() -> new IllegalStateException("No versioned migrations on the classpath"));
                return "%03d".formatted(max);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to enumerate classpath migrations", e);
        }
    }

    private void assertFullyMigrated() {
        Integer migrationCount = jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where success and type <> 'BASELINE'",
                Integer.class);
        assertThat(migrationCount).isEqualTo(expectedMigrationCount());
        String current = jdbc.queryForObject(
                "select max(version) from public.flyway_schema_history where success", String.class);
        assertThat(current).isEqualTo(expectedMaxVersion());
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
    void recoveredBaselineAtZeroStateIsIdempotentlyRecognizedAsMigrated() {
        // Reproduce the exact current Render state: the guarded recovery
        // produced one successful version-0 baseline followed by the
        // complete successful V001..V019 chain and the application schema,
        // then Render restarted the container against the same database.
        // The strategy must classify MIGRATED and execute plain migrate()
        // with zero history changes on every subsequent startup.
        resetSchema();
        createDocumentedRlsBootstrap();
        strategy.migrate(newFlyway()); // first startup: baseline 0 + full chain
        assertFullyMigrated();
        Integer baselineRows = jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where type = 'BASELINE'", Integer.class);
        assertThat(baselineRows).isEqualTo(1);

        List<String> before = jdbc.queryForList(
                "select installed_rank || ':' || version || ':' || type || ':' || success "
                        + "from public.flyway_schema_history order by installed_rank", String.class);
        // One version-0 baseline followed by the complete shipped chain.
        assertThat(before).hasSize(expectedMigrationCount() + 1);

        // Second and third startups: MIGRATED -> plain migrate(), no changes.
        strategy.migrate(newFlyway());
        strategy.migrate(newFlyway());

        List<String> after = jdbc.queryForList(
                "select installed_rank || ':' || version || ':' || type || ':' || success "
                        + "from public.flyway_schema_history order by installed_rank", String.class);
        assertThat(after).isEqualTo(before);
        assertFullyMigrated();
    }

    @Test
    @Order(9)
    void alreadyMigratedDatabaseIsLeftIntact() {
        // Build a fully migrated state, snapshot the history, run the
        // strategy again, and prove nothing changed.
        resetSchema();
        strategy.migrate(newFlyway());
        List<String> before = jdbc.queryForList(
                "select version from public.flyway_schema_history order by installed_rank", String.class);
        assertThat(before.size()).isEqualTo(expectedMigrationCount());
        strategy.migrate(newFlyway());
        List<String> after = jdbc.queryForList(
                "select version from public.flyway_schema_history order by installed_rank", String.class);
        assertThat(after).isEqualTo(before);
        assertFullyMigrated();
    }

    @Test
    @Order(10)
    void renderProductionStateBaselinedThroughV019ContinuesToTheShippedChain() {
        // The exact production state observed on Render (2026-09): an earlier
        // deploy of this same guarded recovery baselined the Supabase
        // bootstrap schema at zero and applied the full chain its jar shipped
        // (V001..V019); the application tables and real data exist; the
        // newer jar ships V020..V023. The strategy must classify this as a
        // pending upgrade and continue with plain migrate() — not fail
        // closed — and the upgrade must preserve every row.
        resetSchema();
        createDocumentedRlsBootstrap();

        // Emulate the earlier deploy: baseline at zero, then migrate only as
        // far as that jar's chain went.
        Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("0")
                .load()
                .baseline();
        Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .target("19")
                .load()
                .migrate();

        // Production-like data written before the upgrade, on the V019
        // schema. The V002 seed user/profile is part of the applied chain and
        // therefore present in production too — it is the single profile the
        // legacy rows can provably belong to.
        UUID jobId = UUID.fromString("00000000-0000-7000-8000-00000000cd03");
        UUID applicationId = UUID.fromString("00000000-0000-7000-8000-00000000cd04");
        UUID auditId = UUID.fromString("00000000-0000-7000-8000-00000000cd05");
        UUID sourceId = UUID.fromString("00000000-0000-7000-8000-00000000cd06");
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities)
                values (?, 'MANUAL_IMPORT', 'render-org', 'Render source', '{}')
                """, sourceId);
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, status, content_hash,
                                  match_score, match_recommendation, match_breakdown)
                values (?, ?, 'render-ext-1', 'render-dedup-1', 'Render Corp', 'Engineer', 'desc', 'DISCOVERED', 'render-hash-1',
                        87, 'APPLY', '{"skill_overlap": 87}'::jsonb)
                """, jobId, sourceId);
        jdbc.update("insert into applications (id, job_id, status, mode) values (?, ?, 'READY_TO_APPLY', 'ASSISTED')",
                applicationId, jobId);
        jdbc.update("insert into audit_logs (id, actor, action, entity_type) values (?, ?, 'LOGIN_SUCCESS', 'USER')",
                auditId, "dev@example.local");

        // Exactly the numbers from the Render logs: 20 history rows (1
        // baseline + 19 migrations) against a build shipping 23.
        Integer historyRows = jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history", Integer.class);
        assertThat(historyRows).isEqualTo(20);

        // Before the fix this threw "Refusing Flyway bootstrap recovery:
        // unexpected public schema state". It must instead continue the chain.
        strategy.migrate(newFlyway());

        assertFullyMigrated();

        // ── every pre-existing row preserved, nothing duplicated ──
        // The seeded account survives untouched and V020 backfills it as a
        // LOCAL credential without rewriting anything.
        String seedEmail = jdbc.queryForObject(
                "select email::text from users where email = 'dev@example.local'", String.class);
        assertThat(seedEmail).isEqualTo("dev@example.local");
        String authProvider = jdbc.queryForObject(
                "select auth_provider from users where email = 'dev@example.local'", String.class);
        assertThat(authProvider).isEqualTo("LOCAL");

        Integer profileCount = jdbc.queryForObject("select count(*) from profiles", Integer.class);
        assertThat(profileCount).isEqualTo(1);
        UUID seedProfileId = jdbc.queryForObject("select id from profiles", UUID.class);

        // V022's single-profile attribution claims the legacy application for
        // the only profile that could have owned it.
        UUID attributedProfile = jdbc.queryForObject(
                "select profile_id from applications where id = ?", UUID.class, applicationId);
        assertThat(attributedProfile).isEqualTo(seedProfileId);
        String applicationStatus = jdbc.queryForObject(
                "select status from applications where id = ?", String.class, applicationId);
        assertThat(applicationStatus).isEqualTo("READY_TO_APPLY");

        // V022 moves the per-user match decision off the shared jobs row into
        // job_matches — data preserved, not duplicated.
        var match = jdbc.queryForMap(
                "select score, recommendation from job_matches where job_id = ? and profile_id = ?",
                jobId, seedProfileId);
        assertThat(match.get("score")).isEqualTo(87);
        assertThat(match.get("recommendation")).isEqualTo("APPLY");
        Integer matchColumnCount = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'jobs' and column_name like 'match%'
                """, Integer.class);
        assertThat(matchColumnCount).isZero();

        // The audit row survives append-only (V008) with its owner link
        // retained as a historical reference (V023 dropped the purge-blocking
        // foreign key).
        Integer auditCount = jdbc.queryForObject(
                "select count(*) from audit_logs where id = ?", Integer.class, auditId);
        assertThat(auditCount).isEqualTo(1);

        // The recovery's own baseline artifact is still exactly one row.
        Integer baselineRows = jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where type = 'BASELINE'", Integer.class);
        assertThat(baselineRows).isEqualTo(1);
    }
}
