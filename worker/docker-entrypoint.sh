#!/bin/sh
# Worker container entrypoint.
#
# Responsibilities:
#   1. Apply environment defaults so a misconfigured deployment fails loudly
#      instead of silently polling the wrong place.
#   2. Start the worker and FORWARD SIGTERM/SIGINT to it, so the in-process
#      graceful shutdown runs (cooperative step-boundary abort; the active
#      plan is left RUNNING for the backend's stale-plan sweeper).
#   3. Enforce a bounded grace period, then SIGKILL — a hung Chromium must
#      never block container termination past the platform's own deadline.
set -eu

: "${WORKER_POLL_INTERVAL_MS:=10000}"
export WORKER_POLL_INTERVAL_MS

if [ ! -w /data ] 2>/dev/null; then
  echo "entrypoint: WARNING - /data is not writable by $(id -u); worker state and artifacts will fail to persist" >&2
fi

node src/worker.js &
WORKER_PID=$!

shutdown() {
  signal="$1"
  echo "entrypoint: forwarding ${signal} to worker pid ${WORKER_PID}"
  kill -TERM "${WORKER_PID}" 2>/dev/null || true
  waited=0
  while kill -0 "${WORKER_PID}" 2>/dev/null && [ "${waited}" -lt 40 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "${WORKER_PID}" 2>/dev/null; then
    echo "entrypoint: graceful shutdown exceeded ${waited}s - sending SIGKILL" >&2
    kill -KILL "${WORKER_PID}" 2>/dev/null || true
  fi
}
trap 'shutdown TERM' TERM
trap 'shutdown INT' INT

# `wait` is interrupted by the trapped signals, which run the shutdown
# handler; when the node process exits (gracefully or after the kill),
# wait returns its exit code and the container terminates.
wait "${WORKER_PID}"
exit $?
