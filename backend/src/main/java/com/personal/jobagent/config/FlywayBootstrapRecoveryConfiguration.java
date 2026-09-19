package com.personal.jobagent.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class FlywayBootstrapRecoveryConfiguration {
    private static final Logger log = LoggerFactory.getLogger(FlywayBootstrapRecoveryConfiguration.class);

    private final DataSource dataSource;

    public FlywayBootstrapRecoveryConfiguration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs in place of the default Flyway migration call. Inspects the public
     * schema, classifies it, and only then migrates/baselines/fails closed.
     * Object *names* are logged (no credentials, no row contents).
     */
    @Bean
    FlywayMigrationStrategy guardedFlywayMigrationStrategy() {
        return flyway -> {
            FlywayBootstrapRecoveryPolicy.SchemaState state = inspect();
            FlywayBootstrapRecoveryPolicy.Classification classification =
                    FlywayBootstrapRecoveryPolicy.classify(state);
            FlywayBootstrapRecoveryPolicy.Action action = FlywayBootstrapRecoveryPolicy.decide(state);
            log.info("Flyway bootstrap diagnosis: historyTableExists={}, applicationTablesPresent={}, "
                            + "platformObjectsPresent={}, classification={}, selectedAction={}, "
                            + "applicationObjectNames={}, platformObjectNames={}",
                    state.historyExists(), !state.applicationObjects().isEmpty() || state.usersExists(),
                    !state.platformObjects().isEmpty(), classification, action,
                    state.applicationObjects(), state.platformObjects());
            switch (action) {
                case MIGRATE -> flyway.migrate();
                case BASELINE_AT_ZERO_THEN_MIGRATE -> {
                    flyway.baseline();
                    flyway.migrate();
                }
                case DROP_HISTORY_BASELINE_AT_ZERO_THEN_MIGRATE -> {
                    dropHistoryTable();
                    flyway.baseline();
                    flyway.migrate();
                }
                case FAIL_CLOSED -> throw new IllegalStateException(
                        "Refusing Flyway bootstrap recovery: unexpected public schema state " + state);
            }
        };
    }

    private FlywayBootstrapRecoveryPolicy.SchemaState inspect() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        boolean historyExists = Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history') is not null", Boolean.class));
        int historyRows = 0;
        int successfulRows = 0;
        int baselineRows = 0;
        if (historyExists) {
            // Rows left behind by a failed migration carry success = false and
            // are not part of a healthy history; only successful rows count.
            List<Integer> counts = jdbc.queryForObject("""
                    select count(*)::int,
                           count(*) filter (where success)::int,
                           count(*) filter (where type = 'BASELINE' and success)::int
                    from public.flyway_schema_history
                    """, (rs, rowNum) -> List.of(
                    rs.getInt(1), rs.getInt(2), rs.getInt(3)));
            historyRows = counts.get(0);
            successfulRows = counts.get(1);
            baselineRows = counts.get(2);
        }
        boolean usersExists = Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('public.users') is not null", Boolean.class));
        Set<String> platformObjects = new LinkedHashSet<>();
        Set<String> applicationObjects = new LinkedHashSet<>();
        jdbc.query("""
                select n.nspname || '.' || c.relname,
                       exists (
                         select 1
                         from pg_depend d
                         join pg_extension e on e.oid = d.refobjid
                         where d.classid = 'pg_class'::regclass
                           and d.objid = c.oid
                           and d.deptype = 'e'
                       ) as extension_owned
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public'
                  and c.relkind in ('r','p','v','m','f','S','c')
                  and c.relname <> 'flyway_schema_history'
                order by 1
                """, rs -> {
            String qualified = rs.getString(1);
            if (rs.getBoolean(2) || FlywayBootstrapRecoveryPolicy.isKnownPostgisObject(qualified)) {
                platformObjects.add(qualified);
            } else {
                applicationObjects.add(qualified);
            }
        });
        return new FlywayBootstrapRecoveryPolicy.SchemaState(
                historyExists, historyRows, successfulRows, baselineRows, usersExists, applicationObjects,
                platformObjects);
    }

    private void dropHistoryTable() {
        new JdbcTemplate(dataSource).execute("drop table public.flyway_schema_history");
    }
}
