-- V061__discovery_source_health.sql
-- Wave 2: Discovery source health tracking and failure monitoring
-- Supports safe deletion guards, health status aggregation, and circuit tripping.

ALTER TABLE job_sources ADD COLUMN IF NOT EXISTS health_status text NOT NULL DEFAULT 'HEALTHY';
ALTER TABLE job_sources ADD COLUMN IF NOT EXISTS consecutive_failures int NOT NULL DEFAULT 0;
ALTER TABLE job_sources ADD COLUMN IF NOT EXISTS last_successful_run_at timestamptz;
ALTER TABLE job_sources ADD COLUMN IF NOT EXISTS error_summary text;

CREATE INDEX IF NOT EXISTS job_sources_health_idx ON job_sources(health_status);
