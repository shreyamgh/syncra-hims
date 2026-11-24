import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AllergyEntry, CriticalResult, EncounterRecord, ImmunizationRecord, Me, NoteRecord, Orderable, OrderRecord, Queue } from '../api/types';
import type { Session } from '../auth/session';
import { FakeWebSocket } from '../test/fixtures';
import { DoctorDesk } from './DoctorDesk';

vi.mock('../dictation/microphone', () => ({ startMicrophone: vi.fn() }));

const DOCTOR: Session = { token: 't', subject: 'dev.doctor', roles: ['DOCTOR'], expiresAt: Date.now() + 3_600_000 };
const NURSE: Session = { token: 't', subject: 'dev.nurse', roles: ['NURSE'], expiresAt: Date.now() + 3_600_000 };
const DOCTOR_ME: Me = { linked: true, staffId: 'staff-doctor', name: 'Dr Asha Menon', staffRole: 'DOCTOR', doctor: true };
const NURSE_ME: Me = { linked: true, staffId: 'staff-nurse', name: 'Sr Mary Thomas', staffRole: 'NURSE', doctor: false };

const visit = (n: number) => ({
  visitId: `visit-${n}`, encounterId: `enc-${n}`, tokenNumber: n, visitDate: '2026-11-20', departmentCode: 'GMED',
  departmentName: 'General Medicine', uhid: `SYNC-20261120-000${n}`, patientName: n === 1 ? 'Ramesh Kumar' : 'Sunita Devi',
  gender: 'M' as const, ageYears: 46, visitType: 'NEW', priority: 'NORMAL' as const, queueStatus: 'IN_CONSULTATION' as const,
  registeredAt: new Date().toISOString(),
});
const QUEUE: Queue = {
  departmentCode: 'GMED', departmentName: 'General Medicine', date: '2026-11-20', counts: {}, visits: [visit(1), visit(2)],
};

const ORDERABLES: Orderable[] = [
  { serviceId: 's1', code: 'LAB-RFT', name: 'Renal Function Test', category: 'LABORATORY' },
  { serviceId: 's2', code: 'RAD-XCH', name: 'X-Ray Chest PA View', category: 'RADIOLOGY', modality: 'DX' },
];

function baseRecord(n = 1): EncounterRecord {
  return {
    encounterId: `enc-${n}`, encounterType: 'OPD', status: 'IN_PROGRESS',
    patient: { id: `p-${n}`, uhid: `SYNC-20261120-000${n}`, name: 'Ramesh Kumar', gender: 'M', ageYears: 46 },
    departmentCode: 'GMED', departmentName: 'General Medicine',
    visit: { visitId: `visit-${n}`, tokenNumber: n, queueStatus: 'IN_CONSULTATION' },
    vitalsHistory: [], complaints: [], prescriptions: [], allergies: [],
    diagnoses: [{ id: 'dx', description: 'Chronic kidney disease', diagnosisType: 'PROVISIONAL', primary: true, enteredInError: false }],
    notes: [], orders: [],
  };
}

interface Call { method: string; url: string; body?: Record<string, unknown>; headers: Record<string, string> }
let calls: Call[];
let state: { me: Me; record: EncounterRecord; allergies: AllergyEntry[]; immunizations: ImmunizationRecord[]; critical: CriticalResult[] };
const now = () => new Date().toISOString();

function sentTo(method: string, url: string | RegExp) {
  return calls.filter((c) => c.method === method && (typeof url === 'string' ? c.url === url : url.test(c.url)));
}

function mockGateway() {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method, url, body, headers: (init?.headers ?? {}) as Record<string, string> });
    const json = (payload: unknown, status = 200) => new Response(JSON.stringify(payload), { status });
    const r = state.record;
    const enc = `/api/v1/emr/encounters/${r.encounterId}`;
    const allergyUrl = `/api/v1/emr/patients/${r.patient.uhid}/allergies`;
    let m: RegExpMatchArray | null;

    if (url === '/api/v1/opd/departments') return json([{ code: 'GMED', name: 'General Medicine' }]);
    if (url.startsWith('/api/v1/opd/queue?department=GMED')) return json(QUEUE);
    if (url === '/api/v1/emr/me') return json(state.me);
    if (url === enc && method === 'GET') return json(r);
    if (url.startsWith('/api/v1/emr/orderables?q=')) {
      const q = decodeURIComponent(url.split('q=')[1]!.split('&')[0]!).toLowerCase();
      return json(ORDERABLES.filter((o) => o.name.toLowerCase().includes(q) || o.code.toLowerCase().includes(q)));
    }
    if (url === `${enc}/orders` && method === 'POST') {
      const service = ORDERABLES.find((o) => o.code === body.serviceCode)!;
      const order: OrderRecord = {
        id: `order-${(r.orders ?? []).length + 1}`, encounterId: r.encounterId, serviceCode: service.code, serviceName: service.name,
        orderType: service.category, status: 'ORDERED', priority: body.priority, clinicalIndication: body.clinicalIndication,
        orderedBy: 'Dr Asha Menon', orderedAt: now(),
        accessionNumber: service.category === 'RADIOLOGY' ? 'RAD/26-27/000001' : undefined,
      };
      state.record = { ...r, orders: [...(r.orders ?? []), order] };
      return json(order, 201);
    }
    if ((m = url.match(/^\/api\/v1\/emr\/orders\/([^/]+)\/cancel$/))) {
      const id = m[1];
      state.record = { ...r, orders: r.orders!.map((o) => (o.id === id ? { ...o, status: 'CANCELLED', cancelledReason: body.reason } : o)) };
      return json(state.record.orders!.find((o) => o.id === id));
    }
    if (url === `${enc}/notes` && method === 'POST') {
      const created: NoteRecord = {
        id: `note-${(r.notes ?? []).length + 1}`, encounterId: r.encounterId, noteType: body.noteType, content: body.content,
        authorId: state.me.staffId!, author: state.me.name!, signedAt: body.sign ? now() : undefined,
        amendsNoteId: body.amendsNoteId, enteredInError: false, version: 1, createdAt: now(),
      };
      state.record = { ...r, notes: [...(r.notes ?? []), created] };
      return json(created, 201);
    }
    if ((m = url.match(/\/notes\/([^/]+)$/)) && method === 'PUT') {
      const target = r.notes!.find((n) => n.id === m![1])!;
      if (init?.headers && (init.headers as Record<string, string>)['If-Match'] !== `"${target.version}"`) {
        return json({ code: 'STALE_NOTE', detail: 'stale' }, 412);
      }
      const edited = { ...target, content: body.content, version: target.version + 1 };
      state.record = { ...r, notes: r.notes!.map((n) => (n.id === target.id ? edited : n)) };
      return json(edited);
    }
    if ((m = url.match(/\/notes\/([^/]+)\/sign$/))) {
      state.record = { ...r, notes: r.notes!.map((n) => (n.id === m![1] ? { ...n, signedAt: now() } : n)) };
      return json(state.record.notes!.find((n) => n.id === m![1]));
    }
    if (url === allergyUrl && method === 'GET') return json(state.allergies);
    if (url === allergyUrl && method === 'POST') {
      const added: AllergyEntry = { id: `alg-${state.allergies.length + 1}`, ...body, status: 'ACTIVE', recordedBy: 'dev.doctor', recordedAt: now() } as AllergyEntry;
      state.allergies = [...state.allergies, added];
      state.record = { ...r, allergies: [...r.allergies, { allergen: added.allergenText, drugClass: added.drugClass }] };
      return json(added, 201);
    }
    if ((m = url.match(/\/allergies\/([^/]+)\/status$/))) {
      state.allergies = state.allergies.map((a) => (a.id === m![1] ? { ...a, status: body.status } : a));
      const active = state.allergies.filter((a) => a.status === 'ACTIVE');
      state.record = { ...r, allergies: active.map((a) => ({ allergen: a.allergenText, drugClass: a.drugClass })) };
      return json(state.allergies.find((a) => a.id === m![1]));
    }
    if (url.startsWith('/api/v1/emr/critical-results?')) return json(state.critical.filter((c) => !c.acknowledgedAt));
    if ((m = url.match(/^\/api\/v1\/emr\/critical-results\/([^/]+)\/acknowledge$/))) {
      state.critical = state.critical.map((c) => (c.id === m![1] ? { ...c, acknowledgedAt: now(), acknowledgedBy: 'Dr Asha Menon', status: 'ACKNOWLEDGED' } : c));
      return json(state.critical.find((c) => c.id === m![1]));
    }
    if (url === '/api/v1/emr/vaccines') {
      return json([
        { code: 'PENTA', name: 'Pentavalent (DPT-HepB-Hib)', targetDisease: 'DPT, HepB, Hib', defaultRoute: 'IM', defaultDoseMl: 0.5, seriesDoses: 3, programme: 'UIP' },
        { code: 'TCV', name: 'Typhoid Conjugate Vaccine', targetDisease: 'Typhoid', defaultRoute: 'IM', defaultDoseMl: 0.5, seriesDoses: 1, programme: 'OTHER' },
      ]);
    }
    if (url === `/api/v1/emr/patients/${r.patient.uhid}/immunizations`) return json({ uhid: r.patient.uhid, immunizations: state.immunizations });
    if (url === `${enc}/immunizations` && method === 'POST') {
      const dose = { id: `imm-${state.immunizations.length + 1}`, encounterId: r.encounterId, vaccineName: body.vaccineCode, targetDisease: '',
        administeredAt: body.administeredAt ?? now(), enteredInError: false, recordedAt: now(), ...body };
      state.immunizations = [dose, ...state.immunizations];
      return json(dose, 201);
    }
    if ((m = url.match(/\/immunizations\/([^/]+)\/entered-in-error$/))) {
      state.immunizations = state.immunizations.map((i) => (i.id === m![1] ? { ...i, enteredInError: true } : i));
      return json(state.immunizations.find((i) => i.id === m![1]));
    }
    if (url === '/api/v1/ai/cdss/check') {
      return json({ kb_version: 'k', kb_reviewed: false, alerts: [], recognised: [], not_checked: [] });
    }
    return json({ detail: `unexpected ${method} ${url}` }, 404);
  }));
}

async function openPatient(user: ReturnType<typeof userEvent.setup>, session: Session = DOCTOR) {
  render(<DoctorDesk session={session} />);
  await user.click(await screen.findByText('Ramesh Kumar'));
  await screen.findByRole('tab', { name: /Orders & results/ });
}

beforeEach(() => {
  vi.stubGlobal('WebSocket', FakeWebSocket);
  state = { me: DOCTOR_ME, record: baseRecord(), allergies: [], immunizations: [], critical: [] };
  mockGateway();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Orders & results', () => {
  it('orders a test from the catalogue by keyboard, with the primary diagnosis as indication', async () => {
    const user = userEvent.setup();
    await openPatient(user);
    await user.click(screen.getByRole('tab', { name: /Orders & results/ }));

    await user.type(screen.getByRole('combobox', { name: 'Find a test' }), 'renal');
    expect(await screen.findByRole('option', { name: /Renal Function Test/ })).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Enter}');
    expect(screen.getByRole('textbox', { name: 'Clinical indication' })).toHaveValue('Chronic kidney disease');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Priority' }), 'URGENT');
    await user.click(screen.getByRole('button', { name: 'Place order' }));

    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/orders')).toHaveLength(1));
    expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/orders')[0]!.body)
      .toEqual({ serviceCode: 'LAB-RFT', priority: 'URGENT', clinicalIndication: 'Chronic kidney disease' });
    const placed = await screen.findByRole('listitem', { name: 'Renal Function Test' });
    expect(within(placed).getByText('Ordered')).toBeInTheDocument();
    expect(within(placed).getByText('Urgent')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Ordered Renal Function Test.');
  });

  it('refuses an imaging order without a clinical indication', async () => {
    state.record = { ...baseRecord(), diagnoses: [] };
    const user = userEvent.setup();
    await openPatient(user);
    await user.click(screen.getByRole('tab', { name: /Orders & results/ }));

    await user.type(screen.getByRole('combobox', { name: 'Find a test' }), 'chest');
    await user.click(await screen.findByRole('option', { name: /X-Ray Chest/ }));
    await user.click(screen.getByRole('button', { name: 'Place order' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Give the clinical indication for the imaging study');
    expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/orders')).toHaveLength(0);
  });

  it('shows reported results with flags, badges the tab, and cancels an open order with a reason', async () => {
    state.record = {
      ...baseRecord(),
      orders: [
        {
          id: 'o-k', encounterId: 'enc-1', serviceCode: 'LAB-RFT', serviceName: 'Renal Function Test', orderType: 'LABORATORY',
          status: 'REPORTED', priority: 'URGENT', orderedBy: 'Dr Asha Menon', orderedAt: now(), completedAt: now(),
          resultSummary: 'Renal impairment', reportedBy: 'Vikram Singh', resultValues: [
            { code: 'CREAT', name: 'Serum creatinine', value: 2.4, unit: 'mg/dL', referenceRange: '0.7-1.3', flag: 'H' },
            { code: 'KSERUM', name: 'Potassium', value: 6.9, unit: 'mmol/L', referenceRange: '3.5-5.1', flag: 'C' },
          ],
        },
        {
          id: 'o-x', encounterId: 'enc-1', serviceCode: 'RAD-XCH', serviceName: 'X-Ray Chest PA View', orderType: 'RADIOLOGY',
          status: 'ORDERED', priority: 'ROUTINE', orderedBy: 'Dr Asha Menon', orderedAt: now(), accessionNumber: 'RAD/26-27/000004',
        },
      ],
    };
    const user = userEvent.setup();
    await openPatient(user);
    const tab = screen.getByRole('tab', { name: /Orders & results/ });
    expect(tab).toHaveTextContent('1 critical');
    await user.click(tab);

    const result = screen.getByRole('region', { name: 'Orders and results' });
    const rft = within(result).getByRole('listitem', { name: 'Renal Function Test' });
    expect(within(rft).getByText('Renal impairment')).toBeInTheDocument();
    const potassium = within(rft).getByRole('row', { name: /Potassium/ });
    expect(within(potassium).getByText('Critical')).toBeInTheDocument();
    expect(within(within(rft).getByRole('row', { name: /Serum creatinine/ })).getByText('High')).toBeInTheDocument();
    expect(within(rft).queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();

    const xray = within(result).getByRole('listitem', { name: 'X-Ray Chest PA View' });
    await user.click(within(xray).getByRole('button', { name: 'Cancel order' }));
    expect(within(xray).getByRole('button', { name: 'Confirm cancel' })).toBeDisabled();
    await user.type(within(xray).getByRole('textbox', { name: /Reason to cancel/ }), 'Done at another centre');
    await user.click(within(xray).getByRole('button', { name: 'Confirm cancel' }));

    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/orders/o-x/cancel')).toHaveLength(1));
    expect(sentTo('POST', '/api/v1/emr/orders/o-x/cancel')[0]!.body).toEqual({ reason: 'Done at another centre' });
    expect(await within(xray).findByText('Cancelled')).toBeInTheDocument();
  });
});

describe('Notes', () => {
  it('writes and signs a note, edits a draft with its version, and amends a signed note', async () => {
    state.record = {
      ...baseRecord(),
      notes: [
        { id: 'n-signed', encounterId: 'enc-1', noteType: 'HISTORY', content: 'Pedal oedema for 2 weeks.', authorId: 'staff-doctor',
          author: 'Dr Asha Menon', signedAt: '2026-11-20T04:00:00Z', enteredInError: false, version: 2, createdAt: '2026-11-20T04:00:00Z' },
        { id: 'n-draft', encounterId: 'enc-1', noteType: 'EXAMINATION', content: 'BP high', authorId: 'staff-doctor',
          author: 'Dr Asha Menon', enteredInError: false, version: 3, createdAt: '2026-11-20T04:30:00Z' },
        { id: 'n-nurse', encounterId: 'enc-1', noteType: 'NURSING', content: 'Vitals taken.', authorId: 'staff-nurse',
          author: 'Sr Mary Thomas', signedAt: '2026-11-20T03:50:00Z', enteredInError: false, version: 1, createdAt: '2026-11-20T03:50:00Z' },
      ],
    };
    const user = userEvent.setup();
    await openPatient(user);
    const tab = screen.getByRole('tab', { name: /Notes/ });
    expect(tab).toHaveTextContent('1 draft');
    await user.click(tab);
    const notes = screen.getByRole('list', { name: 'Notes' });

    // Someone else's note is read-only.
    const nursing = within(notes).getByRole('listitem', { name: 'Nursing note by Sr Mary Thomas' });
    expect(within(nursing).queryByRole('button')).not.toBeInTheDocument();

    // Edit the draft: the PUT carries the version it was read at.
    const draft = within(notes).getByRole('listitem', { name: 'Examination by Dr Asha Menon' });
    await user.click(within(draft).getByRole('button', { name: 'Edit' }));
    await user.clear(within(draft).getByRole('textbox', { name: 'Edit draft' }));
    await user.type(within(draft).getByRole('textbox', { name: 'Edit draft' }), 'BP 160/100, no pallor');
    await user.click(within(draft).getByRole('button', { name: 'Save & sign' }));
    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/notes/n-draft/sign')).toHaveLength(1));
    const put = sentTo('PUT', '/api/v1/emr/encounters/enc-1/notes/n-draft')[0]!;
    expect(put.headers['If-Match']).toBe('"3"');
    expect(put.body).toEqual({ content: 'BP 160/100, no pallor' });

    // Amend the signed history: same type, pre-filled, and always signed.
    const history = within(notes).getByRole('listitem', { name: 'History by Dr Asha Menon' });
    await user.click(within(history).getByRole('button', { name: 'Amend' }));
    const composer = screen.getByRole('group', { name: 'Amend note' });
    const text = within(composer).getByRole('textbox', { name: 'Note text' });
    expect(text).toHaveValue('Pedal oedema for 2 weeks.');
    expect(within(composer).getByRole('button', { name: 'Sign amendment' })).toBeDisabled();
    await user.type(text, ' Also facial puffiness.');
    await user.click(within(composer).getByRole('button', { name: 'Sign amendment' }));
    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/notes')).toHaveLength(1));
    expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/notes')[0]!.body).toEqual({
      noteType: 'HISTORY', content: 'Pedal oedema for 2 weeks. Also facial puffiness.', sign: true, amendsNoteId: 'n-signed',
    });
    expect(await within(history).findByText('Amended')).toBeInTheDocument();
    expect(within(history).queryByRole('button', { name: 'Amend' })).not.toBeInTheDocument();

    // A new progress note, signed straight away.
    const writer = screen.getByRole('group', { name: 'Write a note' });
    await user.type(within(writer).getByRole('textbox', { name: 'Note text' }), 'Plan: RFT, review tomorrow.');
    await user.click(within(writer).getByRole('button', { name: 'Sign note' }));
    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/notes')).toHaveLength(2));
    expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/notes')[1]!.body)
      .toEqual({ noteType: 'PROGRESS', content: 'Plan: RFT, review tomorrow.', sign: true });
  });

  it('protects an unsaved note: no switching patients unconfirmed, and Ctrl+Enter does not complete the visit', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const user = userEvent.setup();
    await openPatient(user);
    await user.click(screen.getByRole('tab', { name: /Notes/ }));
    const text = within(screen.getByRole('group', { name: 'Write a note' })).getByRole('textbox', { name: 'Note text' });
    await user.type(text, 'Half-written thought');

    await user.keyboard('{Control>}{Enter}{/Control}');
    expect(sentTo('POST', /\/consultation$/)).toHaveLength(0);

    // Switching tabs keeps the text.
    await user.click(screen.getByRole('tab', { name: /Consultation/ }));
    await user.click(screen.getByRole('tab', { name: /Notes/ }));
    expect(text).toHaveValue('Half-written thought');

    await user.click(screen.getByText('Sunita Devi'));
    expect(confirm).toHaveBeenCalledWith('Discard the unsigned notes for the current patient?');
    expect(screen.getByRole('heading', { name: 'Ramesh Kumar' })).toBeInTheDocument();
  });

  it('gives a nurse nursing note types and no order form', async () => {
    state.me = NURSE_ME;
    const user = userEvent.setup();
    await openPatient(user, NURSE);
    await user.click(screen.getByRole('tab', { name: /Notes/ }));
    const types = within(screen.getByRole('combobox', { name: 'Note type' })).getAllByRole('option').map((o) => o.textContent);
    expect(types).toEqual(['Nursing note', 'Progress note', 'Other']);

    await user.click(screen.getByRole('tab', { name: /Orders & results/ }));
    expect(screen.queryByRole('group', { name: 'Place an order' })).not.toBeInTheDocument();
  });
});

describe('Allergies', () => {
  it('records an allergy from the header, ends one, and the drug-safety check sees the change', async () => {
    state.allergies = [{
      id: 'alg-1', allergenType: 'FOOD', allergenText: 'Peanut', reaction: 'Hives', severity: 'MODERATE', status: 'ACTIVE',
      recordedBy: 'dev.nurse', recordedAt: '2026-01-10T05:00:00Z',
    }];
    state.record = { ...baseRecord(), allergies: [{ allergen: 'Peanut', reaction: 'Hives' }] };
    const user = userEvent.setup();
    await openPatient(user);

    await user.click(screen.getByRole('button', { name: /Allergy: Peanut/ }));
    expect(screen.getByRole('tab', { name: /Allergies/ })).toHaveAttribute('aria-selected', 'true');
    const list = await screen.findByRole('table', { name: 'Allergy list' });
    expect(within(list).getByRole('row', { name: /Peanut/ })).toHaveTextContent('Active');

    const form = screen.getByRole('form', { name: 'Record an allergy' });
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Type' }), 'DRUG_CLASS');
    await user.type(within(form).getByRole('textbox', { name: 'Allergen' }), 'Penicillin');
    await user.click(within(form).getByRole('button', { name: 'Record allergy' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Name the drug class');
    expect(sentTo('POST', /\/allergies$/)).toHaveLength(0);

    await user.type(within(form).getByRole('textbox', { name: /Drug class/ }), 'Penicillins');
    await user.type(within(form).getByRole('textbox', { name: 'Reaction' }), 'Urticaria');
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Severity' }), 'SEVERE');
    await user.click(within(form).getByRole('button', { name: 'Record allergy' }));
    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/patients/SYNC-20261120-0001/allergies')).toHaveLength(1));
    expect(sentTo('POST', '/api/v1/emr/patients/SYNC-20261120-0001/allergies')[0]!.body).toEqual({
      allergenType: 'DRUG_CLASS', allergenText: 'Penicillin', drugClass: 'Penicillins', reaction: 'Urticaria', severity: 'SEVERE',
    });
    expect(await within(list).findByRole('row', { name: /Penicillin/ })).toBeInTheDocument();
    // The encounter record reloads, so the header and the CDSS see the new allergy.
    expect(await screen.findByRole('button', { name: /Allergy: Penicillin/ })).toBeInTheDocument();

    await user.selectOptions(within(list).getByRole('combobox', { name: 'End the Peanut allergy' }), 'RESOLVED');
    await waitFor(() => expect(sentTo('PUT', '/api/v1/emr/patients/SYNC-20261120-0001/allergies/alg-1/status')).toHaveLength(1));
    expect(sentTo('PUT', /alg-1\/status$/)[0]!.body).toEqual({ status: 'RESOLVED' });
    expect(await within(list).findByRole('button', { name: 'Reactivate' })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: /Allergy: Peanut/ })).not.toBeInTheDocument());
  });
});

describe('Vaccinations', () => {
  it('records a dose given here with its batch, flags a due dose, and withdraws an entry with a reason', async () => {
    state.immunizations = [{
      id: 'imm-0', encounterId: 'enc-0', vaccineCode: 'PENTA', vaccineName: 'Pentavalent (DPT-HepB-Hib)', targetDisease: '', status: 'COMPLETED',
      doseNumber: 1, seriesDoses: 3, administeredAt: '2026-09-01T05:00:00Z', primarySource: false, reportOrigin: 'MCP card',
      nextDueDate: '2026-10-01', enteredInError: false, recordedAt: '2026-11-01T05:00:00Z',
    } as ImmunizationRecord];
    const user = userEvent.setup();
    await openPatient(user);
    await user.click(screen.getByRole('tab', { name: /Vaccinations/ }));

    expect(await screen.findByRole('note')).toHaveTextContent('Due: Pentavalent (DPT-HepB-Hib) (next dose since 2026-10-01)');
    const form = screen.getByRole('form', { name: 'Record a vaccination' });
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Vaccine' }), 'PENTA');
    // The next dose in the series and the vaccine's route are filled in.
    expect(within(form).getByRole('combobox', { name: 'Dose' })).toHaveValue('2');
    expect(within(form).getByRole('combobox', { name: 'Route' })).toHaveValue('IM');
    await user.click(within(form).getByRole('button', { name: 'Record vaccination' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Enter the batch (lot) number and expiry of the vial');

    await user.type(within(form).getByRole('textbox', { name: 'Batch (lot) number' }), 'PV-2611');
    await user.type(within(form).getByLabelText('Vial expiry'), '2027-06-30');
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Site' }), 'LEFT_THIGH');
    await user.click(within(form).getByRole('button', { name: 'Record vaccination' }));
    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/immunizations')).toHaveLength(1));
    expect(sentTo('POST', '/api/v1/emr/encounters/enc-1/immunizations')[0]!.body).toEqual({
      vaccineCode: 'PENTA', status: 'COMPLETED', doseNumber: 2, primarySource: true, lotNumber: 'PV-2611', expiryDate: '2027-06-30',
      route: 'IM', site: 'LEFT_THIGH', doseMl: 0.5,
    });

    const history = screen.getByRole('table', { name: 'Vaccination history' });
    const card = within(history).getByRole('row', { name: 'Pentavalent (DPT-HepB-Hib) dose 1' });
    await user.click(within(card).getByRole('button', { name: 'Entered in error' }));
    await user.type(within(card).getByRole('textbox', { name: /entered in error/ }), 'Wrong patient card');
    await user.click(within(card).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/encounters/enc-0/immunizations/imm-0/entered-in-error')).toHaveLength(1));
    expect(sentTo('POST', '/api/v1/emr/encounters/enc-0/immunizations/imm-0/entered-in-error')[0]!.body).toEqual({ reason: 'Wrong patient card' });
  });
});

describe('Critical results', () => {
  it("shows the doctor's unacknowledged critical results and records the acknowledgement with the action taken", async () => {
    state.critical = [{
      id: 'crit-1', orderId: 'o-k', encounterId: 'enc-9', uhid: 'SYNC-20261121-0009', patientName: 'Suresh Patel', serviceName: 'Random Blood Sugar',
      criticalValues: [{ code: 'GLU_R', name: 'Random blood glucose', value: 452, unit: 'mg/dL', flag: 'C' }], reportedBy: 'Vikram Singh',
      reportedAt: now(), orderedBy: 'Dr Asha Menon', communicatedTo: 'Dr Asha Menon', communicationMethod: 'PHONE', communicatedBy: 'Vikram Singh',
      communicatedAt: now(), status: 'COMMUNICATED',
    }];
    const user = userEvent.setup();
    render(<DoctorDesk session={DOCTOR} />);
    const banner = await screen.findByRole('region', { name: 'Critical results to acknowledge' });
    expect(banner).toHaveTextContent('Random blood glucose 452 mg/dL');
    expect(banner).toHaveTextContent('called by Vikram Singh to Dr Asha Menon');
    await user.type(within(banner).getByRole('textbox', { name: 'Action taken for Suresh Patel' }), 'Insulin infusion started');
    await user.click(within(banner).getByRole('button', { name: 'Acknowledge' }));
    await waitFor(() => expect(sentTo('POST', '/api/v1/emr/critical-results/crit-1/acknowledge')).toHaveLength(1));
    expect(sentTo('POST', '/api/v1/emr/critical-results/crit-1/acknowledge')[0]!.body).toEqual({ note: 'Insulin infusion started' });
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Critical results to acknowledge' })).not.toBeInTheDocument());
  });
});
