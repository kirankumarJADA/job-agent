#!/usr/bin/env bash
# Stage B, run INSIDE a disposable postgres:17.11 container started by
# backup-supabase.ps1. See scripts/db-migration/README.md.
#
#   /work  scripts (container disk)
#   /dump  RAM-backed tmpfs: plaintext archive and the transient secrets
#   /out   container disk: ONLY the encrypted archive and metadata files
#
# Live mode reads the database password and the encryption passphrase from
# the terminal (docker exec -it). They are never echoed, never passed on a
# command line, and only ever written to the tmpfs, which disappears with
# the container. Dry-run mode takes throwaway test values from the
# environment and refuses to start in live mode if they are present.
set -Eeuo pipefail
umask 077

WORK=/work
DUMP=/dump
OUT=/out
LOG="$OUT/backup.log"
CHECKS="$OUT/checks.txt"
MANIFEST="$OUT/manifest.txt"
mkdir -p "$OUT"
: > "$LOG"
: > "$CHECKS"
FAILED=0

log()  { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$LOG" >&2; }
pass() { printf 'PASS|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "PASS $1 ${2:-}"; }
fail() { printf 'FAIL|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "FAIL $1 ${2:-}"; FAILED=1; }
note() { printf 'NOTE|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "NOTE $1 ${2:-}"; }
cleanup_secrets() { rm -f "$DUMP/.pgpass" "$DUMP/.passphrase"; }
WATCHDOG=
stop_watchdog() {
  if [[ -n $WATCHDOG ]]; then kill "$WATCHDOG" 2>/dev/null || true; wait "$WATCHDOG" 2>/dev/null || true; WATCHDOG=; fi
}
trap 'cleanup_secrets; stop_watchdog' EXIT
abort() { fail "$1" "${2:-}"; log "backup aborted"; exit 1; }

MODE=${ROBIN_MODE:-}
case "$MODE" in
  live|dryrun) ;;
  *) echo "ROBIN_MODE must be 'live' or 'dryrun'" >&2; exit 2 ;;
esac
if [[ $MODE == live && ( -n ${ROBIN_DRYRUN_PGPASSWORD:-} || -n ${ROBIN_DRYRUN_PASSPHRASE:-} ) ]]; then
  echo "refusing to run: dry-run test secrets are set in live mode" >&2
  exit 2
fi
for v in PGHOST PGPORT PGUSER PGDATABASE PGSSLMODE; do
  [[ -n ${!v:-} ]] || { echo "$v is required" >&2; exit 2; }
done
if [[ $MODE == live && $PGSSLMODE == disable ]]; then
  echo "refusing to run: live mode requires TLS (PGSSLMODE require or verify-full)" >&2
  exit 2
fi

# Scope and egress controls, set by backup-supabase.ps1:
#   ROBIN_DEFER_TABLE_DATA  comma-separated schema.table names whose ROWS are
#                           left out of this archive. Their definitions, and
#                           their counts and fingerprints in the snapshot, are
#                           still recorded. Empty (the default) = complete.
#   ROBIN_EGRESS_CAP_BYTES  stop pg_dump and psql once this container has
#                           received more than this many bytes, counted on all
#                           its network interfaces from before the first
#                           connection. 0 = no cap. Live mode requires a cap.
DEFER=${ROBIN_DEFER_TABLE_DATA:-}
CAP=${ROBIN_EGRESS_CAP_BYTES:-0}
[[ $CAP =~ ^[0-9]+$ ]] || { echo "ROBIN_EGRESS_CAP_BYTES must be a whole number of bytes" >&2; exit 2; }
if [[ $MODE == live ]] && (( CAP == 0 )); then
  echo "refusing to run: live mode needs an egress cap (ROBIN_EGRESS_CAP_BYTES)" >&2
  exit 2
fi
deferred=()
if [[ -n $DEFER ]]; then
  IFS=',' read -r -a deferred <<< "$DEFER"
  for t in "${deferred[@]}"; do
    [[ $t =~ ^[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*$ ]] || { echo "invalid table name in ROBIN_DEFER_TABLE_DATA: $t" >&2; exit 2; }
  done
fi
printf '%s\n' "${deferred[@]}" | sed '/^$/d' > "$OUT/deferred-table-data.txt"

net_ifaces() { # network interfaces of this container, loopback excluded
  local f
  for f in /sys/class/net/*/statistics/rx_bytes; do
    [[ -r $f && $f != /sys/class/net/lo/* ]] && printf '%s\n' "$f"
  done
  return 0
}
net_rx() { # bytes received by this container on every interface except loopback
  local total=0 f v
  while IFS= read -r f; do
    v=$(< "$f") || v=0
    total=$(( total + v ))
  done < <(net_ifaces)
  printf '%s' "$total"
}
mb() { printf '%s' "$(( $1 / 1000000 ))"; } # decimal MB, the unit of the Supabase usage page

start_watchdog() { # every 0.1 s: enforce the cap; every 30 s: report what was received
  # The cap is checked between samples, so data already in flight when it is
  # crossed still arrives (on a fast local network the 20 MB test cap was
  # overshot to 46 MB). Set the cap with a margin: see the README.
  (
    set +e
    last=0
    while :; do
      used=$(( $(net_rx) - RX0 ))
      printf '%(%s)T\n' -1 > "$DUMP/watchdog.beat"
      if (( CAP > 0 && used > CAP )); then
        printf '%s\n' "$used" > "$DUMP/egress-cap.hit"
        for p in /proc/[0-9]*; do [[ $(cat "$p/comm" 2>/dev/null) == pg_dump ]] && kill -TERM "${p#/proc/}" 2>/dev/null; done
        for p in /proc/[0-9]*; do [[ $(cat "$p/comm" 2>/dev/null) == psql ]] && kill -TERM "${p#/proc/}" 2>/dev/null; done
        log "EGRESS CAP REACHED: $(mb "$used") MB received, cap $(mb "$CAP") MB; pg_dump and psql were stopped"
        exit 0
      fi
      printf -v now '%(%s)T' -1
      if (( now - last >= 30 )); then
        if (( CAP > 0 )); then log "network: $(mb "$used") MB received so far (cap $(mb "$CAP") MB)"
        else log "network: $(mb "$used") MB received so far (no cap)"; fi
        last=$now
      fi
      sleep 0.1
    done
  ) &
  WATCHDOG=$!
}

write_pgpass() {
  local esc=${1//\\/\\\\}
  esc=${esc//:/\\:}
  printf '*:*:*:*:%s\n' "$esc" > "$DUMP/.pgpass"
  chmod 600 "$DUMP/.pgpass"
}

read_secret() { # $1 = prompt; prints the value on stdout
  local value
  printf '%s' "$1" > /dev/tty
  IFS= read -rs value < /dev/tty
  printf '\n' > /dev/tty
  printf '%s' "$value"
}

log "Robin backup ($MODE): $(pg_dump --version); $(gpg --version | head -n 1)"
log "target: port $PGPORT, database $PGDATABASE, sslmode $PGSSLMODE (host and user are not logged)"
if (( ${#deferred[@]} > 0 )); then
  log "scope: PARTIAL - the rows of ${DEFER//,/, } are left out (definitions, counts and fingerprints are kept)"
else
  log "scope: complete (every table's rows)"
fi
if (( CAP > 0 )); then log "egress cap: $(mb "$CAP") MB received by this container"; else log "egress cap: none"; fi

# ── 1. secrets, in tmpfs only ─────────────────────────────────────────
if [[ $MODE == live ]]; then
  [[ -t 0 ]] || { echo "live mode needs an interactive terminal (docker exec -it)" >&2; exit 2; }
  pw=$(read_secret "Database password for the backup connection (input hidden): ")
  [[ -n $pw ]] || { echo "empty password" >&2; exit 2; }
  write_pgpass "$pw"
  unset pw
else
  write_pgpass "$ROBIN_DRYRUN_PGPASSWORD"
  printf '%s' "$ROBIN_DRYRUN_PASSPHRASE" > "$DUMP/.passphrase"
  chmod 600 "$DUMP/.passphrase"
  unset ROBIN_DRYRUN_PGPASSWORD ROBIN_DRYRUN_PASSPHRASE
fi
export PGPASSFILE="$DUMP/.pgpass" PGAPPNAME=robin-backup PGCONNECT_TIMEOUT=20

# ── 2. connect and check the server major version ─────────────────────
# Everything this container receives from here on counts against the cap.
# A cap that cannot be measured is refused rather than silently ignored.
if [[ -z $(net_ifaces) ]]; then
  if (( CAP > 0 )); then abort egress_meter "cannot read this container's network counters, so the cap could not be enforced"; fi
  note egress_meter "network counters unavailable; transfer not measured"
fi
RX0=$(net_rx)
start_watchdog
for _ in 1 2 3 4 5 6 7 8 9 10; do [[ -s $DUMP/watchdog.beat ]] && break; sleep 0.5; done
if (( CAP > 0 )) && [[ ! -s $DUMP/watchdog.beat ]]; then abort egress_meter "the egress watchdog did not start"; fi
info=$(psql -X -q -At -F '|' -c "select current_setting('server_version_num'), current_setting('default_transaction_read_only')") \
  || abort connect "psql could not connect (check host, user, password, sslmode)"
IFS='|' read -r vnum ro <<< "$info"
if (( vnum / 10000 == 17 )); then pass server_major "server_version_num=$vnum"; else abort server_major "expected PostgreSQL 17, got $vnum"; fi
log "default_transaction_read_only=$ro"

# ── 3. one snapshot: facts, fingerprints, inventory, then pg_dump ─────
log "opening the snapshot session (fingerprints read every table once, then pg_dump runs)"
set +e
# Progress (the session's \echo lines and pg_dump's per-table verbose lines)
# is shown on the terminal and kept in backup.log.
psql -X -q -At -F '|' -v ON_ERROR_STOP=1 -f "$WORK/snapshot-session.sql" 2>&1 | tee -a "$LOG" >&2
session_rc=${PIPESTATUS[0]}
set -e
# The network part of the run is over: stop the watchdog, then measure.
stop_watchdog
rx_used=$(( $(net_rx) - RX0 ))
dump_rc=$(cat "$DUMP/pg_dump.status" 2>/dev/null || echo none)
if [[ -s $DUMP/watchdog.lost ]]; then
  abort egress_meter "the egress watchdog stopped during the dump, so pg_dump was stopped after $(mb "$rx_used") MB; nothing was encrypted or copied out"
fi
# The cap stops the transfer. An archive is kept only if pg_dump had already
# finished by itself when the cap was crossed (the last bytes of the session
# can arrive after pg_dump exits); otherwise nothing is kept.
if [[ -s $DUMP/egress-cap.hit && $dump_rc != 0 ]]; then
  abort egress_cap "stopped after $(mb "$rx_used") MB received (cap $(mb "$CAP") MB); nothing was encrypted or copied out"
fi
if (( CAP > 0 )); then
  if (( rx_used <= CAP )); then
    pass egress_within_cap "$(mb "$rx_used") MB received ($rx_used bytes), cap $(mb "$CAP") MB"
  else
    note egress_over_cap "$(mb "$rx_used") MB received ($rx_used bytes), $(( (rx_used - CAP) / 1000000 )) MB over the cap of $(mb "$CAP") MB, after pg_dump had finished; the archive is complete"
  fi
else
  note network_received "$(mb "$rx_used") MB received ($rx_used bytes), no cap"
fi
[[ -s $DUMP/pg_dump.status ]] || abort pg_dump "pg_dump never ran (snapshot session exited with $session_rc; see backup.log)"
if [[ $dump_rc == 0 ]]; then pass pg_dump_exit "exit 0"; else abort pg_dump_exit "exit $dump_rc (see pg_dump.log)"; fi
if [[ $session_rc == 0 ]]; then
  pass snapshot_session "facts, fingerprints, inventory and pg_dump used one snapshot"
else
  # The session may lose its connection after pg_dump has finished (for
  # example a pooler timeout while it sat idle in its transaction). The
  # archive is unaffected; the fingerprints were written before pg_dump.
  note snapshot_session "psql exited with $session_rc after pg_dump; fingerprints were taken before pg_dump"
fi
if grep -qE 'pg_dump: (error|fatal):' "$DUMP/pg_dump.log"; then
  fail pg_dump_log "the verbose log reports errors"
else
  pass pg_dump_log "no errors in the verbose log"
fi
grep -qE 'pg_dump: warning:' "$DUMP/pg_dump.log" && note pg_dump_warnings "$(grep -cE 'pg_dump: warning:' "$DUMP/pg_dump.log") warning(s) in pg_dump.log"

fp_lines=$(grep -c '|' "$DUMP/source-fingerprints.txt" || true)
if (( fp_lines > 0 )); then pass source_fingerprints "$fp_lines tables fingerprinted in the dump snapshot"; else abort source_fingerprints "no fingerprints were recorded"; fi

# ── 4. archive readable, TOC complete for public ──────────────────────
pg_restore --list "$DUMP/robin.dump" > "$OUT/toc.txt" || abort toc "pg_restore --list failed"
toc_entries=$(grep -c '^[0-9]' "$OUT/toc.txt" || true)
pass toc_readable "$toc_entries TOC entries"

inv() { awk -F'|' -v k="$1" '$1 == k { print $2; found = 1 } END { if (!found) print 0 }' "$DUMP/source-inventory.txt"; }
toc_count() { grep -cE "^[0-9]+; [0-9]+ [0-9]+ $1 public " "$OUT/toc.txt" || true; }
check_toc() { # $1 = check name, $2 = TOC count, $3 = expected
  if [[ $2 == "$3" ]]; then pass "$1" "$2"; else fail "$1" "archive has $2, snapshot inventory has $3"; fi
}
deferred_public=0
for t in "${deferred[@]}"; do [[ $t == public.* ]] && deferred_public=$(( deferred_public + 1 )); done
check_toc toc_table_data_public "$(toc_count 'TABLE DATA')" "$(( $(inv tables) - deferred_public ))"
check_toc toc_row_security_public "$(toc_count 'ROW SECURITY')" "$(inv rls_enabled_tables)"
check_toc toc_triggers_public "$(toc_count 'TRIGGER')" "$(inv triggers)"
check_toc toc_views_public "$(toc_count 'VIEW')" "$(inv views)"
check_toc toc_sequence_set_public "$(toc_count 'SEQUENCE SET')" "$(inv sequences)"
check_toc toc_indexes_public "$(( $(toc_count 'INDEX') + $(toc_count 'CONSTRAINT') ))" "$(inv indexes)"

# ── 5. every snapshot row is in the archive ───────────────────────────
pg_restore --data-only --file=- "$DUMP/robin.dump" | awk -f "$WORK/copy-counts.awk" > "$OUT/archive-counts.txt" \
  || abort archive_counts "could not read the archive's table data"
cmp_result=$(awk -F'|' -v out="$OUT/count-comparison.txt" -v deferred="$DEFER" '
  BEGIN { n = split(deferred, d, ","); for (i = 1; i <= n; i++) if (d[i] != "") dfr[d[i]] = 1 }
  FNR == NR { arch[$1] = $2; next }
  { seen[$1] = 1
    if ($1 in dfr) {
      if ($1 in arch) { bad++; print "DEFERRED_BUT_IN_ARCHIVE|" $1 "|snapshot=" $2 "|archive=" arch[$1] > out }
      else { dfd++; dfrows += $2; print "DEFERRED|" $1 "|snapshot=" $2 > out }
    } else if ($1 in arch) {
      if (arch[$1] == $2) ok++
      else { bad++; print "MISMATCH|" $1 "|snapshot=" $2 "|archive=" arch[$1] > out }
    } else if ($4 == "t") {
      ext++; print "EXTENSION_TABLE_NOT_IN_ARCHIVE|" $1 "|snapshot=" $2 > out
    } else { bad++; print "MISSING_FROM_ARCHIVE|" $1 "|snapshot=" $2 > out }
  }
  END {
    for (a in arch) if (!(a in seen)) { bad++; print "UNEXPECTED_IN_ARCHIVE|" a "|archive=" arch[a] > out }
    printf "%d|%d|%d|%d|%d\n", ok + 0, bad + 0, ext + 0, dfd + 0, dfrows + 0
  }' "$OUT/archive-counts.txt" "$DUMP/source-fingerprints.txt")
IFS='|' read -r cmp_ok cmp_bad cmp_ext cmp_dfd cmp_dfrows <<< "$cmp_result"
touch "$OUT/count-comparison.txt"
if [[ $cmp_bad == 0 && $cmp_ok -gt 0 ]]; then
  pass archive_row_counts "$cmp_ok tables: archive rows equal snapshot rows"
else
  fail archive_row_counts "$cmp_bad table(s) differ (see count-comparison.txt)"
fi
[[ $cmp_ext == 0 ]] || note extension_tables "$cmp_ext extension-owned table(s) are not dumped as data (see count-comparison.txt)"
if (( ${#deferred[@]} > 0 )); then
  if [[ $cmp_dfd == "${#deferred[@]}" ]]; then
    pass deferred_table_data "rows of $cmp_dfd table(s) left out as requested ($cmp_dfrows rows at the snapshot): ${DEFER//,/, }"
  else
    fail deferred_table_data "$cmp_dfd of ${#deferred[@]} deferred table(s) were found in the snapshot and left out of the archive"
  fi
fi

# ── 6. hash, encrypt (AES-256), prove decryption ──────────────────────
plain_sha=$(sha256sum "$DUMP/robin.dump" | cut -d' ' -f1)
plain_bytes=$(stat -c %s "$DUMP/robin.dump")
printf '%s  robin.dump\n' "$plain_sha" > "$OUT/robin.dump.sha256"

if [[ $MODE == live ]]; then
  printf '\nThe archive is ready. Paste the encryption passphrase from your password manager.\n' > /dev/tty
  p1=$(read_secret "Encryption passphrase (input hidden): ")
  p2=$(read_secret "Repeat the passphrase: ")
  if [[ $p1 != "$p2" ]]; then unset p1 p2; abort passphrase "the two entries differ"; fi
  if (( ${#p1} < 16 )); then unset p1 p2; abort passphrase "use at least 16 characters"; fi
  printf '%s' "$p1" > "$DUMP/.passphrase"
  chmod 600 "$DUMP/.passphrase"
  unset p1 p2
fi

export GNUPGHOME="$DUMP/.gnupg"
mkdir -p -m 700 "$GNUPGHOME"
gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$DUMP/.passphrase" \
    --symmetric --cipher-algo AES256 --s2k-mode 3 --s2k-digest-algo SHA512 --s2k-count 65011712 \
    --compress-algo none --no-symkey-cache \
    --output "$DUMP/robin.dump.gpg" "$DUMP/robin.dump" 2>> "$LOG" || abort encrypt "gpg failed"
dec_sha=$(gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$DUMP/.passphrase" \
              --decrypt "$DUMP/robin.dump.gpg" 2>> "$LOG" | sha256sum | cut -d' ' -f1)
rm -f "$DUMP/.passphrase"
if [[ $dec_sha == "$plain_sha" ]]; then pass decrypt_roundtrip "decrypted SHA-256 equals the archive's"; else fail decrypt_roundtrip "decrypted SHA-256 differs"; fi

enc_sha=$(sha256sum "$DUMP/robin.dump.gpg" | cut -d' ' -f1)
enc_bytes=$(stat -c %s "$DUMP/robin.dump.gpg")
printf '%s  robin.dump.gpg\n' "$enc_sha" > "$OUT/robin.dump.gpg.sha256"
cp "$DUMP/robin.dump.gpg" "$OUT/robin.dump.gpg"
for f in source-facts.txt source-fingerprints.txt source-inventory.txt pg_dump.log pg_dump.timing; do
  cp "$DUMP/$f" "$OUT/$f"
done
if (( ${#deferred[@]} > 0 )); then scope=partial; else scope=complete; fi

# ── 7. manifest (no host, user, credentials or row data) ──────────────
{
  echo "format|robin-backup-manifest-v1"
  echo "mode|$MODE"
  echo "scope|$scope"
  echo "deferred_table_data|$DEFER"
  echo "deferred_rows|${cmp_dfrows:-0}"
  echo "network_rx_bytes|$rx_used"
  echo "egress_cap_bytes|$CAP"
  echo "written_utc|$(date -u +%FT%TZ)"
  echo "image|${ROBIN_IMAGE:-unknown}"
  echo "image_id|${ROBIN_IMAGE_DIGEST:-unknown}"
  echo "pg_dump|$(pg_dump --version)"
  echo "gpg|$(gpg --version | head -n 1)"
  echo "port|$PGPORT"
  echo "database|$PGDATABASE"
  echo "sslmode|$PGSSLMODE"
  cat "$DUMP/source-facts.txt"
  cat "$DUMP/pg_dump.timing"
  echo "archive_format|custom (pg_dump -Fc, gzip level 6)"
  echo "archive_bytes|$plain_bytes"
  echo "archive_sha256|$plain_sha"
  echo "encryption|OpenPGP symmetric AES256, S2K iterated+salted SHA512 (65011712)"
  echo "encrypted_bytes|$enc_bytes"
  echo "encrypted_sha256|$enc_sha"
  echo "toc_entries|$toc_entries"
  echo "tables_fingerprinted|$fp_lines"
  echo "rows_fingerprinted|$(awk -F'|' '{ s += $2 } END { print s + 0 }' "$DUMP/source-fingerprints.txt")"
  echo "checks_pass|$(grep -c '^PASS' "$CHECKS" || true)"
  echo "checks_fail|$(grep -c '^FAIL' "$CHECKS" || true)"
  echo "checks_note|$(grep -c '^NOTE' "$CHECKS" || true)"
} > "$MANIFEST"

if (( FAILED )); then
  log "BACKUP FAILED: $(grep -c '^FAIL' "$CHECKS") check(s) failed; see checks.txt"
  exit 1
fi
if [[ $scope == partial ]]; then
  log "PARTIAL BACKUP OK: $(grep -c '^PASS' "$CHECKS") checks passed; encrypted archive $enc_bytes bytes; rows of ${DEFER//,/, } are NOT in it (this is not the complete backup)"
else
  log "BACKUP OK: $(grep -c '^PASS' "$CHECKS") checks passed; encrypted archive $enc_bytes bytes"
fi
