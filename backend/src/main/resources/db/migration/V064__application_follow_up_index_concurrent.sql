-- V064__application_follow_up_index_concurrent.sql
-- Wave 2 / C2: build applications_follow_up_idx without locking `applications`.
--
-- Runs OUTSIDE a transaction: see the companion
-- V064__application_follow_up_index_concurrent.sql.conf (executeInTransaction=false),
-- because CREATE INDEX CONCURRENTLY cannot run inside a transaction block.
--
-- CREATE INDEX CONCURRENTLY takes only SHARE UPDATE EXCLUSIVE on `applications`, so
-- reads and writes continue for the whole build. It is slower than a plain build and
-- it can fail; both are deliberate trade-offs against a read-and-write lockout.
--
-- Interrupted or failed builds: a cancelled CREATE INDEX CONCURRENTLY leaves an INVALID
-- index behind. A bare IF NOT EXISTS would then treat the name as taken and silently
-- keep an index the planner never uses, so any invalid leftover is dropped first.
-- Dropping an invalid index removes no table data. Because this migration is not
-- transactional, a failure is recorded in flyway_schema_history with success = false and
-- must be cleared with `flyway repair` before migrate will run again.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_class c
        JOIN pg_index i ON i.indexrelid = c.oid
        JOIN pg_class t ON t.oid = i.indrelid
        WHERE c.relname = 'applications_follow_up_idx'
          AND t.relname = 'applications'
          AND NOT i.indisvalid
    ) THEN
        RAISE NOTICE 'Dropping invalid applications_follow_up_idx left by an interrupted concurrent build';
        EXECUTE 'DROP INDEX applications_follow_up_idx';
    END IF;
END $$;

CREATE INDEX CONCURRENTLY IF NOT EXISTS applications_follow_up_idx
    ON applications (profile_id, follow_up_status, follow_up_due_at);
