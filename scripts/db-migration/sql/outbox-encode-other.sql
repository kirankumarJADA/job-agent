-- The two non-row parts of an outbox export, built ON THE SOURCE SERVER in
-- the export session's snapshot and sent as one binary COPY row each, in the
-- same layout as sql/outbox-encode-rows.sql:
--   (part, chunk, rows, check_value, body_sha256, body_bytes, blob)
-- psql variables: part (D | S), out, tkey.
--
--   D  the job dictionary: the id of every public.jobs row, ordered by id,
--      one per line as 32 hex digits. Line k is rank k in field 4 of the
--      compact row encoding. check_value = 0.
--   S  the pending job.discovered summary: one line per job with pending
--      job.discovered events: job (rank, or '!' and 32 hex digits), number of
--      events, first sequence_id, last minus first sequence_id.
--      check_value = the total number of events (it must equal part B1's rows).
\if :{?part}
\else
\echo 'outbox-encode-other.sql needs the part variable'
\quit
\endif
SELECT :'part' = 'D' AS is_dict \gset
\if :is_dict
COPY (
  WITH b AS (
    SELECT count(*) AS rows,
           convert_to(coalesce(string_agg(replace(j.id::text, '-', ''), E'\n' ORDER BY j.id), ''), 'UTF8') AS body
      FROM public.jobs j
  )
  SELECT 'D'::text, 1::int, rows, 0::numeric, encode(sha256(body), 'hex'), octet_length(body)::bigint,
         extensions.pgp_sym_encrypt_bytea(body, :'tkey', 'compress-algo=2, compress-level=9, cipher-algo=aes256')
    FROM b
) TO STDOUT WITH (FORMAT binary)
\g :out
\else
COPY (
  WITH dict AS MATERIALIZED (
    SELECT j.id, row_number() OVER (ORDER BY j.id) AS r FROM public.jobs j
  ),
  g AS (
    SELECT o.aggregate_id, count(*) AS events, min(o.sequence_id) AS first_seq, max(o.sequence_id) AS last_seq
      FROM public.outbox_events o
     WHERE o.published_at IS NULL AND o.event_type = 'job.discovered'
     GROUP BY o.aggregate_id
  ),
  e AS (
    SELECT row_number() OVER (ORDER BY d.r NULLS LAST, g.aggregate_id) AS n, g.events,
           concat_ws(E'\t',
             CASE WHEN d.r IS NOT NULL THEN d.r::text ELSE '!' || replace(g.aggregate_id::text, '-', '') END,
             g.events::text, g.first_seq::text, (g.last_seq - g.first_seq)::text) AS line
      FROM g LEFT JOIN dict d ON d.id = g.aggregate_id
  ),
  b AS (
    SELECT count(*) AS rows, coalesce(sum(events), 0) AS total,
           convert_to(coalesce(string_agg(line, E'\n' ORDER BY n), ''), 'UTF8') AS body
      FROM e
  )
  SELECT 'S'::text, 1::int, rows, total::numeric, encode(sha256(body), 'hex'), octet_length(body)::bigint,
         extensions.pgp_sym_encrypt_bytea(body, :'tkey', 'compress-algo=2, compress-level=9, cipher-algo=aes256')
    FROM b
) TO STDOUT WITH (FORMAT binary)
\g :out
\endif
