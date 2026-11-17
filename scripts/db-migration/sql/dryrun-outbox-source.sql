-- TESTS ONLY: a synthetic outbox source for export-outbox.ps1. No real data.
-- Run as the throwaway source's postgres role after dryrun-source-setup.sql
-- (which provides the roles, pgcrypto in schema extensions and the RLS
-- bootstrap). public.outbox_events is created with production's exact
-- definition (V001 + V004, including outbox_pending_idx).
--
--   psql -v jobs=60960 -v pending=771636 -v published=898345 -v edge=1 -f dryrun-outbox-source.sql
--
-- Ids are UUIDv7 like the application's (UuidV7.generate()): 48-bit
-- millisecond time, version 7, random bits. edge=1 adds rows built to break
-- the compact encoding: non-v7 ids, other aggregate and event types,
-- unusual payloads (extra keys, non-string action, odd source_id, job_id
-- other than the aggregate, control characters, Unicode, big numbers),
-- causation ids, attempt counts, last_error with tabs and newlines, NULL and
-- empty strings, published_at before created_at, far past and future times,
-- sequence gaps and aggregates that are not in public.jobs.
\set ON_ERROR_STOP on
SET TimeZone = 'UTC';

CREATE TABLE public.jobs (id uuid PRIMARY KEY, title text NOT NULL);
CREATE TABLE public.outbox_events (
  sequence_id bigserial,
  id uuid primary key,
  aggregate_type text not null, aggregate_id uuid not null,
  event_type text not null, schema_version int not null default 1,
  payload jsonb not null,
  correlation_id uuid not null, causation_id uuid,
  created_at timestamptz not null default now(),
  published_at timestamptz
);
CREATE INDEX outbox_pending_idx ON public.outbox_events(sequence_id) WHERE published_at IS NULL;
ALTER TABLE public.outbox_events
  ADD COLUMN attempt_count int NOT NULL DEFAULT 0,
  ADD COLUMN last_error text;

CREATE FUNCTION pg_temp.uuid7(ts timestamptz) RETURNS uuid LANGUAGE sql VOLATILE AS $$
  SELECT (lpad(to_hex(floor(extract(epoch FROM ts) * 1000)::bigint), 12, '0')
          || '7' || lpad(to_hex(floor(random() * 4096)::int), 3, '0')
          || to_hex(8 + floor(random() * 4)::int)
          || lpad(to_hex(floor(random() * 4294967296)::bigint), 8, '0')
          || lpad(to_hex(floor(random() * 16777216)::int), 6, '0')
          || to_hex(floor(random() * 16)::int))::uuid
$$;

INSERT INTO public.jobs (id, title)
SELECT pg_temp.uuid7(timestamptz '2026-09-01 00:00:00+00' + g * interval '97 seconds'), 'Synthetic role ' || g
  FROM generate_series(1, :jobs) g;

CREATE TEMP TABLE src AS
SELECT array_agg(pg_temp.uuid7(timestamptz '2026-06-01 00:00:00+00' + g * interval '1 day')::text) AS a
  FROM generate_series(1, 5) g;
CREATE TEMP TABLE jarr AS SELECT array_agg(id ORDER BY id) AS a, count(*)::int AS n FROM public.jobs;

-- Published history: 18 Oct to 2 Nov 18:46, in creation order.
INSERT INTO public.outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id, created_at, published_at)
SELECT pg_temp.uuid7(t.c + interval '1 millisecond' * floor(random() * 3)), 'JOB', t.j, 'job.discovered',
       jsonb_build_object('action', (ARRAY['TOUCHED','TOUCHED','TOUCHED','TOUCHED','TOUCHED','TOUCHED','TOUCHED','UPDATED','UPDATED','INSERTED'])[1 + floor(random() * 10)::int],
                          'job_id', t.j::text, 'source_id', (SELECT a FROM src)[1 + floor(random() * 5)::int]),
       pg_temp.uuid7(t.c + interval '1 millisecond' * floor(random() * 3)),
       t.c, t.c + interval '1 millisecond' * (50 + floor(random() * 4000))
  FROM (SELECT g, timestamptz '2026-10-18 00:00:00+00' + (g * interval '1 second' * (1335600.0 / :published))
                 + interval '1 microsecond' * floor(random() * 999999) AS c,
               (SELECT a FROM jarr)[1 + floor(random() * (SELECT n FROM jarr))::int] AS j
          FROM generate_series(1, :published) g) t
 ORDER BY t.c;

-- A few published events of other types, as in production (about 0.1%).
INSERT INTO public.outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id, created_at, published_at)
SELECT pg_temp.uuid7(c), 'USER', gen_random_uuid(), (ARRAY['verification.completed','signup.completed'])[1 + (g % 2)],
       jsonb_build_object('user_id', gen_random_uuid()::text, 'employer', 'Example ' || g, 'step', g),
       pg_temp.uuid7(c), c, c + interval '2 seconds'
  FROM (SELECT g, timestamptz '2026-10-20 00:00:00+00' + g * interval '17 minutes' AS c
          FROM generate_series(1, greatest(1, :published / 1000)) g) s;

-- The pending backlog: 2 Nov 18:46 to 6 Nov 13:31, never dispatched.
INSERT INTO public.outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id, created_at)
SELECT pg_temp.uuid7(t.c + interval '1 millisecond' * floor(random() * 3)), 'JOB', t.j, 'job.discovered',
       jsonb_build_object('action', (ARRAY['TOUCHED','TOUCHED','TOUCHED','TOUCHED','TOUCHED','TOUCHED','TOUCHED','TOUCHED','UPDATED','INSERTED'])[1 + floor(random() * 10)::int],
                          'job_id', t.j::text, 'source_id', (SELECT a FROM src)[1 + floor(random() * 5)::int]),
       pg_temp.uuid7(t.c + interval '1 millisecond' * floor(random() * 3)), t.c
  FROM (SELECT g, timestamptz '2026-11-02 18:46:02+00' + (g * interval '1 second' * (326700.0 / greatest(:pending, 1)))
                 + interval '1 microsecond' * floor(random() * 999999) AS c,
               (SELECT a FROM jarr)[1 + floor(random() * (SELECT n FROM jarr))::int] AS j
          FROM generate_series(1, :pending) g) t
 ORDER BY t.c;

-- The 23 pending user-facing events of production's mix.
INSERT INTO public.outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id, causation_id, created_at)
SELECT pg_temp.uuid7(c), CASE WHEN e = 'job.matched' THEN 'JOB' ELSE 'USER' END,
       CASE WHEN e = 'job.matched' THEN (SELECT a FROM jarr)[1 + g] ELSE gen_random_uuid() END, e,
       CASE WHEN e = 'job.matched'
            THEN jsonb_build_object('job_id', (SELECT a FROM jarr)[1 + g]::text, 'profile_id', gen_random_uuid()::text,
                                    'score', 70 + g, 'recommendation', 'APPLY', 'job_title', 'Synthetic role ' || g,
                                    'message', 'New job match — Synthetic role ' || g)
            ELSE jsonb_build_object('user_id', gen_random_uuid()::text, 'employer', 'Example ' || g) END,
       pg_temp.uuid7(c), CASE WHEN g % 3 = 0 THEN pg_temp.uuid7(c - interval '1 second') END, c
  FROM (SELECT e, g, timestamptz '2026-11-03 12:00:00+00' + (g * 37 + length(e)) * interval '1 minute' AS c
          FROM (VALUES ('job.matched', 11), ('verification.completed', 11), ('signup.completed', 1)) AS t (e, n),
               generate_series(1, t.n) g) s;

\if :edge
-- Rows built to break the encoding. Half pending, half published.
INSERT INTO public.outbox_events (sequence_id, id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                                  correlation_id, causation_id, created_at, published_at, attempt_count, last_error)
SELECT 900000000 + g * 1000003,
       CASE WHEN g % 4 = 0 THEN gen_random_uuid() ELSE pg_temp.uuid7(c) END,
       (ARRAY['JOB','APPLICATION','USER','Agg	tab','ÄGGRÉGATE ✓',''])[1 + g % 6],
       CASE WHEN g % 5 = 0 THEN gen_random_uuid() ELSE (SELECT a FROM jarr)[1 + g] END,
       (ARRAY['job.discovered','job.discovered','job.matched','résumé.generated ✓','line
break',''])[1 + g % 6],
       (ARRAY[1, 2, 0, -1, 2147483647])[1 + g % 5],
       CASE g % 12
         WHEN 0 THEN jsonb_build_object('action', 'TOUCHED', 'job_id', (SELECT a FROM jarr)[1 + g]::text, 'source_id', (SELECT a FROM src)[1], 'extra', 1)
         WHEN 1 THEN jsonb_build_object('action', 5, 'job_id', (SELECT a FROM jarr)[1 + g]::text, 'source_id', (SELECT a FROM src)[1])
         WHEN 2 THEN jsonb_build_object('action', 'REPOSTED', 'job_id', (SELECT a FROM jarr)[1 + g]::text, 'source_id', upper((SELECT a FROM src)[2]))
         WHEN 3 THEN jsonb_build_object('action', 'INSERTED', 'job_id', (SELECT a FROM jarr)[1 + g]::text, 'source_id', 'not-a-uuid	with tab')
         WHEN 4 THEN jsonb_build_object('action', 'UPDATED', 'job_id', (SELECT a FROM jarr)[1 + g]::text, 'source_id', '')
         WHEN 5 THEN jsonb_build_object('action', 'TOUCHED', 'job_id', gen_random_uuid()::text, 'source_id', (SELECT a FROM src)[3])
         WHEN 6 THEN jsonb_build_object('text', E'tab\there\nnewline \\ backslash "quote" é ✓ 😀 \u0001', 'n', 1e-7, 'big', 123456789012345678901234567890.123456789)
         WHEN 7 THEN '[]'::jsonb
         WHEN 8 THEN '"just a string"'::jsonb
         WHEN 9 THEN jsonb_build_object('action', '', 'job_id', (SELECT a FROM jarr)[1 + g]::text, 'source_id', (SELECT a FROM src)[4])
         WHEN 10 THEN jsonb_build_object('action', 'TOUCHED', 'job_id', (SELECT a FROM jarr)[1 + g]::text)
         ELSE jsonb_build_object('action', 'TOUCHED', 'job_id', (SELECT a FROM jarr)[1 + g]::text, 'source_id', (SELECT a FROM src)[5])
       END,
       CASE WHEN g % 3 = 0 THEN gen_random_uuid() ELSE pg_temp.uuid7(c + interval '5 seconds') END,
       CASE g % 4 WHEN 0 THEN NULL WHEN 1 THEN pg_temp.uuid7(c - interval '1 hour') WHEN 2 THEN gen_random_uuid() ELSE pg_temp.uuid7(c) END,
       c,
       CASE WHEN g % 2 = 0 THEN NULL WHEN g % 6 = 1 THEN c - interval '3 days 0.000001 seconds' ELSE c + interval '0.000001 seconds' END,
       (ARRAY[0, 3, 0, 5, 2147483647])[1 + g % 5],
       CASE g % 5 WHEN 0 THEN NULL WHEN 1 THEN '' WHEN 2 THEN E'error\twith\ttabs\nand\r\nnewlines \\ ✓' WHEN 3 THEN repeat('x', 3000) ELSE 'plain' END
  FROM (SELECT g, (ARRAY[timestamptz '1999-12-31 23:59:59.999999+00', timestamptz '2099-06-30 12:00:00.000001+00',
                         timestamptz '2026-11-04 10:00:00.5+00', timestamptz '1970-01-01 00:00:00.000001+00'])[1 + g % 4]
                   + g * interval '1.000001 seconds' AS c
          FROM generate_series(1, 48) g) s;
-- Keep the sequence ahead of the explicit sequence_id values above.
SELECT setval('public.outbox_events_sequence_id_seq', 2000000000);
INSERT INTO public.outbox_events (id, aggregate_type, aggregate_id, event_type, payload, correlation_id, created_at)
SELECT pg_temp.uuid7(now()), 'JOB', (SELECT a FROM jarr)[1], 'job.discovered',
       jsonb_build_object('action', 'TOUCHED', 'job_id', (SELECT a FROM jarr)[1]::text, 'source_id', (SELECT a FROM src)[1]),
       pg_temp.uuid7(now()), now();
\endif

ANALYZE public.jobs;
ANALYZE public.outbox_events;
SELECT 'synthetic outbox: ' || count(*) || ' rows, ' || count(*) FILTER (WHERE published_at IS NULL) || ' pending'
  FROM public.outbox_events;
