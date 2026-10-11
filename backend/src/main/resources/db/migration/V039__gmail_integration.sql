-- Phase 8.4: real Gmail integration (read-only, TRACK workflow).
-- One connection per profile: the candidate's own Gmail mailbox, connected
-- through OAuth with offline access. The refresh token is stored ENCRYPTED
-- (AES-256-GCM; key from APP_GMAIL_TOKEN_KEY), never in plaintext and never
-- logged. Read-only scope only: Robin never sends, deletes, labels or
-- modifies mail.

create table if not exists gmail_connections (
    id                      uuid primary key,
    profile_id              uuid not null unique references profiles(id) on delete cascade,
    google_email            text not null,
    encrypted_refresh_token text not null,
    scopes                  text not null,
    status                  text not null default 'CONNECTED'
        check (status in ('CONNECTED', 'REAUTH_REQUIRED', 'DISCONNECTED')),
    history_id              bigint,
    last_sync_at            timestamptz,
    last_sync_status        text,
    last_sync_error         text,
    connected_at            timestamptz not null default now(),
    updated_at              timestamptz not null default now()
);

create index if not exists gmail_connections_status_idx
    on gmail_connections(status, last_sync_at);
