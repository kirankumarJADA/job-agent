import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { classify, FOREIGN_ROUTES, LIST_ROUTES, parseArgs, runIsolationProbe } from './isolation-probe.mjs';

const TEST_EMAIL = 'robin.test.candidate@example.com';
const FOREIGN_APP = '018f2a6e-7c1d-7a3b-9e2f-4b5c6d7e8f90';
const TOKEN = 'test-candidate-id-token';

/** A backend whose owner scoping either works (isolated) or is broken (leaky). */
function backend({ leaky = false, account = TEST_EMAIL } = {}) {
  const seen = [];
  const fetchImpl = async (url, opts) => {
    seen.push({ url, method: opts.method, auth: opts.headers.Authorization });
    const path = new URL(url).pathname.replace('/api/v1', '');
    const reply = (status, body) => ({ status, text: async () => JSON.stringify(body) });
    if (path === '/auth/me') return reply(200, { userId: 'u-test', username: account });
    if (path === '/managed-mail/address') return reply(200, { address: 'robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com' });
    if (LIST_ROUTES.includes(path)) return reply(200, leaky ? [{ applicationId: FOREIGN_APP }] : []);
    return leaky ? reply(200, { id: FOREIGN_APP }) : reply(404, { title: 'Not Found' });
  };
  return { fetchImpl, seen };
}

const foreign = { application: [FOREIGN_APP], coverLetter: [], cvVersion: [], review: [] };

describe('Phase 20 isolation probe', () => {

  it('passes when every foreign read is refused and lists contain no foreign id', async () => {
    const api = backend();
    const result = await runIsolationProbe({ backend: 'https://b.test', token: TOKEN, expectEmail: TEST_EMAIL, foreign, fetchImpl: api.fetchImpl });

    assert.equal(result.ok, true);
    assert.equal(result.checks.length, FOREIGN_ROUTES.application.length + LIST_ROUTES.length);
    assert.equal(result.ownAlias, 'robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com');
  });

  it('fails when a foreign resource is readable or appears in a list', async () => {
    const api = backend({ leaky: true });
    const result = await runIsolationProbe({ backend: 'https://b.test', token: TOKEN, expectEmail: TEST_EMAIL, foreign, fetchImpl: api.fetchImpl });

    assert.equal(result.ok, false);
    assert.ok(result.checks.every((c) => c.verdict === 'FAIL'));
  });

  it('stops after /auth/me when the token belongs to a different account', async () => {
    const api = backend({ account: 'real.candidate@example.com' });
    const result = await runIsolationProbe({ backend: 'https://b.test', token: TOKEN, expectEmail: TEST_EMAIL, foreign, fetchImpl: api.fetchImpl });

    assert.equal(result.identityOk, false);
    assert.equal(api.seen.length, 1, 'no foreign id may be requested with the wrong account');
  });

  it('sends only authenticated GET requests', async () => {
    const api = backend();
    await runIsolationProbe({ backend: 'https://b.test', token: TOKEN, expectEmail: TEST_EMAIL, foreign, fetchImpl: api.fetchImpl });

    assert.ok(api.seen.length > 2);
    assert.ok(api.seen.every((r) => r.method === 'GET' && r.auth === `Bearer ${TOKEN}`));
  });

  it('judges a 2xx by whether it carries foreign data, not by its status alone', () => {
    const ids = [FOREIGN_APP];
    assert.equal(classify(404), 'PASS');
    assert.equal(classify(403), 'PASS');
    assert.equal(classify(200, JSON.stringify({ id: FOREIGN_APP }), ids), 'FAIL');
    assert.equal(classify(200, JSON.stringify({ items: [{ applicationId: FOREIGN_APP.toUpperCase() }] }), ids), 'FAIL');
    assert.equal(classify(200, '{"items":[]}', ids), 'PASS');
    assert.equal(classify(200, '{"items":[],"nextCursor":"","limit":50}', ids), 'PASS');
    assert.equal(classify(200, '[]', ids), 'PASS');
    assert.equal(classify(200, '{"status":"READY"}', ids), 'INCONCLUSIVE');
    assert.equal(classify(204, '', ids), 'INCONCLUSIVE');
    assert.equal(classify(401), 'INCONCLUSIVE');
    assert.equal(classify(500), 'INCONCLUSIVE');
    assert.equal(classify('network-error'), 'INCONCLUSIVE');
  });

  it('accepts the receipts route answering a foreign application with an empty owner-filtered list', async () => {
    const api = backend();
    const filtered = async (url, opts) => (new URL(url).pathname.endsWith('/receipts')
      ? { status: 200, text: async () => '{"items":[]}' }
      : api.fetchImpl(url, opts));
    const result = await runIsolationProbe({ backend: 'https://b.test', token: TOKEN, expectEmail: TEST_EMAIL, foreign, fetchImpl: filtered });

    assert.equal(result.ok, true);
    const receipts = result.checks.find((c) => c.path.endsWith('/receipts'));
    assert.equal(receipts.status, 200);
    assert.equal(receipts.note, 'owner-filtered, empty');
  });

  it('requires the expected account and at least one foreign UUID', () => {
    assert.throws(() => parseArgs(['--foreign-application', FOREIGN_APP]), /--expect-email/);
    assert.throws(() => parseArgs(['--expect-email', TEST_EMAIL]), /at least one --foreign-/);
    assert.throws(() => parseArgs(['--expect-email', TEST_EMAIL, '--foreign-application', '42']), /needs a UUID/);
    const args = parseArgs(['--expect-email', TEST_EMAIL, '--foreign-application', FOREIGN_APP.toUpperCase()]);
    assert.deepEqual(args.foreign.application, [FOREIGN_APP]);
  });

  it('refuses to run without a token', async () => {
    await assert.rejects(runIsolationProbe({ backend: 'https://b.test', token: '', expectEmail: TEST_EMAIL, foreign }), /ROBIN_TEST_ID_TOKEN/);
  });
});
