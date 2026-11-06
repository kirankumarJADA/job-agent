-- Stage C analysis of the pending outbox, run ONLY on the isolated restored
-- copy (heavy queries are fine there, never on the source). Aggregates only:
-- counts and hourly histograms, no row content. Output: key|... lines.
\set ON_ERROR_STOP on
SELECT to_regclass('public.outbox_events') IS NOT NULL AS has_outbox \gset
\if :has_outbox
SELECT 'pending_by_type_action', event_type, coalesce(payload ->> 'action', '-'), count(*)
  FROM public.outbox_events WHERE published_at IS NULL GROUP BY 2, 3 ORDER BY 2, 3;
SELECT 'pending_distinct_aggregates', event_type, count(DISTINCT aggregate_id)
  FROM public.outbox_events WHERE published_at IS NULL GROUP BY 2 ORDER BY 2;
SELECT 'pending_attempts', attempt_count, count(*), count(*) FILTER (WHERE last_error IS NOT NULL)
  FROM public.outbox_events WHERE published_at IS NULL GROUP BY 2 ORDER BY 2;
SELECT 'pending_partially_consumed', count(*)
  FROM public.outbox_events o
 WHERE o.published_at IS NULL
   AND EXISTS (SELECT 1 FROM public.consumed_events c WHERE c.event_id = o.id);
SELECT 'published_consumers_missing', count(*)
  FROM public.outbox_events o
 WHERE o.published_at IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM public.consumed_events c WHERE c.event_id = o.id);
SELECT 'created_per_hour', to_char(date_trunc('hour', created_at), 'YYYY-MM-DD"T"HH24'), count(*)
  FROM public.outbox_events
 WHERE created_at >= (SELECT min(created_at) - interval '12 hours' FROM public.outbox_events WHERE published_at IS NULL)
 GROUP BY 2 ORDER BY 2;
SELECT 'published_per_hour', to_char(date_trunc('hour', published_at), 'YYYY-MM-DD"T"HH24'), count(*)
  FROM public.outbox_events
 WHERE published_at >= (SELECT min(created_at) - interval '12 hours' FROM public.outbox_events WHERE published_at IS NULL)
 GROUP BY 2 ORDER BY 2;
\endif
SELECT to_regclass('public.autonomy_policies') IS NOT NULL AS has_autonomy \gset
\if :has_autonomy
SELECT 'autonomy_policy', action_kind, autonomy_level FROM public.autonomy_policies ORDER BY 2;
\endif
SELECT to_regclass('public.approval_rules') IS NOT NULL AS has_approval_rules \gset
\if :has_approval_rules
SELECT 'approval_rules_rows', count(*) FROM public.approval_rules;
\endif
