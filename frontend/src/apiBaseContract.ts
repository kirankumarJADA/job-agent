/**
 * The backend's routes are fixed under {@code /api/v1}, and the frontend's
 * API client prepends {@code VITE_API_BASE_URL} verbatim to every relative
 * endpoint. A production deployment whose base URL omits the {@code /api/v1}
 * suffix misroutes EVERY request (this is exactly how the first production
 * deployment produced opaque CORS failures on /auth/firebase/session).
 *
 * <p>{@code assertDeployableApiBase} runs at BUILD time from vite.config.ts —
 * a loud build failure is the only protection that survives a misconfigured
 * hosting environment, mirroring the local-inspection env guard.
 */
export function assertDeployableApiBase(env: Record<string, unknown>): void {
  const base = env.VITE_API_BASE_URL;
  if (typeof base !== 'string' || base.trim() === '') {
    return; // unset: the client falls back to its localhost default
  }
  const trimmed = base.trim().replace(/\/+$/, '');
  if (!trimmed.endsWith('/api/v1')) {
    throw new Error(
      `VITE_API_BASE_URL must end with /api/v1 (got "${base}"). The frontend prefixes this base to ` +
        'every relative endpoint such as /auth/firebase/session; without the suffix all requests ' +
        'miss the backend API and fail with opaque CORS errors in the browser.',
    );
  }
}
