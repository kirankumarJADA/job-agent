-- Phase 9: region-aware source catalogue + Adzuna (https://www.adzuna.com), a
-- fourth aggregator source, alongside Tsenta, FreeHire and Arbeitnow.
--
-- 1. job_sources.kind accepts 'ADZUNA' (PlatformKind.ADZUNA).
-- 2. job_sources gains a `region` column: the ISO-3166-1 alpha-2 country code
--    a source is scoped to (e.g. 'GB'), or 'GLOBAL' for a source that is not
--    region-restricted (every existing row — company career pages and
--    ATS-hosted boards are not regional in the sense this column captures;
--    the existing aggregators crawl a worldwide catalogue). Defaulting every
--    existing row to 'GLOBAL' is deliberately conservative: it changes no
--    existing source's selection behaviour (a candidate whose preferences
--    name any country still sees every 'GLOBAL' source, exactly as before
--    this column existed) and only the newly-seeded, genuinely
--    country-scoped Adzuna row is actually restricted by it.
-- 3. Seeds exactly one job_sources row for the UK (org_identifier = 'gb',
--    region = 'GB') — AdzunaAdapter validates org_identifier as an ISO
--    country code against Adzuna's own documented country list, so adding
--    coverage for another country later is one more row with a different
--    identifier/region, no code change (the task's own "architecture that
--    accommodates other regions" requirement). "automation": false — like
--    Arbeitnow and FreeHire, this aggregator's own application link is not
--    one of Robin's verified ATS execution contracts, so the browser
--    automation pipeline never attempts to drive a submission there.
--
-- Convergence: the upsert re-asserts enabled/schedule_cron/policy/region, so
-- a drifted environment row is healed to this definition on any re-apply —
-- same convention as V044's, V045's and V046's own seeds.

alter table job_sources drop constraint if exists job_sources_kind_check;
alter table job_sources add constraint job_sources_kind_check check (kind in (
    'WORKDAY','GREENHOUSE','LEVER','ASHBY','RIPPLING','ICIMS','BAMBOOHR','WORKABLE','JAZZHR','JOBVITE',
    'BREEZYHR','ORACLE_CLOUD','SMARTRECRUITERS','PAYLOCITY','ULTIPRO','ADP','DOVER','GEM','ZOHO_RECRUIT','BRASSRING',
    'CAREERPLUG','DAYFORCE','HIREOLOGY','JOIN','PHENOM','PINPOINT','POLYMER','RECRUITEE','SAP_SUCCESSFACTORS','TEAMTAILOR',
    'TSENTA','FREEHIRE','ARBEITNOW','ADZUNA',
    'COMPANY_SITE','SEARCH_API','MANUAL_IMPORT'));

alter table job_sources add column if not exists region varchar(16) not null default 'GLOBAL';
create index if not exists job_sources_region_idx on job_sources(region) where enabled;

insert into job_sources (
  id, kind, org_identifier, company_id, display_name, capabilities, policy,
  rate_limit_per_min, schedule_cron, enabled, health, region
) values
  ('9f1c2d3e-4000-7000-8000-0000000ab002', 'ADZUNA', 'gb', null,
   'Adzuna — United Kingdom', '{"discovery": true, "api": true, "automation": false}'::jsonb,
   'DISCOVERY_ONLY', 20, '0 0 */6 * * ?', true, '{}'::jsonb, 'GB')
on conflict (kind, org_identifier) do update set
  enabled = true,
  schedule_cron = excluded.schedule_cron,
  policy = excluded.policy,
  display_name = excluded.display_name,
  region = excluded.region;
