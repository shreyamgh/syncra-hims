import { currentToken, signOut } from '../auth/session';

/** An RFC 7807 problem returned by the gateway or a service. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
    readonly fields?: Record<string, string>,
    /** The whole problem document, for extra properties (e.g. duplicate-patient candidates). */
    readonly problem?: Record<string, unknown>,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  signal?: AbortSignal;
  /** Extra request headers, e.g. If-Match for optimistic locking. */
  headers?: Record<string, string>;
  /**
   * Bearer tokens need no cookies, so cookies are omitted (neither sent nor stored). Only the
   * image-viewer session uses 'same-origin', so that its HttpOnly cookie is kept.
   */
  credentials?: RequestCredentials;
}

/** Same-origin call to the gateway with the signed-in user's bearer token. */
export async function api<T>(path: string, { method = 'GET', body, signal, headers: extra, credentials = 'omit' }: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { ...extra, Accept: 'application/json' };
  const token = currentToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
    credentials,
  });

  if (response.status === 401) {
    signOut();
    throw new ApiError(401, 'Your session has expired. Please sign in again.', 'UNAUTHENTICATED');
  }
  const text = await response.text();
  const payload = text ? safeJson(text) : undefined;
  if (!response.ok) {
    const problem = (payload ?? {}) as { detail?: string; title?: string; code?: string; fields?: Record<string, string> };
    throw new ApiError(
      response.status,
      problem.detail ?? problem.title ?? `Request failed (${response.status})`,
      problem.code,
      problem.fields,
      problem as Record<string, unknown>,
    );
  }
  return payload as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
