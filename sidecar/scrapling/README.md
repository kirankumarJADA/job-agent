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
| `auto` | bounded escalation across all three | unknown/mixed targets | required for escalation past `http` |

The mode is chosen by the backend via `SCRAPLING_FETCH_MODE` — plain HTTP is
the default so ordinary pages never pay the browser-automation cost.
`solve_cloudflare` is wired through to StealthyFetcher (documented Scrapling
capability) but is **not proven against a live protected target** from this
repository; treat it as best-effort.

### `auto` mode: bounded multi-strategy fallback (`extraction.py`)

`auto` asks the sidecar to decide for itself, per request, rather than a
human picking one fixed mode for every target: try `http` first; escalate to
`dynamic` only when the actual result gives a concrete reason to (an
empty/near-empty body, an explicit "enable JavaScript" marker); escalate
straight to `stealthy` — skipping `dynamic` entirely — on an anti-bot signal
(403/429/503, or page text like "checking your browser", "cloudflare",
"captcha"), since an ordinary browser has no better chance against that wall
than a plain HTTP client does. Each strategy gets at most one bounded retry
on a thrown exception (a real transient condition) — never on a merely
unwanted HTTP response — and the whole sequence tries at most the three
strategies once each, so a bad target fails bounded rather than looping. The
response's `strategy_chain` field records exactly which strategies were
tried, in order, for diagnosis.

### Adaptive, provenance-tagged job-field extraction

Every successful fetch also runs Scrapling's real adaptive extraction
mechanism (`Selector(..., adaptive=True)`, `.css(selector, identifier=...,
adaptive=True)`, `.retrieve()`/`.relocate()`/`.save()` — see `extraction.py`
for the exact calls) to find job-page fields independently of the Java-side
regex/JSON-LD parser, returned under the response's `fields` key:

1. **JSON-LD** `JobPosting` structured data (confidence 0.85–0.95) — the
   page's own machine-readable claim about itself, preferred whenever present.
2. **OpenGraph/meta tags** (confidence 0.5–0.7) when no JSON-LD exists.
3. **Adaptive CSS recovery** (confidence 0.55) for whatever is still
   missing: a narrow, evidence-backed selector hint (e.g. `h1` for title),
   with Scrapling's own `relocate()` recovering the element by structural
   similarity when the hint selector no longer matches directly (the page
   was redesigned) — proven in `test_extraction.py` with two HTML versions
   of "the same page."

A relocated candidate is independently validated before being trusted — a
tag-name check against the ORIGINALLY saved element (Scrapling's own
`relocate()` scores by overall structural similarity, which a sparse enough
page can satisfy with the WRONG element; a `<main>` with no attributes can
out-score a changed `<h1>` purely on "both have no attributes," which this
module's own test suite demonstrates and guards against) plus a plausibility
check (rejects empty/boilerplate/implausibly-long values). A rejected or
absent relocation is reported as absence, never a guess — and, deliberately,
a bad relocation attempt never overwrites the saved fingerprint, so a later
genuine page redesign can still recover using the original known-good
element rather than whatever the bad attempt landed on.

The Java-side `ScraplingProvider` only lets a `fields` value FILL an
already-blank field (any confidence) or REPLACE a present one at the
JSON-LD confidence tier — a low-confidence adaptive-recovery guess can never
overwrite what the existing regex/JSON-LD parser already found.

Set `SCRAPLING_ADAPTIVE_EXTRACTION=false` to disable this entirely (the
response simply omits `fields`); set `SCRAPLING_ADAPTIVE_STORAGE=/path/to/file.db`
to persist the adaptive tracking database to a specific (ideally mounted)
path rather than Scrapling's own default location inside its install
directory.

### Bounded safety

- `SCRAPLING_MAX_CONTENT_BYTES` (default 5 MiB) caps returned content size;
  an oversized page is truncated and the response sets `truncated: true`.
- `SCRAPLING_MIN_DELAY_MS` (default 0 = disabled) enforces a minimum delay
  between requests to the *same host*, tracked in-process — a different host
  is never delayed by another host's throttle.
- Every request is checked against `is_disallowed_url` first: a target
  resolving to loopback, link-local, or a private (RFC1918) network address
  is refused with 400 before any fetcher ever runs — this is an SSRF
  safeguard against Robin's own internal network, not a content/robots.txt
  policy decision (which stays a separate, human/operator concern).

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
| `SCRAPLING_FETCH_MODE` | `http` \| `dynamic` \| `stealthy` \| `auto` | `http` |
| `SCRAPLING_API_TOKEN` | optional shared secret; sent as `Authorization: Bearer`, enforced by the sidecar when set | empty |
| `SCRAPLING_TIMEOUT_MS` | backend-side sidecar timeout | `60000` |

## Development and testing

```bash
cd sidecar/scrapling
.venv/Scripts/python -m pip install -r requirements-dev.txt   # adds pytest + httpx (TestClient) on top of requirements.txt
.venv/Scripts/python -m pytest                                  # 52 deterministic tests, no network, no browser
```

`test_extraction.py` covers the fallback escalation and adaptive-extraction
logic directly (fast, pure-Python, injected fake fetchers); `test_app.py`
covers the FastAPI route contract with Scrapling's real fetcher classes
monkeypatched at their source modules. Neither test file starts a real
browser or makes a real network request — the standard suite runs the same
way in CI as locally.

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
