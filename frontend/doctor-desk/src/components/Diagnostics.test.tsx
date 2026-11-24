import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CriticalResult, ImagingStudy, OrderRecord, ResultTemplate } from '../api/types';
import { type Session, signIn, signOut } from '../auth/session';
import { App } from '../App';
import { ImagingDesk } from './ImagingDesk';
import { LabDesk } from './LabDesk';

const session = (subject: string, ...roles: string[]): Session => ({ token: 't', subject, roles, expiresAt: Date.now() + 3_600_000 });
const LAB = session('dev.lab', 'LAB_TECHNICIAN');
const NURSE = session('dev.nurse', 'NURSE');
const RADIOLOGIST = session('dev.radiologist', 'RADIOLOGIST');
const RADIOGRAPHER = session('dev.radiographer', 'RADIOGRAPHER');

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

function order(id: string, patch: Partial<OrderRecord>): OrderRecord {
  return {
    id, encounterId: `enc-${id}`, patientId: `p-${id}`, uhid: `SYNC-20261121-000${id.length}`, patientName: 'Lakshmi Iyer',
    serviceCode: 'LAB-RFT', serviceName: 'Renal Function Test', orderType: 'LABORATORY', status: 'ORDERED', priority: 'ROUTINE',
    orderedBy: 'Anil Mehta', orderedAt: ago(40), ...patch,
  };
}

const TEMPLATE: ResultTemplate = {
  sex: 'F', ageYears: 68, rangesApply: true, analytes: [
    { code: 'UREA', name: 'Blood urea', unit: 'mg/dL', decimals: 0, low: 15, high: 40, referenceRange: '15-40' },
    { code: 'CREAT', name: 'Serum creatinine', unit: 'mg/dL', decimals: 2, low: 0.6, high: 1.1, referenceRange: '0.6-1.1' },
    { code: 'POTASSIUM', name: 'Potassium', unit: 'mmol/L', decimals: 1, low: 3.5, high: 5.1, criticalLow: 2.8, criticalHigh: 6.2, referenceRange: '3.5-5.1' },
  ],
};

interface Call { method: string; url: string; body?: Record<string, unknown> }
let calls: Call[];
let labOrders: OrderRecord[];
let radiologyOrders: OrderRecord[];
let studies: ImagingStudy[];
let critical: CriticalResult[];

const sent = (method: string, url: string) => calls.filter((c) => c.method === method && c.url === url);

function mockGateway() {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method, url, body });
    const json = (payload: unknown, status = 200) => new Response(JSON.stringify(payload), { status });
    const parsed = new URL(url, 'http://desk');
    let m: RegExpMatchArray | null;

    if (parsed.pathname === '/api/v1/emr/worklist') {
      const statuses = parsed.searchParams.get('status')!.split(',');
      const source = parsed.searchParams.get('type') === 'RADIOLOGY' ? radiologyOrders : labOrders;
      return json(source.filter((o) => statuses.includes(o.status)));
    }
    if ((m = url.match(/^\/api\/v1\/emr\/orders\/([^/]+)\/analytes$/))) return json(TEMPLATE);
    if ((m = url.match(/^\/api\/v1\/emr\/orders\/([^/]+)\/sample$/))) {
      labOrders = labOrders.map((o) => (o.id === m![1] ? { ...o, status: 'SAMPLE_COLLECTED', sampleCollectedAt: ago(0) } : o));
      return json(labOrders.find((o) => o.id === m![1]));
    }
    if ((m = url.match(/^\/api\/v1\/emr\/orders\/([^/]+)\/result$/))) {
      labOrders = labOrders.map((o) => (o.id === m![1] ? { ...o, status: 'REPORTED', completedAt: ago(0), resultValues: body.values } : o));
      const order = labOrders.find((o) => o.id === m![1])!;
      const k = (body.values as { code: string; value: number }[]).find((v) => v.code === 'POTASSIUM' && v.value > 6.2);
      if (k) {
        critical.push({ id: `crit-${order.id}`, orderId: order.id, encounterId: order.encounterId, uhid: order.uhid!, patientName: order.patientName!,
          serviceName: order.serviceName, criticalValues: [{ ...k, name: 'Potassium', unit: 'mmol/L', flag: 'C' }], reportedBy: 'Vikram Singh',
          reportedAt: ago(0), orderedBy: order.orderedBy, status: 'TO_COMMUNICATE' });
      }
      return json(order);
    }
    if (parsed.pathname === '/api/v1/emr/critical-results') {
      return json(critical.filter((c) => parsed.searchParams.get('communicated') !== 'false' || !c.communicatedAt));
    }
    if ((m = url.match(/^\/api\/v1\/emr\/critical-results\/([^/]+)\/communicate$/))) {
      critical = critical.map((c) => (c.id === m![1] ? { ...c, communicatedAt: ago(0), communicatedTo: body.communicatedTo, status: 'COMMUNICATED' } : c));
      return json(critical.find((c) => c.id === m![1]));
    }
    if (parsed.pathname === '/api/v1/emr/imaging/studies') {
      const statuses = parsed.searchParams.getAll('reportStatus');
      const matched = parsed.searchParams.get('matched');
      return json(studies.filter((s) => (statuses.length === 0 || statuses.includes(s.reportStatus))
        && (matched === null || Boolean(s.orderId) === (matched === 'true'))));
    }
    if ((m = url.match(/^\/api\/v1\/emr\/imaging\/studies\/([^/]+)\/match$/))) {
      const target = radiologyOrders.find((o) => o.id === body.orderId)!;
      studies = studies.map((s) => (s.id === m![1] ? { ...s, orderId: target.id, serviceName: target.serviceName, accessionNumber: target.accessionNumber } : s));
      radiologyOrders = radiologyOrders.map((o) => (o.id === target.id ? { ...o, status: 'COMPLETED' } : o));
      return json(studies.find((s) => s.id === m![1]));
    }
    if ((m = url.match(/^\/api\/v1\/emr\/imaging\/studies\/([^/]+)\/report$/))) {
      const current = studies.find((s) => s.id === m![1])!;
      const status = current.reportStatus === 'UNREPORTED' ? 'FINAL' : 'ADDENDUM';
      studies = studies.map((s) => (s.id === m![1]
        ? { ...s, reportStatus: status, reportContent: body.content, reportedBy: 'Kavita Rao', reportedAt: ago(0) } : s));
      return json(studies.find((s) => s.id === m![1]));
    }
    if (url === '/api/v1/opd/departments') return json([]);
    return json({ detail: `unexpected ${method} ${url}` }, 404);
  }));
}

beforeEach(() => {
  critical = [];
  labOrders = [
    order('a', { priority: 'STAT', status: 'SAMPLE_COLLECTED', sampleCollectedAt: ago(10), clinicalIndication: 'CKD, on ACE inhibitor' }),
    order('bb', { serviceCode: 'LAB-CBC', serviceName: 'Complete Blood Count', patientName: 'Ravi Shankar' }),
  ];
  radiologyOrders = [
    order('x', { orderType: 'RADIOLOGY', serviceCode: 'RAD-XCH', serviceName: 'X-Ray Chest PA View', accessionNumber: 'RAD/26-27/000007', uhid: 'SYNC-1', patientName: 'Meena Joshi' }),
    order('y', { orderType: 'RADIOLOGY', serviceCode: 'RAD-CTH', serviceName: 'CT Head (Plain)', accessionNumber: 'RAD/26-27/000008', uhid: 'SYNC-2', patientName: 'Arjun Rao' }),
  ];
  studies = [
    {
      id: 'st-1', patientId: 'p1', uhid: 'SYNC-3', patientName: 'Farah Khan', orderId: 'o-3', serviceName: 'USG Whole Abdomen',
      studyInstanceUid: '1.2.3', accessionNumber: 'RAD/26-27/000003', modality: 'US', description: 'Abdomen', seriesCount: 1,
      instanceCount: 24, reportStatus: 'UNREPORTED', receivedAt: ago(30), clinicalIndication: 'Right upper quadrant pain', priority: 'URGENT',
      orderedBy: 'Anil Mehta', dicomWebPath: '/pacs/dicom-web/studies/1.2.3',
    },
    {
      id: 'st-2', patientId: 'p2', uhid: 'SYNC-1', patientName: 'Meena Joshi', studyInstanceUid: '1.2.4', modality: 'DX',
      description: 'Chest PA', instanceCount: 1, reportStatus: 'UNREPORTED', receivedAt: ago(5), dicomWebPath: '/pacs/dicom-web/studies/1.2.4',
    },
  ];
  mockGateway();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  signOut();
  localStorage.clear();
});

describe('Laboratory desk', () => {
  it('reports a result: flags preview as typed, critical values need confirming and name the ordering doctor', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true);
    const user = userEvent.setup();
    render(<LabDesk session={LAB} />);

    expect(await screen.findByRole('tab', { name: /To report/ })).toHaveAttribute('aria-selected', 'true');
    const row = await screen.findByRole('row', { name: 'Renal Function Test for Lakshmi Iyer' });
    expect(within(row).getByText('STAT')).toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: 'Enter result' }));

    const values = await screen.findByRole('table', { name: 'Result values' });
    await user.type(within(values).getByRole('textbox', { name: 'Blood urea result' }), '38{Enter}');
    // Enter moves to the next value.
    expect(within(values).getByRole('textbox', { name: 'Serum creatinine result' })).toHaveFocus();
    await user.keyboard('1.2');
    await user.type(within(values).getByRole('textbox', { name: 'Potassium result' }), '6.5');
    expect(within(values).getByLabelText('Blood urea flag preview')).toHaveTextContent('Normal');
    expect(within(values).getByLabelText('Serum creatinine flag preview')).toHaveTextContent('High');
    expect(within(values).getByLabelText('Potassium flag preview')).toHaveTextContent('Critical');

    // Declining the critical-value prompt sends nothing.
    await user.click(screen.getByRole('button', { name: 'Report result' }));
    expect(confirm).toHaveBeenCalledWith(expect.stringMatching(/Potassium 6\.5 mmol\/L[\s\S]*Anil Mehta/));
    expect(sent('POST', '/api/v1/emr/orders/a/result')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Report result' }));
    await waitFor(() => expect(sent('POST', '/api/v1/emr/orders/a/result')).toHaveLength(1));
    expect(sent('POST', '/api/v1/emr/orders/a/result')[0]!.body).toEqual({
      values: [
        { code: 'UREA', name: 'Blood urea', value: 38, unit: 'mg/dL' },
        { code: 'CREAT', name: 'Serum creatinine', value: 1.2, unit: 'mg/dL' },
        { code: 'POTASSIUM', name: 'Potassium', value: 6.5, unit: 'mmol/L' },
      ],
    });
    expect(await screen.findByRole('status')).toHaveTextContent('critical value: Potassium 6.5 mmol/L. Call Anil Mehta now and log the call.');
    expect(screen.getByRole('tab', { name: /Reported/ })).toHaveTextContent('1');

    // The desk moves to the call list; the call is logged only once the value has been read back.
    expect(screen.getByRole('tab', { name: /Critical to call/ })).toHaveAttribute('aria-selected', 'true');
    const call = await screen.findByRole('row', { name: 'Critical Renal Function Test for Lakshmi Iyer' });
    expect(within(call).getByRole('textbox', { name: /Told whom/ })).toHaveValue('Anil Mehta');
    expect(within(call).getByRole('button', { name: 'Log call' })).toBeDisabled();
    await user.click(within(call).getByRole('checkbox', { name: 'Read back' }));
    await user.click(within(call).getByRole('button', { name: 'Log call' }));
    await waitFor(() => expect(sent('POST', '/api/v1/emr/critical-results/crit-a/communicate')).toHaveLength(1));
    expect(sent('POST', '/api/v1/emr/critical-results/crit-a/communicate')[0]!.body)
      .toEqual({ communicatedTo: 'Anil Mehta', method: 'PHONE', readBack: true });
    expect(await screen.findByText('Every critical result has been called to the treating team.')).toBeInTheDocument();
  });

  it('lets a nurse collect samples but not report them', async () => {
    const user = userEvent.setup();
    render(<LabDesk session={NURSE} />);
    expect(await screen.findByRole('tab', { name: /To collect/ })).toHaveAttribute('aria-selected', 'true');
    const cbc = await screen.findByRole('row', { name: 'Complete Blood Count for Ravi Shankar' });
    await user.click(within(cbc).getByRole('button', { name: 'Collect sample' }));
    await waitFor(() => expect(sent('POST', '/api/v1/emr/orders/bb/sample')).toHaveLength(1));
    expect(await screen.findByRole('status')).toHaveTextContent('Sample collected: Complete Blood Count for Ravi Shankar.');

    await user.click(screen.getByRole('tab', { name: /To report/ }));
    expect(screen.getByRole('table', { name: 'To report' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enter result' })).not.toBeInTheDocument();
  });
});

describe('Imaging desk', () => {
  it('lets the radiologist sign a report with an impression, then an addendum', async () => {
    const user = userEvent.setup();
    render(<ImagingDesk session={RADIOLOGIST} />);
    const row = await screen.findByRole('row', { name: 'USG Whole Abdomen for Farah Khan' });
    await user.click(within(row).getByRole('button', { name: 'Report' }));

    const panel = screen.getByRole('complementary', { name: 'Report' });
    expect(within(panel).getByText('Right upper quadrant pain')).toBeInTheDocument();
    await user.type(within(panel).getByRole('textbox', { name: 'Findings' }), 'Gall bladder distended with calculi. CBD normal.');
    await user.click(within(panel).getByRole('button', { name: 'Sign report' }));
    expect(within(panel).getByRole('alert')).toHaveTextContent('Give the impression');
    expect(sent('POST', '/api/v1/emr/imaging/studies/st-1/report')).toHaveLength(0);

    await user.type(within(panel).getByRole('textbox', { name: 'Impression' }), 'Cholelithiasis');
    await user.click(within(panel).getByRole('button', { name: 'Sign report' }));
    await waitFor(() => expect(sent('POST', '/api/v1/emr/imaging/studies/st-1/report')).toHaveLength(1));
    expect(sent('POST', '/api/v1/emr/imaging/studies/st-1/report')[0]!.body)
      .toEqual({ content: 'Gall bladder distended with calculi. CBD normal.', impression: 'Cholelithiasis' });

    // The panel now shows the signed report; an addendum starts from it and must change it.
    const signed = await within(screen.getByRole('complementary', { name: 'Report' })).findByRole('region', { name: 'Signed report' });
    expect(signed).toHaveTextContent('Signed by Kavita Rao');
    await user.click(screen.getByRole('button', { name: 'Add addendum' }));
    const findings = screen.getByRole('textbox', { name: 'Findings' });
    expect(findings).toHaveValue('Gall bladder distended with calculi. CBD normal.');
    await user.type(screen.getByRole('textbox', { name: 'Impression' }), 'Cholelithiasis');
    await user.click(screen.getByRole('button', { name: 'Sign addendum' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Change the report to add an addendum');
    await user.type(findings, ' No pericholecystic fluid.');
    await user.click(screen.getByRole('button', { name: 'Sign addendum' }));
    await waitFor(() => expect(sent('POST', '/api/v1/emr/imaging/studies/st-1/report')).toHaveLength(2));
    expect(await screen.findByRole('status')).toHaveTextContent('Addendum signed');
  });

  it("lets the radiographer match a study to that patient's order, and not report", async () => {
    const user = userEvent.setup();
    render(<ImagingDesk session={RADIOGRAPHER} />);
    expect(await screen.findByRole('tab', { name: /Awaiting images/ })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('RAD/26-27/000007')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: /Unmatched studies/ }));
    const study = screen.getByRole('row', { name: 'Chest PA for Meena Joshi' });
    const choice = within(study).getByRole('combobox', { name: "Order for Meena Joshi's study" });
    // Only Meena Joshi's open order is offered.
    expect(within(choice).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Choose the order…', expect.stringContaining('X-Ray Chest PA View · RAD/26-27/000007'),
    ]);
    await user.selectOptions(choice, 'x');
    await user.click(within(study).getByRole('button', { name: 'Match' }));
    await waitFor(() => expect(sent('POST', '/api/v1/emr/imaging/studies/st-2/match')).toHaveLength(1));
    expect(sent('POST', '/api/v1/emr/imaging/studies/st-2/match')[0]!.body).toEqual({ orderId: 'x' });
    // The confirmation names the order's accession (the study arrived without one).
    expect(await screen.findByRole('status')).toHaveTextContent('Matched Chest PA to X-Ray Chest PA View (RAD/26-27/000007).');

    await user.click(screen.getByRole('tab', { name: /To report/ }));
    await user.click(within(screen.getByRole('row', { name: 'USG Whole Abdomen for Farah Khan' })).getByRole('button', { name: 'Report' }));
    expect(screen.getByText('Waiting for a radiologist to report.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign report' })).not.toBeInTheDocument();
  });
});

describe('Desk routing', () => {
  const signInAs = (subject: string, roles: string[]) => signIn(session(subject, ...roles));

  it('opens the laboratory for a lab technician', async () => {
    signInAs('dev.lab', ['LAB_TECHNICIAN']);
    render(<App />);
    expect(await screen.findByRole('region', { name: 'Laboratory worklist' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Desks' })).not.toBeInTheDocument();
  });

  it('lets a nurse switch between the doctor desk and the laboratory, and remembers the choice', async () => {
    signInAs('dev.nurse', ['NURSE']);
    const user = userEvent.setup();
    const { unmount } = render(<App />);
    const desks = await screen.findByRole('navigation', { name: 'Desks' });
    expect(within(desks).getByRole('button', { name: 'Doctor Desk' })).toHaveAttribute('aria-current', 'page');
    await user.click(within(desks).getByRole('button', { name: 'Laboratory' }));
    expect(await screen.findByRole('region', { name: 'Laboratory worklist' })).toBeInTheDocument();
    unmount();
    render(<App />);
    expect(await screen.findByRole('region', { name: 'Laboratory worklist' })).toBeInTheDocument();
  });
});
