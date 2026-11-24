import type { CdssAlert, CdssSeverity } from '../api/types';
import { type CdssState, isSerious, OVERRIDE_MIN_LENGTH } from '../emr/cdss';

const STYLE: Record<CdssSeverity, string> = {
  CONTRAINDICATED: 'border-red-300 bg-red-50 text-red-800',
  MAJOR: 'border-red-200 bg-red-50 text-red-800',
  MODERATE: 'border-amber-200 bg-amber-50 text-amber-900',
  MINOR: 'border-slate-200 bg-slate-50 text-slate-700',
};

const KIND: Record<CdssAlert['alert_type'], string> = {
  DRUG_DRUG: 'Interaction',
  DRUG_ALLERGY: 'Allergy',
  DUPLICATE_THERAPY: 'Duplicate',
  DOSE_RANGE: 'Dose',
  CONTRAINDICATION: 'Caution',
};

interface Props {
  state: CdssState;
  overrides: Record<string, string>;
  onOverride(ruleCode: string, reason: string): void;
  disabled: boolean;
}

/**
 * Drug-safety alerts for the draft prescription: red for contraindicated and major (a reason
 * is needed to sign past them), amber for moderate, grey for minor.
 */
export function CdssPanel({ state, overrides, onOverride, disabled }: Props) {
  const { result, checking, unavailable } = state;
  if (unavailable) {
    return (
      <section aria-label="Drug safety" className="px-3 py-1.5">
        <p role="status" className="text-[12px] text-amber-700">Drug-safety check unavailable — it will be re-run when you sign.</p>
      </section>
    );
  }
  if (!result) {
    return checking ? <section aria-label="Drug safety" className="px-3 py-1 text-[11px] text-slate-400">Checking drug safety…</section> : null;
  }
  return (
    <section aria-label="Drug safety" className="space-y-1 px-3 py-2">
      <div className="flex items-center gap-2">
        <h3 className="section-title">Drug safety</h3>
        {result.alerts.length === 0 && <span className="text-[12px] text-emerald-700">No alerts</span>}
        {!result.kb_reviewed && (
          <span className="ml-auto text-[11px] text-slate-400" title={result.kb_version}>
            Rules pending pharmacist review · {result.kb_version}
          </span>
        )}
      </div>
      {result.alerts.map((alert) => {
        const serious = isSerious(alert);
        const reason = overrides[alert.rule_code] ?? '';
        return (
          <div key={`${alert.rule_code}-${alert.lines.join('-')}`} role="alert"
            className={`rounded border px-2 py-1 text-[12px] ${STYLE[alert.severity]}`}>
            <div className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-semibold uppercase tracking-wide">{alert.severity.toLowerCase()}</span>
              <span className="text-[11px] opacity-75">{KIND[alert.alert_type]} · line {alert.lines.map((l) => l + 1).join(' & ')}</span>
              <span>{alert.message}</span>
            </div>
            {alert.management && <div className="opacity-80">{alert.management}</div>}
            {serious && (
              <input aria-label={`Reason to override ${alert.rule_code}`} className="field mt-1 bg-white"
                placeholder="Reason to prescribe anyway (required to sign), or change the prescription"
                value={reason} onChange={(e) => onOverride(alert.rule_code, e.target.value)} disabled={disabled}
                minLength={OVERRIDE_MIN_LENGTH} />
            )}
          </div>
        );
      })}
      {result.not_checked.length > 0 && (
        <p className="text-[11px] text-slate-400">Not checked: {result.not_checked.join('; ')}</p>
      )}
    </section>
  );
}
