create table if not exists resume_ats_analyses (
  id uuid primary key,
  profile_id uuid not null references profiles(id) on delete cascade,
  job_id uuid not null references jobs(id) on delete cascade,
  application_id uuid references applications(id) on delete set null,
  input_hash text not null,
  role text,
  domain text,
  required_skills jsonb not null default '[]',
  preferred_skills jsonb not null default '[]',
  normalized_skills jsonb not null default '{}',
  verified_evidence jsonb not null default '[]',
  gaps jsonb not null default '[]',
  ats_report jsonb not null default '{}',
  cv_version_id uuid references cv_versions(id),
  created_at timestamptz not null default now(),
  unique(profile_id, job_id, input_hash)
);
create index if not exists resume_ats_job_idx on resume_ats_analyses(job_id, created_at desc);
create index if not exists resume_ats_application_idx on resume_ats_analyses(application_id);
