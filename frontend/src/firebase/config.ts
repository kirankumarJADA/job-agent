/**
 * Firebase web configuration, read from Vite environment variables.
 *
 * Everything here is a *public* identifier: the Firebase web API key and app id
 * are shipped to the browser by design and are not secrets. Authorization is
 * enforced by Firebase Security Rules and by the backend verifying ID tokens —
 * never by hiding these values. The genuinely secret material is the service
 * account used by the backend, which must never appear in this project.
 *
 * No credential is ever hard-coded. When configuration is absent the helpers
 * below report precisely which variables are missing so a deployment fails
 * visibly and actionably instead of throwing an opaque SDK error at first
 * sign-in — the behaviour required by "report missing configuration rather than
 * crashing mysteriously".
 */

export interface FirebaseWebConfig {
  apiKey: string;
  authDomain: string;
  projectId: string;
  storageBucket: string;
  messagingSenderId: string;
  appId: string;
}

export interface FirebaseConfiguration {
  /** Present only when `isConfigured`. */
  config: FirebaseWebConfig | null;
  /** Names of the VITE_ variables that still need to be set. */
  missingKeys: string[];
  isConfigured: boolean;
}

interface RequiredKey {
  /** Key in the Firebase config object handed to `initializeApp`. */
  field: keyof FirebaseWebConfig;
  /** Environment variable an operator has to set. */
  envVar: string;
}

/** Order matches the Firebase console's own project-settings listing. */
export const REQUIRED_FIREBASE_KEYS: readonly RequiredKey[] = [
  { field: 'apiKey', envVar: 'VITE_FIREBASE_API_KEY' },
  { field: 'authDomain', envVar: 'VITE_FIREBASE_AUTH_DOMAIN' },
  { field: 'projectId', envVar: 'VITE_FIREBASE_PROJECT_ID' },
  { field: 'storageBucket', envVar: 'VITE_FIREBASE_STORAGE_BUCKET' },
  { field: 'messagingSenderId', envVar: 'VITE_FIREBASE_MESSAGING_SENDER_ID' },
  { field: 'appId', envVar: 'VITE_FIREBASE_APP_ID' },
];

/**
 * Raised when a Firebase operation is attempted before configuration is
 * supplied. Carries the missing variable names so the UI can list them.
 */
export class MissingFirebaseConfigurationError extends Error {
  readonly missingKeys: string[];

  constructor(missingKeys: string[]) {
    super(
      `Firebase Authentication is not configured. Missing: ${missingKeys.join(', ')}`,
    );
    this.name = 'MissingFirebaseConfigurationError';
    this.missingKeys = missingKeys;
  }
}

function readValue(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key];
  if (typeof value !== 'string') {
    return undefined;
  }
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

/**
 * Resolves configuration from an environment-like object.
 *
 * The source is injectable so this can be tested exhaustively without mutating
 * the real `import.meta.env`, and so the same logic serves the Vite dev server,
 * a production build and the test runner alike.
 */
export function readFirebaseConfiguration(
  source: Record<string, unknown> = import.meta.env as unknown as Record<string, unknown>,
): FirebaseConfiguration {
  const missingKeys: string[] = [];
  const config: Partial<FirebaseWebConfig> = {};

  for (const { field, envVar } of REQUIRED_FIREBASE_KEYS) {
    const value = readValue(source, envVar);
    if (value === undefined) {
      missingKeys.push(envVar);
    } else {
      config[field] = value;
    }
  }

  if (missingKeys.length > 0) {
    // Any partial configuration is reported whole rather than half-applied:
    // initializing the SDK with some values missing produces a failure much
    // further from the actual problem.
    return { config: null, missingKeys, isConfigured: false };
  }

  return { config: config as FirebaseWebConfig, missingKeys: [], isConfigured: true };
}

/** Configuration for this build. Resolved once at module load. */
export const firebaseConfiguration: FirebaseConfiguration = readFirebaseConfiguration();

/** Convenience flag for rendering "not configured" states without try/catch. */
export const isFirebaseConfigured: boolean = firebaseConfiguration.isConfigured;

/**
 * The configuration, or a throw naming what is missing. Called by the Firebase
 * client on first use rather than at import time, so a misconfigured deploy
 * still renders its (informative) pages instead of failing to boot.
 */
export function requireFirebaseConfiguration(): FirebaseWebConfig {
  if (!firebaseConfiguration.config) {
    throw new MissingFirebaseConfigurationError(firebaseConfiguration.missingKeys);
  }
  return firebaseConfiguration.config;
}
