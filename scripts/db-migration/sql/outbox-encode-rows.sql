-- One chunk of public.outbox_events, encoded, fingerprinted, hashed and
-- compressed ON THE SOURCE SERVER, then sent as ONE binary COPY row:
--   (part, chunk, rows, fingerprint, body_sha256, body_bytes, blob)
-- Run by /dump/plan.sql inside the export session's read-only snapshot.
--
-- psql variables: part (A1 | B1 | B2), chunk, lo and hi (sequence_id
-- bounds for B1), lo_id and hi_id (id bounds for B2), out (the file the
-- COPY row is written to), tkey (the throwaway transit key).
--
--   A1  pending rows of every type except job.discovered   (order: sequence_id)
--   B1  pending job.discovered rows, lo < sequence_id <= hi (order: sequence_id)
--   B2  published rows, lo_id < id <= hi_id                 (order: id)
--
-- fingerprint = sum of the first 60 bits of md5(to_jsonb(row)::text), the
-- per-row fingerprint of sql/table-fingerprints.sql (outbox_events has no
-- generated columns): the chunk fingerprints of a complete export add up to
-- the table fingerprint recorded in the R2 snapshot.
--
-- body = one line per row, fields separated by tabs, robin-outbox-compact-v1
-- (decoded by sql/outbox-decode.sql; every field is ASCII, no tab, no newline):
--   1 sequence_id minus the previous row's (0 before the first row)
--   2 id: a UUIDv7 as (its 48-bit millisecond time minus created_at's
--     millisecond) followed by its last 19 hex digits; any other UUID as
--     '!' and 32 hex digits
--   3 aggregate_type: '' for JOB, else 'x' and the UTF-8 hex
--   4 aggregate_id: its 1-based rank in public.jobs ordered by id (the
--     dictionary of part D), or '!' and 32 hex digits
--   5 event_type: '' for job.discovered, else 'x' and the UTF-8 hex
--   6 schema_version: '' for 1, else the number
--   7 payload: exactly {action, job_id = aggregate_id, source_id}, both
--     strings: the action as I/U/T (INSERTED/UPDATED/TOUCHED) or 'X', its
--     hex and '.', then the source_id as '' (empty), 32 hex digits (a
--     canonical lowercase UUID) or 'x' and its hex; any other payload as 'j'
--     and the hex of its jsonb text
--   8 correlation_id: as field 2
--   9 causation_id: '' for NULL, else as field 2
--  10 created_at in microseconds since 1970 minus the previous row's (0
--     before the first row)
--  11 published_at: '' for NULL, else microseconds after created_at
--  12 attempt_count: '' for 0, else the number
--  13 last_error: '' for NULL, else 'x' and the UTF-8 hex
COPY (
  WITH dict AS MATERIALIZED (
    SELECT j.id, row_number() OVER (ORDER BY j.id) AS r FROM public.jobs j
  ),
  src AS (
    SELECT o.sequence_id, o.aggregate_type, o.event_type, o.schema_version, o.payload,
           o.aggregate_id, o.attempt_count, o.last_error,
           replace(o.id::text, '-', '') AS hid,
           replace(o.correlation_id::text, '-', '') AS hcor,
           replace(o.causation_id::text, '-', '') AS hcau,
           (extract(epoch FROM o.created_at) * 1000000)::bigint AS cus,
           (extract(epoch FROM o.published_at) * 1000000)::bigint AS pus,
           ('x' || substr(md5(to_jsonb(o)::text), 1, 15))::bit(60)::bigint AS fp,
           d.r AS jr,
           o.id AS oid_
      FROM public.outbox_events o
      LEFT JOIN dict d ON d.id = o.aggregate_id
     WHERE (:'part' = 'A1' AND o.published_at IS NULL AND o.event_type <> 'job.discovered')
        OR (:'part' = 'B1' AND o.published_at IS NULL AND o.event_type = 'job.discovered'
            AND o.sequence_id > :lo AND o.sequence_id <= :hi)
        OR (:'part' = 'B2' AND o.published_at IS NOT NULL
            AND o.id > :'lo_id'::uuid AND o.id <= :'hi_id'::uuid)
  ),
  ord AS (
    SELECT s.*,
           row_number() OVER w AS n,
           lag(s.sequence_id, 1, 0::bigint) OVER w AS pseq,
           lag(s.cus, 1, 0::bigint) OVER w AS pcus
      FROM src s
    WINDOW w AS (ORDER BY CASE WHEN :'part' = 'B2' THEN 0::bigint ELSE s.sequence_id END, s.oid_)
  ),
  enc AS (
    SELECT n, fp, concat_ws(E'\t',
      (sequence_id - pseq)::text,
      CASE WHEN substr(hid, 13, 1) = '7'
           THEN (('x' || lpad(left(hid, 12), 16, '0'))::bit(64)::bigint - cus / 1000)::text || substr(hid, 14)
           ELSE '!' || hid END,
      CASE WHEN aggregate_type = 'JOB' THEN '' ELSE 'x' || encode(convert_to(aggregate_type, 'UTF8'), 'hex') END,
      CASE WHEN jr IS NOT NULL THEN jr::text ELSE '!' || replace(aggregate_id::text, '-', '') END,
      CASE WHEN event_type = 'job.discovered' THEN '' ELSE 'x' || encode(convert_to(event_type, 'UTF8'), 'hex') END,
      CASE WHEN schema_version = 1 THEN '' ELSE schema_version::text END,
      CASE WHEN jsonb_typeof(payload -> 'action') = 'string' AND jsonb_typeof(payload -> 'source_id') = 'string'
                AND payload = jsonb_build_object('action', payload ->> 'action', 'job_id', aggregate_id::text,
                                                 'source_id', payload ->> 'source_id')
           THEN CASE payload ->> 'action' WHEN 'INSERTED' THEN 'I' WHEN 'UPDATED' THEN 'U' WHEN 'TOUCHED' THEN 'T'
                     ELSE 'X' || encode(convert_to(payload ->> 'action', 'UTF8'), 'hex') || '.' END
                || CASE WHEN payload ->> 'source_id' = '' THEN ''
                        WHEN payload ->> 'source_id' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                          THEN replace(payload ->> 'source_id', '-', '')
                        ELSE 'x' || encode(convert_to(payload ->> 'source_id', 'UTF8'), 'hex') END
           ELSE 'j' || encode(convert_to(payload::text, 'UTF8'), 'hex') END,
      CASE WHEN substr(hcor, 13, 1) = '7'
           THEN (('x' || lpad(left(hcor, 12), 16, '0'))::bit(64)::bigint - cus / 1000)::text || substr(hcor, 14)
           ELSE '!' || hcor END,
      CASE WHEN hcau IS NULL THEN ''
           WHEN substr(hcau, 13, 1) = '7'
           THEN (('x' || lpad(left(hcau, 12), 16, '0'))::bit(64)::bigint - cus / 1000)::text || substr(hcau, 14)
           ELSE '!' || hcau END,
      (cus - pcus)::text,
      CASE WHEN pus IS NULL THEN '' ELSE (pus - cus)::text END,
      CASE WHEN attempt_count = 0 THEN '' ELSE attempt_count::text END,
      CASE WHEN last_error IS NULL THEN '' ELSE 'x' || encode(convert_to(last_error, 'UTF8'), 'hex') END
    ) AS line
      FROM ord
  ),
  agg AS (
    SELECT count(*) AS rows, coalesce(sum(fp), 0) AS fingerprint,
           convert_to(coalesce(string_agg(line, E'\n' ORDER BY n), ''), 'UTF8') AS body
      FROM enc
  )
  SELECT :'part'::text, :chunk::int, rows, fingerprint, encode(sha256(body), 'hex'), octet_length(body)::bigint,
         extensions.pgp_sym_encrypt_bytea(body, :'tkey', 'compress-algo=2, compress-level=9, cipher-algo=aes256')
    FROM agg
) TO STDOUT WITH (FORMAT binary)
\g :out
