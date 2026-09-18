alter table profiles add column if not exists professional_summary text;
alter table profiles add column if not exists links jsonb not null default '{}'::jsonb;
alter table profiles add column if not exists master_revision bigint not null default 1;
alter table profiles add column if not exists setup_status text not null default 'INCOMPLETE';
alter table profiles drop constraint if exists profiles_setup_status_check;
alter table profiles add constraint profiles_setup_status_check check (setup_status in ('INCOMPLETE','READY'));

alter table work_experiences add column if not exists evidence_status text not null default 'USER_VERIFIED';
alter table education add column if not exists evidence_status text not null default 'USER_VERIFIED';
alter table projects add column if not exists evidence_status text not null default 'USER_VERIFIED';
alter table certifications add column if not exists evidence_status text not null default 'USER_VERIFIED';
alter table skills add column if not exists evidence_status text not null default 'USER_VERIFIED';

create table if not exists profile_evidence (
  id uuid primary key,
  profile_id uuid not null references profiles(id) on delete cascade,
  source_type text not null check (source_type in ('PROFILE','WORK_EXPERIENCE','EDUCATION','PROJECT','SKILL','CERTIFICATION','ACHIEVEMENT')),
  source_id uuid,
  claim text not null,
  evidence_status text not null default 'USER_VERIFIED' check (evidence_status in ('USER_VERIFIED','PENDING','REJECTED')),
  claim_hash text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(profile_id, source_type, source_id, claim_hash)
);
create index if not exists profile_evidence_profile_idx on profile_evidence(profile_id, evidence_status);

alter table cv_versions add column if not exists profile_id uuid references profiles(id) on delete set null;
alter table cv_versions add column if not exists profile_revision bigint;
alter table cv_versions add column if not exists profile_snapshot_hash text;
alter table cv_versions add column if not exists content_sha256 text;
alter table cv_versions add column if not exists immutable boolean not null default true;
create index if not exists cv_versions_job_profile_idx on cv_versions(job_id, profile_id, created_at desc);

create table if not exists cv_claim_evidence (
  id uuid primary key,
  cv_version_id uuid not null references cv_versions(id) on delete cascade,
  source_type text not null,
  source_id uuid,
  claim text not null,
  evidence_status text not null check (evidence_status in ('USER_VERIFIED','PENDING')),
  created_at timestamptz not null default now(),
  unique(cv_version_id, source_type, source_id, claim)
);
create index if not exists cv_claim_evidence_cv_idx on cv_claim_evidence(cv_version_id);

alter table resume_ats_analyses add column if not exists profile_revision bigint;
alter table resume_ats_analyses add column if not exists profile_snapshot_hash text;

create or replace function bump_master_profile_revision() returns trigger language plpgsql as $$
begin
  update profiles set master_revision = master_revision + 1, setup_status = 'INCOMPLETE', updated_at = now()
  where id = coalesce(new.profile_id, old.profile_id);
  return coalesce(new, old);
end;
$$;
drop trigger if exists work_experiences_master_revision on work_experiences;
create trigger work_experiences_master_revision after insert or update or delete on work_experiences
for each row execute function bump_master_profile_revision();
drop trigger if exists education_master_revision on education;
create trigger education_master_revision after insert or update or delete on education
for each row execute function bump_master_profile_revision();
drop trigger if exists projects_master_revision on projects;
create trigger projects_master_revision after insert or update or delete on projects
for each row execute function bump_master_profile_revision();
drop trigger if exists certifications_master_revision on certifications;
create trigger certifications_master_revision after insert or update or delete on certifications
for each row execute function bump_master_profile_revision();
drop trigger if exists skills_master_revision on skills;
create trigger skills_master_revision after insert or update or delete on skills
for each row execute function bump_master_profile_revision();

create or replace function reject_immutable_cv_update() returns trigger language plpgsql as $$
begin
  if old.immutable then
    raise exception 'IMMUTABLE_CV_VERSION';
  end if;
  return new;
end;
$$;
drop trigger if exists cv_versions_immutable_update on cv_versions;
create trigger cv_versions_immutable_update before update on cv_versions
for each row execute function reject_immutable_cv_update();

create or replace function reject_immutable_cv_delete() returns trigger language plpgsql as $$
begin
  if old.immutable then
    raise exception 'IMMUTABLE_CV_VERSION';
  end if;
  return old;
end;
$$;
drop trigger if exists cv_versions_immutable_delete on cv_versions;
create trigger cv_versions_immutable_delete before delete on cv_versions
for each row execute function reject_immutable_cv_delete();
