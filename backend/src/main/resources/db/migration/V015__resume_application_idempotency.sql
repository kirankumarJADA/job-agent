alter table resume_ats_analyses drop constraint if exists resume_ats_analyses_profile_id_job_id_input_hash_key;
create unique index if not exists resume_ats_no_application_unique
  on resume_ats_analyses(profile_id, job_id, input_hash) where application_id is null;
create unique index if not exists resume_ats_application_unique
  on resume_ats_analyses(profile_id, job_id, input_hash, application_id) where application_id is not null;
