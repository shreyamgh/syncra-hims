// Rules behind the front-office screens; the Patient and Billing services enforce the same.
import type { BedStatus, BoardEntry, PatientDetails } from '../api/frontoffice';
import type { Gender } from '../api/types';

const MOBILE = /^[6-9]\d{9}$/;

export interface RegistrationDraft {
  title: string;
  firstName: string;
  middleName: string;
  lastName: string;
  gender: Gender | '';
  ageBy: 'DOB' | 'AGE';
  dateOfBirth: string;
  ageYears: string;
  mobile: string;
  relationType: string;
  relationName: string;
  line: string;
  cityVillage: string;
  district: string;
  state: string;
  pincode: string;
  abhaNumber: string;
}

export const emptyRegistration = (): RegistrationDraft => ({
  title: '', firstName: '', middleName: '', lastName: '', gender: '', ageBy: 'DOB', dateOfBirth: '', ageYears: '', mobile: '',
  relationType: '', relationName: '', line: '', cityVillage: '', district: '', state: '', pincode: '', abhaNumber: '',
});

const blank = (v: string) => (v.trim() ? v.trim() : undefined);

export function toPatientDetails(d: RegistrationDraft): PatientDetails {
  const address = { line: blank(d.line), cityVillage: blank(d.cityVillage), district: blank(d.district), state: blank(d.state), pincode: blank(d.pincode) };
  return {
    title: blank(d.title),
    firstName: d.firstName.trim(),
    middleName: blank(d.middleName),
    lastName: blank(d.lastName),
    gender: d.gender as Gender,
    dateOfBirth: d.ageBy === 'DOB' ? blank(d.dateOfBirth) : undefined,
    ageYears: d.ageBy === 'AGE' && d.ageYears.trim() ? Number(d.ageYears) : undefined,
    mobile: blank(d.mobile),
    relationType: blank(d.relationType),
    relationName: blank(d.relationName),
    address: Object.values(address).some(Boolean) ? address : undefined,
    abhaNumber: blank(d.abhaNumber),
  };
}


/** What stops a registration, in the receptionist's words (today is yyyy-mm-dd). */
export function registrationProblems(d: RegistrationDraft, today = new Date().toISOString().slice(0, 10)): string[] {
  const problems: string[] = [];
  if (!d.firstName.trim()) problems.push('Enter the first name');
  if (!d.gender) problems.push('Choose the sex');
  if (d.ageBy === 'DOB') {
    if (!d.dateOfBirth) problems.push('Enter the date of birth, or switch to age in years');
    else if (d.dateOfBirth > today) problems.push('The date of birth is in the future');
  } else {
    const age = Number(d.ageYears);
    if (!d.ageYears.trim() || !Number.isInteger(age) || age < 0 || age > 125) problems.push('Enter the age in whole years (0 to 125)');
  }
  if (!d.mobile.trim()) problems.push('Enter a mobile number (the patient’s or a relative’s)');
  else if (!MOBILE.test(d.mobile.trim())) problems.push('The mobile number must be 10 digits starting with 6, 7, 8 or 9');
  if (d.pincode.trim() && !/^[1-9]\d{5}$/.test(d.pincode.trim())) problems.push('The PIN code must be 6 digits');
  if (d.abhaNumber.trim() && !/^\d{2}-?\d{4}-?\d{4}-?\d{4}$/.test(d.abhaNumber.trim())) problems.push('The ABHA number has 14 digits');
  if (d.relationType && !d.relationName.trim()) problems.push('Give the name for the relation chosen');
  return problems;
}

export const BED_TONE: Record<BedStatus, string> = {
  AVAILABLE: 'border-emerald-300 bg-emerald-50 text-emerald-900',
  OCCUPIED: 'border-indigo-300 bg-white text-slate-900',
  RESERVED: 'border-amber-300 bg-amber-50 text-amber-900',
  CLEANING: 'border-sky-300 bg-sky-50 text-sky-900',
  MAINTENANCE: 'border-slate-300 bg-slate-100 text-slate-500',
  BLOCKED: 'border-slate-300 bg-slate-100 text-slate-500',
};

/** Beds grouped by ward, in board order. */
export function byWard(board: BoardEntry[]): { wardCode: string; wardName: string; beds: BoardEntry[] }[] {
  const wards = new Map<string, { wardCode: string; wardName: string; beds: BoardEntry[] }>();
  for (const bed of board) {
    const ward = wards.get(bed.wardCode) ?? { wardCode: bed.wardCode, wardName: bed.wardName, beds: [] };
    ward.beds.push(bed);
    wards.set(bed.wardCode, ward);
  }
  return [...wards.values()];
}

/** Days in hospital by the midnight census (a same-day stay counts as one). */
export function daysAdmitted(admittedAt: string, now = new Date()): number {
  const ist = (d: Date) => d.toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
  const days = Math.round((Date.parse(ist(now)) - Date.parse(ist(new Date(admittedAt)))) / 86_400_000);
  return Math.max(1, days);
}
