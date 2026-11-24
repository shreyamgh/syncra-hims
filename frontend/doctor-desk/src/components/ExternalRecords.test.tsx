import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { addDays, type HiuConsent } from '../api/hiu';
import { askProblems, ExternalRecordsPanel } from './ExternalRecordsPanel';
import { hospitalToday } from './VaccinationsPanel';

const UHID = 'SYNC-20261121-0005';
interface Call { method: string; url: string; body?: Record<string, unknown> }
let calls: Call[];

const GRANTED: HiuConsent = {
  id: 'c1', status: 'GRANTED', purpose: 'CAREMGT', hiTypes: ['OPConsultation', 'DiagnosticReport'],
  from: '2024-11-21T00:00:00Z', to: '2026-11-21T06:00:00Z', eraseAt: '2026-11-28T18:29:59Z', requestedBy: 'Dr Anil Mehta',
  requestedAt: '2026-11-21T05:00:00Z',
  artefacts: [{ consentId: 'a1', status: 'GRANTED', hipId: 'IN2910000099', hipName: 'City Care Hospital', hiTypes: ['OPConsultation'],
    detailsReceived: true, eraseAt: '2026-11-26T00:00:00Z',
    transfers: [{ id: 't1', status: 'PARTIAL', requestedAt: '2026-11-21T05:02:00Z', completedAt: '2026-11-21T05:03:00Z', records: 1,
      error: '1 record(s) could not be accepted' }] }],
};

beforeEach(() => {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method, url, body });
    const json = (payload: unknown, status = 200) => new Response(JSON.stringify(payload), { status });
    if (url === `/api/v1/abdm/hiu/patients/${UHID}/consent-requests`) return json([GRANTED]);
    if (url === `/api/v1/abdm/hiu/patients/${UHID}/records`) {
      return json([{ id: 'r1', hipName: 'City Care Hospital', recordType: 'OPConsultRecord', title: 'OP Consultation',
        recordDate: '2026-10-02T05:00:00Z', receivedAt: '2026-11-21T05:03:00Z', eraseAt: '2026-11-26T00:00:00Z' }]);
    }
    if (url === '/api/v1/abdm/hiu/records/r1') {
      return json({ record: { id: 'r1', hipName: 'City Care Hospital', title: 'OP Consultation', receivedAt: '2026-11-21T05:03:00Z', eraseAt: '2026-11-26T00:00:00Z' },
        summary: { title: 'OP Consultation', date: '2 Oct 2026, 10:30', author: 'Dr Kavita Rao', sections: [
          { title: 'Medications', lines: ['Tab Metformin 500 mg · 1 tab twice a day after food'] },
          { title: 'Investigations', lines: ['HbA1c: 8.1 % (H) · ref 4–5.6'] }] } });
    }
    if (url === '/api/v1/abdm/hiu/consent-requests') return json({ ...GRANTED, id: 'c2', status: 'REQUESTED', artefacts: [] }, 202);
    if (url === `/api/v1/abdm/hiu/consents/a1/data-requests?uhid=${UHID}`) return json({ id: 't2', status: 'REQUESTED', records: 0 }, 202);
    return json({ detail: `unexpected ${method} ${url}` }, 404);
  }));
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Asking for records from other hospitals', () => {
  it('checks the period and how long records may be kept', () => {
    const today = '2026-11-21';
    const ok = { purpose: 'CAREMGT', hiTypes: ['Prescription' as const], from: '2025-01-01', to: today, keepUntil: addDays(today, 7) };
    expect(askProblems(ok, today)).toEqual([]);
    expect(askProblems({ ...ok, hiTypes: [], keepUntil: addDays(today, 31) }, today)).toEqual([
      'Choose at least one kind of record', 'Records can be kept for at most 30 days']);
    expect(askProblems({ ...ok, from: '2026-12-01', to: '2026-12-02' }, today)).toEqual([
      'The period cannot end after today']);
  });
});

describe('Records from other hospitals', () => {
  it('asks consent, shows what each hospital sent, and opens a record', async () => {
    const user = userEvent.setup();
    const done = vi.fn();
    render(<ExternalRecordsPanel uhid={UHID} isDoctor onError={vi.fn()} onDone={done} />);
    const requests = await screen.findByRole('region', { name: 'Consent requests' });
    expect(await within(requests).findByText('Approved for 1 hospital')).toBeInTheDocument();
    expect(within(requests).getByText('City Care Hospital')).toBeInTheDocument();
    expect(within(requests).getByText(/1 record\(s\) · 1 record\(s\) could not be accepted/)).toBeInTheDocument();

    // A partial transfer can be asked for again.
    await user.click(within(requests).getByRole('button', { name: 'Ask again' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.startsWith('/api/v1/abdm/hiu/consents/a1/data-requests'))).toBe(true));

    await user.click(screen.getByRole('button', { name: 'Ask for consent…' }));
    const form = screen.getByRole('form', { name: 'Ask for consent' });
    await user.click(within(form).getByRole('checkbox', { name: 'Bills' }));
    await user.click(within(form).getByRole('checkbox', { name: 'Vitals & wellness' }));
    await user.click(within(form).getByRole('button', { name: "Ask the patient's consent" }));
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST' && c.url === '/api/v1/abdm/hiu/consent-requests')).toHaveLength(1));
    const today = hospitalToday();
    expect(calls.find((c) => c.url === '/api/v1/abdm/hiu/consent-requests')!.body).toEqual({
      uhid: UHID, purpose: 'CAREMGT', from: addDays(today, -730), to: today, keepUntil: addDays(today, 7),
      hiTypes: ['OPConsultation', 'Prescription', 'DischargeSummary', 'DiagnosticReport', 'ImmunizationRecord', 'HealthDocumentRecord', 'Invoice'],
    });
    expect(done).toHaveBeenCalledWith('Consent asked: the patient approves in their ABHA app.');

    const received = screen.getByRole('region', { name: 'Records received' });
    await user.click(within(received).getByRole('button', { name: 'OP Consultation' }));
    const record = await screen.findByRole('region', { name: 'OP Consultation' });
    expect(record).toHaveTextContent('City Care Hospital · 2 Oct 2026, 10:30 · Dr Kavita Rao');
    expect(within(record).getByText('Tab Metformin 500 mg · 1 tab twice a day after food')).toBeInTheDocument();
    expect(within(record).getByText('HbA1c: 8.1 % (H) · ref 4–5.6')).toBeInTheDocument();
  });

  it('is for the treating doctor only', () => {
    render(<ExternalRecordsPanel uhid={UHID} isDoctor={false} onError={vi.fn()} onDone={vi.fn()} />);
    expect(screen.getByText('Records from other hospitals are shown to the treating doctor.')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
