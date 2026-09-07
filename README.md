# Personal AI Job Agent

Phase 1 monorepo. See `docs/adr/`, `docs/contracts/` for design records (added
as later slices land) and the architecture review for the full master plan.

## Repo layout

```
infra/       docker-compose, postgres init, observability profile
backend/     Spring Boot 3.3 / Java 21 modular monolith
frontend/    Vite + React + TypeScript dashboard
worker/      empty until Phase 6 — see worker/README.md for the contract
docs/        ADRs, API/event contracts, runbooks
datasets/    versioned benchmark suites (Phase 1e/1f onward)
```

## Running locally

```bash
cp .env.example infra/.env      # Compose resolves .env relative to the compose file's
                                  # directory (or your CWD) — NOT the repo root, so it
                                  # must live in infra/, not next to this README.
cd infra
docker compose up --build
```

- Backend: http://localhost:8080/api/v1/system/health
- Frontend: http://localhost:5173
- Postgres: localhost:5433 (jobagent / jobagent_dev_password — dev only; host port is 5433, not the default 5432, to avoid clashing with a locally-installed Postgres — see `infra/docker-compose.yml`)

Backend tests (from `backend/`): `mvn verify` — runs unit tests, the
ArchUnit module-boundary suite, and Testcontainers-backed integration tests.
Requires a Docker daemon.

Frontend tests (from `frontend/`): `npm install && npm test`.

## What's implemented in this slice (P1-a)

- Monorepo structure matching the architecture doc's repo layout.
- `docker-compose.yml` (postgres, redis, backend, frontend) + optional
  observability profile.
- Flyway `V001__core.sql` (full Phase 1 schema) and `V002__seed.sql` (dev
  user/profile/preferences/seed jobs).
- Backend bootstrap: `GET /api/v1/system/health`, JSON structured logging
  with `correlation_id` threaded through MDC via `CorrelationIdFilter`, a
  `LogScrubber` for the "no secrets in logs" requirement, UUIDv7 generation,
  RFC 9457 error shape, and a placeholder `SecurityConfig` (real auth is
  P1-b — everything is currently `permitAll`).
- `ArchModuleBoundaryTest` (ArchUnit) enforcing the §B1 dependency rule.
- Frontend skeleton that calls the health endpoint, proving the dev loop
  end-to-end.
- GitHub Actions CI (`mvn verify`, `tsc`/`vitest`/build).

## Verification status — please read before trusting "done"

This slice was built and partially verified inside a sandboxed environment
with **no access to Maven Central and no Docker daemon**. What that means
concretely:

| Component | Verified how | Status |
|---|---|---|
| `docker-compose.yml`, `docker-compose.obs.yml`, prometheus config | YAML parsed | ✅ syntactically valid |
| `V001__core.sql`, `V002__seed.sql` | **Installed Postgres 16 for real and ran both migrations against a live DB**, then queried the seeded rows back | ✅ actually executes; seed data confirmed correct (weights sum to 100, 2 jobs present) |
| `pom.xml` | Parsed as XML | ✅ well-formed; dependency versions are believed-correct but **not resolved against Maven Central** |
| Backend Java sources (`JobAgentApplication`, `CorrelationIdFilter`, `SystemHealthController`, `SecurityConfig`, `ArchModuleBoundaryTest`, etc.) | Manual review only | ⚠️ **not compiled** — no `mvn` / Maven Central access in this sandbox. Run `mvn verify` yourself before relying on this |
| Frontend (`package.json`, `tsconfig.json`, `App.tsx`, etc.) | **Ran `npm install`, `tsc -b`, `vite build`, and the dev server for real** | ✅ actually compiles, builds, and serves (HTTP 200 confirmed) |
| `Dockerfile`s (backend, frontend) | Manual review only | ⚠️ not built — untested |
| GitHub Actions workflow | Manual review only | ⚠️ not run — untested |

**Before you consider P1-a done, please run `mvn verify` and
`docker compose up --build` yourself** and tell me what breaks — the backend
Java code and both Dockerfiles are the highest-risk unverified pieces.

## Phase 1 Definition of Done (from the architecture doc — tracking, not all met yet)

- [x] Seed migrations apply cleanly and produce correct data (verified above)
- [ ] `docker compose up` → healthy stack (untestable here — please confirm)
- [ ] Login → edit profile/preferences → persisted with audit rows (P1-b/P1-d)
- [ ] `/system/llm/ping` failover demo (P1-e)
- [ ] Benchmark run → promote → routing honoured (P1-f)
- [ ] `GET /api/v1/jobs` seeded + filtered, Job Detail renders (P1-g)
- [ ] Kill-mid-dispatch / Reconciler replay proven (P1-c, needed before P1-g's
      event-driven pieces are meaningful)
- [ ] CI green on push (untestable here — please confirm once pushed)

## Next slice

**P1-b: Auth + audit** — session auth (argon2id), CSRF, the real
`SecurityConfig` replacing today's placeholder, audit-log writer, and a
"delete my data" purge endpoint. I'll hold here for your confirmation that
`mvn verify` and `docker compose up` work in your real environment before
building on top of unverified backend code.
