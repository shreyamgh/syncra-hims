import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { type Account, type Issued, ROLES, type Role, type StaffMember, admin, suggestUsername } from '../api/admin';
import type { Session } from '../auth/session';
import type { DeskId } from '../emr/diagnostics';
import { DeskHeader, type Notice } from './DeskHeader';
import { Badge, label, when } from './ui';

/** Administration: staff accounts (create, reset password, disable, unlock, roles). */
export function AdminDesk({ session, onDeskChange }: { session: Session; onDeskChange?(desk: DeskId): void }) {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [staff, setStaff] = useState<StaffMember[]>([]);
  const [issued, setIssued] = useState<Issued | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const report = useCallback((e: unknown) => setNotice({ kind: 'error', text: e instanceof Error ? e.message : 'Something went wrong' }), []);

  const load = useCallback(async () => {
    try {
      const [a, s] = await Promise.all([admin.accounts(), admin.staff()]);
      setAccounts(a);
      setStaff(s);
    } catch (e) {
      report(e);
    }
  }, [report]);
  useEffect(() => {
    void load();
  }, [load]);

  const run = async (action: () => Promise<Account | Issued>, text: string) => {
    setBusy(true);
    try {
      const result = await action();
      if ('temporaryPassword' in result) setIssued(result);
      setNotice({ kind: 'ok', text });
      setEditing(null);
      await load();
    } catch (e) {
      report(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex h-full flex-col">
      <DeskHeader session={session} desk="admin" onDeskChange={onDeskChange} notice={notice} />
      <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
        <section aria-label="Accounts" className="min-h-0 overflow-y-auto border-r border-slate-200 bg-white">
          <table className="w-full border-collapse text-[12px]" aria-label="Staff accounts">
            <thead className="sticky top-0 bg-white">
              <tr className="border-b border-slate-200">
                <th className="th">Name</th><th className="th">Username</th><th className="th">Roles</th><th className="th">Status</th>
                <th className="th">Last sign-in</th><th className="th w-64" />
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {accounts === null && <tr><td colSpan={6} className="td py-2 text-slate-400">Loading…</td></tr>}
              {accounts?.map((a) => (
                <tr key={a.id} aria-label={a.displayName} className={a.status === 'DISABLED' ? 'text-slate-400' : ''}>
                  <td className="td font-medium">{a.displayName}</td>
                  <td className="td font-mono">{a.username}</td>
                  <td className="td">
                    {editing === a.id ? (
                      <RolePicker initial={a.roles} busy={busy} onCancel={() => setEditing(null)}
                        onSave={(roles) => void run(() => admin.setRoles(a.id, roles), `Roles of ${a.username} changed; they apply from the next sign-in.`)} />
                    ) : (
                      <span className="flex flex-wrap gap-1">{a.roles.map((r) => <Badge key={r} tone="slate">{label(r)}</Badge>)}</span>
                    )}
                  </td>
                  <td className="td">
                    <span className="flex flex-wrap gap-1">
                      {a.status === 'DISABLED' ? <Badge tone="slate">Disabled</Badge> : <Badge tone="emerald">Active</Badge>}
                      {a.locked && <Badge tone="red" title={`Locked until ${when(a.lockedUntil)}`}>Locked</Badge>}
                      {a.mustChangePassword && <Badge tone="amber">Temporary password</Badge>}
                    </span>
                  </td>
                  <td className="td text-slate-500">{when(a.lastLoginAt) || 'Never'}</td>
                  <td className="td text-right">
                    <span className="flex justify-end gap-1">
                      {editing !== a.id && <button type="button" className="btn-ghost h-6" disabled={busy} onClick={() => setEditing(a.id)}>Roles</button>}
                      {a.locked && <button type="button" className="btn-ghost h-6" disabled={busy} onClick={() => void run(() => admin.unlock(a.id), `${a.username} unlocked.`)}>Unlock</button>}
                      <button type="button" className="btn-ghost h-6" disabled={busy} onClick={() => {
                        if (window.confirm(`Reset ${a.username}'s password? Their sessions end and they get a temporary password.`)) {
                          void run(() => admin.resetPassword(a.id), `Password of ${a.username} reset.`);
                        }
                      }}>Reset password</button>
                      {a.status === 'ACTIVE'
                        ? <button type="button" className="btn-ghost h-6 text-red-700" disabled={busy || a.username === session.subject}
                            onClick={() => void run(() => admin.disable(a.id), `${a.username} disabled; their sessions ended.`)}>Disable</button>
                        : <button type="button" className="btn-ghost h-6" disabled={busy} onClick={() => void run(() => admin.enable(a.id), `${a.username} enabled.`)}>Enable</button>}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <aside aria-label="New account" className="min-h-0 space-y-3 overflow-y-auto bg-slate-50 p-3">
          {issued && (
            <section aria-label="Temporary password" className="rounded border border-amber-300 bg-amber-50 p-2">
              <p className="text-[12px] text-amber-900">
                Give this temporary password to <b>{issued.account.displayName}</b> ({issued.account.username}). It is shown only once;
                they choose their own at first sign-in.
              </p>
              <p className="mt-1 select-all rounded bg-white px-2 py-1 text-center font-mono text-lg tracking-widest">{issued.temporaryPassword}</p>
              <button type="button" className="btn-ghost mt-1 h-6" onClick={() => setIssued(null)}>Done</button>
            </section>
          )}
          <CreateAccount staff={staff.filter((s) => !s.hasAccount)} busy={busy}
            onCreate={(account) => run(() => admin.create(account), `Account ${account.username} created.`)} />
        </aside>
      </div>
    </div>
  );
}

function RolePicker({ initial, busy, onSave, onCancel }: { initial: Role[]; busy: boolean; onSave(roles: Role[]): void; onCancel(): void }) {
  const [roles, setRoles] = useState<Role[]>(initial);
  return (
    <span className="flex flex-wrap items-center gap-1">
      {ROLES.map((r) => (
        <label key={r} className="flex items-center gap-0.5 text-[11px]">
          <input type="checkbox" checked={roles.includes(r)} disabled={busy}
            onChange={(e) => setRoles(e.target.checked ? [...roles, r] : roles.filter((x) => x !== r))} />{label(r)}
        </label>
      ))}
      <button type="button" className="btn-secondary h-6" disabled={busy || roles.length === 0 || roles.length > 4} onClick={() => onSave(roles)}>Save</button>
      <button type="button" className="btn-ghost h-6" onClick={onCancel}>Cancel</button>
    </span>
  );
}

function CreateAccount({ staff, busy, onCreate }: {
  staff: StaffMember[];
  busy: boolean;
  onCreate(account: { username: string; displayName?: string; staffId?: string; roles: Role[] }): Promise<void>;
}) {
  const [staffId, setStaffId] = useState('');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [roles, setRoles] = useState<Role[]>([]);
  const [problems, setProblems] = useState<string[]>([]);

  const chooseStaff = (id: string) => {
    setStaffId(id);
    const member = staff.find((s) => s.id === id);
    if (member) {
      setUsername(member.iamSubject ?? suggestUsername(member.fullName));
      setRoles([member.staffRole]);
      setDisplayName('');
    }
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found: string[] = [];
    if (!/^[a-z0-9][a-z0-9._-]{2,63}$/.test(username)) found.push("Usernames are 3 to 64 lowercase letters, digits, '.', '_' or '-'");
    if (!staffId && !displayName.trim()) found.push('Link a staff member, or give the name');
    if (roles.length === 0 || roles.length > 4) found.push('Choose one to four roles');
    setProblems(found);
    if (found.length > 0) return;
    await onCreate({ username, staffId: staffId || undefined, displayName: displayName.trim() || undefined, roles });
    setStaffId('');
    setUsername('');
    setDisplayName('');
    setRoles([]);
  };

  return (
    <form onSubmit={(e) => void submit(e)} aria-label="Create an account" className="space-y-2 rounded border border-slate-200 bg-white p-2">
      <h2 className="text-[14px] font-semibold">New account</h2>
      <label className="block text-[11px] text-slate-500">Staff member
        <select className="field" value={staffId} disabled={busy} onChange={(e) => chooseStaff(e.target.value)}>
          <option value="">Not linked to a staff record</option>
          {staff.map((s) => <option key={s.id} value={s.id}>{s.fullName} · {label(s.staffRole)}{s.department ? ` · ${s.department}` : ''}</option>)}
        </select>
      </label>
      {!staffId && (
        <label className="block text-[11px] text-slate-500">Name
          <input className="field" maxLength={150} value={displayName} disabled={busy} onChange={(e) => setDisplayName(e.target.value)} />
        </label>
      )}
      <label className="block text-[11px] text-slate-500">Username
        <input className="field font-mono" maxLength={64} value={username} disabled={busy} onChange={(e) => setUsername(e.target.value.toLowerCase())} />
      </label>
      <fieldset className="text-[11px] text-slate-500">
        <legend>Roles (one to four)</legend>
        <div className="mt-0.5 grid grid-cols-2 gap-0.5">
          {ROLES.map((r) => (
            <label key={r} className="flex items-center gap-1 text-[12px] text-slate-700">
              <input type="checkbox" checked={roles.includes(r)} disabled={busy}
                onChange={(e) => setRoles(e.target.checked ? [...roles, r] : roles.filter((x) => x !== r))} />{label(r)}
            </label>
          ))}
        </div>
      </fieldset>
      {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
      <button type="submit" className="btn-primary w-full justify-center" disabled={busy}>Create account</button>
    </form>
  );
}
