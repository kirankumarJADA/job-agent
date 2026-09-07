-- Runs once on first container init (docker-entrypoint-initdb.d).
-- Flyway migrations (backend/src/main/resources/db/migration) own schema
-- objects; this file only enables extensions Flyway's DDL depends on.

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
