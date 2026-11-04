import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';
import worker from '../src/index.js';
import {
  assertProbeRecipient,
  buildProbePayload,
  planProbe,
  renderSql,
  runProbe,
  sha256Hex,
  signDelivery
} from '../scripts/idempotency-probe.mjs';

const SECRET = 'probe-test-secret-0123456789';
const ALIAS = 'robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com';

function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

/** Mirrors CloudflareSignatureVerifier + ManagedInboundService's duplicate rules. */
function contractBackend(secret) {
  const tokens = new Map();
  const messageIds = new Set();
  const calls = [];
  const fetchImpl = async (url, opts) => {
    calls.push(opts);
    const reply = (status, body) => ({ status, json: async () => ({ status: body }) });
    const h = opts.headers;
    const payloadSha = sha256(opts.body);
    const expected = createHmac('sha256', secret)
      .update(`${h['X-Robin-Timestamp']}.${h['X-Robin-Token']}.${payloadSha}`).digest('hex');
    if (!timingSafeEqual(Buffer.from(expected), Buffer.from(h['X-Robin-Signature']))) {
      return reply(401, 'unauthenticated');
    }
    const tokenHash = sha256(h['X-Robin-Token']);
    if (tokens.has(tokenHash)) {
      return tokens.get(tokenHash) === payloadSha ? reply(200, 'duplicate') : reply(406, 'rejected');
    }
    tokens.set(tokenHash, payloadSha);
    const messageId = JSON.parse(opts.body).messageId.replace(/^<|>$/g, '');
    if (messageIds.has(messageId)) return reply(200, 'duplicate');
    messageIds.add(messageId);
    return reply(200, 'stored');
  };
  return { fetchImpl, calls, tokens, messageIds };
}

describe('Phase 20 idempotency probe', () => {

  it('signs deliveries byte-for-byte like the deployed Cloudflare Worker', async () => {
    const raw = 'From: HR <hr@employer.example>\r\nTo: ' + ALIAS + '\r\nSubject: Hello\r\n'
      + 'Message-ID: <m-1@employer.example>\r\nContent-Type: text/plain\r\n\r\nBody';
    const bytes = new TextEncoder().encode(raw);
    const message = {
      to: ALIAS, from: 'hr@employer.example', rawSize: bytes.byteLength,
      raw: new ReadableStream({ start(c) { c.enqueue(bytes); c.close(); } }),
      setReject() {}
    };
    let captured;
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => { captured = opts; return { status: 200, json: async () => ({}) }; };
    try {
      await worker.email(message, { ROBIN_BACKEND_URL: 'https://api.test.example', CLOUDFLARE_EMAIL_WORKER_SECRET: SECRET }, {});
    } finally {
      globalThis.fetch = originalFetch;
    }

    const ours = signDelivery({
      secret: SECRET,
      body: captured.body,
      timestamp: captured.headers['X-Robin-Timestamp'],
      token: captured.headers['X-Robin-Token']
    });
    assert.deepEqual(ours, captured.headers);
  });

  it('passes against a backend that honours the duplicate-webhook contract', async () => {
    const backend = contractBackend(SECRET);
    const plan = planProbe({ recipient: ALIAS });

    const outcome = await runProbe({ backend: 'https://backend.test', secret: SECRET, plan, fetchImpl: backend.fetchImpl });

    assert.equal(outcome.ok, true, JSON.stringify(outcome.results, null, 2));
    assert.deepEqual(outcome.results.map((r) => `${r.actual.status} ${r.actual.body}`),
      ['200 stored', '200 duplicate', '200 duplicate', '406 rejected', '401 unauthenticated']);
    assert.equal(backend.tokens.size, 2, 'only the first two tokens are ever accepted');
    assert.equal(backend.messageIds.size, 1, 'exactly one message is stored');
  });

  it('fails when the backend stores duplicates instead of recognising them', async () => {
    const naive = async () => ({ status: 200, json: async () => ({ status: 'stored' }) });
    const plan = planProbe({ recipient: ALIAS });

    const outcome = await runProbe({ backend: 'https://backend.test', secret: SECRET, plan, fetchImpl: naive });

    assert.equal(outcome.ok, false);
    assert.deepEqual(outcome.results.map((r) => r.ok), [true, false, false, false, false]);
  });

  it('repeats the first request byte-for-byte and replays its token with a different body', async () => {
    const backend = contractBackend(SECRET);
    let now = 1_800_000_000_000;
    const plan = planProbe({ recipient: ALIAS });

    await runProbe({ backend: 'https://b.test', secret: SECRET, plan, fetchImpl: backend.fetchImpl, clock: () => (now += 1_000) });

    const [first, repeat, sameMessage, replay, tampered] = backend.calls;
    assert.deepEqual(repeat.headers, first.headers);
    assert.equal(repeat.body, first.body);
    assert.equal(sameMessage.body, first.body);
    assert.notEqual(sameMessage.headers['X-Robin-Token'], first.headers['X-Robin-Token']);
    assert.ok(Number(sameMessage.headers['X-Robin-Timestamp']) > Number(first.headers['X-Robin-Timestamp']),
      'later requests are signed when sent, so a slow cold start cannot make them stale');
    assert.equal(replay.headers['X-Robin-Token'], first.headers['X-Robin-Token']);
    assert.notEqual(replay.body, first.body);
    assert.equal(tampered.body, first.body);
  });

  it('defaults to a quarantined payload that carries no job, code or link signals', () => {
    const quarantined = JSON.parse(buildProbePayload({ recipient: ALIAS, messageId: 'p@probe.invalid', mode: 'quarantined', date: 'D' }));
    const full = JSON.parse(buildProbePayload({ recipient: ALIAS, messageId: 'p@probe.invalid', mode: 'full', date: 'D' }));

    assert.equal(quarantined.spam, 'yes');
    assert.equal(full.spam, 'no');
    assert.equal(quarantined.envelopeRecipient, ALIAS);
    assert.equal(quarantined.messageId, '<p@probe.invalid>');
    assert.doesNotMatch(quarantined.subject + quarantined.text, /\d|https?:/);
    assert.equal(planProbe({ recipient: ALIAS }).mode, 'quarantined');
  });

  it('refuses anything other than a reserved alias', () => {
    assert.equal(assertProbeRecipient(ALIAS), ALIAS);
    for (const bad of [
      'robin@mail.aplivenosi.com',
      'robin+u7kq2m9x4vb3tn5p@gmail.com',
      'robin+U7KQ2M9X4VB3TN5P@mail.aplivenosi.com',
      'robin+u7kq2m9x4vb3tn5@mail.aplivenosi.com',
      'robin+1u7kq2m9x4vb3tn5@mail.aplivenosi.com',
      'robin+u7kq2m9x4vb3tn5l@mail.aplivenosi.com',
      undefined
    ]) {
      assert.throws(() => assertProbeRecipient(bad), /reserved Robin alias/, String(bad));
    }
    assert.throws(() => planProbe({ recipient: ALIAS, mode: 'loud' }), /--mode/);
  });

  it('prints verification SQL keyed by digests, never by raw tokens', () => {
    const plan = planProbe({ recipient: ALIAS });
    const sql = renderSql(plan);

    assert.ok(sql.includes(plan.tokenSha256.first) && sql.includes(plan.tokenSha256.second));
    assert.ok(sql.includes(plan.messageIdSha256));
    assert.equal(plan.messageIdSha256, sha256Hex(plan.messageId));
    for (const request of plan.requests.filter((r) => r.token)) {
      assert.ok(!sql.includes(request.token), 'raw token must not appear in printed SQL');
    }
    assert.match(sql, /expect 0 rows/);
  });

  it('refuses to send without the shared secret', async () => {
    await assert.rejects(
      runProbe({ backend: 'https://b.test', secret: '', plan: planProbe({ recipient: ALIAS }), fetchImpl: async () => { throw new Error('must not send'); } }),
      /CLOUDFLARE_EMAIL_WORKER_SECRET/);
  });
});
