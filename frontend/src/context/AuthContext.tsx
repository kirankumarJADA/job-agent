import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

import { ApiError, apiFetch, setIdTokenProvider } from '../api/client';
import { currentIdToken } from '../firebase/client';
import {
  AuthFailure,
  createFirebaseAccount,
  idTokenFor,
  sendPasswordReset,
  signInWithEmail,
  signOutOfFirebase,
  subscribeToAuthState,
  toAuthFailure,
} from '../firebase/authService';
import { firebaseConfiguration, isFirebaseConfigured } from '../firebase/config';
import { User } from '../types';

/** Shape the backend actually returns from /auth/*. */
interface BackendUser {
  userId: string;
  email: string;
  displayName: string;
}

/** Normalises the backend payload, tolerating either id field name. */
function toUser(response: BackendUser & { id?: string }): User {
  return {
    id: response.userId ?? response.id ?? '',
    email: response.email,
    displayName: response.displayName,
  };
}

export interface SignUpInput {
  fullName: string;
  email: string;
  password: string;
  inviteCode: string;
}

export interface AuthContextType {
  /** The signed-in application user, or null. */
  user: User | null;
  /** True until both the application session and Firebase state are resolved. */
  loading: boolean;
  /** False when this build has no VITE_FIREBASE_* configuration. */
  firebaseConfigured: boolean;
  /** VITE_ variables that must be set before authentication can work. */
  missingFirebaseKeys: string[];
  login: (email: string, password: string) => Promise<void>;
  signUp: (input: SignUpInput) => Promise<void>;
  logout: () => Promise<void>;
  requestPasswordReset: (email: string) => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [sessionResolved, setSessionResolved] = useState(false);
  const [firebaseResolved, setFirebaseResolved] = useState(!isFirebaseConfigured);

  /**
   * Reads the current application session. This stays authoritative for
   * "is this browser signed in", because it is what the server itself will
   * enforce on every subsequent request.
   */
  const refresh = useCallback(async () => {
    try {
      const response = await apiFetch<BackendUser>('/auth/me');
      setUser(toUser(response));
    } catch {
      setUser(null);
    } finally {
      setSessionResolved(true);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;

    void refresh();

    if (!isFirebaseConfigured) {
      // Nothing to subscribe to. The auth pages render an explanation instead.
      return () => {
        cancelled = true;
      };
    }

    // Every outgoing request carries the current Firebase ID token; the SDK
    // refreshes it in the background. Registered here so the API client stays
    // free of Firebase and React dependencies.
    setIdTokenProvider(currentIdToken);

    let unsubscribe: () => void = () => undefined;
    try {
      unsubscribe = subscribeToAuthState(
        (firebaseUser) => {
          if (cancelled) {
            return;
          }
          if (!firebaseUser) {
            setFirebaseResolved(true);
            return;
          }
          void (async () => {
            try {
              // Exchange the ID token for an application session. The backend
              // derives the account from the verified token, so this is safe to
              // call on every Firebase state change (including token refresh).
              const idToken = await idTokenFor(firebaseUser);
              const response = await apiFetch<BackendUser>('/auth/firebase/session', {
                method: 'POST',
                body: JSON.stringify({ idToken }),
              });
              if (!cancelled) {
                setUser(toUser(response));
              }
            } catch {
              // An existing session may still be valid even if this exchange
              // fails; `refresh` (or the next sign-in) resolves the truth.
            } finally {
              if (!cancelled) {
                setFirebaseResolved(true);
              }
            }
          })();
        },
        (failure: AuthFailure) => {
          if (!cancelled) {
            setFirebaseResolved(true);
          }
          // Surfaced to the console only: a stale persisted session that cannot
          // be restored is not something to interrupt the page load for.
          console.warn(failure.message);
        },
      );
    } catch (error) {
      // Missing or unusable configuration. Fail to a resolved state so the UI
      // can explain itself rather than hanging on the loading screen.
      console.warn(toAuthFailure(error).message);
      setFirebaseResolved(true);
    }

    return () => {
      cancelled = true;
      unsubscribe();
      setIdTokenProvider(null);
    };
  }, [refresh]);

  const login = useCallback(async (email: string, password: string) => {
    const firebaseUser = await signInWithEmail(email, password);
    const idToken = await idTokenFor(firebaseUser);
    const response = await apiFetch<BackendUser>('/auth/firebase/session', {
      method: 'POST',
      body: JSON.stringify({ idToken }),
    });
    setUser(toUser(response));
  }, []);

  const signUp = useCallback(async (input: SignUpInput) => {
    // 1. Firebase owns the credential.
    const firebaseUser = await createFirebaseAccount(input.fullName, input.email, input.password);

    try {
      // 2. The application account is created only by the backend, which is
      //    where the registration invite code is enforced. No user id is sent —
      //    the backend uses the verified token's uid.
      //
      //    `signup: true` is not a permission and grants nothing: it tells the
      //    backend that the Firebase account was created by this very submission,
      //    so that if the registration is refused it may delete that fresh account
      //    instead of stranding an email address in Firebase. The backend still
      //    checks the account's own creation timestamp before deleting anything.
      const idToken = await idTokenFor(firebaseUser);
      const response = await apiFetch<BackendUser>('/auth/firebase/session', {
        method: 'POST',
        body: JSON.stringify({ idToken, inviteCode: input.inviteCode || undefined, signup: true }),
      });
      setUser(toUser(response));
    } catch (error) {
      // The Firebase account was created but the application refused it (a bad
      // or missing invite code, or registration closed). Sign the Firebase user
      // back out so the browser is not left in a half-authenticated state, and
      // leave no session behind for an account that does not exist.
      await signOutOfFirebase();
      setUser(null);
      throw error;
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await apiFetch<void>('/auth/logout', { method: 'POST' });
    } catch (error) {
      // A 401 here just means the server session had already gone; the local
      // sign-out below still has to happen.
      if (!(error instanceof ApiError)) {
        throw error;
      }
    } finally {
      await signOutOfFirebase();
      setUser(null);
    }
  }, []);

  const requestPasswordReset = useCallback(async (email: string) => {
    // Firebase sends the email and owns the reset token entirely; this
    // application never sees or stores one.
    await sendPasswordReset(email);
  }, []);

  const value = useMemo<AuthContextType>(
    () => ({
      user,
      loading: !(sessionResolved && firebaseResolved),
      firebaseConfigured: isFirebaseConfigured,
      missingFirebaseKeys: firebaseConfiguration.missingKeys,
      login,
      signUp,
      logout,
      requestPasswordReset,
      refresh,
    }),
    [user, sessionResolved, firebaseResolved, login, signUp, logout, requestPasswordReset, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
