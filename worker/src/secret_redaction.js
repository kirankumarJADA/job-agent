/**
 * Phase 8.5 continuation (credential-leak hardening): one place that decides
 * what a URL or an error string looks like once it is safe to write to the
 * worker's own on-disk crash-recovery state, an emitted event, or a log line.
 *
 * <p>The concrete incident this exists to close: a GET-method form (the
 * account-lifecycle mock test fixture used one for simplicity) submits its
 * fields as a query string, so `page.url()` immediately after that submit
 * can contain the just-typed password in plain text. `browser_worker.js`
 * already records `page.url()` on every completed step (`markStep(...,
 * {currentUrl})`) purely for crash-resume (`state.lastUrl` is `page.goto`'d
 * on restart) — that mechanism has nothing to do with credentials and must
 * not become a place one leaks into. Every URL that reaches persisted state
 * goes through {@link sanitizeUrl} first; a resumed run still gets a
 * navigable URL back (redaction replaces a sensitive query/fragment value,
 * it never removes the parameter or changes the path), it just never gets
 * the secret back — which is fine, because no part of this plan's resume
 * logic depends on a query-string value surviving a restart; every field a
 * resumed run needs is re-filled by the plan's own steps.
 */

const SENSITIVE_PARAM_PATTERN = /password|passwd|pwd|token|secret|otp|credential|api[_-]?key|session|auth(?:orization)?/i;

/**
 * Returns a version of `rawUrl` safe to persist or log: query-parameter and
 * fragment values whose *name* looks sensitive are replaced with
 * `[REDACTED]`; any embedded `user:pass@host` credentials are stripped. An
 * unparseable value never passes through unredacted — it becomes a fixed,
 * safe placeholder instead, since "could not confirm this is safe" must
 * fail closed, the same direction every other safety check in this codebase
 * takes.
 */
export function sanitizeUrl(rawUrl) {
  if (rawUrl === null || rawUrl === undefined) return rawUrl;
  let url;
  try {
    url = new URL(String(rawUrl));
  } catch {
    return '[unparseable URL]';
  }
  url.username = '';
  url.password = '';
  for (const key of [...url.searchParams.keys()]) {
    if (SENSITIVE_PARAM_PATTERN.test(key)) url.searchParams.set(key, '[REDACTED]');
  }
  if (url.hash && url.hash.length > 1 && url.hash.includes('=')) {
    try {
      const fragmentParams = new URLSearchParams(url.hash.slice(1));
      let changed = false;
      for (const key of [...fragmentParams.keys()]) {
        if (SENSITIVE_PARAM_PATTERN.test(key)) { fragmentParams.set(key, '[REDACTED]'); changed = true; }
      }
      if (changed) url.hash = `#${fragmentParams.toString()}`;
    } catch {
      // Not a key=value fragment (e.g. a plain anchor) — nothing to redact.
    }
  }
  return url.toString();
}

/**
 * Redacts `name=value`-shaped secrets out of free-text (typically an error
 * message that may itself embed a URL or a raw query string). Matching is
 * intentionally conservative toward over-redaction rather than under-redaction:
 * a key name alone (no `=value`) is still redacted for the original, narrower
 * set of names this already covered before this change (password/otp/token/
 * cookie/secret) to preserve exact prior behaviour; the newly added names
 * (credential, api key, session, auth) require an explicit `=value` so
 * ordinary words like "author" or "session expired" are not flagged as a
 * leak with nothing actually sensitive attached.
 */
export function redactSecrets(text) {
  const str = String(text ?? '');
  return str
    .replace(/(password|otp|token|cookie|secret)=?[^\s,;]*/gi, '$1=[REDACTED]')
    .replace(/\b(credential|api[_-]?key|session|auth(?:orization)?)=[^\s,;]*/gi, '$1=[REDACTED]');
}
