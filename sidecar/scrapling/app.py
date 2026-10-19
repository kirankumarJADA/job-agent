"""Robin's local Scrapling sidecar.

A deliberately small HTTP wrapper around the Scrapling Python library
(https://github.com/D4Vinci/Scrapling) so the Java discovery engine can use
Scrapling's HTTP, dynamic (Playwright) and stealthy (Camoufox) fetchers
through the existing provider abstraction.

Contract (consumed by backend/.../discovery/ScraplingProvider.java):

    POST /fetch
      body: {"url": str, "mode": "http"|"dynamic"|"stealthy"|"auto",
             "timeout_ms": int?, "solve_cloudflare": bool?}
      200 -> {"ok": true,  "status": int, "url": str, "content": str, "fetcher": str,
              "strategy_chain": [str]?, "truncated": bool?, "fields": {name: {value, source, confidence}}?}
      200 -> {"ok": false, "error": str,  "status": int|null, "strategy_chain": [str]?}   (fetch-level failure)
      4xx/5xx -> sidecar-level failure (bad token, bad request, disallowed URL)

    GET /health -> {"ok": true}

Robin x Tsenta hardening (this continuation): adds a bounded multi-strategy
fallback (``mode: "auto"``, opt-in — the existing explicit http/dynamic/
stealthy modes are completely unchanged, so an older caller sees identical
behavior) and adaptive, provenance-tagged job-field extraction, both
implemented in ``extraction.py`` and unit-tested there in isolation from this
HTTP layer. ``content``/``fetcher`` — the only fields the existing Java
provider reads — are populated exactly as before; ``fields``/``strategy_chain``/
``truncated`` are strictly additive.

Security model: this service is a LOCAL tool. It fetches whatever URL it is
given (that is its job), so it must never be reachable from the public
internet — bind it to loopback and, in Docker, publish the port to 127.0.0.1
only. If SCRAPLING_API_TOKEN is set, requests must present it as
"Authorization: Bearer <token>". Logs record the target URL and status only —
never page content, never tokens. A URL resolving to a loopback or private
network address is refused outright (SSRF against Robin's own internal
network is not a "scraping difficulty" to work around).
"""

from __future__ import annotations

import logging
import os

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, HttpUrl

from extraction import (
    DomainThrottle,
    cap_content,
    escalate_fetch,
    extract_fields,
    is_disallowed_url,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("scrapling-sidecar")

def _env_str(name: str, default: str) -> str:
    # Some deployment mechanisms (e.g. Docker Compose's `${VAR:-}` expansion)
    # set an env var to an EMPTY string rather than leaving it unset — treat
    # that identically to unset, never as an explicit empty override.
    value = os.environ.get(name, "").strip()
    return value or default


def _env_int(name: str, default: int) -> int:
    try:
        return int(_env_str(name, str(default)))
    except ValueError:
        return default


REQUIRED_TOKEN = _env_str("SCRAPLING_API_TOKEN", "")
DEFAULT_MODE = _env_str("SCRAPLING_DEFAULT_MODE", "http").lower()
VALID_MODES = {"http", "dynamic", "stealthy", "auto"}
MAX_CONTENT_BYTES = _env_int("SCRAPLING_MAX_CONTENT_BYTES", 5 * 1024 * 1024)
MIN_DOMAIN_DELAY_SECONDS = _env_int("SCRAPLING_MIN_DELAY_MS", 0) / 1000.0
ADAPTIVE_STORAGE_FILE = _env_str("SCRAPLING_ADAPTIVE_STORAGE", "") or None
ADAPTIVE_EXTRACTION_ENABLED = _env_str("SCRAPLING_ADAPTIVE_EXTRACTION", "true").lower() != "false"

app = FastAPI(title="robin-scrapling-sidecar", docs_url=None, redoc_url=None)
_throttle = DomainThrottle(MIN_DOMAIN_DELAY_SECONDS)


class FetchRequest(BaseModel):
    url: HttpUrl
    mode: str | None = None
    timeout_ms: int | None = None
    solve_cloudflare: bool = False


class FetchResponse(BaseModel):
    ok: bool
    status: int | None = None
    url: str | None = None
    content: str | None = None
    fetcher: str | None = None
    error: str | None = None
    strategy_chain: list[str] | None = None
    truncated: bool | None = None
    fields: dict[str, dict] | None = None


def _authorize(authorization: str | None) -> None:
    if not REQUIRED_TOKEN:
        return
    if authorization != f"Bearer {REQUIRED_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid sidecar token")


def _fetcher_for(mode: str, url: str, timeout_ms: int | None, solve_cloudflare: bool):
    """Returns a zero-arg callable that performs exactly one fetch attempt
    with the given Scrapling fetcher. Imports lazily so a missing browser
    toolchain only affects the modes that need it, not plain HTTP fetches.

    Robin x Tsenta hardening — a real bug caught by an actual live fetch
    (never would have surfaced from a mocked test): ``Fetcher`` (curl_cffi)
    takes ``timeout`` in SECONDS, but ``DynamicFetcher``/``StealthyFetcher``
    (Playwright-based) take it in MILLISECONDS — confirmed against the
    pinned version's own docstrings ("The timeout in milliseconds..."). The
    previous code divided ``timeout_ms`` by 1000 for every mode, so a caller
    asking for a 30s browser timeout actually got 30ms and failed
    immediately. ``http`` still converts to seconds; ``dynamic``/``stealthy``
    now pass ``timeout_ms`` through unchanged.
    """
    from scrapling.fetchers import DynamicFetcher, Fetcher, StealthyFetcher

    if mode == "http":
        kwargs = {}
        if timeout_ms:
            kwargs["timeout"] = max(5, timeout_ms // 1000)
        return lambda: Fetcher.get(url, **kwargs)
    if mode == "dynamic":
        kwargs = {"headless": True}
        if timeout_ms:
            kwargs["timeout"] = max(5000, timeout_ms)
        return lambda: DynamicFetcher.fetch(url, **kwargs)
    if mode == "stealthy":
        kwargs = {"headless": True}
        if timeout_ms:
            kwargs["timeout"] = max(5000, timeout_ms)
        if solve_cloudflare:
            # Documented Scrapling capability. Not verified against a live
            # protected target from this repository — treat as best-effort.
            kwargs["solve_cloudflare"] = True
        return lambda: StealthyFetcher.fetch(url, **kwargs)
    raise ValueError(f"unsupported mode {mode!r}")


def _fetch(url: str, mode: str, timeout_ms: int | None, solve_cloudflare: bool):
    """Dispatch to the requested Scrapling fetcher; returns the page object.
    Preserved exactly for the three explicit modes (pre-existing behavior,
    unchanged) — ``auto`` is handled separately by ``_fetch_auto`` below.
    """
    return _fetcher_for(mode, url, timeout_ms, solve_cloudflare)()


def _fetch_auto(url: str, timeout_ms: int | None, solve_cloudflare: bool):
    """Bounded multi-strategy escalation (Robin x Tsenta hardening): tries
    http first, escalates to dynamic/stealthy only when the actual fetched
    result gives a concrete, evidence-based reason to (see
    ``extraction.classify_outcome``/``escalate_fetch``) — never an unbounded
    loop, at most one attempt per strategy in ``STRATEGY_ORDER`` plus one
    bounded retry each.
    """
    fetchers = {
        mode: _fetcher_for(mode, url, timeout_ms, solve_cloudflare)
        for mode in ("http", "dynamic", "stealthy")
    }
    result = escalate_fetch(fetchers, allowed_strategies=("http", "dynamic", "stealthy"))
    return result


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.post("/fetch")
def fetch(request: FetchRequest, authorization: str | None = Header(default=None)) -> FetchResponse:
    _authorize(authorization)

    url = str(request.url)
    disallowed = is_disallowed_url(url)
    if disallowed:
        raise HTTPException(status_code=400, detail=disallowed)

    mode = (request.mode or DEFAULT_MODE).lower()
    if mode not in VALID_MODES:
        raise HTTPException(status_code=400, detail=f"mode must be one of {sorted(VALID_MODES)}")

    _throttle.wait(url)

    strategy_chain: list[str] | None = None
    if mode == "auto":
        escalation = _fetch_auto(url, request.timeout_ms, request.solve_cloudflare)
        strategy_chain = [a.strategy for a in escalation.attempts]
        if not escalation.ok:
            log.warning("fetch failed mode=auto url=%s chain=%s final=%s", url, strategy_chain, escalation.error)
            return FetchResponse(ok=False, error=escalation.error or "ALL_STRATEGIES_FAILED",
                                  fetcher="auto", strategy_chain=strategy_chain)
        page = escalation.page
        effective_fetcher = escalation.strategy_used
    else:
        try:
            page = _fetch(url, mode, request.timeout_ms, request.solve_cloudflare)
        except Exception as exc:  # noqa: BLE001 - report, never leak page content
            log.warning("fetch failed mode=%s url=%s error=%s", mode, url, type(exc).__name__)
            return FetchResponse(ok=False, error=f"{type(exc).__name__}", fetcher=mode)
        effective_fetcher = mode

    status = int(getattr(page, "status", 0) or 0)
    content = getattr(page, "html_content", None) or ""
    final_url = str(getattr(page, "url", None) or url)
    log.info("fetch mode=%s url=%s status=%s bytes=%d", effective_fetcher, url, status, len(content))

    if status >= 400:
        return FetchResponse(ok=False, status=status, url=final_url,
                              error=f"UPSTREAM_STATUS_{status}", fetcher=effective_fetcher,
                              strategy_chain=strategy_chain)
    if not content.strip():
        return FetchResponse(ok=False, status=status, url=final_url,
                              error="EMPTY_CONTENT", fetcher=effective_fetcher,
                              strategy_chain=strategy_chain)

    content, truncated = cap_content(content, MAX_CONTENT_BYTES)

    fields = None
    if ADAPTIVE_EXTRACTION_ENABLED:
        try:
            extracted = extract_fields(content, final_url, ADAPTIVE_STORAGE_FILE)
            fields = {name: {"value": result.value, "source": result.source, "confidence": result.confidence}
                      for name, result in extracted.items()}
        except Exception as exc:  # noqa: BLE001 - field extraction is a bonus, never fatal to the fetch itself
            log.warning("adaptive extraction failed url=%s error=%s", url, type(exc).__name__)

    return FetchResponse(ok=True, status=status, url=final_url, content=content, fetcher=effective_fetcher,
                          strategy_chain=strategy_chain, truncated=truncated or None, fields=fields or None)


if __name__ == "__main__":
    import uvicorn

    host = os.environ.get("SCRAPLING_SERVICE_HOST", "127.0.0.1")
    port = int(os.environ.get("SCRAPLING_SERVICE_PORT", "8199"))
    uvicorn.run(app, host=host, port=port, log_level="info")
