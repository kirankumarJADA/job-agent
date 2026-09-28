-- V024__neutralize_seeded_dev_credential.sql
--
-- Closes a production security hole that V002/V003 opened by accident.
--
-- V002 seeds a LOCAL development account (dev@example.local) and V003 sets its
-- password_hash to the Argon2id hash of a password that V003's own comment
-- writes out in full. Flyway applies the same chain in every environment, and
-- POST /api/v1/auth/login is reachable without a session, so the hosted
-- deployment ended up holding a working credential that anyone who can read
-- this repository knows. A publicly-known password is not a password.
--
-- V002/V003 are already applied in production and are immutable -- editing them
-- would change their recorded checksums and break validation on every existing
-- database -- so this is a forward-only correction.
--
-- NEUTRALIZE, DO NOT DELETE. `profiles.user_id` and everything beneath profiles
-- cascades from `users` (V001), and an operator may have written real data on
-- this account: V022's ownership backfill attributes legacy rows to "the single
-- profile that could have owned them", which is precisely the seeded profile.
-- A DELETE would therefore destroy real production data as a side effect of a
-- security fix. Setting `password_hash` to NULL removes the only thing that was
-- ever wrong -- the credential -- and V020 already made that column nullable,
-- with the login path proven safe for a NULL hash: the configured encoder
-- returns false for it, so the login answers 401 rather than 500 (see
-- PasswordlessAccountSafetyTest and SeededDevCredentialRemovalIT).
--
-- SCOPE: exactly the still-unmodified seed credential, matched on the V003
-- hash. A rotated password, a Firebase-linked account and every other account
-- are left alone, which is what makes this both precise and idempotent: running
-- it again on a corrected database changes nothing.
--
-- ENVIRONMENT: the placeholder removed from both statements below is
-- `remove_seed_dev_account`, supplied as
-- `spring.flyway.placeholders.remove_seed_dev_account`. application.yml
-- defaults it to TRUE, so any deployment that has not explicitly declared
-- itself local loses the published credential; application-local.yml sets it to
-- FALSE so a fresh local database keeps the seeded login that local inspection
-- mode (frontend/src/localInspection.ts) signs in with. The polarity is
-- deliberate: an unknown, misspelled or future profile is treated as
-- production.
--
-- Note the direction of this fix: it can only ever REMOVE the development
-- credential. No runtime code path reinstates it, so a deployment that applies
-- this migration cannot have it re-created by configuration.

-- Guard: refuse to run unless the placeholder was actually substituted and
-- holds one of the two expected values. An unsubstituted token, or a typo such
-- as "yes", fails the migration loudly instead of quietly leaving the published
-- credential in place.
select cast(
         case when '${remove_seed_dev_account}' in ('true', 'false')
              then '0'
              else 'spring.flyway.placeholders.remove_seed_dev_account must be exactly "true" or "false"'
         end as integer) as placeholder_guard;

-- The V003 hash appears here as a MATCH KEY, not as a credential being
-- installed: this WHERE clause is what keeps the correction scoped to the
-- published credential.
update users
   set password_hash = null,
       updated_at = now()
 where email = 'dev@example.local'
   and password_hash = '$argon2id$v=19$m=19456,t=2,p=1$F9uqoBCrDE3/mNdpD/bz7w$N/dY7+nj/TzP1oUNVRW55nb+/YJcHrWUqyKbu8t63ic'
   and '${remove_seed_dev_account}' = 'true';
