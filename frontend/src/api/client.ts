export const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

/**
 * Error thrown for any non-2xx API response.
 *
 * Carries the HTTP status so callers can distinguish cases that need different
 * UI — notably 401 (not signed in), 403 (refused: e.g. a registration invite
 * code was missing or wrong) and 503 (the server has no Firebase credentials).
 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(^|;\\s*)(' + name + ')=([^;]*)'));
  return match ? decodeURIComponent(match[3]) : null;
}

function generateUuid(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * Supplies the Firebase ID token attached to outgoing requests.
 *
 * Registered by AuthContext rather than imported directly, which keeps this
 * module free of any dependency on Firebase or React (and therefore free of
 * import cycles). When nothing is registered — or Firebase is unconfigured —
 * requests simply go out without an Authorization header and fall back to the
 * existing session cookie.
 */
export type IdTokenProvider = () => Promise<string | null>;

let idTokenProvider: IdTokenProvider | null = null;

export function setIdTokenProvider(provider: IdTokenProvider | null): void {
  idTokenProvider = provider;
}

export async function apiFetch<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const url = endpoint.startsWith('http') ? endpoint : `${API_BASE}${endpoint}`;
  const headers = new Headers(options.headers || {});

  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const method = (options.method || 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD') {
    if (!headers.has('X-Request-ID')) {
      headers.set('X-Request-ID', generateUuid());
    }
    const csrfToken = getCookie('XSRF-TOKEN');
    if (csrfToken && !headers.has('X-XSRF-TOKEN')) {
      headers.set('X-XSRF-TOKEN', csrfToken);
    }
  }

  // A verified Firebase ID token authenticates the request directly. The
  // backend derives the caller's identity from this token only — nothing in a
  // request body is ever treated as an identifier.
  if (!headers.has('Authorization') && idTokenProvider) {
    try {
      const token = await idTokenProvider();
      if (token) {
        headers.set('Authorization', `Bearer ${token}`);
      }
    } catch {
      // Token retrieval is best-effort: the session cookie may still be valid,
      // and failing here would turn a recoverable state into a hard error.
    }
  }

  const res = await fetch(url, {
    ...options,
    headers,
    credentials: 'include',
  });

  if (!res.ok) {
    let errorDetail = `HTTP ${res.status}`;
    try {
      const errJson = await res.json();
      errorDetail = errJson.detail || errJson.title || errJson.message || JSON.stringify(errJson);
    } catch {
      const text = await res.text().catch(() => '');
      if (text) errorDetail = text;
    }
    throw new ApiError(res.status, errorDetail);
  }

  if (res.status === 204) {
    return null as unknown as T;
  }

  return res.json();
}

/** Public registration policy, used to label the sign-up form honestly. */
export interface RegistrationPolicy {
  inviteCodeRequired: boolean;
  registrationAvailable: boolean;
}

export function fetchRegistrationPolicy(): Promise<RegistrationPolicy> {
  return apiFetch<RegistrationPolicy>('/auth/registration-policy');
}
