import { useEffect, useMemo, useState } from 'react';
import { endpoints } from '../api/endpoints';
import type { CdssAlert, CdssCheckRequest, CdssCheckResponse, EncounterRecord } from '../api/types';
import type { ConsultationForm } from './form';

export const OVERRIDE_MIN_LENGTH = 5;
const DEBOUNCE_MS = 400;

export const isSerious = (alert: { severity: string }) => alert.severity === 'CONTRAINDICATED' || alert.severity === 'MAJOR';

/** What the CDSS needs from the draft: prescription lines, allergies, age, sex, weight, creatinine. */
export function cdssRequest(form: ConsultationForm, record: EncounterRecord | null): CdssCheckRequest | null {
  const lines = form.prescriptions.filter((p) => p.drugText.trim() !== '');
  if (lines.length === 0 || !record) return null;
  const typedWeight = Number(form.vitals.weightKg.value);
  const weight = form.vitals.weightKg.value.trim() && Number.isFinite(typedWeight) ? typedWeight : record.latestVitals?.weightKg;
  return {
    prescriptions: lines.map((p) => ({
      drug_text: p.drugText.trim(),
      dose: p.dose.trim() || undefined,
      frequency: p.frequency || undefined,
      duration_days: p.durationDays.trim() ? Number(p.durationDays) : undefined,
    })),
    allergies: record.allergies.map((a) => ({ allergen: a.allergen, drug_class: a.drugClass ?? undefined })),
    patient: {
      age_years: record.patient.ageYears,
      sex: record.patient.gender,
      weight_kg: weight,
      serum_creatinine_mg_dl: record.latestCreatinine?.mgPerDl,
    },
  };
}

/** Serious alerts the prescriber has not yet given a reason for. */
export function missingOverrides(alerts: CdssAlert[], overrides: Record<string, string>): CdssAlert[] {
  return alerts.filter((a) => isSerious(a) && (overrides[a.rule_code] ?? '').trim().length < OVERRIDE_MIN_LENGTH);
}

export interface CdssState {
  result: CdssCheckResponse | null;
  checking: boolean;
  unavailable: boolean;
}

/** Re-checks the draft prescription (debounced) whenever its drugs, doses or the patient facts change. */
export function useCdss(form: ConsultationForm, record: EncounterRecord | null): CdssState {
  const request = useMemo(() => cdssRequest(form, record), [form, record]);
  const key = request ? JSON.stringify(request) : '';
  const [state, setState] = useState<CdssState>({ result: null, checking: false, unavailable: false });

  useEffect(() => {
    if (!request) {
      setState({ result: null, checking: false, unavailable: false });
      return undefined;
    }
    const controller = new AbortController();
    setState((s) => ({ ...s, checking: true }));
    const timer = window.setTimeout(() => {
      endpoints.cdssCheck(request, controller.signal).then(
        (result) => setState({ result, checking: false, unavailable: false }),
        (error: unknown) => {
          if ((error as { name?: string }).name !== 'AbortError') {
            setState({ result: null, checking: false, unavailable: true });
          }
        },
      );
    }, DEBOUNCE_MS);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the serialised request is the dependency
  }, [key]);

  return state;
}
