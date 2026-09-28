import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const config = JSON.parse(readFileSync(resolve(frontendRoot, 'vercel.json'), 'utf8')) as {
  $schema: string;
  rewrites: Array<{ source: string; destination: string }>;
};

describe('Vercel SPA routing', () => {
  it('rewrites every current React Router page to the SPA entrypoint', () => {
    const routes = [
      '/login',
      '/signup',
      '/forgot-password',
      '/verify-email',
      '/jobs',
      '/jobs/:id',
      '/profile',
      '/preferences',
      '/models',
      '/sources',
      '/logs',
    ];
    const sources = config.rewrites.map(({ source }) => source);

    expect(sources).toEqual(routes);
    expect(config.rewrites.every(({ destination }) => destination === '/index.html')).toBe(true);
  });

  it('does not add API, asset, or generic catch-all rewrites', () => {
    const sources = config.rewrites.map(({ source }) => source);

    expect(sources).not.toContain('/api/:path*');
    expect(sources).not.toContain('/assets/:path*');
    expect(sources).not.toContain('/:path*');
    expect(sources.every((source) => !source.startsWith('/api/'))).toBe(true);
    expect(sources.every((source) => !source.startsWith('/assets/'))).toBe(true);
  });

  it('keeps the Vercel configuration alongside the SPA entrypoint in frontend root', () => {
    expect(config.$schema).toBe('https://openapi.vercel.sh/vercel.json');
    expect(existsSync(resolve(frontendRoot, 'index.html'))).toBe(true);
  });
});
