-- Stage A dry run ONLY: synthetic rows generated here, no real data. Run it
-- as the throwaway source's postgres role, after the Flyway migration.
--   psql -v job_count=2500 -v event_count=60000 -f dryrun-source-data.sql
\set ON_ERROR_STOP on

-- Jobs: clone a seed job as the template, so every NOT NULL column added by
-- any migration gets a valid value. Descriptions are about 11 KB each, so
-- they are TOASTed and compressed, as real job descriptions are.
SET robin.job_count = :'job_count';
DO $$
DECLARE
  template_id uuid;
  cols text;
  n integer := current_setting('robin.job_count')::integer;
BEGIN
  SELECT id INTO template_id FROM public.jobs ORDER BY id LIMIT 1;
  IF template_id IS NULL THEN
    RAISE EXCEPTION 'dry run: the migrations left no seed job to use as a template';
  END IF;
  SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) INTO cols
    FROM pg_catalog.pg_attribute a
   WHERE a.attrelid = 'public.jobs'::regclass AND a.attnum > 0
     AND NOT a.attisdropped AND a.attgenerated = '' AND a.attidentity = '';
  EXECUTE format($f$
    INSERT INTO public.jobs (%1$s)
    SELECT %1$s FROM (
      SELECT (jsonb_populate_record(j, jsonb_build_object(
                'id', gen_random_uuid(),
                'external_id', 'dryrun-' || g,
                'dedup_key', md5('dedup-' || g),
                'content_hash', md5('content-' || g),
                'title', 'Synthetic role ' || g,
                'description_text', repeat(md5(g::text) || ' synthetic description ', 200),
                'canonical_url', 'https://jobs.example.invalid/' || g,
                'application_url', 'https://jobs.example.invalid/' || g || '/apply',
                'first_seen_at', timestamptz '2026-11-02 00:00:00+00' + g * interval '1 minute',
                'last_seen_at', timestamptz '2026-11-05 00:00:00+00' + g * interval '1 minute'))).*
        FROM public.jobs j, generate_series(1, %2$s) g
       WHERE j.id = %3$L) s
  $f$, cols, n, template_id);
END
$$;

-- Outbox: half published (with both consumers recorded), half pending, in the
-- production mix of actions; plus the three other pending event types.
WITH ids AS (SELECT array_agg(id ORDER BY id) AS a FROM public.jobs)
INSERT INTO public.outbox_events (id, aggregate_type, aggregate_id, event_type, payload,
                                  correlation_id, created_at, published_at)
SELECT gen_random_uuid(), 'JOB', ids.a[1 + (g % array_length(ids.a, 1))], 'job.discovered',
       jsonb_build_object('job_id', ids.a[1 + (g % array_length(ids.a, 1))]::text,
                          'action', (ARRAY['TOUCHED', 'TOUCHED', 'TOUCHED', 'UPDATED', 'INSERTED'])[1 + (g % 5)],
                          'source_id', ''),
       gen_random_uuid(),
       timestamptz '2026-11-01 00:00:00+00' + g * interval '7 seconds',
       CASE WHEN g <= :event_count / 2
            THEN timestamptz '2026-11-01 00:00:00+00' + g * interval '7 seconds' + interval '3 seconds' END
  FROM generate_series(1, :event_count) g, ids;

INSERT INTO public.outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id, created_at)
SELECT gen_random_uuid(), 'USER', gen_random_uuid(), t.event_type, jsonb_build_object('synthetic', true),
       gen_random_uuid(), timestamptz '2026-11-03 12:00:00+00' + g * interval '1 hour'
  FROM (VALUES ('job.matched', 11), ('verification.completed', 11), ('signup.completed', 1)) AS t (event_type, n),
       generate_series(1, t.n) g;

INSERT INTO public.consumed_events (event_id, consumer, processed_at)
SELECT o.id, c.consumer, o.published_at
  FROM public.outbox_events o
 CROSS JOIN (VALUES ('application-pipeline'), ('notification-fanout')) AS c (consumer)
 WHERE o.published_at IS NOT NULL;
