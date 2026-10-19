"""Multi-strategy fetch escalation and adaptive job-field extraction.

Robin x Tsenta — Scrapling hardening. Separated from ``app.py`` (which stays a
thin FastAPI transport layer) so this module's logic is unit-testable without
spinning up HTTP, and so the fetcher callables it escalates across can be
injected directly in tests rather than requiring a real browser toolchain.

Everything here is built against the REAL, pinned ``scrapling==0.4.15`` API
(see ``requirements.txt``): ``Fetcher.get``, ``DynamicFetcher.fetch``,
``StealthyFetcher.fetch`` for fetching, and ``scrapling.parser.Selector``'s
documented adaptive mechanism (``adaptive=True`` at construction,
``.css(selector, identifier=..., adaptive=True, auto_save=True,
percentage=40)``, ``.find_similar()``) for field recovery. No Scrapling API
is invented here — every call shape was verified against the pinned
version's own source before being used.
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Iterable
from urllib.parse import urlparse

log = logging.getLogger("scrapling-sidecar.extraction")

# ── Bounded multi-strategy fetch escalation ──────────────────────────────

# Evidence-based order: cheapest first, browser-backed only when the cheaper
# strategy's own result gives a concrete reason to escalate. Never more than
# len(STRATEGY_ORDER) real fetch attempts per request — no open-ended retry.
STRATEGY_ORDER = ("http", "dynamic", "stealthy")

# Signals that the page needs a browser, scraped from real JS-shell pages
# (webpack/the-page-is-almost-empty-but-for-a-script-tag) rather than
# invented — kept narrow and case-insensitive so it never over-fires on a
# page that merely mentions these words in body copy.
_JS_REQUIRED_MARKERS = (
    "you need to enable javascript",
    "please enable javascript",
    "requires javascript",
    "noscript",
)
_ANTI_BOT_MARKERS = (
    "checking your browser",
    "cloudflare",
    "captcha",
    "are you human",
    "access denied",
    "attention required",
)


@dataclass(frozen=True)
class FetchAttempt:
    strategy: str
    outcome: str  # SUCCESS | EMPTY | JS_REQUIRED | ANTI_BOT | HTTP_ERROR | TRANSIENT_ERROR | ERROR
    status: int | None = None
    detail: str | None = None  # exception class name or short reason — never page content


@dataclass
class EscalationResult:
    page: Any | None
    strategy_used: str | None
    attempts: list[FetchAttempt] = field(default_factory=list)
    ok: bool = False
    error: str | None = None


def _looks_js_required(text: str) -> bool:
    lowered = text.lower()
    # A near-empty body is itself the strongest signal a JS-rendered page
    # hasn't rendered yet — most SPA shells ship under ~2KB of real text.
    if len(text.strip()) < 200 and ("<div id=\"root\"" in lowered or "<div id=\"app\"" in lowered):
        return True
    return any(marker in lowered for marker in _JS_REQUIRED_MARKERS)


def _looks_anti_bot(status: int, text: str) -> bool:
    if status in (403, 429, 503):
        return True
    lowered = text.lower()
    return any(marker in lowered for marker in _ANTI_BOT_MARKERS)


def classify_outcome(status: int, text: str) -> str:
    """Categorize a fetched page's body — the evidence used to decide
    whether escalating to a stronger strategy has a reasonable chance of
    helping, rather than treating every non-200 or empty page the same way.
    """
    if not text or not text.strip():
        return "EMPTY"
    if _looks_anti_bot(status, text):
        return "ANTI_BOT"
    if status >= 400:
        return "HTTP_ERROR"
    if _looks_js_required(text):
        return "JS_REQUIRED"
    return "SUCCESS"


# Which outcome at one strategy justifies trying the next one. ANTI_BOT only
# ever escalates to `stealthy` (the one strategy with a documented countermeasure,
# solve_cloudflare); escalating ANTI_BOT to `dynamic` would just hit the same
# wall with an ordinary browser and waste a full browser launch.
_ESCALATE_TO = {
    "http": {"EMPTY": "dynamic", "JS_REQUIRED": "dynamic", "ANTI_BOT": "stealthy", "TRANSIENT_ERROR": "http"},
    "dynamic": {"EMPTY": "stealthy", "ANTI_BOT": "stealthy", "TRANSIENT_ERROR": "dynamic"},
}


def escalate_fetch(
    fetchers: dict[str, Callable[[], Any]],
    allowed_strategies: Iterable[str],
    max_retries_per_strategy: int = 1,
) -> EscalationResult:
    """Run the bounded fallback sequence: try the first allowed strategy, and
    only escalate to a stronger one when the actual result gives a specific,
    evidence-based reason to (never because a request merely returned
    non-200 — ``classify_outcome`` decides that from the real body).

    ``fetchers`` maps strategy name -> zero-arg callable returning a
    Scrapling page/Response object (or raising). Each strategy runs at most
    ``1 + max_retries_per_strategy`` times before moving on or giving up —
    this is the bound that prevents an unbounded retry loop regardless of
    how many times escalation logic is invoked.
    """
    allowed = [s for s in STRATEGY_ORDER if s in allowed_strategies and s in fetchers]
    if not allowed:
        return EscalationResult(page=None, strategy_used=None, ok=False, error="NO_STRATEGY_AVAILABLE")

    attempts: list[FetchAttempt] = []
    strategy = allowed[0]
    visited: set[str] = set()
    while strategy and strategy not in visited:
        visited.add(strategy)
        page, outcome, status, detail = _run_one_strategy(fetchers[strategy], strategy, max_retries_per_strategy)
        attempts.append(FetchAttempt(strategy=strategy, outcome=outcome, status=status, detail=detail))

        if outcome == "SUCCESS":
            return EscalationResult(page=page, strategy_used=strategy, attempts=attempts, ok=True)

        next_strategy = _ESCALATE_TO.get(strategy, {}).get(outcome)
        if next_strategy and next_strategy in allowed and next_strategy not in visited:
            strategy = next_strategy
            continue
        break

    final = attempts[-1] if attempts else None
    return EscalationResult(
        page=None, strategy_used=None, attempts=attempts, ok=False,
        error=(final.outcome if final else "NO_ATTEMPTS"),
    )


def _run_one_strategy(fetch_fn: Callable[[], Any], strategy: str, max_retries: int) -> tuple[Any | None, str, int | None, str | None]:
    last_detail = None
    for attempt in range(1 + max(0, max_retries)):
        try:
            page = fetch_fn()
        except Exception as exc:  # noqa: BLE001 - classify, never leak page content or raise through
            last_detail = type(exc).__name__
            # A bounded, policy-controlled retry: only once per strategy by
            # default, and only on an exception (a real transient condition),
            # never on a merely-unwanted-but-successful HTTP response.
            if attempt < max_retries:
                continue
            return None, "TRANSIENT_ERROR", None, last_detail
        status = int(getattr(page, "status", 0) or 0)
        text = getattr(page, "html_content", None) or ""
        outcome = classify_outcome(status, text)
        return page, outcome, status, None
    return None, "TRANSIENT_ERROR", None, last_detail


# ── Adaptive, provenance-tagged job-field extraction ─────────────────────

JOB_FIELDS = (
    "title", "company", "location", "description", "employment_type",
    "salary_text", "posted_at", "canonical_url", "application_url",
)


@dataclass(frozen=True)
class FieldResult:
    value: str | None
    source: str  # json_ld | opengraph | css_selector | adaptive_recovery | none
    confidence: float


def _clean_text(value: str | None) -> str | None:
    if value is None:
        return None
    collapsed = re.sub(r"\s+", " ", value).strip()
    return collapsed or None


def _is_plausible(field_name: str, value: str | None) -> bool:
    """Rejects empty, boilerplate, or obviously-wrong values rather than
    accepting whatever a selector happened to match. Adaptive recovery in
    particular only PROVES an element exists at a similar tree position —
    this is the separate check that the actual text makes sense for the
    field it claims to be.
    """
    if not value:
        return False
    lowered = value.lower().strip()
    if field_name in ("title", "company", "location") and len(lowered) < 2:
        return False
    if field_name == "title" and len(value) > 300:
        return False
    boilerplate = {"loading...", "loading", "n/a", "undefined", "null", "click here", "see below"}
    if lowered in boilerplate:
        return False
    return True


def _from_json_ld(selector: Any) -> dict[str, FieldResult]:
    """Prefers structured JobPosting JSON-LD when present and well-formed —
    the single highest-confidence source, since it is the page's own
    machine-readable claim about itself rather than anything inferred from
    rendered markup.
    """
    import orjson

    results: dict[str, FieldResult] = {}
    for script in selector.css("script[type='application/ld+json']"):
        raw = script.text.get() if hasattr(script.text, "get") else str(script.text)
        # Scrapling's TextHandler is a `str` SUBCLASS, which orjson's Rust
        # bindings reject outright ("Input must be bytes... or str") despite
        # `isinstance(raw, str)` being true — cast to a plain `str` first.
        raw = str(raw) if raw is not None else ""
        if not raw.strip():
            continue
        try:
            data = orjson.loads(raw)
        except Exception:  # noqa: BLE001 - a page's own malformed JSON-LD is not our bug to crash on
            continue
        candidates = data if isinstance(data, list) else [data]
        for candidate in candidates:
            if not isinstance(candidate, dict):
                continue
            if str(candidate.get("@type", "")).lower() != "jobposting":
                continue
            title = _clean_text(candidate.get("title"))
            if title and _is_plausible("title", title):
                results["title"] = FieldResult(title, "json_ld", 0.95)
            org = candidate.get("hiringOrganization")
            company = org.get("name") if isinstance(org, dict) else org
            company = _clean_text(company)
            if company and _is_plausible("company", company):
                results["company"] = FieldResult(company, "json_ld", 0.95)
            loc = candidate.get("jobLocation")
            if isinstance(loc, list) and loc:
                loc = loc[0]
            if isinstance(loc, dict):
                addr = loc.get("address") or {}
                parts = [addr.get("addressLocality"), addr.get("addressRegion"), addr.get("addressCountry")] \
                    if isinstance(addr, dict) else []
                location = _clean_text(", ".join(p for p in parts if p))
            else:
                location = _clean_text(loc) if isinstance(loc, str) else None
            if location and _is_plausible("location", location):
                results["location"] = FieldResult(location, "json_ld", 0.9)
            description = _clean_text(candidate.get("description"))
            if description:
                # JSON-LD descriptions are frequently HTML-escaped fragments;
                # strip tags conservatively rather than claim raw markup as text.
                description = _clean_text(re.sub(r"<[^>]+>", " ", description))
            if description and _is_plausible("description", description):
                results["description"] = FieldResult(description, "json_ld", 0.9)
            employment = _clean_text(candidate.get("employmentType"))
            if employment:
                results["employment_type"] = FieldResult(employment, "json_ld", 0.9)
            posted = _clean_text(candidate.get("datePosted"))
            if posted:
                results["posted_at"] = FieldResult(posted, "json_ld", 0.85)
            salary = candidate.get("baseSalary")
            if isinstance(salary, dict):
                value = salary.get("value")
                if isinstance(value, dict):
                    lo, hi, cur = value.get("minValue"), value.get("maxValue"), salary.get("currency")
                    text = " - ".join(str(v) for v in (lo, hi) if v is not None)
                    if text:
                        results["salary_text"] = FieldResult(f"{text} {cur or ''}".strip(), "json_ld", 0.85)
            app_url = _clean_text(candidate.get("url"))
            if app_url:
                results["application_url"] = FieldResult(app_url, "json_ld", 0.85)
    return results


def _from_opengraph(selector: Any) -> dict[str, FieldResult]:
    results: dict[str, FieldResult] = {}
    og_title = selector.css("meta[property='og:title']::attr(content)").get()
    title = _clean_text(og_title)
    if title and _is_plausible("title", title):
        results.setdefault("title", FieldResult(title, "opengraph", 0.6))
    og_desc = selector.css("meta[property='og:description']::attr(content)").get() \
        or selector.css("meta[name='description']::attr(content)").get()
    description = _clean_text(og_desc)
    if description and _is_plausible("description", description):
        results.setdefault("description", FieldResult(description, "opengraph", 0.5))
    canonical = selector.css("link[rel='canonical']::attr(href)").get()
    canonical = _clean_text(canonical)
    if canonical:
        results.setdefault("canonical_url", FieldResult(canonical, "opengraph", 0.7))
    return results


# Label text that commonly precedes the real value in rendered career-site
# markup — used only to pick a STARTING selector for adaptive save/retrieve,
# never trusted as the value itself. Intentionally narrow: a miss here just
# means the field falls through to the Java-side regex/heuristic parser,
# never a wrong guess reported with false confidence.
_LABEL_HINTS: dict[str, tuple[str, ...]] = {
    "title": ("h1",),
    "company": (".company-name", "[class*='company']", "[data-company]"),
    "location": (".location", "[class*='location']", "[data-location]"),
    "description": (".job-description", "[class*='description']", "#content"),
    "employment_type": ("[class*='employment-type']", "[class*='job-type']"),
    "salary_text": ("[class*='salary']", "[class*='compensation']"),
}


def _text_of(node: Any) -> str | None:
    text = node.text
    return _clean_text(text.get() if hasattr(text, "get") else str(text))


def _try_direct_then_relocate(selector: Any, hint: str, identifier: str, percentage: int = 40) -> Any | None:
    """Explicit save/retrieve/relocate, deliberately NOT Scrapling's combined
    ``css(..., adaptive=True, auto_save=True)`` convenience path.

    That convenience path re-saves whatever ``relocate()`` returns whenever
    ``auto_save=True`` — including a WRONG relocation — which permanently
    poisons the saved fingerprint for every later call on this identifier
    (verified directly: one bad relocation to an unrelated ``<main>``
    rewrites the stored element to BE that ``<main>``, so a subsequent
    ``retrieve()`` for "what was originally saved" returns the corrupted
    data, not the original). Doing the three steps explicitly keeps control
    over exactly when a save happens:

    1. A fresh, non-adaptive CSS match is a genuine answer — save it, always.
    2. A relocated match only gets validated here (by the caller, via
       ``_tag_is_consistent``-equivalent reasoning against the PRE-FETCH
       ORIGINAL tag) and, if accepted, is used for this call's value — but
       the stored fingerprint under this identifier is NEVER overwritten by
       a relocation, good or bad, so a later page redesign can still recover
       the ORIGINAL known-good element rather than relocating from whatever
       a previous uncertain relocation happened to land on.
    """
    try:
        direct = selector.css(hint)
    except Exception:  # noqa: BLE001 - an invalid/unsupported selector on this page is not fatal
        return None
    if direct:
        try:
            selector.save(direct[0]._root if hasattr(direct[0], "_root") else direct[0], identifier)
        except Exception:  # noqa: BLE001 - saving is a best-effort convenience, never fatal to this call's result
            pass
        return direct[0]

    try:
        original = selector.retrieve(identifier)
    except Exception:  # noqa: BLE001 - no adaptive storage configured is not an error here
        return None
    if not original:
        return None
    try:
        relocated = selector.relocate(original, percentage, True)
    except Exception:  # noqa: BLE001 - relocation failing outright is just "no candidate", not a crash
        return None
    if not relocated:
        return None
    candidate = relocated[0]
    candidate_tag = getattr(candidate, "tag", None)
    if candidate_tag is not None and candidate_tag != original.get("tag"):
        # Scrapling's relocate() proved STRUCTURAL similarity; it is not
        # this module's proof of SEMANTIC correctness. A tag mismatch against
        # the ORIGINAL saved element (fetched before this relocate attempt,
        # never corrupted by it) is rejected outright.
        return None
    return candidate


def _from_adaptive_css(selector: Any) -> dict[str, FieldResult]:
    """For fields JSON-LD/OpenGraph didn't supply: try a narrow, evidence-
    backed CSS hint, falling back to Scrapling's adaptive relocate() when the
    hint no longer matches directly (see ``_try_direct_then_relocate``).
    """
    results: dict[str, FieldResult] = {}
    for field_name, hints in _LABEL_HINTS.items():
        for hint in hints:
            identifier = f"job:{field_name}"
            candidate = _try_direct_then_relocate(selector, hint, identifier)
            if candidate is None:
                continue
            text = _text_of(candidate)
            if text and _is_plausible(field_name, text):
                # A relocated/adaptive-recovered match carries lower confidence
                # than a fresh one — Scrapling's own relocate() only proves
                # structural similarity, not semantic correctness; the
                # `_is_plausible` check (plus the tag-consistency check inside
                # `_try_direct_then_relocate`) are this module's own additional
                # gates on top of that, never a substitute for it.
                results[field_name] = FieldResult(text, "css_selector", 0.55)
                break
    return results


def build_adaptive_selector(html: str, url: str, storage_file: str | None = None) -> Any:
    """Constructs a Scrapling ``Selector`` with adaptive tracking enabled,
    keyed by this page's own URL so saved selectors from one job board never
    leak into another's relocation attempts.
    """
    from scrapling.parser import Selector

    kwargs: dict[str, Any] = {"content": html, "url": url, "adaptive": True}
    if storage_file:
        kwargs["storage_args"] = {"storage_file": storage_file, "url": url}
    return Selector(**kwargs)


def extract_fields(html: str, url: str, storage_file: str | None = None) -> dict[str, FieldResult]:
    """The full field-extraction pipeline for one fetched page: JSON-LD first
    (highest confidence, a page's own structured claim), then OpenGraph/meta,
    then adaptive CSS recovery for whatever is still missing — never letting
    a later, lower-confidence source overwrite an earlier, higher-confidence
    one for the same field.
    """
    selector = build_adaptive_selector(html, url, storage_file)
    merged: dict[str, FieldResult] = {}
    for extractor in (_from_json_ld, _from_opengraph, _from_adaptive_css):
        try:
            found = extractor(selector)
        except Exception as exc:  # noqa: BLE001 - one extractor's bug must not cost the others
            log.warning("field extractor %s failed: %s", extractor.__name__, type(exc).__name__)
            continue
        for key, result in found.items():
            if key not in merged:
                merged[key] = result
    return merged


# ── Bounded safety: per-domain throttling and content size limits ───────


class DomainThrottle:
    """Minimum delay between requests to the same host, enforced in-process.
    Not a distributed rate limiter — this sidecar is a single local process,
    so in-memory state is the right scope for it.
    """

    def __init__(self, min_delay_seconds: float = 1.0):
        self.min_delay_seconds = max(0.0, min_delay_seconds)
        self._last_request_at: dict[str, float] = {}

    def wait(self, url: str, sleep: Callable[[float], None] = time.sleep) -> float:
        host = urlparse(url).hostname or ""
        if not host or self.min_delay_seconds <= 0:
            return 0.0
        now = time.monotonic()
        last = self._last_request_at.get(host)
        remaining = (last + self.min_delay_seconds - now) if last is not None else 0.0
        if remaining > 0:
            sleep(remaining)
        self._last_request_at[host] = time.monotonic()
        return max(0.0, remaining)


def cap_content(content: str, max_bytes: int) -> tuple[str, bool]:
    """Returns (possibly-truncated content, was_truncated). Caps on byte
    length of the UTF-8 encoding, not character count, so the cap is a real
    memory/transport bound rather than an approximation.
    """
    encoded = content.encode("utf-8")
    if len(encoded) <= max_bytes:
        return content, False
    return encoded[:max_bytes].decode("utf-8", errors="ignore"), True


def is_disallowed_url(url: str) -> str | None:
    """Returns a reason string when a URL must never be fetched, else None.
    Deliberately narrow — this blocks fetching Robin's own internal network,
    not a content/policy judgment (that stays a human/operator decision, not
    something this sidecar silently decides on robots.txt or ToS grounds)."""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return "UNSUPPORTED_SCHEME"
    host = (parsed.hostname or "").lower()
    if not host:
        return "NO_HOST"
    if host in ("localhost", "0.0.0.0") or host.startswith("127.") or host.startswith("169.254."):
        return "LOOPBACK_OR_LINK_LOCAL_TARGET"
    if host.startswith("10.") or host.startswith("192.168."):
        return "PRIVATE_NETWORK_TARGET"
    if re.match(r"^172\.(1[6-9]|2\d|3[01])\.", host):
        return "PRIVATE_NETWORK_TARGET"
    return None
