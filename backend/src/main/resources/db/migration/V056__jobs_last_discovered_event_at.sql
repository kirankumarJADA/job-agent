-- V056__jobs_last_discovered_event_at.sql
-- Additive rate-limiting column for job.discovered outbox event emission (P1-3).
-- Nullable timestamptz with no default, safe on existing populated table.

alter table jobs add column if not exists last_discovered_event_at timestamptz;
create index if not exists jobs_last_discovered_event_at_idx on jobs(last_discovered_event_at) where last_discovered_event_at is not null;
