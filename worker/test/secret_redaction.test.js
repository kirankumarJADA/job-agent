import test from 'node:test';
import assert from 'node:assert/strict';
import { sanitizeUrl, redactSecrets } from '../src/secret_redaction.js';

// ── sanitizeUrl ──────────────────────────────────────────────────────────

test('redacts a password submitted via a GET form query string', () => {
  const result = sanitizeUrl('https://mockemployer.test/verify?email=c%40x.test&password=hunter2');
  assert.ok(!result.includes('hunter2'), 'the raw password must not survive');
  assert.match(result, /password=%5BREDACTED%5D|password=\[REDACTED\]/);
  assert.match(result, /email=c%40x\.test/, 'non-sensitive params are preserved verbatim');
});

test('redacts every sensitive parameter name, case-insensitively, leaving the rest alone', () => {
  const url = 'https://x.test/path?Token=abc123&otp=445566&api_key=sk-live-zzz&session=sess1&q=hello';
  const result = sanitizeUrl(url);
  for (const secret of ['abc123', '445566', 'sk-live-zzz', 'sess1']) {
    assert.ok(!result.includes(secret), `${secret} must be redacted`);
  }
  assert.match(result, /q=hello/);
});

test('redacts an OAuth-style implicit-flow fragment, not just the query string', () => {
  const result = sanitizeUrl('https://x.test/callback#access_token=secret-tok&state=xyz');
  assert.ok(!result.includes('secret-tok'));
  assert.match(result, /state=xyz/);
});

test('leaves a plain, non-key=value fragment untouched', () => {
  const result = sanitizeUrl('https://x.test/docs#password-reset-help');
  assert.equal(result, 'https://x.test/docs#password-reset-help');
});

test('strips embedded userinfo credentials from the URL authority', () => {
  const result = sanitizeUrl('https://user:hunter2@x.test/path');
  assert.ok(!result.includes('hunter2'));
  assert.ok(!result.includes('user:'));
});

test('a URL with nothing sensitive in it passes through unchanged', () => {
  const url = 'https://boards.greenhouse.io/acme/jobs/42?utm_source=li';
  assert.equal(sanitizeUrl(url), url);
});

test('an unparseable value never passes through unredacted', () => {
  assert.equal(sanitizeUrl('not a url at all, password=hunter2'), '[unparseable URL]');
});

test('null/undefined pass through as-is (nothing to redact, nothing to crash on)', () => {
  assert.equal(sanitizeUrl(null), null);
  assert.equal(sanitizeUrl(undefined), undefined);
});

// ── redactSecrets (free-text / error messages) ──────────────────────────

test('redacts a password= fragment embedded in a free-text error message', () => {
  const result = redactSecrets('Timeout navigating to https://x.test/verify?password=hunter2&ok=1');
  assert.ok(!result.includes('hunter2'));
  assert.match(result, /password=\[REDACTED\]/);
});

test('redacts the originally-covered names (password/otp/token/cookie/secret) with or without a value', () => {
  for (const name of ['password', 'otp', 'token', 'cookie', 'secret']) {
    const result = redactSecrets(`leaked ${name}=xyz123 here`);
    assert.ok(!result.includes('xyz123'), `${name} value must be redacted`);
  }
});

test('redacts the newly added names only when they carry an explicit value', () => {
  assert.match(redactSecrets('credential=abc'), /credential=\[REDACTED\]/);
  assert.match(redactSecrets('api_key=abc'), /api_key=\[REDACTED\]/);
  assert.match(redactSecrets('session=abc'), /session=\[REDACTED\]/);
  assert.match(redactSecrets('Authorization=abc'), /Authorization=\[REDACTED\]/);
});

test('ordinary words that merely contain a sensitive substring are not falsely flagged as a leak', () => {
  // "author" and "session expired" (no "=value") must not be mangled.
  assert.equal(redactSecrets('The author reviewed the session summary'),
    'The author reviewed the session summary');
});

test('handles null/undefined input without throwing, as an empty string', () => {
  assert.equal(redactSecrets(null), '');
  assert.equal(redactSecrets(undefined), '');
});
