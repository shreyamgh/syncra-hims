// Records from other hospitals, under the patient's ABDM consent (ABDM Service, as HIU).
import { api } from './http';

export type HiType = 'OPConsultation' | 'Prescription' | 'DischargeSummary' | 'DiagnosticReport' | 'ImmunizationRecord'
  | 'HealthDocumentRecord' | 'WellnessRecord' | 'Invoice';

export const HI_TYPES: { code: HiType; label: string }[] = [
  { code: 'OPConsultation', label: 'OP consultations' },
  { code: 'Prescription', label: 'Prescriptions' },
  { code: 'DischargeSummary', label: 'Discharge summaries' },
  { code: 'DiagnosticReport', label: 'Lab & imaging reports' },
  { code: 'ImmunizationRecord', label: 'Vaccinations' },
  { code: 'HealthDocumentRecord', label: 'Health documents' },
  { code: 'WellnessRecord', label: 'Vitals & wellness' },
  { code: 'Invoice', label: 'Bills' },
];

export type ConsentStatus = 'REQUESTED' | 'GRANTED' | 'DENIED' | 'EXPIRED' | 'REVOKED' | 'FAILED';
export type TransferStatus = 'REQUESTED' | 'ACKNOWLEDGED' | 'TRANSFERRED' | 'PARTIAL' | 'FAILED';

export interface HiuTransfer {
  id: string;
  status: TransferStatus;
  requestedAt: string;
  completedAt?: string;
  records: number;
  error?: string;
}

export interface HiuArtefact {
  consentId: string;
  status: 'GRANTED' | 'REVOKED' | 'EXPIRED' | 'DENIED';
  hipId?: string;
  hipName?: string;
  hiTypes: string[];
  from?: string;
  to?: string;
  eraseAt?: string;
  detailsReceived: boolean;
  transfers: HiuTransfer[];
}

export interface HiuConsent {
  id: string;
  status: ConsentStatus;
  purpose: string;
  hiTypes: string[];
  from: string;
  to: string;
  eraseAt: string;
  requestedBy: string;
  requestedAt: string;
  error?: string;
  artefacts: HiuArtefact[];
}

export interface ExternalRecord {
  id: string;
  hipId?: string;
  hipName?: string;
  recordType?: string;
  title?: string;
  recordDate?: string;
  receivedAt: string;
  eraseAt: string;
}

export interface ExternalRecordDetail {
  record: ExternalRecord;
  summary: { title: string; date?: string; author?: string; custodian?: string; sections: { title: string; lines: string[] }[] };
}

export interface ConsentAsk {
  uhid: string;
  purpose: string;
  hiTypes: HiType[];
  from: string;
  to: string;
  keepUntil: string;
}

const enc = encodeURIComponent;

export const hiu = {
  consents: (uhid: string) => api<HiuConsent[]>(`/api/v1/abdm/hiu/patients/${enc(uhid)}/consent-requests`),
  ask: (body: ConsentAsk) => api<HiuConsent>('/api/v1/abdm/hiu/consent-requests', { method: 'POST', body }),
  records: (uhid: string) => api<ExternalRecord[]>(`/api/v1/abdm/hiu/patients/${enc(uhid)}/records`),
  record: (id: string) => api<ExternalRecordDetail>(`/api/v1/abdm/hiu/records/${enc(id)}`),
  fhir: (id: string) => api<unknown>(`/api/v1/abdm/hiu/records/${enc(id)}/fhir`),
  again: (consentId: string, uhid: string) =>
    api<HiuTransfer>(`/api/v1/abdm/hiu/consents/${enc(consentId)}/data-requests?uhid=${enc(uhid)}`, { method: 'POST' }),
};

/** "2026-11-21" plus n days, in the same form. */
export function addDays(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

/** What to tell the doctor about a request, in plain words. */
export function consentStatusText(c: HiuConsent): string {
  switch (c.status) {
    case 'REQUESTED':
      return 'Waiting for the patient to approve in their ABHA app';
    case 'GRANTED':
      return c.artefacts.length === 1 ? 'Approved for 1 hospital' : `Approved for ${c.artefacts.length} hospitals`;
    case 'DENIED':
      return 'The patient declined';
    case 'EXPIRED':
      return 'Expired; records received were erased';
    case 'REVOKED':
      return 'The patient withdrew consent; records received were erased';
    default:
      return c.error ?? 'Could not be sent to ABDM';
  }
}
