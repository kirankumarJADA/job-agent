import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

import { ApiError, apiFetch, setIdTokenProvider } from '../api/client';
import { currentFirebaseUser, currentIdToken } from '../firebase/client';
import {
  AuthFailure,
  createFirebaseAccount,
  idTokenFor,
  refreshFirebaseUser,
  requestEmailVerification,
  sendPasswordReset,
  signInWithEmail,
  signInWithGoogle as firebaseSignInWithGoogle,
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

/**
 * An email/password account that exists in Firebase but whose address has not
 * been verified yet, so no application session may be established for it.
 *
 * `inviteCode` and `signup` are carried through to the deferred session
 * exchange: registration is enforced by the backend when the exchange finally
 * happens, and `signup: true` lets the backend clean up the fresh Firebase
 * account if that exchange is refused. Persisted to sessionStorage so a page
 * reload in the middle of verifying does not lose the code the user typed.
 */
export interface PendingVerification {
  email: string;
  inviteCode?: string;
  signup: boolean;
}

const PENDING_STORAGE_KEY = 'robin.pending-verification';

function readStoredPending(): PendingVerification | null {
  try {
    const raw = window.sessionStorage.getItem(PENDING_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as PendingVerification;
    return parsed && typeof parsed.email === 'string' && parsed.email !== '' ? parsed : null;
  } catch {
    return null;
  }
}

function writeStoredPending(pending: PendingVerification | null): void {
  try {
    if (pending) {
      window.sessionStorage.setItem(PENDING_STORAGE_KEY, JSON.stringify(pending));
    } else {
      window.sessionStorage.removeItem(PENDING_STORAGE_KEY);
    }
  } catch {
    // sessionStorage being unavailable (private mode, disabled storage) must
    // never break authentication itself; the in-memory state still works for
    // the current tab.
  }
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
  /**
   * True when a Firebase account exists and its email is verified-waiting: the
   * verification screen is the only place this visitor belongs until they
   * follow the email link.
   */
  awaitingVerification: boolean;
  /** The account waiting for verification, when {@link awaitingVerification}. */
  pendingVerification: PendingVerification | null;
  login: (email: string, password: string) => Promise<void>;
  signUp: (input: SignUpInput) => Promise<void>;
  /**
   * Google sign-in through Firebase's popup flow. Google identities are
   * verified at the provider, so no verification email is involved. On the
   * sign-up screen the form's invite code is passed through so a brand-new
   * Google identity can pass the registration gate in the same step.
   */
  signInWithGoogle: (options?: { inviteCode?: string }) => Promise<void>;
  /** Asks Firebase to send its verification email again. */
  resendVerificationEmail: () => Promise<void>;
  /**
   * Re-checks Firebase's verified state after the user has (probably) followed
   * the email link. Returns 'not-verified' when the link has not been followed
   * yet; establishes the application session and returns 'verified' when it
   * has. Throws `ApiError`/`AuthFailure` for the caller to present.
   *
   * `inviteCode` is for the retry path: when the first exchange was refused
   * for a missing/invalid invite code (e.g. the sign-up form's code was lost
   * with the tab), the screen can collect one and re-run the check with it.
   */
  checkEmailVerification: (options?: { inviteCode?: string }) => Promise<'not-verified' | 'verified'>;
  /** Abandons the pending account and returns to a signed-out state. */
  clearPendingVerification: () => Promise<void>;
  logout: () => Promise<void>;
  requestPasswordReset: (email: string) => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [sessionResolved, setSessionResolved] = useState(false);
  const [firebaseResolved, setFirebaseResolved] = useState(!isFirebaseConfigured);
  const [pendingVerification, setPendingVerification] = useState<PendingVerification | null>(readStoredPending);

  /**
   * The Firebase user of the pending account. Not state: it is an opaque SDK
   * handle the verification screen needs (resend, reload, token), never
   * rendered, and re-obtainable from `getFirebaseAuth().currentUser` after a
   * reload — which is exactly what `checkEmailVerification` falls back to.
   */
  const pendingUserRef = useRef<FirebaseUserHandle | null>(null);

  const updatePending = useCallback(
    (
      value:
        | PendingVerification
        | null
        | ((previous: PendingVerification | null) => PendingVerification | null),
    ) => {
      setPendingVerification((previous) => {
        const next = typeof value === 'function' ? value(previous) : value;
        writeStoredPending(next);
        return next;
      });
    },
    [],
  );

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

  /**
   * Establishes the application session from a Firebase user whose email is
   * verified. The backend verifies the ID token cryptographically and enforces
   * the invite gate on account creation — this call only delivers the token.
   */
  const exchangeSession = useCallback(
    async (firebaseUser: FirebaseUserHandle, options?: { inviteCode?: string; signup?: boolean }) => {
      const idToken = await idTokenFor(firebaseUser);
      const response = await apiFetch<BackendUser>('/auth/firebase/session', {
        method: 'POST',
        body: JSON.stringify({
          idToken,
          inviteCode: options?.inviteCode || undefined,
          signup: options?.signup || undefined,
        }),
      });
      setUser(toUser(response));
    },
    [],
  );

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

          // An email/password account whose address Firebase has not verified
          // must never reach the session exchange — the backend refuses it, and
          // the application never sends one. Hold the visitor at the
          // verification screen instead. The stored invite code (if any) is
          // preserved for the deferred exchange.
          if (!firebaseUser.emailVerified) {
            pendingUserRef.current = firebaseUser;
            updatePending((previous) => ({
              email: firebaseUser.email ?? previous?.email ?? '',
              inviteCode: previous?.inviteCode,
              signup: previous?.signup ?? false,
            }));
            setFirebaseResolved(true);
            return;
          }

          void (async () => {
            try {
              // Recover an application session for a verified Firebase user
              // (e.g. reload after the cookie expired). Already-linked
              // accounts pass without the invite gate; a refusal here — a new
              // Google account on a gated deployment — is handled by the
              // explicit sign-in paths, which surface it properly.
              await exchangeSession(firebaseUser);
              if (!cancelled) {
                updatePending(null);
                pendingUserRef.current = null;
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
  }, [refresh, exchangeSession, updatePending]);

  const login = useCallback(
    async (email: string, password: string) => {
      const firebaseUser = await signInWithEmail(email, password);

      if (!firebaseUser.emailVerified) {
        // Verified-waiting: route to the verification screen instead of
        // attempting an exchange the backend would refuse.
        pendingUserRef.current = firebaseUser;
        updatePending((previous) => ({
          email: firebaseUser.email ?? email,
          inviteCode: previous?.inviteCode,
          signup: false,
        }));
        return;
      }

      await exchangeSession(firebaseUser);
    },
    [exchangeSession, updatePending],
  );

  const signUp = useCallback(
    async (input: SignUpInput) => {
      // 1. Firebase owns the credential.
      const firebaseUser = await createFirebaseAccount(input.fullName, input.email, input.password);

      // 2. Firebase's own verification email. The application account is NOT
      //    created here and no session is established: the exchange happens on
      //    the verification screen, after Firebase reports the address as
      //    verified. A failure to send (rate limiting, network) is not fatal —
      //    the account exists and the verification screen's Resend covers it.
      try {
        await requestEmailVerification(firebaseUser);
      } catch (error) {
        console.warn(toAuthFailure(error).message);
      }

      // 3. Hold at the verification screen. The auth-state listener fires with
      //    this same unverified user and lands in the same place, so the two
      //    paths converge. The invite code rides along for the deferred
      //    exchange, which is where the backend actually enforces it.
      pendingUserRef.current = firebaseUser;
      updatePending({
        email: input.email.trim(),
        inviteCode: input.inviteCode || undefined,
        signup: true,
      });
    },
    [updatePending],
  );

  const signInWithGoogle = useCallback(
    async (options?: { inviteCode?: string }) => {
      // Google identities are verified at the provider, so this path never
      // sends a verification email; the exchange below is the same one
      // email/password uses after verification. A refused registration (invite
      // gate) surfaces here for the caller to present.
      const firebaseUser = await firebaseSignInWithGoogle();
      await exchangeSession(
        firebaseUser,
        options?.inviteCode ? { inviteCode: options.inviteCode, signup: true } : undefined,
      );
    },
    [exchangeSession],
  );

  const resendVerificationEmail = useCallback(async () => {
    const firebaseUser = pendingUserRef.current ?? currentFirebaseUser();
    if (!firebaseUser) {
      // The Firebase session died while waiting (closed browser, revoked
      // token). Signing in again re-enters the verification screen.
      throw new AuthFailure('auth/no-current-user', 'Your sign-in session expired. Please sign in again.');
    }
    await requestEmailVerification(firebaseUser);
  }, []);

  const checkEmailVerification = useCallback(
    async (options?: { inviteCode?: string }): Promise<'not-verified' | 'verified'> => {
      const firebaseUser = pendingUserRef.current ?? currentFirebaseUser();
      if (!firebaseUser) {
        throw new AuthFailure('auth/no-current-user', 'Your sign-in session expired. Please sign in again.');
      }

      const fresh = await refreshFirebaseUser(firebaseUser);
      if (!fresh.emailVerified) {
        return 'not-verified';
      }

      // The address is verified: now — and only now — create the application
      // account/session. The invite code from the sign-up form (or from the
      // retry field) is delivered here, where the backend enforces it.
      const pending = pendingVerification;
      const inviteCode = options?.inviteCode || pending?.inviteCode;
      const signup = options?.inviteCode ? true : pending?.signup;
      await exchangeSession(fresh, { inviteCode, signup });
      pendingUserRef.current = null;
      updatePending(null);
      return 'verified';
    },
    [pendingVerification, exchangeSession, updatePending],
  );

  const clearPendingVerification = useCallback(async () => {
    await signOutOfFirebase();
    pendingUserRef.current = null;
    updatePending(null);
  }, [updatePending]);

  const requestPasswordReset = useCallback(async (email: string) => {
    // Firebase sends the email and owns the reset token entirely; this
    // application never sees or stores one.
    await sendPasswordReset(email);
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
      pendingUserRef.current = null;
      updatePending(null);
      setUser(null);
    }
  }, [updatePending]);

  const awaitingVerification = user === null && pendingVerification !== null;

  const value = useMemo<AuthContextType>(
    () => ({
      user,
      loading: !(sessionResolved && firebaseResolved),
      firebaseConfigured: isFirebaseConfigured,
      missingFirebaseKeys: firebaseConfiguration.missingKeys,
      awaitingVerification,
      pendingVerification,
      login,
      signUp,
      signInWithGoogle,
      resendVerificationEmail,
      checkEmailVerification,
      clearPendingVerification,
      logout,
      requestPasswordReset,
      refresh,
    }),
    [
      user,
      sessionResolved,
      firebaseResolved,
      awaitingVerification,
      pendingVerification,
      login,
      signUp,
      signInWithGoogle,
      resendVerificationEmail,
      checkEmailVerification,
      clearPendingVerification,
      logout,
      requestPasswordReset,
      refresh,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

/**
 * The Firebase SDK's auth user type, referenced structurally so this module
 * keeps its existing dependency shape (the concrete `User` type lives in
 * `firebase/auth`, which the auth service module owns).
 */
type FirebaseUserHandle = Parameters<typeof idTokenFor>[0];

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
