import type { EncounterRecord, Visit } from '../api/types';
import { PriorityBadge, QueueStatusText, label } from './ui';

interface Props {
  visit: Visit;
  record: EncounterRecord | null;
  onAction(action: 'start' | 'hold' | 'no-show'): void;
  busy: boolean;
  locked: boolean;
  /** Opens the allergy list (clicking the allergy chips). */
  onOpenAllergies?(): void;
}

export function PatientHeader({ visit, record, onAction, busy, locked, onOpenAllergies }: Props) {
  const status = record?.visit?.queueStatus ?? visit.queueStatus;
  const closed = status === 'COMPLETED' || status === 'NO_SHOW' || status === 'CANCELLED';
  const allergies = record?.allergies ?? [];
  return (
    <header className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-slate-200 bg-white px-3 py-2">
      <div className="flex items-baseline gap-2">
        <span className="font-mono text-lg font-bold tabular-nums text-slate-400">#{visit.tokenNumber}</span>
        <h2 className="text-[15px] font-semibold">{visit.patientName}</h2>
        <span className="text-slate-500">{visit.ageYears ?? '?'} y / {visit.gender}</span>
        <span className="font-mono text-[12px] text-slate-500">{visit.uhid}</span>
        <PriorityBadge priority={visit.priority} />
      </div>
      <div className="flex items-center gap-2 text-[12px]">
        <QueueStatusText status={status} />
        <span className="text-slate-400">·</span>
        <span className="text-slate-500">{label(visit.visitType)}</span>
        {visit.doctor && <span className="text-slate-500">· {visit.doctor.name}</span>}
      </div>
      <button type="button" className="flex flex-wrap items-center gap-1 rounded text-left hover:bg-slate-50 disabled:cursor-default"
        onClick={onOpenAllergies} disabled={!onOpenAllergies || locked} title="Open the allergy list (Alt+4)">
        {allergies.length === 0 ? (
          <span className="text-[11px] text-slate-400">No known allergies recorded</span>
        ) : (
          allergies.map((a) => (
            <span key={a.allergen} className="rounded bg-red-50 px-1.5 py-px text-[11px] font-medium text-red-700 ring-1 ring-inset ring-red-200">
              Allergy: {a.allergen}{a.reaction ? ` (${a.reaction})` : ''}
            </span>
          ))
        )}
      </button>
      <div className="ml-auto flex gap-1">
        {(status === 'WAITING' || status === 'CALLED' || status === 'ON_HOLD') && (
          <button type="button" className="btn-secondary" onClick={() => onAction('start')} disabled={busy || locked}>
            Start consultation
          </button>
        )}
        {status === 'IN_CONSULTATION' && (
          <button type="button" className="btn-ghost" onClick={() => onAction('hold')} disabled={busy || locked} title="Send for investigations; the token returns to the queue">
            Hold
          </button>
        )}
        {(status === 'WAITING' || status === 'CALLED') && (
          <button type="button" className="btn-ghost" onClick={() => onAction('no-show')} disabled={busy || locked}>
            No show
          </button>
        )}
        {closed && <span className="text-[12px] text-slate-500">Visit closed</span>}
      </div>
    </header>
  );
}
