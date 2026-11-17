-- outbox-exclusion-apply.sql: Option C, excludes every pending job.discovered
-- event from the drain (V052). Outbox rows are not changed or deleted.
--
-- Run on the target database, after the restore checks pass and while
-- dispatch is paused, as the application role:
--   sudo -u postgres psql -X -At -v ON_ERROR_STOP=1 -d robin -v label=cutover-historical-discovery \
--        -v actor=<operator> -v reason='<why>' < outbox-exclusion-apply.sql
-- (standard input, or -f with a path the postgres user can read)
--
-- One transaction. Idempotent: the same label again returns the recorded
-- batch with created = f. It refuses an empty backlog, a reverted label and a
-- second active batch. A missing -v variable is a syntax error, so nothing
-- runs. Then run outbox-exclusion-check.sql.
\set ON_ERROR_STOP on
select batch_id, label, max_sequence_id, excluded_count, created
  from outbox_exclude_pending_discovery(:'label', :'reason', :'actor');
