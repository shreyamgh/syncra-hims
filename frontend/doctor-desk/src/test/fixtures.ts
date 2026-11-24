import type { ExtractedVitals, Extraction } from '../dictation/protocol';

export function extraction(partial: Omit<Partial<Extraction>, 'vitals'> & { vitals?: Partial<ExtractedVitals> } = {}): Extraction {
  return {
    vitals: {
      temperature_c: null, pulse_bpm: null, respiratory_rate: null, bp_systolic: null, bp_diastolic: null,
      spo2_percent: null, weight_kg: null, height_cm: null, blood_glucose_mg_dl: null,
      ...partial.vitals,
    },
    complaints: partial.complaints ?? [],
    diagnoses: partial.diagnoses ?? [],
    prescriptions: partial.prescriptions ?? [],
    warnings: partial.warnings ?? [],
  };
}

export const PARACETAMOL = {
  drug_text: 'Tab Paracetamol 650 mg', drug_name: 'Paracetamol', dosage_form: 'TABLET', strength: '650 mg',
  dose: '1 tab', route: 'ORAL', frequency: 'BD', timing: 'AFTER_FOOD', duration_days: 5,
};

/** Minimal WebSocket double driven by the test. */
export class FakeWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.CONNECTING;
  binaryType = 'blob';
  sent: (string | ArrayBuffer)[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => unknown) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this);
  }

  send(data: string | ArrayBuffer) {
    this.sent.push(data);
  }

  close(code = 1000) {
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.({ code });
  }

  open() {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.();
  }

  async receive(message: object) {
    await this.onmessage?.({ data: JSON.stringify(message) });
  }

  json(index: number) {
    return JSON.parse(this.sent[index] as string);
  }
}
