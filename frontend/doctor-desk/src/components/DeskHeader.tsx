import { endpoints } from '../api/endpoints';
import { iam } from '../auth/iam';
import { type Session, signOut } from '../auth/session';
import { DESKS, type DeskId, desksFor } from '../emr/diagnostics';

export type Notice = { kind: 'ok' | 'error'; text: string };

interface Props {
  session: Session;
  desk: DeskId;
  /** Shown when the user can open more than one desk. */
  onDeskChange?(desk: DeskId): void;
  notice: Notice | null;
}

/** The dark top bar shared by every desk: product, desk switcher, the last notice, the user. */
export function DeskHeader({ session, desk, onDeskChange, notice }: Props) {
  const available = desksFor(session.roles);
  return (
    <header className="flex h-10 shrink-0 items-center gap-3 bg-slate-900 px-3 text-white">
      <span className="font-semibold">Syncra HIMS</span>
      {available.length > 1 && onDeskChange ? (
        <nav aria-label="Desks" className="flex gap-1">
          {DESKS.filter((d) => available.includes(d.id)).map((d) => (
            <button key={d.id} type="button" aria-current={d.id === desk ? 'page' : undefined} onClick={() => onDeskChange(d.id)}
              className={`btn h-6 ${d.id === desk ? 'bg-slate-700 text-white' : 'text-slate-400 hover:bg-slate-800 hover:text-white'}`}>
              {d.label}
            </button>
          ))}
        </nav>
      ) : (
        <span className="text-slate-400">{DESKS.find((d) => d.id === desk)?.label}</span>
      )}
      {notice && (
        <span role={notice.kind === 'error' ? 'alert' : 'status'}
          className={`ml-4 truncate rounded px-2 py-0.5 text-[12px] ${notice.kind === 'error' ? 'bg-red-600' : 'bg-emerald-600'}`}>
          {notice.text}
        </span>
      )}
      <span className="ml-auto whitespace-nowrap text-[12px] text-slate-300">{session.subject} · {session.roles.join(', ')}</span>
      <button type="button" className="btn h-6 text-slate-300 hover:bg-slate-800" onClick={() => {
        // End the sign-in session and clear the image-viewer cookie (best effort), then forget the token.
        void Promise.allSettled([iam.logout(), endpoints.closeViewerSession()]).finally(signOut);
      }}>Sign out</button>
    </header>
  );
}
