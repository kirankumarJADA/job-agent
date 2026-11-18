-- V062__job_bookmarks.sql
-- Wave 2: Candidate job bookmarks and saved opportunities

CREATE TABLE IF NOT EXISTS job_bookmarks (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    job_id uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_bookmarks_profile_job UNIQUE (profile_id, job_id)
);

CREATE INDEX IF NOT EXISTS job_bookmarks_profile_idx ON job_bookmarks(profile_id, created_at DESC);
