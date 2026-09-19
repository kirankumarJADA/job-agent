package com.personal.jobagent.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Array;
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
     * anything created by the operator fails closed unless it matches the
     * single documented bootstrap routine below.
     */
    private static final String PLATFORM_OWNER_PREFIX = "supabase_";

    /**
     * Runs in place of the default Flyway migration call. Enumerates every
     * object Flyway itself considers when deciding schema emptiness
     * (relations, types, routines — see PostgreSQLSchema.empty()), logs a
     * safe diagnosis, and only then migrates/baselines/fails closed.
     * Object names, kinds, owners and match criteria are logged; never
     * credentials or function bodies.
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
            log.info("Flyway bootstrap detected objects (kind schema.name [owner, extension, classification"
                    + ", rls_bootstrap_criteria_unmet]): {}", state.objectDetails());
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
        //
        // The routine branch additionally recognizes exactly one documented
        // Supabase platform bootstrap routine (guides/database/postgres/
        // event-triggers): public.rls_auto_enable() returning event_trigger,
        // zero-arg, plpgsql, SECURITY DEFINER, search_path=pg_catalog, owned
        // by postgres, and bound to the ensure_rls ddl_command_end event
        // trigger covering CREATE TABLE / CREATE TABLE AS / SELECT INTO.
        // Function name alone is never sufficient; every structural
        // characteristic must match, and unmet criteria are logged.
        record CatalogObject(String kind, String qualified, boolean extensionOwned, String extensionName,
                             String owner, boolean supabaseRlsBootstrap, List<String> rlsUnmet) {}
        List<CatalogObject> objects = jdbc.query("""
                select kind, qualified, extension_owned, ext_name, owner, supabase_rls_bootstrap, rls_unmet
                from (
                  select 'RELATION/' || case c.relkind
                           when 'r' then 'TABLE' when 'v' then 'VIEW'
                           when 'S' then 'SEQUENCE' when 't' then 'TOAST_TABLE'
                           else c.relkind::text end as kind,
                         n.nspname || '.' || c.relname as qualified,
                         (d.objid is not null) as extension_owned,
                         e.extname as ext_name,
                         ro.rolname as owner,
                         false as supabase_rls_bootstrap,
                         null::text[] as rls_unmet
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
                         (d.objid is not null), e.extname, ro.rolname,
                         false, null::text[]
                  from pg_catalog.pg_type t
                  join pg_catalog.pg_namespace n on n.oid = t.typnamespace
                  left join pg_catalog.pg_depend d on d.objid = t.oid and d.deptype = 'e'
                  left join pg_catalog.pg_extension e on e.oid = d.refobjid
                  left join pg_catalog.pg_roles ro on ro.oid = t.typowner
                  where n.nspname = 'public' and t.typcategory not in ('A','C')
                  union all
                  select 'ROUTINE', q.qualified, q.extension_owned, q.ext_name, q.owner,
                         (q.rls_unmet is not null and cardinality(q.rls_unmet) = 0),
                         q.rls_unmet
                  from (
                    select n.nspname || '.' || p.proname as qualified,
                           (d.objid is not null) as extension_owned,
                           e.extname as ext_name,
                           ro.rolname as owner,
                           case when p.proname = 'rls_auto_enable' then
                             array_remove(array[
                               case when pg_catalog.pg_get_function_identity_arguments(p.oid) = ''
                                         and p.pronargs = 0
                                    then null else 'arguments_not_zero_arg' end,
                               case when pg_catalog.format_type(p.prorettype, null) = 'event_trigger'
                                    then null else 'return_type_not_event_trigger' end,
                               case when l.lanname = 'plpgsql'
                                    then null else 'language_not_plpgsql' end,
                               case when p.prosecdef
                                    then null else 'not_security_definer' end,
                               case when p.proconfig is not null
                                         and 'search_path=pg_catalog' = any(p.proconfig)
                                    then null else 'search_path_not_pg_catalog' end,
                               case when ro.rolname = 'postgres'
                                    then null else 'owner_not_postgres' end,
                               case when exists (
                                      select 1 from pg_catalog.pg_event_trigger et
                                      where et.evtfoid = p.oid
                                        and et.evtname = 'ensure_rls'
                                        and et.evtevent = 'ddl_command_end'
                                        and et.evtenabled <> 'D'
                                        and et.evttags @> array['CREATE TABLE','CREATE TABLE AS','SELECT INTO'])
                                    then null else 'ensure_rls_event_trigger_missing_or_mismatched' end
                             ], null)
                           end as rls_unmet
                    from pg_catalog.pg_proc p
                    join pg_catalog.pg_namespace n on n.oid = p.pronamespace
                    left join pg_catalog.pg_depend d on d.objid = p.oid and d.deptype = 'e'
                    left join pg_catalog.pg_extension e on e.oid = d.refobjid
                    left join pg_catalog.pg_roles ro on ro.oid = p.proowner
                    left join pg_catalog.pg_language l on l.oid = p.prolang
                    where n.nspname = 'public'
                  ) q
                ) all_objects
                order by qualified
                """, (rs, rowNum) -> {
            Array unmet = rs.getArray(7);
            List<String> unmetList = unmet == null ? List.of() : List.of((String[]) unmet.getArray());
            return new CatalogObject(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                    rs.getString(5), rs.getBoolean(6), unmetList);
        });

        Set<String> platformObjects = new LinkedHashSet<>();
        Set<String> applicationObjects = new LinkedHashSet<>();
        List<String> details = new ArrayList<>();
        for (CatalogObject object : objects) {
            boolean platform = object.extensionOwned()
                    || object.supabaseRlsBootstrap()
                    || FlywayBootstrapRecoveryPolicy.isKnownPostgisObject(object.qualified())
                    || (object.owner() != null && object.owner().startsWith(PLATFORM_OWNER_PREFIX));
            (platform ? platformObjects : applicationObjects).add(object.qualified());
            StringBuilder detail = new StringBuilder(object.kind() + " " + object.qualified()
                    + " [owner=" + object.owner()
                    + (object.extensionOwned() ? ", extension=" + object.extensionName() : "")
                    + (object.supabaseRlsBootstrap() ? ", supabase_rls_bootstrap=matched" : "")
                    + (platform ? ", PLATFORM" : ", APPLICATION"));
            if ("ROUTINE".equals(object.kind()) && object.qualified().endsWith(".rls_auto_enable")
                    && !platform && !object.rlsUnmet().isEmpty()) {
                detail.append(", rls_bootstrap_criteria_unmet=").append(object.rlsUnmet());
            }
            detail.append("]");
            details.add(detail.toString());
        }
        return new FlywayBootstrapRecoveryPolicy.SchemaState(
                historyExists, historyRows, successfulRows, baselineRows, usersExists,
                applicationObjects, platformObjects, details);
    }

    private void dropHistoryTable() {
        new JdbcTemplate(dataSource).execute("drop table public.flyway_schema_history");
    }
}
