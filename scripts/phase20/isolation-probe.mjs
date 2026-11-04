#!/usr/bin/env node
/**
 * Operator-run, read-only production check that the dedicated TEST candidate
 * cannot read another candidate's resources (Phase 20,
 * docs/ROBIN_PHASE20_VERIFICATION.md §6.0).
 *
 * Authenticates with the test candidate's Firebase ID token, read from
 * ROBIN_TEST_ID_TOKEN (never printed), and sends GET requests only. For every
 * foreign resource id the backend must refuse: 404/403 (owner-scoped lookups
 * treat an id the caller does not own exactly like a missing one), or, for a
 * route that filters by owner, an empty collection. Any response containing a
 * foreign id fails. Response bodies are never printed, so a failure cannot leak
 * the other candidate's data into a terminal or log.
 *
 * Usage:
 *   node scripts/phase20/isolation-probe.mjs --expect-email <test candidate email>
 *        --foreign-application <uuid> [--foreign-cover-letter <uuid>]
 *        [--foreign-cv-version <uuid>] [--foreign-review <uuid>]
 *        [--backend https://job-agent-mwhu.onrender.com]
 */
import { pathToFileURL } from 'node:url';

export const DEFAULT_BACKEND = 'https://job-agent-mwhu.onrender.com';
const API = '/api/v1';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Owner-scoped GET routes for each kind of foreign resource id. */
export const FOREIGN_ROUTES = {
  application: [
    '/applications/{id}',
    '/applications/{id}/timeline',
    '/applications/{id}/track',
    '/applications/{id}/track/timeline',
    '/applications/{id}/receipts',
    '/apply/applications/{id}/readiness'
  ],
  coverLetter: ['/cover-letters/{id}'],
  cvVersion: ['/resume-intelligence/cv/{id}/artifact'],
  review: ['/review-queue/{id}']
};

/** Own-data list endpoints that must never contain a foreign id. */
export const LIST_ROUTES = ['/track/reviews', '/managed-mail/messages'];

/** True for `[]` or an object whose collections are all empty, e.g. {"items":[],"nextCursor":""}. */
export function isEmptyCollection(text) {
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    return false;
  }
  if (Array.isArray(json)) return json.length === 0;
  if (!json || typeof json !== 'object') return false;
  const values = Object.values(json);
  return values.some(Array.isArray)
    && values.every((v) => (Array.isArray(v) ? v.length === 0 : v === null || v === '' || typeof v === 'number'));
}

/**
 * 403/404 is a refusal. A 2xx is judged by its content: any foreign id means
 * data leaked; an empty collection means the route filters by owner (e.g. the
 * receipts list answers a foreign application with {"items":[]}); anything
 * else needs a human look.
 */
export function classify(status, text = '', foreignIds = []) {
  if (status === 403 || status === 404) return 'PASS';
  if (typeof status === 'number' && status >= 200 && status < 300) {
    const body = String(text).toLowerCase();
    if (foreignIds.some((id) => body.includes(id))) return 'FAIL';
    return isEmptyCollection(text) ? 'PASS' : 'INCONCLUSIVE';
  }
  return 'INCONCLUSIVE';
}

export function parseArgs(argv) {
  const args = { backend: DEFAULT_BACKEND, foreign: { application: [], coverLetter: [], cvVersion: [], review: [] } };
  const kinds = {
    '--foreign-application': 'application',
    '--foreign-cover-letter': 'coverLetter',
    '--foreign-cv-version': 'cvVersion',
    '--foreign-review': 'review'
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (kinds[arg]) {
      const id = argv[++i];
      if (!UUID.test(id ?? '')) throw new Error(`${arg} needs a UUID, got: ${id}`);
      args.foreign[kinds[arg]].push(id.toLowerCase());
    } else if (arg === '--expect-email') args.expectEmail = argv[++i];
    else if (arg === '--backend') args.backend = argv[++i];
    else throw new Error(`Unknown argument: ${arg}`);
  }
  if (!args.expectEmail) throw new Error('--expect-email is required: name the TEST candidate account this token must belong to');
  if (Object.values(args.foreign).every((ids) => ids.length === 0)) {
    throw new Error('Give at least one --foreign-* id belonging to a DIFFERENT candidate');
  }
  return args;
}

export async function runIsolationProbe({ backend, token, expectEmail, foreign, fetchImpl = fetch, timeoutMs = 90_000 }) {
  if (!token) throw new Error('ROBIN_TEST_ID_TOKEN is not set');
  const base = `${backend.replace(/\/+$/, '')}${API}`;
  const get = async (path) => {
    try {
      const response = await fetchImpl(`${base}${path}`, {
        method: 'GET',
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        signal: AbortSignal.timeout(timeoutMs)
      });
      return { status: response.status, text: await response.text().catch(() => '') };
    } catch (error) {
      return { status: 'network-error', text: '' };
    }
  };

  const me = await get('/auth/me');
  let identity = null;
  try {
    identity = me.status === 200 ? JSON.parse(me.text) : null;
  } catch {
    identity = null;
  }
  const signedInAs = identity?.username ?? identity?.email ?? null;
  const identityOk = typeof signedInAs === 'string' && signedInAs.toLowerCase() === expectEmail.toLowerCase();
  if (!identityOk) {
    return { identityOk, signedInAs, meStatus: me.status, checks: [], ok: false };
  }

  const checks = [];
  const allForeign = Object.values(foreign).flat();
  for (const [kind, ids] of Object.entries(foreign)) {
    for (const id of ids) {
      for (const route of FOREIGN_ROUTES[kind]) {
        const path = route.replace('{id}', id);
        const { status, text } = await get(path);
        const verdict = classify(status, text, allForeign);
        const note = verdict === 'PASS' && status < 300 ? 'owner-filtered, empty' : undefined;
        checks.push({ path, status, verdict, note });
      }
    }
  }
  for (const path of LIST_ROUTES) {
    const { status, text } = await get(path);
    const leaked = allForeign.some((id) => text.toLowerCase().includes(id));
    const verdict = status === 200 ? (leaked ? 'FAIL' : 'PASS') : 'INCONCLUSIVE';
    checks.push({ path, status, verdict, note: leaked ? 'contains a foreign id' : 'no foreign id present' });
  }

  const mailbox = await get('/managed-mail/address');
  let ownAlias = null;
  try {
    ownAlias = mailbox.status === 200 ? JSON.parse(mailbox.text).address ?? null : null;
  } catch {
    ownAlias = null;
  }

  return {
    identityOk,
    signedInAs,
    ownAlias,
    checks,
    ok: checks.length > 0 && checks.every((c) => c.verdict === 'PASS')
  };
}

async function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exit(2);
  }
  const token = process.env.ROBIN_TEST_ID_TOKEN;
  if (!token) {
    console.error('ROBIN_TEST_ID_TOKEN is not set in this shell.');
    process.exit(2);
  }

  const result = await runIsolationProbe({ backend: args.backend, token, expectEmail: args.expectEmail, foreign: args.foreign });
  if (!result.identityOk) {
    console.error(`Token does not belong to ${args.expectEmail} (GET /auth/me -> ${result.meStatus}, account: ${result.signedInAs ?? 'unknown'}). Nothing else was requested.`);
    process.exit(2);
  }
  console.log(`Signed in as: ${result.signedInAs}`);
  for (const check of result.checks) {
    console.log(`${check.verdict.padEnd(12)} GET ${API}${check.path} -> ${check.status}${check.note ? ` (${check.note})` : ''}`);
  }
  if (result.ownAlias) console.log(`\nTest candidate's own managed alias: ${result.ownAlias}`);
  const failed = result.checks.filter((c) => c.verdict === 'FAIL').length;
  const inconclusive = result.checks.filter((c) => c.verdict === 'INCONCLUSIVE').length;
  console.log(failed ? `\nISOLATION FAILURE on ${failed} request(s).`
    : inconclusive ? `\nNo leak observed, but ${inconclusive} request(s) were inconclusive.` : '\nAll foreign reads were refused.');
  process.exit(failed ? 1 : inconclusive ? 3 : 0);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  await main();
}
