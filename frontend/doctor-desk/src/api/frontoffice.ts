// Front office: registration, OPD tokens, admissions (bed board) and billing.
import { api } from './http';
import type { Gender, Priority, Visit } from './types';

// ---------------------------------------------------------------- patients

export interface PatientSummary {
  uhid: string;
  fullName: string;
  gender: Gender;
  ageYears?: number;
  maskedMobile?: string;
  abhaLinked: boolean;
  deceased: boolean;
  registeredOn: string;
}

export interface Address {
  line?: string;
  cityVillage?: string;
  district?: string;
  state?: string;
  pincode?: string;
}

export interface PatientDetails {
  title?: string;
  firstName: string;
  middleName?: string;
  lastName?: string;
  gender: Gender;
  dateOfBirth?: string;
  ageYears?: number;
  bloodGroup?: string;
  relationType?: string;
  relationName?: string;
  mobile?: string;
  email?: string;
  address?: Address;
  abhaNumber?: string;
  abhaAddress?: string;
  emergencyContact?: { name?: string; mobile?: string };
}

export interface Patient extends PatientDetails {
  id: string;
  uhid: string;
  fullName: string;
  dobEstimated: boolean;
  abhaVerified: boolean;
  deceased: boolean;
  registeredAt: string;
  version: number;
}

export type VisitType = 'NEW' | 'FOLLOW_UP' | 'REFERRAL' | 'EMERGENCY';

export interface Doctor {
  id: string;
  name: string;
  councilRegistration?: string;
}

// ---------------------------------------------------------------- IPD

export type BedStatus = 'AVAILABLE' | 'RESERVED' | 'OCCUPIED' | 'CLEANING' | 'MAINTENANCE' | 'BLOCKED';
export type BedCategory = 'GENERAL' | 'SEMI_PRIVATE' | 'PRIVATE' | 'ICU';
export type AdmissionType = 'ELECTIVE' | 'EMERGENCY' | 'MATERNITY' | 'TRANSFER_IN' | 'DAYCARE';
export type DischargeOutcome = 'DISCHARGED' | 'LAMA' | 'ABSCONDED' | 'EXPIRED' | 'TRANSFERRED_OUT';

export interface WardCensus {
  wardCode: string;
  wardName: string;
  wardType: string;
  genderRestriction?: string;
  total: number;
  available: number;
  occupied: number;
  reserved: number;
  cleaning: number;
  outOfService: number;
}

export interface BoardEntry {
  wardCode: string;
  wardName: string;
  wardType: string;
  bedCode: string;
  bedCategory: BedCategory;
  bedStatus: BedStatus;
  occupant?: {
    admissionId: string;
    admissionNumber: string;
    uhid: string;
    patientName: string;
    gender: Gender;
    ageYears?: number;
    admittedAt: string;
    admittingDoctor: string;
  };
}

export interface AvailableBed {
  bedCode: string;
  wardCode: string;
  wardName: string;
  bedCategory: BedCategory;
  hasOxygen: boolean;
  hasMonitor: boolean;
  hasVentilator: boolean;
}

export interface Admission {
  admissionId: string;
  admissionNumber: string;
  encounterId: string;
  uhid: string;
  patientName: string;
  gender: Gender;
  ageYears?: number;
  departmentCode: string;
  departmentName: string;
  admittingDoctor: { id: string; name: string };
  admissionType: AdmissionType;
  status: string;
  admittedAt: string;
  provisionalDiagnosis?: string;
  expectedDischargeDate?: string;
  currentBed?: { bedCode: string; wardCode: string; wardName: string; bedCategory: BedCategory; assignedAt: string };
  dischargeInitiatedAt?: string;
  dischargedAt?: string;
}

export interface AdmitRequest {
  uhid: string;
  departmentCode: string;
  admittingDoctorId: string;
  admissionType: AdmissionType;
  bedCode?: string;
  tariffPlanCode?: string;
  provisionalDiagnosis?: string;
  attendant?: { name?: string; relation?: string; mobile?: string };
  medicoLegal?: boolean;
}

// ---------------------------------------------------------------- billing

export type PaymentMode = 'CASH' | 'CARD' | 'UPI' | 'NET_BANKING' | 'CHEQUE' | 'DEMAND_DRAFT' | 'INSURANCE' | 'GOVT_SCHEME' | 'CORPORATE_CREDIT';

export interface PaymentInput {
  mode: PaymentMode;
  amount: number;
  reference?: string;
  cardLast4?: string;
  remarks?: string;
}

export interface BillLine {
  lineId: string;
  serviceCode: string;
  category: string;
  description: string;
  serviceDate?: string;
  quantity: number;
  unitPrice: number;
  grossAmount: number;
  discountAmount: number;
  gstRate: number;
  taxAmount: number;
  lineTotal: number;
  /** Lines the system keeps (admission, bed-days, nursing) cannot be removed by hand. */
  automatic: boolean;
}

export interface Receipt {
  paymentId: string;
  receiptNumber: string;
  type: string;
  mode: PaymentMode;
  amount: number;
  reference?: string;
  receivedAt: string;
  receivedBy: string;
  status: string;
}

export interface Bill {
  billId: string;
  billNumber?: string;
  title: string;
  billType: 'OPD' | 'IPD' | 'PHARMACY' | 'EMERGENCY';
  status: 'DRAFT' | 'INTERIM' | 'FINAL' | 'CANCELLED';
  patient: { uhid: string; name: string; gender: string; ageYears?: number; address?: string };
  tariffPlan: string;
  encounterId?: string;
  admissionId?: string;
  admissionNumber?: string;
  lines: BillLine[];
  totals: { gross: number; discount: number; tax: number; net: number; roundOff: number; payable: number; received: number; refunded: number; balanceDue: number };
  receipts: Receipt[];
  unallocatedAdvance?: number;
  createdAt: string;
  finalizedAt?: string;
  finalizedBy?: string;
  cancelledAt?: string;
}

export interface BillSummary {
  billId: string;
  billNumber?: string;
  billType: 'OPD' | 'IPD' | 'PHARMACY' | 'EMERGENCY';
  status: 'DRAFT' | 'INTERIM' | 'FINAL' | 'CANCELLED';
  payable: number;
  createdAt: string;
}

export interface TariffPlan {
  id: string;
  code: string;
  name: string;
  payerType: string;
  isDefault: boolean;
}

export interface Rate {
  serviceCode: string;
  serviceName: string;
  category: string;
  amount: number;
}

const enc = encodeURIComponent;

export const frontOffice = {
  searchPatients: (q: string, signal?: AbortSignal) => api<PatientSummary[]>(`/api/v1/patients?q=${enc(q)}&size=20`, { signal }),
  patient: (uhid: string) => api<Patient>(`/api/v1/patients/${enc(uhid)}`),
  register: (details: PatientDetails, confirmNotDuplicate = false) =>
    api<Patient>(`/api/v1/patients${confirmNotDuplicate ? '?confirmNotDuplicate=true' : ''}`, { method: 'POST', body: details }),

  doctors: (departmentCode: string) => api<Doctor[]>(`/api/v1/opd/departments/${enc(departmentCode)}/doctors`),
  newVisit: (visit: { uhid: string; departmentCode: string; doctorId?: string; visitType: VisitType; priority?: Priority; referredBy?: string; medicoLegal?: boolean }) =>
    api<Visit>('/api/v1/opd/visits', { method: 'POST', body: visit }),

  wards: () => api<WardCensus[]>('/api/v1/ipd/wards'),
  board: () => api<BoardEntry[]>('/api/v1/ipd/beds'),
  availableBeds: (gender?: Gender) => api<AvailableBed[]>(`/api/v1/ipd/beds/available${gender ? `?gender=${gender}` : ''}`),
  admissions: () => api<Admission[]>('/api/v1/ipd/admissions'),
  admit: (request: AdmitRequest) => api<Admission>('/api/v1/ipd/admissions', { method: 'POST', body: request }),
  moveBed: (admissionId: string, bedCode: string, reason: 'TRANSFER' | 'UPGRADE' | 'DOWNGRADE') =>
    api<Admission>(`/api/v1/ipd/admissions/${enc(admissionId)}/bed`, { method: 'POST', body: { bedCode, reason } }),
  initiateDischarge: (admissionId: string) =>
    api<Admission>(`/api/v1/ipd/admissions/${enc(admissionId)}/discharge-initiation`, { method: 'POST' }),
  discharge: (admissionId: string, outcome: DischargeOutcome) =>
    api<Admission>(`/api/v1/ipd/admissions/${enc(admissionId)}/discharge`, { method: 'POST', body: { outcome } }),

  tariffs: () => api<TariffPlan[]>('/api/v1/billing/tariffs'),
  rates: (planCode: string, bedCategory = 'ALL') => api<Rate[]>(`/api/v1/billing/tariffs/${enc(planCode)}/rates?bedCategory=${bedCategory}`),
  opdSlip: (visitId: string, payment?: PaymentInput) => api<Bill>('/api/v1/billing/opd-slips', { method: 'POST', body: { visitId, payment } }),
  bills: (uhid: string) => api<BillSummary[]>(`/api/v1/billing/bills?uhid=${enc(uhid)}`),
  bill: (billId: string) => api<Bill>(`/api/v1/billing/bills/${enc(billId)}`),
  admissionBill: (admissionId: string) => api<Bill>(`/api/v1/billing/admissions/${enc(admissionId)}/bill`),
  refreshBill: (admissionId: string) => api<Bill>(`/api/v1/billing/admissions/${enc(admissionId)}/bill/refresh`, { method: 'POST' }),
  finalizeBill: (admissionId: string) => api<Bill>(`/api/v1/billing/admissions/${enc(admissionId)}/bill/finalize`, { method: 'POST' }),
  advance: (admissionId: string, payment: PaymentInput) =>
    api<unknown>(`/api/v1/billing/admissions/${enc(admissionId)}/advances`, { method: 'POST', body: { payment } }),
  addLine: (billId: string, serviceCode: string, quantity: number) =>
    api<Bill>(`/api/v1/billing/bills/${enc(billId)}/lines`, { method: 'POST', body: { serviceCode, quantity } }),
  removeLine: (billId: string, lineId: string) =>
    api<Bill>(`/api/v1/billing/bills/${enc(billId)}/lines/${enc(lineId)}`, { method: 'DELETE' }),
  pay: (billId: string, type: 'SETTLEMENT' | 'REFUND', payment: PaymentInput) =>
    api<unknown>(`/api/v1/billing/bills/${enc(billId)}/payments`, { method: 'POST', body: { type, payment } }),
};

/** ₹1,23,456.50: rupees with Indian digit grouping. */
export const rupees = (amount: number | undefined | null) =>
  amount === undefined || amount === null ? '' : amount.toLocaleString('en-IN', { style: 'currency', currency: 'INR' });

/** Opens a bill's print page (/print.html fetches it with this tab's sign-in) in a new tab. */
export function printBill(billId: string): void {
  window.open(`/print.html#${encodeURIComponent(billId)}`, '_blank');
}
