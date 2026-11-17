-- LOCAL ONLY: run by container/export-outbox.sh against the throwaway server
-- inside the export container (RAM-backed data directory), never against
-- the source. Creates the tables that receive an outbox export and the
-- functions that decrypt and decode robin-outbox-compact-v1 chunks (see
-- sql/outbox-encode-rows.sql for the format).
--
-- public.outbox_events has exactly the 13 columns the source has (the export
-- refuses any other shape before reading a row), with the source's NOT NULL
-- columns NOT NULL and id as primary key, so a decoding error, a duplicated
-- row or a missing dictionary entry fails loudly instead of loading.
\set ON_ERROR_STOP on
CREATE SCHEMA robin_export;
CREATE EXTENSION pgcrypto WITH SCHEMA robin_export;

CREATE TABLE public.outbox_events (
  sequence_id    bigint NOT NULL,
  id             uuid NOT NULL PRIMARY KEY,
  aggregate_type text NOT NULL,
  aggregate_id   uuid NOT NULL,
  event_type     text NOT NULL,
  schema_version integer NOT NULL,
  payload        jsonb NOT NULL,
  correlation_id uuid NOT NULL,
  causation_id   uuid,
  created_at     timestamptz NOT NULL,
  published_at   timestamptz,
  attempt_count  integer NOT NULL,
  last_error     text
);

-- What arrived: one row per chunk file, exactly as the source sent it.
CREATE TABLE robin_export.chunk_blob (
  part text NOT NULL, chunk integer NOT NULL, rows bigint NOT NULL, check_value numeric NOT NULL,
  body_sha256 text NOT NULL, body_bytes bigint NOT NULL, blob bytea NOT NULL,
  PRIMARY KEY (part, chunk)
);
-- What the source said about each chunk next to what decoding produced.
CREATE TABLE robin_export.chunk_check (
  part text NOT NULL, chunk integer NOT NULL,
  server_rows bigint NOT NULL, server_check numeric NOT NULL, server_sha256 text NOT NULL, server_body_bytes bigint NOT NULL,
  blob_bytes bigint NOT NULL, body_bytes bigint NOT NULL, body_sha256 text NOT NULL,
  decoded_rows bigint NOT NULL, decoded_check numeric NOT NULL,
  PRIMARY KEY (part, chunk)
);
CREATE TABLE robin_export.job_dict (r integer PRIMARY KEY, id uuid NOT NULL UNIQUE);
CREATE TABLE robin_export.pending_job_summary (
  job_id uuid PRIMARY KEY,
  events bigint NOT NULL,
  first_sequence_id bigint NOT NULL,
  last_sequence_id bigint NOT NULL
);
COMMENT ON TABLE robin_export.pending_job_summary IS
  'Every job with pending job.discovered events in the export snapshot: how many, and the first and last sequence_id.';

-- Field 2/8/9 of the compact encoding back to a UUID.
CREATE FUNCTION robin_export.uuid7_decode(f text, created_us bigint) RETURNS uuid
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT CASE WHEN left(f, 1) = '!' THEN substr(f, 2)::uuid
              ELSE (lpad(to_hex(created_us / 1000 + left(f, length(f) - 19)::bigint), 12, '0')
                    || '7' || right(f, 19))::uuid END
$$;

-- Microseconds since 1970 back to a timestamptz, in integer arithmetic only
-- (a time-only interval, so the result does not depend on TimeZone).
CREATE FUNCTION robin_export.us_to_ts(us bigint) RETURNS timestamptz
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT timestamptz '1970-01-01 00:00:00+00' + (us::text || ' microseconds')::interval
$$;

-- Field 7 back to the jsonb payload.
CREATE FUNCTION robin_export.payload_decode(p text, job uuid) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
  c text := left(p, 1);
  action text;
  rest text;
  dot integer;
  source text;
BEGIN
  IF c = 'j' THEN
    RETURN convert_from(decode(substr(p, 2), 'hex'), 'UTF8')::jsonb;
  ELSIF c = 'I' THEN action := 'INSERTED'; rest := substr(p, 2);
  ELSIF c = 'U' THEN action := 'UPDATED';  rest := substr(p, 2);
  ELSIF c = 'T' THEN action := 'TOUCHED';  rest := substr(p, 2);
  ELSIF c = 'X' THEN
    dot := strpos(p, '.');
    IF dot < 2 THEN RAISE EXCEPTION 'payload field: unterminated action'; END IF;
    action := convert_from(decode(substr(p, 2, dot - 2), 'hex'), 'UTF8');
    rest := substr(p, dot + 1);
  ELSE
    RAISE EXCEPTION 'payload field: unknown form %', c;
  END IF;
  IF rest = '' THEN source := '';
  ELSIF left(rest, 1) = 'x' THEN source := convert_from(decode(substr(rest, 2), 'hex'), 'UTF8');
  ELSIF rest ~ '^[0-9a-f]{32}$' THEN source := rest::uuid::text;
  ELSE RAISE EXCEPTION 'payload field: bad source_id';
  END IF;
  RETURN jsonb_build_object('action', action, 'job_id', job::text, 'source_id', source);
END
$$;

-- Decrypts one received chunk, checks its body hash, decodes it into its
-- table and records the source's figures next to the decoded ones. The
-- settings are those of sql/table-fingerprints.sql, so decoded_check is the
-- fingerprint of the rows as stored here, comparable with the source's.
CREATE FUNCTION robin_export.decode_chunk(p_part text, p_chunk integer, p_key text)
RETURNS robin_export.chunk_check
LANGUAGE plpgsql
SET TimeZone = 'UTC' SET extra_float_digits = 1 SET bytea_output = 'hex'
SET IntervalStyle = 'postgres' SET DateStyle = 'ISO, MDY'
AS $$
DECLARE
  b robin_export.chunk_blob%ROWTYPE;
  body bytea;
  r robin_export.chunk_check;
  bad bigint;
BEGIN
  SELECT * INTO STRICT b FROM robin_export.chunk_blob WHERE part = p_part AND chunk = p_chunk;
  body := robin_export.pgp_sym_decrypt_bytea(b.blob, p_key);
  r.part := p_part; r.chunk := p_chunk;
  r.server_rows := b.rows; r.server_check := b.check_value; r.server_sha256 := b.body_sha256;
  r.server_body_bytes := b.body_bytes; r.blob_bytes := octet_length(b.blob);
  r.body_bytes := octet_length(body); r.body_sha256 := encode(sha256(body), 'hex');
  IF r.body_sha256 <> b.body_sha256 THEN
    RAISE EXCEPTION 'chunk % %: body SHA-256 % differs from the source''s %', p_part, p_chunk, r.body_sha256, b.body_sha256;
  END IF;

  IF p_part = 'D' THEN
    INSERT INTO robin_export.job_dict (r, id)
    SELECT t.n, t.line::uuid
      FROM string_to_table(convert_from(body, 'UTF8'), E'\n') WITH ORDINALITY AS t (line, n)
     WHERE octet_length(body) > 0;
    GET DIAGNOSTICS r.decoded_rows = ROW_COUNT;
    r.decoded_check := 0;

  ELSIF p_part = 'S' THEN
    WITH l AS (
      SELECT string_to_array(t.line, E'\t') AS f
        FROM string_to_table(convert_from(body, 'UTF8'), E'\n') AS t (line)
       WHERE octet_length(body) > 0
    ), k AS (
      SELECT f, CASE WHEN left(f[1], 1) = '!' THEN NULL ELSE f[1]::integer END AS jr FROM l
    ), ins AS (
      INSERT INTO robin_export.pending_job_summary (job_id, events, first_sequence_id, last_sequence_id)
      SELECT CASE WHEN k.jr IS NULL THEN substr(k.f[1], 2)::uuid ELSE d.id END,
             k.f[2]::bigint, k.f[3]::bigint, k.f[3]::bigint + k.f[4]::bigint
        FROM k LEFT JOIN robin_export.job_dict d ON d.r = k.jr
      RETURNING events
    )
    SELECT count(*), coalesce(sum(events), 0) INTO r.decoded_rows, r.decoded_check FROM ins;

  ELSIF p_part IN ('A1', 'B1', 'B2') THEN
    SELECT count(*) INTO bad
      FROM string_to_table(convert_from(body, 'UTF8'), E'\n') AS t (line)
     WHERE octet_length(body) > 0 AND cardinality(string_to_array(t.line, E'\t')) <> 13;
    IF bad > 0 THEN RAISE EXCEPTION 'chunk % %: % line(s) do not have 13 fields', p_part, p_chunk, bad; END IF;
    WITH lines AS (
      SELECT t.n, string_to_array(t.line, E'\t') AS f
        FROM string_to_table(convert_from(body, 'UTF8'), E'\n') WITH ORDINALITY AS t (line, n)
       WHERE octet_length(body) > 0
    ), base AS (
      SELECT n, f,
             (sum(f[1]::bigint) OVER (ORDER BY n))::bigint AS sequence_id,
             (sum(f[10]::bigint) OVER (ORDER BY n))::bigint AS cus,
             CASE WHEN left(f[4], 1) = '!' THEN NULL ELSE f[4]::integer END AS jr
        FROM lines
    ), dec AS (
      SELECT x.n, x.sequence_id,
             robin_export.uuid7_decode(x.f[2], x.cus) AS id,
             CASE WHEN x.f[3] = '' THEN 'JOB' ELSE convert_from(decode(substr(x.f[3], 2), 'hex'), 'UTF8') END AS aggregate_type,
             CASE WHEN x.jr IS NULL THEN substr(x.f[4], 2)::uuid ELSE d.id END AS aggregate_id,
             CASE WHEN x.f[5] = '' THEN 'job.discovered' ELSE convert_from(decode(substr(x.f[5], 2), 'hex'), 'UTF8') END AS event_type,
             CASE WHEN x.f[6] = '' THEN 1 ELSE x.f[6]::integer END AS schema_version,
             x.f[7] AS penc,
             robin_export.uuid7_decode(x.f[8], x.cus) AS correlation_id,
             CASE WHEN x.f[9] = '' THEN NULL ELSE robin_export.uuid7_decode(x.f[9], x.cus) END AS causation_id,
             robin_export.us_to_ts(x.cus) AS created_at,
             CASE WHEN x.f[11] = '' THEN NULL ELSE robin_export.us_to_ts(x.cus + x.f[11]::bigint) END AS published_at,
             CASE WHEN x.f[12] = '' THEN 0 ELSE x.f[12]::integer END AS attempt_count,
             CASE WHEN x.f[13] = '' THEN NULL ELSE convert_from(decode(substr(x.f[13], 2), 'hex'), 'UTF8') END AS last_error
        FROM base x LEFT JOIN robin_export.job_dict d ON d.r = x.jr
    ), ins AS (
      INSERT INTO public.outbox_events (sequence_id, id, aggregate_type, aggregate_id, event_type, schema_version,
                                        payload, correlation_id, causation_id, created_at, published_at,
                                        attempt_count, last_error)
      SELECT sequence_id, id, aggregate_type, aggregate_id, event_type, schema_version,
             robin_export.payload_decode(penc, aggregate_id), correlation_id, causation_id, created_at,
             published_at, attempt_count, last_error
        FROM dec ORDER BY n
      RETURNING *
    )
    SELECT count(*), coalesce(sum(('x' || substr(md5(to_jsonb(ins)::text), 1, 15))::bit(60)::bigint), 0)
      INTO r.decoded_rows, r.decoded_check
      FROM ins;
  ELSE
    RAISE EXCEPTION 'unknown part %', p_part;
  END IF;

  INSERT INTO robin_export.chunk_check VALUES (r.*);
  RETURN r;
END
$$;

-- Fingerprint of the stored outbox rows (published or pending), with the
-- settings of sql/table-fingerprints.sql: used again after the archive has
-- been restored into a fresh database.
CREATE FUNCTION robin_export.outbox_fingerprint(p_published boolean, OUT rows bigint, OUT fingerprint numeric)
LANGUAGE sql STABLE
SET TimeZone = 'UTC' SET extra_float_digits = 1 SET bytea_output = 'hex'
SET IntervalStyle = 'postgres' SET DateStyle = 'ISO, MDY'
AS $$
  SELECT count(*), coalesce(sum(('x' || substr(md5(to_jsonb(t)::text), 1, 15))::bit(60)::bigint), 0)
    FROM public.outbox_events t
   WHERE (t.published_at IS NOT NULL) = p_published
$$;
