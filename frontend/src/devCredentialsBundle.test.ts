import { describe, it, expect } from 'vitest';
import { build } from 'vite';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const FORBIDDEN_DEV_STRINGS = [
  'Auto-fill Dev Credentials',
  'DevPassword123!',
  'dev@example.local',
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
        expect(bundle).toContain('Email Address');
        expect(bundle).not.toContain('jsxDEV');

        const leaked = FORBIDDEN_DEV_STRINGS.filter((value) => bundle.includes(value));
        expect(leaked).toEqual([]);
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
