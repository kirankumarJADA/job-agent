-- Phase 3: Arbeitnow (https://www.arbeitnow.com) as a third discovery
-- source, alongside Tsenta and FreeHire.
--
-- 1. job_sources.kind accepts 'ARBEITNOW' (PlatformKind.ARBEITNOW). Existing
--    rows and their kinds are untouched.
-- 2. Seeds exactly one job_sources row — ArbeitnowAdapter has no category
--    partition to split across rows (see its own javadoc: unlike FreeHire,
--    Arbeitnow's entire catalogue is small enough for one bounded run to
--    regularly reach its own end-of-list signal; there is also no
--    documented category facet to partition by). No company_id: this
--    source is not scoped to one employer. "automation": false — Arbeitnow
--    exposes no employer-direct application URL at all (confirmed live:
--    its own "Apply" action posts back to arbeitnow.com itself), so
--    ArbeitnowAdapter never sets applicationUrl and Robin's application
--    pipeline cannot act on these postings; discovery and matching are the
--    extent of this integration, exactly like FreeHire's own seed already
--    marks automation false for an unrelated reason.
--
-- Convergence: the upsert re-asserts enabled/schedule_cron/policy, so a
-- drifted environment row is healed to this definition on any re-apply —
-- same convention as V028's and V044's own seeds.

alter table job_sources drop constraint if exists job_sources_kind_check;
alter table job_sources add constraint job_sources_kind_check check (kind in (
    'WORKDAY','GREENHOUSE','LEVER','ASHBY','RIPPLING','ICIMS','BAMBOOHR','WORKABLE','JAZZHR','JOBVITE',
    'BREEZYHR','ORACLE_CLOUD','SMARTRECRUITERS','PAYLOCITY','ULTIPRO','ADP','DOVER','GEM','ZOHO_RECRUIT','BRASSRING',
    'CAREERPLUG','DAYFORCE','HIREOLOGY','JOIN','PHENOM','PINPOINT','POLYMER','RECRUITEE','SAP_SUCCESSFACTORS','TEAMTAILOR',
    'TSENTA','FREEHIRE','ARBEITNOW',
    'COMPANY_SITE','SEARCH_API','MANUAL_IMPORT'));

insert into job_sources (
  id, kind, org_identifier, company_id, display_name, capabilities, policy,
  rate_limit_per_min, schedule_cron, enabled, health
) values
  ('9f1c2d3e-4000-7000-8000-0000000ab001', 'ARBEITNOW', 'jobs', null,
   'Arbeitnow — All Jobs', '{"discovery": true, "api": true, "automation": false}'::jsonb,
   'DISCOVERY_ONLY', 30, '0 */30 * * * ?', true, '{}'::jsonb)
on conflict (kind, org_identifier) do update set
  enabled = true,
  schedule_cron = excluded.schedule_cron,
  policy = excluded.policy,
  display_name = excluded.display_name;

-- First scheduled run ingests up to the configured page cap worth of recent
-- postings (bounded, see BoardDiscoveryService's default max-pages);
-- ingestion is idempotent (JobIdentity keys + unique (source_id,
-- external_id)), so repeated runs only refresh last_seen_at for postings
-- already seen, and a changed listing (different title/description/etc. on
-- the same slug) updates the existing row rather than duplicating it.
