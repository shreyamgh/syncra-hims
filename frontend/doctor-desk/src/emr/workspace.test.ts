import { describe, expect, it } from 'vitest';
import type { Me, NoteRecord, OrderRecord } from '../api/types';
import {
  allergyProblems, emptyVaccination, noteTypesFor, noteViews, orderCounts, orderProblems, vaccinationProblems,
} from './workspace';

const ME: Me = { linked: true, staffId: 'staff-me', name: 'Dr Asha Menon', staffRole: 'DOCTOR', doctor: true };

function note(id: string, minute: number, patch: Partial<NoteRecord> = {}): NoteRecord {
  return {
    id, encounterId: 'enc-1', noteType: 'PROGRESS', content: `note ${id}`, authorId: 'staff-me', author: 'Dr Asha Menon',
    signedAt: `2026-11-20T10:${String(minute).padStart(2, '0')}:00Z`, enteredInError: false, version: 1,
    createdAt: `2026-11-20T10:${String(minute).padStart(2, '0')}:00Z`, ...patch,
  };
}

function order(id: string, patch: Partial<OrderRecord> = {}): OrderRecord {
  return {
    id, encounterId: 'enc-1', serviceCode: 'LAB-RFT', serviceName: 'Renal Function Test', orderType: 'LABORATORY',
    status: 'ORDERED', priority: 'ROUTINE', orderedBy: 'Dr Asha Menon', orderedAt: '2026-11-20T10:00:00Z', ...patch,
  };
}

describe('notes', () => {
  it('lets the author edit and sign a draft, and amend only the latest signed version', () => {
    const views = noteViews([
      note('original', 1),
      note('amendment', 5, { amendsNoteId: 'original' }),
      note('draft', 9, { signedAt: undefined }),
    ], ME);

    expect(views.map((v) => v.note.id)).toEqual(['draft', 'amendment', 'original']);
    const [draft, amendment, original] = views;
    expect(draft).toMatchObject({ state: 'DRAFT', canEdit: true, canSign: true, canAmend: false });
    expect(amendment).toMatchObject({ state: 'SIGNED', canEdit: false, canAmend: true });
    expect(amendment!.amends?.id).toBe('original');
    expect(original).toMatchObject({ state: 'AMENDED', canAmend: false, canMarkInError: true });
    expect(original!.amendedBy?.id).toBe('amendment');
  });

  it("gives no actions on someone else's note, and an amendment in error does not replace the original", () => {
    const views = noteViews([
      note('theirs', 1, { authorId: 'staff-other', author: 'Sr Mary' }),
      note('bad-amendment', 3, { authorId: 'staff-other', amendsNoteId: 'theirs', enteredInError: true }),
    ], ME);
    const theirs = views.find((v) => v.note.id === 'theirs')!;
    expect(theirs).toMatchObject({ state: 'SIGNED', mine: false, canEdit: false, canAmend: false, canMarkInError: false });
    expect(views.find((v) => v.note.id === 'bad-amendment')!.state).toBe('IN_ERROR');
  });

  it('offers note types by staff role, and none without a staff record', () => {
    expect(noteTypesFor(ME)).toContain('HISTORY');
    expect(noteTypesFor(ME)).not.toContain('RADIOLOGY_REPORT');
    const nurse = noteTypesFor({ ...ME, doctor: false, staffRole: 'NURSE' });
    expect(nurse).toContain('NURSING');
    expect(nurse).not.toContain('HISTORY');
    expect(noteTypesFor({ linked: false, doctor: false })).toEqual([]);
    expect(noteTypesFor(null)).toEqual([]);
    // Without a staff record nothing is "mine".
    expect(noteViews([note('a', 1)], { linked: false, doctor: false })[0]!.canEdit).toBe(false);
  });
});

describe('orders', () => {
  it('counts open orders and abnormal and critical results', () => {
    const counts = orderCounts([
      order('1'),
      order('2', { status: 'SAMPLE_COLLECTED' }),
      order('3', { status: 'CANCELLED' }),
      order('4', {
        status: 'REPORTED', resultValues: [
          { code: 'CREAT', name: 'Creatinine', value: 2.4, flag: 'H' },
          { code: 'KSERUM', name: 'Potassium', value: 6.9, flag: 'C' },
        ],
      }),
      order('5', { status: 'REPORTED', resultValues: [{ code: 'HB', name: 'Haemoglobin', value: 13, flag: 'N' }] }),
    ]);
    expect(counts).toEqual({ open: 2, reported: 2, abnormal: 1, critical: 1 });
  });

  it('needs a test, and a clinical indication for imaging', () => {
    expect(orderProblems({ clinicalIndication: '' })).toEqual(['Choose a test or procedure to order']);
    expect(orderProblems({ serviceCode: 'LAB-CBC', category: 'LABORATORY', clinicalIndication: '' })).toEqual([]);
    expect(orderProblems({ serviceCode: 'RAD-XCH', category: 'RADIOLOGY', clinicalIndication: '  ' }))
      .toEqual(['Give the clinical indication for the imaging study']);
  });
});

describe('allergies', () => {
  it('needs an allergen, and the class for a drug-class allergy', () => {
    expect(allergyProblems({ allergenType: 'DRUG', allergenText: ' ' })).toEqual(['Name the allergen']);
    expect(allergyProblems({ allergenType: 'DRUG_CLASS', allergenText: 'Penicillin', drugClass: '' }))
      .toEqual(['Name the drug class (e.g. Penicillins)']);
    expect(allergyProblems({ allergenType: 'FOOD', allergenText: 'Peanut' })).toEqual([]);
  });
});

describe('vaccinations', () => {
  const base = { ...emptyVaccination(), vaccineCode: 'PENTA' };
  it('needs batch and an in-date vial for a dose given here', () => {
    expect(vaccinationProblems(base, '2026-11-21')).toEqual(['Enter the batch (lot) number and expiry of the vial']);
    expect(vaccinationProblems({ ...base, lotNumber: 'B-123', expiryDate: '2026-11-20' }, '2026-11-21'))
      .toEqual(['The vial expired on 2026-11-20: it must not be given']);
    expect(vaccinationProblems({ ...base, lotNumber: 'B 123', expiryDate: '2027-01-01' }, '2026-11-21'))
      .toEqual(["Batch numbers use letters, digits, '.', '/' or '-'"]);
    expect(vaccinationProblems({ ...base, lotNumber: 'B-123', expiryDate: '2027-01-01', doseNumber: '4' }, '2026-11-21', 3))
      .toEqual(['This vaccine has 3 dose(s) in its series']);
  });

  it('needs a past date and its source for a dose from the card, and a reason when not given', () => {
    expect(vaccinationProblems({ ...base, mode: 'CARD' }, '2026-11-21'))
      .toEqual(['Enter the date on the card', 'Say where the record comes from (e.g. MCP card)']);
    expect(vaccinationProblems({ ...base, mode: 'CARD', givenOn: '2026-12-01', reportOrigin: 'MCP card' }, '2026-11-21'))
      .toEqual(['The date on the card is in the future']);
    expect(vaccinationProblems({ ...base, mode: 'NOT_GIVEN' }, '2026-11-21')).toEqual(['Give the reason it was not given']);
    expect(vaccinationProblems({ ...base, lotNumber: 'B1', expiryDate: '2027-01-01', nextDueDate: '2026-11-21' }, '2026-11-21'))
      .toEqual(['The next due date must be after this dose']);
  });
});
