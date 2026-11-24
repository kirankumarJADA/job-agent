-- V063__application_follow_ups.sql
-- Wave 2: Application follow-up tracking and stale application reminders.
--
-- Column adds ONLY. The supporting index is built separately, and without locking the
-- table, by V064 using CREATE INDEX CONCURRENTLY. See C2 in
-- docs/ROBIN_WAVE2_RELEASE_READINESS.md.
--
-- Do not add a plain CREATE INDEX back into this script: Flyway runs each migration in
-- one transaction, so the index build would inherit and hold the ACCESS EXCLUSIVE lock
-- taken by the ALTER TABLE statements below, blocking reads and writes on `applications`
-- for the whole build. The two ALTER TABLEs on their own take that lock only briefly:
-- a constant DEFAULT is metadata-only from PostgreSQL 11 onward, so no table rewrite.

ALTER TABLE applications ADD COLUMN IF NOT EXISTS follow_up_due_at timestamptz;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS follow_up_status text NOT NULL DEFAULT 'NONE';
