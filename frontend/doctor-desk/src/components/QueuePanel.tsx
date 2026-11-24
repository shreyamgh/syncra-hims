import type { Department, Queue, Visit } from '../api/types';
import { PriorityBadge, QueueStatusText, waitedMinutes } from './ui';

interface Props {
  departments: Department[];
  department: string;
  onDepartmentChange(code: string): void;
  queue: Queue | null;
  selectedVisitId: string | null;
  onSelect(visit: Visit): void;
  onCallNext(): void;
  busy: boolean;
  locked: boolean;
}

/** Today's OPD queue for one department, in board order (dense, one row per token). */
export function QueuePanel({ departments, department, onDepartmentChange, queue, selectedVisitId, onSelect, onCallNext, busy, locked }: Props) {
  const counts = queue?.counts ?? {};
  return (
    <aside className="flex min-h-0 flex-col border-r border-slate-200 bg-white" aria-label="OPD queue">
      <div className="flex items-center gap-2 border-b border-slate-200 p-2">
        <select
          aria-label="Department"
          className="field w-auto min-w-0 flex-1"
          value={department}
          onChange={(e) => onDepartmentChange(e.target.value)}
          disabled={locked}
        >
          {departments.map((d) => (
            <option key={d.code} value={d.code}>{d.name}</option>
          ))}
        </select>
        <button type="button" className="btn-primary" onClick={onCallNext} disabled={busy || locked || !department} title="Call next patient (Alt+N)">
          Call next
        </button>
      </div>
      <div className="flex gap-3 border-b border-slate-100 px-2 py-1 text-[11px] text-slate-500">
        <span>Waiting <b className="text-slate-800">{counts.WAITING ?? 0}</b></span>
        <span>Called <b className="text-slate-800">{counts.CALLED ?? 0}</b></span>
        <span>In consult <b className="text-slate-800">{counts.IN_CONSULTATION ?? 0}</b></span>
        <span>Done <b className="text-slate-800">{counts.COMPLETED ?? 0}</b></span>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        <table className="w-full border-collapse">
          <thead className="sticky top-0 bg-slate-50">
            <tr>
              <th className="th w-10">Tok</th>
              <th className="th">Patient</th>
              <th className="th w-20">Status</th>
              <th className="th w-10 text-right">Wait</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {queue?.visits.map((visit) => {
              const selected = visit.visitId === selectedVisitId;
              return (
                <tr
                  key={visit.visitId}
                  onClick={() => !locked && onSelect(visit)}
                  aria-selected={selected}
                  className={`cursor-pointer ${selected ? 'bg-indigo-50' : 'hover:bg-slate-50'} ${locked && !selected ? 'opacity-60' : ''}`}
                >
                  <td className="td font-mono font-semibold tabular-nums">{visit.tokenNumber}</td>
                  <td className="td">
                    <div className="flex items-center gap-1">
                      <span className="truncate font-medium">{visit.patientName}</span>
                      <PriorityBadge priority={visit.priority} />
                    </div>
                    <div className="text-[11px] text-slate-500">
                      {visit.ageYears ?? '?'}{visit.gender} · {visit.uhid}
                    </div>
                  </td>
                  <td className="td"><QueueStatusText status={visit.queueStatus} /></td>
                  <td className="td text-right text-[11px] tabular-nums text-slate-500">
                    {visit.queueStatus === 'WAITING' ? waitedMinutes(visit.registeredAt) : ''}
                  </td>
                </tr>
              );
            })}
            {queue && queue.visits.length === 0 && (
              <tr><td colSpan={4} className="p-4 text-center text-slate-400">No tokens issued today.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </aside>
  );
}
