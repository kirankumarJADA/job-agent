import { describe, it, expect } from 'vitest';
import { build } from 'vite';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Strings that must never survive into a production bundle.
 *
 * The first four are the historical dev-credentials UI. The rest are
 * server-side secret names and shapes: the Firebase service account must never
 * be reachable from the browser, and a VITE_-prefixed copy of it would ship it
 * to every visitor.
 */
const FORBIDDEN_DEV_STRINGS = [
  'Auto-fill Dev Credentials',
  'DevPassword123!',
  'dev@example.local',
  'Local Dev Mode',
  'FIREBASE_PRIVATE_KEY',
  'FIREBASE_CLIENT_EMAIL',
  'BEGIN PRIVATE KEY',
  '"type":"service_account"',
];

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const readBundle = (outDir: string): string => {
  const assetsDir = join(outDir, 'assets');
  return readdirSync(assetsDir)
    .filter((file) => file.endsWith('.js'))
    .map((file) => readFileSync(join(assetsDir, file), 'utf8'))
    .join('\n');
};

describe('production bundle', () => {
  it(
    'contains no dev-only credentials or dev-mode UI',
    async () => {
      const outDir = mkdtempSync(join(tmpdir(), 'job-agent-dist-'));
      // Vitest runs with NODE_ENV=test; a production build must use NODE_ENV=production
      // so Vite resolves `import.meta.env.DEV` to a literal `false`.
      const previousNodeEnv = process.env.NODE_ENV;
      process.env.NODE_ENV = 'production';
      try {
        await build({
          root: frontendRoot,
          configFile: resolve(frontendRoot, 'vite.config.ts'),
          mode: 'production',
          logLevel: 'silent',
          build: { outDir, emptyOutDir: true },
        });

        const bundle = readBundle(outDir);
        // Sanity check: the bundle is real production application JavaScript.
        // 'Email Address' is the sign-in form's label, so its presence proves
        // the auth screens were actually compiled into this output rather than
        // the assertion passing because nothing was built.
        expect(bundle).toContain('Email Address');
        expect(bundle).not.toContain('jsxDEV');
        // The Firebase client is expected in the bundle — that is the point of
        // the integration. What must not be there is a server credential.
        //
        // 'identitytoolkit' is the Google Identity Toolkit host the Firebase
        // Auth SDK hard-codes, so it is a reliable marker that the SDK itself
        // was bundled (unlike the product name, which minification may drop).
        expect(bundle).toContain('identitytoolkit');
        // Our own integration must be present too, so this cannot pass on an
        // empty or stale build.
        expect(bundle).toContain('Firebase Authentication is not configured');
        expect(bundle).toContain('VITE_FIREBASE_API_KEY');

        const leaked = FORBIDDEN_DEV_STRINGS.filter((value) => bundle.includes(value));
        expect(leaked).toEqual([]);

        // The public web API key is legitimately present; assert it is the only
        // kind of Firebase configuration value in the bundle by confirming no
        // private-key material survived.
        expect(bundle).not.toMatch(/-----BEGIN [A-Z ]*PRIVATE KEY-----/);
      } finally {
        if (previousNodeEnv === undefined) {
          delete process.env.NODE_ENV;
        } else {
          process.env.NODE_ENV = previousNodeEnv;
        }
        rmSync(outDir, { recursive: true, force: true });
      }
    },
    180_000,
  );
});
