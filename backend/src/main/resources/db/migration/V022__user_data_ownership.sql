-- V022__user_data_ownership.sql
--
-- Multi-user data isolation.
--
-- Phase 1 was deliberately single-user: profiles/preference_sets were scoped
-- by user_id, but jobs, applications, notifications, audit_logs, emails and
-- the automation_* tables were global. With self-service registration enabled
-- (V020) that is no longer safe, so every user-owned table gets an explicit
-- owner derived from the schema that already exists.
--
-- Ownership root: profiles.id. This is not a new invention — V001 already
-- makes profiles the single row per user (`unique(user_id)`) that every
-- other user-owned table hangs off (work_experiences, education, projects,
-- certifications, skills, preference_sets, applicant_identities, and
-- V013's cv_versions/cover_letters/application_answers/resume_ats_analyses).
-- Scoping to profile_id therefore reuses the existing ownership concept
-- instead of introducing a second one alongside users.id.
--
-- What stays GLOBAL (and why) — see docs/contracts/api.md:
--   * jobs, job_sources, companies, sponsor_records, job_snapshots,
--     job_source_observations, discovery_extractions, job_analyses,
--     job_scores: public posting + discovery-catalogue data, deduped
--     globally (`unique(source_id, external_id)` and jobs.dedup_key) and
--     sourced from globally-configured connectors. Two users discovering the
--     same posting share one row by design; making these per-user would break
--     the dedup contract, the source-failure tracking and the product's
--     browse-the-catalogue behaviour.
--   * llm_*, prompts, routing_policies, model_benchmark_runs,
--     benchmark_results, outbox_events, consumed_events, autonomy_policies,
--     job_source_observations: platform/ops telemetry, not user data.
--   * users: account records (already unique by email).
--
-- The one genuinely per-user thing that used to live on the shared jobs row
-- was the match decision (match_score / match_recommendation /
-- match_breakdown, added by V007). JobMatchService.evaluateMatch(jobId,
-- profileId, ...) wrote it onto jobs, so two users scoring the same posting
-- overwrote each other's result and flipped the shared status to 'SCORED'.
-- That data is moved to job_matches below, keyed by (profile_id, job_id).

-- ── per-user ownership columns ─────────────────────────────────────
-- Nullable on purpose. The application always populates them (see the
-- repositories), but NULL is what a row that cannot be safely attributed
-- gets — and every read path filters on `profile_id = ?`, so NULL rows are
-- invisible to users rather than shared between them. Failing closed beats
-- inventing an owner for data we cannot prove ownership of.

alter table applications add column if not exists profile_id uuid references profiles(id) on delete cascade;
alter table notifications add column if not exists profile_id uuid references profiles(id) on delete cascade;
alter table emails add column if not exists profile_id uuid references profiles(id) on delete cascade;
alter table automation_plans add column if not exists profile_id uuid references profiles(id) on delete cascade;
alter table worker_events add column if not exists profile_id uuid references profiles(id) on delete cascade;

-- audit_logs is append-only and its retention is required for audit
-- integrity (V001 revokes update/delete from backend_role), so it must NOT
-- cascade away with a profile purge: the record of a purge has to outlive
-- it. `on delete set null` keeps the row while dropping the owner link.
alter table audit_logs add column if not exists profile_id uuid references profiles(id) on delete set null;

create index if not exists applications_profile_idx on applications(profile_id);
create index if not exists notifications_profile_idx on notifications(profile_id, created_at desc);
create index if not exists emails_profile_idx on emails(profile_id, received_at desc);
create index if not exists automation_plans_profile_idx on automation_plans(profile_id);
create index if not exists worker_events_profile_idx on worker_events(profile_id);
create index if not exists audit_logs_profile_idx on audit_logs(profile_id, created_at desc);

-- ── uniqueness that was accidentally global ────────────────────────
-- applications_one_per_job let a user hold exactly one live application per
-- job *in the whole database* — with a second user that constraint makes the
-- feature impossible to use. Same shape of bug for emails.message_id and
-- automation_plans.idempotency_key: both were globally unique, so a second
-- user's identical key either collided or, worse, resolved to the first
-- user's row (AutomationPlanRepository.create() read the plan id back by
-- idempotency_key alone).

drop index if exists applications_one_per_job;
drop index if exists applications_profile_job_live_uq;
create unique index applications_profile_job_live_uq on applications(profile_id, job_id)
  where status not in ('FAILED','WITHDRAWN');

alter table emails drop constraint if exists emails_message_id_key;
create unique index if not exists emails_profile_message_uq on emails(profile_id, message_id);

alter table automation_plans drop constraint if exists automation_plans_idempotency_key_key;
create unique index if not exists automation_plans_profile_idempotency_uq
  on automation_plans(profile_id, idempotency_key);

-- ── per-user job match results ─────────────────────────────────────
create table if not exists job_matches (
  profile_id     uuid not null references profiles(id) on delete cascade,
  job_id         uuid not null references jobs(id) on delete cascade,
  score          int  not null,
  recommendation text not null check (recommendation in ('APPLY','REVIEW','SKIP')),
  breakdown      jsonb not null default '{}'::jsonb,
  scored_at      timestamptz not null default now(),
  primary key (profile_id, job_id)
);
create index if not exists job_matches_job_idx on job_matches(job_id);

-- ── legacy attribution (fail closed) ───────────────────────────────
-- Row-by-row derivation only. Order matters: each step uses ownership
-- established by an earlier step, and nothing is guessed.
--
--   1. Anything reachable from an application inherits that application's
--      owner.
--   2. When the database holds exactly ONE profile, every remaining global
--      row provably belonged to that user (the single-user Phase 1
--      deployment this data came from), so it is attributed to them.
--   3. With more than one profile, anything still unattributed stays NULL —
--      excluded from every user-facing read instead of being shown to
--      whoever asks first.
--
-- audit_logs is a special case, and NOT populated here. V008 installs a
-- BEFORE UPDATE/DELETE trigger that rejects any mutation of the table
-- (verified against real PostgreSQL: an `update audit_logs` inside this
-- migration aborts the whole block with "audit_logs is append-only"). That
-- immutability is a security property this migration must not weaken, so
-- legacy audit rows keep profile_id = NULL and are attributed at READ time
-- from their `actor` column instead — `actor` already holds the acting
-- account's email, so `actor = <current user's email>` is exact attribution.
-- New rows get profile_id written at INSERT, which the trigger permits.
do $$
declare
  v_profile_count int;
  v_only_profile  uuid;
begin
  select count(*) into v_profile_count from profiles;
  if v_profile_count = 1 then
    select id into v_only_profile from profiles;
  end if;

  -- 1a. applications (single-profile deployments only: there is no other
  --     reachable evidence of who created a legacy application row).
  if v_only_profile is not null then
    update applications set profile_id = v_only_profile where profile_id is null;
  end if;

  -- 1b. children whose parent chain now carries an owner.
  update emails e set profile_id = a.profile_id
    from applications a
   where e.application_id = a.id and e.profile_id is null and a.profile_id is not null;

  update automation_plans p set profile_id = a.profile_id
    from applications a
   where p.application_id = a.id and p.profile_id is null and a.profile_id is not null;

  update notifications n set profile_id = a.profile_id
    from applications a
   where n.application_id = a.id and n.profile_id is null and a.profile_id is not null;

  update worker_events w set profile_id = p.profile_id
    from automation_plans p
   where w.plan_id = p.id and w.profile_id is null and p.profile_id is not null;

  update worker_events w set profile_id = a.profile_id
    from applications a
   where w.application_id = a.id and w.profile_id is null and a.profile_id is not null;

  update account_sessions s set owner_profile_id = a.profile_id
    from applications a
   where s.application_id = a.id and s.owner_profile_id is null and a.profile_id is not null;

  -- 2. single-profile fallback for the remainder of the Phase 1 globals.
  if v_only_profile is not null then
    update emails            set profile_id = v_only_profile where profile_id is null;
    update automation_plans  set profile_id = v_only_profile where profile_id is null;
    update notifications     set profile_id = v_only_profile where profile_id is null;
    update worker_events     set profile_id = v_only_profile where profile_id is null;

    -- The per-user match decision V007 stored on the shared job row.
    insert into job_matches (profile_id, job_id, score, recommendation, breakdown, scored_at)
    select v_only_profile, j.id, j.match_score, j.match_recommendation,
           coalesce(j.match_breakdown, '{}'::jsonb), now()
      from jobs j
     where j.match_score is not null
       and j.match_recommendation is not null
    on conflict (profile_id, job_id) do nothing;
  end if;

  -- 3. Anything still unattributed remains NULL. Every read path filters on
  --    the owner, so those rows are invisible to users rather than shared —
  --    the fail-closed outcome required when ownership cannot be proven.
end
$$;

-- ── remove the per-user data from the shared catalogue row ─────────
-- JobMatchService no longer writes these, and JobRepository no longer reads
-- them (it joins job_matches for the caller's own result). Dropping them is
-- the fail-closed option: leaving a stale per-user match decision on a row
-- every authenticated user can select is exactly the leak being fixed, and a
-- future `select *` would silently re-expose it.
alter table jobs drop column if exists match_score;
alter table jobs drop column if exists match_recommendation;
alter table jobs drop column if exists match_breakdown;

-- ── enforce ownership where the write path always knows the owner ──
-- Not for applications/notifications/emails/automation_plans: those carry
-- authenticated writes today, but a NOT NULL would make this migration fail
-- on a multi-profile database holding unattributable legacy rows, and a
-- failed migration on real data is worse than a runtime filter that excludes
-- NULL. The repositories populate the column on every insert.
comment on column applications.profile_id is
  'Owning candidate profile. NULL = legacy row that could not be safely attributed; excluded from all user-facing reads.';
comment on column notifications.profile_id is
  'Owning candidate profile. NULL = system/ops notification with no user owner; never returned to a user.';
comment on column emails.profile_id is
  'Owning candidate profile, derived from the correlated application at ingest. NULL = not attributable.';
comment on column automation_plans.profile_id is
  'Owning candidate profile, derived from the plan''s application. NULL = not attributable.';
comment on column audit_logs.profile_id is
  'Profile of the acting user, written at INSERT. NULL for legacy rows (this table is append-only per V008, so they cannot be backfilled) and for system/ops actions; AuditController attributes NULL rows at read time by actor email. Retained on profile purge.';
