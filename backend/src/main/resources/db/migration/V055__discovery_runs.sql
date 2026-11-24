-- V055__discovery_runs.sql
-- Run history for discovery board runs (P1-2)
-- Written once per board run with execution metrics, counters, and status.

create table if not exists discovery_runs (
    id uuid primary key,
    source_id uuid references job_sources(id) on delete set null,
    kind text not null,
    mode text not null,
    started_at timestamptz not null,
    finished_at timestamptz not null,
    duration_ms bigint not null,
    status text not null,
    failure_category text,
    detail text,
    counters jsonb not null default '{}'::jsonb,
    complete boolean not null default false
);

create index if not exists discovery_runs_source_idx on discovery_runs(source_id, started_at desc);
create index if not exists discovery_runs_kind_idx on discovery_runs(kind, started_at desc);
