-- V007__notifications_fanout.sql
-- Feature 8: completes the notifications subsystem.
--
-- V001's `notifications` table (severity/category/title/body/link/read_at)
-- served only the OutboxProcessor's DLQ sweep. This migration extends it in
-- three additive steps so every pipeline event (job matched, CV/cover letter
-- generated, verification, submission, recruiter reply, offer, hard stop,
-- approval required, ...) can fan out through it:
--
--   dedup_key    — idempotency. One notification per business occurrence.
--                  The outbox redelivers after a crash mid-dispatch (by
--                  design: "kill mid-dispatch, restart, zero lost events");
--                  without this key a replayed event produces a second,
--                  identical notification row. Handlers check-and-insert
--                  inside one statement and rely on the partial UNIQUE
--                  index below as the real guarantee (check-then-insert
--                  alone races under concurrent dispatchers).
--   metadata     — event payload correlation (job_id/application_id/
--                  cv_version_id/cover_letter_id/event_id ...). Never
--                  secrets: producers must keep OTPs/tokens out of here
--                  (LogScrubber governs logs; this column is the same rule
--                  for notifications).
--   job_id /     — typed correlation so "which job/application is this
--   application_id about" is queryable without scraping JSON.
--
-- The partial unique index (WHERE dedup_key IS NOT NULL) keeps V001's
-- pre-existing DLQ rows — and any future notification class that genuinely
-- wants to repeat (e.g. a daily digest) — legal without a dedup key.

alter table notifications add column if not exists dedup_key text;
alter table notifications add column if not exists metadata jsonb not null default '{}';
alter table notifications add column if not exists job_id uuid references jobs(id);
alter table notifications add column if not exists application_id uuid references applications(id);

create unique index if not exists notifications_dedup_key_uq
  on notifications(dedup_key)
  where dedup_key is not null;

create index if not exists notifications_created_at_idx
  on notifications(created_at desc);

create index if not exists notifications_job_idx
  on notifications(job_id)
  where job_id is not null;

create index if not exists notifications_application_idx
  on notifications(application_id)
  where application_id is not null;

-- ── job match metadata (Feature 8 producer: JobMatchService) ─────────
-- The "job matched" decision is persisted on the job row itself so the
-- dashboard/score endpoints can read it without a join. Deterministic
-- scoring output; the notification itself carries only the summary.
alter table jobs add column if not exists match_score int;
alter table jobs add column if not exists match_recommendation text
  check (match_recommendation in ('APPLY','REVIEW','SKIP'));
alter table jobs add column if not exists match_breakdown jsonb;
