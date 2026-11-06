#!/usr/bin/env bash
# Runs pg_dump inside the snapshot exported by sql/snapshot-session.sql.
# Called from that psql session with \! so the exporting transaction stays
# open for the whole dump. Connection settings come from the environment
# (PGHOST, PGPORT, PGUSER, PGDATABASE, PGSSLMODE, PGPASSFILE).
set -uo pipefail

snap=$(tr -d '[:space:]' < /dump/snapshot.id)
if [[ -z "$snap" ]]; then
  echo "run-pg-dump: no snapshot id" >&2
  echo 90 > /dump/pg_dump.status
  exit 90
fi

started=$(date -u +%FT%TZ)
pg_dump --format=custom --compress=gzip:6 --snapshot="$snap" \
        --lock-wait-timeout=120s --no-password --verbose \
        --file=/dump/robin.dump 2> /dump/pg_dump.log
rc=$?
finished=$(date -u +%FT%TZ)

printf '%s\n' "$rc" > /dump/pg_dump.status
printf 'pg_dump_started|%s\npg_dump_finished|%s\npg_dump_exit|%s\n' \
       "$started" "$finished" "$rc" > /dump/pg_dump.timing
exit "$rc"
