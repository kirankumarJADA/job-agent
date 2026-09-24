-- V023__audit_profile_link_no_fk.sql
--
-- Repairs the purge path broken by V022's audit_logs.profile_id foreign key.
--
-- V022 added:
--   alter table audit_logs add column ... references profiles(id) on delete set null
--
-- The intent was sound: audit rows must survive a profile purge (the record
-- of a purge has to outlive it), so the owner link should be dropped instead
-- of cascading. But the mechanism cannot work: V008 installs a BEFORE UPDATE
-- trigger that rejects EVERY mutation of audit_logs ("audit_logs is
-- append-only"), and `on delete set null` is implemented by the database as
-- exactly such an UPDATE on the referencing rows. Deleting a profile with
-- audit history therefore aborts with
--   ERROR: audit_logs is append-only: UPDATE is forbidden (SQLSTATE 55000)
-- so POST /auth/purge-my-data could never succeed for a real account. Caught
-- by AuthControllerIT against a real PostgreSQL in this session.
--
-- No ON DELETE behaviour can satisfy both properties (rows immutably kept AND
-- purge completing), because any FK enforcement writes to audit_logs during
-- the delete: SET NULL updates it, NO ACTION/CASCADE-avoidance refuses the
-- delete. The append-only guarantee is the stronger, security-relevant one,
-- so the foreign key is dropped and the ownership column becomes a plain
-- historical reference: written at INSERT for attribution (AuditController
-- scopes reads on it), kept verbatim after the referenced profile is purged.
-- Referential integrity of a dangling audit reference is not a requirement;
-- an unforgeable, unmodifiable trail is.

do $$
declare
  v_constraint text;
begin
  -- Resolve the FK on audit_logs.profile_id by catalog lookup rather than by
  -- assuming V022 got the default name, so this also cleans up any database
  -- where the constraint was created under a different (or renamed) name.
  select c.conname into v_constraint
    from pg_constraint c
    join pg_attribute a
      on a.attrelid = c.conrelid and a.attnum = any (c.conkey)
   where c.conrelid = 'audit_logs'::regclass
     and c.contype = 'f'
     and a.attname = 'profile_id'
   limit 1;

  if v_constraint is not null then
    execute format('alter table audit_logs drop constraint %I', v_constraint);
  end if;
end
$$;

-- The index stays: reads are attributed per profile (profile_id, created_at).
comment on column audit_logs.profile_id is
  'Profile of the acting user, written at INSERT. NULL for legacy rows (append-only per V008, so they cannot be backfilled) and for system/ops actions; AuditController attributes NULL rows at read time by actor email. No foreign key: the profile row may be purged while the audit row is retained, leaving the id as a historical reference (V023 — the FK''s on delete set null was unenforceable against the append-only trigger and made profile purge fail).';
