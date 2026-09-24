-- Firebase Authentication identity layer.
--
-- Links each local `users` row to its Firebase identity. Firebase owns
-- credential verification (password storage, hashing, password-reset
-- emails) for federated accounts, so `password_hash` becomes nullable.
--
-- The legacy locally-hashed credential added by V002/V003 keeps working
-- unchanged: `POST /api/v1/auth/login` still authenticates those rows, and
-- existing accounts are never rewritten by this migration. New rows created
-- through Firebase simply have no local password at all.
--
-- `firebase_uid` is nullable because every pre-existing row predates
-- Firebase; the partial unique index (below) enforces "at most one local
-- row per Firebase identity" without colliding on the NULLs. `auth_provider`
-- records which authority actually owns the credential so the backend can
-- refuse a local password login for a pure-Firebase account.
--
-- Everything here is idempotent: `add column if not exists`,
-- `drop not null` (a no-op when already nullable), a guarded constraint,
-- and a partial index created with `if not exists`.

alter table users add column if not exists firebase_uid text;
alter table users add column if not exists auth_provider text not null default 'LOCAL';
alter table users add column if not exists updated_at timestamptz not null default now();

-- Firebase accounts have no locally stored hash. Kept nullable rather than
-- empty-string so "no password" cannot be confused with "a password that
-- happens to be blank" by Argon2PasswordEncoder.matches().
alter table users alter column password_hash drop not null;

-- Guarded so re-running this migration on a schema that already has the
-- constraint is a no-op rather than an error.
do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'users_auth_provider_check') then
    alter table users add constraint users_auth_provider_check
      check (auth_provider in ('LOCAL', 'FIREBASE'));
  end if;
end $$;

-- One local account per Firebase UID. Partial: pre-Firebase rows are NULL
-- and must not conflict with each other.
create unique index if not exists users_firebase_uid_key
  on users (firebase_uid) where firebase_uid is not null;
