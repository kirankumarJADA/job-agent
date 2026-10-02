import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthFailure, idTokenFor, refreshFirebaseUser } from './authService';

// Only the firebase/auth symbols authService touches; the module under test
// is the real one.
vi.mock('firebase/auth', () => ({
  reload: vi.fn(),
  sendEmailVerification: vi.fn(),
  signInWithEmailAndPassword: vi.fn(),
  createUserWithEmailAndPassword: vi.fn(),
  signInWithPopup: vi.fn(),
  signOut: vi.fn(),
}));
vi.mock('./client', () => ({
  getFirebaseAuth: vi.fn(() => ({})),
}));

function fakeUser(overrides: Partial<{ emailVerified: boolean }> = {}) {
  return {
    uid: 'uid-1',
    email: 'candidate@example.test',
    emailVerified: overrides.emailVerified ?? false,
    getIdToken: vi.fn().mockResolvedValue('token-value'),
    reload: vi.fn().mockResolvedValue(undefined),
  } as unknown as import('firebase/auth').User & { getIdToken: ReturnType<typeof vi.fn> };
}

describe('idTokenFor (session-exchange token)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // A + B: the exchange token must be FORCE refreshed — the cached token
  // minted at sign-in still carries email_verified=false for an account
  // verified moments ago, which the backend rightly refuses with 403.
  it('forces a token refresh so the exchange carries the current verification claim', async () => {
    const user = fakeUser();

    const token = await idTokenFor(user);

    expect(user.getIdToken).toHaveBeenCalledWith(true);
    expect(token).toBe('token-value');
  });

  it('forces the refresh even when the user object already says verified', async () => {
    const user = fakeUser({ emailVerified: true });

    await idTokenFor(user);

    expect(user.getIdToken).toHaveBeenCalledWith(true);
  });
});

describe('refreshFirebaseUser (verification re-check)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // A: reload() is awaited and the SAME user object (mutated in place by the
  // SDK) is returned, so the caller reads post-reload emailVerified.
  it('awaits reload and returns the refreshed user', async () => {
    const { reload } = await import('firebase/auth');
    const user = fakeUser();

    const fresh = await refreshFirebaseUser(user);

    expect(reload).toHaveBeenCalledWith(user);
    expect(fresh).toBe(user);
  });

  it('wraps a reload failure in an AuthFailure instead of crashing the flow', async () => {
    const { reload } = await import('firebase/auth');
    vi.mocked(reload).mockRejectedValueOnce(new Error('network down'));
    const user = fakeUser();

    await expect(refreshFirebaseUser(user)).rejects.toBeInstanceOf(AuthFailure);
  });
});
