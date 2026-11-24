// Rules behind the laboratory and imaging desks. The Patient Service is the authority: it flags
// lab values itself, so the flag shown while typing is a preview computed the same way.

import type { AnalyteTemplate, NewResultValue, ResultFlag, ResultTemplate } from '../api/types';

/** Which desks a signed-in user can open, by role. */
export type DeskId = 'doctor' | 'lab' | 'imaging' | 'front' | 'pharmacy' | 'admin';

export const DESKS: { id: DeskId; label: string; roles: string[] }[] = [
  { id: 'doctor', label: 'Doctor Desk', roles: ['DOCTOR', 'NURSE'] },
  { id: 'lab', label: 'Laboratory', roles: ['LAB_TECHNICIAN', 'NURSE'] },
  { id: 'imaging', label: 'Imaging', roles: ['RADIOGRAPHER', 'RADIOLOGIST'] },
  { id: 'front', label: 'Front office', roles: ['RECEPTIONIST', 'BILLING_CLERK', 'ADMINISTRATOR'] },
  { id: 'pharmacy', label: 'Pharmacy', roles: ['PHARMACIST'] },
  { id: 'admin', label: 'Administration', roles: ['ADMINISTRATOR'] },
];

export function desksFor(roles: string[]): DeskId[] {
  return DESKS.filter((d) => d.roles.some((r) => roles.includes(r))).map((d) => d.id);
}

// ---------------------------------------------------------------- lab results

/** What the technician chose for a row: let the ranges decide, or a flag by hand. */
export type FlagChoice = 'AUTO' | '' | ResultFlag;

export interface ResultRow {
  analyte: AnalyteTemplate;
  value: string;
  flag: FlagChoice;
}

const SEVERITY: Record<ResultFlag, number> = { N: 0, A: 1, H: 1, L: 1, C: 2 };

export const hasRange = (a: AnalyteTemplate) =>
  a.low !== undefined || a.high !== undefined || a.criticalLow !== undefined || a.criticalHigh !== undefined;

export function emptyRows(template: ResultTemplate): ResultRow[] {
  return template.analytes.map((analyte) => ({ analyte, value: '', flag: hasRange(analyte) ? 'AUTO' : '' }));
}

/** The flag the ranges give a value: as the server computes it (bounds inclusive). */
export function rangeFlag(analyte: AnalyteTemplate, value: number): ResultFlag | undefined {
  if (!hasRange(analyte)) return undefined;
  if ((analyte.criticalLow !== undefined && value < analyte.criticalLow)
    || (analyte.criticalHigh !== undefined && value > analyte.criticalHigh)) return 'C';
  if (analyte.low !== undefined && value < analyte.low) return 'L';
  if (analyte.high !== undefined && value > analyte.high) return 'H';
  return 'N';
}

const parse = (text: string) => (text.trim() === '' ? undefined : Number(text.trim()));

/** The flag that will be stored: computed, unless a hand-chosen flag is more severe. */
export function effectiveFlag(row: ResultRow): ResultFlag | undefined {
  const value = parse(row.value);
  const computed = value !== undefined && Number.isFinite(value) ? rangeFlag(row.analyte, value) : undefined;
  const chosen = row.flag === 'AUTO' || row.flag === '' ? undefined : row.flag;
  if (!computed) return chosen;
  if (!chosen) return computed;
  return SEVERITY[chosen] > SEVERITY[computed] ? chosen : computed;
}

export function resultProblems(rows: ResultRow[], summary: string): string[] {
  const problems: string[] = [];
  for (const row of rows) {
    const value = parse(row.value);
    if (value !== undefined && !Number.isFinite(value)) problems.push(`${row.analyte.name}: enter a number`);
  }
  if (!rows.some((r) => r.value.trim() !== '') && !summary.trim()) {
    problems.push('Enter at least one value or a result summary');
  }
  return problems;
}

/** The values to send: entered rows only; "Auto" sends no flag so the server decides. */
export function resultValues(rows: ResultRow[]): NewResultValue[] {
  return rows.filter((r) => r.value.trim() !== '').map((r) => ({
    code: r.analyte.code,
    name: r.analyte.name,
    value: Number(r.value.trim()),
    unit: r.analyte.unit,
    flag: r.flag === 'AUTO' || r.flag === '' ? undefined : r.flag,
  }));
}

/** "Potassium 6.5 mmol/L" for each value that will be stored as critical. */
export function criticalValues(rows: ResultRow[]): string[] {
  return rows
    .filter((r) => r.value.trim() !== '' && effectiveFlag(r) === 'C')
    .map((r) => `${r.analyte.name} ${r.value.trim()}${r.analyte.unit ? ` ${r.analyte.unit}` : ''}`);
}
