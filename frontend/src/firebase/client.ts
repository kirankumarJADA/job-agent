import { getApps, initializeApp, type FirebaseApp } from 'firebase/app';
import { getAuth, type Auth, type User } from 'firebase/auth';

import { requireFirebaseConfiguration } from './config';

/**
 * Lazily-created Firebase singletons.
 *
 * Initialisation happens on first use rather than at module import, for two
 * reasons: a deployment without configuration still renders its pages (and can
 * explain what is missing) instead of dying during module evaluation, and the
 * test runner never needs live credentials to import the auth modules.
 */

const APP_NAME = 'robin-job-agent';

let cachedApp: FirebaseApp | null = null;
let cachedAuth: Auth | null = null;

/**
 * @throws MissingFirebaseConfigurationError when the build has no Firebase
 *         configuration — the caller is expected to have checked
 *         `isFirebaseConfigured` and rendered an explanation instead.
 */
export function getFirebaseApp(): FirebaseApp {
  if (cachedApp) {
    return cachedApp;
  }

  const existing = getApps().find((app) => app.name === APP_NAME);
  if (existing) {
    cachedApp = existing;
    return cachedApp;
  }

  cachedApp = initializeApp(requireFirebaseConfiguration(), APP_NAME);
  return cachedApp;
}

/** The Firebase Auth instance for this app. */
export function getFirebaseAuth(): Auth {
  if (cachedAuth) {
    return cachedAuth;
  }
  cachedAuth = getAuth(getFirebaseApp());
  return cachedAuth;
}

/**
 * The currently signed-in Firebase user, or null.
 *
 * Returns null when Firebase is unconfigured rather than throwing, so callers
 * can use it purely as "is there a token to attach" without guarding every call
 * site.
 */
export function currentFirebaseUser(): User | null {
  try {
    return getFirebaseAuth().currentUser;
  } catch {
    return null;
  }
}

/**
 * A fresh ID token for the current Firebase user, or null when signed out.
 *
 * The SDK caches tokens and refreshes them automatically, so this is cheap to
 * call per request. `forceRefresh` is left off deliberately: forcing it on every
 * call would issue a network round trip per API request.
 */
export async function currentIdToken(): Promise<string | null> {
  const user = currentFirebaseUser();
  if (!user) {
    return null;
  }
  return user.getIdToken();
}
