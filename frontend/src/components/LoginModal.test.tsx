import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

import { AuthProvider } from '../context/AuthContext';
import { LoginModal } from './LoginModal';

const renderLoginModal = () =>
  renderToStaticMarkup(
    React.createElement(AuthProvider, null, React.createElement(LoginModal)),
  );

const DEV_CONTROL_LABEL = 'Auto-fill Dev Credentials';

describe('LoginModal dev-credentials visibility', () => {
  beforeEach(() => {
    // Vitest runs with `import.meta.env.DEV === true`, mirroring `vite dev`.
    vi.stubEnv('DEV', true);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('renders the dev convenience control in development mode', () => {
    const html = renderLoginModal();
    expect(html).toContain(DEV_CONTROL_LABEL);
    expect(html).toContain('Local Dev Mode');
  });

  it('does not render the dev convenience control in production mode', () => {
    vi.stubEnv('DEV', false);
    const html = renderLoginModal();
    expect(html).not.toContain(DEV_CONTROL_LABEL);
    expect(html).not.toContain('Local Dev Mode');
  });

  it('never exposes dev credentials in production markup', () => {
    vi.stubEnv('DEV', false);
    const html = renderLoginModal();
    expect(html).not.toContain('DevPassword123!');
    expect(html).not.toContain('dev@example.local');
  });

  it('keeps the normal email/password login form in both modes', () => {
    const devHtml = renderLoginModal();
    vi.stubEnv('DEV', false);
    const prodHtml = renderLoginModal();

    for (const html of [devHtml, prodHtml]) {
      expect(html).toContain('Email Address');
      expect(html).toContain('Password');
      expect(html).toContain('Sign In');
      expect(html).toContain('type="email"');
      expect(html).toContain('type="password"');
      expect(html).toContain('<form');
      // The credentials fields must start empty (no pre-filled secrets).
      expect(html).not.toContain('value="dev@example.local"');
      expect(html).not.toContain('value="DevPassword123!"');
    }
  });
});
