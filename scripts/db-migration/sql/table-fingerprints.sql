-- Per-table row count and order-independent content fingerprint.
--
-- Output, one line per ordinary (leaf) table, via psql -At -F '|':
--   schema.table|row_count|fingerprint|extension_member
--
-- fingerprint = sum over rows of the first 60 bits of
--   md5((to_jsonb(row) - <generated columns>)::text)
-- A sum does not depend on row order, so no sort is needed. Each table is
-- read with one sequential scan and a plain aggregate: no sort, join,
-- DISTINCT or hash table, so no temp files are written on the source.
--
-- Generated columns (jobs.search_vector) are excluded: pg_dump does not dump
-- them, and the restore recomputes them.
--
-- The caller must run this inside one transaction with fixed settings, so
-- that the source and the restored copy render values identically:
--   SET LOCAL TimeZone = 'UTC'; SET LOCAL extra_float_digits = 1;
--   SET LOCAL bytea_output = 'hex'; SET LOCAL IntervalStyle = 'postgres';
--   SET LOCAL DateStyle = 'ISO, MDY';
--
-- Input: psql variable fp_schemas, a comma-separated schema list (e.g. public).
SELECT format(
         $q$SELECT %L, count(*), coalesce(sum(('x' || substr(md5((to_jsonb(t) - %L::text[])::text), 1, 15))::bit(60)::bigint), 0), %L FROM %I.%I AS t$q$,
         n.nspname || '.' || c.relname,
         coalesce((SELECT array_agg(a.attname::text ORDER BY a.attnum)
                     FROM pg_catalog.pg_attribute a
                    WHERE a.attrelid = c.oid AND a.attgenerated <> '' AND NOT a.attisdropped),
                  '{}'::text[])::text,
         CASE WHEN EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                            WHERE d.classid = 'pg_catalog.pg_class'::regclass
                              AND d.objid = c.oid AND d.deptype = 'e')
              THEN 't' ELSE 'f' END,
         n.nspname, c.relname)
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname = ANY (string_to_array(:'fp_schemas', ','))
 ORDER BY n.nspname, c.relname
\gexec
