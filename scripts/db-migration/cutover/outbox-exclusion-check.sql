-- outbox-exclusion-check.sql: read-only state of the Option C exclusion
-- (V052), one "name|value" line per check, from one consistent snapshot.
--
--   sudo -u postgres psql -X -At -v ON_ERROR_STOP=1 -d robin [-v restored_max=1670005] < outbox-exclusion-check.sql
--
-- restored_max, the restored maximum sequence_id, limits the restored_* lines
-- to the restored rows, so that events written since the first start (a
-- sign-in, for example) do not change them. Without it they cover every row.
--
-- Expected on production after outbox-exclusion-apply.sql, before dispatch:
--   active_batches 1, restored_pending 771659 and restored_pending_fingerprint
--   771659:445324708623384356660241 (E1's, unchanged: rows are untouched),
--   excluded_recorded 771636, excluded_still_pending 771636,
--   dispatchable_pending 23 plus any event written since the first start,
--   unrecorded_below_watermark 0, recorded_but_published 0,
--   recorded_out_of_scope 0.
-- Before any exclusion, or after a revert: active_batches 0, watermark 0, and
-- dispatchable_pending counts every pending event below 5 attempts.
\set ON_ERROR_STOP on
\if :{?restored_max}
\else
\set restored_max 9223372036854775807
\endif
-- to_jsonb renders timestamptz in the session's TimeZone: pin it, as every
-- other fingerprinting script does, so the fingerprints compare with E1's.
SET TimeZone = 'UTC';
begin transaction isolation level repeatable read read only;
with b as (select * from outbox_exclusion_batches where reverted_at is null),
     w as (select coalesce(max(b.max_sequence_id), 0) as mark from b),
     x as (select x.* from outbox_exclusions x join b on b.id = x.batch_id)
select 'active_batches', count(*)::text from b
union all select 'active_label', coalesce(max(b.label), '-') from b
union all select 'watermark', (select w.mark from w)::text
union all select 'pending_total', count(*)::text from outbox_events o where o.published_at is null
union all select 'pending_job_discovered', count(*)::text
            from outbox_events o where o.published_at is null and o.event_type = 'job.discovered'
union all select 'excluded_recorded', count(*)::text from x
union all select 'excluded_still_pending', count(*)::text
            from x join outbox_events o on o.id = x.event_id where o.published_at is null
union all select 'dispatchable_pending', count(*)::text
            from outbox_events o, w
           where o.published_at is null and o.attempt_count < 5
             and not (o.event_type = 'job.discovered' and o.sequence_id <= w.mark)
union all select 'dead_letters_not_excluded', count(*)::text
            from outbox_events o, w
           where o.published_at is null and o.attempt_count >= 5
             and not (o.event_type = 'job.discovered' and o.sequence_id <= w.mark)
union all select 'unrecorded_below_watermark', count(*)::text
            from outbox_events o, w
           where o.published_at is null and o.event_type = 'job.discovered' and o.sequence_id <= w.mark
             and not exists (select 1 from x where x.event_id = o.id)
union all select 'recorded_but_published', count(*)::text
            from x join outbox_events o on o.id = x.event_id where o.published_at is not null
union all select 'recorded_out_of_scope', count(*)::text
            from x, w where x.sequence_id > w.mark or x.event_type <> 'job.discovered'
union all select 'pending_fingerprint',
                 count(*) || ':' || coalesce(sum(('x' || substr(md5(to_jsonb(o)::text), 1, 15))::bit(60)::bigint), 0)
            from outbox_events o where o.published_at is null
union all select 'restored_pending', count(*)::text
            from outbox_events o where o.published_at is null and o.sequence_id <= :restored_max
union all select 'restored_pending_fingerprint',
                 count(*) || ':' || coalesce(sum(('x' || substr(md5(to_jsonb(o)::text), 1, 15))::bit(60)::bigint), 0)
            from outbox_events o where o.published_at is null and o.sequence_id <= :restored_max;
commit;
