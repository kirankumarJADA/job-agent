# Deployment guide

Target architecture. All hosted components have a free/hobby tier; none is
required — the local Docker stack remains the default dev environment.

| Component | Local (current stack) | Hosted target |
|---|---|---|
| Frontend | Vite dev server (compose) | **Vercel** (static build) |
| Backend | compose `backend` | **Render** (Docker) |
| PostgreSQL | compose `postgres` | **Supabase** |
| Redis | compose `redis` | **Upstash** (TLS) |
| Worker | compose `worker` profile | Render worker service (same image) |

The backend `prod` profile (`backend/src/main/resources/application-prod.yml`)
is fail-closed: `SPRING_DATASOURCE_URL` and `APP_CORS_ALLOWED_ORIGINS` have no
defaults, so a misconfigured deploy refuses to start instead of silently
pointing at localhost. CV PDFs are stored **in Postgres** (`files.content`
bytea) — no persistent disk is required on any host.

## 0. Prerequisites

- GitHub repo pushed (this one).
- One long random token for worker→backend auth: `openssl rand -hex 32`.

## 1. Supabase (database)

1. Create a project (free tier is fine for local-style use).
2. Copy the **Connection string → URI** for the *Session pooler* (port 5432)
   or *Transaction pooler* (6543). Supabase pools via PgBouncer; use the
   session pooler if you observe prepared-statement issues.
3. Convert to JDBC form (the backend appends the SSL mode):
   `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres`
4. Record: `SPRING_DATASOURCE_URL`, username (`postgres.<ref>`), password.
5. Flyway migrations run automatically on backend start
   (`baseline-on-migrate: true`); no manual SQL step.

FREE-TIER: Supabase pauses after ~1 week of inactivity on the free plan.

## 2. Upstash (Redis)

1. Create a Redis database (any region near the Render backend).
2. Record host + port (6379). TLS is always on; the backend enables it via
   `spring.data.redis.ssl.enabled=true` in the prod profile.
3. No code uses Redis at runtime today (events go through Postgres outbox);
   it is health-checked by actuator. Any in-memory Redis compatible with TLS
   works.

FREE-TIER: Upstash free tier is pay-per-command with a daily cap.

## 3. Render (backend, Docker)

1. New → Background Worker / Web Service → "Existing image" or Blueprint from
   the GitHub repo, Docker path `backend/Dockerfile`.
2. Environment:
   - `SPRING_PROFILES_ACTIVE=prod`
   - `SPRING_DATASOURCE_URL` (from Supabase, step 1)
   - `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
   - `SPRING_REDIS_HOST`, `SPRING_REDIS_PORT=6379`
   - `APP_CORS_ALLOWED_ORIGINS=https://<app>.vercel.app`
   - `WORKER_EVENT_TOKEN=<openssl rand -hex 32>`
   - Optional LLM: `NIM_BASE_URL`, `NIM_API_KEY`, `GEMINI_API_KEY`
3. Health check path: `/api/v1/system/health` (Render defaults to `/`;
   set it explicitly).
4. Port: 8080 (Render injects PORT; this app binds 8080 — set the health
   check and any proxy to 8080 or add `SERVER_PORT`).

FREE-TIER: Render free instances sleep after inactivity and have no
persistent disk; that is fine — DB storage means no disk is needed.

## 4. Vercel (frontend)

1. Import the repo; framework preset Vite; root directory `frontend`.
2. Environment: `VITE_API_BASE_URL=https://<render-backend>.onrender.com/api/v1`.
3. Deploy. The build bakes the URL into the bundle at build time — changing
   the backend URL requires a redeploy.
4. Cross-origin cookies: backend sets `SameSite=None; Secure` on both the
   session and XSRF cookies in prod. Browsers accept that only over HTTPS —
   Vercel and Render both provide it by default.

FREE-TIER: Vercel hobby is fine for a personal SPA.

## 5. Worker (Render worker service, optional until needed)

Same repo/image, Docker path `worker/Dockerfile`. The worker is idle-safe:
with no plan file it starts and waits; it never contacts an employer or
mailbox unless a plan is explicitly supplied. Environment:

- `WORKER_EVENT_URL=https://<render-backend>/api/v1/automation/events`
- `WORKER_EVENT_TOKEN=<same value as Render backend>`
- `WORKER_STATE_PATH=/data/worker-state.json`, `WORKER_ARTIFACT_DIR=/data/artifacts`
  (Render disk or ephemeral — state is a crash-recovery cache, not the
  source of truth; the backend DB owns durable state).

Safety is unchanged: CAPTCHA/anti-bot/access-control challenges remain hard
stops; submission requires the explicit approve path; no real employer is
contacted without a real plan supplied by a real operator.

## 6. Post-deployment verification

```bash
# 1. Backend health (Render URL)
curl -f https://<render-backend>/api/v1/system/health

# 2. Login works (browser at the Vercel URL; check Set-Cookie has
#    Secure; SameSite=None on JSESSIONID and XSRF-TOKEN)

# 3. Tailor a CV for any job and download the exact artifact:
#    POST /api/v1/resume-intelligence/tailor {"jobId":"..."}
#    GET  /api/v1/resume-intelligence/cv/<cvVersionId>/artifact
#    -> 200, application/pdf

# 4. Worker events auth (from the worker or curl):
curl -X POST https://<render-backend>/api/v1/automation/events \
  -H "Authorization: Bearer $WORKER_EVENT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"smoke-1","type":"HEARTBEAT","planId":"<existing-plan-id>"}'
# -> 200 {"accepted":true,...}; wrong/missing token -> 401

# 5. Flyway applied:
#    Render logs show "Successfully applied N migrations" on first boot.
```

## 7. Production vs free-tier requirements

| Concern | Free/hobby OK | Production requirement |
|---|---|---|
| DB | Supabase free | Paid tier for backups, PITR, no pausing |
| Redis | Upstash free | Paid for persistence + higher command limits |
| Backend | Render free (cold starts) | Paid: no sleep, autoscaling, better TLS |
| Frontend | Vercel hobby | Paid: analytics, domain, DDoS protection |
| Secrets | Render/Vercel env vars | Vault/rotation policy, least-privilege DB user |
| Email/mailbox | not configured | Dedicated mailbox + OAuth consent screens |
| Real submissions | never in testing | Legal/ToS review per employer before any real use |
