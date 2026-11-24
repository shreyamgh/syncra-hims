import type { Extraction } from '../dictation/protocol';
import { api } from './http';
import type {
  AllergenType, AllergyEntry, AllergySeverity, AllergyStatus, CdssCheckRequest, CdssCheckResponse, ConsultationRequest,
  CommunicationMethod, CriticalResult, Department, EncounterRecord, Icd10Code, ImagingStudy, ImmunizationRecord, Me, NewImmunization, Vaccine, NewResultValue, NoteRecord, NoteType, Orderable, OrderPriority,
  OrderRecord, OrderStatus, OrderType, Queue, ReportStatus, ResultTemplate, Visit,
} from './types';

export interface NewAllergy {
  allergenType: AllergenType;
  allergenText: string;
  drugClass?: string;
  reaction?: string;
  severity?: AllergySeverity;
}

export interface NewOrder {
  serviceCode: string;
  priority: OrderPriority;
  clinicalIndication?: string;
}

export interface NewNote {
  noteType: NoteType;
  content: string;
  sign: boolean;
  amendsNoteId?: string;
}

const encounterPath = (encounterId: string) => `/api/v1/emr/encounters/${encodeURIComponent(encounterId)}`;
const allergyPath = (uhid: string) => `/api/v1/emr/patients/${encodeURIComponent(uhid)}/allergies`;

export type VisitAction = 'call' | 'start' | 'hold' | 'requeue' | 'no-show';

export const endpoints = {
  departments: () => api<Department[]>('/api/v1/opd/departments'),
  queue: (department: string) => api<Queue>(`/api/v1/opd/queue?department=${encodeURIComponent(department)}`),
  callNext: (department: string) =>
    api<Visit>(`/api/v1/opd/queue/${encodeURIComponent(department)}/call-next`, { method: 'POST' }),
  visitAction: (visitId: string, action: VisitAction) =>
    api<Visit>(`/api/v1/opd/visits/${visitId}/${action}`, { method: 'POST' }),
  encounter: (encounterId: string) => api<EncounterRecord>(`/api/v1/emr/encounters/${encounterId}`),
  signConsultation: (encounterId: string, request: ConsultationRequest) =>
    api<EncounterRecord>(`/api/v1/emr/encounters/${encounterId}/consultation`, { method: 'POST', body: request }),
  extract: (text: string) => api<Extraction>('/api/v1/ai/extract', { method: 'POST', body: { text } }),
  cdssCheck: (request: CdssCheckRequest, signal?: AbortSignal) =>
    api<CdssCheckResponse>('/api/v1/ai/cdss/check', { method: 'POST', body: request, signal }),
  icd10: (query: string, signal?: AbortSignal) =>
    api<Icd10Code[]>(`/api/v1/ai/icd10?q=${encodeURIComponent(query)}`, { signal }),

  me: () => api<Me>('/api/v1/emr/me'),

  orderables: (query: string, type?: OrderType, signal?: AbortSignal) =>
    api<Orderable[]>(`/api/v1/emr/orderables?q=${encodeURIComponent(query)}${type ? `&type=${type}` : ''}`, { signal }),
  placeOrder: (encounterId: string, order: NewOrder) =>
    api<OrderRecord>(`${encounterPath(encounterId)}/orders`, { method: 'POST', body: order }),
  cancelOrder: (orderId: string, reason: string) =>
    api<OrderRecord>(`/api/v1/emr/orders/${encodeURIComponent(orderId)}/cancel`, { method: 'POST', body: { reason } }),

  allergies: (uhid: string) => api<AllergyEntry[]>(allergyPath(uhid)),
  addAllergy: (uhid: string, allergy: NewAllergy) => api<AllergyEntry>(allergyPath(uhid), { method: 'POST', body: allergy }),
  allergyStatus: (uhid: string, allergyId: string, status: AllergyStatus) =>
    api<AllergyEntry>(`${allergyPath(uhid)}/${encodeURIComponent(allergyId)}/status`, { method: 'PUT', body: { status } }),

  addNote: (encounterId: string, note: NewNote) =>
    api<NoteRecord>(`${encounterPath(encounterId)}/notes`, { method: 'POST', body: note }),
  editNote: (encounterId: string, note: NoteRecord, content: string) =>
    api<NoteRecord>(`${encounterPath(encounterId)}/notes/${encodeURIComponent(note.id)}`, {
      method: 'PUT', body: { content }, headers: { 'If-Match': `"${note.version}"` },
    }),
  signNote: (encounterId: string, noteId: string) =>
    api<NoteRecord>(`${encounterPath(encounterId)}/notes/${encodeURIComponent(noteId)}/sign`, { method: 'POST' }),
  // ---- image viewer (sets the HttpOnly viewer cookie for /pacs)

  openViewerSession: () => api<void>('/pacs/session', { method: 'POST', credentials: 'same-origin' }),
  closeViewerSession: () => api<void>('/pacs/session', { method: 'DELETE', credentials: 'same-origin' }),

  // ---- critical results

  criticalResults: (filter: { communicated?: boolean; acknowledged?: boolean; orderedByMe?: boolean }) => {
    const params = new URLSearchParams();
    Object.entries(filter).forEach(([k, v]) => v !== undefined && params.set(k, String(v)));
    return api<CriticalResult[]>(`/api/v1/emr/critical-results?${params}`);
  },
  communicateCritical: (id: string, call: { communicatedTo: string; method: CommunicationMethod; readBack: boolean }) =>
    api<CriticalResult>(`/api/v1/emr/critical-results/${encodeURIComponent(id)}/communicate`, { method: 'POST', body: call }),
  acknowledgeCritical: (id: string, note?: string) =>
    api<CriticalResult>(`/api/v1/emr/critical-results/${encodeURIComponent(id)}/acknowledge`, { method: 'POST', body: { note } }),

  // ---- immunizations

  vaccines: () => api<Vaccine[]>('/api/v1/emr/vaccines'),
  immunizations: (uhid: string) =>
    api<{ uhid: string; immunizations: ImmunizationRecord[] }>(`/api/v1/emr/patients/${encodeURIComponent(uhid)}/immunizations`),
  recordImmunization: (encounterId: string, dose: NewImmunization) =>
    api<ImmunizationRecord>(`${encounterPath(encounterId)}/immunizations`, { method: 'POST', body: dose }),
  immunizationInError: (encounterId: string, immunizationId: string, reason: string) =>
    api<ImmunizationRecord>(`${encounterPath(encounterId)}/immunizations/${encodeURIComponent(immunizationId)}/entered-in-error`,
      { method: 'POST', body: { reason } }),

  // ---- worklists (laboratory and imaging)

  worklist: (type: OrderType, statuses: OrderStatus[], since?: string) =>
    api<OrderRecord[]>(`/api/v1/emr/worklist?type=${type}&status=${statuses.join(',')}${since ? `&since=${encodeURIComponent(since)}` : ''}`),
  resultTemplate: (orderId: string) => api<ResultTemplate>(`/api/v1/emr/orders/${encodeURIComponent(orderId)}/analytes`),
  collectSample: (orderId: string) =>
    api<OrderRecord>(`/api/v1/emr/orders/${encodeURIComponent(orderId)}/sample`, { method: 'POST', body: {} }),
  reportResult: (orderId: string, result: { resultSummary?: string; values: NewResultValue[] }) =>
    api<OrderRecord>(`/api/v1/emr/orders/${encodeURIComponent(orderId)}/result`, { method: 'POST', body: result }),

  studies: (filter: { reportStatus?: ReportStatus[]; matched?: boolean; reportedSince?: string }) => {
    const params = new URLSearchParams();
    filter.reportStatus?.forEach((s) => params.append('reportStatus', s));
    if (filter.matched !== undefined) params.set('matched', String(filter.matched));
    if (filter.reportedSince) params.set('reportedSince', filter.reportedSince);
    return api<ImagingStudy[]>(`/api/v1/emr/imaging/studies?${params}`);
  },
  matchStudy: (studyId: string, orderId: string) =>
    api<ImagingStudy>(`/api/v1/emr/imaging/studies/${encodeURIComponent(studyId)}/match`, { method: 'POST', body: { orderId } }),
  reportStudy: (studyId: string, report: { content: string; impression?: string }) =>
    api<ImagingStudy>(`/api/v1/emr/imaging/studies/${encodeURIComponent(studyId)}/report`, { method: 'POST', body: report }),

  noteInError: (encounterId: string, noteId: string) =>
    api<NoteRecord>(`${encounterPath(encounterId)}/notes/${encodeURIComponent(noteId)}/entered-in-error`, { method: 'POST' }),
};
