// Rules behind the Orders, Notes and Allergies screens. Kept free of React so they can be tested
// directly; the Patient Service enforces the same rules and is the authority.

import type { NewAllergy } from '../api/endpoints';
import type { Me, NoteRecord, NoteType, OrderRecord, OrderStatus, ResultFlag } from '../api/types';

// ---------------------------------------------------------------- notes

/** Note types a doctor may write (RADIOLOGY_REPORT comes only from the imaging worklist). */
const DOCTOR_TYPES: NoteType[] = ['HISTORY', 'EXAMINATION', 'PROGRESS', 'PROCEDURE', 'ADVICE', 'DISCHARGE_SUMMARY', 'OTHER'];
/** Types any clinical author may write; the rest are doctor-only on the server. */
const NURSE_TYPES: NoteType[] = ['NURSING', 'PROGRESS', 'OTHER'];

export const NOTE_TYPE_LABEL: Record<NoteType, string> = {
  HISTORY: 'History',
  EXAMINATION: 'Examination',
  PROGRESS: 'Progress note',
  PROCEDURE: 'Procedure note',
  NURSING: 'Nursing note',
  ADVICE: 'Advice',
  DISCHARGE_SUMMARY: 'Discharge summary',
  RADIOLOGY_REPORT: 'Radiology report',
  OTHER: 'Other',
};

export function noteTypesFor(me: Me | null): NoteType[] {
  if (!me?.linked) return [];
  return me.doctor ? DOCTOR_TYPES : NURSE_TYPES;
}

export type NoteState = 'DRAFT' | 'SIGNED' | 'AMENDED' | 'IN_ERROR';

export interface NoteView {
  note: NoteRecord;
  state: NoteState;
  /** The note this one corrects, when it is an amendment. */
  amends?: NoteRecord;
  /** The signed amendment that replaced this note. */
  amendedBy?: NoteRecord;
  mine: boolean;
  canEdit: boolean;
  canSign: boolean;
  canAmend: boolean;
  canMarkInError: boolean;
}

/**
 * Notes newest first, each with its state and the actions open to the signed-in author:
 * drafts are edited and signed; a signed note is corrected only by a signed amendment (of the
 * latest version); any of the author's notes can be marked entered in error.
 */
export function noteViews(notes: NoteRecord[], me: Me | null): NoteView[] {
  const byId = new Map(notes.map((n) => [n.id, n]));
  const amendmentOf = new Map<string, NoteRecord>();
  for (const n of notes) {
    if (n.amendsNoteId && n.signedAt && !n.enteredInError) amendmentOf.set(n.amendsNoteId, n);
  }
  return [...notes]
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .map((note) => {
      const amendedBy = amendmentOf.get(note.id);
      const state: NoteState = note.enteredInError ? 'IN_ERROR' : !note.signedAt ? 'DRAFT' : amendedBy ? 'AMENDED' : 'SIGNED';
      const mine = Boolean(me?.linked && me.staffId === note.authorId);
      return {
        note,
        state,
        amends: note.amendsNoteId ? byId.get(note.amendsNoteId) : undefined,
        amendedBy,
        mine,
        canEdit: mine && state === 'DRAFT',
        canSign: mine && state === 'DRAFT',
        canAmend: mine && state === 'SIGNED' && note.noteType !== 'RADIOLOGY_REPORT',
        canMarkInError: mine && state !== 'IN_ERROR',
      };
    });
}

// ---------------------------------------------------------------- orders

const CLOSED: OrderStatus[] = ['COMPLETED', 'REPORTED', 'CANCELLED'];

export const isOpenOrder = (order: OrderRecord) => !CLOSED.includes(order.status);

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  ORDERED: 'Ordered',
  SCHEDULED: 'Scheduled',
  SAMPLE_COLLECTED: 'Sample collected',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Done, awaiting report',
  REPORTED: 'Reported',
  CANCELLED: 'Cancelled',
};

export const FLAG_LABEL: Record<ResultFlag, string> = { H: 'High', L: 'Low', N: 'Normal', A: 'Abnormal', C: 'Critical' };

export const isAbnormal = (flag: ResultFlag | undefined) => flag !== undefined && flag !== 'N';

export interface OrderCounts {
  open: number;
  reported: number;
  abnormal: number;
  critical: number;
}

/** Counts for the tab badge: open orders, and reported results that are out of range or critical. */
export function orderCounts(orders: OrderRecord[]): OrderCounts {
  const reported = orders.filter((o) => o.status === 'REPORTED');
  const values = reported.flatMap((o) => o.resultValues ?? []);
  return {
    open: orders.filter(isOpenOrder).length,
    reported: reported.length,
    abnormal: reported.filter((o) => (o.resultValues ?? []).some((v) => isAbnormal(v.flag))).length,
    critical: values.filter((v) => v.flag === 'C').length,
  };
}

/** Problems that stop an order being placed (radiologists need the clinical question). */
export function orderProblems(order: { serviceCode?: string; category?: string; clinicalIndication: string }): string[] {
  const problems: string[] = [];
  if (!order.serviceCode) problems.push('Choose a test or procedure to order');
  if (order.category === 'RADIOLOGY' && !order.clinicalIndication.trim()) {
    problems.push('Give the clinical indication for the imaging study');
  }
  return problems;
}

// ---------------------------------------------------------------- allergies

export function allergyProblems(allergy: NewAllergy): string[] {
  const problems: string[] = [];
  if (!allergy.allergenText.trim()) problems.push('Name the allergen');
  if (allergy.allergenType === 'DRUG_CLASS' && !allergy.drugClass?.trim()) {
    problems.push('Name the drug class (e.g. Penicillins)');
  }
  return problems;
}

// ---------------------------------------------------------------- vaccinations

export type VaccinationMode = 'GIVEN' | 'CARD' | 'NOT_GIVEN';

export interface VaccinationDraft {
  mode: VaccinationMode;
  vaccineCode: string;
  doseNumber: string;
  /** yyyy-mm-dd: the date on the card (transcribed doses only). */
  givenOn: string;
  reportOrigin: string;
  lotNumber: string;
  expiryDate: string;
  manufacturer: string;
  route: string;
  site: string;
  doseMl: string;
  nextDueDate: string;
  notDoneReason: string;
  notes: string;
}

export const emptyVaccination = (): VaccinationDraft => ({
  mode: 'GIVEN', vaccineCode: '', doseNumber: '', givenOn: '', reportOrigin: '', lotNumber: '', expiryDate: '',
  manufacturer: '', route: '', site: '', doseMl: '', nextDueDate: '', notDoneReason: '', notes: '',
});

/** Problems the server would also refuse, in the clinician's words. `today` is yyyy-mm-dd. */
export function vaccinationProblems(d: VaccinationDraft, today: string, seriesDoses?: number): string[] {
  const problems: string[] = [];
  if (!d.vaccineCode) problems.push('Choose the vaccine');
  const dose = d.doseNumber ? Number(d.doseNumber) : undefined;
  if (dose !== undefined && seriesDoses !== undefined && dose > seriesDoses) problems.push(`This vaccine has ${seriesDoses} dose(s) in its series`);
  if (d.mode === 'GIVEN') {
    if (!d.lotNumber.trim() || !d.expiryDate) problems.push('Enter the batch (lot) number and expiry of the vial');
    else if (d.expiryDate < today) problems.push(`The vial expired on ${d.expiryDate}: it must not be given`);
    if (d.lotNumber.trim() && !/^[A-Za-z0-9./-]+$/.test(d.lotNumber.trim())) problems.push("Batch numbers use letters, digits, '.', '/' or '-'");
  }
  if (d.mode === 'CARD') {
    if (!d.givenOn) problems.push('Enter the date on the card');
    else if (d.givenOn > today) problems.push('The date on the card is in the future');
    if (!d.reportOrigin.trim()) problems.push('Say where the record comes from (e.g. MCP card)');
  }
  if (d.mode === 'NOT_GIVEN' && !d.notDoneReason.trim()) problems.push('Give the reason it was not given');
  const on = d.mode === 'CARD' ? d.givenOn : today;
  if (d.nextDueDate && on && d.nextDueDate <= on) problems.push('The next due date must be after this dose');
  return problems;
}
