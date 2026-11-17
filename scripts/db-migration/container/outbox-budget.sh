#!/usr/bin/env bash
# Egress bookkeeping for an outbox export, called by /dump/plan.sql (via \!)
# inside the export session.
#
#   check PART CHUNK ESTIMATE  exit 0 if the next chunk fits in the cap, else
#                              write /dump/budget.stop and exit 3 (the plan
#                              then ends the session before reading it)
#   done PART CHUNK            the chunk's COPY row arrived completely
#   complete PART              every chunk of the part was exported
#   overflow PART              the part needed more chunks than planned
#
# "Fits" means: bytes received by this container so far, plus the larger of
# ESTIMATE and 1.25 x the largest chunk of this part received so far, plus
# 256 KB, stays within the cap. The watchdog in export-outbox.sh still
# enforces the cap as a hard limit.
set -Euo pipefail
DUMP=/dump
LOG=/out/export.log
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$LOG" >&2; }
net_rx() {
  local total=0 f v
  for f in /sys/class/net/*/statistics/rx_bytes; do
    [[ -r $f && $f != /sys/class/net/lo/* ]] || continue
    v=$(< "$f") || v=0
    total=$(( total + v ))
  done
  printf '%s' "$total"
}
mb() { printf '%s.%02d' "$(( $1 / 1000000 ))" "$(( ($1 % 1000000) / 10000 ))"; }

cmd=${1:-}; part=${2:-}; chunk=${3:-}
[[ $part =~ ^(D|A1|S|B1|B2)$ ]] || { echo "outbox-budget.sh: bad part '$part'" >&2; exit 2; }
case $cmd in
  check)
    est=${4:-0}
    cap=$(< "$DUMP/cap"); rx0=$(< "$DUMP/rx0")
    used=$(( $(net_rx) - rx0 ))
    largest=0
    while read -r p c b; do
      [[ $p == "$part" ]] && (( b > largest )) && largest=$b
    done < <(cat "$DUMP/chunks.done" 2>/dev/null || true)
    next=$(( largest * 5 / 4 ))
    (( next < est )) && next=$est
    need=$(( used + next + 262144 ))
    if (( cap > 0 && need > cap )); then
      printf '%s %s %s %s\n' "$part" "$chunk" "$used" "$next" > "$DUMP/budget.stop"
      log "budget: stopping before $part chunk $chunk: $(mb "$used") MB received, next chunk up to $(mb "$next") MB, cap $(mb "$cap") MB"
      exit 3
    fi
    printf '%s %s\n' "$part" "$chunk" > "$DUMP/inflight"
    exit 0 ;;
  done)
    f=$(printf '%s/chunks/%s-%04d.bin' "$DUMP" "$part" "$chunk")
    [[ -s $f ]] || { log "budget: $f is missing after its COPY"; exit 2; }
    bytes=$(stat -c %s "$f")
    printf '%s %s %s\n' "$part" "$chunk" "$bytes" >> "$DUMP/chunks.done"
    rm -f "$DUMP/inflight"
    cap=$(< "$DUMP/cap"); rx0=$(< "$DUMP/rx0")
    log "received $part chunk $chunk: $(mb "$bytes") MB; $(mb "$(( $(net_rx) - rx0 ))") MB so far (cap $(mb "$cap") MB)"
    exit 0 ;;
  complete)
    printf '%s\n' "$part" >> "$DUMP/parts.complete"
    log "part $part complete"
    exit 0 ;;
  overflow)
    printf '%s\n' "$part" >> "$DUMP/parts.overflow"
    log "part $part needed more chunks than planned"
    exit 0 ;;
  *) echo "outbox-budget.sh: unknown command '$cmd'" >&2; exit 2 ;;
esac
