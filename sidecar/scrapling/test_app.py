"""Deterministic HTTP-contract tests for app.py.

Scrapling's real fetchers are monkeypatched at their source modules
(``scrapling.fetchers.requests``/``chrome``/``stealth_chrome``) so these tests
exercise the REAL FastAPI route handlers end-to-end — auth, URL validation,
mode dispatch, auto-escalation wiring, content capping, adaptive-field
wiring — without a real browser or real network.
"""

from __future__ import annotations

import importlib

import pytest
from fastapi.testclient import TestClient


class _FakePage:
    def __init__(self, status: int, html: str, url: str = "https://example.test/x"):
        self.status = status
        self.html_content = html
        self.url = url


@pytest.fixture
def app_module(monkeypatch):
    """Reloads app.py fresh per test so module-level config (token, modes,
    size caps) reflects env vars set by each test, and resets the module-
    level DomainThrottle so tests never interfere with each other's timing.
    """
    monkeypatch.delenv("SCRAPLING_API_TOKEN", raising=False)
    monkeypatch.delenv("SCRAPLING_DEFAULT_MODE", raising=False)
    monkeypatch.delenv("SCRAPLING_MAX_CONTENT_BYTES", raising=False)
    monkeypatch.delenv("SCRAPLING_MIN_DELAY_MS", raising=False)
    monkeypatch.delenv("SCRAPLING_ADAPTIVE_EXTRACTION", raising=False)
    import app as app_mod

    importlib.reload(app_mod)
    return app_mod


def _client(app_module) -> TestClient:
    return TestClient(app_module.app)


def test_health_endpoint():
    import app as app_mod
    client = TestClient(app_mod.app)
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"ok": True}


def test_fetch_requires_token_when_configured(monkeypatch):
    monkeypatch.setenv("SCRAPLING_API_TOKEN", "secret-token")
    import app as app_mod
    importlib.reload(app_mod)
    client = _client(app_mod)

    response = client.post("/fetch", json={"url": "https://example.test/jobs/1"})
    assert response.status_code == 401

    response_ok_auth = client.post(
        "/fetch", json={"url": "https://example.test/jobs/1"},
        headers={"Authorization": "Bearer wrong-token"},
    )
    assert response_ok_auth.status_code == 401
    monkeypatch.delenv("SCRAPLING_API_TOKEN", raising=False)


def test_fetch_rejects_disallowed_urls_before_ever_fetching(app_module, monkeypatch):
    client = _client(app_module)
    called = {"count": 0}
    monkeypatch.setattr(
        "scrapling.fetchers.requests.Fetcher.get",
        classmethod(lambda cls, *a, **k: called.__setitem__("count", called["count"] + 1) or _FakePage(200, "x" * 500)),
    )
    response = client.post("/fetch", json={"url": "http://127.0.0.1:9000/internal"})
    assert response.status_code == 400
    assert called["count"] == 0, "a disallowed URL must never reach a real fetcher call"


def test_fetch_rejects_unknown_mode(app_module):
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "telepathy"})
    assert response.status_code == 400


def test_fetch_http_mode_success(app_module, monkeypatch):
    html = '<html><head><title>Backend Engineer</title></head><body>x</body></html>'
    monkeypatch.setattr(
        "scrapling.fetchers.requests.Fetcher.get",
        classmethod(lambda cls, url, **k: _FakePage(200, html, url)),
    )
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "http"})
    body = response.json()
    assert body["ok"] is True
    assert body["fetcher"] == "http"
    assert "Backend Engineer" in body["content"]


def test_fetch_upstream_error_status_is_reported_as_not_ok(app_module, monkeypatch):
    monkeypatch.setattr(
        "scrapling.fetchers.requests.Fetcher.get",
        classmethod(lambda cls, url, **k: _FakePage(404, "<html>not found</html>", url)),
    )
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/missing", "mode": "http"})
    body = response.json()
    assert body["ok"] is False
    assert body["error"] == "UPSTREAM_STATUS_404"


def test_fetch_exception_from_fetcher_is_reported_without_leaking_detail(app_module, monkeypatch):
    def _raise(cls, url, **k):
        raise ConnectionError("dns resolution failed for internal-reason: secret-proxy-password-123")

    monkeypatch.setattr("scrapling.fetchers.requests.Fetcher.get", classmethod(_raise))
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "http"})
    body = response.json()
    assert body["ok"] is False
    assert body["error"] == "ConnectionError"
    assert "secret-proxy-password" not in response.text


def test_fetch_auto_mode_escalates_from_http_to_dynamic_and_reports_the_chain(app_module, monkeypatch):
    monkeypatch.setattr(
        "scrapling.fetchers.requests.Fetcher.get",
        classmethod(lambda cls, url, **k: _FakePage(200, '<div id="root"></div>', url)),  # JS_REQUIRED
    )
    monkeypatch.setattr(
        "scrapling.fetchers.chrome.DynamicFetcher.fetch",
        classmethod(lambda cls, url, **k: _FakePage(200, "<html>" + "x" * 500 + "</html>", url)),
    )
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "auto"})
    body = response.json()
    assert body["ok"] is True
    assert body["fetcher"] == "dynamic"
    assert body["strategy_chain"] == ["http", "dynamic"]


def test_fetch_auto_mode_reports_failure_with_full_chain_when_every_strategy_fails(app_module, monkeypatch):
    for target in ("scrapling.fetchers.requests.Fetcher.get",
                   "scrapling.fetchers.chrome.DynamicFetcher.fetch",
                   "scrapling.fetchers.stealth_chrome.StealthyFetcher.fetch"):
        monkeypatch.setattr(target, classmethod(lambda cls, url, **k: _FakePage(403, "access denied", url)))
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "auto"})
    body = response.json()
    assert body["ok"] is False
    # ANTI_BOT at http escalates straight to stealthy (skipping dynamic) — see extraction.py's _ESCALATE_TO.
    assert body["strategy_chain"] == ["http", "stealthy"]


def test_fetch_truncates_oversized_content_and_flags_it(app_module, monkeypatch):
    monkeypatch.setenv("SCRAPLING_MAX_CONTENT_BYTES", "100")
    import app as app_mod
    importlib.reload(app_mod)
    monkeypatch.setattr(
        "scrapling.fetchers.requests.Fetcher.get",
        classmethod(lambda cls, url, **k: _FakePage(200, "x" * 10_000, url)),
    )
    client = TestClient(app_mod.app)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "http"})
    body = response.json()
    assert body["ok"] is True
    assert body["truncated"] is True
    assert len(body["content"].encode("utf-8")) <= 100


def test_fetch_includes_adaptive_fields_when_structured_metadata_is_present(app_module, monkeypatch):
    html = ('<html><head><script type="application/ld+json">'
            '{"@type":"JobPosting","title":"Backend Engineer","hiringOrganization":{"name":"Acme"}}'
            '</script></head><body></body></html>')
    monkeypatch.setattr(
        "scrapling.fetchers.requests.Fetcher.get",
        classmethod(lambda cls, url, **k: _FakePage(200, html, url)),
    )
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "http"})
    body = response.json()
    assert body["ok"] is True
    assert body["fields"]["title"] == {"value": "Backend Engineer", "source": "json_ld", "confidence": 0.95}
    assert body["fields"]["company"]["value"] == "Acme"


def test_fetch_omits_fields_entirely_when_adaptive_extraction_is_disabled(monkeypatch):
    monkeypatch.setenv("SCRAPLING_ADAPTIVE_EXTRACTION", "false")
    import app as app_mod
    importlib.reload(app_mod)
    html = '<html><head><script type="application/ld+json">{"@type":"JobPosting","title":"X"}</script></head></html>'
    monkeypatch.setattr(
        "scrapling.fetchers.requests.Fetcher.get",
        classmethod(lambda cls, url, **k: _FakePage(200, html, url)),
    )
    client = TestClient(app_mod.app)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "http"})
    body = response.json()
    assert body["ok"] is True
    assert body.get("fields") is None
    monkeypatch.delenv("SCRAPLING_ADAPTIVE_EXTRACTION", raising=False)


def test_fetch_dynamic_mode_passes_timeout_ms_to_playwright_unconverted(app_module, monkeypatch):
    """Regression test for a real bug caught by an actual live fetch against
    quotes.toscrape.com/js/: DynamicFetcher/StealthyFetcher take `timeout` in
    MILLISECONDS (Playwright's own convention), not seconds — the previous
    code divided by 1000 for every mode, so a 30s request timed out after
    30ms. Only `http` (curl_cffi) legitimately wants seconds.
    """
    captured = {}

    def fake_dynamic_fetch(cls, url, **kwargs):
        captured.update(kwargs)
        return _FakePage(200, "x" * 500, url)

    monkeypatch.setattr("scrapling.fetchers.chrome.DynamicFetcher.fetch", classmethod(fake_dynamic_fetch))
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "dynamic", "timeout_ms": 30000})
    assert response.json()["ok"] is True
    assert captured["timeout"] == 30000, "DynamicFetcher's timeout must stay in milliseconds, never divided by 1000"


def test_fetch_http_mode_still_converts_timeout_ms_to_seconds(app_module, monkeypatch):
    captured = {}

    def fake_get(cls, url, **kwargs):
        captured.update(kwargs)
        return _FakePage(200, "x" * 500, url)

    monkeypatch.setattr("scrapling.fetchers.requests.Fetcher.get", classmethod(fake_get))
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "http", "timeout_ms": 30000})
    assert response.json()["ok"] is True
    assert captured["timeout"] == 30, "curl_cffi's Fetcher legitimately wants seconds, unlike the Playwright fetchers"


def test_fetch_empty_content_is_reported_as_incomplete(app_module, monkeypatch):
    monkeypatch.setattr(
        "scrapling.fetchers.requests.Fetcher.get",
        classmethod(lambda cls, url, **k: _FakePage(200, "   ", url)),
    )
    client = _client(app_module)
    response = client.post("/fetch", json={"url": "https://example.test/jobs/1", "mode": "http"})
    body = response.json()
    assert body["ok"] is False
    assert body["error"] == "EMPTY_CONTENT"
