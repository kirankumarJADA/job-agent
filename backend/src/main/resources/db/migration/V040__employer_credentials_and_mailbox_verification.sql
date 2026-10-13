-- Phase 8.5: employer account lifecycle + real mailbox verification.
--
-- 1. employer_credentials: the candidate's approved login for an employer
--    portal (or the account Robin created with explicit approval). Username
--    and password are stored ENCRYPTED (AES-256-GCM, key from
--    APP_SECRETS_KEY) and are only ever decrypted for the worker that is
--    actively executing that candidate's own application. Never logged,
--    never sent to an LLM, never included in API responses.
-- 2. mailbox_verification: audit of every OTP/link resolution the worker
--    requested, so verification is attributable and replay-safe.

create table if not exists employer_credentials (
    id              uuid primary key,
    profile_id      uuid not null references profiles(id) on delete cascade,
    employer_domain text not null,
    username_encrypted      text not null,
    password_encrypted      text not null,
    created_by              text not null default 'CANDIDATE',
    status                  text not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    unique (profile_id, employer_domain)
);

create table if not exists mailbox_verifications (
    id              uuid primary key,
    application_id  uuid not null references applications(id) on delete cascade,
    account_session_id uuid references account_sessions(id),
    employer_domain text not null,
    provider_id     text not null,
    source_email_id uuid references emails(id),
    otp_value_ref   text,
    link_url        text,
    expires_at      timestamptz,
    consumed_at     timestamptz,
    created_at      timestamptz not null default now()
);

create index if not exists mailbox_verifications_application_idx
    on mailbox_verifications(application_id, created_at desc);
