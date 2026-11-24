import { type ComplaintRow, DURATION_UNITS, type DurationUnit, emptyComplaint } from '../emr/form';
import { DictatedMark, label } from './ui';

interface Props {
  rows: ComplaintRow[];
  onChange(rows: ComplaintRow[]): void;
  onEdit(id: string, patch: Partial<ComplaintRow>): void;
  disabled: boolean;
}

export function ComplaintsSection({ rows, onChange, onEdit, disabled }: Props) {
  return (
    <section aria-label="Complaints" className="px-3 py-2">
      <div className="mb-1 flex items-center">
        <h3 className="section-title">Complaints</h3>
        <button type="button" className="btn-ghost ml-auto h-6" onClick={() => onChange([...rows, emptyComplaint()])} disabled={disabled}>
          + Add
        </button>
      </div>
      <table className="w-full border-collapse">
        <thead>
          <tr className="border-b border-slate-200">
            <th className="th">Complaint</th>
            <th className="th w-36">Duration</th>
            <th className="th w-12 text-center">Chief</th>
            <th className="th w-6" />
            <th className="th w-6" />
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((row) => {
            const dictated = row.source === 'DICTATION';
            return (
              <tr key={row.id}>
                <td className="td">
                  <input aria-label="Complaint" className={`field ${dictated ? 'field-dictated' : ''}`} value={row.description}
                    onChange={(e) => onEdit(row.id, { description: e.target.value })} disabled={disabled} />
                </td>
                <td className="td">
                  <div className="flex gap-1">
                    <input aria-label="Duration" inputMode="numeric" className={`field w-12 tabular-nums ${dictated ? 'field-dictated' : ''}`}
                      value={row.durationValue} onChange={(e) => onEdit(row.id, { durationValue: e.target.value })} disabled={disabled} />
                    <select aria-label="Duration unit" className={`field ${dictated ? 'field-dictated' : ''}`} value={row.durationUnit}
                      onChange={(e) => onEdit(row.id, { durationUnit: e.target.value as DurationUnit | '' })} disabled={disabled}>
                      <option value="">—</option>
                      {DURATION_UNITS.map((u) => <option key={u} value={u}>{label(u)}</option>)}
                    </select>
                  </div>
                </td>
                <td className="td text-center">
                  <input type="checkbox" aria-label="Chief complaint" checked={row.chief}
                    onChange={(e) => onEdit(row.id, { chief: e.target.checked })} disabled={disabled} />
                </td>
                <td className="td">{dictated && <DictatedMark />}</td>
                <td className="td">
                  <button type="button" aria-label="Remove complaint" className="text-slate-400 hover:text-red-600"
                    onClick={() => onChange(rows.filter((r) => r.id !== row.id))} disabled={disabled}>×</button>
                </td>
              </tr>
            );
          })}
          {rows.length === 0 && (
            <tr><td colSpan={5} className="td py-2 text-slate-400">No complaints yet — dictate or add one.</td></tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
