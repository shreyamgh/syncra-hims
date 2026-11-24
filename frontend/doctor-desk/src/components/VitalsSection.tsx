import { type ConsultationForm, VITALS, type VitalKey } from '../emr/form';
import { DictatedMark } from './ui';

interface Props {
  form: ConsultationForm;
  onChange(key: VitalKey, value: string): void;
  disabled: boolean;
}

export function VitalsSection({ form, onChange, disabled }: Props) {
  const weight = Number(form.vitals.weightKg.value);
  const height = Number(form.vitals.heightCm.value) / 100;
  const bmi = weight > 0 && height > 0 ? (weight / (height * height)).toFixed(1) : '';
  return (
    <section aria-label="Vitals" className="px-3 py-2">
      <h3 className="section-title mb-1">Vitals</h3>
      <div className="grid grid-cols-5 gap-x-2 gap-y-1 lg:grid-cols-10">
        {VITALS.map((vital) => {
          const field = form.vitals[vital.key];
          const dictated = field.source === 'DICTATION' && field.value !== '';
          return (
            <label key={vital.key} className="block">
              <span className="flex items-center gap-1 text-2xs text-slate-500">
                {vital.label} <span className="text-slate-400">{vital.unit}</span>
                {dictated && <DictatedMark />}
              </span>
              <input
                inputMode="decimal"
                aria-label={vital.label}
                className={`field tabular-nums ${dictated ? 'field-dictated' : ''}`}
                value={field.value}
                onChange={(e) => onChange(vital.key, e.target.value)}
                disabled={disabled}
              />
            </label>
          );
        })}
        <div>
          <span className="text-2xs text-slate-500">BMI</span>
          <div className="flex h-7 items-center px-1.5 tabular-nums text-slate-600">{bmi || '—'}</div>
        </div>
      </div>
    </section>
  );
}
