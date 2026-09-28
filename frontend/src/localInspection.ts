/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LOCAL INSPECTION MODE — LOCAL DEVELOPMENT ONLY. NOT A SECURITY FEATURE.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Why it exists: Firebase Authentication refuses to sign anyone in from an
 * origin that is not authorised on the Firebase project (a Vercel preview
 * domain, for instance), which makes it impossible to look at the rest of the
 * application locally. This mode lets a developer on their own machine enter
 * the authenticated application without involving Firebase at all.
 *
 * What it does: signs in through the ORDINARY local account endpoint,
 * `POST /api/v1/auth/login`, as the local development account seeded by Flyway
 * (V002/V003) — which exists in a local database only: migration V024
 * neutralizes its published password in every environment whose profile does
 * not say "local", so production has no such account to sign in with. That is a
 * REAL backend session: every endpoint still
 * authenticates the caller from the session cookie, and every authorization
 * rule still applies unchanged. Nothing is faked — no Firebase token is
 * invented, no account is created, no schema is touched, and the production
 * Firebase flow is not modified anywhere.
 *
 * Why it cannot reach production — four independent barriers:
 *
 *   1. Every call site guards on `import.meta.env.DEV`, which Vite replaces
 *      with the literal `false` in a production build, so the whole branch
 *      (and anything only it references) is removed from the bundle.
 *   2. The API base URL must be a loopback address, so the mode refuses to run
 *      against a deployed backend even from a local development server.
 *   3. The credentials come from the developer's own git-ignored
 *      `frontend/.env.local`. Nothing is defaulted or hard-coded here, and the
 *      password is never logged or rendered. (It is a local database fixture:
 *      migration V024 removes it from any deployment that is not local.)
 *   4. `vite build` refuses to run at all when any of the three
 *      VITE_LOCAL_INSPECTION_* variables is set (see vite.config.ts), so a
 *      built — i.e. deployable — bundle can never contain the mode or its
 *      credentials.
 *
 * This module deliberately has no top-level `import.meta` access, so the build
 * guard below can be imported by `vite.config.ts`, which runs in Node.
 *
 * REMOVAL: delete this file, its call sites (context/AuthContext.tsx and
 * components/LocalInspectionBanner.tsx), the guard block in vite.config.ts, the
 * VITE_LOCAL_INSPECTION_* block in frontend/.env.example, and this module's
 * tests.
 */

/** The flag an operator sets in `frontend/.env.local` to turn this on. */
export const LOCAL_INSPECTION_MODE_ENV = 'VITE_LOCAL_INSPECTION_MODE';
/** Email of the seeded local development account. Not a secret. */
export const LOCAL_INSPECTION_EMAIL_ENV = 'VITE_LOCAL_INSPECTION_EMAIL';
/** That account's password. Supplied locally only; never committed. */
export const LOCAL_INSPECTION_PASSWORD_ENV = 'VITE_LOCAL_INSPECTION_PASSWORD';

/**
 * Console/banner marker. Rendered only inside the development-only branches, so
 * a production bundle must not contain it — asserted by
 * productionBundle.test.ts.
 */
export const LOCAL_INSPECTION_MARKER = 'ROBIN LOCAL INSPECTION MODE';

/** The existing local account endpoint. No new backend route is introduced. */
export const LOCAL_INSPECTION_LOGIN_ENDPOINT = '/auth/login';

/**
 * Values accepted as "yes" for the flag, matched case-insensitively. Anything
 * else — including `false`, `no` and `0` — leaves the mode off.
 */
const ENABLING_VALUES = new Set(['true', '1', 'yes', 'on', 'enabled']);

/** The environment values the decision is made from. All injectable for tests. */
export interface LocalInspectionEnv {
  /** `import.meta.env.DEV` — true only in a Vite development build/dev server. */
  dev: boolean;
  /** `import.meta.env.PROD` — true only in a production build. */
  prod: boolean;
  /** `VITE_LOCAL_INSPECTION_MODE`. */
  mode: unknown;
  /** `VITE_LOCAL_INSPECTION_EMAIL`. */
  email: unknown;
  /** `VITE_LOCAL_INSPECTION_PASSWORD`. */
  password: unknown;
  /** The API base URL this build will actually call. */
  apiBase: unknown;
}

export interface LocalInspectionMode {
  enabled: boolean;
  /** Why the mode is off (or `'enabled'`). Never contains a credential. */
  reason: string;
  /** The local development account's email, only when enabled. */
  email: string | null;
  /** Its password, only when enabled. Never logged, never rendered. */
  password: string | null;
}

const OFF_REASON_PREFIX = 'local inspection mode is off: ';

function off(reason: string): LocalInspectionMode {
  return { enabled: false, reason: OFF_REASON_PREFIX + reason, email: null, password: null };
}

/** A trimmed, non-empty string; null for anything else (including non-strings). */
function readText(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

/**
 * True only for genuine loopback API bases (localhost, *.localhost,
 * 127.0.0.0/8, ::1) — the same definition the backend applies to CORS origins
 * in SecurityConfig.isLoopbackOrigin. `https://robin.example.com` and hosts that
 * merely contain the word "localhost" (e.g. `mylocalhost.example.com`) are not
 * loopback.
 */
export function isLoopbackApiBase(apiBase: string | null): boolean {
  if (apiBase === null) {
    return false;
  }
  let host: string;
  try {
    host = new URL(apiBase).hostname;
  } catch {
    // Not a parseable absolute URL — refuse rather than guess.
    return false;
  }
  const normalised = host.toLowerCase().replace(/^\[/, '').replace(/\]$/, '');
  if (
    normalised === 'localhost' ||
    normalised.endsWith('.localhost') ||
    normalised === '::1' ||
    normalised === '0:0:0:0:0:0:0:1'
  ) {
    return true;
  }
  const parts = normalised.split('.');
  return (
    parts.length === 4 &&
    parts[0] === '127' &&
    parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255)
  );
}

/**
 * The single decision point. Fails closed: anything unexpected — a production
 * build, a missing flag, a deployed API, missing credentials — leaves the mode
 * off, and the caller then behaves exactly like an ordinary signed-out visitor.
 */
export function resolveLocalInspectionMode(env: LocalInspectionEnv): LocalInspectionMode {
  const flag = readText(env.mode);
  if (flag === null || !ENABLING_VALUES.has(flag.toLowerCase())) {
    return off(`${LOCAL_INSPECTION_MODE_ENV} is not set to an enabling value`);
  }

  // Checked before the credential requirements so a production build reports
  // why it is refusing rather than what it would have needed.
  if (env.dev !== true || env.prod === true) {
    return off('this is not a local development build');
  }

  const apiBase = readText(env.apiBase);
  if (!isLoopbackApiBase(apiBase)) {
    return off('the API base URL is not a loopback address');
  }

  const email = readText(env.email);
  const password = readText(env.password);
  if (email === null || password === null) {
    return off(
      `${LOCAL_INSPECTION_EMAIL_ENV} and ${LOCAL_INSPECTION_PASSWORD_ENV} must both be set in frontend/.env.local`,
    );
  }

  return { enabled: true, reason: 'enabled', email, password };
}

/**
 * The mode for this build, read from Vite's environment. The property accesses
 * below are static on purpose: Vite substitutes each one with its literal value
 * at build time.
 */
export function currentLocalInspectionMode(apiBase: string): LocalInspectionMode {
  return resolveLocalInspectionMode({
    dev: import.meta.env.DEV,
    prod: import.meta.env.PROD,
    mode: import.meta.env.VITE_LOCAL_INSPECTION_MODE,
    email: import.meta.env.VITE_LOCAL_INSPECTION_EMAIL,
    password: import.meta.env.VITE_LOCAL_INSPECTION_PASSWORD,
    apiBase,
  });
}

export type LocalInspectionSignInResult<T> =
  /** The mode is off — no request was made. */
  | { status: 'disabled'; reason: string }
  /** The local account was refused; the caller stays signed out. */
  | { status: 'failed' }
  /** A real application session was established. */
  | { status: 'signed-in'; response: T };

/**
 * Establishes the application session for the seeded local development account.
 *
 * `post` is injected rather than imported so the exact request can be asserted
 * without a network, and so this module stays free of the API client (which
 * reads `import.meta.env` at import time and therefore must not be loaded by
 * vite.config.ts). A refusal is reported, never worked around: a mode that could
 * mint a session the backend did not issue would be worse than no mode.
 */
export async function establishLocalInspectionSession<T>(
  mode: LocalInspectionMode,
  post: (endpoint: string, body: { email: string; password: string }) => Promise<T>,
): Promise<LocalInspectionSignInResult<T>> {
  if (!mode.enabled || mode.email === null || mode.password === null) {
    return { status: 'disabled', reason: mode.reason };
  }
  try {
    const response = await post(LOCAL_INSPECTION_LOGIN_ENDPOINT, {
      email: mode.email,
      password: mode.password,
    });
    return { status: 'signed-in', response };
  } catch {
    // The failure detail is deliberately dropped: nothing here may ever echo the
    // credential back, and the caller logs a credential-free warning.
    return { status: 'failed' };
  }
}

/** True when a value is set to something a build must never contain. */
function isSetValue(value: unknown): boolean {
  return readText(value) !== null;
}

/**
 * Names of the inspection variables that are set to a value a *build* must
 * never contain. Only the flag honours "false means off": any non-blank
 * credential value is a violation, because a production build would bake it into
 * the JavaScript it serves.
 */
export function localInspectionEnvViolations(env: Record<string, unknown>): string[] {
  const violations: string[] = [];
  const flag = readText(env[LOCAL_INSPECTION_MODE_ENV]);
  if (flag !== null && ENABLING_VALUES.has(flag.toLowerCase())) {
    violations.push(LOCAL_INSPECTION_MODE_ENV);
  }
  if (isSetValue(env[LOCAL_INSPECTION_EMAIL_ENV])) {
    violations.push(LOCAL_INSPECTION_EMAIL_ENV);
  }
  if (isSetValue(env[LOCAL_INSPECTION_PASSWORD_ENV])) {
    violations.push(LOCAL_INSPECTION_PASSWORD_ENV);
  }
  return violations;
}

/**
 * Called by `vite.config.ts` for every `vite build`. Failing the build is the
 * only guarantee that survives a misconfigured CI or hosting environment: it
 * means "set these in Vercel by mistake" cannot result in a deployed bundle that
 * ships the bypass or a development credential.
 *
 * The message names the offending variables but never their values.
 */
export function assertNoLocalInspectionEnv(env: Record<string, unknown>): void {
  const violations = localInspectionEnvViolations(env);
  if (violations.length === 0) {
    return;
  }
  throw new Error(
    `Refusing to build: ${violations.join(', ')} ${violations.length === 1 ? 'is' : 'are'} set. ` +
      'Local inspection mode is a local-development-only convenience run by `npm run dev`, which ' +
      'reads these from frontend/.env.local. A build produces a deployable bundle, so shipping this ' +
      `flag or its credentials is never intended. Remove ${violations.join(', ')} from the build ` +
      'environment and use `npm run dev` instead.',
  );
}
