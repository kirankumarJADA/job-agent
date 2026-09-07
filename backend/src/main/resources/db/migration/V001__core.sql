-- V001__core.sql
-- Phase 1 core schema. Later-phase tables (email_*, automation_runs/steps) are
-- designed in docs/contracts and migrate in their own phases, not here.
-- All PKs are app-generated UUIDv7 (time-ordered, index-friendly) — generated
-- in application code via common.UuidV7, not by a DB default, so the DB stays
-- portable and ordering is guaranteed even under retries.

create extension if not exists citext;
create extension if not exists pgcrypto;

-- ── identity & profile ─────────────────────────────────────────────
create table users (
  id            uuid primary key,
  email         citext unique not null,
  password_hash text not null,                 -- argon2id
  display_name  text not null,
  created_at    timestamptz not null default now()
);

create table profiles (
  id          uuid primary key,
  user_id     uuid not null references users(id) on delete cascade,
  headline    text, phone text, location text,
  work_eligibility jsonb not null default '{}',
  career_goals jsonb not null default '{}',
  updated_at  timestamptz not null default now(),
  unique(user_id)
);

create table work_experiences (
  id uuid primary key,
  profile_id uuid not null references profiles(id) on delete cascade,
  company text not null, title text not null,
  start_month date not null, end_month date,
  location text,
  bullets jsonb not null default '[]',
  sort_order int not null default 0
);

create table education (
  id uuid primary key,
  profile_id uuid not null references profiles(id) on delete cascade,
  institution text not null, qualification text not null,
  field text, start_year int, end_year int, grade text
);

create table projects (
  id uuid primary key,
  profile_id uuid not null references profiles(id) on delete cascade,
  name text not null, summary text, url text,
  bullets jsonb not null default '[]', sort_order int default 0
);

create table certifications (
  id uuid primary key,
  profile_id uuid not null references profiles(id) on delete cascade,
  name text not null, issuer text, issued_on date, credential_id text
);

create table skills (
  id uuid primary key,
  profile_id uuid not null references profiles(id) on delete cascade,
  name citext not null,
  category text,
  mastery int check (mastery between 1 and 5),
  years numeric(4,1),
  evidence_experience_id uuid references work_experiences(id),
  unique(profile_id, name)
);

-- ── preferences (single active set) ───────────────────────────────
create table preference_sets (
  id uuid primary key,
  profile_id uuid not null references profiles(id) on delete cascade,
  titles text[] not null default '{}',
  keywords_include text[] not null default '{}',
  keywords_exclude text[] not null default '{}',
  required_skills text[] not null default '{}',
  locations_allowed text[] not null default '{}',
  remote_types text[] not null default '{}',
  employment_types text[] not null default '{FULL_TIME}',
  experience_levels text[] not null default '{}',
  salary_min_gbp bigint,
  sponsorship_policy text not null default 'SHOW_ALL'
      check (sponsorship_policy in
        ('SPONSORSHIP_PREFERRED','SPONSORSHIP_REQUIRED','SPONSORSHIP_NOT_REQUIRED','SHOW_ALL')),
  company_size_pref text[], industry_pref text[],
  application_mode text not null default 'ASSISTED'
      check (application_mode in ('MANUAL','ASSISTED','CONTROLLED_AUTO')),
  scoring_weights jsonb not null,
  extra_filters jsonb not null default '{}',
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- ── sources & companies ───────────────────────────────────────────
create table companies (
  id uuid primary key,
  slug citext unique not null,
  name text not null, careers_url text,
  industry text, size_bucket text, hq_country text
);

create table job_sources (
  id uuid primary key,
  kind text not null check (kind in
    ('GREENHOUSE','LEVER','ASHBY','WORKDAY','SMARTRECRUITERS',
     'COMPANY_SITE','SEARCH_API','MANUAL_IMPORT')),
  org_identifier text not null,
  company_id uuid references companies(id),
  display_name text not null,
  capabilities jsonb not null,
  policy text not null default 'DISCOVERY_ONLY'
      check (policy in ('AUTOMATION_ALLOWED','AUTOMATION_REQUIRES_APPROVAL',
                        'DISCOVERY_ONLY','REVIEW_REQUIRED','DISABLED')),
  rate_limit_per_min int not null default 30,
  schedule_cron text, credential_ref text,
  enabled boolean not null default true,
  health jsonb not null default '{}',
  failure_streak int not null default 0,
  last_run_at timestamptz,
  unique(kind, org_identifier)
);

create table sponsor_records (
  id uuid primary key,
  company_id uuid not null references companies(id) on delete cascade,
  source text not null check (source in
    ('HOME_OFFICE_REGISTER','JOB_AD_TEXT','KNOWN_HISTORY','AI_INFERENCE','USER_INPUT')),
  licence_indicator text,
  confidence numeric(3,2),
  evidence jsonb not null default '{}',
  valid_as_of date,
  fetched_at timestamptz not null default now()
);
create index on sponsor_records(company_id);

-- ── jobs ──────────────────────────────────────────────────────────
create table jobs (
  id uuid primary key,
  source_id uuid not null references job_sources(id),
  external_id text not null,
  dedup_key text not null,
  company_id uuid references companies(id),
  company_name_raw text, title text not null,
  location_raw text, city text, country text,
  remote_type text check (remote_type in ('REMOTE','HYBRID','ONSITE','UNKNOWN')),
  employment_type text, experience_level text,
  salary_min numeric, salary_max numeric,
  salary_currency char(3), salary_period text,
  description_text text not null,
  skills_extracted text[] not null default '{}',
  application_url text, canonical_url text,
  posted_at timestamptz, posted_date_source text
      check (posted_date_source in ('EXPLICIT','INFERRED','UNKNOWN')),
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  repost_count int not null default 0,
  content_hash text not null,
  status text not null default 'DISCOVERED'
      check (status in ('DISCOVERED','FILTERED_OUT','ANALYSED','SCORED',
                        'DECIDED','ARCHIVED','PIPELINE_ERROR')),
  filter_reasons jsonb,
  search_vector tsvector generated always as
      (setweight(to_tsvector('english', coalesce(title,'')),'A') ||
       setweight(to_tsvector('english', left(description_text, 20000)),'B')) stored,
  deleted_at timestamptz,
  unique(source_id, external_id)
);
create index jobs_dedup_idx   on jobs(dedup_key);
create index jobs_status_idx  on jobs(status, first_seen_at desc);
create index jobs_fts_idx     on jobs using gin(search_vector);
create index jobs_company_idx on jobs(company_id);

create table job_snapshots (
  id uuid primary key,
  job_id uuid not null references jobs(id) on delete cascade,
  content_hash text not null,
  captured_at timestamptz not null default now(),
  diff_summary jsonb
);

-- ── intelligence outputs ──────────────────────────────────────────
create table job_analyses (
  id uuid primary key,
  job_id uuid not null references jobs(id) on delete cascade,
  prompt_version text not null, model_label text not null,
  sponsorship_status text not null
      check (sponsorship_status in ('LIKELY_AVAILABLE','POSSIBLE',
             'UNKNOWN','LIKELY_NOT_AVAILABLE','NOT_AVAILABLE')),
  sponsorship_confidence numeric(3,2) not null,
  sponsorship_reason text not null,
  sponsorship_evidence jsonb not null default '[]',
  skills_required jsonb, summary text, match_explanation text,
  red_flags jsonb, input_hash text, created_at timestamptz not null default now()
);
create index on job_analyses(job_id, created_at desc);

create table job_scores (
  id uuid primary key,
  job_id uuid not null references jobs(id) on delete cascade,
  overall int not null,
  recommendation text not null check (recommendation in ('APPLY','REVIEW','SKIP')),
  breakdown jsonb not null,
  weights_snapshot jsonb not null,
  explanation text, score_version int not null default 1,
  created_at timestamptz not null default now()
);
create index on job_scores(job_id, created_at desc);

-- ── approvals, applications, documents ────────────────────────────
create table approvals (
  id uuid primary key,
  kind text not null,
  context jsonb not null,
  status text not null default 'PENDING'
      check (status in ('PENDING','APPROVED','REJECTED','EXPIRED')),
  requested_at timestamptz not null default now(),
  decided_at timestamptz, decided_by text,
  expires_at timestamptz,
  entity_type text, entity_id uuid
);

create table applications (
  id uuid primary key,
  job_id uuid not null references jobs(id),
  ats_requisition_fingerprint text,
  status text not null default 'READY_TO_APPLY'
      check (status in ('READY_TO_APPLY','APPLICATION_STARTED','OTP_PENDING',
        'APPLICATION_SUBMITTED','CONFIRMATION_RECEIVED','INTERVIEW',
        'ASSESSMENT','REJECTED','OFFER','WITHDRAWN','FAILED')),
  mode text not null,
  cv_version_id uuid,
  submitted_at timestamptz,
  confirmation_email_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index applications_one_per_job on applications(job_id)
  where status not in ('FAILED','WITHDRAWN');

create table application_events (
  id uuid primary key,
  application_id uuid not null references applications(id) on delete cascade,
  type text not null, payload jsonb not null default '{}',
  actor text not null default 'SYSTEM',
  occurred_at timestamptz not null default now()
);

create table files (
  id uuid primary key,
  object_key text unique not null, sha256 text not null,
  content_type text, byte_size bigint not null,
  purpose text,
  created_at timestamptz not null default now()
);

create table cv_versions (
  id uuid primary key,
  parent_version_id uuid references cv_versions(id),
  job_id uuid references jobs(id),
  kind text not null check (kind in ('BASE','TAILORED')),
  title text not null, body_markdown text not null,
  pdf_file_id uuid references files(id),
  claims_validation jsonb not null default '{}',
  approved boolean not null default false,
  created_at timestamptz not null default now()
);

-- ── event layer ───────────────────────────────────────────────────
create table outbox_events (
  sequence_id bigserial,
  id uuid primary key,
  aggregate_type text not null, aggregate_id uuid not null,
  event_type text not null, schema_version int not null default 1,
  payload jsonb not null,
  correlation_id uuid not null, causation_id uuid,
  created_at timestamptz not null default now(),
  published_at timestamptz
);
create index outbox_pending_idx on outbox_events(sequence_id)
  where published_at is null;

create table consumed_events (
  event_id uuid not null, consumer text not null,
  processed_at timestamptz not null default now(),
  primary key (event_id, consumer)
);

-- ── llm subsystem ─────────────────────────────────────────────────
create table llm_providers (
  id text primary key,
  kind text not null check (kind in
    ('NVIDIA_NIM','GEMINI','OPENAI','OLLAMA','FUTURE')),
  base_url text, api_key_env_var text not null,
  enabled boolean not null default true, config jsonb not null default '{}'
);

create table llm_models (
  id uuid primary key,
  provider_id text not null references llm_providers(id),
  model_key text not null,
  display_name text, context_window int,
  input_cost_per_mtok numeric, output_cost_per_mtok numeric,
  capabilities text[] not null default '{}',
  enabled boolean not null default true,
  notes jsonb not null default '{}',
  unique(provider_id, model_key)
);

create table prompts (
  id uuid primary key,
  task_type text not null,
  name text not null, version int not null,
  template text not null, json_schema jsonb,
  checksum text not null, is_active boolean not null default false,
  created_at timestamptz not null default now(),
  unique(task_type, name, version)
);

create table llm_calls (
  id uuid primary key,
  task_type text not null, provider_id text, model_id uuid,
  ok boolean not null, http_status int, error_class text,
  latency_ms int not null,
  input_tokens int, output_tokens int, est_cost numeric,
  attempt int not null default 1,
  prompt_checksum text, correlation_id uuid not null,
  request_redacted jsonb, response_excerpt jsonb,
  created_at timestamptz not null default now()
);
create index llm_calls_task_idx on llm_calls(task_type, created_at desc);
create index llm_calls_model_idx on llm_calls(model_id, created_at desc);

create table model_benchmark_runs (
  id uuid primary key,
  suite text not null, task_type text not null,
  config jsonb not null,
  status text not null default 'RUNNING'
      check (status in ('RUNNING','COMPLETED','FAILED','CANCELLED')),
  started_at timestamptz not null default now(), finished_at timestamptz,
  git_sha text, notes text
);

create table benchmark_results (
  id uuid primary key,
  run_id uuid not null references model_benchmark_runs(id) on delete cascade,
  model_id uuid not null references llm_models(id),
  case_index int not null, passed boolean,
  score numeric,
  metrics jsonb not null
);
create index on benchmark_results(run_id);

create table routing_policies (
  task_type text primary key,
  primary_model_id uuid references llm_models(id),
  fallback_model_ids uuid[] not null default '{}',
  basis text not null check (basis in ('BENCHMARK','MANUAL','DEFAULT','MANUAL_REVIEW')),
  based_on_run_id uuid references model_benchmark_runs(id),
  rationale text, updated_at timestamptz not null default now()
);

-- ── ops ───────────────────────────────────────────────────────────
create table notifications (
  id uuid primary key, severity text not null default 'INFO',
  category text not null, title text not null, body text,
  link text, read_at timestamptz,
  created_at timestamptz not null default now()
);

create table audit_logs (
  id uuid primary key,
  actor text not null, action text not null,
  entity_type text, entity_id uuid,
  before_state jsonb, after_state jsonb,
  ip inet, correlation_id uuid,
  created_at timestamptz not null default now()
);
-- Enforced immutability: the application's runtime role may only insert.
-- backend_role is created by ops/DBA provisioning outside Flyway; guarded
-- with a DO block so this migration is idempotent/portable across envs
-- where the role may not exist yet (e.g. a fresh local dev DB).
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'backend_role') then
    execute 'revoke update, delete on audit_logs from backend_role';
  end if;
end
$$;
