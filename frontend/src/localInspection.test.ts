import { describe, expect, it, vi } from 'vitest';

import { evaluateProtectedRoute } from './components/RouteGuards';
import {
  LOCAL_INSPECTION_EMAIL_ENV,
  LOCAL_INSPECTION_LOGIN_ENDPOINT,
  LOCAL_INSPECTION_MODE_ENV,
  LOCAL_INSPECTION_PASSWORD_ENV,
  assertNoLocalInspectionEnv,
  establishLocalInspectionSession,
  isLoopbackApiBase,
  localInspectionEnvViolations,
  resolveLocalInspectionMode,
  type LocalInspectionEnv,
} from './localInspection';

/**
 * Local inspection mode is a development convenience with a production-shaped
 * blast radius: it decides whether the app signs itself in. These tests pin that
 * decision, and in particular that EVERY non-development input leaves it off.
 *
 * Values below are obviously fake — the real dev credentials live only in the
 * developer's git-ignored frontend/.env.local.
 */
const DEV_ENV: LocalInspectionEnv = {
  dev: true,
  prod: false,
  mode: 'true',
  email: 'dev@example.local',
  password: 'fake-password-for-tests',
  apiBase: 'http://localhost:8080/api/v1',
};

const env = (overrides: Partial<LocalInspectionEnv> = {}): LocalInspectionEnv => ({
  ...DEV_ENV,
  ...overrides,
});

describe('local inspection mode — when it turns on', () => {
  it('enables only in a local development build pointed at a loopback API with both credentials', () => {
    const mode = resolveLocalInspectionMode(env());

    expect(mode.enabled).toBe(true);
    expect(mode.email).toBe(DEV_ENV.email);
    expect(mode.password).toBe(DEV_ENV.password);
  });

  it('accepts the documented flag spellings', () => {
    for (const value of ['true', 'TRUE', ' true ', '1', 'yes', 'on', 'enabled']) {
      expect(resolveLocalInspectionMode(env({ mode: value })).enabled).toBe(true);
    }
  });
});

describe('local inspection mode — production and other refusals', () => {
  it('cannot be enabled in a production build, even with the flag set', () => {
    const mode = resolveLocalInspectionMode(env({ dev: false, prod: true }));

    expect(mode.enabled).toBe(false);
    expect(mode.reason).toContain('not a local development build');
    // Nothing to sign in with, so the caller cannot make a request either.
    expect(mode.email).toBeNull();
    expect(mode.password).toBeNull();
  });

  it('stays off when neither DEV nor PROD is set (an unknown build, so fail closed)', () => {
    expect(resolveLocalInspectionMode(env({ dev: false, prod: false })).enabled).toBe(false);
  });

  it('stays off when DEV and PROD are both set', () => {
    expect(resolveLocalInspectionMode(env({ prod: true })).enabled).toBe(false);
  });

  it.each([undefined, '', '   ', 'false', 'FALSE', 'no', '0', 'off', 42, true])(
    'stays off for the flag value %p',
    (value) => {
      expect(resolveLocalInspectionMode(env({ mode: value })).enabled).toBe(false);
    },
  );

  it('refuses to run against a deployed backend, even from a local dev build', () => {
    // The seeded development password must never be offered to a deployed API,
    // which is reachable from a dev server just as easily as localhost is.
    for (const apiBase of [
      'https://robin-api.onrender.com/api/v1',
      'http://127.0.0.1.example.com/api/v1',
      'http://mylocalhost.example.com/api/v1',
      'not-a-url',
      '',
      undefined,
    ]) {
      const mode = resolveLocalInspectionMode(env({ apiBase }));
      expect(mode.enabled).toBe(false);
      expect(mode.reason).toContain('loopback');
    }
  });

  it.each([undefined, '', '   '])('requires both credentials (email=%p)', (email) => {
    const mode = resolveLocalInspectionMode(env({ email }));

    expect(mode.enabled).toBe(false);
    expect(mode.reason).toContain(LOCAL_INSPECTION_EMAIL_ENV);
  });

  it.each([undefined, '', '   '])('requires both credentials (password=%p)', (password) => {
    const mode = resolveLocalInspectionMode(env({ password }));

    expect(mode.enabled).toBe(false);
    expect(mode.reason).toContain(LOCAL_INSPECTION_PASSWORD_ENV);
  });

  it('never carries a credential out of a refused decision', () => {
    // A refused mode is logged and rendered; a password must not ride along in
    // the reason string or anywhere else in the object.
    const mode = resolveLocalInspectionMode(env({ prod: true }));

    expect(JSON.stringify(mode)).not.toContain(DEV_ENV.password as string);
    expect(mode.reason).not.toContain(DEV_ENV.password as string);
  });
});

describe('loopback API base detection', () => {
  it.each([
    'http://localhost:8080/api/v1',
    'http://LOCALHOST:8080/api/v1',
    'http://api.localhost:8080/api/v1',
    'http://127.0.0.1:8080/api/v1',
    'http://127.9.9.9/api/v1',
    'http://[::1]:8080/api/v1',
  ])('accepts %s', (apiBase) => {
    expect(isLoopbackApiBase(apiBase)).toBe(true);
  });

  it.each([
    'https://robin-api.onrender.com/api/v1',
    'https://mylocalhost.example.com/api/v1',
    'https://localhost.example.com/api/v1',
    'http://127.0.0.1.example.com/api/v1',
    'http://128.0.0.1/api/v1',
    'http://127.0.0.300/api/v1',
    '/api/v1',
    '',
    null,
  ])('rejects %p', (apiBase) => {
    expect(isLoopbackApiBase(apiBase)).toBe(false);
  });
});

describe('establishing the local session', () => {
  it('signs in through the ordinary local login endpoint', async () => {
    const post = vi.fn().mockResolvedValue({ userId: 'user-1', email: 'dev@example.local' });

    const result = await establishLocalInspectionSession(resolveLocalInspectionMode(env()), post);

    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith(LOCAL_INSPECTION_LOGIN_ENDPOINT, {
      email: DEV_ENV.email,
      password: DEV_ENV.password,
    });
    expect(result).toEqual({
      status: 'signed-in',
      response: { userId: 'user-1', email: 'dev@example.local' },
    });
  });

  it('makes no request at all when the mode is off', async () => {
    const post = vi.fn();

    const result = await establishLocalInspectionSession(
      resolveLocalInspectionMode(env({ dev: false, prod: true })),
      post,
    );

    expect(post).not.toHaveBeenCalled();
    expect(result.status).toBe('disabled');
  });

  it('reports a refusal instead of inventing a session', async () => {
    const post = vi.fn().mockRejectedValue(new Error('401 Unauthorized'));

    const result = await establishLocalInspectionSession(resolveLocalInspectionMode(env()), post);

    expect(result).toEqual({ status: 'failed' });
    // The error detail is not passed through: a failed sign-in must not become a
    // channel for echoing credentials.
    expect(JSON.stringify(result)).not.toContain(DEV_ENV.password as string);
  });
});

describe('what the route guards do with the mode', () => {
  const guardState = (authenticated: boolean) => ({
    loading: false,
    authenticated,
    awaitingVerification: false,
  });

  it('lets the app shell render once the local session is established', () => {
    // The guards only ever read the real session state, so "inspection mode on"
    // reaches the shell the same way any other local sign-in does.
    expect(evaluateProtectedRoute(guardState(true))).toBe('render');
  });

  it('still sends an unauthenticated production visitor to /login', async () => {
    // Production environment in, no session out: the guard decision is the
    // ordinary signed-out one, not a bypass.
    const mode = resolveLocalInspectionMode(env({ dev: false, prod: true }));
    const post = vi.fn();
    const attempt = await establishLocalInspectionSession(mode, post);

    expect(attempt.status).toBe('disabled');
    expect(post).not.toHaveBeenCalled();
    expect(evaluateProtectedRoute(guardState(false))).toBe('redirect-login');
  });

  it('still sends an unauthenticated visitor to /login when the local sign-in was refused', async () => {
    const attempt = await establishLocalInspectionSession(resolveLocalInspectionMode(env()), () =>
      Promise.reject(new Error('refused')),
    );

    expect(attempt.status).toBe('failed');
    expect(evaluateProtectedRoute(guardState(false))).toBe('redirect-login');
  });
});

describe('build guard', () => {
  it('reports nothing when the inspection variables are absent', () => {
    expect(localInspectionEnvViolations({})).toEqual([]);
    expect(() => assertNoLocalInspectionEnv({})).not.toThrow();
    expect(() => assertNoLocalInspectionEnv({ VITE_API_BASE_URL: 'https://api.example.com' })).not.toThrow();
  });

  it('treats a flag value of false as not set', () => {
    expect(localInspectionEnvViolations({ [LOCAL_INSPECTION_MODE_ENV]: 'false' })).toEqual([]);
    expect(localInspectionEnvViolations({ [LOCAL_INSPECTION_MODE_ENV]: '  ' })).toEqual([]);
  });

  it('refuses to build with the flag turned on', () => {
    expect(() => assertNoLocalInspectionEnv({ [LOCAL_INSPECTION_MODE_ENV]: 'true' })).toThrow(
      LOCAL_INSPECTION_MODE_ENV,
    );
  });

  it('refuses to build with either credential set, even with the flag off', () => {
    // This is the leak that matters most: Vite inlines VITE_ values into the
    // bundle, so a build given a credential would serve it to every visitor.
    expect(() =>
      assertNoLocalInspectionEnv({ [LOCAL_INSPECTION_PASSWORD_ENV]: 'fake-password-for-tests' }),
    ).toThrow(LOCAL_INSPECTION_PASSWORD_ENV);
    expect(() =>
      assertNoLocalInspectionEnv({ [LOCAL_INSPECTION_EMAIL_ENV]: 'dev@example.local' }),
    ).toThrow(LOCAL_INSPECTION_EMAIL_ENV);
  });

  it('names every offending variable at once', () => {
    const violations = localInspectionEnvViolations({
      [LOCAL_INSPECTION_MODE_ENV]: 'true',
      [LOCAL_INSPECTION_EMAIL_ENV]: 'dev@example.local',
      [LOCAL_INSPECTION_PASSWORD_ENV]: 'fake-password-for-tests',
    });

    expect(violations).toEqual([
      LOCAL_INSPECTION_MODE_ENV,
      LOCAL_INSPECTION_EMAIL_ENV,
      LOCAL_INSPECTION_PASSWORD_ENV,
    ]);
  });

  it('never repeats a credential value in the build error', () => {
    const password = 'fake-password-for-tests';

    expect(() =>
      assertNoLocalInspectionEnv({
        [LOCAL_INSPECTION_MODE_ENV]: 'true',
        [LOCAL_INSPECTION_PASSWORD_ENV]: password,
      }),
    ).toThrow(/Refusing to build/);

    try {
      assertNoLocalInspectionEnv({ [LOCAL_INSPECTION_PASSWORD_ENV]: password });
      expect.unreachable('the build guard must have thrown');
    } catch (error) {
      expect((error as Error).message).not.toContain(password);
    }
  });
});
