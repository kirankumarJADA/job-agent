-- Phase 8.2.5: FIND across the 30 Tsenta-listed ATS platforms + Tsenta itself.
--
-- 1. job_sources.kind accepts every platform in PlatformKind (plus the three
--    legacy non-platform kinds). Existing rows keep their kinds; LEVER,
--    WORKDAY and SMARTRECRUITERS were already allowed.
-- 2. Source run metadata for health/operations: when a run was attempted and
--    when one last succeeded, and the last run's counters as JSON.
-- 3. job_identity_keys: the strong-evidence identity index used to join the
--    same posting across sources (see JobIdentity). Employer+title is never a
--    key. Source-scoped ids are backfilled here; URL/ATS keys at startup.
-- 4. job_source_observations gains source_id and removed_at so "this source
--    no longer lists the job" can be recorded per source, and only after a
--    COMPLETE listing. Backfilled from the existing 'job-source:<uuid>'
--    provider naming.
-- Nothing is deleted; provenance stays in job_source_observations.

alter table job_sources drop constraint if exists job_sources_kind_check;
alter table job_sources add constraint job_sources_kind_check check (kind in (
    'WORKDAY','GREENHOUSE','LEVER','ASHBY','RIPPLING','ICIMS','BAMBOOHR','WORKABLE','JAZZHR','JOBVITE',
    'BREEZYHR','ORACLE_CLOUD','SMARTRECRUITERS','PAYLOCITY','ULTIPRO','ADP','DOVER','GEM','ZOHO_RECRUIT','BRASSRING',
    'CAREERPLUG','DAYFORCE','HIREOLOGY','JOIN','PHENOM','PINPOINT','POLYMER','RECRUITEE','SAP_SUCCESSFACTORS','TEAMTAILOR',
    'TSENTA',
    'COMPANY_SITE','SEARCH_API','MANUAL_IMPORT'));

alter table job_sources add column if not exists last_attempted_at timestamptz;
alter table job_sources add column if not exists last_success_at timestamptz;
alter table job_sources add column if not exists last_run jsonb not null default '{}';

create table if not exists job_identity_keys (
    key        text primary key,
    job_id     uuid not null references jobs(id) on delete cascade,
    key_type   text not null check (key_type in ('SRC','ATS','REQ','URL')),
    source_id  uuid references job_sources(id),
    created_at timestamptz not null default now()
);
create index if not exists job_identity_keys_job_idx on job_identity_keys(job_id);

-- Backfill: source-scoped external ids (always unique per (source_id, external_id)).
insert into job_identity_keys(key, job_id, key_type, source_id)
select 'src:' || j.source_id || ':' || j.external_id, j.id, 'SRC', j.source_id
from jobs j
where coalesce(j.external_id, '') <> ''
on conflict (key) do nothing;

-- URL and platform (ATS) keys for existing jobs are backfilled at startup by
-- JobIdentityBackfill, which uses the exact Java normalisation that live
-- ingestion uses (tracking-parameter stripping, query ordering, platform job
-- ids). Doing it here in SQL would produce keys that never match.

alter table job_source_observations add column if not exists source_id uuid references job_sources(id);
alter table job_source_observations add column if not exists removed_at timestamptz;

update job_source_observations o
set source_id = s.id
from job_sources s
where o.source_id is null
  and o.provider = 'job-source:' || s.id::text;

create index if not exists job_source_observations_source_idx
    on job_source_observations(source_id, source_status, last_seen_at);

-- Jobs whose URL keys all belong to an older job are remembered here so the
-- startup backfill (JobIdentityBackfill) never revisits them.
create table if not exists job_identity_backfill_skips (
    job_id     uuid primary key references jobs(id) on delete cascade,
    created_at timestamptz not null default now()
);
