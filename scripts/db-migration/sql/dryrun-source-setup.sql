-- Stage A dry run ONLY. Builds a synthetic source shaped like the production
-- Supabase database. It contains no real data. Run it as the throwaway
-- container's superuser (supabase_admin), never against a real database.
--
-- What it reproduces from production:
--   - postgres: a LOGIN role that is NOT a superuser but has BYPASSRLS, and
--     owns the application schema;
--   - extensions: citext in public; pgcrypto, uuid-ossp and
--     pg_stat_statements in an "extensions" schema;
--   - platform schemas (auth, storage, realtime, vault) holding only
--     bookkeeping rows;
--   - the supabase_realtime publication;
--   - default privileges for the Data API roles;
--   - Supabase's documented RLS bootstrap: public.rls_auto_enable() plus the
--     ensure_rls event trigger, verbatim as FlywayBootstrapRecoveryStrategyIT
--     reproduces it. It enables RLS on every table the migrations create.
\set ON_ERROR_STOP on

COMMENT ON DATABASE postgres IS 'robin-dryrun-source';

CREATE ROLE postgres LOGIN NOSUPERUSER CREATEDB CREATEROLE BYPASSRLS PASSWORD :'pg_pw';
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN BYPASSRLS;
CREATE ROLE supabase_auth_admin NOLOGIN;
CREATE ROLE supabase_storage_admin NOLOGIN;
CREATE ROLE supabase_realtime_admin NOLOGIN;

GRANT CREATE, USAGE ON SCHEMA public TO postgres;
GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role;

CREATE SCHEMA extensions;
GRANT USAGE ON SCHEMA extensions TO postgres, anon, authenticated, service_role;
CREATE EXTENSION citext WITH SCHEMA public;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;
CREATE EXTENSION "uuid-ossp" WITH SCHEMA extensions;
CREATE EXTENSION pg_stat_statements WITH SCHEMA extensions;
ALTER ROLE postgres SET search_path = "$user", public, extensions;

CREATE SCHEMA auth AUTHORIZATION supabase_auth_admin;
CREATE TABLE auth.users (id uuid PRIMARY KEY, email text, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE auth.schema_migrations (version text PRIMARY KEY);
INSERT INTO auth.schema_migrations SELECT lpad(g::text, 14, '0') FROM generate_series(1, 60) g;
ALTER TABLE auth.users OWNER TO supabase_auth_admin;
ALTER TABLE auth.schema_migrations OWNER TO supabase_auth_admin;
ALTER TABLE auth.users ENABLE ROW LEVEL SECURITY;
GRANT USAGE ON SCHEMA auth TO postgres;
GRANT SELECT ON ALL TABLES IN SCHEMA auth TO postgres;

CREATE SCHEMA storage AUTHORIZATION supabase_storage_admin;
CREATE TABLE storage.buckets (id text PRIMARY KEY, name text NOT NULL);
CREATE TABLE storage.objects (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                              bucket_id text REFERENCES storage.buckets (id), name text);
CREATE TABLE storage.migrations (id integer PRIMARY KEY, name text NOT NULL);
INSERT INTO storage.migrations SELECT g, 'migration-' || g FROM generate_series(1, 30) g;
ALTER TABLE storage.buckets OWNER TO supabase_storage_admin;
ALTER TABLE storage.objects OWNER TO supabase_storage_admin;
ALTER TABLE storage.migrations OWNER TO supabase_storage_admin;
ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;
GRANT USAGE ON SCHEMA storage TO postgres;
GRANT SELECT ON ALL TABLES IN SCHEMA storage TO postgres;

CREATE SCHEMA realtime AUTHORIZATION supabase_realtime_admin;
CREATE TABLE realtime.schema_migrations (version bigint PRIMARY KEY, inserted_at timestamp(0));
INSERT INTO realtime.schema_migrations
SELECT g, timestamp '2026-01-01' + g * interval '1 day' FROM generate_series(1, 40) g;
ALTER TABLE realtime.schema_migrations OWNER TO supabase_realtime_admin;
GRANT USAGE ON SCHEMA realtime TO postgres;
GRANT SELECT ON ALL TABLES IN SCHEMA realtime TO postgres;

CREATE SCHEMA vault;
CREATE TABLE vault.secrets (id uuid PRIMARY KEY, name text, secret text NOT NULL);
GRANT USAGE ON SCHEMA vault TO postgres;
GRANT SELECT ON ALL TABLES IN SCHEMA vault TO postgres;

CREATE PUBLICATION supabase_realtime;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.rls_auto_enable()
RETURNS EVENT_TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $fn$
DECLARE
cmd record;
BEGIN
FOR cmd IN
SELECT *
FROM pg_event_trigger_ddl_commands()
WHERE command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
AND object_type IN ('table','partitioned table')
LOOP
IF cmd.schema_name IS NOT NULL AND cmd.schema_name IN ('public')
   AND cmd.schema_name NOT IN ('pg_catalog','information_schema')
   AND cmd.schema_name NOT LIKE 'pg_toast%'
   AND cmd.schema_name NOT LIKE 'pg_temp%' THEN
BEGIN
EXECUTE format('alter table if exists %s enable row level security', cmd.object_identity);
EXCEPTION
WHEN OTHERS THEN
RAISE;
END;
END IF;
END LOOP;
END;
$fn$;
ALTER FUNCTION public.rls_auto_enable() OWNER TO postgres;
CREATE EVENT TRIGGER ensure_rls ON ddl_command_end
  WHEN TAG IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
  EXECUTE FUNCTION public.rls_auto_enable();

-- A second platform event trigger, like Supabase's pgrst_ddl_watch.
CREATE FUNCTION extensions.pgrst_ddl_watch() RETURNS event_trigger
LANGUAGE plpgsql AS $fn$ BEGIN NULL; END $fn$;
CREATE EVENT TRIGGER pgrst_ddl_watch ON ddl_command_end EXECUTE FUNCTION extensions.pgrst_ddl_watch();
