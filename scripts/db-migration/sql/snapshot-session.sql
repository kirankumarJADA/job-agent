-- Holds ONE read-only snapshot for the whole backup (run by container/backup.sh):
--   1. export the snapshot;
--   2. record source facts, per-table fingerprints and the inventory in it;
--   3. run pg_dump --snapshot (via \!) while this transaction stays open.
-- The transaction is READ ONLY, so the server itself rejects any write.
-- Large tables are touched only by count/sum aggregates and partial-index
-- lookups: no sort, join, DISTINCT or GROUP BY on them, so no temp files.
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = '120s';
SET LOCAL idle_in_transaction_session_timeout = 0;
SET LOCAL TimeZone = 'UTC';
SET LOCAL extra_float_digits = 1;
SET LOCAL bytea_output = 'hex';
SET LOCAL IntervalStyle = 'postgres';
SET LOCAL DateStyle = 'ISO, MDY';

\o /dump/snapshot.id
SELECT pg_export_snapshot();

SELECT string_agg(DISTINCT n.nspname, ',' ORDER BY n.nspname) AS fp_schemas
  FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname NOT LIKE 'pg\_%' \gset

\o /dump/source-facts.txt
SELECT 'snapshot_time', now()::text;
SELECT 'server_version', version();
SELECT 'server_version_num', current_setting('server_version_num');
SELECT 'connected_role', current_user::text;
SELECT 'database_size_bytes', pg_database_size(current_database())::text;
SELECT 'default_transaction_read_only', current_setting('default_transaction_read_only');
SELECT 'fingerprint_schemas', :'fp_schemas';

SELECT to_regclass('public.outbox_events') IS NOT NULL AS has_outbox \gset
\if :has_outbox
SELECT 'outbox_pending', count(*)::text FROM public.outbox_events WHERE published_at IS NULL;
SELECT 'outbox_pending_by_type',
       'job.discovered=' || count(*) FILTER (WHERE event_type = 'job.discovered')
       || ',job.matched=' || count(*) FILTER (WHERE event_type = 'job.matched')
       || ',verification.completed=' || count(*) FILTER (WHERE event_type = 'verification.completed')
       || ',signup.completed=' || count(*) FILTER (WHERE event_type = 'signup.completed')
       || ',other=' || count(*) FILTER (WHERE event_type NOT IN
              ('job.discovered', 'job.matched', 'verification.completed', 'signup.completed'))
  FROM public.outbox_events WHERE published_at IS NULL;
SELECT 'outbox_oldest_pending', coalesce((SELECT created_at::text FROM public.outbox_events
        WHERE published_at IS NULL ORDER BY sequence_id LIMIT 1), '');
SELECT 'outbox_newest_pending', coalesce((SELECT created_at::text FROM public.outbox_events
        WHERE published_at IS NULL ORDER BY sequence_id DESC LIMIT 1), '');
\endif

\o /dump/source-fingerprints.txt
\i /work/table-fingerprints.sql

\set inv_schema public
\o /dump/source-inventory.txt
\i /work/inventory.sql
\o

\echo 'snapshot facts, fingerprints and inventory recorded; running pg_dump in the same snapshot'
\! bash /work/run-pg-dump.sh
\if :SHELL_ERROR
\echo 'pg_dump failed: see pg_dump.log'
SELECT robin_backup_pg_dump_failed;
\endif
COMMIT;
\echo 'snapshot session closed'
