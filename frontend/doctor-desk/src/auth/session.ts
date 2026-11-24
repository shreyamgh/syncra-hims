/**
 * Sign-in state. The IAM service issues a short-lived access token (renewed from an HttpOnly
 * refresh cookie while the session lasts); in development a token from scripts/dev-token.py
 * may be pasted instead. The token lives in sessionStorage, so it is gone when the tab closes.
 */
export interface Session {
  token: string;
  subject: string;
  roles: string[];
  expiresAt: number; // epoch ms
}

const KEY = 'syncra.session';
const listeners = new Set<(session: Session | null) => void>();
let current: Session | null = null;

export function parseToken(raw: string, now = Date.now()): Session | null {
  const token = raw.trim();
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const json = atob(parts[1]!.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(parts[1]!.length / 4) * 4, '='));
    const claims = JSON.parse(json) as { sub?: unknown; roles?: unknown; exp?: unknown };
    if (typeof claims.sub !== 'string' || typeof claims.exp !== 'number') return null;
    const expiresAt = claims.exp * 1000;
    if (expiresAt <= now) return null;
    const roles = Array.isArray(claims.roles) ? claims.roles.filter((r): r is string => typeof r === 'string') : [];
    return { token, subject: claims.sub, roles, expiresAt };
  } catch {
    return null;
  }
}

export function loadSession(): Session | null {
  if (current) return current;
  try {
    const stored = sessionStorage.getItem(KEY);
    current = stored ? parseToken(stored) : null;
  } catch {
    current = null;
  }
  return current;
}

export function signIn(session: Session): void {
  current = session;
  try {
    sessionStorage.setItem(KEY, session.token);
  } catch {
    // Storage unavailable (private mode): the session lasts for this page only.
  }
  listeners.forEach((l) => l(session));
}

export function signOut(): void {
  current = null;
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // ignore
  }
  listeners.forEach((l) => l(null));
}

export function currentToken(): string | null {
  const session = loadSession();
  if (session && session.expiresAt <= Date.now()) {
    signOut();
    return null;
  }
  return session?.token ?? null;
}

export function onSessionChange(listener: (session: Session | null) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
