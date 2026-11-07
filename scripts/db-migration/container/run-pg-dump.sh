#!/usr/bin/env bash
# Runs pg_dump inside the snapshot exported by sql/snapshot-session.sql.
# Called from that psql session with \! so the exporting transaction stays
# open for the whole dump. Connection settings come from the environment
# (PGHOST, PGPORT, PGUSER, PGDATABASE, PGSSLMODE, PGPASSFILE).
#
# ROBIN_DEFER_TABLE_DATA (optional, comma-separated schema.table names): the
# ROWS of these tables are left out with --exclude-table-data; their
# definitions stay in the archive. Each name must be a table that the snapshot
# session fingerprinted, or the dump does not start.
# ROBIN_EGRESS_CAP_BYTES > 0: the dump starts only while backup.sh's egress
# watchdog is alive, so the cap is always enforced.
set -uo pipefail

snap=$(tr -d '[:space:]' < /dump/snapshot.id)
if [[ -z "$snap" ]]; then
  echo "run-pg-dump: no snapshot id" >&2
  echo 90 > /dump/pg_dump.status
  exit 90
fi

extra=()
if [[ -n ${ROBIN_DEFER_TABLE_DATA:-} ]]; then
  IFS=',' read -r -a deferred <<< "$ROBIN_DEFER_TABLE_DATA"
  for t in "${deferred[@]}"; do
    if ! awk -F'|' -v k="$t" '$1 == k { found = 1 } END { exit !found }' /dump/source-fingerprints.txt; then
      echo "run-pg-dump: $t is not a table in the snapshot; refusing to start" >&2
      echo 91 > /dump/pg_dump.status
      exit 91
    fi
    extra+=("--exclude-table-data=$t")
  done
  echo "rows left out of this archive (definitions, counts and fingerprints are kept): ${ROBIN_DEFER_TABLE_DATA//,/, }"
fi

if (( ${ROBIN_EGRESS_CAP_BYTES:-0} > 0 )); then
  beat=$(cat /dump/watchdog.beat 2>/dev/null || echo 0)
  if (( $(date +%s) - beat > 5 )); then
    echo "run-pg-dump: the egress watchdog is not running; refusing to start" >&2
    echo 92 > /dump/pg_dump.status
    exit 92
  fi
fi

started=$(date -u +%FT%TZ)
echo "pg_dump started at $started; one line per table follows"
# The verbose log goes to pg_dump.log and, for progress, to stderr.
pg_dump --format=custom --compress=gzip:6 --snapshot="$snap" \
        --lock-wait-timeout=120s --no-password --verbose "${extra[@]}" \
        --file=/dump/robin.dump 2> >(tee /dump/pg_dump.log >&2)
rc=$?
finished=$(date -u +%FT%TZ)
echo "pg_dump finished at $finished with exit code $rc"

printf '%s\n' "$rc" > /dump/pg_dump.status
printf 'pg_dump_started|%s\npg_dump_finished|%s\npg_dump_exit|%s\n' \
       "$started" "$finished" "$rc" > /dump/pg_dump.timing
exit "$rc"
