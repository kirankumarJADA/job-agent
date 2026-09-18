alter table cv_versions add column if not exists application_id uuid references applications(id) on delete set null;
create index if not exists cv_versions_application_idx on cv_versions(application_id);
