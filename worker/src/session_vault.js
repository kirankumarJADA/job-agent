/**
 * Stage 3F (Workstream B): browser session state at rest.
 *
 * Earlier builds wrote Playwright storageState (cookies + localStorage) as
 * plaintext JSON at artifactDir/sessions/<applicationId>/storage.json and
 * never deleted it. This vault replaces that:
 *
 *  - Default: DISABLED. With no key configured nothing is ever written; the
 *    session exists only in the live browser context for one run.
 *  - With a key (`ROBIN_WORKER_SESSION_KEY`, base64 32 bytes): AES-256-GCM,
 *    authenticated with the application id as additional data (a file copied
 *    to another application's directory will not decrypt), written atomically
 *    with mode 0600 in a 0700 directory, and rejected + deleted when older than
 *    the TTL, tampered with, or undecryptable.
 *  - Legacy plaintext storage.json files are always deleted on sight.
 *
 * Callers delete the vault entry on every terminal outcome (completion,
 * failure, hard stop/handoff, shutdown); it survives only an approval pause or
 * a crash, so the next run can resume, and only until the TTL.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export const SESSION_FILE = 'session.enc';
export const LEGACY_SESSION_FILE = 'storage.json';
export const DEFAULT_SESSION_TTL_MS = 2 * 60 * 60 * 1000;

function parseKey(raw) {
  if (!raw) return null;
  if (Buffer.isBuffer(raw)) return raw.length === 32 ? raw : null;
  try {
    const decoded = Buffer.from(String(raw).trim(), 'base64');
    return decoded.length === 32 ? decoded : null;
  } catch {
    return null;
  }
}

function safeSegment(id) {
  const s = String(id ?? '');
  return /^[A-Za-z0-9_-]{1,128}$/.test(s) ? s : null;
}

export class SessionVault {
  constructor({ artifactDir, key = null, ttlMs = DEFAULT_SESSION_TTL_MS, now = () => Date.now() } = {}) {
    this.root = artifactDir ? path.join(artifactDir, 'sessions') : null;
    this.key = parseKey(key);
    this.ttlMs = ttlMs;
    this.now = now;
  }

  get enabled() {
    return Boolean(this.root && this.key);
  }

  dirFor(applicationId) {
    const seg = safeSegment(applicationId);
    return this.root && seg ? path.join(this.root, seg) : null;
  }

  /** Deletes a legacy plaintext storage.json for one application (always, vault enabled or not). */
  removeLegacy(applicationId) {
    const dir = this.dirFor(applicationId);
    if (!dir) return false;
    const legacy = path.join(dir, LEGACY_SESSION_FILE);
    if (!fs.existsSync(legacy)) return false;
    fs.rmSync(legacy, { force: true });
    return true;
  }

  /** Returns a decrypted storageState object, or undefined (and deletes anything unusable). */
  load(applicationId) {
    this.removeLegacy(applicationId);
    const dir = this.dirFor(applicationId);
    if (!this.enabled || !dir) return undefined;
    const file = path.join(dir, SESSION_FILE);
    if (!fs.existsSync(file)) return undefined;
    try {
      const envelope = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (envelope?.v !== 1 || typeof envelope.savedAt !== 'number') throw new Error('bad envelope');
      if (this.now() - envelope.savedAt > this.ttlMs || envelope.savedAt > this.now() + 60_000) throw new Error('expired');
      const decipher = crypto.createDecipheriv('aes-256-gcm', this.key, Buffer.from(envelope.iv, 'base64'));
      decipher.setAAD(Buffer.from(`robin-session:${applicationId}:${envelope.savedAt}`));
      decipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
      const plain = Buffer.concat([decipher.update(Buffer.from(envelope.ct, 'base64')), decipher.final()]);
      return JSON.parse(plain.toString('utf8'));
    } catch {
      this.purge(applicationId);
      return undefined;
    }
  }

  /** Encrypts and atomically writes storageState. No-op when the vault is disabled. */
  save(applicationId, state) {
    const dir = this.dirFor(applicationId);
    if (!this.enabled || !dir || !state) return false;
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    try { fs.chmodSync(dir, 0o700); } catch { /* best effort on platforms without POSIX modes */ }
    const savedAt = this.now();
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', this.key, iv);
    cipher.setAAD(Buffer.from(`robin-session:${applicationId}:${savedAt}`));
    const ct = Buffer.concat([cipher.update(Buffer.from(JSON.stringify(state), 'utf8')), cipher.final()]);
    const envelope = JSON.stringify({ v: 1, savedAt, iv: iv.toString('base64'), tag: cipher.getAuthTag().toString('base64'),
      ct: ct.toString('base64') });
    const file = path.join(dir, SESSION_FILE);
    const tmp = `${file}.tmp-${process.pid}-${crypto.randomBytes(4).toString('hex')}`;
    fs.writeFileSync(tmp, envelope, { mode: 0o600 });
    fs.renameSync(tmp, file);
    try { fs.chmodSync(file, 0o600); } catch { /* best effort */ }
    return true;
  }

  /** Deletes everything stored for one application. */
  purge(applicationId) {
    const dir = this.dirFor(applicationId);
    if (!dir || !fs.existsSync(dir)) return false;
    fs.rmSync(dir, { recursive: true, force: true });
    return true;
  }

  /** Deletes every session (worker shutdown). */
  purgeAll() {
    if (!this.root || !fs.existsSync(this.root)) return 0;
    const entries = fs.readdirSync(this.root);
    fs.rmSync(this.root, { recursive: true, force: true });
    return entries.length;
  }

  /** Startup sweep: removes every legacy plaintext file and every expired or unreadable vault entry. */
  sweep() {
    if (!this.root || !fs.existsSync(this.root)) return 0;
    let removed = 0;
    for (const entry of fs.readdirSync(this.root)) {
      const dir = path.join(this.root, entry);
      if (!safeSegment(entry)) {
        fs.rmSync(dir, { recursive: true, force: true });
        removed += 1;
        continue;
      }
      if (this.removeLegacy(entry)) removed += 1;
      const file = path.join(dir, SESSION_FILE);
      if (!this.enabled) {
        if (fs.existsSync(dir)) { fs.rmSync(dir, { recursive: true, force: true }); removed += 1; }
        continue;
      }
      if (fs.existsSync(file) && this.load(entry) === undefined) removed += 1;
    }
    return removed;
  }
}
