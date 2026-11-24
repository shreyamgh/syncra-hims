// Wire protocol of the AI service's /ws/ai/dictation endpoint (snake_case JSON).
// Source of truth: services/ai-service/app/dictation/protocol.py

export type Language = 'en-IN' | 'hi-IN';

export interface ExtractedVitals {
  temperature_c: number | null;
  pulse_bpm: number | null;
  respiratory_rate: number | null;
  bp_systolic: number | null;
  bp_diastolic: number | null;
  spo2_percent: number | null;
  weight_kg: number | null;
  height_cm: number | null;
  blood_glucose_mg_dl: number | null;
}

export interface ExtractedComplaint {
  description: string;
  duration_value: number | null;
  duration_unit: 'HOURS' | 'DAYS' | 'WEEKS' | 'MONTHS' | 'YEARS' | null;
}

export interface ExtractedPrescription {
  drug_text: string;
  drug_name: string;
  dosage_form: string | null;
  strength: string | null;
  dose: string | null;
  route: string | null;
  frequency: string | null;
  timing: string | null;
  duration_days: number | null;
}

export interface ExtractedDiagnosis {
  description: string;
  diagnosis_type: 'PROVISIONAL' | 'FINAL';
  icd10_code: string | null;
  icd10_title: string | null;
}

export interface Extraction {
  vitals: ExtractedVitals;
  complaints: ExtractedComplaint[];
  /** Older AI service versions do not send diagnoses. */
  diagnoses?: ExtractedDiagnosis[];
  prescriptions: ExtractedPrescription[];
  warnings: string[];
}

export type ServerMessage =
  | { type: 'ready'; session_id: string; engine: string }
  | { type: 'partial'; text: string; extraction: Extraction }
  | {
      type: 'final';
      session_id: string;
      text: string;
      audio_seconds: number;
      engine: string;
      language: Language;
      extraction: Extraction;
    }
  | { type: 'error'; code: string; message: string };

export const SAMPLE_RATE = 16_000;
