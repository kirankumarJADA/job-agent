#!/usr/bin/env bash
# Outbox export, run INSIDE a disposable postgres:17.11 container started by
# export-outbox.ps1. See scripts/db-migration/README.md ("Outbox export").
#
#   /work   scripts (container disk)
#   /dump   RAM-backed tmpfs, never swapped (memory.swap.max is 0): the
#           secrets, the transit key, the received chunks and the plaintext
#           archive before encryption
#   PGDATA  RAM-backed tmpfs: this container's own server (Unix socket only),
#           which decodes and verifies the chunks; it never talks to the source
#   /in/r2  optional, read-only: the R2 run folder, used only to prove that the
#           passphrase typed here is the one that encrypts the R2 archive
#   /out    container disk: ONLY the encrypted archive and metadata files
#
# Against the source this script runs one psql session, one REPEATABLE READ
# READ ONLY transaction (sql/outbox-export-session.sql). The rows are
# encoded, hashed, fingerprinted and compressed by the source server
# (pgcrypto, zlib level 9) so that far fewer bytes cross the network; the
# transit encryption uses a throwaway key and only serves as the compression
# container. The archive that leaves the container is a pg_dump of the decoded
# rows, restored and fingerprinted again here, then encrypted with gpg
# exactly like the R2 archive.
set -Eeuo pipefail
umask 077

WORK=/work
DUMP=/dump
OUT=/out
PGDATA=${PGDATA:-/var/lib/postgresql/data}
LOG=$OUT/export.log
CHECKS=$OUT/checks.txt
MANIFEST=$OUT/manifest.txt
mkdir -p "$OUT" "$DUMP/chunks"
: > "$LOG"
: > "$CHECKS"
FAILED=0
PARTIAL=0

log()  { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$LOG" >&2; }
pass() { printf 'PASS|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "PASS $1 ${2:-}"; }
fail() { printf 'FAIL|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "FAIL $1 ${2:-}"; FAILED=1; }
note() { printf 'NOTE|%s|%s\n' "$1" "${2:-}" >> "$CHECKS"; log "NOTE $1 ${2:-}"; }
cleanup_secrets() { rm -f "$DUMP/.pgpass" "$DUMP/.passphrase" "$DUMP/.tkey"; }
WATCHDOG=
stop_watchdog() {
  if [[ -n $WATCHDOG ]]; then kill "$WATCHDOG" 2>/dev/null || true; wait "$WATCHDOG" 2>/dev/null || true; WATCHDOG=; fi
}
trap 'cleanup_secrets; stop_watchdog' EXIT
abort() { fail "$1" "${2:-}"; log "export aborted"; exit 1; }
mb() { printf '%s.%02d' "$(( $1 / 1000000 ))" "$(( ($1 % 1000000) / 10000 ))"; }

MODE=${ROBIN_MODE:-}
case "$MODE" in
  live|dryrun) ;;
  *) echo "ROBIN_MODE must be 'live' or 'dryrun'" >&2; exit 2 ;;
esac
if [[ $MODE == live ]]; then
  for v in ROBIN_DRYRUN_PGPASSWORD ROBIN_DRYRUN_PASSPHRASE ROBIN_TEST_CORRUPT_CHUNK ROBIN_TEST_TAMPER_ROW; do
    [[ -z ${!v:-} ]] || { echo "refusing to run: $v is set in live mode" >&2; exit 2; }
  done
fi
for v in ROBIN_SRC_HOST ROBIN_SRC_PORT ROBIN_SRC_USER ROBIN_SRC_DB ROBIN_SRC_SSLMODE ROBIN_PARTS ROBIN_CHUNK_ROWS; do
  [[ -n ${!v:-} ]] || { echo "$v is required" >&2; exit 2; }
done
if [[ $MODE == live && $ROBIN_SRC_SSLMODE == disable ]]; then
  echo "refusing to run: live mode requires TLS (sslmode require or verify-full)" >&2; exit 2
fi
case $ROBIN_PARTS in pending|published|all) ;; *) echo "ROBIN_PARTS must be pending, published or all" >&2; exit 2 ;; esac
[[ $ROBIN_CHUNK_ROWS =~ ^[1-9][0-9]*$ ]] || { echo "ROBIN_CHUNK_ROWS must be a positive number" >&2; exit 2; }
CAP=${ROBIN_EGRESS_CAP_BYTES:-0}
[[ $CAP =~ ^[0-9]+$ ]] || { echo "ROBIN_EGRESS_CAP_BYTES must be a whole number of bytes" >&2; exit 2; }
if [[ $MODE == live ]] && (( CAP == 0 )); then
  echo "refusing to run: live mode needs an egress cap (ROBIN_EGRESS_CAP_BYTES)" >&2; exit 2
fi
export ROBIN_PARTS ROBIN_CHUNK_ROWS

# Source connection: only ever through prod_psql, never through the
# environment, so that no local command can reach the source by accident.
prod_psql() {
  local envs=(PGHOST="$ROBIN_SRC_HOST" PGPORT="$ROBIN_SRC_PORT" PGUSER="$ROBIN_SRC_USER" PGDATABASE="$ROBIN_SRC_DB"
              PGSSLMODE="$ROBIN_SRC_SSLMODE" PGPASSFILE="$DUMP/.pgpass" PGAPPNAME=robin-outbox-export PGCONNECT_TIMEOUT=20)
  [[ -n ${ROBIN_SRC_SSLROOTCERT:-} ]] && envs+=(PGSSLROOTCERT="$ROBIN_SRC_SSLROOTCERT")
  env "${envs[@]}" psql -X "$@"
}
LOCAL=(psql -X -q -At -F '|' -v ON_ERROR_STOP=1 -h /var/run/postgresql -U postgres)

net_ifaces() {
  local f
  for f in /sys/class/net/*/statistics/rx_bytes; do
    [[ -r $f && $f != /sys/class/net/lo/* ]] && printf '%s\n' "$f"
  done
  return 0
}
net_rx() {
  local total=0 f v
  while IFS= read -r f; do v=$(< "$f") || v=0; total=$(( total + v )); done < <(net_ifaces)
  printf '%s' "$total"
}
start_watchdog() { # every 0.1 s: enforce the cap as a hard limit; every 30 s: report
  (
    set +e
    last=0
    while :; do
      used=$(( $(net_rx) - RX0 ))
      printf '%(%s)T\n' -1 > "$DUMP/watchdog.beat"
      if (( CAP > 0 && used > CAP )); then
        printf '%s\n' "$used" > "$DUMP/egress-cap.hit"
        for p in /proc/[0-9]*; do [[ $(cat "$p/comm" 2>/dev/null) == psql ]] && kill -TERM "${p#/proc/}" 2>/dev/null; done
        log "EGRESS CAP REACHED: $(mb "$used") MB received, cap $(mb "$CAP") MB; psql was stopped"
        exit 0
      fi
      printf -v now '%(%s)T' -1
      if (( now - last >= 30 )); then log "network: $(mb "$used") MB received so far (cap $(mb "$CAP") MB)"; last=$now; fi
      sleep 0.1
    done
  ) &
  WATCHDOG=$!
}
read_secret() {
  local value
  printf '%s' "$1" > /dev/tty
  IFS= read -rs value < /dev/tty
  printf '\n' > /dev/tty
  printf '%s' "$value"
}
write_pgpass() {
  local esc=${1//\\/\\\\}
  esc=${esc//:/\\:}
  printf '*:*:*:*:%s\n' "$esc" > "$DUMP/.pgpass"
  chmod 600 "$DUMP/.pgpass"
}
fact() { awk -F'|' -v k="$1" '$1 == k { print substr($0, length(k) + 2); found = 1 } END { if (!found) print "" }' "$DUMP/export-facts.txt" 2>/dev/null; }

log "Robin outbox export ($MODE): $(psql --version); $(gpg --version | head -n 1)"
log "source: port $ROBIN_SRC_PORT, database $ROBIN_SRC_DB, sslmode $ROBIN_SRC_SSLMODE (host and user are not logged)"
log "parts: $ROBIN_PARTS; $ROBIN_CHUNK_ROWS rows per chunk; egress cap $(mb "$CAP") MB"

# ── 0. plaintext stays in RAM ─────────────────────────────────────────
swap_max=$(cat /sys/fs/cgroup/memory.swap.max 2>/dev/null || echo unknown)
not_tmpfs=""
for p in "$DUMP" "$PGDATA"; do
  [[ $(stat -fc %T "$p" 2>/dev/null || echo missing) == tmpfs ]] || not_tmpfs+="$p "
done
if [[ -z $not_tmpfs && $swap_max == 0 ]]; then
  pass plaintext_in_ram "$DUMP and $PGDATA are tmpfs and this container cannot swap (memory.swap.max=0)"
else
  abort plaintext_in_ram "not on tmpfs: ${not_tmpfs:-none}; memory.swap.max is $swap_max, so plaintext could reach a disk; nothing was read"
fi
pg_isready -q -h /var/run/postgresql -U postgres || abort local_server "the container's own server is not ready"
listen=$("${LOCAL[@]}" -d postgres -c "show listen_addresses")
[[ -z $listen ]] || abort local_server "the container's own server listens on '$listen'; it must use its Unix socket only"
pass local_server "decoding server: Unix socket only, data directory on tmpfs"

# ── 1. secrets, in tmpfs only; the passphrase is proven on R2 first ──
if [[ $MODE == live ]]; then
  [[ -t 0 ]] || { echo "live mode needs an interactive terminal (docker exec -it)" >&2; exit 2; }
  pw=$(read_secret "Database password for the export connection (input hidden): ")
  [[ -n $pw ]] || { echo "empty password" >&2; exit 2; }
  write_pgpass "$pw"; unset pw
  printf '\nPaste the encryption passphrase from your password manager (the one that encrypts the R2 archive).\n' > /dev/tty
  p1=$(read_secret "Encryption passphrase (input hidden): ")
  if (( ${#p1} < 16 )); then unset p1; abort passphrase "use at least 16 characters"; fi
  if [[ ! -s /in/r2/robin.dump.gpg ]]; then
    p2=$(read_secret "Repeat the passphrase: ")
    if [[ $p1 != "$p2" ]]; then unset p1 p2; abort passphrase "the two entries differ"; fi
    unset p2
  fi
  printf '%s' "$p1" > "$DUMP/.passphrase"; unset p1
else
  write_pgpass "$ROBIN_DRYRUN_PGPASSWORD"
  printf '%s' "$ROBIN_DRYRUN_PASSPHRASE" > "$DUMP/.passphrase"
  unset ROBIN_DRYRUN_PGPASSWORD ROBIN_DRYRUN_PASSPHRASE
fi
chmod 600 "$DUMP/.passphrase"
export GNUPGHOME="$DUMP/.gnupg"
mkdir -p -m 700 "$GNUPGHOME"
if [[ -s /in/r2/robin.dump.gpg && -s /in/r2/robin.dump.sha256 ]]; then
  log "checking the passphrase against the R2 archive (decrypted to a hash only, nothing is written)"
  r2_expected=$(cut -d' ' -f1 /in/r2/robin.dump.sha256)
  r2_actual=$(gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$DUMP/.passphrase" \
                  --decrypt /in/r2/robin.dump.gpg 2>> "$LOG" | sha256sum | cut -d' ' -f1) || true
  if [[ $r2_actual == "$r2_expected" ]]; then
    pass passphrase_is_r2s "it decrypts the R2 archive to its recorded SHA-256, so both archives share one passphrase"
  else
    abort passphrase_is_r2s "it does not decrypt the R2 archive; nothing was read from the source"
  fi
else
  [[ $MODE == live ]] && note passphrase_is_r2s "no R2 archive mounted: the passphrase was typed twice instead"
fi
head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$DUMP/.tkey"
chmod 600 "$DUMP/.tkey"

# ── 2. connect, measured from the first byte ──────────────────────────
if [[ -z $(net_ifaces) ]]; then
  (( CAP > 0 )) && abort egress_meter "cannot read this container's network counters, so the cap could not be enforced"
  note egress_meter "network counters unavailable; transfer not measured"
fi
RX0=$(net_rx)
printf '%s' "$RX0" > "$DUMP/rx0"
printf '%s' "$CAP" > "$DUMP/cap"
start_watchdog
for _ in 1 2 3 4 5 6 7 8 9 10; do [[ -s $DUMP/watchdog.beat ]] && break; sleep 0.5; done
if (( CAP > 0 )) && [[ ! -s $DUMP/watchdog.beat ]]; then abort egress_meter "the egress watchdog did not start"; fi
info=$(prod_psql -q -At -F '|' -c "select current_setting('server_version_num'), current_setting('default_transaction_read_only')") \
  || abort connect "psql could not connect (check host, user, password, sslmode)"
IFS='|' read -r vnum ro <<< "$info"
if (( vnum / 10000 == 17 )); then pass server_major "server_version_num=$vnum"; else abort server_major "expected PostgreSQL 17, got $vnum"; fi
log "default_transaction_read_only=$ro (the export transaction itself is READ ONLY)"

# ── 3. the export session: one read-only snapshot ─────────────────────
FP_ALL=false; [[ ${ROBIN_FINGERPRINT_ALL:-0} == 1 ]] && FP_ALL=true
log "opening the export session (fingerprint every table: $FP_ALL)"
set +e
prod_psql -q -At -F '|' -v ON_ERROR_STOP=1 -v fp_all="$FP_ALL" -f "$WORK/outbox-export-session.sql" 2>&1 | tee -a "$LOG" >&2
session_rc=${PIPESTATUS[0]}
set -e
stop_watchdog
rx_used=$(( $(net_rx) - RX0 ))
rm -f "$DUMP/.pgpass"
cp "$DUMP/export-facts.txt" "$OUT/source-facts.txt" 2>/dev/null || true
[[ -s $DUMP/export-facts-end.txt ]] && cat "$DUMP/export-facts-end.txt" >> "$OUT/source-facts.txt"
grep -q '^FAIL|export_plan|' "$CHECKS" && abort export_plan "the source did not match what the encoder expects; no row was read"
[[ -s $DUMP/export-facts.txt ]] || abort snapshot_session "the session ended before recording its facts (exit $session_rc)"
if [[ -s $DUMP/watchdog.lost ]]; then abort egress_meter "the egress watchdog stopped during the export"; fi

if (( CAP > 0 )); then
  if (( rx_used <= CAP )); then pass egress_within_cap "$(mb "$rx_used") MB received ($rx_used bytes), cap $(mb "$CAP") MB"
  else fail egress_within_cap "$(mb "$rx_used") MB received ($rx_used bytes), over the cap of $(mb "$CAP") MB"; fi
else
  note network_received "$(mb "$rx_used") MB received ($rx_used bytes), no cap"
fi
[[ $(fact transaction_read_only) == on ]] && pass source_read_only "the export transaction was READ ONLY, REPEATABLE READ"
# Every table against R2: public must be unchanged for R2 + this export to
# be one consistent copy. Supabase-owned schemas may change on their own.
if [[ $FP_ALL == true ]]; then
  if [[ -s $DUMP/source-fingerprints.txt ]]; then
    cp "$DUMP/source-fingerprints.txt" "$OUT/source-fingerprints.txt"
    if [[ -s /in/r2/source-fingerprints.txt ]]; then
      IFS='|' read -r fp_same fp_pub fp_other < <(awk -F'|' -v out="$OUT/fingerprint-comparison.txt" '
        FNR == NR { r2[$1] = $2 "|" $3; next }
        { now[$1] = $2 "|" $3 }
        END {
          for (t in now) {
            if (!(t in r2)) { tag = "NEW"; } else if (r2[t] != now[t]) { tag = "CHANGED"; } else { same++; continue }
            print tag "|" t "|r2=" (t in r2 ? r2[t] : "") "|now=" now[t] > out
            if (t ~ /^public\./) pub++; else other++
          }
          for (t in r2) if (!(t in now)) { print "MISSING|" t "|r2=" r2[t] > out; if (t ~ /^public\./) pub++; else other++ }
          printf "%d|%d|%d\n", same + 0, pub + 0, other + 0
        }' /in/r2/source-fingerprints.txt "$DUMP/source-fingerprints.txt")
      touch "$OUT/fingerprint-comparison.txt"
      if (( fp_pub == 0 )); then
        pass public_unchanged_since_r2 "$fp_same tables equal R2's row count and fingerprint; no public table differs"
      else
        fail public_unchanged_since_r2 "$fp_pub public table(s) differ from R2 (fingerprint-comparison.txt): R2 + this export would not be one consistent copy"
      fi
      (( fp_other > 0 )) && note platform_tables_since_r2 "$fp_other Supabase-owned table(s) differ from R2 (fingerprint-comparison.txt)"
    else
      note public_unchanged_since_r2 "every table was fingerprinted, but no R2 fingerprints are mounted to compare with"
    fi
  else
    fail table_fingerprints "requested, but the session recorded none"
  fi
fi
t0=$(fact temp_bytes_start); t1=$(awk -F'|' '$1 == "temp_bytes_end" { print $2 }' "$DUMP/export-facts-end.txt" 2>/dev/null || true)
if [[ -n $t0 && -n $t1 ]]; then
  if [[ $t0 == "$t1" ]]; then pass source_temp_files "no temporary files were written on the source during the export"
  else note source_temp_files "the source's temp_bytes grew by $(( t1 - t0 )) bytes during the export (any session counts)"; fi
fi

touch "$DUMP/chunks.done" "$DUMP/parts.complete" "$DUMP/parts.overflow"
if [[ -s $DUMP/egress-cap.hit ]]; then
  PARTIAL=1; note egress_cap "the hard cap stopped psql; chunks that had fully arrived are kept, the one in flight is discarded"
fi
if [[ -s $DUMP/budget.stop ]]; then
  PARTIAL=1; read -r bp bc bu bn < "$DUMP/budget.stop"
  note egress_budget "stopped cleanly before part $bp chunk $bc: $(mb "$bu") MB received, the next chunk could need $(mb "$bn") MB"
fi
if [[ -s $DUMP/parts.overflow ]]; then fail export_plan "parts needing more chunks than planned: $(paste -sd, "$DUMP/parts.overflow")"; fi
if (( session_rc != 0 && ! PARTIAL )); then fail snapshot_session "psql exited with $session_rc (see export.log)"; fi
case $ROBIN_PARTS in pending) want=(D A1 S B1) ;; published) want=(D B2) ;; all) want=(D A1 S B1 B2) ;; esac
complete=(); missing=()
for p in "${want[@]}"; do
  if grep -qx "$p" "$DUMP/parts.complete"; then complete+=("$p"); else missing+=("$p"); PARTIAL=1; fi
done
chunks=$(wc -l < "$DUMP/chunks.done")
log "received: $chunks chunk(s); complete parts: ${complete[*]:-none}; incomplete: ${missing[*]:-none}"
(( chunks > 0 )) || abort nothing_received "no chunk arrived; nothing to keep"
grep -qx D "$DUMP/parts.complete" || abort job_dictionary "the job dictionary did not arrive, so no chunk can be decoded"
if [[ -s $DUMP/inflight ]]; then note chunk_in_flight "discarded the incomplete chunk $(cat "$DUMP/inflight")"; fi

# ── 4. decode and verify every chunk on the container's own server ───
"${LOCAL[@]}" -d postgres -c 'CREATE DATABASE robin_outbox' > /dev/null
"${LOCAL[@]}" -d robin_outbox -f "$WORK/outbox-decode.sql" > /dev/null
LDB=("${LOCAL[@]}" -d robin_outbox)
while read -r p c b; do
  f=$(printf '%s/chunks/%s-%04d.bin' "$DUMP" "$p" "$c")
  if [[ $MODE == dryrun && ${ROBIN_TEST_CORRUPT_CHUNK:-} == "$p $c" ]]; then
    log "TEST: corrupting one byte in the middle of $p chunk $c"
    size=$(stat -c %s "$f"); off=$(( size / 2 ))
    printf '\x5a' | dd of="$f" bs=1 seek="$off" conv=notrunc status=none
  fi
  "${LDB[@]}" -c "\\copy robin_export.chunk_blob from '$f' with (format binary)" > /dev/null \
    || abort chunk_load "$p chunk $c is not a complete COPY row"
done < "$DUMP/chunks.done"
decode_failed=0
for p in D S A1 B1 B2; do
  while read -r q c b; do
    [[ $q == "$p" ]] || continue
    # psql substitutes :'key' only in scripts, not in -c; the key is read
    # from tmpfs inside psql, so it never appears on a command line.
    if ! out=$("${LDB[@]}" 2>> "$LOG" <<SQL
\\set key \`cat $DUMP/.tkey\`
select server_rows, decoded_rows, server_check, decoded_check from robin_export.decode_chunk('$p', $c, :'key');
SQL
    ); then
      fail chunk_decode "$p chunk $c could not be decrypted or decoded (see export.log)"; decode_failed=1; continue
    fi
    IFS='|' read -r sr dr sc dc <<< "$out"
    if [[ $sr != "$dr" || $sc != "$dc" ]]; then
      fail chunk_verify "$p chunk $c: source $sr rows / $sc, decoded $dr rows / $dc"; decode_failed=1
    fi
  done < "$DUMP/chunks.done"
done
rm -f "$DUMP/.tkey"
(( decode_failed == 0 )) && pass chunks_verified "$chunks chunk(s): body SHA-256, row count and fingerprint equal the source's for every one"

q() { "${LDB[@]}" -c "$1"; }
part_sum() { q "select coalesce(sum(server_rows),0)||'|'||coalesce(sum(server_check),0)||'|'||coalesce(sum(blob_bytes),0)||'|'||count(*) from robin_export.chunk_check where part = '$1'"; }
declare -A P_ROWS P_CHECK P_BYTES P_CHUNKS
for p in D A1 S B1 B2; do
  IFS='|' read -r "P_ROWS[$p]" "P_CHECK[$p]" "P_BYTES[$p]" "P_CHUNKS[$p]" <<< "$(part_sum "$p")"
done
jobs_rows=$(fact jobs_rows); pend_jd=$(fact pending_job_discovered); pend_other=$(fact pending_other)
[[ ${P_ROWS[D]} == "$jobs_rows" ]] && pass job_dictionary "${P_ROWS[D]} job ids (public.jobs has $jobs_rows)" \
  || fail job_dictionary "${P_ROWS[D]} job ids, public.jobs has $jobs_rows"
if grep -qx A1 "$DUMP/parts.complete"; then
  [[ ${P_ROWS[A1]} == "$pend_other" ]] && pass part_A1 "${P_ROWS[A1]} pending rows of other types ($(fact pending_other_by_type)), exact" \
    || fail part_A1 "${P_ROWS[A1]} rows, the snapshot has $pend_other"
fi
if grep -qx B1 "$DUMP/parts.complete"; then
  [[ ${P_ROWS[B1]} == "$pend_jd" ]] && pass part_B1 "${P_ROWS[B1]} pending job.discovered rows in ${P_CHUNKS[B1]} chunks, exact" \
    || fail part_B1 "${P_ROWS[B1]} rows, the snapshot has $pend_jd"
fi
if grep -qx S "$DUMP/parts.complete"; then
  [[ ${P_CHECK[S]} == "$pend_jd" ]] && pass part_S "${P_ROWS[S]} jobs carry the ${P_CHECK[S]} pending job.discovered events" \
    || fail part_S "the summary counts ${P_CHECK[S]} events, the snapshot has $pend_jd"
  if grep -qx B1 "$DUMP/parts.complete"; then
    s_cmp=$(q "select count(*) from (select aggregate_id, count(*) n, min(sequence_id) f, max(sequence_id) l from public.outbox_events where published_at is null and event_type = 'job.discovered' group by 1) x full join robin_export.pending_job_summary s on s.job_id = x.aggregate_id where s.job_id is null or x.aggregate_id is null or s.events <> x.n or s.first_sequence_id <> x.f or s.last_sequence_id <> x.l")
    [[ $s_cmp == 0 ]] && pass summary_matches_rows "the per-job summary equals the exported job.discovered rows" \
      || fail summary_matches_rows "$s_cmp job(s) differ between the summary and the rows"
  fi
fi
if grep -qx B2 "$DUMP/parts.complete"; then
  pass part_B2 "${P_ROWS[B2]} published rows in ${P_CHUNKS[B2]} chunks, exact"
fi
PEND_ROWS=$(( ${P_ROWS[A1]:-0} + ${P_ROWS[B1]:-0} ))
PEND_FP=$(q "select ${P_CHECK[A1]:-0} + ${P_CHECK[B1]:-0}")
PUB_ROWS=${P_ROWS[B2]:-0}; PUB_FP=${P_CHECK[B2]:-0}
# The R2 snapshot recorded the whole table's row count and fingerprint.
if [[ -n ${ROBIN_R2_OUTBOX:-} ]]; then
  IFS='|' read -r r2_rows r2_fp <<< "$ROBIN_R2_OUTBOX"
  if grep -qx A1 "$DUMP/parts.complete" && grep -qx B1 "$DUMP/parts.complete" && grep -qx B2 "$DUMP/parts.complete"; then
    tot_rows=$(( PEND_ROWS + PUB_ROWS )); tot_fp=$(q "select $PEND_FP + $PUB_FP")
    [[ $tot_rows == "$r2_rows" && $tot_fp == "$r2_fp" ]] \
      && pass equals_r2_snapshot "all $tot_rows rows; fingerprint $tot_fp equals the one recorded in the R2 snapshot" \
      || fail equals_r2_snapshot "$tot_rows rows / $tot_fp; the R2 snapshot recorded $r2_rows / $r2_fp"
  else
    note equals_r2_snapshot "not every part is in this run; pending $PEND_ROWS rows / $PEND_FP, published $PUB_ROWS rows / $PUB_FP (the sums of all runs must equal $r2_rows / $r2_fp)"
  fi
fi
if [[ -n ${ROBIN_R2_PENDING:-} ]] && grep -qx A1 "$DUMP/parts.complete" && grep -qx B1 "$DUMP/parts.complete"; then
  [[ $PEND_ROWS == "$ROBIN_R2_PENDING" ]] && pass pending_equals_r2 "$PEND_ROWS pending rows, as in the R2 snapshot" \
    || fail pending_equals_r2 "$PEND_ROWS pending rows, the R2 snapshot had $ROBIN_R2_PENDING"
fi

if [[ $MODE == dryrun && -n ${ROBIN_TEST_TAMPER_ROW:-} ]]; then
  log "TEST: changing one decoded row before the archive is written"
  q "update public.outbox_events set attempt_count = attempt_count + 1 where id = (select min(id::text)::uuid from public.outbox_events)" > /dev/null
fi

# ── 5. the archive, restored and fingerprinted again ──────────────────
pg_dump -Fc -h /var/run/postgresql -U postgres -d robin_outbox \
        -t public.outbox_events -t robin_export.pending_job_summary -t robin_export.chunk_check \
        -f "$DUMP/outbox-export.dump" 2>> "$LOG" || abort pg_dump "pg_dump of the decoded rows failed"
"${LOCAL[@]}" -d postgres -c 'DROP DATABASE robin_outbox' > /dev/null
"${LOCAL[@]}" -d postgres -c 'CREATE DATABASE robin_verify' > /dev/null
VDB=("${LOCAL[@]}" -d robin_verify)
"${VDB[@]}" -c 'CREATE SCHEMA robin_export' > /dev/null
pg_restore -h /var/run/postgresql -U postgres -d robin_verify --exit-on-error "$DUMP/outbox-export.dump" 2>> "$LOG" \
  || abort archive_restore "the archive does not restore into an empty database"
fp_out=$("${VDB[@]}" <<'SQL'
SET TimeZone = 'UTC'; SET extra_float_digits = 1; SET bytea_output = 'hex'; SET IntervalStyle = 'postgres'; SET DateStyle = 'ISO, MDY';
SELECT (t.published_at IS NOT NULL), count(*), coalesce(sum(('x' || substr(md5(to_jsonb(t)::text), 1, 15))::bit(60)::bigint), 0)
  FROM public.outbox_events t GROUP BY 1 ORDER BY 1;
SQL
)
r_pend_rows=0; r_pend_fp=0; r_pub_rows=0; r_pub_fp=0
while IFS='|' read -r pub n fpv; do
  [[ -z $pub ]] && continue
  if [[ $pub == t ]]; then r_pub_rows=$n; r_pub_fp=$fpv; else r_pend_rows=$n; r_pend_fp=$fpv; fi
done <<< "$fp_out"
if [[ $r_pend_rows == "$PEND_ROWS" && $r_pend_fp == "$PEND_FP" && $r_pub_rows == "$PUB_ROWS" && $r_pub_fp == "$PUB_FP" ]]; then
  pass archive_restores_exactly "restored into a fresh database: pending $r_pend_rows rows / $r_pend_fp, published $r_pub_rows rows / $r_pub_fp, equal to the source"
else
  fail archive_restores_exactly "restored pending $r_pend_rows / $r_pend_fp and published $r_pub_rows / $r_pub_fp; the source sent pending $PEND_ROWS / $PEND_FP and published $PUB_ROWS / $PUB_FP"
fi
sum_rows=$("${VDB[@]}" -c "select count(*)||'|'||coalesce(sum(events),0) from robin_export.pending_job_summary")
log "restored summary: $sum_rows (jobs|events)"
pg_restore --list "$DUMP/outbox-export.dump" > "$OUT/toc.txt" || abort toc "pg_restore --list failed"
"${LOCAL[@]}" -d postgres -c 'DROP DATABASE robin_verify' > /dev/null

# ── 6. encrypt with the R2 passphrase, prove decryption ───────────────
plain_sha=$(sha256sum "$DUMP/outbox-export.dump" | cut -d' ' -f1)
plain_bytes=$(stat -c %s "$DUMP/outbox-export.dump")
printf '%s  outbox-export.dump\n' "$plain_sha" > "$OUT/outbox-export.dump.sha256"
gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$DUMP/.passphrase" \
    --symmetric --cipher-algo AES256 --s2k-mode 3 --s2k-digest-algo SHA512 --s2k-count 65011712 \
    --compress-algo none --no-symkey-cache \
    --output "$DUMP/outbox-export.dump.gpg" "$DUMP/outbox-export.dump" 2>> "$LOG" || abort encrypt "gpg failed"
dec_sha=$(gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$DUMP/.passphrase" \
              --decrypt "$DUMP/outbox-export.dump.gpg" 2>> "$LOG" | sha256sum | cut -d' ' -f1)
rm -f "$DUMP/.passphrase" "$DUMP/outbox-export.dump"
[[ $dec_sha == "$plain_sha" ]] && pass decrypt_roundtrip "decrypted SHA-256 equals the archive's" || fail decrypt_roundtrip "decrypted SHA-256 differs"
enc_sha=$(sha256sum "$DUMP/outbox-export.dump.gpg" | cut -d' ' -f1)
enc_bytes=$(stat -c %s "$DUMP/outbox-export.dump.gpg")
printf '%s  outbox-export.dump.gpg\n' "$enc_sha" > "$OUT/outbox-export.dump.gpg.sha256"
cp "$DUMP/outbox-export.dump.gpg" "$OUT/outbox-export.dump.gpg"
cp "$DUMP/chunks.done" "$OUT/chunks-received.txt"
cp "$DUMP/parts.complete" "$OUT/parts-complete.txt"

# ── 7. manifest (no host, user, credentials or row data) ──────────────
status=complete; (( PARTIAL )) && status=partial
{
  echo "format|robin-outbox-export-manifest-v1"
  echo "mode|$MODE"
  echo "parts_requested|$ROBIN_PARTS (${want[*]})"
  echo "parts_complete|${complete[*]:-}"
  echo "parts_incomplete|${missing[*]:-}"
  echo "status|$status"
  echo "chunk_rows|$ROBIN_CHUNK_ROWS"
  for p in D A1 S B1 B2; do
    echo "part_$p|chunks=${P_CHUNKS[$p]:-0} rows=${P_ROWS[$p]:-0} check=${P_CHECK[$p]:-0} received_bytes=${P_BYTES[$p]:-0}"
  done
  echo "pending_rows|$PEND_ROWS"
  echo "pending_fingerprint|$PEND_FP"
  echo "published_rows|$PUB_ROWS"
  echo "published_fingerprint|$PUB_FP"
  echo "r2_outbox|${ROBIN_R2_OUTBOX:-}"
  echo "network_rx_bytes|$rx_used"
  echo "egress_cap_bytes|$CAP"
  echo "written_utc|$(date -u +%FT%TZ)"
  echo "image|${ROBIN_IMAGE:-unknown}"
  echo "image_id|${ROBIN_IMAGE_DIGEST:-unknown}"
  echo "psql|$(psql --version)"
  echo "gpg|$(gpg --version | head -n 1)"
  echo "port|$ROBIN_SRC_PORT"
  echo "database|$ROBIN_SRC_DB"
  echo "sslmode|$ROBIN_SRC_SSLMODE"
  cat "$OUT/source-facts.txt"
  echo "transfer_format|robin-outbox-compact-v1 (sql/outbox-encode-rows.sql), zlib level 9 inside pgcrypto, throwaway AES-256 transit key"
  echo "archive_format|custom (pg_dump -Fc): public.outbox_events, robin_export.pending_job_summary, robin_export.chunk_check"
  echo "archive_bytes|$plain_bytes"
  echo "archive_sha256|$plain_sha"
  echo "encryption|OpenPGP symmetric AES256, S2K iterated+salted SHA512 (65011712)"
  echo "encrypted_bytes|$enc_bytes"
  echo "encrypted_sha256|$enc_sha"
  echo "checks_pass|$(grep -c '^PASS' "$CHECKS" || true)"
  echo "checks_fail|$(grep -c '^FAIL' "$CHECKS" || true)"
  echo "checks_note|$(grep -c '^NOTE' "$CHECKS" || true)"
} > "$MANIFEST"

if (( FAILED )); then
  log "OUTBOX EXPORT FAILED: $(grep -c '^FAIL' "$CHECKS") check(s) failed; see checks.txt"
  exit 1
fi
if (( PARTIAL )); then
  log "OUTBOX EXPORT PARTIAL: $(grep -c '^PASS' "$CHECKS") checks passed; complete parts ${complete[*]:-none}, incomplete ${missing[*]:-none}; every row in the archive is verified"
  exit 4
fi
log "OUTBOX EXPORT OK: $(grep -c '^PASS' "$CHECKS") checks passed; parts ${complete[*]}; encrypted archive $enc_bytes bytes"
