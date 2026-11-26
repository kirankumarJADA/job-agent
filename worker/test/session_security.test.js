// Stage 3F — browser session lifecycle, shutdown cleanup and artifact policy.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { SessionVault, SESSION_FILE, LEGACY_SESSION_FILE } from '../src/session_vault.js';
import { BrowserWorker } from '../src/browser_worker.js';
import { StepType, Policy } from '../src/plan.js';
import {
  assertArtifactAcceptable, ArtifactPolicyError, MAX_UNIVERSAL_UPLOAD_BYTES, declaredFormat, safeArtifactFileName,
} from '../src/artifact_policy.js';

const KEY = Buffer.alloc(32, 9).toString('base64');
const APP = '11111111-2222-3333-4444-555555555555';
const STATE = { cookies: [{ name: 'portal_session', value: 'SUPER-SECRET-COOKIE-VALUE', domain: '127.0.0.1', path: '/',
  expires: -1, httpOnly: true, secure: false, sameSite: 'Lax' }], origins: [] };
const tmp = (p) => fs.mkdtempSync(path.join(os.tmpdir(), p));

// ── vault ──────────────────────────────────────────────────────────────

test('without a key the vault is disabled and writes nothing to disk', () => {
  const dir = tmp('vault-off-');
  const vault = new SessionVault({ artifactDir: dir });
  assert.equal(vault.enabled, false);
  assert.equal(vault.save(APP, STATE), false);
  assert.equal(fs.existsSync(path.join(dir, 'sessions')), false);
  assert.equal(vault.load(APP), undefined);
});

test('with a key the session is encrypted at rest and round-trips', () => {
  const dir = tmp('vault-on-');
  const vault = new SessionVault({ artifactDir: dir, key: KEY });
  assert.equal(vault.save(APP, STATE), true);
  const file = path.join(dir, 'sessions', APP, SESSION_FILE);
  const raw = fs.readFileSync(file, 'utf8');
  assert.ok(!raw.includes('SUPER-SECRET-COOKIE-VALUE') && !raw.includes('portal_session'), 'no plaintext at rest');
  assert.deepEqual(vault.load(APP), STATE);
  if (process.platform !== 'win32') {
    assert.equal(fs.statSync(file).mode & 0o777, 0o600);
    assert.equal(fs.statSync(path.dirname(file)).mode & 0o777, 0o700);
  }
});

test('a session file moved to another application, tampered with, expired, or read with the wrong key is rejected and deleted', () => {
  const dir = tmp('vault-adv-');
  const vault = new SessionVault({ artifactDir: dir, key: KEY });
  vault.save(APP, STATE);
  const other = '99999999-2222-3333-4444-555555555555';
  fs.mkdirSync(path.join(dir, 'sessions', other), { recursive: true });
  fs.copyFileSync(path.join(dir, 'sessions', APP, SESSION_FILE), path.join(dir, 'sessions', other, SESSION_FILE));
  assert.equal(vault.load(other), undefined, 'AAD binds the ciphertext to its application');
  assert.equal(fs.existsSync(path.join(dir, 'sessions', other)), false);

  const file = path.join(dir, 'sessions', APP, SESSION_FILE);
  const env = JSON.parse(fs.readFileSync(file, 'utf8'));
  env.ct = Buffer.from(Buffer.from(env.ct, 'base64').map((b, i) => (i === 0 ? b ^ 1 : b))).toString('base64');
  fs.writeFileSync(file, JSON.stringify(env));
  assert.equal(vault.load(APP), undefined, 'tampering is detected');
  assert.equal(fs.existsSync(path.join(dir, 'sessions', APP)), false);

  let now = 1_000_000;
  const clocked = new SessionVault({ artifactDir: dir, key: KEY, ttlMs: 1000, now: () => now });
  clocked.save(APP, STATE);
  now += 5000;
  assert.equal(clocked.load(APP), undefined, 'expired sessions are never reused');
  assert.equal(fs.existsSync(path.join(dir, 'sessions', APP)), false);

  vault.save(APP, STATE);
  assert.equal(new SessionVault({ artifactDir: dir, key: Buffer.alloc(32, 1).toString('base64') }).load(APP), undefined);
});

test('legacy plaintext storage.json files are deleted on load and by the startup sweep, and path tricks are refused', () => {
  const dir = tmp('vault-legacy-');
  for (const id of [APP, 'other-app']) {
    fs.mkdirSync(path.join(dir, 'sessions', id), { recursive: true });
    fs.writeFileSync(path.join(dir, 'sessions', id, LEGACY_SESSION_FILE), JSON.stringify(STATE));
  }
  fs.mkdirSync(path.join(dir, 'sessions', 'weird name!'), { recursive: true });
  const vault = new SessionVault({ artifactDir: dir });
  assert.equal(vault.load(APP), undefined);
  assert.equal(fs.existsSync(path.join(dir, 'sessions', APP, LEGACY_SESSION_FILE)), false);
  assert.ok(vault.sweep() >= 2);
  assert.equal(fs.existsSync(path.join(dir, 'sessions', 'other-app', LEGACY_SESSION_FILE)), false);
  assert.equal(fs.existsSync(path.join(dir, 'sessions', 'weird name!')), false);
  assert.equal(vault.dirFor('../../etc'), null);
});

// ── worker-level lifecycle ─────────────────────────────────────────────

async function cookieServer() {
  const seen = [];
  const server = http.createServer((req, res) => {
    seen.push(req.headers.cookie ?? '');
    res.setHeader('set-cookie', 'portal_session=fresh-value; Path=/; HttpOnly');
    res.end('<!doctype html><title>ok</title><body>ok</body>');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { seen, url: `http://127.0.0.1:${server.address().port}/apply`, close: () => new Promise((r) => server.close(r)) };
}

function inspectionPlan(url, applicationId) {
  return { planId: `plan-${applicationId}`, planType: 'INSPECTION', version: 1,
    correlation: { jobId: 'job-1', applicationId },
    steps: [{ id: 'nav', type: StepType.NAVIGATE, policy: Policy.AUTO, params: { url } },
      { id: 'safety', type: StepType.POLICY_CHECK, policy: Policy.AUTO, params: { action: 'INSPECT_ONLY' } }] };
}

test('a completed run leaves no session on disk, ignores a planted legacy storage.json, and never sends its cookie', async () => {
  const root = tmp('worker-session-');
  const server = await cookieServer();
  const applicationId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  fs.mkdirSync(path.join(root, 'sessions', applicationId), { recursive: true });
  fs.writeFileSync(path.join(root, 'sessions', applicationId, LEGACY_SESSION_FILE), JSON.stringify(STATE));
  try {
    const worker = new BrowserWorker({ localTestNetwork: true, sessionKey: KEY, statePath: path.join(root, 'state.json'), artifactDir: root });
    const result = await worker.execute(inspectionPlan(server.url, applicationId), { headless: true });
    assert.equal(result.status, 'COMPLETED');
    assert.ok(server.seen.every((c) => !c.includes('SUPER-SECRET-COOKIE-VALUE')), 'the legacy plaintext session was never loaded');
    assert.equal(fs.existsSync(path.join(root, 'sessions', applicationId)), false, 'completion deletes the session');
  } finally {
    await server.close();
  }
});

test('a failed run deletes its session too (exception path)', async () => {
  const root = tmp('worker-session-fail-');
  const applicationId = 'aaaaaaaa-bbbb-cccc-dddd-ffffffffffff';
  const vault = new SessionVault({ artifactDir: root, key: KEY });
  vault.save(applicationId, STATE);
  const worker = new BrowserWorker({ localTestNetwork: true, sessionKey: KEY, statePath: path.join(root, 'state.json'), artifactDir: root });
  await assert.rejects(() => worker.execute(inspectionPlan('http://127.0.0.1:1/unreachable', applicationId), { headless: true }));
  assert.equal(fs.existsSync(path.join(root, 'sessions', applicationId)), false);
});

const WORKER_JS = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src', 'worker.js');

test('SIGTERM: the worker sweeps legacy files at startup and purges every session before exiting',
  { skip: process.platform === 'win32' ? 'POSIX signal delivery to a Node child is not available on Windows' : false },
  async () => {
    const root = tmp('worker-sigterm-');
    const vault = new SessionVault({ artifactDir: root, key: KEY });
    vault.save(APP, STATE);
    fs.mkdirSync(path.join(root, 'sessions', 'legacy-app'), { recursive: true });
    fs.writeFileSync(path.join(root, 'sessions', 'legacy-app', LEGACY_SESSION_FILE), JSON.stringify(STATE));
    const child = spawn(process.execPath, [WORKER_JS, '--artifacts', root, '--state', path.join(root, 'state.json')], {
      env: { ...process.env, ROBIN_WORKER_SESSION_KEY: KEY, WORKER_POLL_URL: '', WORKER_PLAN_FILE: '' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error(`worker did not become ready: ${out}`)), 15000);
      child.stdout.on('data', () => { if (out.includes('WORKER_READY')) { clearTimeout(t); resolve(); } });
    });
    assert.equal(fs.existsSync(path.join(root, 'sessions', 'legacy-app', LEGACY_SESSION_FILE)), false, 'startup sweep');
    assert.equal(fs.existsSync(path.join(root, 'sessions', APP, SESSION_FILE)), true, 'a fresh encrypted session survives startup');
    const exited = new Promise((resolve) => child.on('exit', (code, signal) => resolve({ code, signal })));
    child.kill('SIGTERM');
    const { code } = await exited;
    assert.equal(code, 0);
    assert.ok(out.includes('WORKER_SHUTDOWN'));
    assert.equal(fs.existsSync(path.join(root, 'sessions')), false, 'shutdown purges every session');
  });

// ── artifact policy (Workstream D) ─────────────────────────────────────

test('universal artifacts: 10 MiB cap, real file signature, and safe file names', () => {
  const pdf = Buffer.concat([Buffer.from('%PDF-1.7\n'), Buffer.alloc(64, 0x20)]);
  assert.equal(assertArtifactAcceptable(pdf, { fileName: 'cv.pdf' }), 'pdf');
  assert.throws(() => assertArtifactAcceptable(Buffer.alloc(MAX_UNIVERSAL_UPLOAD_BYTES + 1, 0x25), { fileName: 'cv.pdf' }),
    (e) => e instanceof ArtifactPolicyError && e.reason === 'ARTIFACT_TOO_LARGE');
  assert.throws(() => assertArtifactAcceptable(Buffer.from('MZ\x90\x00 not a pdf'), { fileName: 'cv.pdf' }),
    (e) => e.reason === 'ARTIFACT_SIGNATURE_MISMATCH');
  assert.throws(() => assertArtifactAcceptable(Buffer.from('%PDF-1.4 renamed'), { fileName: 'cv.docx' }),
    (e) => e.reason === 'ARTIFACT_SIGNATURE_MISMATCH');
  assert.throws(() => assertArtifactAcceptable(Buffer.from('%PDF-1.4'), { fileName: 'cv.exe' }),
    (e) => e.reason === 'ARTIFACT_TYPE_UNSUPPORTED');
  assert.throws(() => assertArtifactAcceptable(Buffer.alloc(0), { fileName: 'cv.pdf' }), (e) => e.reason === 'ARTIFACT_EMPTY');
  assert.equal(assertArtifactAcceptable(Buffer.from('Dear hiring team,\n'), { fileName: 'cl.txt' }), 'text');
  assert.throws(() => assertArtifactAcceptable(Buffer.from([0x44, 0x00, 0x45]), { fileName: 'cl.txt' }),
    (e) => e.reason === 'ARTIFACT_SIGNATURE_MISMATCH');
  assert.equal(assertArtifactAcceptable(Buffer.from([0x50, 0x4b, 0x03, 0x04, 1, 2]),
    { contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }), 'docx');
  assert.equal(declaredFormat({ contentType: 'application/pdf; charset=binary' }), 'pdf');
  assert.equal(safeArtifactFileName('../../etc/passwd'), 'passwd');
  assert.equal(safeArtifactFileName('..'), 'document');
  assert.equal(safeArtifactFileName('C:\\Users\\x\\cv<1>.pdf'), 'cv_1_.pdf');
});
