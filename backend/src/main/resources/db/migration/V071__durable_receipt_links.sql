-- V071: durable receipt linking (Stage 3I Phase 2).
--
-- Additive only. V001-V070 are untouched, and V069's submission_receipts stays
-- dedicated to server-verified sandbox receipts: nothing here relaxes its NOT
-- NULL sandbox_registration_id / employer_confirmation_id or reuses sandbox
-- registrations for real employer receipts.
--
-- application_receipt_links
--   One immutable row per correlation decision about one employer receipt
--   (application_receipts, V038) for one application. It records what the
--   receipt was compared against at the time the decision was made:
--     * the browser-observed submission (submission_evidence, V069) and its
--       plan, when Robin submitted the application;
--     * both confirmation references, exactly as originally recorded, and the
--       comparison result (never a replacement of one by the other);
--     * the receipt's own timestamp and where it came from (provider receipt
--       time vs a value the candidate supplied), the submission anchor it was
--       checked against and that anchor's provenance, and the clock-skew
--       allowance applied;
--     * the receipt's channel, provider and fingerprint, and the digest of the
--       submission evidence it was linked to.
--   Rows are append-only. A candidate decision (confirm or reject) appends a
--   new row that supersedes the previous one; the previous row is never
--   rewritten. Exactly one row per receipt is current: a receipt has one root
--   row, and every row can be superseded at most once, so the chain is linear.
--
-- Isolation: every row carries profile_id and application_id; a trigger
-- rejects any row whose receipt, plan, evidence or superseded row belongs to a
-- different application or profile, so a cross-profile link cannot be written
-- even by direct SQL.
--
-- Deletion: a profile or application purge cascades as before. The references
-- to the receipt, plan, evidence and superseded row are NO ACTION (checked at
-- the end of the statement): deleting any of them directly while a link
-- depends on it fails, while a purge that removes everything in one statement
-- still succeeds. A direct DELETE of a link row is refused.
--
-- Historical data: one root row is written for every receipt that already
-- exists, marked V071_BACKFILL / NOT_ASSESSED. The backfill records only facts
-- already stored on the receipt; it does not fabricate a comparison or a
-- temporal decision that was never made.

-- ── 0. Fail closed if the schema this migration builds on is not what it expects.
do $$
declare
    v_missing text[] := array[]::text[];
    v_expected record;
begin
    for v_expected in
        select * from (values
            ('application_receipts', 'id', 'uuid'),
            ('application_receipts', 'profile_id', 'uuid'),
            ('application_receipts', 'application_id', 'uuid'),
            ('application_receipts', 'channel', 'text'),
            ('application_receipts', 'external_reference', 'text'),
            ('application_receipts', 'evidence_time', 'timestamp with time zone'),
            ('application_receipts', 'evidence_time_precision', 'text'),
            ('application_receipts', 'fingerprint', 'text'),
            ('application_receipts', 'verification_status', 'text'),
            ('application_receipts', 'captured_at', 'timestamp with time zone'),
            ('submission_evidence', 'id', 'uuid'),
            ('submission_evidence', 'plan_id', 'uuid'),
            ('submission_evidence', 'application_id', 'uuid'),
            ('submission_evidence', 'profile_id', 'uuid'),
            ('submission_evidence', 'employer_confirmation_id', 'text'),
            ('submission_evidence', 'raw_evidence_sha256', 'character'),
            ('automation_plans', 'id', 'uuid'),
            ('automation_plans', 'application_id', 'uuid'),
            ('automation_plans', 'profile_id', 'uuid'),
            ('applications', 'id', 'uuid'),
            ('applications', 'profile_id', 'uuid'),
            ('profiles', 'id', 'uuid')
        ) as t(table_name, column_name, data_type)
    loop
        if not exists (
            select 1 from information_schema.columns c
             where c.table_schema = current_schema()
               and c.table_name = v_expected.table_name
               and c.column_name = v_expected.column_name
               and c.data_type = v_expected.data_type) then
            v_missing := v_missing || (v_expected.table_name || '.' || v_expected.column_name || ' ' || v_expected.data_type);
        end if;
    end loop;
    if not exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                    where p.proname = 'reject_receipt_record_update' and n.nspname = current_schema()) then
        v_missing := v_missing || 'function reject_receipt_record_update()'::text;
    end if;
    if coalesce(array_length(v_missing, 1), 0) > 0 then
        raise exception 'V071 schema assumptions do not hold; missing or different: %', array_to_string(v_missing, ', ');
    end if;
end $$;

-- ── 1. The link table.
create table application_receipt_links (
    id                       uuid primary key,
    profile_id               uuid not null references profiles(id) on delete cascade,
    application_id           uuid not null references applications(id) on delete cascade,
    receipt_id               uuid not null references application_receipts(id),
    plan_id                  uuid references automation_plans(id),
    submission_evidence_id   uuid references submission_evidence(id),
    supersedes_link_id       uuid references application_receipt_links(id),
    origin                   text not null
        check (origin in ('INGESTION', 'CANDIDATE_REVIEW', 'V071_BACKFILL')),
    decision                 text not null
        check (decision in ('LINKED', 'CANDIDATE_CONFIRMED', 'CANDIDATE_REJECTED', 'NOT_ASSESSED')),
    -- Receipt provenance, copied from the receipt and the ingestion path.
    channel                  text not null
        check (channel in ('MAILBOX_CONNECTOR', 'CANDIDATE_FORWARDED', 'CANDIDATE_ENTERED')),
    provider_id              text,
    receipt_verification     text not null
        check (receipt_verification in ('VERIFIED', 'UNVERIFIED', 'REJECTED', 'UNKNOWN')),
    receipt_fingerprint      text not null,
    -- Integrity: the server digest of the submission evidence this receipt was compared against.
    evidence_sha256          char(64),
    -- Both references exactly as originally recorded; the comparison never replaces either.
    receipt_reference        text,
    submission_reference     text,
    reference_consistency    text not null
        check (reference_consistency in ('ALIGNED', 'CONFLICTING', 'NOT_COMPARABLE', 'NOT_ASSESSED')),
    reference_detail         text,
    -- Temporal plausibility, with the provenance of both timestamps.
    evidence_time            timestamptz,
    evidence_time_precision  text not null
        check (evidence_time_precision in ('EXACT', 'DATE', 'UNKNOWN')),
    evidence_time_source     text not null
        check (evidence_time_source in ('PROVIDER_RECEIPT', 'CANDIDATE_SUPPLIED', 'NONE')),
    anchor_time              timestamptz,
    anchor_precision         text check (anchor_precision in ('EXACT', 'DATE')),
    anchor_source            text not null
        check (anchor_source in ('PRE_SUBMIT_ATTEMPT', 'SUBMIT_APPROVAL', 'CANDIDATE_REPORTED_SUBMISSION', 'NONE')),
    temporal_status          text not null
        check (temporal_status in ('PLAUSIBLE', 'BEFORE_SUBMISSION', 'FUTURE_DATED', 'EVIDENCE_TIME_UNKNOWN',
                                   'NO_SUBMISSION_ANCHOR', 'NOT_ASSESSED')),
    clock_skew_seconds       integer not null check (clock_skew_seconds >= 0),
    policy_version           text not null,
    correlation              jsonb not null default '{}'::jsonb,
    actor                    text not null,
    linked_at                timestamptz not null default clock_timestamp(),
    check (submission_evidence_id is null or plan_id is not null),
    check ((anchor_source = 'NONE') = (anchor_time is null)),
    check ((anchor_time is null) = (anchor_precision is null)),
    check (evidence_time_precision <> 'UNKNOWN' or evidence_time is null),
    check ((evidence_time_source = 'NONE') = (evidence_time is null)),
    -- A backfilled row is a record that a receipt existed before V071, never a decision.
    check ((origin = 'V071_BACKFILL') = (decision = 'NOT_ASSESSED')),
    check (origin <> 'V071_BACKFILL' or (reference_consistency = 'NOT_ASSESSED' and temporal_status = 'NOT_ASSESSED')),
    -- Only a candidate review supersedes an earlier row, and it always does.
    check ((origin = 'CANDIDATE_REVIEW') = (supersedes_link_id is not null)),
    check (origin <> 'CANDIDATE_REVIEW' or decision in ('CANDIDATE_CONFIRMED', 'CANDIDATE_REJECTED')),
    check (origin <> 'INGESTION' or decision = 'LINKED'),
    -- A candidate-supplied channel can never carry a verified receipt (mirrors V038).
    check (channel = 'MAILBOX_CONNECTOR' or receipt_verification <> 'VERIFIED')
);

comment on table application_receipt_links is
    'Stage 3I Phase 2: append-only correlation decisions linking an employer receipt to an application and, when Robin submitted it, to the browser-observed submission evidence. One current row per receipt (linear supersede chain).';

-- Exactly one root per receipt, and each row superseded at most once: one current row per receipt.
create unique index application_receipt_links_root_uq
    on application_receipt_links (receipt_id) where supersedes_link_id is null;
create unique index application_receipt_links_supersedes_uq
    on application_receipt_links (supersedes_link_id) where supersedes_link_id is not null;
create index application_receipt_links_owner_idx
    on application_receipt_links (profile_id, application_id, linked_at);
create index application_receipt_links_evidence_idx
    on application_receipt_links (submission_evidence_id) where submission_evidence_id is not null;
create index application_receipt_links_plan_idx
    on application_receipt_links (plan_id) where plan_id is not null;

-- ── 2. Isolation: every referenced row belongs to the same application and profile.
create or replace function application_receipt_links_assert_consistent()
returns trigger
language plpgsql
as $$
begin
  if not exists (select 1 from applications a
                  where a.id = new.application_id and a.profile_id = new.profile_id) then
    raise exception 'application_receipt_links row must belong to the owner of its application' using errcode = '23514';
  end if;
  if not exists (select 1 from application_receipts r
                  where r.id = new.receipt_id and r.application_id = new.application_id
                    and r.profile_id = new.profile_id) then
    raise exception 'application_receipt_links receipt must belong to the same application and profile' using errcode = '23514';
  end if;
  if new.plan_id is not null and not exists (
      select 1 from automation_plans p
       where p.id = new.plan_id and p.application_id = new.application_id and p.profile_id = new.profile_id) then
    raise exception 'application_receipt_links plan must belong to the same application and profile' using errcode = '23514';
  end if;
  if new.submission_evidence_id is not null and not exists (
      select 1 from submission_evidence e
       where e.id = new.submission_evidence_id and e.plan_id = new.plan_id
         and e.application_id = new.application_id and e.profile_id = new.profile_id) then
    raise exception 'application_receipt_links evidence must belong to the same plan, application and profile' using errcode = '23514';
  end if;
  if new.supersedes_link_id is not null and not exists (
      select 1 from application_receipt_links l
       where l.id = new.supersedes_link_id and l.receipt_id = new.receipt_id
         and l.application_id = new.application_id and l.profile_id = new.profile_id) then
    raise exception 'application_receipt_links can only supersede a row for the same receipt' using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger application_receipt_links_consistent
    before insert on application_receipt_links
    for each row execute function application_receipt_links_assert_consistent();

-- ── 3. Append-only: UPDATE is refused; DELETE only as a cascade from a purge.
create or replace function application_receipt_links_guard()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'UPDATE' then
    raise exception 'application_receipt_links is append-only: UPDATE is forbidden' using errcode = '55000';
  end if;
  if pg_trigger_depth() <= 1 then
    raise exception 'application_receipt_links is append-only: direct DELETE is forbidden' using errcode = '55000';
  end if;
  return old;
end;
$$;

create trigger application_receipt_links_append_only
    before update or delete on application_receipt_links
    for each row execute function application_receipt_links_guard();

-- ── 4. Historical receipts: one NOT_ASSESSED root row each, from stored facts only.
insert into application_receipt_links (
    id, profile_id, application_id, receipt_id, origin, decision, channel, provider_id,
    receipt_verification, receipt_fingerprint, receipt_reference, reference_consistency,
    evidence_time, evidence_time_precision, evidence_time_source, anchor_source, temporal_status,
    clock_skew_seconds, policy_version, actor, linked_at)
select gen_random_uuid(), r.profile_id, r.application_id, r.id, 'V071_BACKFILL', 'NOT_ASSESSED', r.channel, null,
       r.verification_status, r.fingerprint, r.external_reference, 'NOT_ASSESSED',
       r.evidence_time, r.evidence_time_precision,
       case when r.evidence_time is null then 'NONE'
            when r.channel = 'MAILBOX_CONNECTOR' then 'PROVIDER_RECEIPT'
            else 'CANDIDATE_SUPPLIED' end,
       'NONE', 'NOT_ASSESSED', 0, 'v071-backfill', 'SYSTEM', r.captured_at
from application_receipts r
join applications a on a.id = r.application_id and a.profile_id = r.profile_id;
