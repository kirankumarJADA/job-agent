#!/usr/bin/env node
/**
 * Operator-run production check of Robin's duplicate-webhook handling
 * (Phase 20, docs/ROBIN_PHASE20_VERIFICATION.md §6.1).
 *
 * Signs synthetic deliveries exactly as the Cloudflare Email Worker does and
 * sends them straight to the backend's inbound endpoint, then compares every
 * response with the documented contract:
 *
 *   1. first delivery                          -> 200 {"status":"stored"}
 *   2. byte-identical repeat (Cloudflare retry) -> 200 {"status":"duplicate"}
 *   3. same Message-ID, fresh token            -> 200 {"status":"duplicate"}
 *   4. replayed token, altered payload         -> 406 {"status":"rejected"}
 *   5. tampered signature                      -> 401 {"status":"unauthenticated"}
 *
 * Safety:
 *   - Dry run unless --execute is given.
 *   - The shared secret is read from CLOUDFLARE_EMAIL_WORKER_SECRET and never
 *     printed; only SHA-256 digests of the single-use tokens are shown.
 *   - The recipient must be a reserved alias (prefix+16-character token), never
 *     a base address. Use the dedicated test candidate's alias only.
 *   - Default mode "quarantined" marks the probe as spam: both idempotency gates
 *     still run, but no content is kept and no email/link/notification/timeline
 *     row is created. "--mode full" exercises the full ingestion pipeline.
 *
 * Usage:
 *   node scripts/idempotency-probe.mjs --recipient <alias> [--mode quarantined|full]
 *        [--backend https://job-agent-mwhu.onrender.com] [--execute]
 */
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const DEFAULT_BACKEND = 'https://job-agent-mwhu.onrender.com';
export const DEFAULT_DOMAIN = 'mail.aplivenosi.com';
export const INBOUND_PATH = '/api/v1/managed-mail/inbound/cloudflare';
const PROBE_SENDER = 'idempotency-probe@probe.invalid';

export function sha256Hex(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

/** Headers for one delivery, computed exactly as cloudflare-email-worker/src/index.js does. */
export function signDelivery({ secret, body, timestamp, token }) {
  const signature = createHmac('sha256', secret)
    .update(`${timestamp}.${token}.${sha256Hex(body)}`, 'utf8')
    .digest('hex');
  return {
    'Content-Type': 'application/json',
    'X-Robin-Timestamp': timestamp,
    'X-Robin-Token': token,
    'X-Robin-Signature': signature
  };
}

export function assertProbeRecipient(recipient, domain = DEFAULT_DOMAIN) {
  const escaped = domain.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const alias = new RegExp(`^[a-z0-9._-]+\\+[a-km-np-z][a-km-np-z2-9]{15}@${escaped}$`);
  if (typeof recipient !== 'string' || !alias.test(recipient)) {
    throw new Error(`--recipient must be a reserved Robin alias such as robin+<16-character token>@${domain}`);
  }
  return recipient;
}

/** Field set and order mirror the Worker's payload; the content carries no job, code or link signals. */
export function buildProbePayload({ recipient, messageId, mode, date, subject = 'Synthetic Idempotency Probe' }) {
  return JSON.stringify({
    envelopeRecipient: recipient,
    envelopeSender: PROBE_SENDER,
    from: `Robin Idempotency Probe <${PROBE_SENDER}>`,
    subject,
    text: 'Synthetic delivery used to verify duplicate webhook handling. No action is needed.',
    html: '',
    messageId: `<${messageId}>`,
    date,
    spf: '',
    dkim: '',
    spam: mode === 'full' ? 'no' : 'yes'
  });
}

function flipLastHexDigit(hex) {
  const last = hex.at(-1);
  return hex.slice(0, -1) + (last === '0' ? '1' : '0');
}

/**
 * Builds the five requests and their expected responses. Timestamps are
 * assigned when each request is sent (see runProbe) so a cold backend start
 * cannot push them outside the signature window.
 */
export function planProbe({ recipient, mode = 'quarantined', domain = DEFAULT_DOMAIN, now = new Date(), uuid = randomUUID }) {
  if (mode !== 'quarantined' && mode !== 'full') {
    throw new Error('--mode must be "quarantined" or "full"');
  }
  assertProbeRecipient(recipient, domain);
  const stamp = now.toISOString().slice(0, 10).replaceAll('-', '');
  const messageId = `idempotency-probe-${stamp}-${uuid().slice(0, 8)}@probe.invalid`;
  const date = now.toUTCString();
  const body = buildProbePayload({ recipient, messageId, mode, date });
  const alteredBody = buildProbePayload({ recipient, messageId, mode, date, subject: 'Synthetic Idempotency Probe (altered)' });
  const tokens = { first: uuid(), second: uuid(), tampered: uuid() };

  const requests = [
    { name: 'first delivery', token: tokens.first, body, expect: { status: 200, body: 'stored' } },
    { name: 'byte-identical repeat', repeatOf: 0, expect: { status: 200, body: 'duplicate' } },
    { name: 'same Message-ID, fresh token', token: tokens.second, body, expect: { status: 200, body: 'duplicate' } },
    { name: 'replayed token, altered payload', token: tokens.first, body: alteredBody, expect: { status: 406, body: 'rejected' } },
    { name: 'tampered signature', token: tokens.tampered, body, tamper: true, expect: { status: 401, body: 'unauthenticated' } }
  ];

  return {
    mode,
    recipient,
    messageId,
    messageIdSha256: sha256Hex(messageId),
    tokenSha256: {
      first: sha256Hex(tokens.first),
      second: sha256Hex(tokens.second),
      tampered: sha256Hex(tokens.tampered)
    },
    requests
  };
}

/** Sends the plan in order. `fetchImpl` and `clock` are injectable for tests. */
export async function runProbe({ backend, secret, plan, fetchImpl = fetch, clock = () => Date.now(), timeoutMs = 90_000 }) {
  if (!secret) throw new Error('CLOUDFLARE_EMAIL_WORKER_SECRET is not set');
  const endpoint = `${backend.replace(/\/+$/, '')}${INBOUND_PATH}`;
  const sent = [];
  const results = [];

  for (const request of plan.requests) {
    let headers;
    let body;
    if (request.repeatOf !== undefined) {
      ({ headers, body } = sent[request.repeatOf]);
    } else {
      body = request.body;
      const timestamp = Math.floor(clock() / 1000).toString();
      headers = signDelivery({ secret, body, timestamp, token: request.token });
      if (request.tamper) {
        headers = { ...headers, 'X-Robin-Signature': flipLastHexDigit(headers['X-Robin-Signature']) };
      }
    }
    sent.push({ headers, body });

    let status;
    let statusBody;
    try {
      const response = await fetchImpl(endpoint, {
        method: 'POST',
        headers,
        body,
        signal: AbortSignal.timeout(timeoutMs)
      });
      status = response.status;
      statusBody = await response.json().then((json) => json.status, () => undefined);
    } catch (error) {
      status = 'network-error';
      statusBody = error.name;
    }
    const ok = status === request.expect.status && statusBody === request.expect.body;
    results.push({ name: request.name, expected: request.expect, actual: { status, body: statusBody }, ok });
  }
  return { ok: results.every((r) => r.ok), results };
}

export function renderSql(plan) {
  const t = plan.tokenSha256;
  const quarantined = plan.mode === 'quarantined';
  return `-- Read-only verification. Run in the Supabase SQL editor.
-- 1. Webhook tokens: expect exactly 2 rows (first, second). The tampered request's token must be absent.
select token_sha256, provider_id, signed_at
from managed_mail_webhook_tokens
where token_sha256 in ('${t.first}', '${t.second}', '${t.tampered}')
order by signed_at;

-- 2. Inbound vault: expect exactly 1 row${quarantined ? ', spam_flagged = true and content_kept = false' : ', content_kept = true'}.
select id, profile_id, spam_flagged, content_encrypted is not null as content_kept, content_purged_at, received_at
from managed_inbound_messages
where message_id_sha256 = '${plan.messageIdSha256}';

-- 3. Normalised email evidence: expect ${quarantined ? '0 rows' : '1 row'}.
select e.id, e.classification, l.status as link_status, l.match_strength
from emails e left join email_application_links l on l.email_id = e.id
where e.message_id = '${plan.messageId}';

-- 4. Application timeline: expect 0 rows.
select count(*) from application_events ev join emails e on e.id = ev.email_id
where e.message_id = '${plan.messageId}';

${quarantined ? `-- Optional cleanup (quarantined mode). Deletes exactly the probe's rows.
delete from managed_inbound_messages where message_id_sha256 = '${plan.messageIdSha256}' and envelope_recipient = '${plan.recipient}';
delete from managed_mail_webhook_tokens where token_sha256 in ('${t.first}', '${t.second}');`
    : `-- Cleanup (full mode): follow the two-step, primary-key-only procedure in
-- docs/ROBIN_PHASE20_VERIFICATION.md §6.1 using message id '${plan.messageId}'.`}
`;
}

function parseArgs(argv) {
  const args = { mode: 'quarantined', backend: DEFAULT_BACKEND, domain: DEFAULT_DOMAIN, execute: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--execute') args.execute = true;
    else if (arg === '--recipient') args.recipient = argv[++i];
    else if (arg === '--mode') args.mode = argv[++i];
    else if (arg === '--backend') args.backend = argv[++i];
    else if (arg === '--domain') args.domain = argv[++i];
    else throw new Error(`Unknown argument: ${arg}`);
  }
  return args;
}

async function deployedCommit(backend) {
  try {
    const response = await fetch(`${backend.replace(/\/+$/, '')}/actuator/info`, { signal: AbortSignal.timeout(90_000) });
    const info = await response.json();
    return info?.deployment?.commit ?? 'not reported';
  } catch {
    return 'unreachable';
  }
}

async function main() {
  let args;
  let plan;
  try {
    args = parseArgs(process.argv.slice(2));
    plan = planProbe({ recipient: args.recipient, mode: args.mode, domain: args.domain });
  } catch (error) {
    console.error(error.message);
    process.exit(2);
  }

  console.log(`Target:      ${args.backend}${INBOUND_PATH}`);
  console.log(`Recipient:   ${plan.recipient}  (must be the dedicated TEST candidate's alias)`);
  console.log(`Mode:        ${plan.mode}`);
  console.log(`Message-ID:  ${plan.messageId}`);
  plan.requests.forEach((r, i) => console.log(`  ${i + 1}. ${r.name.padEnd(34)} expect ${r.expect.status} ${r.expect.body}`));

  if (!args.execute) {
    console.log('\nDry run only. Re-run with --execute to send these requests.');
    return;
  }
  const secret = process.env.CLOUDFLARE_EMAIL_WORKER_SECRET;
  if (!secret) {
    console.error('CLOUDFLARE_EMAIL_WORKER_SECRET is not set in this shell.');
    process.exit(2);
  }

  console.log(`\nDeployed commit reported by backend: ${await deployedCommit(args.backend)}`);
  const outcome = await runProbe({ backend: args.backend, secret, plan });
  for (const r of outcome.results) {
    const actual = `${r.actual.status} ${r.actual.body ?? ''}`.trim();
    console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name.padEnd(34)} expected ${r.expected.status} ${r.expected.body}, got ${actual}`);
  }
  console.log(`\n${renderSql(plan)}`);
  console.log(outcome.ok ? 'All five responses matched the contract.' : 'At least one response did NOT match the contract.');
  process.exit(outcome.ok ? 0 : 1);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  await main();
}
