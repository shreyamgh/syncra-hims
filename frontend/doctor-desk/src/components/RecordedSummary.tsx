import type { EncounterRecord } from '../api/types';
import { duration, label } from './ui';

/** What is already signed into this encounter (read-only). */
export function RecordedSummary({ record }: { record: EncounterRecord }) {
  const v = record.latestVitals;
  const nothing = !v && record.complaints.length === 0 && record.prescriptions.length === 0;
  if (nothing) return null;
  return (
    <section aria-label="Recorded this visit" className="mx-3 my-2 rounded border border-slate-200 bg-white">
      <h3 className="section-title border-b border-slate-100 px-2 py-1">Recorded this visit</h3>
      <div className="space-y-1 px-2 py-1.5 text-[12px]">
        {v && (
          <p>
            <span className="text-slate-500">Vitals: </span>
            {[
              v.bpSystolic && `BP ${v.bpSystolic}/${v.bpDiastolic}`,
              v.pulseBpm && `P ${v.pulseBpm}`,
              v.temperatureC && `T ${v.temperatureC}°C`,
              v.spo2Percent && `SpO₂ ${v.spo2Percent}%`,
              v.respiratoryRate && `RR ${v.respiratoryRate}`,
              v.weightKg && `Wt ${v.weightKg} kg`,
              v.bmi && `BMI ${v.bmi}`,
              v.bloodGlucoseMgDl && `RBS ${v.bloodGlucoseMgDl}`,
            ].filter(Boolean).join(' · ')}
          </p>
        )}
        {record.complaints.length > 0 && (
          <p>
            <span className="text-slate-500">Complaints: </span>
            {record.complaints
              .map((c) => `${c.description}${c.durationValue ? ` × ${duration(c.durationValue, c.durationUnit)}` : ''}`)
              .join(', ')}
          </p>
        )}
        {record.prescriptions.map((rx) => (
          <div key={rx.id}>
            <span className="text-slate-500">Rx {label(rx.status)} by {rx.prescribedBy}{rx.prescriberRegistration ? ` (Reg. ${rx.prescriberRegistration})` : ''}:</span>
            <ol className="ml-4 list-decimal">
              {rx.items.map((item) => (
                <li key={item.lineNo}>
                  <b>{item.drugText}</b> {[item.dose, item.frequency, label(item.timing), item.durationDays && `× ${duration(item.durationDays, 'DAYS')}`].filter(Boolean).join(' · ')}
                </li>
              ))}
            </ol>
            {rx.advice && <p className="text-slate-600">Advice: {rx.advice}</p>}
          </div>
        ))}
      </div>
    </section>
  );
}
