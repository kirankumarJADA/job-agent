-- V003__fix_seed_user_password.sql
-- V002's seeded password_hash was literally base64 of the placeholder text
-- "placeholdersalt"/"placeholderhash" — never derived from a real password
-- via Argon2, so no password could ever verify against it. Discovered
-- during P1-b implementation (auth is the first slice that actually
-- exercises this column). Per the DB evolution plan, V002 is already
-- applied and immutable, so this is a corrective UPDATE, not an edit to
-- V002 itself.
--
-- Dev login for the seeded user (dev@example.local) is: DevPassword123!
-- Local dev only — rotate before any non-local use, same as before.
--
-- Hash generated with argon2-cffi (a standards-compliant Argon2 reference
-- implementation) using the same parameters V002 already declared
-- (m=19456, t=2, p=1), so this is a like-for-like replacement, not a
-- parameter change. Self-verified independently (correct password
-- validates, wrong password rejected) — see P1-b implementation notes for
-- what was and wasn't verified against Spring Security's own decoder.

update users
set password_hash = '$argon2id$v=19$m=19456,t=2,p=1$F9uqoBCrDE3/mNdpD/bz7w$N/dY7+nj/TzP1oUNVRW55nb+/YJcHrWUqyKbu8t63ic'
where email = 'dev@example.local';
