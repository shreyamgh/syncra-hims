import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { loadSession, signIn, signOut } from '../auth/session';
import { AdminDesk } from './AdminDesk';
import { LoginGate } from './LoginGate';

const encode = (claims: object) => `e30.${btoa(JSON.stringify(claims)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')}.sig`;
const tokenFor = (sub: string, roles: string[], secondsLeft = 1800) =>
  encode({ sub, roles, exp: Math.floor(Date.now() / 1000) + secondsLeft });

interface Call { method: string; url: string; body?: Record<string, unknown>; credentials?: RequestCredentials }
let calls: Call[];
let passwordChangeRequired: boolean;
let devSignIn: boolean;

function mockIam() {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method, url, body, credentials: init?.credentials });
    const json = (payload: unknown, status = 200) => new Response(payload === undefined ? null : JSON.stringify(payload), { status });
    const session = (username: string, roles: string[]) => json({
      accessToken: tokenFor(username, roles), expiresAt: new Date(Date.now() + 1_800_000).toISOString(),
      sessionEnds: new Date(Date.now() + 43_200_000).toISOString(), user: { username, name: 'Dr Asha Menon', roles },
    });
    if (url === '/api/v1/auth/config') return json({ developmentSignIn: devSignIn, minPasswordLength: 10 });
    if (url === '/api/v1/auth/login') {
      if (body.password === 'wrong') return json({ code: 'WRONG_CREDENTIALS', detail: 'Wrong username or password' }, 401);
      if (passwordChangeRequired) return json({ code: 'PASSWORD_CHANGE_REQUIRED', detail: 'Choose a new password' }, 403);
      return session(body.username, ['DOCTOR']);
    }
    if (url === '/api/v1/auth/password') {
      passwordChangeRequired = false;
      return json(undefined, 204);
    }
    if (url === '/api/v1/auth/refresh') return session('dr.asha', ['DOCTOR']);
    if (url === '/api/v1/iam/users') {
      if (method === 'POST') {
        return json({ account: { id: 'u2', username: body.username, displayName: 'Asha Kulkarni', roles: body.roles, status: 'ACTIVE', locked: false, mustChangePassword: true, createdAt: '' }, temporaryPassword: 'Kp7mQx2RtW9z' }, 201);
      }
      return json([{ id: 'u1', username: 'dev.admin', displayName: 'Development Administrator', roles: ['ADMINISTRATOR'], status: 'ACTIVE', locked: false, mustChangePassword: false, createdAt: '' },
        { id: 'u3', username: 'nurse.sunita', displayName: 'Sunita Pawar', roles: ['NURSE'], status: 'ACTIVE', locked: true, lockedUntil: '2026-11-21T06:00:00Z', mustChangePassword: false, createdAt: '' }]);
    }
    if (url === '/api/v1/iam/staff') return json([{ id: 's9', fullName: 'Asha Kulkarni', staffRole: 'RADIOGRAPHER', department: 'Radiology', hasAccount: false }]);
    if (url === '/api/v1/iam/users/u3/reset-password') return json({ account: { id: 'u3', username: 'nurse.sunita', displayName: 'Sunita Pawar', roles: ['NURSE'], status: 'ACTIVE', locked: false, mustChangePassword: true, createdAt: '' }, temporaryPassword: 'Zq4nHt8Ve3Lc' });
    return json({ detail: `unexpected ${method} ${url}` }, 404);
  }));
}

beforeEach(() => {
  passwordChangeRequired = false;
  devSignIn = false;
  mockIam();
});
afterEach(() => {
  signOut();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Sign-in', () => {
  it('signs in with username and password, keeping the refresh cookie', async () => {
    const user = userEvent.setup();
    render(<LoginGate />);
    const form = screen.getByRole('form', { name: 'Sign in' });
    await user.type(within(form).getByRole('textbox', { name: 'Username' }), 'dr.asha');
    await user.type(within(form).getByLabelText('Password'), 'wrong');
    await user.click(within(form).getByRole('button', { name: 'Sign in' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Wrong username or password');
    expect(loadSession()).toBeNull();

    await user.clear(within(form).getByLabelText('Password'));
    await user.type(within(form).getByLabelText('Password'), 'monsoon tea at the ward');
    await user.click(within(form).getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(loadSession()?.subject).toBe('dr.asha'));
    const login = calls.filter((c) => c.url === '/api/v1/auth/login').at(-1)!;
    expect(login.body).toEqual({ username: 'dr.asha', password: 'monsoon tea at the ward' });
    expect(login.credentials).toBe('same-origin');
    // Development tokens are offered only when the server says so.
    expect(screen.queryByRole('button', { name: /Development/ })).not.toBeInTheDocument();
  });

  it('makes a temporary password be replaced before the desk opens', async () => {
    passwordChangeRequired = true;
    const user = userEvent.setup();
    render(<LoginGate />);
    await user.type(screen.getByRole('textbox', { name: 'Username' }), 'nurse.new');
    await user.type(screen.getByLabelText('Password'), 'Kp7mQx2RtW9z');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    const change = await screen.findByRole('form', { name: 'Choose a new password' });
    expect(loadSession()).toBeNull();
    await user.type(within(change).getByLabelText('New password'), 'ward rounds at seven');
    await user.type(within(change).getByLabelText('New password again'), 'ward rounds at six');
    await user.click(within(change).getByRole('button', { name: 'Save and sign in' }));
    expect(within(change).getByRole('alert')).toHaveTextContent('The two new passwords differ');

    await user.clear(within(change).getByLabelText('New password again'));
    await user.type(within(change).getByLabelText('New password again'), 'ward rounds at seven');
    await user.click(within(change).getByRole('button', { name: 'Save and sign in' }));
    await waitFor(() => expect(loadSession()?.subject).toBe('nurse.new'));
    expect(calls.find((c) => c.url === '/api/v1/auth/password')!.body)
      .toEqual({ username: 'nurse.new', currentPassword: 'Kp7mQx2RtW9z', newPassword: 'ward rounds at seven' });
  });

  it('offers development tokens when the server allows them', async () => {
    devSignIn = true;
    const user = userEvent.setup();
    render(<LoginGate />);
    await user.click(await screen.findByRole('button', { name: /Development: sign in with a token/ }));
    await user.type(screen.getByRole('textbox', { name: 'Access token' }), tokenFor('dev.lab', ['LAB_TECHNICIAN']));
    await user.click(screen.getByRole('button', { name: 'Use token' }));
    expect(loadSession()?.subject).toBe('dev.lab');
  });

  it('renews the access token before it expires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    signIn({ token: tokenFor('dr.asha', ['DOCTOR'], 150), subject: 'dr.asha', roles: ['DOCTOR'], expiresAt: Date.now() + 150_000 });
    render(<App />);
    expect(calls.some((c) => c.url === '/api/v1/auth/refresh')).toBe(false);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000);
    });
    await waitFor(() => expect(calls.some((c) => c.url === '/api/v1/auth/refresh' && c.method === 'POST')).toBe(true));
    await waitFor(() => expect(loadSession()!.expiresAt).toBeGreaterThan(Date.now() + 1_000_000));
  });
});

describe('Administration', () => {
  const ADMIN = { token: 't', subject: 'dev.admin', roles: ['ADMINISTRATOR'], expiresAt: Date.now() + 3_600_000 };

  it('creates an account for a staff member and shows its temporary password once', async () => {
    const user = userEvent.setup();
    render(<AdminDesk session={ADMIN} />);
    expect(await screen.findByRole('row', { name: 'Sunita Pawar' })).toHaveTextContent('Locked');
    // Administrators cannot disable themselves.
    expect(within(screen.getByRole('row', { name: 'Development Administrator' })).getByRole('button', { name: 'Disable' })).toBeDisabled();

    const form = screen.getByRole('form', { name: 'Create an account' });
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Staff member' }), 's9');
    expect(within(form).getByRole('textbox', { name: 'Username' })).toHaveValue('asha.kulkarni');
    expect(within(form).getByRole('checkbox', { name: 'radiographer' })).toBeChecked();
    await user.click(within(form).getByRole('button', { name: 'Create account' }));
    await waitFor(() => expect(calls.some((c) => c.url === '/api/v1/iam/users' && c.method === 'POST')).toBe(true));
    expect(calls.find((c) => c.url === '/api/v1/iam/users' && c.method === 'POST')!.body)
      .toEqual({ username: 'asha.kulkarni', staffId: 's9', roles: ['RADIOGRAPHER'] });
    const shown = await screen.findByRole('region', { name: 'Temporary password' });
    expect(shown).toHaveTextContent('Kp7mQx2RtW9z');
    await user.click(within(shown).getByRole('button', { name: 'Done' }));
    expect(screen.queryByText('Kp7mQx2RtW9z')).not.toBeInTheDocument();
  });

  it('resets a password after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const user = userEvent.setup();
    render(<AdminDesk session={ADMIN} />);
    await user.click(within(await screen.findByRole('row', { name: 'Sunita Pawar' })).getByRole('button', { name: 'Reset password' }));
    expect(await screen.findByRole('region', { name: 'Temporary password' })).toHaveTextContent('Zq4nHt8Ve3Lc');
  });
});
