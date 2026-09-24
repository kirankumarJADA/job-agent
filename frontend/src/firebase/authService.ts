import {
  createUserWithEmailAndPassword,
  GoogleAuthProvider,
  onAuthStateChanged,
  reload,
  sendEmailVerification,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  updateProfile,
  type User,
} from 'firebase/auth';

import { getFirebaseAuth } from './client';

/**
 * Human-readable text for the Firebase error codes a user can actually
 * provoke. Anything unmapped falls back to a generic message plus the code —
 * the code is a diagnostic identifier, not a secret, and including it turns
 * "sign-in failed" into something an operator can act on.
 */
const FRIENDLY_MESSAGES: Record<string, string> = {
  'auth/invalid-email': 'That email address is not valid.',
  'auth/missing-password': 'Enter your password.',
  'auth/user-disabled': 'This account has been disabled.',
  // Firebase deliberately reports "no such user" and "wrong password" with the
  // same code, so the UI cannot leak which emails have accounts.
  'auth/user-not-found': 'The email or password is incorrect.',
  'auth/wrong-password': 'The email or password is incorrect.',
  'auth/invalid-credential': 'The email or password is incorrect.',
  'auth/email-already-in-use': 'An account already exists for that email address. Try signing in instead.',
  'auth/weak-password': 'Choose a stronger password — at least 8 characters.',
  'auth/too-many-requests': 'Too many attempts. Wait a moment and try again.',
  'auth/network-request-failed': 'Network error. Check your connection and try again.',
  'auth/operation-not-allowed': 'Email and password sign-in is not enabled for this Firebase project.',
  'auth/unauthorized-domain': 'This domain is not authorised in the Firebase project.',
  'auth/invalid-api-key': 'Firebase rejected the configured API key.',
  'auth/missing-email': 'Enter your email address.',
  'auth/requires-recent-login': 'Please sign in again to continue.',
  // Google popup specifics.
  'auth/popup-closed-by-user': 'The Google sign-in window was closed before finishing. Try again when ready.',
  'auth/cancelled-popup-request': 'Another sign-in window is already open. Finish or close it first.',
  'auth/popup-blocked': 'The browser blocked the sign-in window. Allow popups for this site and try again.',
};

/** Error carrying both a message safe to display and the original code. */
export class AuthFailure extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'AuthFailure';
    this.code = code;
  }
}

/**
 * Converts anything thrown by the Firebase SDK into an {@link AuthFailure}.
 *
 * Never includes the original error message: that can contain the API key or
 * other configuration detail, and it is not useful to the person signing in.
 */
export function toAuthFailure(error: unknown): AuthFailure {
  const code =
    typeof error === 'object' && error !== null && 'code' in error && typeof (error as { code: unknown }).code === 'string'
      ? (error as { code: string }).code
      : 'auth/unknown';

  const friendly = FRIENDLY_MESSAGES[code];
  if (friendly) {
    return new AuthFailure(code, friendly);
  }
  return new AuthFailure(code, `Sign-in failed. Please try again. (${code})`);
}

/**
 * Subscribes to Firebase authentication state. Returns an unsubscribe function.
 * `onAuthStateChanged` fires once with `null` when nobody is signed in, so
 * callers must not treat that first call as "just signed out".
 */
export function subscribeToAuthState(
  callback: (user: User | null) => void,
  onError?: (failure: AuthFailure) => void,
): () => void {
  return onAuthStateChanged(
    getFirebaseAuth(),
    callback,
    (error) => {
      // Fires when the persisted session cannot be restored; treating it as
      // signed-out is correct, but the caller should still surface a notice.
      callback(null);
      onError?.(toAuthFailure(error));
    },
  );
}

/** Signs in with email and password. */
export async function signInWithEmail(email: string, password: string): Promise<User> {
  try {
    const credential = await signInWithEmailAndPassword(getFirebaseAuth(), email.trim(), password);
    return credential.user;
  } catch (error) {
    throw toAuthFailure(error);
  }
}

/**
 * Signs in with Google through Firebase's own popup flow.
 *
 * The resulting identity is verified at the provider (Google-owned addresses
 * carry email_verified in the token Firebase issues), so no separate
 * verification email is ever sent for it. Creating the application account is
 * still the backend's job, via the same session exchange email/password uses.
 */
export async function signInWithGoogle(): Promise<User> {
  try {
    const provider = new GoogleAuthProvider();
    provider.setCustomParameters({ prompt: 'select_account' });
    const credential = await signInWithPopup(getFirebaseAuth(), provider);
    return credential.user;
  } catch (error) {
    throw toAuthFailure(error);
  }
}

/**
 * Asks Firebase to send its standard verification email for a just-created
 * account. No token, code or link is generated, stored or transported by this
 * application — the whole flow (send, host, verify) lives inside Firebase, and
 * `reload` below is how the app observes its result.
 */
export async function requestEmailVerification(user: User): Promise<void> {
  try {
    await sendEmailVerification(user);
  } catch (error) {
    throw toAuthFailure(error);
  }
}

/**
 * Refreshes the user's server-side state from Firebase, so a verification
 * link followed moments ago is reflected without waiting for the SDK's own
 * token refresh. Returns the fresh user; callers decide what emailVerified
 * means for them.
 */
export async function refreshFirebaseUser(user: User): Promise<User> {
  try {
    await reload(user);
    return user;
  } catch (error) {
    // A stale session mid-check (signed out in another tab, token revoked)
    // surfaces as a normal retryable failure rather than a crash.
    throw toAuthFailure(error);
  }
}

/**
 * Creates a Firebase account and sets its display name.
 *
 * Only the Firebase half: creating the corresponding application account is the
 * caller's job, because that is where the registration invite code is enforced
 * by the backend. If the backend refuses, the caller must sign the Firebase user
 * back out (see AuthContext.signUp) so no orphan credential is left behind.
 */
export async function createFirebaseAccount(
  fullName: string,
  email: string,
  password: string,
): Promise<User> {
  try {
    const credential = await createUserWithEmailAndPassword(getFirebaseAuth(), email.trim(), password);
    const name = fullName.trim();
    if (name !== '') {
      // Best-effort: a missing display name is cosmetic, and the backend
      // derives a fallback from the email address anyway.
      await updateProfile(credential.user, { displayName: name }).catch(() => undefined);
    }
    return credential.user;
  } catch (error) {
    throw toAuthFailure(error);
  }
}

/**
 * Sends Firebase's own password-reset email.
 *
 * No reset token is generated, stored or transported by this application — the
 * whole flow lives inside Firebase, so there is no custom token system to get
 * wrong and no secret in this codebase.
 */
export async function sendPasswordReset(email: string): Promise<void> {
  try {
    await sendPasswordResetEmail(getFirebaseAuth(), email.trim());
  } catch (error) {
    throw toAuthFailure(error);
  }
}

/** Signs out of Firebase. Never throws — signing out must not be blockable. */
export async function signOutOfFirebase(): Promise<void> {
  try {
    await signOut(getFirebaseAuth());
  } catch {
    // Best-effort: local state is cleared by the caller regardless.
  }
}

/** A fresh ID token for a signed-in Firebase user. */
export async function idTokenFor(user: User): Promise<string> {
  return user.getIdToken();
}
