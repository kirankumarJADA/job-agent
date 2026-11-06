package com.personal.jobagent.config;

import org.flywaydb.core.Flyway;
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

    @Test
    void runsTheRequestedFlywayActionAgainstTheExternalDatabase() throws Exception {
        String url = System.getProperty("robin.flyway.external.url");
        String user = System.getProperty("robin.flyway.external.user", "robin_app");
        String password = System.getProperty("robin.flyway.external.password", "");
        String action = System.getProperty("robin.flyway.external.action", "validate");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("0")
                .placeholders(Map.of("remove_seed_dev_account", "true"))
                .load();

        switch (action) {
            case "validate" -> validate(flyway, jdbc);
            case "migrate" -> migrateDryRunSource(url, dataSource, flyway, jdbc);
            default -> throw new IllegalArgumentException("Unknown robin.flyway.external.action: " + action);
        }
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
