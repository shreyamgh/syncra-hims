import type { ConsultationRequest, DictationSessionRecord } from '../api/types';
import type { Extraction, ExtractedVitals } from '../dictation/protocol';

/**
 * Consultation form model and the rules for auto-filling it from dictation.
 *
 * Dictation never overwrites the doctor: a value typed or edited by hand is
 * MANUAL and stays as it is; only empty fields or fields that dictation itself
 * filled are (re)written. Rows added by dictation remember their session so the
 * saved record can show where each value came from.
 */
export type Source = 'MANUAL' | 'DICTATION';
export type DurationUnit = 'HOURS' | 'DAYS' | 'WEEKS' | 'MONTHS' | 'YEARS';

export const ROUTES = ['ORAL', 'IV', 'IM', 'SC', 'TOPICAL', 'INHALATION', 'NASAL', 'OPHTHALMIC', 'OTIC', 'RECTAL',
  'SUBLINGUAL', 'OTHER'] as const;
export const FREQUENCIES = ['OD', 'BD', 'TDS', 'QID', 'HS', 'SOS', 'STAT', 'Q4H', 'Q6H', 'Q8H', 'WEEKLY', 'ALT_DAYS',
  'OTHER'] as const;
export const TIMINGS = ['AFTER_FOOD', 'BEFORE_FOOD', 'WITH_FOOD', 'EMPTY_STOMACH', 'BEDTIME', 'ANY'] as const;
export const DURATION_UNITS: DurationUnit[] = ['HOURS', 'DAYS', 'WEEKS', 'MONTHS', 'YEARS'];

export type Route = (typeof ROUTES)[number];
export type Frequency = (typeof FREQUENCIES)[number];
export type Timing = (typeof TIMINGS)[number];

export const VITALS = [
  { key: 'temperatureC', label: 'Temp', unit: '°C', from: 'temperature_c', min: 25, max: 45 },
  { key: 'pulseBpm', label: 'Pulse', unit: '/min', from: 'pulse_bpm', min: 20, max: 250 },
  { key: 'respiratoryRate', label: 'RR', unit: '/min', from: 'respiratory_rate', min: 4, max: 80 },
  { key: 'bpSystolic', label: 'BP sys', unit: 'mmHg', from: 'bp_systolic', min: 40, max: 300 },
  { key: 'bpDiastolic', label: 'BP dia', unit: 'mmHg', from: 'bp_diastolic', min: 20, max: 200 },
  { key: 'spo2Percent', label: 'SpO₂', unit: '%', from: 'spo2_percent', min: 30, max: 100 },
  { key: 'weightKg', label: 'Weight', unit: 'kg', from: 'weight_kg', min: 0.3, max: 400 },
  { key: 'heightCm', label: 'Height', unit: 'cm', from: 'height_cm', min: 20, max: 250 },
  { key: 'bloodGlucoseMgDl', label: 'RBS', unit: 'mg/dL', from: 'blood_glucose_mg_dl', min: 10, max: 1500 },
] as const satisfies readonly { key: string; label: string; unit: string; from: keyof ExtractedVitals; min: number; max: number }[];

export type VitalKey = (typeof VITALS)[number]['key'];

export interface FieldValue {
  value: string;
  source: Source;
  sessionRef?: string;
}

export interface ComplaintRow {
  id: string;
  description: string;
  durationValue: string;
  durationUnit: DurationUnit | '';
  chief: boolean;
  source: Source;
  sessionRef?: string;
}

export interface PrescriptionRow {
  id: string;
  drugText: string;
  dose: string;
  route: Route | '';
  frequency: Frequency | '';
  timing: Timing | '';
  durationDays: string;
  instructions: string;
  source: Source;
  sessionRef?: string;
  /** Normalised drug name used to recognise the same drug in later dictation updates. */
  matchKey: string;
}

export type DiagnosisType = 'PROVISIONAL' | 'DIFFERENTIAL' | 'FINAL';
export const DIAGNOSIS_TYPES: DiagnosisType[] = ['PROVISIONAL', 'DIFFERENTIAL', 'FINAL'];

export interface DiagnosisRow {
  id: string;
  description: string;
  icd10Code: string;
  type: DiagnosisType;
  primary: boolean;
  source: Source;
  sessionRef?: string;
}

export interface ConsultationForm {
  vitals: Record<VitalKey, FieldValue>;
  complaints: ComplaintRow[];
  diagnoses: DiagnosisRow[];
  prescriptions: PrescriptionRow[];
  /** The prescriber's reasons for overriding serious CDSS alerts, by rule code. */
  cdssOverrides: Record<string, string>;
  advice: string;
  followUpDate: string;
  sessions: DictationSessionRecord[];
}

let nextId = 0;
export const newId = (prefix: string) => `${prefix}-${Date.now().toString(36)}-${(nextId++).toString(36)}`;

export function emptyForm(): ConsultationForm {
  const vitals = Object.fromEntries(VITALS.map((v) => [v.key, { value: '', source: 'MANUAL' }])) as Record<
    VitalKey,
    FieldValue
  >;
  return {
    vitals, complaints: [], diagnoses: [], prescriptions: [], cdssOverrides: {}, advice: '', followUpDate: '', sessions: [],
  };
}

export function emptyDiagnosis(): DiagnosisRow {
  return { id: newId('dx'), description: '', icd10Code: '', type: 'PROVISIONAL', primary: false, source: 'MANUAL' };
}

export function emptyComplaint(): ComplaintRow {
  return { id: newId('c'), description: '', durationValue: '', durationUnit: '', chief: false, source: 'MANUAL' };
}

export function emptyPrescription(): PrescriptionRow {
  return {
    id: newId('rx'), drugText: '', dose: '', route: '', frequency: '', timing: '', durationDays: '', instructions: '',
    source: 'MANUAL', matchKey: '',
  };
}

const normalise = (text: string) => text.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
const asOption = <T extends string>(options: readonly T[], value: string | null): T | '' =>
  value && (options as readonly string[]).includes(value) ? (value as T) : '';

/** Merges a (cumulative) extraction into the form. Pure: returns a new form. */
export function applyExtraction(form: ConsultationForm, extraction: Extraction, sessionRef: string): ConsultationForm {
  const vitals = { ...form.vitals };
  for (const vital of VITALS) {
    const value = extraction.vitals[vital.from];
    const current = vitals[vital.key];
    if (value !== null && value !== undefined && (current.value === '' || current.source === 'DICTATION')) {
      vitals[vital.key] = { value: String(value), source: 'DICTATION', sessionRef };
    }
  }

  const complaints = [...form.complaints];
  for (const found of extraction.complaints) {
    const key = normalise(found.description);
    const index = complaints.findIndex((c) => normalise(c.description) === key);
    const row: ComplaintRow = {
      id: index >= 0 ? complaints[index]!.id : newId('c'),
      description: found.description,
      durationValue: found.duration_value === null ? '' : String(found.duration_value),
      durationUnit: found.duration_unit ?? '',
      chief: index >= 0 ? complaints[index]!.chief : complaints.length === 0,
      source: 'DICTATION',
      sessionRef,
    };
    if (index < 0) complaints.push(row);
    else if (complaints[index]!.source === 'DICTATION') complaints[index] = row;
  }

  const diagnoses = [...form.diagnoses];
  for (const found of extraction.diagnoses ?? []) {
    const key = normalise(found.description);
    const index = diagnoses.findIndex((d) => normalise(d.description) === key);
    const row: DiagnosisRow = {
      id: index >= 0 ? diagnoses[index]!.id : newId('dx'),
      description: found.description,
      icd10Code: found.icd10_code ?? '',
      type: found.diagnosis_type,
      primary: index >= 0 ? diagnoses[index]!.primary : diagnoses.length === 0,
      source: 'DICTATION',
      sessionRef,
    };
    if (index < 0) diagnoses.push(row);
    else if (diagnoses[index]!.source === 'DICTATION') diagnoses[index] = row;
  }

  const prescriptions = [...form.prescriptions];
  for (const found of extraction.prescriptions) {
    const key = normalise(found.drug_name);
    const index = prescriptions.findIndex(
      (p) => p.matchKey === key || (key.length > 0 && normalise(p.drugText).includes(key)),
    );
    const row: PrescriptionRow = {
      id: index >= 0 ? prescriptions[index]!.id : newId('rx'),
      drugText: found.drug_text,
      dose: found.dose ?? '',
      route: asOption(ROUTES, found.route),
      frequency: asOption(FREQUENCIES, found.frequency),
      timing: asOption(TIMINGS, found.timing),
      durationDays: found.duration_days === null ? '' : String(found.duration_days),
      instructions: index >= 0 ? prescriptions[index]!.instructions : '',
      source: 'DICTATION',
      sessionRef,
      matchKey: key,
    };
    if (index < 0) prescriptions.push(row);
    else if (prescriptions[index]!.source === 'DICTATION') prescriptions[index] = row;
  }

  return { ...form, vitals, complaints, diagnoses, prescriptions };
}

/** A hand edit turns a value into the doctor's own (MANUAL), so dictation will not touch it again. */
export function editVital(form: ConsultationForm, key: VitalKey, value: string): ConsultationForm {
  return { ...form, vitals: { ...form.vitals, [key]: { value, source: 'MANUAL' } } };
}

export function editRow<T extends { id: string; source: Source; sessionRef?: string }>(
  rows: T[],
  id: string,
  patch: Partial<T>,
): T[] {
  return rows.map((row) => (row.id === id ? { ...row, ...patch, source: 'MANUAL', sessionRef: undefined } : row));
}

export function isDirty(form: ConsultationForm): boolean {
  return (
    Object.values(form.vitals).some((v) => v.value.trim() !== '') ||
    form.complaints.some((c) => c.description.trim() !== '') ||
    form.diagnoses.some((d) => d.description.trim() !== '') ||
    form.prescriptions.some((p) => p.drugText.trim() !== '') ||
    form.advice.trim() !== ''
  );
}

/** Field-level problems that would be rejected by the server. */
export function validate(form: ConsultationForm): string[] {
  const problems: string[] = [];
  for (const vital of VITALS) {
    const raw = form.vitals[vital.key].value.trim();
    if (raw === '') continue;
    const value = Number(raw);
    if (!Number.isFinite(value) || value < vital.min || value > vital.max) {
      problems.push(`${vital.label} must be between ${vital.min} and ${vital.max} ${vital.unit}`);
    }
  }
  const sys = Number(form.vitals.bpSystolic.value);
  const dia = Number(form.vitals.bpDiastolic.value);
  if (form.vitals.bpSystolic.value && form.vitals.bpDiastolic.value && sys <= dia) {
    problems.push('Systolic BP must be higher than diastolic');
  }
  form.complaints.forEach((c, i) => {
    if (c.description.trim() && (c.durationValue === '') !== (c.durationUnit === '')) {
      problems.push(`Complaint ${i + 1}: give both the duration and its unit`);
    }
  });
  form.diagnoses.forEach((d, i) => {
    if (d.description.trim() && d.icd10Code.trim() && !/^[A-Za-z]\d{2}(\.[0-9A-Za-z]{1,4})?$/.test(d.icd10Code.trim())) {
      problems.push(`Diagnosis ${i + 1}: ICD-10 code looks wrong (e.g. J06.9)`);
    }
  });
  if (form.diagnoses.filter((d) => d.description.trim() && d.primary && d.type === 'FINAL').length > 1) {
    problems.push('Only one final diagnosis can be primary');
  }
  form.prescriptions.forEach((p, i) => {
    const days = p.durationDays.trim();
    if (p.drugText.trim() && days && (!/^\d+$/.test(days) || Number(days) < 1 || Number(days) > 365)) {
      problems.push(`Prescription line ${i + 1}: days must be 1-365`);
    }
  });
  return problems;
}

/** Builds the signed-consultation payload for POST /api/v1/emr/encounters/{id}/consultation. */
export function toConsultationRequest(form: ConsultationForm, completeVisit: boolean): ConsultationRequest {
  const vitalEntries = VITALS.map((v) => [v.key, form.vitals[v.key]] as const).filter(([, f]) => f.value.trim() !== '');
  const dictated = vitalEntries.find(([, f]) => f.source === 'DICTATION');
  // One vitals row per save: it is DICTATION only if no value in it was typed by hand.
  const allDictated = vitalEntries.length > 0 && vitalEntries.every(([, f]) => f.source === 'DICTATION');

  const complaints = form.complaints
    .filter((c) => c.description.trim() !== '')
    .map((c) => ({
      description: c.description.trim(),
      durationValue: c.durationValue === '' ? undefined : Number(c.durationValue),
      durationUnit: c.durationUnit || undefined,
      chief: c.chief,
      source: c.source,
      dictationSessionRef: c.source === 'DICTATION' ? c.sessionRef : undefined,
    }));

  const diagnoses = form.diagnoses
    .filter((d) => d.description.trim() !== '')
    .map((d) => ({
      description: d.description.trim(),
      icd10Code: d.icd10Code.trim().toUpperCase() || undefined,
      type: d.type,
      primary: d.primary,
      source: d.source,
      dictationSessionRef: d.source === 'DICTATION' ? d.sessionRef : undefined,
    }));

  const items = form.prescriptions
    .filter((p) => p.drugText.trim() !== '')
    .map((p) => ({
      drugText: p.drugText.trim(),
      dose: p.dose.trim() || undefined,
      route: p.route || undefined,
      frequency: p.frequency || undefined,
      timing: p.timing || undefined,
      durationDays: p.durationDays.trim() === '' ? undefined : Number(p.durationDays),
      instructions: p.instructions.trim() || undefined,
      source: p.source,
      dictationSessionRef: p.source === 'DICTATION' ? p.sessionRef : undefined,
    }));

  return {
    dictationSessions: form.sessions,
    vitals:
      vitalEntries.length === 0
        ? undefined
        : {
            ...Object.fromEntries(vitalEntries.map(([key, f]) => [key, Number(f.value)])),
            source: allDictated ? 'DICTATION' : 'MANUAL',
            dictationSessionRef: allDictated ? dictated?.[1].sessionRef : undefined,
          },
    complaints,
    diagnoses,
    prescription:
      items.length === 0
        ? undefined
        : { items, advice: form.advice.trim() || undefined, followUpDate: form.followUpDate || undefined },
    cdssOverrides: items.length === 0 ? [] : Object.entries(form.cdssOverrides)
      .filter(([, reason]) => reason.trim().length >= 5)
      .map(([ruleCode, reason]) => ({ ruleCode, reason: reason.trim() })),
    completeVisit,
  };
}
