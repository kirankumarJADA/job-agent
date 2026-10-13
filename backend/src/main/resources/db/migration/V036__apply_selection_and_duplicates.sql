-- Phase 8.2 (APPLY): employer application-form question provenance and
-- cross-source duplicate protection. Additive only: no existing column
-- changes meaning and no data is rewritten.
--
-- 1. Employer-required questions captured from a REAL, read-only inspection
--    of the job's application form (Greenhouse public form today). Each row
--    records where the question came from and what the source actually said:
--    required / optional / UNKNOWN. A field whose metadata is absent is
--    UNKNOWN — never assumed optional.
create table if not exists job_form_questions (
    id             uuid primary key,
    job_id         uuid not null references jobs(id) on delete cascade,
    source         text not null check (source in ('GREENHOUSE_PUBLIC_FORM')),
    form_url       text not null,
    question_key   text not null,
    question_text  text,
    required_state text not null check (required_state in ('REQUIRED', 'OPTIONAL', 'UNKNOWN')),
    answer_type    text not null,
    options        jsonb not null default '[]',
    capture_status text not null check (capture_status in ('INSPECTED', 'UNAVAILABLE')),
    captured_at    timestamptz not null default now(),
    unique (job_id, source, question_key)
);
create index if not exists job_form_questions_job_idx on job_form_questions(job_id);

-- 2. Answers bind to the stable form question they answer (where the capture
--    could match one) and record who confirmed them. Existing rows keep the
--    MODEL_DRAFT default: a generated draft is never presented as a
--    candidate-confirmed answer.
alter table application_answers add column if not exists form_question_id uuid
    references job_form_questions(id) on delete set null;
alter table application_answers add column if not exists answer_origin text not null default 'MODEL_DRAFT';
alter table application_answers drop constraint if exists application_answers_answer_origin_check;
alter table application_answers add constraint application_answers_answer_origin_check
    check (answer_origin in ('MODEL_DRAFT', 'CANDIDATE_CONFIRMED', 'CANDIDATE_EDITED'));

-- 3. Cross-source duplicate identity for applications. The key is derived
--    from the strongest identity signals actually available (normalised
--    application/canonical URL, employer requisition identifier, or the
--    source's external id) — never from titles. Two postings of the same role
--    seen through different boards collide here; genuinely different roles do
--    not. The partial unique index makes duplicate enforcement race-safe at
--    the persistence layer: under concurrent creation exactly one live
--    application per (profile, identity) can be committed. Legacy rows have a
--    NULL key (unknown identity) and are unaffected.
alter table applications add column if not exists identity_key text;
create unique index if not exists applications_profile_identity_live_uq
    on applications(profile_id, identity_key)
    where status not in ('FAILED', 'WITHDRAWN') and identity_key is not null;
