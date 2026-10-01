-- Generated answers are drafts even when their content passed automated checks.
-- Only an explicit owner action may enable an answer for controlled autofill.
alter table application_answers
    add column if not exists human_confirmed boolean not null default false;
