"""Deterministic tests for extraction.py — no real network, no real browser.

Fetchers are plain Python callables/fakes injected directly into
``escalate_fetch``; adaptive extraction runs against real Scrapling
``Selector`` objects built from inline HTML fixtures (fast, in-process, no
network), which is how the genuine adaptive relocation behaviour (§5 of the
task) gets proven rather than assumed.
"""

from __future__ import annotations

import tempfile
import os

import pytest

import extraction as ext


# ── classify_outcome ──────────────────────────────────────────────────────

def test_classify_outcome_empty_body():
    assert ext.classify_outcome(200, "") == "EMPTY"
    assert ext.classify_outcome(200, "   \n  ") == "EMPTY"


def test_classify_outcome_js_required_markers():
    assert ext.classify_outcome(200, "<html>Please enable JavaScript to continue</html>") == "JS_REQUIRED"
    assert ext.classify_outcome(200, '<div id="root"></div>') == "JS_REQUIRED"


def test_classify_outcome_anti_bot():
    assert ext.classify_outcome(403, "anything") == "ANTI_BOT"
    assert ext.classify_outcome(200, "Checking your browser before accessing") == "ANTI_BOT"
    assert ext.classify_outcome(429, "rate limited") == "ANTI_BOT"


def test_classify_outcome_http_error_without_anti_bot_signature():
    assert ext.classify_outcome(500, "internal server error, nothing suspicious") == "HTTP_ERROR"


def test_classify_outcome_success():
    assert ext.classify_outcome(200, "<html><body>" + "x" * 500 + "</body></html>") == "SUCCESS"


# ── escalate_fetch: bounded multi-strategy escalation ─────────────────────

class _FakePage:
    def __init__(self, status: int, html: str, url: str = "https://example.test/x"):
        self.status = status
        self.html_content = html
        self.url = url


def test_escalate_fetch_succeeds_on_first_strategy_without_escalating():
    calls = {"http": 0, "dynamic": 0}
    fetchers = {
        "http": lambda: (calls.__setitem__("http", calls["http"] + 1), _FakePage(200, "x" * 500))[1],
        "dynamic": lambda: (calls.__setitem__("dynamic", calls["dynamic"] + 1), _FakePage(200, "x" * 500))[1],
    }
    result = ext.escalate_fetch(fetchers, allowed_strategies=("http", "dynamic"))
    assert result.ok is True
    assert result.strategy_used == "http"
    assert calls["dynamic"] == 0, "must never try a stronger strategy once the cheap one succeeds"
    assert [a.strategy for a in result.attempts] == ["http"]


def test_escalate_fetch_escalates_from_http_to_dynamic_on_js_required():
    fetchers = {
        "http": lambda: _FakePage(200, '<div id="root"></div>'),  # JS_REQUIRED
        "dynamic": lambda: _FakePage(200, "x" * 500),  # SUCCESS
    }
    result = ext.escalate_fetch(fetchers, allowed_strategies=("http", "dynamic"))
    assert result.ok is True
    assert result.strategy_used == "dynamic"
    assert [a.strategy for a in result.attempts] == ["http", "dynamic"]
    assert result.attempts[0].outcome == "JS_REQUIRED"


def test_escalate_fetch_escalates_anti_bot_straight_to_stealthy_skipping_dynamic():
    calls = []
    fetchers = {
        "http": lambda: (calls.append("http"), _FakePage(403, "access denied"))[1],
        "dynamic": lambda: (calls.append("dynamic"), _FakePage(200, "x" * 500))[1],
        "stealthy": lambda: (calls.append("stealthy"), _FakePage(200, "x" * 500))[1],
    }
    result = ext.escalate_fetch(fetchers, allowed_strategies=("http", "dynamic", "stealthy"))
    assert result.ok is True
    assert result.strategy_used == "stealthy"
    assert "dynamic" not in calls, "an ordinary browser has no chance against an anti-bot wall; skip straight to stealthy"


def test_escalate_fetch_fails_after_exhausting_every_allowed_strategy():
    fetchers = {
        "http": lambda: _FakePage(403, "access denied"),
        "stealthy": lambda: _FakePage(403, "access denied"),
    }
    result = ext.escalate_fetch(fetchers, allowed_strategies=("http", "stealthy"))
    assert result.ok is False
    assert result.page is None
    assert result.error == "ANTI_BOT"
    assert [a.strategy for a in result.attempts] == ["http", "stealthy"]


def test_escalate_fetch_retries_a_transient_exception_once_before_giving_up():
    attempts = {"count": 0}

    def flaky():
        attempts["count"] += 1
        raise TimeoutError("connection timed out")

    result = ext.escalate_fetch({"http": flaky}, allowed_strategies=("http",), max_retries_per_strategy=1)
    assert result.ok is False
    assert attempts["count"] == 2, "exactly one retry (2 total attempts), never an unbounded loop"
    assert result.error == "TRANSIENT_ERROR"


def test_escalate_fetch_never_loops_forever_even_with_a_cycle_in_escalation_map():
    # http -> http (TRANSIENT_ERROR maps back to itself in _ESCALATE_TO) must
    # still terminate because `visited` blocks re-entering an already-tried strategy.
    calls = {"count": 0}

    def always_times_out():
        calls["count"] += 1
        raise TimeoutError()

    result = ext.escalate_fetch({"http": always_times_out}, allowed_strategies=("http",), max_retries_per_strategy=0)
    assert result.ok is False
    assert calls["count"] == 1


def test_escalate_fetch_reports_no_strategy_available_when_allowlist_is_empty():
    result = ext.escalate_fetch({"http": lambda: _FakePage(200, "x")}, allowed_strategies=())
    assert result.ok is False
    assert result.error == "NO_STRATEGY_AVAILABLE"


# ── extract_fields: provenance-tagged, adaptive job-field extraction ──────

JOB_POSTING_JSON_LD = """<html><head>
<script type="application/ld+json">{"@type":"JobPosting","title":"Backend Engineer",
"description":"Build Java services at scale","hiringOrganization":{"name":"Acme"},
"jobLocation":{"address":{"addressLocality":"London"}},"employmentType":"FULL_TIME",
"datePosted":"2026-01-01"}</script>
</head><body><h1>Backend Engineer</h1></body></html>"""


def test_extract_fields_prefers_json_ld_as_the_highest_confidence_source():
    fields = ext.extract_fields(JOB_POSTING_JSON_LD, "https://example.test/jobs/1")
    assert fields["title"].value == "Backend Engineer"
    assert fields["title"].source == "json_ld"
    assert fields["company"] == ext.FieldResult("Acme", "json_ld", 0.95)
    assert fields["location"].value == "London"
    assert fields["employment_type"].value == "FULL_TIME"


def test_extract_fields_json_ld_wins_over_a_disagreeing_rendered_page():
    # The <h1> and og:title disagree with JSON-LD's own title — JSON-LD, the
    # page's own structured claim about itself, must win, never be overwritten
    # by a lower-confidence source found later in the merge order.
    html = """<html><head>
    <meta property="og:title" content="Senior Backend Engineer (Remote)">
    <script type="application/ld+json">{"@type":"JobPosting","title":"Backend Engineer"}</script>
    </head><body><h1>Completely Different Heading</h1></body></html>"""
    fields = ext.extract_fields(html, "https://example.test/jobs/1")
    assert fields["title"].value == "Backend Engineer"
    assert fields["title"].source == "json_ld"


def test_extract_fields_falls_back_to_opengraph_when_no_json_ld_present():
    html = """<html><head>
    <meta property="og:title" content="Backend Engineer at Acme">
    <meta property="og:description" content="Build Java services at scale">
    <link rel="canonical" href="https://example.test/jobs/1">
    </head><body></body></html>"""
    fields = ext.extract_fields(html, "https://example.test/jobs/1")
    assert fields["title"] == ext.FieldResult("Backend Engineer at Acme", "opengraph", 0.6)
    assert fields["canonical_url"].value == "https://example.test/jobs/1"


def test_extract_fields_falls_back_to_adaptive_css_when_no_structured_metadata_exists():
    html = '<html><body><h1>Backend Engineer</h1><div class="company-name">Acme Corp</div></body></html>'
    fields = ext.extract_fields(html, "https://example.test/jobs/1")
    assert fields["title"] == ext.FieldResult("Backend Engineer", "css_selector", 0.55)
    assert fields["company"] == ext.FieldResult("Acme Corp", "css_selector", 0.55)


def test_extract_fields_recovers_a_relocated_element_after_a_page_redesign():
    """The core §5 proof: a selector that genuinely no longer matches the
    live DOM is still recovered via Scrapling's own adaptive relocate(),
    using structural similarity at the saved tree position — not a lucky
    coincidental selector match."""
    storage = os.path.join(tempfile.mkdtemp(), "adaptive.db")
    html_before_redesign = (
        '<html><body><h1>Backend Engineer</h1><div class="company-name">Acme Corp</div></body></html>'
    )
    ext.extract_fields(html_before_redesign, "https://boards.example.org/acme/jobs/1", storage)

    html_after_redesign = (
        '<html><body><h1>Backend Engineer</h1><div class="employer-label">Acme Corp</div></body></html>'
    )
    fields = ext.extract_fields(html_after_redesign, "https://boards.example.org/acme/jobs/1", storage)
    assert fields["company"].value == "Acme Corp"
    assert fields["company"].source == "css_selector"


def test_extract_fields_reports_no_adaptive_recovery_when_the_element_is_genuinely_gone():
    storage = os.path.join(tempfile.mkdtemp(), "adaptive.db")
    html_before = '<html><body><h1>Backend Engineer</h1><div class="company-name">Acme Corp</div></body></html>'
    ext.extract_fields(html_before, "https://boards.example.org/acme/jobs/1", storage)

    html_removed_entirely = "<html><body><main>This job posting has been removed.</main></body></html>"
    fields = ext.extract_fields(html_removed_entirely, "https://boards.example.org/acme/jobs/1", storage)
    assert "company" not in fields, "nothing structurally similar exists — must report absence, never a fabricated guess"
    assert "title" not in fields


def test_extract_fields_a_rejected_bad_relocation_never_poisons_storage_for_later_genuine_recovery():
    """Regression proof for a real bug found while writing these tests:
    Scrapling's combined ``css(..., adaptive=True, auto_save=True)`` RE-SAVES
    whatever `relocate()` returns, even a wrong candidate this module's own
    tag-consistency check would reject — corrupting the saved fingerprint for
    every later lookup. `_try_direct_then_relocate` never re-saves a
    relocated candidate (good or bad), so the ORIGINAL saved element must
    still be recoverable after an intervening page version matched nothing
    plausible at all.
    """
    storage = os.path.join(tempfile.mkdtemp(), "adaptive.db")
    html_v1 = '<html><body><h1>Backend Engineer</h1><div class="company-name">Acme Corp</div></body></html>'
    ext.extract_fields(html_v1, "https://boards.example.org/acme/jobs/1", storage)

    # An intervening fetch (e.g. a temporary "removed" placeholder) that
    # would have corrupted storage under the old, combined-flag approach.
    html_removed = "<html><body><main>This job posting has been removed.</main></body></html>"
    fields_removed = ext.extract_fields(html_removed, "https://boards.example.org/acme/jobs/1", storage)
    assert "title" not in fields_removed

    # The real redesign this whole mechanism exists for: a genuinely similar
    # element at the same structural position, just with different markup —
    # must still recover correctly using the ORIGINAL (uncorrupted) fingerprint.
    html_v2_redesigned = '<html><body><h1>Backend Engineer</h1><div class="employer-label">Acme Corp</div></body></html>'
    fields_v2 = ext.extract_fields(html_v2_redesigned, "https://boards.example.org/acme/jobs/1", storage)
    assert fields_v2["company"].value == "Acme Corp"


def test_extract_fields_rejects_empty_and_boilerplate_values():
    assert ext._is_plausible("title", "") is False
    assert ext._is_plausible("title", None) is False
    assert ext._is_plausible("title", "Loading...") is False
    assert ext._is_plausible("company", "N/A") is False
    assert ext._is_plausible("title", "x" * 400) is False, "an implausibly long title is rejected, not trusted verbatim"
    assert ext._is_plausible("title", "Backend Engineer") is True


def test_extract_fields_json_ld_description_strips_embedded_html():
    html = """<html><head><script type="application/ld+json">
    {"@type":"JobPosting","title":"Engineer","description":"<p>Build <b>great</b> things</p>"}
    </script></head><body></body></html>"""
    fields = ext.extract_fields(html, "https://example.test/jobs/1")
    assert fields["description"].value == "Build great things"


def test_extract_fields_handles_json_ld_as_an_array_of_candidates():
    html = """<html><head><script type="application/ld+json">
    [{"@type":"BreadcrumbList"},{"@type":"JobPosting","title":"Engineer"}]
    </script></head><body></body></html>"""
    fields = ext.extract_fields(html, "https://example.test/jobs/1")
    assert fields["title"].value == "Engineer"


def test_extract_fields_ignores_malformed_json_ld_without_crashing():
    html = '<html><head><script type="application/ld+json">{not valid json</script></head><body></body></html>'
    fields = ext.extract_fields(html, "https://example.test/jobs/1")
    assert "title" not in fields  # falls through silently, no exception propagates


# ── DomainThrottle ──────────────────────────────────────────────────────

def test_domain_throttle_enforces_minimum_delay_on_the_same_host():
    throttle = ext.DomainThrottle(min_delay_seconds=5.0)
    slept = []
    throttle.wait("https://example.test/a", sleep=slept.append)
    assert slept == []  # first request to this host: no wait
    throttle.wait("https://example.test/b", sleep=slept.append)
    assert len(slept) == 1 and slept[0] > 0, "second request to the SAME host within the window must wait"


def test_domain_throttle_never_delays_requests_to_different_hosts():
    throttle = ext.DomainThrottle(min_delay_seconds=5.0)
    slept = []
    throttle.wait("https://a.example.test/x", sleep=slept.append)
    throttle.wait("https://b.example.test/x", sleep=slept.append)
    assert slept == []


def test_domain_throttle_is_a_no_op_when_configured_to_zero():
    throttle = ext.DomainThrottle(min_delay_seconds=0)
    slept = []
    throttle.wait("https://example.test/a", sleep=slept.append)
    throttle.wait("https://example.test/a", sleep=slept.append)
    assert slept == []


# ── cap_content ───────────────────────────────────────────────────────────

def test_cap_content_truncates_oversized_content():
    content, truncated = ext.cap_content("x" * 1000, 100)
    assert truncated is True
    assert len(content.encode("utf-8")) <= 100


def test_cap_content_leaves_small_content_untouched():
    content, truncated = ext.cap_content("hello", 1000)
    assert truncated is False
    assert content == "hello"


# ── is_disallowed_url ───────────────────────────────────────────────────

@pytest.mark.parametrize("url", [
    "http://localhost:8080/x",
    "http://127.0.0.1/x",
    "http://169.254.169.254/latest/meta-data",  # cloud metadata endpoint
    "http://10.0.0.5/x",
    "http://192.168.1.1/x",
    "http://172.16.0.1/x",
    "ftp://example.test/x",
    "file:///etc/passwd",
])
def test_is_disallowed_url_blocks_internal_and_unsupported_targets(url):
    assert ext.is_disallowed_url(url) is not None


@pytest.mark.parametrize("url", [
    "https://example.test/jobs/1",
    "http://boards.greenhouse.io/acme/jobs/1",
    "https://172.32.0.1/x",  # just outside the 172.16/12 private range
])
def test_is_disallowed_url_allows_ordinary_public_targets(url):
    assert ext.is_disallowed_url(url) is None
