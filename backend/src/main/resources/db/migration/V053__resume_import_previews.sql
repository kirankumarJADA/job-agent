-- V053__resume_import_previews.sql
--
-- Master Resume Import (feat/master-resume-import): the two-stage
-- preview/confirm workflow needs a short-lived handle that proves a given
-- "confirm" call follows a "preview" call the SAME user just made, for the
-- SAME profile, without persisting the uploaded resume or anything extracted
-- from it — those stay entirely in the response body / the browser's hands
-- between the two calls (no resume text at rest, no PII in this table).
--
-- A row carries only an opaque token's owner, the profile it was computed
-- against, and its expiry. Confirm claims a row with a single
-- UPDATE ... WHERE consumed_at IS NULL AND expires_at > now(), inside the same
-- transaction that applies the import, so a token can be used at most once
-- even under concurrent confirms, and a confirm that fails and rolls back
-- leaves the token usable. Deleting the profile (data purge) deletes its
-- previews with it.
create table resume_import_previews (
  id           uuid primary key,
  user_id      uuid not null references users(id) on delete cascade,
  profile_id   uuid not null references profiles(id) on delete cascade,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz not null,
  consumed_at  timestamptz
);

create index resume_import_previews_user_idx on resume_import_previews (user_id);
