import {
  type ConsultationForm, emptyPrescription, FREQUENCIES, type Frequency, type PrescriptionRow, ROUTES, type Route,
  TIMINGS, type Timing,
} from '../emr/form';
import { DictatedMark, label } from './ui';

interface Props {
  form: ConsultationForm;
  onRowsChange(rows: PrescriptionRow[]): void;
  onEdit(id: string, patch: Partial<PrescriptionRow>): void;
  onAdviceChange(advice: string): void;
  onFollowUpChange(date: string): void;
  disabled: boolean;
}

export function PrescriptionSection({ form, onRowsChange, onEdit, onAdviceChange, onFollowUpChange, disabled }: Props) {
  const rows = form.prescriptions;
  return (
    <section aria-label="Prescription" className="px-3 py-2">
      <div className="mb-1 flex items-center">
        <h3 className="section-title">Prescription (Rx)</h3>
        <button type="button" className="btn-ghost ml-auto h-6" onClick={() => onRowsChange([...rows, emptyPrescription()])} disabled={disabled}>
          + Add drug
        </button>
      </div>
      <table className="w-full border-collapse">
        <thead>
          <tr className="border-b border-slate-200">
            <th className="th w-6">#</th>
            <th className="th">Drug (as printed)</th>
            <th className="th w-20">Dose</th>
            <th className="th w-24">Route</th>
            <th className="th w-20">Freq</th>
            <th className="th w-28">Timing</th>
            <th className="th w-14">Days</th>
            <th className="th w-40">Instructions</th>
            <th className="th w-6" />
            <th className="th w-6" />
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((row, index) => {
            const d = row.source === 'DICTATION' ? 'field-dictated' : '';
            const edit = (patch: Partial<PrescriptionRow>) => onEdit(row.id, patch);
            return (
              <tr key={row.id}>
                <td className="td tabular-nums text-slate-400">{index + 1}</td>
                <td className="td"><input aria-label="Drug" className={`field font-medium ${d}`} value={row.drugText} onChange={(e) => edit({ drugText: e.target.value })} disabled={disabled} /></td>
                <td className="td"><input aria-label="Dose" className={`field ${d}`} value={row.dose} onChange={(e) => edit({ dose: e.target.value })} disabled={disabled} /></td>
                <td className="td">
                  <select aria-label="Route" className={`field ${d}`} value={row.route} onChange={(e) => edit({ route: e.target.value as Route | '' })} disabled={disabled}>
                    <option value="">—</option>
                    {ROUTES.map((r) => <option key={r} value={r}>{label(r)}</option>)}
                  </select>
                </td>
                <td className="td">
                  <select aria-label="Frequency" className={`field ${d}`} value={row.frequency} onChange={(e) => edit({ frequency: e.target.value as Frequency | '' })} disabled={disabled}>
                    <option value="">—</option>
                    {FREQUENCIES.map((f) => <option key={f} value={f}>{f}</option>)}
                  </select>
                </td>
                <td className="td">
                  <select aria-label="Timing" className={`field ${d}`} value={row.timing} onChange={(e) => edit({ timing: e.target.value as Timing | '' })} disabled={disabled}>
                    <option value="">—</option>
                    {TIMINGS.map((t) => <option key={t} value={t}>{label(t)}</option>)}
                  </select>
                </td>
                <td className="td"><input aria-label="Days" inputMode="numeric" className={`field tabular-nums ${d}`} value={row.durationDays} onChange={(e) => edit({ durationDays: e.target.value })} disabled={disabled} /></td>
                <td className="td"><input aria-label="Instructions" className="field" value={row.instructions} onChange={(e) => edit({ instructions: e.target.value })} disabled={disabled} /></td>
                <td className="td">{row.source === 'DICTATION' && <DictatedMark />}</td>
                <td className="td">
                  <button type="button" aria-label="Remove drug" className="text-slate-400 hover:text-red-600" onClick={() => onRowsChange(rows.filter((r) => r.id !== row.id))} disabled={disabled}>×</button>
                </td>
              </tr>
            );
          })}
          {rows.length === 0 && (
            <tr><td colSpan={10} className="td py-2 text-slate-400">No drugs yet — dictate “Tab Paracetamol 650 mg 1-0-1 after food for 5 days” or add one.</td></tr>
          )}
        </tbody>
      </table>
      <div className="mt-2 grid grid-cols-[1fr_10rem] gap-2">
        <label className="block">
          <span className="text-2xs text-slate-500">Advice</span>
          <input className="field" value={form.advice} onChange={(e) => onAdviceChange(e.target.value)} disabled={disabled} />
        </label>
        <label className="block">
          <span className="text-2xs text-slate-500">Follow-up</span>
          <input type="date" className="field" value={form.followUpDate} onChange={(e) => onFollowUpChange(e.target.value)} disabled={disabled} />
        </label>
      </div>
    </section>
  );
}
