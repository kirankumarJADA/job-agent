import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';

import { LOCAL_INSPECTION_MARKER } from '../localInspection';

/**
 * The banner is the visible half of local inspection mode. These tests pin both
 * directions: it is silent unless the mode is actually configured, and it is
 * unmistakable when it is — a developer must never be left guessing whether the
 * session they are looking at came from a real sign-in.
 *
 * `import.meta.env` is stubbed rather than the mode object, so the real
 * resolution path (flag + development build + loopback API + credentials) is
 * what gets exercised. Because the component decides at module load — that is
 * what lets Vite remove it from a production bundle entirely — each case
 * re-imports the module after stubbing. The removal itself is asserted against
 * the built bundle in devCredentialsBundle.test.ts, which is the only place it
 * can be observed.
 */
afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

const render = async (): Promise<string> => {
  vi.resetModules();
  const { LocalInspectionBanner } = await import('./LocalInspectionBanner');
  return renderToStaticMarkup(React.createElement(LocalInspectionBanner));
};

describe('local inspection banner', () => {
  it('renders nothing when the mode is not configured', async () => {
    expect(await render()).toBe('');
  });

  it('renders nothing when the flag is set but no credentials are present', async () => {
    vi.stubEnv('VITE_LOCAL_INSPECTION_MODE', 'true');

    expect(await render()).toBe('');
  });

  it('announces the mode when it is fully configured', async () => {
    vi.stubEnv('VITE_LOCAL_INSPECTION_MODE', 'true');
    vi.stubEnv('VITE_LOCAL_INSPECTION_EMAIL', 'dev@example.local');
    vi.stubEnv('VITE_LOCAL_INSPECTION_PASSWORD', 'fake-password-for-tests');

    const html = await render();

    expect(html).toContain(LOCAL_INSPECTION_MARKER);
    expect(html).toContain('Development only');
    expect(html).toContain('local-inspection-banner');
    // The notice must never become a place credentials are rendered.
    expect(html).not.toContain('fake-password-for-tests');
    expect(html).not.toContain('dev@example.local');
  });
});
