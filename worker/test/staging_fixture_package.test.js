// Stage 3G — the shared synthetic employer app and its (undeployed) Cloudflare package.
// Pure HTTP-level tests: Request in, Response out. No network, no Cloudflare account.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createUniversalEmployerApp, memoryStore, OFFICIAL_TURNSTILE_TEST_SITEKEY }
  from '../../staging/universal-employer-fixture/src/app.js';
import worker, { FixtureState, SCENARIO_PROFILES, SECURITY_HEADERS, routeFor }
  from '../../staging/universal-employer-fixture/src/index.js';

const ORIGIN = 'https://fixture.test-operator.invalid';
const PKG = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'staging', 'universal-employer-fixture');

function noNetwork(t) {
  t.mock.method(globalThis, 'fetch', async () => { throw new Error('the fixture must never make an outbound request'); });
}

async function flow(app, { submits = 1 } = {}) {
  const { applyPath, step1ApiPath, step2Path, submitPath } = app.paths;
  const r1 = await app.handle(new Request(`${ORIGIN}${applyPath}`));
  const cookie = r1.headers.get('set-cookie').split(';')[0];
  const h = { cookie, 'content-type': 'application/json' };
  await app.handle(new Request(`${ORIGIN}${step1ApiPath}`, { method: 'POST', headers: h, body: JSON.stringify({ firstName: 'Synth' }) }));
  await app.handle(new Request(`${ORIGIN}${step2Path}`, { headers: h }));
  const results = [];
  for (let i = 0; i < submits; i += 1) {
    const res = await app.handle(new Request(`${ORIGIN}${submitPath}`, { method: 'POST', headers: h, body: JSON.stringify({ noticePeriod: '4 weeks' }) }));
    results.push({ status: res.status, body: await res.json() });
  }
  return results;
}

// ── shared app: required behaviours ─────────────────────────────────────

test('defaults reproduce the original fixture: multi-step pages, session cookie, genuine confirmation', async (t) => {
  noNetwork(t);
  const app = createUniversalEmployerApp();
  const page1 = await (await app.handle(new Request(`${ORIGIN}${app.paths.applyPath}`))).text();
  assert.match(page1, /Step 1 of 2/);
  assert.match(page1, /id="f_cv" type="file" accept="\.pdf" required/);
  const [result] = await flow(app);
  assert.deepEqual(result, { status: 200, body: { ok: true, reference: 'EMP-2026-8841' } });
  assert.equal(app.submissions.length, 1);
  assert.equal(app.submissions[0].step2.noticePeriod, '4 weeks');
});

test('dynamic controls: conditional mandatory field, body-portal combobox, open + closed Shadow DOM', async () => {
  const app = createUniversalEmployerApp({ conditionalField: true, portalCombobox: true, shadowDom: true });
  const page2 = await (await app.handle(new Request(`${ORIGIN}${app.paths.step2Path}`))).text();
  assert.match(page2, /require visa sponsorship\?<span>\*<\/span>/);
  assert.match(page2, /Which visa type would you need\?/);
  assert.match(page2, /role="combobox"/);
  assert.match(page2, /document\.body\.appendChild\(list\)/, 'listbox is portalled outside the form');
  assert.match(page2, /attachShadow\(\{ mode: 'open' \}\)/);
  assert.match(page2, /attachShadow\(\{ mode: 'closed' \}\)/);
});

test('uploads: hidden native input behind a visible dropzone; PDF-only CV and DOCX-only cover letter', async () => {
  const app = createUniversalEmployerApp({ customDropzone: true, includeCoverLetterField: true, coverLetterAccept: '.docx' });
  const page1 = await (await app.handle(new Request(`${ORIGIN}${app.paths.applyPath}`))).text();
  assert.match(page1, /<label for="f_cv" id="cv-dropzone" class="dropzone"/);
  assert.match(page1, /id="f_cv" type="file" accept="\.pdf" required style="position:absolute;opacity:0/);
  assert.match(page1, /id="f_cl" type="file" accept="\.docx"/);
});

test('gates: login wall, MFA, local CAPTCHA marker and the official Turnstile test widget', async () => {
  const text = async (opts) => (await createUniversalEmployerApp(opts).handle(new Request(`${ORIGIN}/careers/jobs/stage3-role/apply`))).text();
  assert.match(await text({ loginWallMode: true }), /type="password"/);
  assert.match(await text({ mfaMode: true }), /two-factor verification code/);
  assert.match(await text({ captchaMode: 'page1' }), /g-recaptcha/);
  const turnstile = await text({ captchaMode: 'turnstile-test' });
  assert.match(turnstile, new RegExp(`class="cf-turnstile" data-sitekey="${OFFICIAL_TURNSTILE_TEST_SITEKEY}"`));
  assert.doesNotMatch(turnstile, /challenges\.cloudflare\.com/, 'local tests never load the external script');
  assert.match(await text({ captchaMode: 'turnstile-test', loadOfficialCaptchaScript: true }), /challenges\.cloudflare\.com\/turnstile\/v0\/api\.js/);
});

test('error responses: 422 validation, HTTP 200 {ok:false}, 500, CAPTCHA 403 — none recorded as a submission', async () => {
  for (const [opts, status, ok] of [[{ validationErrorOnSubmit: true }, 422, undefined], [{ okFalseOnSubmit: true }, 200, false],
    [{ silent500OnSubmit: true }, 500, undefined], [{ captchaMode: 'submit' }, 403, undefined]]) {
    const app = createUniversalEmployerApp(opts);
    const [r] = await flow(app);
    assert.equal(r.status, status, JSON.stringify(opts));
    if (ok !== undefined) assert.equal(r.body.ok, ok);
    assert.equal(app.submissions.length, 0);
    assert.equal((await app.diagnostics()).acceptedSubmissions, 0);
  }
  const page2 = await (await createUniversalEmployerApp({ okFalseOnSubmit: true })
    .handle(new Request(`${ORIGIN}/careers/jobs/stage3-role/apply/step-2`))).text();
  assert.match(page2, /json\.ok === false/, 'page never navigates to confirmation on {ok:false}');
});

test('idempotency counter: exactly one accepted submission; every later attempt refused with 409 and counted', async (t) => {
  noNetwork(t);
  const app = createUniversalEmployerApp({ idempotent: true, uniqueReceipts: true, receiptPrefix: 'ROBIN-S14' });
  const results = await flow(app, { submits: 4 });
  assert.equal(results[0].status, 200);
  assert.match(results[0].body.reference, /^ROBIN-S14-0001-[0-9A-F]{8}$/);
  assert.deepEqual(results.slice(1).map((r) => r.status), [409, 409, 409]);
  const d = await app.diagnostics();
  assert.deepEqual([d.submitAttempts, d.acceptedSubmissions, d.rejectedDuplicates], [4, 1, 3]);
  assert.deepEqual(d.receipts, [results[0].body.reference]);
});

test('receipt ids are unique per accepted submission', async () => {
  const app = createUniversalEmployerApp({ uniqueReceipts: true });
  const results = await flow(app, { submits: 3 });
  const refs = results.map((r) => r.body.reference);
  assert.equal(new Set(refs).size, 3);
});

test('the confirmation page escapes the reflected reference (no script injection on the staging host)', async () => {
  const app = createUniversalEmployerApp();
  const body = await (await app.handle(new Request(`${ORIGIN}${app.paths.confirmationPath}?ref=${encodeURIComponent('<script>x()</script>')}`))).text();
  assert.doesNotMatch(body, /<script>x\(\)<\/script>/);
  assert.match(body, /&lt;script&gt;/);
});

test('diagnostics expose counters and receipt ids only — never form values', async () => {
  const app = createUniversalEmployerApp({ uniqueReceipts: true });
  await flow(app);
  const d = JSON.stringify(await app.diagnostics());
  assert.doesNotMatch(d, /Synth|4 weeks|noticePeriod|firstName/);
});

// ── Cloudflare package: host gate, headers, admin auth, durable idempotency, expiry ──

function fakeStorage() {
  const m = new Map();
  let alarm = null;
  return { map: m,
    async get(k) { return m.get(k); }, async put(k, v) { m.set(k, v); }, async delete(k) { m.delete(k); },
    async deleteAll() { m.clear(); }, async getAlarm() { return alarm; }, async setAlarm(t) { alarm = t; } };
}

function fakeEnv({ token = 'x'.repeat(40), storage = fakeStorage() } = {}) {
  const objects = new Map();
  const env = {
    FIXTURE_HOSTNAME: 'fixture.test-operator.invalid', FIXTURE_PATH_PREFIX: '/robin-fixture', DATA_TTL_HOURS: '24',
    FIXTURE_ADMIN_TOKEN: token,
    FIXTURE_STATE: {
      idFromName: (n) => n,
      get: (id) => {
        if (!objects.has(id)) objects.set(id, new FixtureState({ storage }, env));
        return objects.get(id);
      },
    },
  };
  return { env, storage, objects };
}

const scenarioUrl = (slug, rest = 'careers/jobs/stage3-role/apply') => `${ORIGIN}/robin-fixture/${slug}/${rest}`;

test('package: only the authorised host over HTTPS; other hosts 421, http redirected, unknown paths 404', async (t) => {
  noNetwork(t);
  const { env } = fakeEnv();
  assert.equal((await worker.fetch(new Request('https://robin-synthetic-employer-portal.acct.workers.dev/robin-fixture/s01-multistep/careers/jobs/stage3-role/apply'), env)).status, 421);
  assert.equal((await worker.fetch(new Request('https://evil.example/robin-fixture/s01-multistep/x'), env)).status, 421);
  const http = await worker.fetch(new Request(scenarioUrl('s01-multistep').replace('https:', 'http:')), env);
  assert.equal(http.status, 308);
  assert.equal(http.headers.get('location'), scenarioUrl('s01-multistep'));
  assert.equal((await worker.fetch(new Request(`${ORIGIN}/other/path`), env)).status, 404);
  assert.equal((await worker.fetch(new Request(scenarioUrl('not-a-scenario')), env)).status, 404);
  const robots = await worker.fetch(new Request(`${ORIGIN}/robots.txt`), env);
  assert.match(await robots.text(), /Disallow: \//);
  const { env: unset } = fakeEnv();
  unset.FIXTURE_HOSTNAME = '';
  assert.equal((await worker.fetch(new Request(scenarioUrl('s01-multistep')), unset)).status, 421, 'no configured host: refuse everything');
});

test('package: every response carries HSTS, noindex, no-store and a restrictive CSP', async () => {
  const { env } = fakeEnv();
  const res = await worker.fetch(new Request(scenarioUrl('s01-multistep')), env);
  assert.equal(res.status, 200);
  for (const [k, v] of Object.entries(SECURITY_HEADERS)) assert.equal(res.headers.get(k), v, k);
  assert.match(res.headers.get('strict-transport-security'), /max-age=31536000/);
});

test('package: admin diagnostics/teardown need the bearer token; unset or weak tokens close them', async () => {
  const admin = (slug, method = 'GET', auth) => new Request(`${ORIGIN}/robin-fixture/__robin/${method === 'GET' ? 'diagnostics' : 'teardown'}?scenario=${slug}`,
    { method, headers: auth ? { authorization: `Bearer ${auth}` } : {} });
  const good = 'z'.repeat(40);
  const { env } = fakeEnv({ token: good });
  assert.equal((await worker.fetch(admin('s14-duplicate'), env)).status, 401);
  assert.equal((await worker.fetch(admin('s14-duplicate', 'GET', 'wrong'.repeat(8)), env)).status, 401);
  const ok = await worker.fetch(admin('s14-duplicate', 'GET', good), env);
  assert.equal(ok.status, 200);
  assert.deepEqual(Object.keys(await ok.json()).sort(),
    ['acceptedSubmissions', 'page1GetCount', 'receipts', 'rejectedDuplicates', 'step1Posts', 'submitAttempts']);
  for (const weak of [undefined, '', 'short-token']) {
    const { env: w } = fakeEnv({ token: weak });
    assert.equal((await worker.fetch(admin('s14-duplicate', 'GET', weak || 'x'), w)).status, 401, `weak=${weak}`);
  }
});

async function submitThroughPackage(env, slug) {
  const p1 = await worker.fetch(new Request(scenarioUrl(slug)), env);
  const cookie = p1.headers.get('set-cookie').split(';')[0];
  const h = { cookie, 'content-type': 'application/json' };
  await worker.fetch(new Request(scenarioUrl(slug, 'careers/jobs/stage3-role/step-1'), { method: 'POST', headers: h, body: '{}' }), env);
  return worker.fetch(new Request(scenarioUrl(slug, 'careers/jobs/stage3-role/submit'), { method: 'POST', headers: h, body: '{}' }), env);
}

test('package: the idempotency counter is durable — a restarted Durable Object still refuses a second submission', async () => {
  const storage = fakeStorage();
  const first = fakeEnv({ storage });
  assert.equal((await submitThroughPackage(first.env, 's14-duplicate')).status, 200);
  const restarted = fakeEnv({ storage }); // new object instance, same persisted storage (eviction/restart)
  assert.equal((await submitThroughPackage(restarted.env, 's14-duplicate')).status, 409);
  assert.equal(storage.map.get('acceptedSubmissions'), 1);
  assert.equal(storage.map.get('rejectedDuplicates'), 1);
});

test('package: teardown and the expiry alarm wipe scenario data', async () => {
  const token = 'q'.repeat(40);
  const { env, storage, objects } = fakeEnv({ token });
  await submitThroughPackage(env, 's13-confirmation');
  assert.ok(await storage.getAlarm(), 'first use schedules the expiry alarm');
  const td = await worker.fetch(new Request(`${ORIGIN}/robin-fixture/__robin/teardown?scenario=s13-confirmation`,
    { method: 'POST', headers: { authorization: `Bearer ${token}` } }), env);
  assert.equal(td.status, 200);
  assert.equal(storage.map.size, 0);
  await submitThroughPackage(env, 's13-confirmation');
  await objects.get('s13-confirmation').alarm();
  assert.equal(storage.map.size, 0);
});

test('package: scenario profiles cover all 15 matrix rows and the routing refuses traversal outside the prefix', () => {
  const rows = new Set(Object.keys(SCENARIO_PROFILES).map((s) => Number(s.slice(1, 3))));
  assert.deepEqual([...rows].sort((a, b) => a - b), Array.from({ length: 15 }, (_, i) => i + 1));
  assert.equal(routeFor(new URL(`${ORIGIN}/robin-fixtureX/s01-multistep/a`), '/robin-fixture'), null);
  assert.equal(routeFor(new URL(`${ORIGIN}/robin-fixture/../s01-multistep/a`), '/robin-fixture'), null);
});

test('package config template cannot deploy by accident and holds no secret or real hostname', () => {
  assert.equal(fs.existsSync(path.join(PKG, 'wrangler.toml')), false, 'only the .example template is committed');
  const tpl = fs.readFileSync(path.join(PKG, 'wrangler.toml.example'), 'utf8');
  assert.match(tpl, /^workers_dev = false$/m);
  assert.match(tpl, /REPLACE-WITH-AUTHORISED-HOSTNAME/);
  assert.doesNotMatch(tpl, /^FIXTURE_ADMIN_TOKEN\s*=/m, 'the admin token is a secret, never a var');
});
