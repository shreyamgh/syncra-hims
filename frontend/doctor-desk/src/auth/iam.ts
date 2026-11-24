// Calls to the IAM service. These carry no bearer token (they are how one is obtained); the
// refresh session lives in an HttpOnly cookie, so they send same-origin credentials.
import { ApiError } from '../api/http';
import { type Session, parseToken, signIn } from './session';

export interface SignInConfig {
  developmentSignIn: boolean;
  minPasswordLength: number;
}

interface SessionResponse {
  accessToken: string;
  expiresAt: string;
  sessionEnds: string;
  user: { username: string; name: string; roles: string[] };
}

async function call<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin',
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    const problem = (payload ?? {}) as { detail?: string; code?: string };
    throw new ApiError(response.status, problem.detail ?? `Request failed (${response.status})`, problem.code, undefined,
      payload as Record<string, unknown> | undefined);
  }
  return payload as T;
}

function start(response: SessionResponse): Session {
  const session = parseToken(response.accessToken);
  if (!session) throw new Error('The sign-in service returned an unusable token');
  signIn(session);
  return session;
}

export const iam = {
  config: () => call<SignInConfig>('/api/v1/auth/config'),
  /** Signs in; a temporary password answers 403 PASSWORD_CHANGE_REQUIRED. */
  login: async (username: string, password: string) => start(await call<SessionResponse>('/api/v1/auth/login', { username, password })),
  changePassword: (username: string, currentPassword: string, newPassword: string) =>
    call<void>('/api/v1/auth/password', { username, currentPassword, newPassword }),
  /** Renews the access token from the refresh cookie (rotating it). */
  refresh: async () => start(await call<SessionResponse>('/api/v1/auth/refresh', {})),
  logout: () => call<void>('/api/v1/auth/logout', {}),
};

/** Milliseconds before expiry at which the desk renews its token. */
export const RENEW_BEFORE_MS = 2 * 60_000;
