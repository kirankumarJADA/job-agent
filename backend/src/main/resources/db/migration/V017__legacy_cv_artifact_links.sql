create table if not exists cv_artifact_links (
  cv_version_id uuid primary key references cv_versions(id) on delete cascade,
  file_id uuid not null references files(id) on delete restrict,
  created_at timestamptz not null default now()
);
insert into cv_artifact_links(cv_version_id, file_id)
select id, pdf_file_id from cv_versions where pdf_file_id is not null
on conflict do nothing;
