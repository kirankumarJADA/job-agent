package com.personal.jobagent.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operator tool for the database migration (docs/ROBIN_DB_MIGRATION_PHASE2_PLAN.md,
 * scripts/db-migration). It runs Flyway against an EXTERNAL database, configured the
 * way the application configures it ({@code spring.flyway} in application.yml, as
 * {@link FlywayBootstrapRecoveryStrategyIT} also does).
 *
 * <p>The test is disabled unless {@code -Drobin.flyway.external.url} is set, so ordinary
 * builds and CI never run it. Modes, chosen with {@code -Drobin.flyway.external.action}:
 * <ul>
 *   <li>{@code validate} (default), used in Stage C against the isolated restored copy.
 *       The database must hold every versioned migration shipped with this build, all
 *       successful, with none pending and no checksum mismatch. It changes nothing.</li>
 *   <li>{@code validate-restore}, used by restore-test.ps1 against a restored archive. As
 *       {@code validate}, except that migrations shipped after the archive was taken may be
 *       pending: the restored history must be an unbroken prefix of the shipped chain, so
 *       every pending migration is newer than the current version, and none is failed,
 *       ignored, changed or unknown to this build. The pending versions are reported as
 *       {@code pendingUpgrade=}; the application applies them at its first start. It changes
 *       nothing.</li>
 *   <li>{@code migrate}, used only to build the Stage A dry-run source, with the
 *       application's own guarded bootstrap strategy. It refuses to run unless all three
 *       hold: the URL is loopback, the database carries the dry-run marker comment, and
 *       {@code -Drobin.flyway.external.allow-migrate=dry-run-only} is set.</li>
 * </ul>
 * The password is that of a throwaway local container, never a production credential.
 */
@EnabledIfSystemProperty(named = "robin.flyway.external.url", matches = ".+",
        disabledReason = "operator tool: set -Drobin.flyway.external.url to run it")
class ExternalDatabaseFlywayIT {

    static final String DRY_RUN_MARKER = "robin-dryrun-source";
    private static final Pattern LOOPBACK_URL =
            Pattern.compile("jdbc:postgresql://(127\\.0\\.0\\.1|localhost)(:\\d+)?/[^?]+(\\?.*)?");
    private static final Pattern ALLOWED_RESTORE_URL =
            Pattern.compile("jdbc:postgresql://(127\\.0\\.0\\.1|localhost|132\\.226\\.133\\.162)(:\\d+)?/[^?]+(\\?.*)?");

    @Test
    void runsTheRequestedFlywayActionAgainstTheExternalDatabase() throws Exception {
        String url = System.getProperty("robin.flyway.external.url");
        String user = System.getProperty("robin.flyway.external.user", "robin_app");
        String password = externalPassword();
        String action = System.getProperty("robin.flyway.external.action", "validate");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        switch (action) {
            case "validate" -> validate(configure(dataSource).load(), jdbc);
            // Only pending migrations are tolerated; "future" ones (applied in the database
            // but unknown to this build) are no longer ignored, so they fail validation.
            case "validate-restore" -> validateRestore(
                    configure(dataSource).ignoreMigrationPatterns("*:pending").load(), jdbc);
            case "migrate" -> migrateDryRunSource(url, dataSource, configure(dataSource).load(), jdbc);
            case "migrate-restore" -> migrateRestoredCopy(url, dataSource, configure(dataSource).load(), jdbc);
            case "migrate-clean" -> migrateClean(url, dataSource, configure(dataSource).load(), jdbc);
            default -> throw new IllegalArgumentException("Unknown robin.flyway.external.action: " + action);
        }
    }

    private static void migrateRestoredCopy(String url, DriverManagerDataSource dataSource,
                                            Flyway flyway, JdbcTemplate jdbc) throws Exception {
        assertThat(url).as("migrate-restore only runs against an authorized loopback or staging host").matches(ALLOWED_RESTORE_URL);
        assertThat(System.getProperty("robin.flyway.external.allow-migrate"))
                .as("explicit restore-test opt-in").isEqualTo("restore-test");
        String dbName = jdbc.queryForObject("select current_database()", String.class);
        assertThat(dbName).as("target database name").isEqualTo("robin_restore");

        new FlywayBootstrapRecoveryConfiguration(dataSource).guardedFlywayMigrationStrategy().migrate(flyway);

        History history = History.read(jdbc);
        int expected = shippedMigrationCount();
        boolean ok = history.failed() == 0 && history.versioned() == expected && "052".equals(history.maxVersion());
        report((ok ? "ROBIN_FLYWAY migrate PASS " : "ROBIN_FLYWAY migrate FAIL ") + history + " shipped=" + expected);
        assertThat(history.failed()).as("failed history rows").isZero();
        assertThat(history.versioned()).as("applied versioned migrations").isEqualTo(expected);
        assertThat(history.maxVersion()).as("max version reached").isEqualTo("052");
    }

    private static void migrateClean(String url, DriverManagerDataSource dataSource,
                                     Flyway flyway, JdbcTemplate jdbc) throws Exception {
        assertThat(url).as("migrate-clean only runs against an authorized loopback or staging host").matches(ALLOWED_RESTORE_URL);
        assertThat(System.getProperty("robin.flyway.external.allow-migrate"))
                .as("explicit clean-init opt-in").isEqualTo("clean-init");
        String dbName = jdbc.queryForObject("select current_database()", String.class);
        assertThat(dbName).as("target database name").isEqualTo("robin");

        Integer tableCount = jdbc.queryForObject(
                "select count(*)::int from information_schema.tables where table_schema = 'public'", Integer.class);
        assertThat(tableCount).as("database must be completely empty before clean migrate").isZero();

        new FlywayBootstrapRecoveryConfiguration(dataSource).guardedFlywayMigrationStrategy().migrate(flyway);

        // Remove dev/seed mock rows to ensure a pristine production schema with zero fake entities
        jdbc.execute("truncate table jobs cascade");
        jdbc.execute("delete from job_sources where org_identifier in ('examplefintech', 'examplecloud')");
        jdbc.execute("delete from companies where slug in ('example-fintech', 'example-cloud')");
        jdbc.execute("delete from users where email = 'dev@example.local'");

        History history = History.read(jdbc);
        int expected = shippedMigrationCount();
        boolean ok = history.failed() == 0 && history.versioned() == expected && "052".equals(history.maxVersion());
        report((ok ? "ROBIN_FLYWAY migrate PASS " : "ROBIN_FLYWAY migrate FAIL ") + history + " shipped=" + expected);
        assertThat(history.failed()).as("failed history rows").isZero();
        assertThat(history.versioned()).as("applied versioned migrations").isEqualTo(expected);
        assertThat(history.maxVersion()).as("max version reached").isEqualTo("052");

        // Verify clean state
        Integer userCount = jdbc.queryForObject("select count(*)::int from users", Integer.class);
        Integer jobCount = jdbc.queryForObject("select count(*)::int from jobs", Integer.class);
        assertThat(userCount).as("users count after clean initialization").isZero();
        assertThat(jobCount).as("jobs count after clean initialization").isZero();
    }

    /**
     * The password comes from the environment variable ROBIN_EXTERNAL_DB_PASSWORD
     * (scripts/db-migration/common.ps1 sets it for the Maven process only), so it never
     * appears on a command line or in the failsafe report, which records system properties.
     * The system property remains as a fallback for throwaway local containers.
     */
    private static String externalPassword() {
        String fromEnv = System.getenv("ROBIN_EXTERNAL_DB_PASSWORD");
        return fromEnv != null && !fromEnv.isEmpty() ? fromEnv : System.getProperty("robin.flyway.external.password", "");
    }

    private static FluentConfiguration configure(DriverManagerDataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("0")
                .placeholders(Map.of("remove_seed_dev_account", "true"));
    }

    private static void validate(Flyway flyway, JdbcTemplate jdbc) throws Exception {
        ValidateResult result = flyway.validateWithResult();
        int pending = flyway.info().pending().length;
        int invalid = result.invalidMigrations == null ? 0 : result.invalidMigrations.size();
        History history = History.read(jdbc);
        int expected = shippedMigrationCount();
        boolean ok = result.validationSuccessful && pending == 0 && invalid == 0
                && history.failed() == 0 && history.versioned() == expected;
        report((ok ? "ROBIN_FLYWAY validate PASS " : "ROBIN_FLYWAY validate FAIL ")
                + history + " shipped=" + expected + " pending=" + pending + " invalid=" + invalid);

        assertThat(result.validationSuccessful).as("Flyway validate").isTrue();
        assertThat(pending).as("pending migrations").isZero();
        assertThat(invalid).as("invalid migrations").isZero();
        assertThat(history.failed()).as("failed history rows").isZero();
        assertThat(history.versioned()).as("applied versioned migrations").isEqualTo(expected);
    }

    private static void validateRestore(Flyway flyway, JdbcTemplate jdbc) throws Exception {
        ValidateResult result = flyway.validateWithResult();
        MigrationInfo[] pendingMigrations = flyway.info().pending();
        MigrationInfo currentMigration = flyway.info().current();
        MigrationVersion current = currentMigration == null || currentMigration.getVersion() == null
                ? MigrationVersion.EMPTY : currentMigration.getVersion();
        int pending = pendingMigrations.length;
        int invalid = result.invalidMigrations == null ? 0 : result.invalidMigrations.size();
        boolean newerOnly = Arrays.stream(pendingMigrations)
                .allMatch(m -> m.getVersion() != null && m.getVersion().compareTo(current) > 0);
        String upgrade = pending == 0 ? "none" : Arrays.stream(pendingMigrations)
                .map(m -> String.valueOf(m.getVersion())).collect(Collectors.joining(","));
        History history = History.read(jdbc);
        int expected = shippedMigrationCount();
        boolean ok = result.validationSuccessful && invalid == 0 && newerOnly
                && history.failed() == 0 && history.versioned() + pending == expected;
        report((ok ? "ROBIN_FLYWAY validate PASS " : "ROBIN_FLYWAY validate FAIL ")
                + history + " shipped=" + expected + " pending=" + pending + " invalid=" + invalid
                + " pendingUpgrade=" + upgrade);

        assertThat(result.validationSuccessful).as("Flyway validate, pending migrations allowed").isTrue();
        assertThat(invalid).as("invalid migrations").isZero();
        assertThat(newerOnly).as("every pending migration is newer than the restored version " + current).isTrue();
        assertThat(history.failed()).as("failed history rows").isZero();
        assertThat(history.versioned() + pending).as("applied plus pending = shipped").isEqualTo(expected);
    }

    private static void migrateDryRunSource(String url, DriverManagerDataSource dataSource,
                                            Flyway flyway, JdbcTemplate jdbc) throws Exception {
        assertThat(url).as("dry-run migrate only runs against a loopback database").matches(LOOPBACK_URL);
        assertThat(System.getProperty("robin.flyway.external.allow-migrate"))
                .as("explicit dry-run opt-in").isEqualTo("dry-run-only");
        String marker = jdbc.queryForObject(
                "select shobj_description(oid, 'pg_database') from pg_database where datname = current_database()",
                String.class);
        assertThat(marker).as("dry-run marker comment on the database").isEqualTo(DRY_RUN_MARKER);

        new FlywayBootstrapRecoveryConfiguration(dataSource).guardedFlywayMigrationStrategy().migrate(flyway);

        History history = History.read(jdbc);
        int expected = shippedMigrationCount();
        boolean ok = history.failed() == 0 && history.versioned() == expected;
        report((ok ? "ROBIN_FLYWAY migrate PASS " : "ROBIN_FLYWAY migrate FAIL ") + history + " shipped=" + expected);
        assertThat(history.failed()).as("failed history rows").isZero();
        assertThat(history.versioned()).as("applied versioned migrations").isEqualTo(expected);
    }

    private record History(int rows, int versioned, int baseline, int failed, String maxVersion) {
        static History read(JdbcTemplate jdbc) {
            return jdbc.queryForObject("""
                    select count(*)::int,
                           (count(*) filter (where success and type <> 'BASELINE'))::int,
                           (count(*) filter (where type = 'BASELINE'))::int,
                           (count(*) filter (where not success))::int,
                           coalesce(max(version) filter (where success), '')
                    from flyway_schema_history
                    """, (rs, rowNum) -> new History(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                    rs.getString(5)));
        }

        @Override
        public String toString() {
            return "historyRows=" + rows + " applied=" + versioned + " baseline=" + baseline
                    + " failed=" + failed + " current=" + maxVersion;
        }
    }

    private static int shippedMigrationCount() throws Exception {
        return (int) Arrays.stream(new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/migration/V*.sql"))
                .filter(r -> r.getFilename() != null && r.getFilename().matches("V\\d+__.*\\.sql"))
                .count();
    }

    private static void report(String line) throws Exception {
        System.out.println(line);
        String path = System.getProperty("robin.flyway.external.report");
        if (path != null && !path.isBlank()) {
            Files.writeString(Path.of(path), line + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }
}
