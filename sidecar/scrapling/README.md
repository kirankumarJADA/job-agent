# Scrapling sidecar

An **optional, local** fetching service for Robin's discovery engine, wrapping
the [Scrapling](https://github.com/D4Vinci/Scrapling) Python library
(`scrapling[fetchers]==0.4.15`) behind one HTTP endpoint so the Java backend
can use it through the existing `ScraperProvider` abstraction
(`backend/src/main/java/com/personal/jobagent/discovery/ScraplingProvider.java`).

## Why a sidecar

Scrapling is Python; Robin's backend is Java. Rather than a second
integration architecture, this mirrors the existing Crawl4AI pattern: a
self-hosted HTTP fetch service that the backend talks to as a provider.
Robin's Node `worker/` is deliberately untouched — it executes application
automation plans and contains no discovery logic.

## Modes

| Mode | Scrapling fetcher | Use for | Browser binaries |
|---|---|---|---|
| `http` (default) | `Fetcher.get` | ordinary static pages | not required |
| `dynamic` | `DynamicFetcher.fetch` | JavaScript-rendered pages | required (`scrapling install`) |
| `stealthy` | `StealthyFetcher.fetch` | protected targets (documented Cloudflare handling) | required (`scrapling install`) |

The mode is chosen by the backend via `SCRAPLING_FETCH_MODE` — plain HTTP is
the default so ordinary pages never pay the browser-automation cost.
`solve_cloudflare` is wired through to StealthyFetcher (documented Scrapling
capability) but is **not proven against a live protected target** from this
repository; treat it as best-effort.

## Run locally (no Docker)

```bash
cd sidecar/scrapling
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt   # Linux/mac: .venv/bin/python
.venv/Scripts/python app.py                                # binds 127.0.0.1:8199
# optional browser toolchain for dynamic/stealthy modes:
.venv/Scripts/python -m scrapling install
```

## Run in the local Docker stack

```bash
docker compose -f infra/docker-compose.yml --profile scrapling up scrapling
```

The service is compose-profile-gated, so the standard stack is unchanged when
the profile is not enabled. The port publishes to `127.0.0.1` only.

## Backend configuration

| Variable | Meaning | Default |
|---|---|---|
| `SCRAPLING_BASE_URL` | sidecar base URL (e.g. `http://localhost:8199`). Unset ⇒ provider `UNCONFIGURED` and skipped. | empty |
| `SCRAPLING_FETCH_MODE` | `http` \| `dynamic` \| `stealthy` | `http` |
| `SCRAPLING_API_TOKEN` | optional shared secret; sent as `Authorization: Bearer`, enforced by the sidecar when set | empty |
| `SCRAPLING_TIMEOUT_MS` | backend-side sidecar timeout | `60000` |

## Security

- The sidecar fetches **any URL it is given** — that is its job. It must never
  be reachable from the public internet. Loopback binding / 127.0.0.1-only
  port publishing is mandatory.
- Scraped page content is **untrusted data**: it flows into the existing
  `ExtractedJob`/parser pipeline as stored evidence only and is never
  interpreted as instructions. It is never logged; logs carry URL, mode and
  status only.
- The optional token protects the sidecar from other local processes; it is
  never logged and never reaches the frontend or Vercel.
