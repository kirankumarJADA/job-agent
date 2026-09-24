import { describe, expect, it } from 'vitest';

import { evaluateProtectedRoute, evaluatePublicOnlyRoute } from './RouteGuards';

describe('protected routes', () => {
  it('renders the app for a signed-in user', () => {
    expect(evaluateProtectedRoute({ loading: false, authenticated: true })).toBe('render');
  });

  it('sends a signed-out visitor to sign in', () => {
    expect(evaluateProtectedRoute({ loading: false, authenticated: false })).toBe('redirect-login');
  });

  it('shows the loader — never the app — while auth is unresolved', () => {
    // Without this, a reload would flash the protected shell for a signed-out
    // visitor, because the session check has not returned yet.
    expect(evaluateProtectedRoute({ loading: true, authenticated: false })).toBe('loading');
  });

  it('does not bounce a signed-in user whose session is still being restored', () => {
    // The other half of the same bug: optimistically redirecting to /login
    // during loading would kick a valid user out on every page reload.
    expect(evaluateProtectedRoute({ loading: true, authenticated: true })).toBe('loading');
  });
});

describe('public-only routes', () => {
  it('renders the auth screen for a signed-out visitor', () => {
    expect(evaluatePublicOnlyRoute({ loading: false, authenticated: false })).toBe('render');
  });

  it('sends a signed-in user into the app', () => {
    // Landing on /login with an active session should not present a sign-in
    // form that cannot succeed.
    expect(evaluatePublicOnlyRoute({ loading: false, authenticated: true })).toBe('redirect-app');
  });

  it('shows the loader while auth is unresolved rather than flickering a form', () => {
    expect(evaluatePublicOnlyRoute({ loading: true, authenticated: false })).toBe('loading');
    expect(evaluatePublicOnlyRoute({ loading: true, authenticated: true })).toBe('loading');
  });
});
