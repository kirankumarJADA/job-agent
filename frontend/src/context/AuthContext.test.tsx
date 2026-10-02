// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render } from '@testing-library/react';

import { ApiError as ApiErrorStub } from '../api/client';
import { AuthFailure } from '../firebase/authService';
import { AuthContextType, useAuth, AuthProvider } from './AuthContext';

/**
 * Behavioural tests for the email-verification gate (the production incident:
 * a verified email still could not reach the application). The Firebase layer
 * is mocked at the module boundary; the flow, the session exchange and the
 * pending-verification state machine are the real ones.
 */

// The api client is mocked wholesale: ApiError keeps the constructor shape
// the code under test relies on (status + message).
vi.mock('../api/client', () => {
  class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  }
  return {
    ApiError,
    API_BASE: 'http://test/api/v1',
    setIdTokenProvider: vi.fn(),
    apiFetch: vi.fn(),
  };
});

// The Firebase service boundary: everything the context imports, with
// AuthFailure kept real (the context's instanceof checks depend on it).
vi.mock('../firebase/authService', async (importOriginal) => ({
  ...(await importOriginal()),
  currentFirebaseUser: vi.fn(() => null),
  idTokenFor: vi.fn(async () => 'fresh-token'),
  refreshFirebaseUser: vi.fn(),
  requestEmailVerification: vi.fn(async () => undefined),
  sendPasswordReset: vi.fn(async () => undefined),
  signInWithEmail: vi.fn(),
  signInWithGoogle: vi.fn(),
  signOutOfFirebase: vi.fn(async () => undefined),
  subscribeToAuthState: vi.fn(() => () => undefined),
  createFirebaseAccount: vi.fn(),
}));

vi.mock('../firebase/client', () => ({
  currentFirebaseUser: vi.fn(() => null),
  currentIdToken: vi.fn(async () => null),
}));

vi.mock('../localInspection', () => ({
  LOCAL_INSPECTION_MARKER: 'local-inspection',
  currentLocalInspectionMode: vi.fn(() => ({ enabled: false })),
  establishLocalInspectionSession: vi.fn(),
}));

vi.mock('../firebase/config', () => ({
  firebaseConfiguration: vi.fn(() => ({
    apiKey: 'test', authDomain: 'test', projectId: 'test',
    storageBucket: 'test', messagingSenderId: 'test', appId: 'test',
  })),
  isFirebaseConfigured: vi.fn(() => true),
}));

import { apiFetch } from '../api/client';
import type { User as FirebaseUser } from 'firebase/auth';
import {
  createFirebaseAccount,
  idTokenFor,
  refreshFirebaseUser,
  subscribeToAuthState,
} from '../firebase/authService';

const mockedApiFetch = vi.mocked(apiFetch);
const mockedRefresh = vi.mocked(refreshFirebaseUser);
const mockedIdTokenFor = vi.mocked(idTokenFor);
const mockedCreate = vi.mocked(createFirebaseAccount);
const mockedSubscribe = vi.mocked(subscribeToAuthState);

function fakeFirebaseUser(emailVerified: boolean): FirebaseUser {
  return {
    uid: 'uid-1',
    email: 'candidate@example.test',
    emailVerified,
    displayName: 'Candidate',
    getIdToken: vi.fn(async () => 'fresh-token'),
  } as unknown as FirebaseUser;
}

const BackendUser = { userId: 'user-1', email: 'candidate@example.test', displayName: 'Candidate' };

type Harness = { context: AuthContextType | null };

function renderHarness(): Harness {
  const harness: Harness = { context: null };
  function Probe() {
    const value = useAuth();
    harness.context = value;
    return null;
  }
  render(
    React.createElement(AuthProvider, null, React.createElement(Probe)),
  );
  return harness;
}

beforeEach(() => {
  vi.clearAllMocks();
  window.sessionStorage.clear();
  // Mount refresh finds no existing session (401) and settles signed out.
  mockedApiFetch.mockImplementation((async (path: string) => {
    if (String(path).includes('/auth/me')) {
      throw new ApiErrorStub(401, 'Not signed in');
    }
    throw new ApiErrorStub(404, 'unexpected');
  }) as typeof apiFetch);
  mockedSubscribe.mockReturnValue(() => undefined);
});

afterEach(() => {
  vi.clearAllMocks();
});

async function signUpPending() {
  mockedCreate.mockResolvedValueOnce(fakeFirebaseUser(false));
  const harness = renderHarness();
  await act(async () => {
    await harness.context!.signUp({
      fullName: 'Candidate', email: 'candidate@example.test',
      password: 'PasswordA1!', inviteCode: 'INVITE-1',
    });
  });
  return harness;
}

describe('checkEmailVerification — the verification gate', () => {
  it('exchanges the freshly verified identity: reload → verified → forced token → session (C, D)', async () => {
    const harness = await signUpPending();
    // Firebase now reports the address verified after the email link:
    const verified = fakeFirebaseUser(true);
    mockedRefresh.mockResolvedValueOnce(verified);
    mockedIdTokenFor.mockResolvedValueOnce('fresh-token');
    mockedApiFetch.mockImplementation((async (path: string, init?: RequestInit) => {
      if (String(path).includes('/auth/firebase/session')) {
        const body = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
        expect(body.idToken).toBe('fresh-token');
        expect(body.signup).toBe(true);
        expect(body.inviteCode).toBe('INVITE-1');
        return BackendUser;
      }
      throw new ApiErrorStub(401, 'Not signed in');
    }) as typeof apiFetch);

    let result: 'not-verified' | 'verified' | undefined;
    await act(async () => {
      result = await harness.context!.checkEmailVerification();
    });

    expect(result).toBe('verified');
    expect(mockedIdTokenFor).toHaveBeenCalledWith(verified);
    // The verified identity became the application session:
    expect(harness.context!.user).toEqual({
      id: 'user-1', email: 'candidate@example.test', displayName: 'Candidate',
    });
  });

  it('an unverified Firebase user never reaches the session exchange (E)', async () => {
    const harness = await signUpPending();
    mockedRefresh.mockResolvedValueOnce(fakeFirebaseUser(false));

    let result: 'not-verified' | 'verified' | undefined;
    await act(async () => {
      result = await harness.context!.checkEmailVerification();
    });

    expect(result).toBe('not-verified');
    expect(mockedApiFetch).not.toHaveBeenCalledWith(
      expect.stringContaining('/auth/firebase/session'),
      expect.anything(),
    );
  });

  it('a reload failure surfaces as a retryable AuthFailure and never bypasses the gate (F)', async () => {
    const harness = await signUpPending();
    mockedRefresh.mockRejectedValueOnce(new AuthFailure('auth/network', 'Check your connection and try again.'));

    await act(async () => {
      await expect(harness.context!.checkEmailVerification()).rejects.toBeInstanceOf(AuthFailure);
    });
    expect(mockedApiFetch).not.toHaveBeenCalledWith(
      expect.stringContaining('/auth/firebase/session'),
      expect.anything(),
    );
  });

  it('a backend refusal (stale/invalid token) keeps the visitor at the gate (G)', async () => {
    const harness = await signUpPending();
    mockedRefresh.mockResolvedValueOnce(fakeFirebaseUser(true));
    mockedIdTokenFor.mockResolvedValueOnce('stale-token');
    mockedApiFetch.mockImplementation((async (path: string) => {
      if (String(path).includes('/auth/firebase/session')) {
        throw new ApiErrorStub(403,
          'This email address has not been verified yet. Follow the verification link Firebase emailed you, then sign in again.');
      }
      throw new ApiErrorStub(401, 'Not signed in');
    }) as typeof apiFetch);

    await act(async () => {
      await expect(harness.context!.checkEmailVerification()).rejects.toBeInstanceOf(ApiErrorStub);
    });
    // The refusal must NOT clear the pending state or the session:
    expect(harness.context!.user).toBeNull();
    expect(harness.context!.pendingVerification).not.toBeNull();
  });

  it('the pending verification state is cleared only after a successful exchange (H)', async () => {
    const harness = await signUpPending();
    expect(harness.context!.pendingVerification).not.toBeNull();

    mockedRefresh.mockResolvedValueOnce(fakeFirebaseUser(true));
    mockedApiFetch.mockImplementation((async (path: string) => {
      if (String(path).includes('/auth/firebase/session')) {
        return BackendUser;
      }
      throw new ApiErrorStub(401, 'Not signed in');
    }) as typeof apiFetch);

    await act(async () => {
      await harness.context!.checkEmailVerification();
    });

    expect(harness.context!.user).not.toBeNull();
    expect(harness.context!.pendingVerification).toBeNull();
  });
});
