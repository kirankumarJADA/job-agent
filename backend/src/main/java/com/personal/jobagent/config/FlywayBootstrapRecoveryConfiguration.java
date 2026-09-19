package com.personal.jobagent.config;

import org.flywaydb.core.Flyway;
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
    private final DataSource dataSource;

    public FlywayBootstrapRecoveryConfiguration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    FlywayMigrationStrategy guardedFlywayMigrationStrategy() {
        return flyway -> {
            FlywayBootstrapRecoveryPolicy.SchemaState state = inspect();
            FlywayBootstrapRecoveryPolicy.Action action = FlywayBootstrapRecoveryPolicy.decide(state);
            switch (action) {
                case MIGRATE -> flyway.migrate();
                case BASELINE_ZERO_THEN_MIGRATE -> {
                    flyway.baseline();
                    flyway.migrate();
                }
                case DROP_HISTORY_BASELINE_ZERO_THEN_MIGRATE -> {
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
            List<Integer> counts = jdbc.queryForObject("""
                    select count(*)::int,
                           count(*) filter (where success)::int,
                           count(*) filter (where type = 'BASELINE' and success)::int
                    from public.flyway_schema_history
                    """, (rs, rowNum) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
            historyRows = counts.get(0);
            successfulRows = counts.get(1);
            baselineRows = counts.get(2);
        }
        boolean usersExists = Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('public.users') is not null", Boolean.class));
        Set<String> nonExtensionObjects = new LinkedHashSet<>(jdbc.query("""
                select n.nspname || '.' || c.relname
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public'
                  and c.relname <> 'flyway_schema_history'
                  and c.relkind in ('r','p','v','m','f','S','c')
                  and not exists (
                    select 1
                    from pg_depend d
                    join pg_extension e on e.oid = d.refobjid
                    where d.classid = 'pg_class'::regclass
                      and d.objid = c.oid
                      and d.deptype = 'e'
                  )
                order by 1
                """, (rs, rowNum) -> rs.getString(1)));
        return new FlywayBootstrapRecoveryPolicy.SchemaState(
                historyExists, historyRows, successfulRows, baselineRows, usersExists, nonExtensionObjects);
    }

    private void dropHistoryTable() {
        new JdbcTemplate(dataSource).execute("drop table public.flyway_schema_history");
    }
}
