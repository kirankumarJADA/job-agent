package com.personal.jobagent.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
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
     * Supabase platform roles. Objects owned by these roles in public on a
     * never-migrated project are platform bootstrap artifacts, not user data.
     * The user's own admin role ("postgres") is deliberately NOT in this set:
     * anything created by the operator fails closed.
     */
    private static final String PLATFORM_OWNER_PREFIX = "supabase_";

    /**
     * Runs in place of the default Flyway migration call. Enumerates every
     * object Flyway itself considers when deciding schema emptiness
     * (relations, types, routines — see PostgreSQLSchema.empty()), logs a
     * safe diagnosis, and only then migrates/baselines/fails closed.
     * Object names, kinds and owners are logged; never credentials or row
     * contents.
     */
    @Bean
    FlywayMigrationStrategy guardedFlywayMigrationStrategy() {
        return flyway -> {
            FlywayBootstrapRecoveryPolicy.SchemaState state = inspect();
            FlywayBootstrapRecoveryPolicy.Classification classification =
                    FlywayBootstrapRecoveryPolicy.classify(state);
            FlywayBootstrapRecoveryPolicy.Action action = FlywayBootstrapRecoveryPolicy.decide(state);
            log.info("Flyway bootstrap diagnosis: historyTableExists={}, applicationTablesPresent={}, "
                            + "platformObjectsPresent={}, classification={}, selectedAction={}",
                    state.historyExists(),
                    !state.applicationObjects().isEmpty() || state.usersExists(),
                    !state.platformObjects().isEmpty(), classification, action);
            log.info("Flyway bootstrap detected objects (kind schema.name [owner, extension]): {}",
                    state.objectDetails());
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

        // Mirrors Flyway 10.10.0 PostgreSQLSchema.empty() exactly: relations
        // (r/v/S/t), types (typcategory NOT IN ('A','C')) and routines, each
        // minus extension-owned rows. Any object set Flyway would call
        // non-empty must be visible here so FRESH always agrees with Flyway.
        record CatalogObject(String kind, String qualified, boolean extensionOwned,
                             String extensionName, String owner) {}
        List<CatalogObject> objects = jdbc.query("""
                select kind, qualified, extension_owned, ext_name, owner from (
                  select 'RELATION/' || case c.relkind
                           when 'r' then 'TABLE' when 'v' then 'VIEW'
                           when 'S' then 'SEQUENCE' when 't' then 'TOAST_TABLE'
                           else c.relkind::text end as kind,
                         n.nspname || '.' || c.relname as qualified,
                         (d.objid is not null) as extension_owned,
                         e.extname as ext_name,
                         ro.rolname as owner
                  from pg_catalog.pg_class c
                  join pg_catalog.pg_namespace n on n.oid = c.relnamespace
                  left join pg_catalog.pg_depend d on d.objid = c.oid and d.deptype = 'e'
                  left join pg_catalog.pg_extension e on e.oid = d.refobjid
                  left join pg_catalog.pg_roles ro on ro.oid = c.relowner
                  where n.nspname = 'public' and c.relkind in ('r','v','S','t')
                    -- Flyway's own metadata table is not schema content: its
                    -- emptiness check only runs when the history table is absent.
                    and c.relname <> 'flyway_schema_history'
                  union all
                  select 'TYPE',
                         n.nspname || '.' || t.typname,
                         (d.objid is not null), e.extname, ro.rolname
                  from pg_catalog.pg_type t
                  join pg_catalog.pg_namespace n on n.oid = t.typnamespace
                  left join pg_catalog.pg_depend d on d.objid = t.oid and d.deptype = 'e'
                  left join pg_catalog.pg_extension e on e.oid = d.refobjid
                  left join pg_catalog.pg_roles ro on ro.oid = t.typowner
                  where n.nspname = 'public' and t.typcategory not in ('A','C')
                  union all
                  select 'ROUTINE',
                         n.nspname || '.' || p.proname,
                         (d.objid is not null), e.extname, ro.rolname
                  from pg_catalog.pg_proc p
                  join pg_catalog.pg_namespace n on n.oid = p.pronamespace
                  left join pg_catalog.pg_depend d on d.objid = p.oid and d.deptype = 'e'
                  left join pg_catalog.pg_extension e on e.oid = d.refobjid
                  left join pg_catalog.pg_roles ro on ro.oid = p.proowner
                  where n.nspname = 'public'
                ) q
                order by qualified
                """, (rs, rowNum) -> new CatalogObject(
                rs.getString(1), rs.getString(2), rs.getBoolean(3),
                rs.getString(4), rs.getString(5)));

        Set<String> platformObjects = new LinkedHashSet<>();
        Set<String> applicationObjects = new LinkedHashSet<>();
        List<String> details = new ArrayList<>();
        for (CatalogObject object : objects) {
            boolean platform = object.extensionOwned()
                    || FlywayBootstrapRecoveryPolicy.isKnownPostgisObject(object.qualified())
                    || (object.owner() != null && object.owner().startsWith(PLATFORM_OWNER_PREFIX));
            (platform ? platformObjects : applicationObjects).add(object.qualified());
            details.add(object.kind() + " " + object.qualified()
                    + " [owner=" + object.owner()
                    + (object.extensionOwned() ? ", extension=" + object.extensionName() : "")
                    + (platform ? ", PLATFORM" : ", APPLICATION") + "]");
        }
        return new FlywayBootstrapRecoveryPolicy.SchemaState(
                historyExists, historyRows, successfulRows, baselineRows, usersExists,
                applicationObjects, platformObjects, details);
    }

    private void dropHistoryTable() {
        new JdbcTemplate(dataSource).execute("drop table public.flyway_schema_history");
    }
}
