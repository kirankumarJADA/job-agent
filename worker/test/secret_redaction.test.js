import test from 'node:test';
import assert from 'node:assert/strict';
import { sanitizeUrl, redactSecrets } from '../src/secret_redaction.js';

// ── sanitizeUrl ──────────────────────────────────────────────────────────

test('redacts a password and private email submitted via a GET form query string', () => {
  const result = sanitizeUrl('https://mockemployer.test/verify?email=c%40x.test&password=hunter2&code=998877&key=k1&magic=m2');
  assert.ok(!result.includes('hunter2'), 'the raw password must not survive');
  assert.ok(!result.includes('c%40x.test') && !result.includes('c@x.test'), 'the email must be redacted');
  assert.ok(!result.includes('998877'), 'verification code param must be redacted');
  assert.ok(!result.includes('k1'), 'key param must be redacted');
  assert.ok(!result.includes('m2'), 'magic param must be redacted');
  assert.match(result, /password=%5BREDACTED%5D|password=\[REDACTED\]/);
  assert.match(result, /email=%5BREDACTED%5D|email=\[REDACTED\]/);
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

test('redacts URL userinfo, Cookie headers, apikey, Authorization schemes and escaped JSON values', () => {
  const input = 'Cookie: sid=s123\nAuthorization: Basic dXNlcjpwYXNz\nhttps://alice:pwd123@example.com/cb {"apikey":"k-escaped\\"1"} Bearer tok+abc/def==';
  const redacted = redactSecrets(input);
  for (const leak of ['s123', 'dXNlcjpwYXNz', 'alice:pwd123', 'k-escaped', 'tok+abc/def==']) {
    assert.ok(!redacted.includes(leak), `secret ${leak} must be redacted`);
  }
  assert.match(redacted, /Cookie: \[REDACTED\]/);
  assert.match(redacted, /Authorization: \[REDACTED\]/);
  assert.match(redacted, /https:\/\/\[REDACTED\]@example\.com\/cb/);
  assert.match(redacted, /Bearer \[REDACTED\]/);
});

test('redacts quoted Cookie/Set-Cookie values and quoted apikey/token headers cleanly', () => {
  const input = 'Cookie: sid="quoted-secret-99"; theme="dark" Set-Cookie: session="quoted-sess-77" api_key: "quoted-ak-55"';
  const redacted = redactSecrets(input);
  for (const leak of ['quoted-secret-99', 'quoted-sess-77', 'quoted-ak-55']) {
    assert.ok(!redacted.includes(leak), `quoted secret ${leak} must be redacted`);
  }
  // Ensure JSON safety is preserved when quoted secret strings appear inside JSON messages
  const jsonInput = JSON.stringify({ error: 'Cookie: sid="inner-cookie-42" failed' });
  const redactedJson = redactSecrets(jsonInput);
  assert.ok(!redactedJson.includes('inner-cookie-42'));
  assert.equal(typeof JSON.parse(redactedJson).error, 'string');
});

test('Stage 3I Phase 5: redacts numeric JSON OTPs, new token/sig/activation params, and Error/object trees without mutating inputs', () => {
  const numericJson = '{"otp": 482913, "pin": 9012, "code": 771122, "ok": true}';
  const scrubbedJson = redactSecrets(numericJson);
  for (const leak of ['482913', '9012', '771122']) {
    assert.ok(!scrubbedJson.includes(leak), `numeric JSON secret ${leak} must be redacted`);
  }
  const parsed = JSON.parse(scrubbedJson);
  assert.equal(parsed.ok, true);

  const kv = 'https://app.example/verify?activation_code=act-999&lease_token=lt-123&sig=deadbeef&jwt=eyJ.a.b&nonce=n-8&ticket=t-7&verifier=v-6';
  const scrubbedKv = redactSecrets(kv);
  for (const leak of ['act-999', 'lt-123', 'deadbeef', 'eyJ.a.b', 'n-8', 't-7', 'v-6']) {
    assert.ok(!scrubbedKv.includes(leak), `query param secret ${leak} must be redacted`);
  }

  const origErr = new Error('OTP failed for otp=654321&leaseToken=tok-xyz');
  const scrubbedErr = redactSecrets(origErr);
  assert.ok(!String(scrubbedErr).includes('654321'));
  assert.ok(!String(scrubbedErr).includes('tok-xyz'));
  assert.match(origErr.message, /654321/, 'must not mutate original Error');

  const obj = { nested: { activationCode: 'a1', leaseToken: 'tok-999', otp: 123456, safe: 'ok' } };
  const scrubbedObj = redactSecrets(obj);
  assert.equal(obj.nested.leaseToken, 'tok-999', 'must not mutate input object');
  assert.equal(scrubbedObj.nested.safe, 'ok');
  assert.ok(!JSON.stringify(scrubbedObj).includes('tok-999'));
  assert.ok(!JSON.stringify(scrubbedObj).includes('123456'));
});
