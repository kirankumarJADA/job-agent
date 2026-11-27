-- V073: Stage 3I Phase 5 — separated pending/confirmed account registration state and strict active verification lease uniqueness.
--
-- Additive only. V001-V072 remain byte-for-byte untouched.
--
-- 1. applicant_identities.account_status:
--    * Widen CHECK constraint to allow 'PENDING' alongside 'ACTIVE' and 'DISABLED' so
--      authorizing an account-creation plan does not prematurely mark an unverified,
--      pre-signup identity as an already-registered employer account ('ACTIVE').
--    * Existing rows and direct IdentityService inserts default to 'ACTIVE'.
--
-- 2. mailbox_verifications_active_app_session_uidx:
--    * Guarantee at PostgreSQL level that at most one RESERVED verification lease exists
--      per (application_id, coalesce(account_session_id, sentinel)), closing concurrency races.

do $$
declare
    v_schema text := current_schema();
    v_table_oid oid;
    v_conname text;
begin
    select c.oid
      into v_table_oid
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = v_schema
       and c.relname = 'applicant_identities'
       and c.relkind in ('r', 'p');

    if v_table_oid is null then
        raise exception 'Expected table %.applicant_identities does not exist', v_schema;
    end if;

    for v_conname in
        select con.conname
          from pg_constraint con
         where con.conrelid = v_table_oid
           and con.contype = 'c'
           and pg_get_constraintdef(con.oid) ilike '%account_status%'
    loop
        execute format(
            'alter table %I.%I drop constraint %I',
            v_schema,
            'applicant_identities',
            v_conname
        );
    end loop;

    execute format(
        'alter table %I.%I add constraint applicant_identities_account_status_check check (account_status in (''PENDING'', ''ACTIVE'', ''DISABLED''))',
        v_schema,
        'applicant_identities'
    );
end $$;

create unique index if not exists mailbox_verifications_active_app_session_uidx
    on mailbox_verifications (application_id, coalesce(account_session_id, '00000000-0000-0000-0000-000000000000'::uuid))
    where status = 'RESERVED';
