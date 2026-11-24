import { describe, expect, it } from 'vitest';
import { extraction, PARACETAMOL } from '../test/fixtures';
import { applyExtraction, editRow, editVital, emptyForm, isDirty, toConsultationRequest, validate } from './form';

describe('applyExtraction', () => {
  it('fills empty fields and marks them as dictated', () => {
    const form = applyExtraction(emptyForm(), extraction({
      vitals: { bp_systolic: 130, bp_diastolic: 80 },
      complaints: [{ description: 'Fever', duration_value: 3, duration_unit: 'DAYS' }],
      prescriptions: [PARACETAMOL],
    }), 's1');

    expect(form.vitals.bpSystolic).toEqual({ value: '130', source: 'DICTATION', sessionRef: 's1' });
    expect(form.complaints[0]).toMatchObject({ description: 'Fever', durationValue: '3', durationUnit: 'DAYS', chief: true, source: 'DICTATION' });
    expect(form.prescriptions[0]).toMatchObject({ drugText: 'Tab Paracetamol 650 mg', frequency: 'BD', timing: 'AFTER_FOOD', durationDays: '5', route: 'ORAL' });
  });

  it('never overwrites a value the doctor typed', () => {
    let form = editVital(emptyForm(), 'pulseBpm', '88');
    form = applyExtraction(form, extraction({ vitals: { pulse_bpm: 96 } }), 's1');
    expect(form.vitals.pulseBpm).toEqual({ value: '88', source: 'MANUAL' });
  });

  it('updates its own rows as the transcript grows instead of duplicating them', () => {
    let form = applyExtraction(emptyForm(), extraction({ prescriptions: [{ ...PARACETAMOL, frequency: null, duration_days: null }] }), 's1');
    form = applyExtraction(form, extraction({ prescriptions: [PARACETAMOL] }), 's1');
    expect(form.prescriptions).toHaveLength(1);
    expect(form.prescriptions[0]).toMatchObject({ frequency: 'BD', durationDays: '5' });
  });

  it('leaves a hand-edited row alone and recognises a typed drug', () => {
    let form = applyExtraction(emptyForm(), extraction({ complaints: [{ description: 'Cough', duration_value: null, duration_unit: null }] }), 's1');
    form = { ...form, complaints: editRow(form.complaints, form.complaints[0]!.id, { durationValue: '2', durationUnit: 'WEEKS' }) };
    form = applyExtraction(form, extraction({ complaints: [{ description: 'cough', duration_value: 5, duration_unit: 'DAYS' }] }), 's2');
    expect(form.complaints).toHaveLength(1);
    expect(form.complaints[0]).toMatchObject({ durationValue: '2', durationUnit: 'WEEKS', source: 'MANUAL' });

    form = { ...form, prescriptions: [{ ...applyExtraction(emptyForm(), extraction({ prescriptions: [PARACETAMOL] }), 'x').prescriptions[0]!, source: 'MANUAL', matchKey: '' }] };
    form = applyExtraction(form, extraction({ prescriptions: [{ ...PARACETAMOL, frequency: 'TDS' }] }), 's2');
    expect(form.prescriptions).toHaveLength(1);
    expect(form.prescriptions[0]!.frequency).toBe('BD');
  });
});

describe('validate and toConsultationRequest', () => {
  it('flags out-of-range vitals and inconsistent blood pressure', () => {
    let form = editVital(emptyForm(), 'spo2Percent', '140');
    form = editVital(editVital(form, 'bpSystolic', '70'), 'bpDiastolic', '90');
    expect(validate(form)).toEqual(['SpO₂ must be between 30 and 100 %', 'Systolic BP must be higher than diastolic']);
  });

  it('builds the signed payload with provenance', () => {
    let form = applyExtraction(emptyForm(), extraction({
      vitals: { bp_systolic: 130, bp_diastolic: 80 },
      complaints: [{ description: 'Fever', duration_value: 3, duration_unit: 'DAYS' }],
      prescriptions: [PARACETAMOL],
    }), 's1');
    form = { ...form, advice: ' Fluids ', sessions: [{ aiSessionId: 's1', language: 'en-IN', engine: 'whisper-small', audioSeconds: 12, outcome: 'completed', startedAt: '2026-11-20T10:00:00Z' }] };

    const request = toConsultationRequest(form, true);
    expect(request.vitals).toEqual({ bpSystolic: 130, bpDiastolic: 80, source: 'DICTATION', dictationSessionRef: 's1' });
    expect(request.complaints[0]).toMatchObject({ description: 'Fever', durationValue: 3, durationUnit: 'DAYS', source: 'DICTATION', dictationSessionRef: 's1' });
    expect(request.prescription).toMatchObject({ advice: 'Fluids', items: [{ drugText: 'Tab Paracetamol 650 mg', durationDays: 5, source: 'DICTATION' }] });
    expect(request.completeVisit).toBe(true);

    const mixed = toConsultationRequest(editVital(form, 'pulseBpm', '90'), false);
    expect(mixed.vitals).toMatchObject({ pulseBpm: 90, source: 'MANUAL', dictationSessionRef: undefined });
  });

  it('knows when there is nothing to sign', () => {
    expect(isDirty(emptyForm())).toBe(false);
    expect(toConsultationRequest(emptyForm(), true)).toMatchObject({ vitals: undefined, complaints: [], prescription: undefined });
  });
});

describe('diagnoses and drug-safety overrides', () => {
  it('fills dictated diagnoses with their suggested ICD-10 code and keeps typed ones', () => {
    let form = applyExtraction(emptyForm(), extraction({
      diagnoses: [{ description: 'Dengue fever', diagnosis_type: 'FINAL', icd10_code: 'A90', icd10_title: 'Dengue' }],
    }), 's1');
    expect(form.diagnoses[0]).toMatchObject({ description: 'Dengue fever', icd10Code: 'A90', type: 'FINAL', primary: true, source: 'DICTATION' });
    form = { ...form, diagnoses: editRow(form.diagnoses, form.diagnoses[0]!.id, { icd10Code: 'A91' }) };
    form = applyExtraction(form, extraction({
      diagnoses: [{ description: 'Dengue fever', diagnosis_type: 'PROVISIONAL', icd10_code: 'A90', icd10_title: 'Dengue' }],
    }), 's1');
    expect(form.diagnoses).toHaveLength(1);
    expect(form.diagnoses[0]).toMatchObject({ icd10Code: 'A91', source: 'MANUAL' });
  });

  it('validates codes and sends only meaningful override reasons with a prescription', () => {
    let form = applyExtraction(emptyForm(), extraction({ prescriptions: [PARACETAMOL] }), 's1');
    form = { ...form, diagnoses: [{ id: 'd1', description: 'Fever', icd10Code: 'FEVER', type: 'PROVISIONAL', primary: true, source: 'MANUAL' }] };
    expect(validate(form)).toContain('Diagnosis 1: ICD-10 code looks wrong (e.g. J06.9)');
    form = { ...form, diagnoses: [{ ...form.diagnoses[0]!, icd10Code: 'r50.9' }], cdssOverrides: { 'DDI-A': 'Needed, INR checked', 'DDI-B': 'ok' } };
    const request = toConsultationRequest(form, true);
    expect(request.diagnoses).toEqual([{ description: 'Fever', icd10Code: 'R50.9', type: 'PROVISIONAL', primary: true, source: 'MANUAL' }]);
    expect(request.cdssOverrides).toEqual([{ ruleCode: 'DDI-A', reason: 'Needed, INR checked' }]);
  });
});

describe('duration labels', () => {
  it('pluralises units correctly', async () => {
    const { duration } = await import('../components/ui');
    expect(duration(1, 'WEEKS')).toBe('1 week');
    expect(duration(3, 'DAYS')).toBe('3 days');
    expect(duration(undefined, 'DAYS')).toBe('');
  });
});
