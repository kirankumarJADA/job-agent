import type { ApplicationSummary } from '../types';

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

/** Result of an authenticated binary download whose bytes were checked against the server digest. */
export interface VerifiedDownload {
  blob: Blob;
  filename: string;
  sha256: string;
}

async function sha256Hex(buffer: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function filenameFrom(disposition: string | null, fallback: string): string {
  if (!disposition) return fallback;
  const match = disposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i);
  return match ? decodeURIComponent(match[1]) : fallback;
}

/**
 * Downloads a protected file through the same authenticated path as apiFetch
 * (Firebase bearer token + session cookie). A plain <a href> would send no
 * Authorization header, so Firebase-only sessions would get 401.
 *
 * The bytes are hashed in the browser and compared with the server's
 * X-Content-SHA256 header; a missing or mismatching digest is an error, so
 * the caller never saves a file that is not the recorded artifact.
 */
export async function apiDownload(endpoint: string, fallbackName: string): Promise<VerifiedDownload> {
  const url = endpoint.startsWith('http') ? endpoint : `${API_BASE}${endpoint}`;
  const headers = new Headers();
  if (idTokenProvider) {
    try {
      const token = await idTokenProvider();
      if (token) headers.set('Authorization', `Bearer ${token}`);
    } catch {
      // Fall back to the session cookie, exactly like apiFetch.
    }
  }
  const res = await fetch(url, { method: 'GET', headers, credentials: 'include' });
  if (!res.ok) {
    let detail = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      detail = body.detail || body.error || body.title || detail;
    } catch {
      // keep the status text
    }
    throw new ApiError(res.status, detail);
  }
  const expected = res.headers.get('X-Content-SHA256');
  const buffer = await res.arrayBuffer();
  if (!expected) {
    throw new ApiError(500, 'The server did not provide a checksum for this file, so it was not saved.');
  }
  const actual = await sha256Hex(buffer);
  if (actual !== expected.toLowerCase()) {
    throw new ApiError(500, 'The downloaded file does not match its recorded checksum, so it was not saved.');
  }
  const type = res.headers.get('Content-Type') || 'application/octet-stream';
  return {
    blob: new Blob([buffer], { type }),
    filename: filenameFrom(res.headers.get('Content-Disposition'), fallbackName),
    sha256: actual,
  };
}

/** Hands a verified download to the browser as a file save. */
export function saveDownload(download: VerifiedDownload): void {
  const objectUrl = URL.createObjectURL(download.blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = download.filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
}

/** Public registration policy, used to label the sign-up form honestly. */
export interface RegistrationPolicy {
  inviteCodeRequired: boolean;
  registrationAvailable: boolean;
}

export interface ApplicationListResponse { items: ApplicationSummary[] }

export function fetchApplications(): Promise<ApplicationListResponse> {
  return apiFetch<ApplicationListResponse>('/applications');
}

export function fetchRegistrationPolicy(): Promise<RegistrationPolicy> {
  return apiFetch<RegistrationPolicy>('/auth/registration-policy');
}
