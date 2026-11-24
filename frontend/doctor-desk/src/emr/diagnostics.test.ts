import { describe, expect, it } from 'vitest';
import type { AnalyteTemplate } from '../api/types';
import {
  type ResultRow, criticalValues, desksFor, effectiveFlag, rangeFlag, resultProblems, resultValues,
} from './diagnostics';

const POTASSIUM: AnalyteTemplate = {
  code: 'POTASSIUM', name: 'Potassium', unit: 'mmol/L', decimals: 1, low: 3.5, high: 5.1, criticalLow: 2.8, criticalHigh: 6.2,
  referenceRange: '3.5-5.1',
};
const HB_CHILD: AnalyteTemplate = { code: 'HB', name: 'Haemoglobin', unit: 'g/dL', decimals: 1 };

const row = (analyte: AnalyteTemplate, value: string, flag: ResultRow['flag'] = 'AUTO'): ResultRow => ({ analyte, value, flag });

describe('desks', () => {
  it('opens the desks that match the roles', () => {
    expect(desksFor(['DOCTOR'])).toEqual(['doctor']);
    expect(desksFor(['NURSE'])).toEqual(['doctor', 'lab']);
    expect(desksFor(['LAB_TECHNICIAN'])).toEqual(['lab']);
    expect(desksFor(['RADIOGRAPHER'])).toEqual(['imaging']);
    expect(desksFor(['BILLING_CLERK'])).toEqual(['front']);
    expect(desksFor(['RECEPTIONIST'])).toEqual(['front']);
    expect(desksFor(['PHARMACIST'])).toEqual(['pharmacy']);
    expect(desksFor(['MRD_OFFICER'])).toEqual([]);
    expect(desksFor(['ADMINISTRATOR'])).toEqual(['front', 'admin']);
  });
});

describe('lab result flags', () => {
  it('flags like the server: inclusive bounds, critical beyond the limits', () => {
    expect(rangeFlag(POTASSIUM, 5.1)).toBe('N');
    expect(rangeFlag(POTASSIUM, 5.2)).toBe('H');
    expect(rangeFlag(POTASSIUM, 6.2)).toBe('H');
    expect(rangeFlag(POTASSIUM, 6.3)).toBe('C');
    expect(rangeFlag(POTASSIUM, 3.4)).toBe('L');
    expect(rangeFlag(POTASSIUM, 2.7)).toBe('C');
    expect(rangeFlag(HB_CHILD, 9)).toBeUndefined();
  });

  it('lets a hand-chosen flag raise the computed one but never lower it', () => {
    expect(effectiveFlag(row(POTASSIUM, '6.5', 'N'))).toBe('C');
    expect(effectiveFlag(row(POTASSIUM, '5.5', 'C'))).toBe('C');
    expect(effectiveFlag(row(POTASSIUM, '4.0'))).toBe('N');
    expect(effectiveFlag(row(POTASSIUM, ''))).toBeUndefined();
    // No range (a child): only the hand-chosen flag.
    expect(effectiveFlag(row(HB_CHILD, '9.1', ''))).toBeUndefined();
    expect(effectiveFlag(row(HB_CHILD, '9.1', 'L'))).toBe('L');
  });

  it('sends entered values only, leaving "Auto" flags for the server to set', () => {
    const rows = [row(POTASSIUM, ' 6.5 '), row(HB_CHILD, '9.1', 'L'), row({ ...POTASSIUM, code: 'SODIUM', name: 'Sodium' }, '')];
    expect(resultValues(rows)).toEqual([
      { code: 'POTASSIUM', name: 'Potassium', value: 6.5, unit: 'mmol/L', flag: undefined },
      { code: 'HB', name: 'Haemoglobin', value: 9.1, unit: 'g/dL', flag: 'L' },
    ]);
    expect(criticalValues(rows)).toEqual(['Potassium 6.5 mmol/L']);
  });

  it('needs numbers, and at least one value or a summary', () => {
    expect(resultProblems([row(POTASSIUM, '')], '')).toEqual(['Enter at least one value or a result summary']);
    expect(resultProblems([row(POTASSIUM, '')], 'Haemolysed sample, repeat')).toEqual([]);
    expect(resultProblems([row(POTASSIUM, '4,5')], '')).toEqual(['Potassium: enter a number']);
  });
});
