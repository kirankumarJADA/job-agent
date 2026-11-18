-- V063__application_follow_ups.sql
-- Wave 2: Application follow-up tracking and stale application reminders.

ALTER TABLE applications ADD COLUMN IF NOT EXISTS follow_up_due_at timestamptz;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS follow_up_status text NOT NULL DEFAULT 'NONE';

CREATE INDEX IF NOT EXISTS applications_follow_up_idx ON applications(profile_id, follow_up_status, follow_up_due_at);
