-- Ownership scoping for external-account automation sessions.
--
-- `account_sessions` previously recorded only the application it belonged to.
-- `applications` has no user column (it predates any multi-user concern), so a
-- session id was not attributable to an account, and the
-- /api/v1/identity/sessions endpoints had to take the caller's ids at face
-- value. That is a cross-account read/write hole as soon as more than one
-- account exists.
--
-- Scoping the session itself to the owning profile closes it: every read and
-- every state change on a session is now filtered by the caller's profile id.
--
-- The column is nullable and deliberately NOT backfilled. A pre-existing row
-- cannot be attributed to an account with confidence, and the strict predicate
-- in IdentityService requires an exact profile match — so an unattributable
-- legacy row is inaccessible rather than accessible to everyone. That is the
-- correct direction to fail.
--
-- Note this is a targeted fix, not the full multi-tenant story: `jobs`,
-- `applications`, `notifications`, `audit_logs` and `emails` remain globally
-- scoped (see docs/contracts/api.md). Production registration is gated behind
-- an invite code (RegistrationGate) precisely because of that; those tables
-- need the same treatment before registration is opened beyond the owner.

alter table account_sessions add column if not exists owner_profile_id uuid references profiles(id) on delete cascade;

create index if not exists account_sessions_owner_idx on account_sessions(owner_profile_id);
