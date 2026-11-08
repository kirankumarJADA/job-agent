-- Outbox export session (run by container/export-outbox.sh against the source).
--
-- ONE connection, ONE REPEATABLE READ, READ ONLY transaction: the server
-- rejects any write. Every part and chunk below is read in that single
-- snapshot, so the export is consistent with itself.
--
--   1. facts: versions, the outbox column list (the encoder only runs if it
--      is exactly the expected one), row counts per pending event type;
--   2. container/outbox-plan.sh writes /dump/plan.sql for the requested
--      parts; each chunk is preceded by an egress budget check
--      (container/outbox-budget.sh), and the plan stops cleanly (\quit)
--      before a chunk that could cross the cap;
--   3. every chunk is encoded, fingerprinted, hashed and compressed ON THE
--      SERVER (sql/outbox-encode-*.sql) and arrives as one binary COPY row.
--
-- Large tables are read through their indexes (outbox_pending_idx for
-- pending rows, the primary key for published rows) in chunks of
-- :chunk_rows rows. Sorts are per chunk and fit in work_mem: no temp files.
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = '120s';
SET LOCAL idle_in_transaction_session_timeout = 0;
SET LOCAL work_mem = '32MB';
-- The fingerprint settings of sql/table-fingerprints.sql: the per-row
-- fingerprints computed here are comparable with the R2 snapshot's.
SET LOCAL TimeZone = 'UTC';
SET LOCAL extra_float_digits = 1;
SET LOCAL bytea_output = 'hex';
SET LOCAL IntervalStyle = 'postgres';
SET LOCAL DateStyle = 'ISO, MDY';

\o /dump/export-facts.txt
SELECT 'snapshot_time', now()::text;
SELECT 'server_version_num', current_setting('server_version_num');
SELECT 'transaction_read_only', current_setting('transaction_read_only');
SELECT 'transaction_isolation', current_setting('transaction_isolation');
SELECT 'default_transaction_read_only', current_setting('default_transaction_read_only');
SELECT 'database_size_bytes', pg_database_size(current_database())::text;
SELECT 'outbox_columns', string_agg(a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
                                    || CASE WHEN a.attnotnull THEN ' not null' ELSE '' END, ', ' ORDER BY a.attnum)
  FROM pg_catalog.pg_attribute a
 WHERE a.attrelid = 'public.outbox_events'::regclass AND a.attnum > 0 AND NOT a.attisdropped;
SELECT 'outbox_generated_columns', count(*)::text
  FROM pg_catalog.pg_attribute a
 WHERE a.attrelid = 'public.outbox_events'::regclass AND a.attnum > 0 AND NOT a.attisdropped AND a.attgenerated <> '';
SELECT 'pgcrypto_schema', coalesce((SELECT n.nspname FROM pg_catalog.pg_extension e
                                      JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
                                     WHERE e.extname = 'pgcrypto'), '');
SELECT 'temp_bytes_start', coalesce((SELECT temp_bytes::text FROM pg_catalog.pg_stat_database
                                      WHERE datname = current_database()), '');
SELECT 'jobs_rows', count(*)::text FROM public.jobs;
SELECT 'pending_job_discovered', count(*) FILTER (WHERE event_type = 'job.discovered')::text
  FROM public.outbox_events WHERE published_at IS NULL;
SELECT 'pending_other', count(*) FILTER (WHERE event_type <> 'job.discovered')::text
  FROM public.outbox_events WHERE published_at IS NULL;
SELECT 'pending_other_by_type',
       coalesce(string_agg(regexp_replace(event_type, '[^[:alnum:]._-]', '?', 'g') || '=' || n, ',' ORDER BY event_type), '')
  FROM (SELECT event_type, count(*) AS n FROM public.outbox_events
         WHERE published_at IS NULL AND event_type <> 'job.discovered' GROUP BY 1) s;
SELECT 'outbox_reltuples', reltuples::bigint::text FROM pg_catalog.pg_class WHERE oid = 'public.outbox_events'::regclass;
\o

-- With fp_all (export-outbox.ps1 -FingerprintAllTables): the R2 fingerprint
-- of every table, in this snapshot, so that export-outbox.sh can prove what
-- has and has not changed since R2. Same schemas, same query and same
-- settings as R2's snapshot-session.sql; one sequential read of each table.
\if :fp_all
SELECT string_agg(DISTINCT n.nspname, ',' ORDER BY n.nspname) AS fp_schemas
  FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname NOT LIKE 'pg\_%' \gset
\echo 'fingerprinting every table, as at R2 (one sequential read of each, no writes)'
\o /dump/source-fingerprints.txt
\i /work/table-fingerprints.sql
\o
\endif

\echo 'export snapshot open; planning the requested parts'
\! bash /work/outbox-plan.sh
\if :SHELL_ERROR
\echo 'the export plan could not be written (see the log); nothing was transferred'
\quit
\endif
\set tkey `cat /dump/.tkey`
\i /dump/plan.sql

\o /dump/export-facts-end.txt
SELECT 'temp_bytes_end', coalesce((SELECT temp_bytes::text FROM pg_catalog.pg_stat_database
                                    WHERE datname = current_database()), '');
SELECT 'snapshot_session_end', clock_timestamp()::text;
\o
COMMIT;
\echo 'export snapshot closed'
