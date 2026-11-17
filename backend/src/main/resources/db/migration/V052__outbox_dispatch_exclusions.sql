-- V052__outbox_dispatch_exclusions.sql
--
-- Database migration, Option C (conservative backlog). Excludes the
-- historical pending job.discovered events from the migration drain without
-- deleting or changing them, and keeps an auditable record of what was
-- excluded, when, by whom and why.
--
-- * outbox_events is not altered: no row and no column changes. So every
--   row's to_jsonb, and the restore tool's fingerprints of it, stay exactly as
--   restored, before an exclusion, while it is active and after a revert. The
--   only addition on outbox_events is a partial index (below).
-- * outbox_exclusion_batches holds one row per exclusion: label (the
--   idempotency key), reason, actor, time and watermark (the highest excluded
--   sequence_id). A revert stamps it and keeps it.
-- * outbox_exclusions holds one row per excluded event, kept after a revert.
-- * While a batch is active, OutboxProcessor never dispatches, retries or
--   dead-letters a pending job.discovered event at or below the watermark,
--   and so never records it in consumed_events. Every other event, including
--   the user-facing ones below the watermark, is dispatched in sequence order
--   as before. With no active batch, dispatch is exactly as before.
-- * outbox_exclude_pending_discovery() and outbox_revert_discovery_exclusion()
--   are the only writers. Each runs as one transaction: it commits whole or
--   not at all.

create table outbox_exclusion_batches (
  id               uuid primary key,
  label            text not null unique check (length(btrim(label)) > 0),
  event_type       text not null check (event_type = 'job.discovered'),
  max_sequence_id  bigint not null check (max_sequence_id > 0),
  excluded_count   bigint not null check (excluded_count > 0),
  reason           text not null check (length(btrim(reason)) > 0),
  created_by       text not null check (length(btrim(created_by)) > 0),
  created_at       timestamptz not null default now(),
  reverted_at      timestamptz,
  reverted_by      text,
  revert_reason    text,
  check ((reverted_at is null) = (reverted_by is null))
);

-- At most one active batch, so the dispatcher's watermark is unambiguous.
create unique index outbox_exclusion_batches_one_active_uq
  on outbox_exclusion_batches ((true)) where reverted_at is null;

-- No foreign key to outbox_events: restore tooling may truncate and reload
-- it, and the audit row carries its own copy of the event's identity.
create table outbox_exclusions (
  batch_id          uuid not null references outbox_exclusion_batches(id),
  event_id          uuid not null,
  sequence_id       bigint not null,
  event_type        text not null check (event_type = 'job.discovered'),
  aggregate_id      uuid not null,
  event_created_at  timestamptz not null,
  attempt_count     int not null,
  primary key (batch_id, event_id)
);
create index outbox_exclusions_event_idx on outbox_exclusions (event_id);

-- Lets the dispatcher read the few eligible events below an active watermark
-- (the user-facing ones) without walking the excluded rows: about 771,000 in
-- production, which it would otherwise rescan on every 2-second round.
create index outbox_pending_non_discovery_idx on outbox_events (sequence_id)
  where published_at is null and event_type <> 'job.discovered';

-- Excludes every pending job.discovered event, in one transaction.
-- Idempotent: the same label returns the batch already recorded, unchanged.
-- Refuses a second active batch, a reverted label, and an empty backlog.
create or replace function outbox_exclude_pending_discovery(p_label text, p_reason text, p_actor text)
returns table (batch_id uuid, label text, max_sequence_id bigint, excluded_count bigint, created boolean)
language plpgsql
as $$
#variable_conflict use_column
declare
  v_batch outbox_exclusion_batches%rowtype;
  v_active text;
  v_id uuid;
  v_max bigint;
  v_count bigint;
  v_inserted bigint;
begin
  if p_label is null or btrim(p_label) = '' then
    raise exception 'outbox exclusion: a label is required';
  end if;
  if p_reason is null or btrim(p_reason) = '' then
    raise exception 'outbox exclusion: a reason is required';
  end if;
  if p_actor is null or btrim(p_actor) = '' then
    raise exception 'outbox exclusion: an actor is required';
  end if;

  -- Serialises every exclusion and revert: a concurrent call waits here and
  -- then sees what this one committed.
  lock table outbox_exclusion_batches in exclusive mode;

  select b.* into v_batch from outbox_exclusion_batches b where b.label = p_label;
  if found then
    if v_batch.reverted_at is not null then
      raise exception 'outbox exclusion: batch "%" was reverted at %; use a new label',
        p_label, v_batch.reverted_at;
    end if;
    return query select v_batch.id, v_batch.label, v_batch.max_sequence_id, v_batch.excluded_count, false;
    return;
  end if;

  select b.label into v_active from outbox_exclusion_batches b where b.reverted_at is null;
  if v_active is not null then
    raise exception 'outbox exclusion: batch "%" is already active; revert it first', v_active;
  end if;

  -- Waits for every transaction writing outbox_events to end, and holds new
  -- writers until this one commits. Reads (and so the dispatcher's query) are
  -- not blocked. No event can commit below the watermark afterwards: later
  -- events take higher sequence numbers. So "pending job.discovered at or
  -- below the watermark" stays exactly the set recorded here.
  lock table outbox_events in share row exclusive mode;

  select max(o.sequence_id), count(*) into v_max, v_count
    from outbox_events o
   where o.published_at is null and o.event_type = 'job.discovered';
  if v_count = 0 then
    raise exception 'outbox exclusion: there is no pending job.discovered event to exclude';
  end if;

  v_id := gen_random_uuid();
  insert into outbox_exclusion_batches (id, label, event_type, max_sequence_id, excluded_count, reason, created_by)
  values (v_id, p_label, 'job.discovered', v_max, v_count, p_reason, p_actor);

  insert into outbox_exclusions (batch_id, event_id, sequence_id, event_type, aggregate_id, event_created_at,
                                 attempt_count)
  select v_id, o.id, o.sequence_id, o.event_type, o.aggregate_id, o.created_at, o.attempt_count
    from outbox_events o
   where o.published_at is null and o.event_type = 'job.discovered';
  get diagnostics v_inserted = row_count;
  if v_inserted <> v_count then
    raise exception 'outbox exclusion: recorded % events, expected %', v_inserted, v_count;
  end if;

  return query select v_id, p_label, v_max, v_count, true;
end;
$$;

-- Ends a batch: its events are dispatched again, in sequence order, as soon
-- as dispatch runs. The batch and its rows are kept, stamped, for the audit.
-- Meant for before dispatch resumes. Once events have been published after
-- the batch was created, it refuses unless p_force is true. Idempotent: a
-- reverted batch is returned unchanged.
create or replace function outbox_revert_discovery_exclusion(p_label text, p_reason text, p_actor text,
                                                             p_force boolean default false)
returns table (batch_id uuid, label text, excluded_count bigint, still_pending bigint, reverted boolean)
language plpgsql
as $$
#variable_conflict use_column
declare
  v_batch outbox_exclusion_batches%rowtype;
  v_pending bigint;
begin
  if p_label is null or btrim(p_label) = '' then
    raise exception 'outbox exclusion revert: a label is required';
  end if;
  if p_reason is null or btrim(p_reason) = '' then
    raise exception 'outbox exclusion revert: a reason is required';
  end if;
  if p_actor is null or btrim(p_actor) = '' then
    raise exception 'outbox exclusion revert: an actor is required';
  end if;

  lock table outbox_exclusion_batches in exclusive mode;

  select b.* into v_batch from outbox_exclusion_batches b where b.label = p_label;
  if not found then
    raise exception 'outbox exclusion revert: there is no batch "%"', p_label;
  end if;

  select count(*) into v_pending
    from outbox_exclusions x
    join outbox_events o on o.id = x.event_id
   where x.batch_id = v_batch.id and o.published_at is null;

  if v_batch.reverted_at is not null then
    return query select v_batch.id, v_batch.label, v_batch.excluded_count, v_pending, false;
    return;
  end if;

  if not coalesce(p_force, false)
     and exists (select 1 from outbox_events o where o.published_at >= v_batch.created_at) then
    raise exception 'outbox exclusion revert: events were dispatched after batch "%" was created; reverting now puts its events back at the head of the queue. Pass p_force => true to do it anyway', p_label;
  end if;

  update outbox_exclusion_batches b
     set reverted_at = now(), reverted_by = p_actor, revert_reason = p_reason
   where b.id = v_batch.id;

  return query select v_batch.id, v_batch.label, v_batch.excluded_count, v_pending, true;
end;
$$;
