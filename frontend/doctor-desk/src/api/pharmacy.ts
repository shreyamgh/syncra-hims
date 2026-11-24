// Pharmacy and stores (Pharmacy Service) and the pharmacy sale (Billing Service).
import type { Bill, PaymentMode } from './frontoffice';
import { api } from './http';

export type Schedule = 'OTC' | 'G' | 'H' | 'H1' | 'X';

export interface Drug {
  id: string;
  code: string;
  brandName: string;
  strength?: string;
  dosageForm: string;
  schedule?: Schedule;
  highAlert: boolean;
  gstRate: number;
  unit: string;
  reorderLevel: number;
  generics?: string;
  mainStore: number;
  dispensary: number;
  nearExpiry: number;
  expired: number;
  earliestExpiry?: string;
  belowReorder: boolean;
}

export interface Batch {
  batchId: string;
  drugId: string;
  drugCode: string;
  brandName: string;
  batchNumber: string;
  expiryDate: string;
  mrp: number;
  storeCode: string;
  quantity: number;
}

export interface PrescriptionItem {
  id: string;
  lineNo: number;
  drugId?: string;
  drugText: string;
  dose?: string;
  frequency?: string;
  durationDays?: number;
  quantity?: number;
}

export interface PendingPrescription {
  prescription: {
    id: string;
    uhid: string;
    patientName: string;
    gender: string;
    prescriber: string;
    prescriberRegistration?: string;
    encounterType: string;
    issuedAt: string;
  };
  ageYears?: number;
  items: { item: PrescriptionItem; suggested?: Drug; suggestedQuantity?: number }[];
}

export interface DispenseLine {
  id: string;
  prescriptionItemId?: string;
  drugCode: string;
  brandName: string;
  strength?: string;
  schedule?: Schedule;
  batchNumber: string;
  expiryDate: string;
  quantity: number;
  unit: string;
  mrp: number;
  gstRate: number;
  amount: number;
}

export interface Dispense {
  id: string;
  number: string;
  prescriptionId: string;
  uhid: string;
  patientName: string;
  prescriber: string;
  dispensedBy: string;
  dispensedAt: string;
  billId?: string;
  lines: DispenseLine[];
  total: number;
}

export interface Requisition {
  id: string;
  number: string;
  requestingStore: string;
  issuingStore: string;
  status: 'REQUESTED' | 'ISSUED' | 'PARTIAL' | 'CANCELLED';
  requestedBy: string;
  requestedAt: string;
  issuedBy?: string;
  issuedAt?: string;
  lines: { id: string; drugId: string; drugCode: string; brandName: string; requested: number; issued: number }[];
}

export interface RegisterEntry {
  dispensedAt: string;
  dispenseNumber: string;
  uhid: string;
  patientName: string;
  patientAddress?: string;
  prescriber: string;
  prescriberRegistration?: string;
  brandName: string;
  strength?: string;
  batchNumber: string;
  quantity: number;
  unit: string;
  dispensedBy: string;
}

export interface ReceiptLine {
  drugId: string;
  batchNumber: string;
  expiryDate: string;
  quantity: number;
  freeQuantity?: number;
  purchaseRate: number;
  mrp: number;
}

const enc = encodeURIComponent;

export const pharmacy = {
  drugs: (q?: string) => api<Drug[]>(`/api/v1/pharmacy/drugs${q ? `?q=${enc(q)}` : ''}`),
  batches: (drugId: string) => api<Batch[]>(`/api/v1/pharmacy/batches?drugId=${enc(drugId)}`),
  pending: () => api<PendingPrescription[]>('/api/v1/pharmacy/prescriptions'),
  dispense: (prescriptionId: string, lines: { prescriptionItemId?: string; drugId: string; quantity: number }[]) =>
    api<Dispense>('/api/v1/pharmacy/dispenses', { method: 'POST', body: { prescriptionId, lines } }),
  receive: (receipt: { supplier: string; invoiceNumber: string; invoiceDate: string; lines: ReceiptLine[] }) =>
    api<{ id: string; number: string }>('/api/v1/pharmacy/receipts', { method: 'POST', body: receipt }),
  requisitions: () => api<Requisition[]>('/api/v1/pharmacy/requisitions'),
  requisition: (lines: { drugId: string; quantity: number }[]) =>
    api<Requisition>('/api/v1/pharmacy/requisitions', { method: 'POST', body: { requestingStore: 'DISPENSARY', issuingStore: 'MAIN', lines } }),
  issue: (id: string) => api<Requisition>(`/api/v1/pharmacy/requisitions/${enc(id)}/issue`, { method: 'POST' }),
  writeOff: (store: string, batchId: string, quantity: number, reason: string) =>
    api<void>('/api/v1/pharmacy/write-offs', { method: 'POST', body: { store, batchId, quantity, reason } }),
  scheduleH1: (from: string, to: string) => api<RegisterEntry[]>(`/api/v1/pharmacy/registers/schedule-h1?from=${from}&to=${to}`),
  /** The GST invoice for a dispense, paid at the counter (the amount is the invoice total). */
  sell: (dispenseId: string, mode: PaymentMode, reference?: string) =>
    api<Bill>('/api/v1/billing/pharmacy-sales', { method: 'POST', body: { dispenseId, payment: { mode, reference } } }),
};

/** "2027-06" → "2027-06-30": a pack's expiry month is good to its last day. */
export function endOfMonth(month: string): string {
  const [y, m] = month.split('-').map(Number);
  const last = new Date(Date.UTC(y!, m!, 0)).getUTCDate();
  return `${month}-${String(last).padStart(2, '0')}`;
}

/** "2027-06-30" → "06/2027", as printed on packs. */
export const packExpiry = (date: string) => `${date.slice(5, 7)}/${date.slice(0, 4)}`;
