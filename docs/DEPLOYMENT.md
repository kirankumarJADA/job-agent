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
5. Flyway migrations run automatically on backend start with
   `baseline-on-migrate: false`, so a clean database executes the complete
   `V001 -> V019` chain in order. Do not use baseline-on-migrate for a new
   Job Agent database: it can mark V001 as applied while leaving `users`
   absent, causing V002 to fail.

### Recovering a failed new database baseline

Only use this recovery on the newly-created Job Agent database that failed
while creating a Flyway baseline, after confirming it contains no application
tables or user data. The application never creates tables manually. First
inspect the state with read-only queries:

```sql
select version, type, success from public.flyway_schema_history order by installed_rank;
select to_regclass('public.users') as users_table;
```

If the only Flyway history row is the failed/empty baseline at version `1`
and `public.users` is null, remove only the Flyway metadata table, then restart
Render with the updated production image:

```sql
do $$
declare
  history_rows integer;
  baseline_rows integer;
begin
  select count(*), count(*) filter (where type = 'BASELINE')
    into history_rows, baseline_rows
    from public.flyway_schema_history;
  if to_regclass('public.users') is null and history_rows = 1 and baseline_rows = 1 then
    drop table public.flyway_schema_history;
  else
    raise exception 'Refusing automatic recovery: database is not an empty baseline-only Job Agent database';
  end if;
end $$;
```

Flyway will then create its history table and apply `V001` through the latest
migration. Do not run this on a database with existing application tables or
user data; stop and perform a DBA-reviewed recovery instead. No tables are
manually created and no unrelated database is touched.

FREE-TIER: Supabase pauses after ~1 week of inactivity on the free plan.

## 2. Upstash (Redis)

1. Create a Redis database (any region near the Render backend).
2. Record host + port (6379). TLS is always on; the backend enables it via
   `spring.data.redis.ssl.enabled=true` in the prod profile. Set
   `SPRING_REDIS_SSL=true` (or the canonical `SPRING_DATA_REDIS_SSL_ENABLED=true`)
   in Render so the effective Lettuce factory uses TLS. Set
   `SPRING_REDIS_PASSWORD` to the Upstash database token; Upstash uses that
   token as the Redis password for TCP clients. The token is never logged.
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
   - `SPRING_REDIS_HOST`, `SPRING_REDIS_PORT=6379`, `SPRING_REDIS_PASSWORD`, `SPRING_REDIS_SSL=true`
   - `APP_CORS_ALLOWED_ORIGINS=https://<app>.vercel.app` — comma-separated,
     **exactly** the real frontend origin(s). Never `*` (credentials are
     allowed), and never a localhost origin: the prod profile sets
     `app.cors.allow-localhost=false`, so loopback origins are dropped from the
     effective allowlist and logged at startup instead of being trusted.
     `APP_CORS_ALLOW_LOCALHOST=true` exists only for the local
     production-like stack (`infra/docker-compose.prod-like.yml`).
   - `WORKER_EVENT_TOKEN=<openssl rand -hex 32>` — **mandatory in production**.
     When set, `/api/v1/automation/events` requires the bearer token and every
     worker-only operation is worker-only. When unset, the backend keeps the
     local-development behaviour in which worker operations that have no
     per-row owner — notably `POST /api/v1/automation/plans/recover-stale`,
     which resets stale RUNNING plans globally — remain reachable by any
     signed-in session. That mode exists so nothing breaks before the worker
     is configured; a production deployment must not rely on it. If the
     variable was missed, the deploy still starts (fail-open is confined to
     that one endpoint family) — so check it: `docker run --rm curlimages/curl
     -s -o /dev/null -w '%{http_code}' -X POST
     https://<render-backend>/api/v1/automation/plans/recover-stale` against a
     session-less request must return 401, not 403-after-auth.
   - **Firebase Admin credentials** — required for authentication to work at
     all. Firebase console → Project settings → Service accounts → *Generate new
     private key*, then copy three fields out of the downloaded JSON:
     - `FIREBASE_PROJECT_ID=<project id>`
     - `FIREBASE_CLIENT_EMAIL=<client_email>`
     - `FIREBASE_PRIVATE_KEY="<private_key>"` — paste the whole PEM including
       the `-----BEGIN/END PRIVATE KEY-----` lines. Escaped `\n` sequences are
       restored automatically, so a single-line value works.

     If these are absent the backend still starts (so the database, health check
     and every non-auth endpoint keep working), but Firebase sign-in answers
     `503` with a message naming whichever variable is missing. It never degrades
     into allowing unverified requests.

     **Email verification and existing accounts.** A Firebase credential whose
     email Firebase has not verified is never linked to an existing local
     account: anyone can create a Firebase account naming someone else's
     address, and linking on the address alone would hand over that account.
     Such a sign-in attempt answers `403` with a "verify your email" message
     and the caller can retry after following Firebase's verification email.
     Brand-new sign-ups are unaffected (they create their own account through
     the invite gate). This is enforced server-side in
     `FirebaseUserService.signIn` — the frontend never decides it.
   - **Registration gating** — `APP_REGISTRATION_INVITE_CODE=<openssl rand -hex 24>`.
     The prod profile defaults `APP_REQUIRE_INVITE_CODE=true`, so **leaving the
     invite code unset disables new sign-ups rather than opening registration**.
     Signing in to an existing account is unaffected either way. Generate a code
     you are willing to share with the people you want to invite; treat it as a
     shared secret and rotate it by changing the value and redeploying.
   - Optional LLM: `NIM_BASE_URL`, `NIM_API_KEY`, `GEMINI_API_KEY`
3. Health check path: `/api/v1/system/health` (Render defaults to `/`;
   set it explicitly).
4. Port: 8080 (Render injects PORT; this app binds 8080 — set the health
   check and any proxy to 8080 or add `SERVER_PORT`).

FREE-TIER: Render free instances sleep after inactivity and have no
persistent disk; that is fine — DB storage means no disk is needed.

### Build/version observability

`GET /actuator/info` reports which artifact is running: `build.name`,
`build.version` and `build.time` come from `META-INF/build-info.properties`,
and a `git` section (branch, commit id, commit time) appears when
`git.properties` was generated. The endpoint stays deliberately narrow —
`management.info.env.enabled=false` keeps environment properties (which can
carry datasource URLs and credentials) out of it, and the git plugin writes
an explicit key allowlist so committer identity, author email and remote URLs
never enter the image.

The Docker build stage copies only `pom.xml` and `src`, so it has no `.git`:
it therefore reports build metadata but no commit SHA. To surface the exact
deployed SHA in the image, either add `COPY .git .git` to the build stage in
`backend/Dockerfile` (valid only when Render's Docker build context is the
repository root) or pass the revision in as a build argument. Until then
treat `/actuator/info` as `version known, commit unknown`.

## 4. Vercel (frontend)

1. Import the repo; framework preset Vite; root directory `frontend`.
2. Environment:
   - `VITE_API_BASE_URL=https://<render-backend>.onrender.com/api/v1`
   - Firebase **web app** configuration (Firebase console → Project settings →
     General → Your apps → Web app → SDK setup and config):
     `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`,
     `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_STORAGE_BUCKET`,
     `VITE_FIREBASE_MESSAGING_SENDER_ID`, `VITE_FIREBASE_APP_ID`.

     These six are public identifiers — they are meant to be shipped to the
     browser, and authorisation is enforced by Firebase Security Rules and by the
     backend verifying ID tokens. They are **not** secrets, unlike the service
     account in step 3.

     If any are missing, the auth screens render an explicit "Firebase
     Authentication is not configured" panel listing exactly which variables are
     missing, instead of showing a form that cannot work.
3. Deploy. The build bakes both the URL and the Firebase config into the bundle
   at build time — changing either requires a redeploy.
4. In the Firebase console, add the Vercel domain under **Authentication →
   Settings → Authorized domains**, or sign-in fails with
   `auth/unauthorized-domain`.
5. Under **Authentication → Sign-in method**, enable **Email/Password**.
   Password-reset emails are sent by Firebase using the templates under
   **Authentication → Templates**, so no SMTP configuration is needed here.
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

# 2. Firebase is configured on the backend. Unauthenticated, and it leaks no
#    credential material — just whether the verifier can run:
curl -s https://<render-backend>/api/v1/auth/registration-policy
# -> {"inviteCodeRequired":true,"registrationAvailable":true}

# 3. Sign in works (browser at the Vercel URL; check Set-Cookie has
#    Secure; SameSite=None on JSESSIONID and XSRF-TOKEN)

# 4. Registration is gated. With no invite code it must be refused, and
#    the API must never report a missing code as success:
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  https://<render-backend>/api/v1/auth/firebase/session \
  -H 'Content-Type: application/json' -d '{"idToken":"not-a-real-token"}'
# -> 401 (the token is rejected before the gate is ever consulted), never 200

# 5. The auth screens report missing Firebase config instead of failing
#    silently. Open the Vercel URL with the VITE_FIREBASE_* vars unset:
#    -> a labelled "Firebase Authentication is not configured" panel listing
#       exactly which variables are missing.

# 6. Tailor a CV for any job and download the exact artifact:
#    POST /api/v1/resume-intelligence/tailor {"jobId":"..."}
#    GET  /api/v1/resume-intelligence/cv/<cvVersionId>/artifact
#    -> 200, application/pdf

# 7. Worker events auth (from the worker or curl):
curl -X POST https://<render-backend>/api/v1/automation/events \
  -H "Authorization: Bearer $WORKER_EVENT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"smoke-1","type":"HEARTBEAT","planId":"<existing-plan-id>"}'
# -> 200 {"accepted":true,...}; wrong/missing token -> 401

# 8. Flyway applied:
#    Render logs show "Successfully applied N migrations" on first boot.
#    A database that predates the isolation work must reach v023 and report
#    "Successfully applied 23 migrations" (or "up to date" if already there).

# 9. User data is isolated (V022). Signs of success after the first deploy:
#    SELECT count(*) FROM applications  WHERE profile_id IS NULL;
#    SELECT count(*) FROM notifications WHERE profile_id IS NULL;
#    -> 0 on a fresh database. On an upgraded one, non-zero is expected only for
#       rows that could not be attributed; they are invisible to users by
#       design, and attaching them to a guess is the bug this migration avoids.
```

## 7. User data isolation (V022)

V022 makes the previously global tables owner-scoped, so review it before the first
production deploy that allows more than one account.

**What it does at deploy time.** Adds `profile_id` (→ `profiles(id)`) to
`applications`, `notifications`, `emails`, `automation_plans`, `worker_events` and
`audit_logs`, replaces three accidentally-global unique constraints with per-owner
ones, moves the per-candidate job match decision off the shared `jobs` row into
`job_matches`, and attributes legacy rows only where ownership is provable.

**Expected runtime effect.** On a single-user Phase 1 database every legacy row is
attributed to that one profile and nothing changes for its owner. On a
multi-profile database, unattributable legacy rows keep `profile_id = NULL` and are
excluded from every user-facing read — visible as missing history rather than as
data leaking between accounts. That is the intended fail-closed trade-off; if those
rows matter, attribute them deliberately (they are still in the table, not deleted)
rather than relaxing the filter.

**Two things it deliberately does not do.** It does not backfill `audit_logs`
(append-only per V008 — an `update` there aborts the migration; legacy audit rows are
attributed at read time from `actor`), and it does not add `NOT NULL`, because that
would abort the migration on real data holding unattributable rows.

**Rollback.** There is no down-migration. The column adds, index swaps and the
dropped `jobs.match_*` columns are the parts that cannot be reversed automatically;
taking a database backup before the first deploy of this version is therefore part
of the change, not an optional extra.

**V023 follow-up (audit link vs purge).** V022's first cut added
`audit_logs.profile_id` as a foreign key with `on delete set null`. That cannot
work: V008's append-only trigger rejects the UPDATE the database performs to null
the column, so profile purge (POST /auth/purge-my-data) failed with
`audit_logs is append-only: UPDATE is forbidden` for any account with audit
history. V023 drops that foreign key — the column stays, written at INSERT for
attribution, and survives a purge as a historical reference. No action is needed
on an operator's side; if V022 already ran, V023 applies on the next deploy.

**Verifying it without deploying.** The isolation suite runs against any real
PostgreSQL and also proves Flyway applies the whole chain to an empty database
(which a mocked template cannot):

```bash
# Any reachable PostgreSQL; the schema is migrated by Flyway on context startup.
mvn -f backend/pom.xml verify -Dit.postgres.url=jdbc:postgresql://127.0.0.1:5432/jobagent \
    -Dit.postgres.username=jobagent -Dit.postgres.password=''
```

Without `-Dit.postgres.url` the suite is skipped rather than failed, so a build
machine without a database is unaffected. Note that the other `*IT` classes use
Testcontainers and need a working Docker daemon.

## 8. Production vs free-tier requirements

| Concern | Free/hobby OK | Production requirement |
|---|---|---|
| DB | Supabase free | Paid tier for backups, PITR, no pausing |
| Redis | Upstash free | Paid for persistence + higher command limits |
| Backend | Render free (cold starts) | Paid: no sleep, autoscaling, better TLS |
| Frontend | Vercel hobby | Paid: analytics, domain, DDoS protection |
| Secrets | Render/Vercel env vars | Vault/rotation policy, least-privilege DB user |
| Email/mailbox | not configured | Dedicated mailbox + OAuth consent screens |
| Real submissions | never in testing | Legal/ToS review per employer before any real use |
