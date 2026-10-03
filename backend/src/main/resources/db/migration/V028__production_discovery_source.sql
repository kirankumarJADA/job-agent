-- Phase 17: production discovery source.
--
-- Seeds ONE real, public Greenhouse board so the DiscoveryScheduler
-- (infra Phase 5) has a genuine production target. Verified live at
-- migration-authoring time: boards-api.greenhouse.io/v1/boards/stripe/jobs
-- returns HTTP 200 with ~714 postings, ~167 of them UK/remote-relevant.
--
-- The scheduler's eligibility query requires:
--   enabled = true AND policy <> 'DISABLED' AND kind IN ('GREENHOUSE','ASHBY')
--   AND org_identifier <> '' AND schedule_cron <> ''
-- This row satisfies all five. The cron fires every 15 minutes (6-field
-- Spring CronExpression, second-granularity - the format the scheduler
-- parses with CronExpression.parse).
--
-- Policy stays DISCOVERY_ONLY: discovered jobs are matched and prepared, but
-- automation still runs through the plan/approval state machine, and
-- REAL_SUBMIT remains hard-stopped.
--
-- Convergence: the upserts re-assert enabled/schedule_cron/policy, so a
-- drifted environment row is healed to this definition on any re-apply.

insert into companies (id, slug, name, careers_url, industry, hq_country)
values (
  '9f1c2d3e-4000-7000-8000-00000000c001',
  'stripe',
  'Stripe',
  'https://boards.greenhouse.io/stripe',
  'Fintech',
  'US'
)
on conflict (slug) do update set
  name = excluded.name,
  careers_url = excluded.careers_url;

insert into job_sources (
  id, kind, org_identifier, company_id, display_name, capabilities, policy,
  rate_limit_per_min, schedule_cron, enabled, health
) values (
  '9f1c2d3e-4000-7000-8000-00000000c002',
  'GREENHOUSE',
  'stripe',
  '9f1c2d3e-4000-7000-8000-00000000c001',
  'Stripe (Greenhouse)',
  '{"discovery": true, "api": true, "automation": false}'::jsonb,
  'DISCOVERY_ONLY',
  30,
  '0 */15 * * * ?',
  true,
  '{}'::jsonb
)
on conflict (kind, org_identifier) do update set
  enabled = true,
  schedule_cron = excluded.schedule_cron,
  policy = excluded.policy,
  company_id = excluded.company_id,
  display_name = excluded.display_name;

-- The first scheduled run ingests the whole live board (hundreds of real
-- postings); ingestion is idempotent (SHA-256 dedup_key + unique
-- (source_id, external_id)), so repeated runs only refresh last_seen_at and
-- repost counts.
