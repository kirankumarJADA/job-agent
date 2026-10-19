-- Phase 8.3 (TRACK): evidence-backed application timeline, submission receipts
-- and recruiter-email association review.
--
-- Additive only. No existing column changes meaning, no application status is
-- promoted, and no legacy record is marked as verified evidence: legacy rows
-- receive the conservative defaults below (INTERNAL timeline rows, candidate-
-- submitted email channel, PENDING_REVIEW for every pre-8.3 email correlation).

-- ── 1. application_events: the existing per-application timeline ─────────────
-- Gains the fields TRACK needs to tell internal Robin activity apart from
-- employer evidence, inferred interpretations and candidate reports. Existing
-- writers keep inserting (id, application_id, type, payload, actor); the
-- defaults classify those rows as INTERNAL Robin records, which is what they
-- are, and the trigger below fills the owner from the application.
alter table application_events add column if not exists profile_id uuid references profiles(id) on delete cascade;
alter table application_events add column if not exists event_key text;
alter table application_events add column if not exists classification text not null default 'INTERNAL';
alter table application_events add column if not exists verification_status text not null default 'NOT_APPLICABLE';
-- Actual time of the underlying event when known. NULL for internal rows means
-- "the moment Robin recorded it" (occurred_at); for external evidence NULL with
-- precision UNKNOWN means the time is genuinely unknown and is never invented.
alter table application_events add column if not exists event_time timestamptz;
alter table application_events add column if not exists event_time_precision text not null default 'EXACT';
alter table application_events add column if not exists summary text;
alter table application_events add column if not exists external_id text;
alter table application_events add column if not exists email_id uuid references emails(id) on delete cascade;
alter table application_events add column if not exists receipt_id uuid;
alter table application_events add column if not exists supersedes_event_id uuid references application_events(id) on delete cascade;
alter table application_events add column if not exists provenance jsonb not null default '{}';

alter table application_events drop constraint if exists application_events_classification_check;
alter table application_events add constraint application_events_classification_check
    check (classification in ('INTERNAL', 'EXTERNAL', 'INFERRED', 'CANDIDATE_REPORTED'));
alter table application_events drop constraint if exists application_events_verification_check;
alter table application_events add constraint application_events_verification_check
    check (verification_status in ('NOT_APPLICABLE', 'VERIFIED', 'UNVERIFIED', 'REJECTED', 'UNKNOWN'));
alter table application_events drop constraint if exists application_events_time_precision_check;
alter table application_events add constraint application_events_time_precision_check
    check (event_time_precision in ('EXACT', 'DATE', 'UNKNOWN')
           and (event_time_precision <> 'UNKNOWN' or event_time is null));
-- A Robin-internal record can never claim external verification, and only
-- employer evidence can be VERIFIED.
alter table application_events drop constraint if exists application_events_verified_is_external_check;
alter table application_events add constraint application_events_verified_is_external_check
    check (verification_status <> 'VERIFIED' or classification = 'EXTERNAL');

-- Owner backfill for existing rows (the application row is the owner of record).
update application_events ae
set profile_id = a.profile_id
from applications a
where a.id = ae.application_id
  and ae.profile_id is null
  and a.profile_id is not null;

-- STATUS_CHANGED rows were written by exactly two callers: the owner's own
-- transition API (a candidate statement) and the pre-8.3 email keyword
-- classifier (event key 'email:<id>', an inference). Neither is verified
-- evidence; the classification records which one each row was.
update application_events
set classification = case when coalesce(payload->>'event_key', '') like 'email:%' then 'INFERRED' else 'CANDIDATE_REPORTED' end,
    verification_status = case when coalesce(payload->>'event_key', '') like 'email:%' then 'UNVERIFIED' else 'NOT_APPLICABLE' end
where type = 'STATUS_CHANGED'
  and classification = 'INTERNAL';

-- Idempotency for every TRACK write: one row per (application, event key).
create unique index if not exists application_events_event_key_uq
    on application_events(application_id, event_key) where event_key is not null;
create index if not exists application_events_profile_idx
    on application_events(profile_id, application_id, occurred_at);
create index if not exists application_events_timeline_idx
    on application_events(application_id, (coalesce(event_time, occurred_at)), id);

-- ── 2. application_receipts: submission / acknowledgement evidence ────────────
create table if not exists application_receipts (
    id                      uuid primary key,
    profile_id              uuid not null references profiles(id) on delete cascade,
    application_id          uuid not null references applications(id) on delete cascade,
    evidence_type           text not null
        check (evidence_type in ('EMPLOYER_ACKNOWLEDGEMENT_EMAIL', 'CANDIDATE_STATEMENT')),
    -- How the evidence reached Robin. Assigned by the server from the ingestion
    -- path, never taken from a request body.
    channel                 text not null
        check (channel in ('MAILBOX_CONNECTOR', 'CANDIDATE_FORWARDED', 'CANDIDATE_ENTERED')),
    email_id                uuid references emails(id) on delete set null,
    provider_message_id     text,
    -- A reference/confirmation number quoted by the evidence itself; NULL when
    -- the evidence carries none. Never generated.
    external_reference      text,
    sender_domain           text,
    evidence_time           timestamptz,
    evidence_time_precision text not null check (evidence_time_precision in ('EXACT', 'DATE', 'UNKNOWN')),
    captured_at             timestamptz not null default now(),
    fingerprint             text not null,
    verification_status     text not null
        check (verification_status in ('VERIFIED', 'UNVERIFIED', 'REJECTED', 'UNKNOWN')),
    verification_reasons    jsonb not null default '[]',
    policy_version          text not null,
    summary                 text not null,
    identity_evidence       jsonb not null default '[]',
    candidate_review        text check (candidate_review in ('CONFIRMED', 'REJECTED')),
    candidate_reviewed_at   timestamptz,
    candidate_review_note   text,
    review_version          int not null default 0,
    unique (application_id, fingerprint),
    check (evidence_time_precision <> 'UNKNOWN' or evidence_time is null),
    -- Defence in depth for the verification policy: a candidate statement can
    -- never be VERIFIED, and only an authenticated mailbox channel can verify.
    check (evidence_type <> 'CANDIDATE_STATEMENT' or verification_status <> 'VERIFIED'),
    check (channel = 'MAILBOX_CONNECTOR' or verification_status <> 'VERIFIED')
);
create index if not exists application_receipts_owner_idx
    on application_receipts(profile_id, application_id, captured_at);

alter table application_events drop constraint if exists application_events_receipt_fk;
alter table application_events add constraint application_events_receipt_fk
    foreign key (receipt_id) references application_receipts(id) on delete cascade;

-- ── 3. emails: channel, minimisation and content de-duplication ───────────────
-- Every pre-8.3 row arrived through the authenticated /api/v1/emails/ingest
-- endpoint, i.e. it was supplied by the candidate: CANDIDATE_FORWARDED.
alter table emails add column if not exists channel text not null default 'CANDIDATE_FORWARDED';
alter table emails drop constraint if exists emails_channel_check;
alter table emails add constraint emails_channel_check
    check (channel in ('MAILBOX_CONNECTOR', 'CANDIDATE_FORWARDED'));
alter table emails add column if not exists content_fingerprint text;
create unique index if not exists emails_profile_fingerprint_uq
    on emails(profile_id, content_fingerprint) where content_fingerprint is not null;
-- false when only a minimal excerpt is kept (Phase 8.3 default); pre-8.3 rows
-- kept the full body.
alter table emails add column if not exists body_retained boolean not null default true;
alter table emails add column if not exists processing_version text;
-- Provider-reported sender authentication (DKIM/SPF/DMARC). Only an
-- authenticated mailbox connector can populate it.
alter table emails add column if not exists sender_authentication jsonb;
-- received_at is NOT NULL; when a message arrives without a received time the
-- ingestion time is stored there (pre-8.3 behaviour) and this flag records
-- that the real time is unknown, so TRACK never presents it as the event time.
alter table emails add column if not exists received_time_known boolean not null default true;

-- ── 4. email_application_links: proposed association + candidate review ──────
create table if not exists email_application_links (
    id                   uuid primary key,
    profile_id           uuid not null references profiles(id) on delete cascade,
    email_id             uuid not null unique references emails(id) on delete cascade,
    application_id       uuid references applications(id) on delete set null,
    status               text not null check (status in
        ('LINKED', 'CONFIRMED', 'PENDING_REVIEW', 'UNMATCHED', 'REJECTED', 'CONFLICT', 'NOT_APPLICABLE')),
    match_strength       text not null check (match_strength in ('STRONG', 'WEAK', 'NONE', 'CANDIDATE', 'LEGACY')),
    rationale            jsonb not null default '[]',
    candidates           jsonb not null default '[]',
    inferred_category    text not null,
    inferred_confidence  numeric(3,2) not null,
    -- The message explicitly says the application was received (receipt policy input).
    explicit_receipt     boolean not null default false,
    -- First labelled application/confirmation reference quoted by the message, if any.
    quoted_reference     text,
    confirmed_category   text,
    evidence_excerpt     text,
    processing_version   text not null,
    decided_at           timestamptz,
    decision_version     int not null default 0,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);
create index if not exists email_application_links_owner_idx
    on email_application_links(profile_id, status, created_at desc);
create index if not exists email_application_links_application_idx
    on email_application_links(application_id);

-- Pre-8.3 correlations were made by matching a company name (or an id) in the
-- message text, which is weak evidence. Each one becomes a PENDING_REVIEW
-- proposal for the candidate; none is treated as established.
insert into email_application_links (id, profile_id, email_id, application_id, status, match_strength,
                                     rationale, candidates, inferred_category, inferred_confidence,
                                     processing_version)
select gen_random_uuid(), e.profile_id, e.id, null, 'PENDING_REVIEW', 'LEGACY',
       jsonb_build_array(jsonb_build_object(
           'code', 'LEGACY_CORRELATION',
           'message', 'Linked before Phase 8.3 by matching the company name in the message; needs your review.')),
       jsonb_build_array(jsonb_build_object('applicationId', e.application_id::text,
                                            'signals', jsonb_build_array('LEGACY_COMPANY_NAME'))),
       case e.classification
           when 'CONFIRMATION' then 'APPLICATION_ACKNOWLEDGEMENT'
           when 'INTERVIEW_INVITE' then 'INTERVIEW_INVITATION'
           when 'ASSESSMENT_INVITE' then 'ASSESSMENT_INVITATION'
           when 'REJECTION' then 'REJECTION'
           when 'OFFER' then 'OFFER'
           when 'RECRUITER_OUTREACH' then 'RECRUITER_FOLLOW_UP'
           else 'UNRELATED_OR_AMBIGUOUS'
       end,
       -- The stored 0.95 was a constant, not a measured confidence.
       0.30,
       'legacy-keyword-v0'
from emails e
join applications a on a.id = e.application_id and a.profile_id = e.profile_id
where e.application_id is not null
  and e.profile_id is not null
on conflict (email_id) do nothing;

-- ── 5. Ownership and append-only guards ───────────────────────────────────────
create or replace function application_events_guard()
returns trigger
language plpgsql
as $$
declare
  owner uuid;
begin
  if tg_op = 'INSERT' then
    select a.profile_id into owner from applications a where a.id = new.application_id;
    if new.profile_id is null then
      new.profile_id := owner;
    elsif owner is distinct from new.profile_id then
      raise exception 'application_events row must belong to the owner of its application'
        using errcode = '23514';
    end if;
    -- The status-transition writer predates TRACK and does not classify its
    -- rows; classify them here exactly as the backfill above does.
    if new.type = 'STATUS_CHANGED' and new.classification = 'INTERNAL' then
      if coalesce(new.payload->>'event_key', '') like 'email:%' then
        new.classification := 'INFERRED';
        new.verification_status := 'UNVERIFIED';
      else
        new.classification := 'CANDIDATE_REPORTED';
        new.verification_status := 'NOT_APPLICABLE';
      end if;
    end if;
    return new;
  end if;
  if tg_op = 'UPDATE' then
    raise exception 'application_events is append-only: UPDATE is forbidden' using errcode = '55000';
  end if;
  -- DELETE: only as a consequence of deleting the parent application, owner
  -- or source message (foreign-key cascade, i.e. nested inside the RI trigger).
  if pg_trigger_depth() <= 1 then
    raise exception 'application_events is append-only: direct DELETE is forbidden' using errcode = '55000';
  end if;
  return old;
end;
$$;

drop trigger if exists application_events_guard_insert on application_events;
create trigger application_events_guard_insert before insert on application_events
    for each row execute function application_events_guard();
drop trigger if exists application_events_guard_mutation on application_events;
create trigger application_events_guard_mutation before update or delete on application_events
    for each row execute function application_events_guard();

create or replace function track_assert_owner()
returns trigger
language plpgsql
as $$
declare
  check_message boolean := false;
begin
  if new.application_id is not null and not exists (
      select 1 from applications a where a.id = new.application_id and a.profile_id = new.profile_id) then
    raise exception '% row must belong to the owner of its application', tg_table_name using errcode = '23514';
  end if;
  -- The message check runs when the link is created or re-pointed, not when a
  -- deleted application merely clears application_id (ON DELETE SET NULL).
  if tg_table_name = 'email_application_links' then
    if tg_op = 'INSERT' then
      check_message := true;
    elsif new.email_id is distinct from old.email_id or new.profile_id is distinct from old.profile_id then
      check_message := true;
    end if;
    if check_message and not exists (
        select 1 from emails e where e.id = new.email_id and e.profile_id = new.profile_id) then
      raise exception 'email_application_links row must belong to the owner of its email' using errcode = '23514';
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists application_receipts_owner on application_receipts;
create trigger application_receipts_owner before insert or update of application_id, profile_id
    on application_receipts for each row execute function track_assert_owner();
drop trigger if exists email_application_links_owner on email_application_links;
create trigger email_application_links_owner before insert or update of application_id, profile_id, email_id
    on email_application_links for each row execute function track_assert_owner();

-- ── 6. Read path for the timeline's projection of append-only audit records ───
-- (CV reviews, cover-letter approvals, package creation/approval). Index only;
-- audit rows are never modified.
create index if not exists audit_logs_entity_idx on audit_logs(entity_id, created_at) where entity_id is not null;

comment on column application_events.classification is
    'INTERNAL = Robin record; EXTERNAL = employer evidence; INFERRED = Robin interpretation awaiting confirmation; CANDIDATE_REPORTED = candidate statement or confirmation';
comment on table application_receipts is
    'Phase 8.3 submission/acknowledgement evidence; verification per docs/PHASE8_FIND_WORKFLOW.md section 13';
