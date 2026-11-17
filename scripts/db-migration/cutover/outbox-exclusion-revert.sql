-- outbox-exclusion-revert.sql: ends the Option C exclusion (V052). Its events
-- are dispatched again, in sequence order, once dispatch runs. The batch and
-- its rows are kept, stamped, for the audit trail.
--
--   sudo -u postgres psql -X -At -v ON_ERROR_STOP=1 -d robin -v label=cutover-historical-discovery \
--        -v actor=<operator> -v reason='<why>' [-v force=true] < outbox-exclusion-revert.sql
--
-- Meant for before dispatch resumes. After events have been published it
-- refuses unless force=true, because the historical events would then go
-- back to the head of the queue. Idempotent: a reverted batch is returned
-- with reverted = f.
\set ON_ERROR_STOP on
\if :{?force}
\else
\set force false
\endif
select batch_id, label, excluded_count, still_pending, reverted
  from outbox_revert_discovery_exclusion(:'label', :'reason', :'actor', :'force'::boolean);
