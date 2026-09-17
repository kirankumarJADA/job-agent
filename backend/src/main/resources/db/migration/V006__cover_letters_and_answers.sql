-- V006__cover_letters_and_answers.sql
-- Subsystem for Feature 2 (Cover Letters) and Feature 3 (Application QA)

create table if not exists cover_letters (
    id                  uuid primary key,
    profile_id          uuid not null references profiles(id) on delete cascade,
    job_id              uuid not null references jobs(id) on delete cascade,
    application_id      uuid references applications(id) on delete set null,
    version             int not null default 1,
    title               text not null,
    body_markdown       text not null,
    claims_validation   jsonb not null default '{}',
    is_approved         boolean not null default false,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    unique(job_id, version)
);
create index if not exists cover_letters_job_idx on cover_letters(job_id);
create index if not exists cover_letters_profile_idx on cover_letters(profile_id);

create table if not exists application_answers (
    id                  uuid primary key,
    profile_id          uuid not null references profiles(id) on delete cascade,
    job_id              uuid not null references jobs(id) on delete cascade,
    application_id      uuid references applications(id) on delete set null,
    question_text       text not null,
    question_type       text not null default 'OPEN_ENDED',
    answer_text         text not null,
    confidence          numeric(3,2) not null default 1.0,
    status              text not null default 'ANSWERED' check (status in ('ANSWERED', 'NEEDS_USER_INPUT', 'HARD_STOP')),
    validation_notes    jsonb not null default '{}',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
create index if not exists application_answers_job_idx on application_answers(job_id);
