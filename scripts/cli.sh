#!/usr/bin/env bash
# Personal AI Job Agent — CLI wrapper (Feature 7 companion script).
#
# Talks to the backend's /cli REST surface. Requires a logged-in session
# cookie: run ./cli.sh login <email> <password> first.
#
# Usage:
#   ./cli.sh status
#   ./cli.sh login <email> <password>
#   ./cli.sh jobs [status] [query]
#   ./cli.sh job <job-id>
#   ./cli.sh cover-letter <job-id>
#   ./cli.sh answer <job-id> "<question>"
#   ./cli.sh ats
#   ./cli.sh notifications [unread]
set -euo pipefail

BASE_URL="${BACKEND_BASE_URL:-http://localhost:8080}"
COOKIE_JAR="${CLI_COOKIE_JAR:-$HOME/.jobagent-cookies}"

req() {
  curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H "Content-Type: application/json" "$@"
}

cmd="${1:-help}"
case "$cmd" in
  status)
    req "$BASE_URL/cli/status"; echo ;;
  login)
    # Login is CSRF-exempt in SecurityConfig; capture the session cookie.
    req -X POST "$BASE_URL/api/v1/auth/login" -d "{\"email\":\"${2:?email required}\",\"password\":\"${3:?password required}\"}"; echo ;;
  jobs)
    req "$BASE_URL/cli/jobs?limit=50${2:+&status=$2}${3:+&q=$3}"; echo ;;
  job)
    req "$BASE_URL/cli/jobs/${2:?job id required}"; echo ;;
  cover-letter)
    req -X POST "$BASE_URL/cli/cover-letter/${2:?job id required}"; echo ;;
  answer)
    req -X POST "$BASE_URL/cli/answer/${2:?job id required}?q=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "${3:?question required}" 2>/dev/null || printf '%s' "${3}")"; echo ;;
  ats)
    req "$BASE_URL/cli/ats"; echo ;;
  notifications)
    req "$BASE_URL/api/v1/notifications?limit=20${2:+&unread=true}"; echo ;;
  help|*)
    cat <<'EOF'
Commands:
  status                       service health summary
  login <email> <password>     authenticate and store the session cookie
  jobs [status] [query]        list jobs
  job <job-id>                 show job detail
  cover-letter <job-id>        generate a job-specific cover letter
  answer <job-id> "<question>" draft an application answer
  ats                          list ATS adapters
  notifications [unread]       show recent notifications
EOF
    ;;
esac
