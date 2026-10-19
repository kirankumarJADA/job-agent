-- Robin x Tsenta continuation: FreeHire (https://freehire.me) as a second
-- aggregator discovery source, alongside Tsenta.
--
-- 1. job_sources.kind accepts 'FREEHIRE' (PlatformKind.FREEHIRE). Existing
--    rows and their kinds are untouched.
-- 2. Seeds five job_sources rows — one per tech category partition
--    (FreeHireAdapter.SEEDED_CATEGORIES) — each DISCOVERY_ONLY, enabled, on
--    its own 30-minute schedule. No company_id: this source is not scoped to
--    one employer. Each row's org_identifier is the literal FreeHire
--    category facet value FreeHireAdapter#parseIdentifier validates.
--
-- Convergence: the upserts re-assert enabled/schedule_cron/policy, so a
-- drifted environment row is healed to this definition on any re-apply —
-- same convention as V028's Greenhouse seed.

alter table job_sources drop constraint if exists job_sources_kind_check;
alter table job_sources add constraint job_sources_kind_check check (kind in (
    'WORKDAY','GREENHOUSE','LEVER','ASHBY','RIPPLING','ICIMS','BAMBOOHR','WORKABLE','JAZZHR','JOBVITE',
    'BREEZYHR','ORACLE_CLOUD','SMARTRECRUITERS','PAYLOCITY','ULTIPRO','ADP','DOVER','GEM','ZOHO_RECRUIT','BRASSRING',
    'CAREERPLUG','DAYFORCE','HIREOLOGY','JOIN','PHENOM','PINPOINT','POLYMER','RECRUITEE','SAP_SUCCESSFACTORS','TEAMTAILOR',
    'TSENTA','FREEHIRE',
    'COMPANY_SITE','SEARCH_API','MANUAL_IMPORT'));

insert into job_sources (
  id, kind, org_identifier, company_id, display_name, capabilities, policy,
  rate_limit_per_min, schedule_cron, enabled, health
) values
  ('9f1c2d3e-4000-7000-8000-0000000fe001', 'FREEHIRE', 'software_engineering', null,
   'FreeHire — Software Engineering', '{"discovery": true, "api": true, "automation": false}'::jsonb,
   'DISCOVERY_ONLY', 30, '0 */30 * * * ?', true, '{}'::jsonb),
  ('9f1c2d3e-4000-7000-8000-0000000fe002', 'FREEHIRE', 'backend', null,
   'FreeHire — Backend', '{"discovery": true, "api": true, "automation": false}'::jsonb,
   'DISCOVERY_ONLY', 30, '0 */30 * * * ?', true, '{}'::jsonb),
  ('9f1c2d3e-4000-7000-8000-0000000fe003', 'FREEHIRE', 'frontend', null,
   'FreeHire — Frontend', '{"discovery": true, "api": true, "automation": false}'::jsonb,
   'DISCOVERY_ONLY', 30, '0 */30 * * * ?', true, '{}'::jsonb),
  ('9f1c2d3e-4000-7000-8000-0000000fe004', 'FREEHIRE', 'fullstack', null,
   'FreeHire — Fullstack', '{"discovery": true, "api": true, "automation": false}'::jsonb,
   'DISCOVERY_ONLY', 30, '0 */30 * * * ?', true, '{}'::jsonb),
  ('9f1c2d3e-4000-7000-8000-0000000fe005', 'FREEHIRE', 'devops', null,
   'FreeHire — DevOps', '{"discovery": true, "api": true, "automation": false}'::jsonb,
   'DISCOVERY_ONLY', 30, '0 */30 * * * ?', true, '{}'::jsonb)
on conflict (kind, org_identifier) do update set
  enabled = true,
  schedule_cron = excluded.schedule_cron,
  policy = excluded.policy,
  display_name = excluded.display_name;

-- First scheduled run per row ingests up to that category's configured page
-- cap worth of recent postings (bounded, see BoardDiscoveryService's default
-- max-pages and FreeHireAdapter's own 10,000-offset ceiling); ingestion is
-- idempotent (JobIdentity keys + unique (source_id, external_id)), so
-- repeated runs only refresh last_seen_at and repost counts for postings
-- already seen.
