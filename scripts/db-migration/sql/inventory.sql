-- Object inventory of one schema as sorted key|value lines (psql -At -F '|').
-- Catalog-only and cheap. Used on the source (inside the backup snapshot)
-- and on the restored copy; the two outputs are compared key by key.
--
-- Input: psql variable inv_schema (e.g. public).
-- Keys "owners" and "extensions" are informational: they are expected to
-- differ after a --no-owner restore into a vanilla PostgreSQL.
SELECT k, v FROM (
  SELECT 'tables' AS k, count(*)::text AS v
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND c.relkind = 'r'
  UNION ALL
  SELECT 'partitioned_tables', count(*)::text
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND c.relkind = 'p'
  UNION ALL
  SELECT 'views', count(*)::text
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND c.relkind = 'v'
  UNION ALL
  SELECT 'materialized_views', count(*)::text
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND c.relkind = 'm'
  UNION ALL
  SELECT 'sequences', count(*)::text
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND c.relkind = 'S'
  UNION ALL
  SELECT 'indexes', count(*)::text
    FROM pg_catalog.pg_index i
    JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema'
  UNION ALL
  SELECT 'triggers', count(*)::text
    FROM pg_catalog.pg_trigger t
    JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND NOT t.tgisinternal
  UNION ALL
  SELECT 'functions_non_extension', count(*)::text
    FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = :'inv_schema'
     AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                      WHERE d.classid = 'pg_catalog.pg_proc'::regclass
                        AND d.objid = p.oid AND d.deptype = 'e')
  UNION ALL
  SELECT 'constraints_' || co.contype::text, count(*)::text
    FROM pg_catalog.pg_constraint co
    JOIN pg_catalog.pg_class c ON c.oid = co.conrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema'
   GROUP BY co.contype
  UNION ALL
  SELECT 'constraints_not_validated', count(*)::text
    FROM pg_catalog.pg_constraint co
    JOIN pg_catalog.pg_class c ON c.oid = co.conrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND NOT co.convalidated
  UNION ALL
  SELECT 'rls_enabled_tables', count(*) FILTER (WHERE c.relrowsecurity)::text
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND c.relkind IN ('r', 'p')
  UNION ALL
  SELECT 'rls_forced_tables', count(*) FILTER (WHERE c.relforcerowsecurity)::text
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND c.relkind IN ('r', 'p')
  UNION ALL
  SELECT 'policies', count(*)::text
    FROM pg_catalog.pg_policy pol
    JOIN pg_catalog.pg_class c ON c.oid = pol.polrelid
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema'
  UNION ALL
  SELECT 'sequence_last_value:' || s.sequencename, coalesce(s.last_value::text, 'null')
    FROM pg_catalog.pg_sequences s
   WHERE s.schemaname = :'inv_schema'
  UNION ALL
  SELECT 'owners', coalesce(string_agg(DISTINCT pg_catalog.pg_get_userbyid(c.relowner)::text, ','), '')
    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = :'inv_schema' AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
  UNION ALL
  SELECT 'extensions', coalesce(string_agg(e.extname || '@' || en.nspname, ',' ORDER BY e.extname), '')
    FROM pg_catalog.pg_extension e JOIN pg_catalog.pg_namespace en ON en.oid = e.extnamespace
) inventory
ORDER BY k;

SELECT to_regclass(:'inv_schema' || '.flyway_schema_history') IS NOT NULL AS inv_has_flyway \gset
\if :inv_has_flyway
SELECT 'flyway_rows', count(*)::text FROM :"inv_schema".flyway_schema_history;
SELECT 'flyway_success_rows', (count(*) FILTER (WHERE success))::text FROM :"inv_schema".flyway_schema_history;
SELECT 'flyway_baseline_rows', (count(*) FILTER (WHERE type = 'BASELINE'))::text FROM :"inv_schema".flyway_schema_history;
SELECT 'flyway_max_version', coalesce(max(version) FILTER (WHERE success), '') FROM :"inv_schema".flyway_schema_history;
\endif
