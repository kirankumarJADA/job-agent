import { describe, expect, it } from 'vitest';

import { evaluateProtectedRoute, evaluatePublicOnlyRoute, evaluateVerificationRoute } from './RouteGuards';

const state = (overrides: Partial<Parameters<typeof evaluateProtectedRoute>[0]> = {}) => ({
  loading: false,
  authenticated: false,
  awaitingVerification: false,
  ...overrides,
});

describe('protected routes', () => {
  it('renders the app for a signed-in user', () => {
    expect(evaluateProtectedRoute(state({ authenticated: true }))).toBe('render');
  });

  it('sends a signed-out visitor to sign in', () => {
    expect(evaluateProtectedRoute(state())).toBe('redirect-login');
  });

  it('holds an unverified visitor at the verification screen instead of /login', () => {
    // The account exists and Firebase owns it; sending this visitor to the
    // sign-up form again would be wrong. Only verification is missing.
    expect(evaluateProtectedRoute(state({ awaitingVerification: true }))).toBe('redirect-verify');
  });

  it('shows the loader — never the app — while auth is unresolved', () => {
    // Without this, a reload would flash the protected shell for a signed-out
    // visitor, because the session check has not returned yet.
    expect(evaluateProtectedRoute(state({ loading: true }))).toBe('loading');
  });

  it('does not bounce a signed-in user whose session is still being restored', () => {
    // The other half of the same bug: optimistically redirecting to /login
    // during loading would kick a valid user out on every page reload.
    expect(evaluateProtectedRoute(state({ loading: true, authenticated: true }))).toBe('loading');
  });

  it('does not bounce a verifying visitor whose session check is still running', () => {
    expect(evaluateProtectedRoute(state({ loading: true, awaitingVerification: true }))).toBe('loading');
  });
});

describe('public-only routes', () => {
  it('renders the auth screen for a signed-out visitor', () => {
    expect(evaluatePublicOnlyRoute(state())).toBe('render');
  });

  it('sends a signed-in user into the app', () => {
    // Landing on /login with an active session should not present a sign-in
    // form that cannot succeed.
    expect(evaluatePublicOnlyRoute(state({ authenticated: true }))).toBe('redirect-app');
  });

  it('sends a verifying visitor back to the verification screen', () => {
    // Re-registering the same address mid-verification is never what the
    // visitor wants; the pending account owns the session flow until it is
    // verified or abandoned.
    expect(evaluatePublicOnlyRoute(state({ awaitingVerification: true }))).toBe('redirect-verify');
  });

  it('shows the loader while auth is unresolved rather than flickering a form', () => {
    expect(evaluatePublicOnlyRoute(state({ loading: true }))).toBe('loading');
    expect(evaluatePublicOnlyRoute(state({ loading: true, authenticated: true }))).toBe('loading');
  });
});

describe('verification route', () => {
  it('renders for a visitor with a pending unverified account', () => {
    expect(evaluateVerificationRoute(state({ awaitingVerification: true }))).toBe('render');
  });

  it('sends a signed-in user into the app — verification is done', () => {
    expect(evaluateVerificationRoute(state({ authenticated: true }))).toBe('redirect-app');
  });

  it('sends a visitor with nothing pending to sign in', () => {
    // /verify-email with no pending account is a bookmark, not a destination.
    expect(evaluateVerificationRoute(state())).toBe('redirect-login');
  });

  it('shows the loader while auth is unresolved', () => {
    expect(evaluateVerificationRoute(state({ loading: true }))).toBe('loading');
  });
});
