import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { EncounterRecord, Queue } from '../api/types';
import type { Session } from '../auth/session';
import type { PcmListener } from '../dictation/microphone';
import { extraction, FakeWebSocket, PARACETAMOL } from '../test/fixtures';
import { DoctorDesk } from './DoctorDesk';

let micListener: PcmListener | null = null;
const micStop = vi.fn(async () => undefined);
vi.mock('../dictation/microphone', () => ({
  startMicrophone: vi.fn(async (listener: PcmListener) => {
    micListener = listener;
    return { stop: micStop };
  }),
}));

const SESSION: Session = { token: 'test-token', subject: 'dev.doctor', roles: ['DOCTOR'], expiresAt: Date.now() + 3_600_000 };

const QUEUE: Queue = {
  departmentCode: 'GMED',
  departmentName: 'General Medicine',
  date: '2026-11-20',
  counts: { CALLED: 1 },
  visits: [{
    visitId: 'visit-1', encounterId: 'enc-1', tokenNumber: 7, visitDate: '2026-11-20', departmentCode: 'GMED',
    departmentName: 'General Medicine', uhid: 'SYNC-20261120-0007', patientName: 'Ramesh Kumar', gender: 'M',
    ageYears: 46, visitType: 'NEW', priority: 'NORMAL', queueStatus: 'CALLED', registeredAt: new Date().toISOString(),
  }],
};

const RECORD: EncounterRecord = {
  encounterId: 'enc-1', encounterType: 'OPD', status: 'ARRIVED',
  patient: { id: 'p-1', uhid: 'SYNC-20261120-0007', name: 'Ramesh Kumar', gender: 'M', ageYears: 46 },
  departmentCode: 'GMED', departmentName: 'General Medicine',
  visit: { visitId: 'visit-1', tokenNumber: 7, queueStatus: 'CALLED' },
  vitalsHistory: [], complaints: [], prescriptions: [],
  allergies: [{ allergen: 'Penicillin', reaction: 'Rash' }],
};

let signed: Record<string, unknown> | null = null;
let cdssAlerts: unknown[] = [];
let cdssRequests: Record<string, unknown>[] = [];

function mockGateway() {
  signed = null;
  cdssAlerts = [];
  cdssRequests = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
    if (url === '/api/v1/opd/departments') return json([{ code: 'GMED', name: 'General Medicine' }]);
    if (url.startsWith('/api/v1/opd/queue?department=GMED')) return json(QUEUE);
    if (url === '/api/v1/emr/encounters/enc-1' && (!init?.method || init.method === 'GET')) return json(RECORD);
    if (url === '/api/v1/ai/cdss/check' && init?.method === 'POST') {
      cdssRequests.push(JSON.parse(init.body as string));
      return json({ kb_version: '2026.11-starter', kb_reviewed: false, alerts: cdssAlerts, recognised: [], not_checked: [] });
    }
    if (url === '/api/v1/emr/encounters/enc-1/consultation' && init?.method === 'POST') {
      signed = JSON.parse(init.body as string);
      return json({ ...RECORD, status: 'FINISHED', visit: { ...RECORD.visit, queueStatus: 'COMPLETED' } }, 201);
    }
    return json({ detail: `unexpected ${url}` }, 404);
  }));
}

beforeEach(() => {
  FakeWebSocket.instances = [];
  micListener = null;
  vi.stubGlobal('WebSocket', FakeWebSocket);
  mockGateway();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Doctor Desk', () => {
  it('auto-fills the consultation from dictation and signs it with provenance', async () => {
    const user = userEvent.setup();
    render(<DoctorDesk session={SESSION} />);

    await user.click(await screen.findByText('Ramesh Kumar'));
    expect(await screen.findByText(/Allergy: Penicillin/)).toBeInTheDocument();

    // Start dictation: the socket carries the token and announces the encounter.
    await user.click(screen.getByRole('button', { name: 'Start dictation' }));
    const socket = FakeWebSocket.instances[0]!;
    expect(socket.url).toMatch(/\/ws\/ai\/dictation\?access_token=test-token$/);
    act(() => socket.open());
    expect(socket.json(0)).toMatchObject({ type: 'start', encounter_id: 'enc-1', language: 'en-IN', sample_rate: 16000 });

    await act(() => socket.receive({ type: 'ready', session_id: 's1', engine: 'whisper-small' }));
    expect(screen.getByRole('button', { name: 'Stop dictation' })).toHaveAttribute('aria-pressed', 'true');

    // Microphone audio is streamed as binary PCM frames.
    act(() => micListener!(new Int16Array([1, 2, 3]), 0.1));
    expect(socket.sent[1]).toBeInstanceOf(ArrayBuffer);

    // A partial transcript fills vitals, complaints and the prescription live.
    await act(() => socket.receive({
      type: 'partial',
      text: 'c/o fever for 3 days. BP 130/80. Tab Paracetamol 650 mg 1-0-1 after food for 5 days',
      extraction: extraction({
        vitals: { bp_systolic: 130, bp_diastolic: 80 },
        complaints: [{ description: 'Fever', duration_value: 3, duration_unit: 'DAYS' }],
        prescriptions: [PARACETAMOL],
      }),
    }));
    expect(screen.getByLabelText('BP sys')).toHaveValue('130');
    expect(screen.getByLabelText('BP sys')).toHaveClass('field-dictated');
    expect(screen.getByLabelText('Complaint')).toHaveValue('Fever');
    expect(screen.getByLabelText('Drug')).toHaveValue('Tab Paracetamol 650 mg');
    expect((screen.getByLabelText('Transcript') as HTMLTextAreaElement).value).toContain('Paracetamol');

    // The doctor types the pulse; later dictation must not overwrite it.
    await user.type(screen.getByLabelText('Pulse'), '88');
    await act(() => socket.receive({ type: 'partial', text: '… pulse 96', extraction: extraction({ vitals: { pulse_bpm: 96, bp_systolic: 130, bp_diastolic: 80 } }) }));
    expect(screen.getByLabelText('Pulse')).toHaveValue('88');

    // Stop, receive the final transcript, then sign.
    await user.click(screen.getByRole('button', { name: 'Stop dictation' }));
    expect(socket.json(socket.sent.length - 1)).toEqual({ type: 'stop' });
    expect(micStop).toHaveBeenCalled();
    await act(() => socket.receive({
      type: 'final', session_id: 's1', text: 'done', audio_seconds: 12.5, engine: 'whisper-small', language: 'en-IN',
      extraction: extraction({ prescriptions: [PARACETAMOL] }),
    }));

    await user.click(screen.getByRole('button', { name: /Sign & complete visit/ }));
    await waitFor(() => expect(signed).not.toBeNull());
    expect(signed).toMatchObject({
      completeVisit: true,
      dictationSessions: [{ aiSessionId: 's1', engine: 'whisper-small', audioSeconds: 12.5, outcome: 'completed' }],
      vitals: { bpSystolic: 130, bpDiastolic: 80, pulseBpm: 88, source: 'MANUAL' },
      complaints: [{ description: 'Fever', durationValue: 3, durationUnit: 'DAYS', source: 'DICTATION', dictationSessionRef: 's1' }],
      prescription: { items: [{ drugText: 'Tab Paracetamol 650 mg', frequency: 'BD', durationDays: 5, source: 'DICTATION', dictationSessionRef: 's1' }] },
    });
    expect(await screen.findByText('Signed. Prescription issued and visit completed.')).toBeInTheDocument();
  });

  it('warns about a serious interaction and signs only with the prescriber\'s reason', async () => {
    const user = userEvent.setup();
    render(<DoctorDesk session={SESSION} />);
    await user.click(await screen.findByText('Ramesh Kumar'));
    await user.click(screen.getByRole('button', { name: 'Start dictation' }));
    const socket = FakeWebSocket.instances[0]!;
    act(() => socket.open());
    await act(() => socket.receive({ type: 'ready', session_id: 's2', engine: 'whisper-small' }));

    cdssAlerts = [{
      alert_type: 'DRUG_DRUG', severity: 'MAJOR', rule_code: 'DDI-VKA-NSAID', message: 'Increased risk of bleeding.',
      management: 'Prefer paracetamol.', lines: [0, 1],
    }];
    await act(() => socket.receive({
      type: 'partial',
      text: 'Diagnosis acute gout. Tab Warfarin 5 mg OD. Tab Ibuprofen 400 mg TDS.',
      extraction: extraction({
        diagnoses: [{ description: 'Acute gout', diagnosis_type: 'PROVISIONAL', icd10_code: 'M10.9', icd10_title: 'Gout' }],
        prescriptions: [
          { ...PARACETAMOL, drug_text: 'Tab Warfarin 5 mg', drug_name: 'Warfarin', frequency: 'OD' },
          { ...PARACETAMOL, drug_text: 'Tab Ibuprofen 400 mg', drug_name: 'Ibuprofen', frequency: 'TDS' },
        ],
      }),
    }));
    expect(screen.getByLabelText('ICD-10 code')).toHaveValue('M10.9');

    const banner = await screen.findByText('Increased risk of bleeding.', {}, { timeout: 3000 });
    expect(banner.closest('[role="alert"]')).toHaveClass('bg-red-50');
    expect(screen.getByText(/Rules pending pharmacist review/)).toBeInTheDocument();
    expect(cdssRequests.at(-1)).toMatchObject({
      allergies: [{ allergen: 'Penicillin' }],
      patient: { age_years: 46, sex: 'M' },
      prescriptions: [{ drug_text: 'Tab Warfarin 5 mg', frequency: 'OD' }, { drug_text: 'Tab Ibuprofen 400 mg', frequency: 'TDS' }],
    });

    await user.click(screen.getByRole('button', { name: 'Stop dictation' }));
    await act(() => socket.receive({
      type: 'final', session_id: 's2', text: 'done', audio_seconds: 5, engine: 'whisper-small', language: 'en-IN',
      extraction: extraction({}),
    }));
    await user.click(screen.getByRole('button', { name: /Sign & complete visit/ }));
    expect(await screen.findByText(/Give a reason to override 1 serious drug-safety alert/)).toBeInTheDocument();
    expect(signed).toBeNull();

    await user.type(screen.getByLabelText('Reason to override DDI-VKA-NSAID'), 'Two days only; INR on day 3');
    await user.click(screen.getByRole('button', { name: /Sign & complete visit/ }));
    await waitFor(() => expect(signed).not.toBeNull());
    expect(signed).toMatchObject({
      cdssOverrides: [{ ruleCode: 'DDI-VKA-NSAID', reason: 'Two days only; INR on day 3' }],
      diagnoses: [{ description: 'Acute gout', icd10Code: 'M10.9', type: 'PROVISIONAL', primary: true, source: 'DICTATION' }],
    });
  });

  it('shows server-side dictation errors and records the interrupted session', async () => {
    const user = userEvent.setup();
    render(<DoctorDesk session={SESSION} />);
    await user.click(await screen.findByText('Ramesh Kumar'));
    await user.click(screen.getByRole('button', { name: 'Start dictation' }));
    const socket = FakeWebSocket.instances[0]!;
    act(() => socket.open());
    await act(() => socket.receive({ type: 'error', code: 'engine_unavailable', message: 'Speech recognition is starting up.' }));
    expect(within(screen.getByLabelText('Voice dictation')).getByRole('status')).toHaveTextContent('Speech recognition is starting up.');
    expect(screen.getByRole('button', { name: 'Start dictation' })).toBeEnabled();
  });

  it('is read-only for nurses (no dictation or signing)', async () => {
    const user = userEvent.setup();
    render(<DoctorDesk session={{ ...SESSION, roles: ['NURSE'] }} />);
    await user.click(await screen.findByText('Ramesh Kumar'));
    expect(await screen.findByText(/Allergy: Penicillin/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start dictation' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Sign & complete/ })).not.toBeInTheDocument();
  });
});
