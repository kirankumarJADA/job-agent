-- Phase 8.1 (PREP): document review, correction lineage and letter PDFs.
-- Additive only. No existing column changes meaning; no data is rewritten.

-- 1. Cover-letter version numbers are per owner. V006 declared
--    unique(job_id, version) globally, so a second candidate generating a
--    letter for the same job collided with the first candidate's v1. The
--    repository already numbers versions per (profile, job); the constraint
--    now matches it.
alter table cover_letters drop constraint if exists cover_letters_job_id_version_key;
create unique index if not exists cover_letters_profile_job_version_uq
    on cover_letters(profile_id, job_id, version);

-- 2. Correction lineage. A user correction never edits an existing letter:
--    it is a new version that points at the version it corrects.
alter table cover_letters add column if not exists parent_version_id uuid
    references cover_letters(id) on delete set null;
alter table cover_letters add column if not exists origin text not null default 'GENERATED';
alter table cover_letters drop constraint if exists cover_letters_origin_check;
alter table cover_letters add constraint cover_letters_origin_check
    check (origin in ('GENERATED', 'USER_CORRECTED'));

-- 3. Immutable letter PDF. The bytes live in files (with their sha256), the
--    same storage tailored CVs use.
alter table cover_letters add column if not exists pdf_file_id uuid references files(id) on delete restrict;

-- 4. Owner review of a tailored CV version. cv_versions rows are immutable
--    (V013 trigger), so the decision is stored beside the version and bound
--    to the digest of the artifact that was reviewed.
create table if not exists cv_version_reviews (
    cv_version_id  uuid primary key references cv_versions(id) on delete cascade,
    profile_id     uuid not null references profiles(id) on delete cascade,
    approved       boolean not null,
    decided_by     text not null,
    decided_at     timestamptz not null default now(),
    content_sha256 text not null
);
create index if not exists cv_version_reviews_profile_idx on cv_version_reviews(profile_id);
