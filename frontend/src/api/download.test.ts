// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { webcrypto } from 'node:crypto';
import { apiDownload, setIdTokenProvider } from './client';

if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto, configurable: true });
}

const BYTES = new TextEncoder().encode('%PDF-1.7 example bytes');

async function hex(bytes: Uint8Array): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes.slice().buffer as ArrayBuffer);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function respond(status: number, body: BodyInit, headers: Record<string, string>) {
  return vi.fn(async (_url: string, _init?: RequestInit) => new Response(body, { status, headers }));
}

describe('apiDownload', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setIdTokenProvider(null);
  });

  it('sends the Firebase bearer token and returns bytes whose checksum matches the server digest', async () => {
    const sha = await hex(BYTES);
    const fetchMock = respond(200, BYTES, {
      'Content-Type': 'application/pdf',
      'Content-Disposition': 'attachment; filename="tailored-cv-1.pdf"',
      'X-Content-SHA256': sha,
    });
    vi.stubGlobal('fetch', fetchMock);
    setIdTokenProvider(async () => 'firebase-id-token');

    const file = await apiDownload('/resume-intelligence/cv/1/artifact', 'fallback.pdf');

    expect(file.sha256).toBe(sha);
    expect(file.filename).toBe('tailored-cv-1.pdf');
    expect(file.blob.type).toBe('application/pdf');
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer firebase-id-token');
    expect(init.credentials).toBe('include');
  });

  it('refuses bytes that do not match the recorded checksum', async () => {
    vi.stubGlobal('fetch', respond(200, BYTES, { 'X-Content-SHA256': '0'.repeat(64) }));
    await expect(apiDownload('/cover-letters/1/pdf', 'x.pdf')).rejects.toThrow(/does not match its recorded checksum/);
  });

  it('refuses a download that carries no checksum', async () => {
    vi.stubGlobal('fetch', respond(200, BYTES, { 'Content-Type': 'application/pdf' }));
    await expect(apiDownload('/cover-letters/1/pdf', 'x.pdf')).rejects.toThrow(/did not provide a checksum/);
  });

  it('surfaces the server error for a refused or missing artifact', async () => {
    vi.stubGlobal('fetch', respond(409, JSON.stringify({ error: 'The stored PDF does not match its recorded checksum and was not served.' }),
      { 'Content-Type': 'application/json' }));
    await expect(apiDownload('/resume-intelligence/cv/1/artifact', 'x.pdf')).rejects.toThrow(/was not served/);
  });
});
