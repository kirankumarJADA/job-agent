-- V069: durable, server-verified submission receipts (Wave 3 Sprint 2.2.1).
--
-- Additive only. Three tables:
--
--   submission_evidence
--     What the authenticated worker reported for one real-submit attempt.
--     Written ONLY by the worker completion path, in the same transaction that
--     moves the plan to SUBMITTED. Its existence is the provenance: nothing a
--     caller writes into automation_plans.result can create a row here. Raw
--     evidence is stored encrypted (AES-GCM, app.secrets.encryption-key) with
--     a server-computed SHA-256 digest of the plaintext.
--
--   candidate_sandbox_registrations
--     Server-side record of a candidate-owned sandbox posting. Receipt
--     verification checks this table; caller-supplied "sandbox" booleans are
--     never trusted.
--
--   submission_receipts
--     A receipt that passed every verification. One per plan (unique), with a
--     unique idempotency key so concurrent or replayed captures cannot create
--     a second receipt. Conflicting evidence for the same key is rejected by
--     the application before insert.
--
-- Rows are immutable once written (UPDATE is rejected by trigger). DELETE is
-- permitted so that the existing profile purge (ON DELETE CASCADE from
-- profiles) keeps working. A receipt's references to its evidence and its
-- sandbox registration are NO ACTION (checked at end of statement): deleting
-- either one directly while a receipt depends on it fails, so a verified
-- receipt can never disappear as a side effect — while a profile purge, which
-- removes all three in the same statement, still succeeds. A registration is
-- withdrawn by setting revoked_at, never by deleting it.

create table submission_evidence (
    id                        uuid primary key,
    plan_id                   uuid not null unique references automation_plans(id) on delete cascade,
    application_id            uuid not null references applications(id) on delete cascade,
    profile_id                uuid not null references profiles(id) on delete cascade,
    worker_principal          text not null,
    recorded_via              text not null,
    ats_platform              text not null,
    target_url                text,
    confirmation_category     text not null,
    employer_confirmation_id  text,
    observed_signals          jsonb not null default '[]'::jsonb,
    evidence_text             text not null,
    raw_evidence_sha256       char(64),
    raw_evidence_ciphertext   text,
    raw_evidence_byte_length  integer,
    raw_evidence_status       text not null
        check (raw_evidence_status in ('STORED_ENCRYPTED', 'NOT_PROVIDED', 'REJECTED_TOO_LARGE', 'ENCRYPTION_UNAVAILABLE')),
    recorded_at               timestamptz not null default clock_timestamp(),
    check ((raw_evidence_status = 'STORED_ENCRYPTED') = (raw_evidence_ciphertext is not null and raw_evidence_sha256 is not null))
);

create index submission_evidence_application_idx on submission_evidence(application_id);
create index submission_evidence_profile_idx on submission_evidence(profile_id);

create table candidate_sandbox_registrations (
    id                uuid primary key,
    profile_id        uuid not null references profiles(id) on delete cascade,
    ats_platform      text not null,
    posting_url       text not null,
    permitted_domain  text not null,
    approved_by       text not null,
    approved_at       timestamptz not null default clock_timestamp(),
    revoked_at        timestamptz,
    unique (profile_id, ats_platform, posting_url)
);

create index candidate_sandbox_registrations_profile_idx
    on candidate_sandbox_registrations(profile_id);

create table submission_receipts (
    id                        uuid primary key,
    idempotency_key           text not null unique,
    plan_id                   uuid not null unique references automation_plans(id) on delete cascade,
    application_id            uuid not null references applications(id) on delete cascade,
    profile_id                uuid not null references profiles(id) on delete cascade,
    attempt_number            integer not null check (attempt_number > 0),
    evidence_id               uuid not null unique references submission_evidence(id),
    evidence_sha256           char(64) not null,
    ats_platform              text not null,
    employer_confirmation_id  text not null,
    observed_signals          jsonb not null,
    approval_audit_id         uuid not null,
    approval_actor            text not null,
    approved_at               timestamptz not null,
    worker_principal          text not null,
    sandbox_registration_id   uuid not null references candidate_sandbox_registrations(id),
    policy_version            text not null,
    captured_at               timestamptz not null default clock_timestamp()
);

create index submission_receipts_application_idx on submission_receipts(application_id);
create index submission_receipts_profile_idx on submission_receipts(profile_id);

create or replace function reject_receipt_record_update()
returns trigger
language plpgsql
as $$
begin
  raise exception '% is immutable: UPDATE is forbidden', tg_table_name
    using errcode = '55000';
end;
$$;

drop trigger if exists submission_evidence_immutable on submission_evidence;
create trigger submission_evidence_immutable
before update on submission_evidence
for each row execute function reject_receipt_record_update();

drop trigger if exists submission_receipts_immutable on submission_receipts;
create trigger submission_receipts_immutable
before update on submission_receipts
for each row execute function reject_receipt_record_update();
