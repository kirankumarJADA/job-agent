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

const SENSITIVE_PARAM_PATTERN = /password|passwd|pwd|passcode|token|lease[_-]?token|secret|otp|pin|credential|api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|id[_-]?token|private[_-]?key|client[_-]?secret|session|xsrf|csrf|auth(?:orization)?|^(?:code|key|magic|email|username|sig|signature|jwt|sid|nonce|ticket|verifier)$|magic[-_]?(?:code|key|link|token)|(?:verify|verification|activation|auth|access|reset|confirm|invite|security|login|otp)[-_]?(?:code|key|token)|(?:^|[_-])(?:sig|signature|jwt|sid|pin|nonce|ticket|verifier|lease_?token)$/i;

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

const SENSITIVE_KEY_NAME = /^(?:password|passwd|pwd|passcode|token|lease[_-]?token|secret|otp|pin|credential|api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|id[_-]?token|private[_-]?key|client[_-]?secret|session(?:_id)?|xsrf|csrf|auth(?:orization)?|sig|signature|jwt|sid|nonce|ticket|verifier|magic|verification[_-]?code|activation[_-]?code|cookie)$/i;

function scrubString(text) {
  const str = String(text ?? '');
  return str
    .replace(/\b(https?:\/\/)([^/\s:@"']+:[^/\s@"']+)@/gi, '$1[REDACTED]@')
    .replace(
      /\b(cookie|set-cookie)\s*:\s*(?:(?:[A-Za-z0-9_.-]+\s*=\s*(?:\\"(?:[^"\\\r\n]|\\.)*\\"|"(?:[^"\\\r\n]|\\.)*"|'(?:[^'\\\r\n]|\\.)*'|[^\s;\r\n<>"']+))(?:\s*;\s*[A-Za-z0-9_.-]+\s*=\s*(?:\\"(?:[^"\\\r\n]|\\.)*\\"|"(?:[^"\\\r\n]|\\.)*"|'(?:[^'\\\r\n]|\\.)*'|[^\s;\r\n<>"']+))*|[^\r\n<>"']+)/gi,
      '$1: [REDACTED]'
    )
    .replace(/\bauthorization\s*:\s*(?:bearer|basic|token|apikey)\s+[A-Za-z0-9._~+/=-]+/gi, 'Authorization: [REDACTED]')
    .replace(/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{6,}/gi, '$1 [REDACTED]')
    .replace(
      /"([A-Za-z0-9_-]*(?:password|passwd|pwd|passcode|otp|pin|token|lease[_-]?token|cookie|secret|api[_-]?key|apikey|credential|session_id|xsrf|csrf|sig|signature|jwt|sid|nonce|ticket|verifier|verification[_-]?code|activation[_-]?code|auth(?:orization)?)[A-Za-z0-9_-]*)"\s*:\s*"(?:[^"\\]|\\.)*"/gi,
      '"$1": "[REDACTED]"'
    )
    .replace(
      /"([A-Za-z0-9_-]*(?:password|passwd|pwd|passcode|otp|pin|token|lease[_-]?token|secret|api[_-]?key|apikey|credential|sig|signature|jwt|sid|nonce|ticket|verifier|code|verification[_-]?code|activation[_-]?code)[A-Za-z0-9_-]*)"\s*:\s*(?:\d{1,12}|true|false)/gi,
      '"$1": "[REDACTED]"'
    )
    .replace(
      /\b([A-Za-z0-9_-]*(?:password|passwd|pwd|passcode|otp|pin|token|lease[_-]?token|cookie|secret|api[_-]?key|apikey|credential|session(?:_id)?|xsrf|csrf|sig|signature|jwt|sid|nonce|ticket|verifier|verification[_-]?code|activation[_-]?code|auth(?:orization)?)|code|key|magic|email|username)=([^\s,;&"'<>)\]}]+)/gi,
      '$1=[REDACTED]'
    )
    .replace(
      /\b([A-Za-z0-9_-]*(?:api[_-]?key|apikey|secret|token|lease[_-]?token|credential|password|passwd|pwd|passcode|session_id|otp|pin|jwt|sid|sig|signature|nonce|ticket|verifier))\s*:\s*(?:\\"(?:[^"\\\r\n]|\\.)*\\"|"(?:[^"\\\r\n]|\\.)*"|'(?:[^'\\\r\n]|\\.)*'|\d+|[^\s,;"'<>]+)/gi,
      '$1: [REDACTED]'
    )
    // Stage 3I Phase 6: a bare numeric "code: 482913" (4+ digits, so HTTP statuses stay readable)
    .replace(/\b((?:security |login |auth |access |verification |confirmation )?code)\s*:\s*(\d{4,10})\b/gi, '$1: [REDACTED]')
    // Stage 3I Phase 6: bare JSON Web Tokens anywhere in free text
    .replace(/\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}/g, '[REDACTED_JWT]');
}

function redactRecursive(value, seen) {
  if (value === null || value === undefined) return value;
  if (typeof value === 'string') return scrubString(value);
  if (typeof value !== 'object') return value;
  if (seen.has(value)) return '[Circular]';
  seen.add(value);
  if (value instanceof Error) {
    const out = { name: value.name, message: scrubString(value.message) };
    if (value.reason !== undefined) out.reason = scrubString(value.reason);
    if (value.stack) out.stack = scrubString(value.stack);
    return out;
  }
  if (Array.isArray(value)) {
    return value.map((item) => redactRecursive(item, seen));
  }
  const copy = {};
  for (const [k, v] of Object.entries(value)) {
    if (SENSITIVE_KEY_NAME.test(k)) {
      copy[k] = '[REDACTED]';
    } else {
      copy[k] = redactRecursive(v, seen);
    }
  }
  return copy;
}

/**
 * Redacts secrets out of free-text, Errors, or structured event objects.
 * Non-string values (objects / Error / Arrays) return deep-cloned redacted
 * structures so caller objects are never mutated.
 */
export function redactSecrets(input) {
  if (input === null || input === undefined) return '';
  if (typeof input === 'string') return scrubString(input);
  if (typeof input === 'object') return redactRecursive(input, new WeakSet());
  return scrubString(String(input));
}
