import { type FormEvent, useEffect, useState } from 'react';
import { ApiError } from '../api/http';
import { type SignInConfig, iam } from '../auth/iam';
import { parseToken, signIn } from '../auth/session';
import { desksFor } from '../emr/diagnostics';

type Step = 'signin' | 'change';

/**
 * Staff sign-in for every desk. A temporary password (new account, reset) has to be replaced
 * before the desk opens. In development the page also accepts a pasted development token.
 */
export function LoginGate() {
  const [config, setConfig] = useState<SignInConfig | null>(null);
  const [step, setStep] = useState<Step>('signin');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [devOpen, setDevOpen] = useState(false);
  const [token, setToken] = useState('');

  useEffect(() => {
    iam.config().then(setConfig, () => setConfig({ developmentSignIn: false, minPasswordLength: 10 }));
  }, []);

  const failed = (e: unknown) => setError(e instanceof Error ? e.message : 'Sign-in failed');

  const signInWithPassword = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const session = await iam.login(username, password);
      if (desksFor(session.roles).length === 0) setError('Your roles have no desk here yet. Ask the administrator.');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'PASSWORD_CHANGE_REQUIRED') {
        setStep('change');
        setError(null);
      } else {
        failed(e);
      }
    } finally {
      setBusy(false);
    }
  };

  const changePassword = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    if (newPassword !== confirm) {
      setError('The two new passwords differ');
      return;
    }
    setBusy(true);
    try {
      await iam.changePassword(username, password, newPassword);
      await iam.login(username, newPassword);
    } catch (e) {
      failed(e);
    } finally {
      setBusy(false);
    }
  };

  const devSignIn = (event: FormEvent) => {
    event.preventDefault();
    const session = parseToken(token);
    if (!session) {
      setError('That is not a valid, unexpired token.');
      return;
    }
    if (desksFor(session.roles).length === 0) {
      setError('This token has no role with a desk.');
      return;
    }
    signIn(session);
  };

  return (
    <main className="flex h-full items-center justify-center bg-slate-50 p-4">
      <div className="w-full max-w-sm space-y-3 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div>
          <h1 className="text-base font-semibold">Syncra HIMS</h1>
          <p className="text-[12px] text-slate-500">Sign in with your hospital username and password.</p>
        </div>
        {step === 'signin' ? (
          <form onSubmit={(e) => void signInWithPassword(e)} aria-label="Sign in" className="space-y-2">
            <label className="block text-[12px] text-slate-600">Username
              <input className="field mt-0.5" autoComplete="username" autoFocus autoCapitalize="none" spellCheck={false}
                value={username} onChange={(e) => setUsername(e.target.value)} disabled={busy} />
            </label>
            <label className="block text-[12px] text-slate-600">Password
              <input className="field mt-0.5" type="password" autoComplete="current-password"
                value={password} onChange={(e) => setPassword(e.target.value)} disabled={busy} />
            </label>
            {error && <p role="alert" className="text-[12px] text-red-600">{error}</p>}
            <button type="submit" className="btn-primary w-full justify-center" disabled={busy || !username.trim() || !password}>
              Sign in
            </button>
          </form>
        ) : (
          <form onSubmit={(e) => void changePassword(e)} aria-label="Choose a new password" className="space-y-2">
            <p className="rounded bg-amber-50 px-2 py-1 text-[12px] text-amber-900">
              You signed in with a temporary password. Choose your own to continue: at least {config?.minPasswordLength ?? 10} characters.
              A phrase of a few words is easy to remember and hard to guess.
            </p>
            <label className="block text-[12px] text-slate-600">New password
              <input className="field mt-0.5" type="password" autoComplete="new-password" autoFocus
                value={newPassword} onChange={(e) => setNewPassword(e.target.value)} disabled={busy} />
            </label>
            <label className="block text-[12px] text-slate-600">New password again
              <input className="field mt-0.5" type="password" autoComplete="new-password"
                value={confirm} onChange={(e) => setConfirm(e.target.value)} disabled={busy} />
            </label>
            {error && <p role="alert" className="text-[12px] text-red-600">{error}</p>}
            <button type="submit" className="btn-primary w-full justify-center" disabled={busy || !newPassword || !confirm}>
              Save and sign in
            </button>
          </form>
        )}
        {config?.developmentSignIn && step === 'signin' && (
          <div className="border-t border-slate-100 pt-2">
            <button type="button" className="text-[11px] text-slate-500 underline" onClick={() => setDevOpen((o) => !o)}>
              Development: sign in with a token
            </button>
            {devOpen && (
              <form onSubmit={devSignIn} aria-label="Development sign-in" className="mt-1 space-y-1">
                <p className="text-[11px] text-slate-500">
                  <code>python3 scripts/dev-token.py --sub dev.doctor --roles DOCTOR</code>
                </p>
                <textarea aria-label="Access token" rows={3} className="field h-auto py-1 font-mono text-[11px]" spellCheck={false}
                  value={token} onChange={(e) => setToken(e.target.value)} />
                <button type="submit" className="btn-secondary w-full justify-center">Use token</button>
              </form>
            )}
          </div>
        )}
      </div>
    </main>
  );
}
