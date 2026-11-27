-- V072: Crash-safe verification leases & Cloudflare sender authentication metadata (Stage 3I Phase 3).
--
-- Additive only. V001-V071 remain byte-for-byte untouched.
--
-- 1. managed_inbound_messages:
--    * Persist structured sender authentication evidence produced by Cloudflare Email Routing
--      (dkim_domain, dmarc_result, auth_serv_id, auth_trusted) so automated OTP resolution can
--      require trusted Authentication-Results verification (`auth_trusted = true`) even while
--      `usableForRegistration` reflects signed webhook transport readiness.
--    * Add bounded short-lived reservation columns so resolving a verification message leases
--      it atomically for one application/session instead of deleting `content_encrypted` before
--      the worker confirms actual OTP/link consumption in the browser.
--
-- 2. mailbox_verifications:
--    * Track lease lifecycle (`RESERVED`, `CONSUMED`, `RELEASED`, `EXPIRED`), expiry timestamp
--      (`reserved_until`), and the SHA-256 digest of the opaque lease confirmation token
--      (`reservation_token_sha256`).
--    * Scope the one-time source unique indexes (`mailbox_verifications_source_email_once_idx` and
--      `mailbox_verifications_managed_message_once_idx`) to `status in ('RESERVED', 'CONSUMED')`
--      so an abandoned/expired/released lease can be recovered cleanly while still forbidding
--      concurrent duplicate claims or reuse of consumed messages at the database level.

-- ── 0. Fail closed if expected schema does not exist.
do $$
declare
    v_missing text[] := array[]::text[];
    v_expected record;
begin
    for v_expected in
        select * from (values
            ('managed_inbound_messages', 'id'),
            ('managed_inbound_messages', 'profile_id'),
            ('managed_inbound_messages', 'provider_id'),
            ('managed_inbound_messages', 'content_encrypted'),
            ('managed_inbound_messages', 'content_purged_at'),
            ('managed_inbound_messages', 'consumed_at'),
            ('mailbox_verifications', 'id'),
            ('mailbox_verifications', 'application_id'),
            ('mailbox_verifications', 'account_session_id'),
            ('mailbox_verifications', 'source_email_id'),
            ('mailbox_verifications', 'source_managed_message_id'),
            ('mailbox_verifications', 'consumed_at'),
            ('applications', 'id'),
            ('account_sessions', 'id')
        ) as t(table_name, column_name)
    loop
        if not exists (
            select 1 from information_schema.columns c
             where c.table_schema = current_schema()
               and c.table_name = v_expected.table_name
               and c.column_name = v_expected.column_name) then
            v_missing := v_missing || (v_expected.table_name || '.' || v_expected.column_name);
        end if;
    end loop;
    if coalesce(array_length(v_missing, 1), 0) > 0 then
        raise exception 'V072 schema assumptions do not hold; missing: %', array_to_string(v_missing, ', ');
    end if;
end $$;

-- ── 1. Cloudflare sender auth & bounded lease columns on managed_inbound_messages.
alter table managed_inbound_messages
    add column if not exists dkim_domain text,
    add column if not exists dmarc_result text,
    add column if not exists auth_serv_id text,
    add column if not exists auth_trusted boolean not null default false,
    add column if not exists reserved_for_application_id uuid references applications(id) on delete set null,
    add column if not exists reserved_for_session_id uuid references account_sessions(id) on delete set null,
    add column if not exists reserved_at timestamptz,
    add column if not exists reservation_expires_at timestamptz,
    add column if not exists reservation_token_sha256 text
        check (reservation_token_sha256 ~ '^[0-9a-f]{64}$');

create index if not exists managed_inbound_messages_reservation_idx
    on managed_inbound_messages(reserved_for_application_id, reservation_expires_at)
    where reserved_at is not null and consumed_at is null;

-- ── 2. Lease lifecycle state on mailbox_verifications & scoped one-time indexes.
alter table mailbox_verifications
    add column if not exists status text not null default 'CONSUMED'
        check (status in ('RESERVED', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    add column if not exists reserved_until timestamptz,
    add column if not exists reservation_token_sha256 text
        check (reservation_token_sha256 ~ '^[0-9a-f]{64}$');

create index if not exists mailbox_verifications_status_reserved_until_idx
    on mailbox_verifications(status, reserved_until)
    where status = 'RESERVED';

create index if not exists mailbox_verifications_application_status_idx
    on mailbox_verifications(application_id, status, created_at desc);

drop index if exists mailbox_verifications_source_email_once_idx;
create unique index mailbox_verifications_source_email_once_idx
    on mailbox_verifications(source_email_id)
    where source_email_id is not null and status in ('RESERVED', 'CONSUMED');

drop index if exists mailbox_verifications_managed_message_once_idx;
create unique index mailbox_verifications_managed_message_once_idx
    on mailbox_verifications(source_managed_message_id)
    where source_managed_message_id is not null and status in ('RESERVED', 'CONSUMED');
