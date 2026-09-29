"""Robin's local Scrapling sidecar.

A deliberately small HTTP wrapper around the Scrapling Python library
(https://github.com/D4Vinci/Scrapling) so the Java discovery engine can use
Scrapling's HTTP, dynamic (Playwright) and stealthy (Camoufox) fetchers
through the existing provider abstraction.

Contract (consumed by backend/.../discovery/ScraplingProvider.java):

    POST /fetch
      body: {"url": str, "mode": "http"|"dynamic"|"stealthy",
             "timeout_ms": int?, "solve_cloudflare": bool?}
      200 -> {"ok": true,  "status": int, "url": str, "content": str, "fetcher": str}
      200 -> {"ok": false, "error": str,  "status": int|null}   (fetch-level failure)
      4xx/5xx -> sidecar-level failure (bad token, bad request)

    GET /health -> {"ok": true}

Security model: this service is a LOCAL tool. It fetches whatever URL it is
given (that is its job), so it must never be reachable from the public
internet — bind it to loopback and, in Docker, publish the port to 127.0.0.1
only. If SCRAPLING_API_TOKEN is set, requests must present it as
"Authorization: Bearer <token>". Logs record the target URL and status only —
never page content, never tokens.
"""

from __future__ import annotations

import logging
import os

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, HttpUrl

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("scrapling-sidecar")

REQUIRED_TOKEN = os.environ.get("SCRAPLING_API_TOKEN", "").strip()
DEFAULT_MODE = os.environ.get("SCRAPLING_DEFAULT_MODE", "http").strip().lower()
VALID_MODES = {"http", "dynamic", "stealthy"}

app = FastAPI(title="robin-scrapling-sidecar", docs_url=None, redoc_url=None)


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


def _authorize(authorization: str | None) -> None:
    if not REQUIRED_TOKEN:
        return
    if authorization != f"Bearer {REQUIRED_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid sidecar token")


def _fetch(url: str, mode: str, timeout_ms: int | None, solve_cloudflare: bool):
    """Dispatch to the requested Scrapling fetcher; returns the page object.

    Import fetchers lazily so a missing browser toolchain only affects the
    modes that need it, not plain HTTP fetches.
    """
    from scrapling.fetchers import DynamicFetcher, Fetcher, StealthyFetcher

    if mode == "http":
        kwargs = {}
        if timeout_ms:
            kwargs["timeout"] = max(5, timeout_ms // 1000)
        return Fetcher.get(url, **kwargs)
    if mode == "dynamic":
        kwargs = {"headless": True}
        if timeout_ms:
            kwargs["timeout"] = max(5, timeout_ms // 1000)
        return DynamicFetcher.fetch(url, **kwargs)
    if mode == "stealthy":
        kwargs = {"headless": True}
        if timeout_ms:
            kwargs["timeout"] = max(5, timeout_ms // 1000)
        if solve_cloudflare:
            # Documented Scrapling capability. Not verified against a live
            # protected target from this repository — treat as best-effort.
            kwargs["solve_cloudflare"] = True
        return StealthyFetcher.fetch(url, **kwargs)
    raise ValueError(f"unsupported mode {mode!r}")


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.post("/fetch")
def fetch(request: FetchRequest, authorization: str | None = Header(default=None)) -> FetchResponse:
    _authorize(authorization)

    mode = (request.mode or DEFAULT_MODE).lower()
    if mode not in VALID_MODES:
        raise HTTPException(status_code=400, detail=f"mode must be one of {sorted(VALID_MODES)}")

    try:
        page = _fetch(str(request.url), mode, request.timeout_ms, request.solve_cloudflare)
    except Exception as exc:  # noqa: BLE001 - report, never leak page content
        log.warning("fetch failed mode=%s url=%s error=%s", mode, request.url, type(exc).__name__)
        return FetchResponse(ok=False, error=f"{type(exc).__name__}", fetcher=mode)

    status = int(getattr(page, "status", 0) or 0)
    content = getattr(page, "html_content", None) or ""
    final_url = str(getattr(page, "url", None) or request.url)
    log.info("fetch mode=%s url=%s status=%s bytes=%d", mode, request.url, status, len(content))

    if status >= 400:
        return FetchResponse(ok=False, status=status, url=final_url,
                             error=f"UPSTREAM_STATUS_{status}", fetcher=mode)
    if not content.strip():
        return FetchResponse(ok=False, status=status, url=final_url,
                             error="EMPTY_CONTENT", fetcher=mode)

    return FetchResponse(ok=True, status=status, url=final_url, content=content, fetcher=mode)


if __name__ == "__main__":
    import uvicorn

    host = os.environ.get("SCRAPLING_SERVICE_HOST", "127.0.0.1")
    port = int(os.environ.get("SCRAPLING_SERVICE_PORT", "8199"))
    uvicorn.run(app, host=host, port=port, log_level="info")
