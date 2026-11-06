#!/usr/bin/env bash
# Stage C, run INSIDE a disposable postgres:17.11 container started by
# restore-test.ps1 (its server is running, PGDATA is on tmpfs, port bound to
# 127.0.0.1 only). See scripts/db-migration/README.md.
#
#   /in       the encrypted archive and the backup's metadata files
#   /restore  RAM-backed tmpfs: the decrypted archive and the passphrase
#   /work     scripts
#   /out      the restore report (metadata only, no row data)
#
# Restores the application scope (schema public) as robin_app, a role with
# NOSUPERUSER NOBYPASSRLS that owns every restored object, exactly as planned
# for the Oracle target, then verifies it against the backup's snapshot.
set -Eeuo pipefail
umask 077

WORK=/work
IN=/in
R=/restore
OUT=/out
LOG="$OUT/restore.log"
CHECKS="$OUT/restore-checks.txt"
mkdir -p "$OUT"
: > "$LOG"
: > "$CHECKS"
FAILED=0

log()  { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$LOG" >&2; }
pass() { printf 'PASS|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "PASS $1 ${2:-}"; }
fail() { printf 'FAIL|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "FAIL $1 ${2:-}"; FAILED=1; }
note() { printf 'NOTE|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "NOTE $1 ${2:-}"; }
trap 'rm -f "$R/.passphrase"' EXIT
abort() { fail "$1" "${2:-}"; log "restore test aborted"; exit 1; }

MODE=${ROBIN_MODE:-}
case "$MODE" in
  live|dryrun) ;;
  *) echo "ROBIN_MODE must be 'live' or 'dryrun'" >&2; exit 2 ;;
esac
[[ -n ${ROBIN_APP_PASSWORD:-} ]] || { echo "ROBIN_APP_PASSWORD is required" >&2; exit 2; }
for f in robin.dump.gpg robin.dump.sha256 source-fingerprints.txt source-inventory.txt source-facts.txt archive-counts.txt; do
  [[ -s $IN/$f ]] || { echo "missing $IN/$f" >&2; exit 2; }
done

SU=(psql -X -q -At -F '|' -v ON_ERROR_STOP=1 -U postgres)
APP=(psql -X -q -At -F '|' -v ON_ERROR_STOP=1 -U robin_app -d robin_restore)

log "Robin restore test ($MODE): $(postgres --version); $(gpg --version | head -n 1)"

# ── 1. decrypt into tmpfs and check the archive hash ──────────────────
if [[ $MODE == live ]]; then
  [[ -t 0 ]] || { echo "live mode needs an interactive terminal (docker exec -it)" >&2; exit 2; }
  printf 'Encryption passphrase for this backup (input hidden): ' > /dev/tty
  IFS= read -rs pp < /dev/tty
  printf '\n' > /dev/tty
  printf '%s' "$pp" > "$R/.passphrase"
  unset pp
else
  printf '%s' "${ROBIN_DRYRUN_PASSPHRASE:?}" > "$R/.passphrase"
  unset ROBIN_DRYRUN_PASSPHRASE
fi
chmod 600 "$R/.passphrase"
export GNUPGHOME="$R/.gnupg"
mkdir -p -m 700 "$GNUPGHOME"
gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$R/.passphrase" \
    --decrypt --output "$R/robin.dump" "$IN/robin.dump.gpg" 2>> "$LOG" \
  || abort decrypt "gpg could not decrypt the archive (wrong passphrase?)"
rm -f "$R/.passphrase"
pass decrypt "decrypted into tmpfs"
sha=$(sha256sum "$R/robin.dump" | cut -d' ' -f1)
expected=$(cut -d' ' -f1 "$IN/robin.dump.sha256")
if [[ $sha == "$expected" ]]; then pass archive_sha256 "$sha"; else abort archive_sha256 "decrypted $sha, manifest $expected"; fi

# ── 2. the target, shaped like the planned Oracle database ────────────
"${SU[@]}" -d postgres -v app_pw="$ROBIN_APP_PASSWORD" <<'SQL'
CREATE ROLE robin_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD :'app_pw';
CREATE ROLE robin_rls_probe NOLOGIN NOSUPERUSER NOBYPASSRLS;
SQL
createdb -U postgres -T template0 -E UTF8 --locale=en_US.utf8 -O robin_app robin_restore
"${SU[@]}" -d robin_restore -c 'CREATE EXTENSION citext WITH SCHEMA public' \
                            -c 'CREATE EXTENSION pgcrypto WITH SCHEMA public'
pass target_prepared "robin_restore owned by robin_app (NOSUPERUSER NOBYPASSRLS); citext, pgcrypto"

# ── 3. restore the application scope ──────────────────────────────────
pg_restore --list "$R/robin.dump" > "$OUT/toc-full.txt"
pg_restore --list --schema=public "$R/robin.dump" > "$R/public.list"
# Platform-only entries are excluded even inside public: they need Supabase
# roles, extensions or publications that a vanilla PostgreSQL does not have.
grep -vE '^[0-9]+; [0-9]+ [0-9]+ (PUBLICATION|PUBLICATION TABLE|PUBLICATION TABLES IN SCHEMA|EVENT TRIGGER|DEFAULT ACL|ACL|SUBSCRIPTION|SUBSCRIPTION TABLE) ' \
  "$R/public.list" > "$R/restore.list" || true
grep '^[0-9]' "$R/restore.list" | sed -E 's/^[0-9]+; [0-9]+ [0-9]+ //' > "$OUT/restored-entries.txt" || true
grep '^[0-9]' "$OUT/toc-full.txt" | sed -E 's/^[0-9]+; [0-9]+ [0-9]+ //' | sort > "$R/all.sorted"
sort "$OUT/restored-entries.txt" > "$R/restored.sorted"
comm -23 "$R/all.sorted" "$R/restored.sorted" > "$OUT/excluded-entries.txt" || true
log "restoring $(wc -l < "$OUT/restored-entries.txt") of $(wc -l < "$R/all.sorted") TOC entries; excluded entries are listed in excluded-entries.txt"
started=$(date +%s)
if pg_restore -U robin_app -d robin_restore --no-owner --no-privileges --exit-on-error --single-transaction \
     -L "$R/restore.list" "$R/robin.dump" 2> "$OUT/pg_restore.log"; then
  pass pg_restore "application scope restored in $(( $(date +%s) - started ))s as robin_app, single transaction"
else
  abort pg_restore "see pg_restore.log"
fi
if grep -qiE 'warning|error' "$OUT/pg_restore.log"; then note pg_restore_log "pg_restore.log is not empty"; fi

# ── 4. inventory equals the snapshot inventory ────────────────────────
"${APP[@]}" -v inv_schema=public -f "$WORK/inventory.sql" > "$OUT/restored-inventory.txt"
awk -F'|' '
  FNR == NR { src[$1] = $2; next }
  { dst[$1] = $2 }
  END {
    for (k in src) {
      if (k == "owners" || k == "extensions") continue
      if (!(k in dst)) print "MISSING|" k "|source=" src[k]
      else if (src[k] != dst[k]) print "DIFF|" k "|source=" src[k] "|restored=" dst[k]
    }
    for (k in dst) if (!(k in src) && k != "owners" && k != "extensions") print "EXTRA|" k "|restored=" dst[k]
  }' "$IN/source-inventory.txt" "$OUT/restored-inventory.txt" > "$OUT/inventory-diff.txt"
inv_keys=$(grep -cvE '^(owners|extensions)\|' "$OUT/restored-inventory.txt" || true)
if [[ -s $OUT/inventory-diff.txt ]]; then fail inventory "differences in inventory-diff.txt"; else pass inventory "$inv_keys keys equal (tables, indexes, constraints, triggers, functions, RLS, sequences, Flyway)"; fi
nv=$(awk -F'|' '$1 == "constraints_not_validated" { print $2 }' "$OUT/restored-inventory.txt")
if [[ ${nv:-0} == 0 ]]; then pass constraints_validated "0 constraints left NOT VALID"; else fail constraints_validated "$nv constraint(s) NOT VALID"; fi

# ── 5. every public table: same row count and same content fingerprint ─
"${APP[@]}" -v fp_schemas=public > "$OUT/restored-fingerprints.txt" <<'SQL'
BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;
SET LOCAL TimeZone = 'UTC';
SET LOCAL extra_float_digits = 1;
SET LOCAL bytea_output = 'hex';
SET LOCAL IntervalStyle = 'postgres';
SET LOCAL DateStyle = 'ISO, MDY';
\i /work/table-fingerprints.sql
COMMIT;
SQL
awk -F'|' '
  FNR == NR { if ($1 ~ /^public\./) src[$1] = $2 "|" $3; next }
  { dst[$1] = $2 "|" $3 }
  END {
    for (k in src) {
      if (!(k in dst)) print "MISSING|" k
      else if (src[k] != dst[k]) print "DIFF|" k "|source=" src[k] "|restored=" dst[k]
    }
    for (k in dst) if (!(k in src)) print "EXTRA|" k
  }' "$IN/source-fingerprints.txt" "$OUT/restored-fingerprints.txt" > "$OUT/fingerprint-diff.txt"
fp_tables=$(wc -l < "$OUT/restored-fingerprints.txt")
fp_rows=$(awk -F'|' '{ s += $2 } END { print s + 0 }' "$OUT/restored-fingerprints.txt")
if [[ -s $OUT/fingerprint-diff.txt ]]; then fail fingerprints "differences in fingerprint-diff.txt"; else pass fingerprints "$fp_tables public tables, $fp_rows rows: counts and content fingerprints equal the snapshot"; fi

# ── 6. the decrypted archive yields the same rows as at backup time ───
pg_restore --data-only --file=- "$R/robin.dump" | awk -f "$WORK/copy-counts.awk" > "$OUT/restore-archive-counts.txt"
if cmp -s <(sort "$IN/archive-counts.txt") <(sort "$OUT/restore-archive-counts.txt"); then
  pass archive_counts "archive row counts identical to the backup-time counts"
else
  fail archive_counts "archive row counts differ from the backup-time counts"
fi

# ── 7. data outside public is present and readable ────────────────────
others=$(awk -F'|' '{ split($1, a, "."); if (a[1] != "public") print a[1] }' "$IN/archive-counts.txt" | sort -u | tr '\n' ' ')
if [[ -n ${others// /} ]]; then
  args=()
  for s in $others; do args+=("--schema=$s"); done
  if pg_restore --data-only "${args[@]}" --file=/dev/null "$R/robin.dump" 2>> "$LOG"; then
    pass non_public_data_readable "schemas: ${others% }; $(awk -F'|' '$1 !~ /^public\./ { s += $2 } END { print s + 0 }' "$IN/archive-counts.txt") rows"
  else
    fail non_public_data_readable "pg_restore could not read the data of: $others"
  fi
else
  note non_public_data_readable "the archive holds no table data outside public"
fi

# ── 8. RLS: enabled and inert for the owner, denying for others ───────
probe=$(sort -t'|' -k2,2nr "$OUT/restored-fingerprints.txt" | head -n 1 | cut -d'|' -f1)
probe_rows=$(sort -t'|' -k2,2nr "$OUT/restored-fingerprints.txt" | head -n 1 | cut -d'|' -f2)
probe_rls=$("${SU[@]}" -d robin_restore -c "select relrowsecurity from pg_class where oid = '$probe'::regclass")
owner_rows=$("${APP[@]}" -c "select count(*) from $probe")
owner_active=$("${APP[@]}" -c "select row_security_active('$probe')")
"${SU[@]}" -d robin_restore -c 'GRANT USAGE ON SCHEMA public TO robin_rls_probe' \
                            -c 'GRANT SELECT ON ALL TABLES IN SCHEMA public TO robin_rls_probe'
other_rows=$("${SU[@]}" -d robin_restore -c "SET ROLE robin_rls_probe; select count(*) from $probe")
if [[ $probe_rls == t && $owner_rows == "$probe_rows" && $owner_active == f && $other_rows == 0 && $probe_rows -gt 0 ]]; then
  pass rls_behaviour "$probe: RLS enabled; owner robin_app sees $owner_rows rows (row_security_active=f); a role without BYPASSRLS sees 0"
elif [[ $probe_rls == f && $owner_rows == "$probe_rows" && $other_rows == "$probe_rows" ]]; then
  note rls_behaviour "$probe has RLS disabled at the source too; owner and other roles both see $owner_rows rows"
else
  fail rls_behaviour "$probe: rls=$probe_rls owner=$owner_rows/$probe_rows active=$owner_active other=$other_rows"
fi

# ── 9. pending outbox preserved exactly ───────────────────────────────
src_pending=$(awk -F'|' '$1 == "outbox_pending" { print $2 }' "$IN/source-facts.txt")
if [[ -n $src_pending ]]; then
  dst_pending=$("${APP[@]}" -c 'select count(*) from public.outbox_events where published_at is null')
  if [[ $dst_pending == "$src_pending" ]]; then pass outbox_pending "$dst_pending pending events preserved"; else fail outbox_pending "snapshot $src_pending, restored $dst_pending"; fi
  "${APP[@]}" -f "$WORK/outbox-analysis.sql" > "$OUT/outbox-analysis.txt" 2>> "$LOG" \
    && pass outbox_analysis "written to outbox-analysis.txt" \
    || note outbox_analysis "analysis queries failed; see restore.log"
fi

if (( FAILED )); then
  log "RESTORE TEST FAILED: $(grep -c '^FAIL' "$CHECKS") check(s) failed; see restore-checks.txt"
  exit 1
fi
log "RESTORE TEST OK: $(grep -c '^PASS' "$CHECKS") checks passed"
