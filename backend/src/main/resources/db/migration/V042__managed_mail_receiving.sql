-- Phase 8.5 (managed mail): Robin-managed receiving addresses.
--
-- Robin gives each candidate one opaque address under a domain the operator
-- owns (for example u7kq2m9x4vb3tn5p@mail.robin.example). Employer
-- registrations use that address, so verification codes reach Robin through
-- an inbound provider (Mailgun routes first) without the candidate's personal
-- Gmail being connected.
--
-- 1. managed_mail_domains: evidence that receiving works for a domain. A row
--    is written only when a provider-signed inbound delivery for that domain
--    has been accepted, so an address is never presented as working before
--    DNS, the provider route and the webhook signature have all been proven.
-- 2. managed_mail_addresses: one alias per profile per domain. Aliases are
--    random (about 80 bits), never derived from the candidate's identity, and
--    unique per domain.
-- 3. managed_mail_webhook_tokens: provider webhook tokens already accepted,
--    stored as SHA-256 hashes with a digest of the payload they arrived with,
--    so a captured request cannot be replayed and a provider retry of the same
--    request is recognised as the same delivery.
-- 4. managed_inbound_messages: one row per delivered message, routed by the
--    SMTP envelope recipient (never the visible To header). Content needed to
--    extract a verification code is stored only encrypted (AES-256-GCM via
--    APP_SECRETS_KEY), only for a short retention window, and is erased as
--    soon as the message is used. The subject is kept only in redacted form.
-- 5. mailbox_verifications.source_managed_message_id: one-time use of a
--    managed message for verification, enforced by a unique index.

create table if not exists managed_mail_domains (
    domain                 text primary key
        check (domain = lower(domain)
               and domain ~ '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$'),
    provider_id            text not null check (provider_id in ('mailgun')),
    receiving_verified_at  timestamptz not null,
    last_inbound_at        timestamptz not null,
    created_at             timestamptz not null default now()
);

create table if not exists managed_mail_addresses (
    id          uuid primary key,
    profile_id  uuid not null references profiles(id) on delete cascade,
    local_part  text not null check (local_part ~ '^[a-z][a-z0-9]{11,31}$'),
    domain      text not null check (domain = lower(domain)),
    status      text not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    created_at  timestamptz not null default now(),
    disabled_at timestamptz,
    constraint managed_mail_addresses_alias_unique unique (local_part, domain),
    constraint managed_mail_addresses_owner_domain_unique unique (profile_id, domain),
    -- Target of the composite foreign key below: a message can only ever be
    -- filed under the profile that owns the address it was delivered to.
    constraint managed_mail_addresses_id_owner_unique unique (id, profile_id)
);

create table if not exists managed_mail_webhook_tokens (
    token_sha256    text primary key check (token_sha256 ~ '^[0-9a-f]{64}$'),
    provider_id     text not null check (provider_id in ('mailgun')),
    signed_at       timestamptz not null,
    payload_sha256  text not null check (payload_sha256 ~ '^[0-9a-f]{64}$'),
    received_at     timestamptz not null default now()
);

create index if not exists managed_mail_webhook_tokens_received_idx
    on managed_mail_webhook_tokens(received_at);

create table if not exists managed_inbound_messages (
    id                     uuid primary key,
    profile_id             uuid not null references profiles(id) on delete cascade,
    address_id             uuid not null,
    provider_id            text not null check (provider_id in ('mailgun')),
    message_id_sha256      text check (message_id_sha256 ~ '^[0-9a-f]{64}$'),
    content_sha256         text not null check (content_sha256 ~ '^[0-9a-f]{64}$'),
    envelope_recipient     text not null,
    envelope_sender        text,
    from_address           text,
    sender_domain          text,
    subject_redacted       text not null default '',
    -- AES-256-GCM ciphertext of the capped subject, text and links; null once erased.
    content_encrypted      text,
    content_purged_at      timestamptz,
    spf_result             text,
    dkim_result            text,
    spam_flagged           boolean not null default false,
    authentication_failed  boolean not null default false,
    signed_at              timestamptz not null,
    received_at            timestamptz not null default now(),
    consumed_at            timestamptz,
    constraint managed_inbound_messages_address_owner_fk
        foreign key (address_id, profile_id)
        references managed_mail_addresses(id, profile_id) on delete cascade,
    -- Content is either present and not yet erased, or erased with a time.
    constraint managed_inbound_messages_content_state
        check ((content_encrypted is null) = (content_purged_at is not null))
);

-- Duplicate deliveries (provider retries with a new token, or a sender's MTA
-- retrying) collapse to one row: by Message-Id when the message has one, by a
-- content fingerprint otherwise.
create unique index if not exists managed_inbound_messages_message_once_idx
    on managed_inbound_messages(address_id, message_id_sha256)
    where message_id_sha256 is not null;

create unique index if not exists managed_inbound_messages_content_once_idx
    on managed_inbound_messages(address_id, content_sha256)
    where message_id_sha256 is null;

create index if not exists managed_inbound_messages_owner_idx
    on managed_inbound_messages(profile_id, received_at desc);

create index if not exists managed_inbound_messages_retention_idx
    on managed_inbound_messages(received_at)
    where content_encrypted is not null;

alter table mailbox_verifications
    add column if not exists source_managed_message_id uuid
        references managed_inbound_messages(id) on delete set null;

create unique index if not exists mailbox_verifications_managed_message_once_idx
    on mailbox_verifications(source_managed_message_id)
    where source_managed_message_id is not null;

-- 6. applicant_identities (V009) never cascaded from its profile. Identities are
--    now recorded during ordinary employer registration (the managed address is
--    the default), so "delete my data" (delete from profiles) must remove them
--    too instead of failing on this foreign key.
alter table applicant_identities drop constraint if exists applicant_identities_profile_id_fkey;
alter table applicant_identities
    add constraint applicant_identities_profile_id_fkey
        foreign key (profile_id) references profiles(id) on delete cascade;

-- 7. The same purge failed for any profile that had started an employer-account
--    session: references into and out of account_sessions (V009, V040) had no
--    delete action, and applications <-> account_sessions reference each other.
--    A session belongs to its application; what belongs to a session goes with
--    it; a reference to a removed session or identity is cleared.
alter table account_sessions drop constraint if exists account_sessions_application_id_fkey;
alter table account_sessions
    add constraint account_sessions_application_id_fkey
        foreign key (application_id) references applications(id) on delete cascade;
alter table account_sessions drop constraint if exists account_sessions_applicant_identity_id_fkey;
alter table account_sessions
    add constraint account_sessions_applicant_identity_id_fkey
        foreign key (applicant_identity_id) references applicant_identities(id) on delete set null;
alter table applications drop constraint if exists applications_account_session_id_fkey;
alter table applications
    add constraint applications_account_session_id_fkey
        foreign key (account_session_id) references account_sessions(id) on delete set null;
alter table mailbox_verifications drop constraint if exists mailbox_verifications_account_session_id_fkey;
alter table mailbox_verifications
    add constraint mailbox_verifications_account_session_id_fkey
        foreign key (account_session_id) references account_sessions(id) on delete set null;
alter table verification_extractions drop constraint if exists verification_extractions_account_session_id_fkey;
alter table verification_extractions
    add constraint verification_extractions_account_session_id_fkey
        foreign key (account_session_id) references account_sessions(id) on delete cascade;
alter table verification_extractions drop constraint if exists verification_extractions_source_email_id_fkey;
alter table verification_extractions
    add constraint verification_extractions_source_email_id_fkey
        foreign key (source_email_id) references emails(id) on delete cascade;
