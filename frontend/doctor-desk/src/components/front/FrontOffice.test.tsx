import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Admission, Bill, BoardEntry } from '../../api/frontoffice';
import type { Session } from '../../auth/session';
import { daysAdmitted, emptyRegistration, registrationProblems } from '../../emr/frontoffice';
import { FrontOffice } from './FrontOffice';

const RECEPTION: Session = { token: 't', subject: 'dev.reception', roles: ['RECEPTIONIST'], expiresAt: Date.now() + 3_600_000 };
const CLERK: Session = { token: 't', subject: 'dev.billing', roles: ['BILLING_CLERK'], expiresAt: Date.now() + 3_600_000 };

interface Call { method: string; url: string; body?: Record<string, unknown> }
let calls: Call[];
let bills: Record<string, Bill>;
const sent = (method: string, url: string) => calls.filter((c) => c.method === method && c.url === url);

const PATIENT = {
  id: 'p1', uhid: 'SYNC-20261121-0007', fullName: 'Kavya Reddy', firstName: 'Kavya', lastName: 'Reddy', gender: 'F', ageYears: 34,
  dobEstimated: false, mobile: '9876543210', abhaVerified: false, deceased: false, registeredAt: '2026-11-21T04:00:00Z', version: 0,
};
const ADMISSION: Admission = {
  admissionId: 'adm-1', admissionNumber: 'IPD/26-27/000004', encounterId: 'enc-9', uhid: 'SYNC-20261120-0003', patientName: 'Ravi Kumar',
  gender: 'M', ageYears: 52, departmentCode: 'GMED', departmentName: 'General Medicine', admittingDoctor: { id: 'd1', name: 'Dr Anil Mehta' },
  admissionType: 'EMERGENCY', status: 'ADMITTED', admittedAt: '2026-11-19T10:00:00Z',
  currentBed: { bedCode: 'MW-03', wardCode: 'MW', wardName: 'Male Ward', bedCategory: 'GENERAL', assignedAt: '2026-11-19T10:00:00Z' },
};
const BOARD: BoardEntry[] = [
  { wardCode: 'MW', wardName: 'Male Ward', wardType: 'GENERAL', bedCode: 'MW-03', bedCategory: 'GENERAL', bedStatus: 'OCCUPIED',
    occupant: { admissionId: 'adm-1', admissionNumber: 'IPD/26-27/000004', uhid: 'SYNC-20261120-0003', patientName: 'Ravi Kumar', gender: 'M', ageYears: 52, admittedAt: '2026-11-19T10:00:00Z', admittingDoctor: 'Dr Anil Mehta' } },
  { wardCode: 'MW', wardName: 'Male Ward', wardType: 'GENERAL', bedCode: 'MW-04', bedCategory: 'GENERAL', bedStatus: 'AVAILABLE' },
  { wardCode: 'FW', wardName: 'Female Ward', wardType: 'GENERAL', bedCode: 'FW-01', bedCategory: 'GENERAL', bedStatus: 'CLEANING' },
];

function bill(id: string, patch: Partial<Bill>): Bill {
  return {
    billId: id, title: 'OPD Bill of Supply', billType: 'OPD', status: 'DRAFT', patient: { uhid: PATIENT.uhid, name: PATIENT.fullName, gender: 'F' },
    tariffPlan: 'GENERAL', lines: [], receipts: [], createdAt: '2026-11-21T04:00:00Z',
    totals: { gross: 350, discount: 0, tax: 0, net: 350, roundOff: 0, payable: 350, received: 0, refunded: 0, balanceDue: 350 }, ...patch,
  };
}

function mockGateway() {
  calls = [];
  bills = {};
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method, url, body });
    const json = (payload: unknown, status = 200) => new Response(payload === undefined ? null : JSON.stringify(payload), { status });
    if (url.startsWith('/api/v1/patients?q=')) return json([]);
    if (url.startsWith('/api/v1/patients') && method === 'POST') {
      if (!url.includes('confirmNotDuplicate=true')) {
        return json({ status: 409, code: 'POSSIBLE_DUPLICATE', detail: 'A similar patient is already registered.',
          candidates: [{ uhid: 'SYNC-20250102-0042', fullName: 'Kavya Reddy', gender: 'F', ageYears: 34, maskedMobile: '******3210', abhaLinked: false, deceased: false, registeredOn: '2025-01-02' }] }, 409);
      }
      return json({ ...PATIENT, ...body, fullName: 'Kavya Reddy' }, 201);
    }
    if (url === `/api/v1/patients/${PATIENT.uhid}`) return json(PATIENT);
    if (url === '/api/v1/opd/departments') return json([{ code: 'GMED', name: 'General Medicine' }]);
    if (url === '/api/v1/opd/departments/GMED/doctors') return json([{ id: 'd1', name: 'Dr Anil Mehta' }]);
    if (url === '/api/v1/opd/visits' && method === 'POST') {
      return json({ visitId: 'v-7', encounterId: 'e-7', tokenNumber: 12, departmentCode: 'GMED', departmentName: 'General Medicine', priority: 'NORMAL', uhid: body.uhid }, 201);
    }
    if (url === '/api/v1/billing/opd-slips') {
      bills['b-opd'] = bill('b-opd', { lines: [
        { lineId: 'l1', serviceCode: 'REG-OPD', category: 'REGISTRATION', description: 'OPD Registration', quantity: 1, unitPrice: 50, grossAmount: 50, discountAmount: 0, gstRate: 0, taxAmount: 0, lineTotal: 50, automatic: true },
        { lineId: 'l2', serviceCode: 'CON-NEW', category: 'CONSULTATION', description: 'Consultation - New Visit', quantity: 1, unitPrice: 300, grossAmount: 300, discountAmount: 0, gstRate: 0, taxAmount: 0, lineTotal: 300, automatic: true },
      ] });
      return json(bills['b-opd'], 201);
    }
    const pay = url.match(/^\/api\/v1\/billing\/bills\/([^/]+)\/payments$/);
    if (pay) {
      const b = bills[pay[1]!]!;
      bills[pay[1]!] = { ...b, billNumber: 'OPB/26-27/000031', status: 'FINAL', totals: { ...b.totals, received: body.payment.amount, balanceDue: b.totals.balanceDue - body.payment.amount } };
      return json({ receiptNumber: 'RCT/26-27/000088' }, 201);
    }
    const one = url.match(/^\/api\/v1\/billing\/bills\/([^/?]+)$/);
    if (one) return json(bills[one[1]!]);
    if (url === '/api/v1/ipd/wards') return json([{ wardCode: 'MW', wardName: 'Male Ward', wardType: 'GENERAL', total: 2, available: 1, occupied: 1, reserved: 0, cleaning: 0, outOfService: 0 }]);
    if (url === '/api/v1/ipd/beds') return json(BOARD);
    if (url === '/api/v1/ipd/admissions' && method === 'GET') return json([ADMISSION]);
    if (url.startsWith('/api/v1/ipd/beds/available')) return json([{ bedCode: 'MW-04', wardCode: 'MW', wardName: 'Male Ward', bedCategory: 'GENERAL', hasOxygen: true, hasMonitor: false, hasVentilator: false }]);
    if (url === '/api/v1/ipd/admissions/adm-1/bed') return json({ ...ADMISSION, currentBed: { ...ADMISSION.currentBed!, bedCode: body.bedCode } });
    if (url === '/api/v1/ipd/admissions' && method === 'POST') return json({ ...ADMISSION, admissionId: 'adm-2', patientName: 'Kavya Reddy', admissionNumber: 'IPD/26-27/000005' }, 201);
    if (url === '/api/v1/billing/tariffs') return json([{ id: 't1', code: 'GENERAL', name: 'General (Self-pay)', payerType: 'SELF', isDefault: true }]);
    if (url.startsWith('/api/v1/billing/tariffs/GENERAL/rates')) return json([{ serviceCode: 'LAB-CBC', serviceName: 'Complete Blood Count', category: 'LABORATORY', amount: 300 }, { serviceCode: 'BED-DAY', serviceName: 'Bed Charges', category: 'BED', amount: 800 }]);
    if (url === '/api/v1/billing/admissions/adm-1/bill') {
      bills['b-ipd'] ??= bill('b-ipd', { title: 'IPD Bill (interim)', billType: 'IPD', status: 'INTERIM', admissionId: 'adm-1', admissionNumber: 'IPD/26-27/000004',
        patient: { uhid: ADMISSION.uhid, name: 'Ravi Kumar', gender: 'M' },
        lines: [{ lineId: 'bd', serviceCode: 'BED-DAY', category: 'BED', description: 'Bed Charges (General)', quantity: 2, unitPrice: 800, grossAmount: 1600, discountAmount: 0, gstRate: 0, taxAmount: 0, lineTotal: 1600, automatic: true }],
        totals: { gross: 1600, discount: 0, tax: 0, net: 1600, roundOff: 0, payable: 1600, received: 0, refunded: 0, balanceDue: 1600 } });
      return json(bills['b-ipd']);
    }
    if (url === '/api/v1/billing/bills/b-ipd/lines') {
      const b = bills['b-ipd']!;
      bills['b-ipd'] = { ...b, lines: [...b.lines, { lineId: 'cbc', serviceCode: 'LAB-CBC', category: 'LABORATORY', description: 'Complete Blood Count', quantity: body.quantity, unitPrice: 300, grossAmount: 300, discountAmount: 0, gstRate: 0, taxAmount: 0, lineTotal: 300, automatic: false }] };
      return json(bills['b-ipd'], 201);
    }
    if (url === '/api/v1/billing/admissions/adm-1/advances') return json({ receiptNumber: 'RCT/26-27/000090', type: 'ADVANCE', amount: body.payment.amount }, 201);
    return json({ detail: `unexpected ${method} ${url}` }, 404);
  }));
}

beforeEach(mockGateway);
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Front office rules', () => {
  it('checks a registration the way the server will', () => {
    expect(registrationProblems(emptyRegistration(), '2026-11-21')).toEqual([
      'Enter the first name', 'Choose the sex', 'Enter the date of birth, or switch to age in years', 'Enter a mobile number (the patient’s or a relative’s)',
    ]);
    const ok = { ...emptyRegistration(), firstName: 'Kavya', gender: 'F' as const, dateOfBirth: '1992-04-01', mobile: '9876543210' };
    expect(registrationProblems(ok, '2026-11-21')).toEqual([]);
    expect(registrationProblems({ ...ok, mobile: '5876543210', pincode: '0560', relationType: 'D/O' }, '2026-11-21')).toEqual([
      'The mobile number must be 10 digits starting with 6, 7, 8 or 9', 'The PIN code must be 6 digits', 'Give the name for the relation chosen',
    ]);
    expect(registrationProblems({ ...ok, ageBy: 'AGE', ageYears: '130' }, '2026-11-21')).toEqual(['Enter the age in whole years (0 to 125)']);
  });

  it('counts hospital days by the midnight census', () => {
    expect(daysAdmitted('2026-11-21T03:00:00Z', new Date('2026-11-21T15:00:00Z'))).toBe(1);
    expect(daysAdmitted('2026-11-19T10:00:00Z', new Date('2026-11-21T05:00:00Z'))).toBe(2);
  });
});

describe('Front office', () => {
  it('registers past a possible duplicate, issues a token and collects the OPD fee', async () => {
    const user = userEvent.setup();
    render(<FrontOffice session={RECEPTION} />);
    await user.click(screen.getByRole('button', { name: 'Register new patient' }));
    const form = screen.getByRole('form', { name: 'Register a patient' });
    await user.click(within(form).getByRole('button', { name: 'Register' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Enter the first name');

    await user.type(within(form).getByRole('textbox', { name: 'First name' }), 'Kavya');
    await user.type(within(form).getByRole('textbox', { name: 'Last name' }), 'Reddy');
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Sex' }), 'F');
    await user.type(within(form).getByLabelText('Date of birth'), '1992-04-01');
    await user.type(within(form).getByRole('textbox', { name: 'Mobile' }), '9876543210');
    await user.click(within(form).getByRole('button', { name: 'Register' }));

    const duplicates = await within(form).findByRole('region', { name: 'Possible duplicates' });
    expect(duplicates).toHaveTextContent('SYNC-20250102-0042');
    await user.click(within(duplicates).getByRole('button', { name: /different person/ }));
    await waitFor(() => expect(sent('POST', '/api/v1/patients?confirmNotDuplicate=true')).toHaveLength(1));
    expect(sent('POST', '/api/v1/patients?confirmNotDuplicate=true')[0]!.body).toMatchObject({
      firstName: 'Kavya', lastName: 'Reddy', gender: 'F', dateOfBirth: '1992-04-01', mobile: '9876543210',
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Registered Kavya Reddy: UHID SYNC-20261121-0007.');

    const visit = screen.getByRole('region', { name: 'OPD visit' });
    await user.selectOptions(within(visit).getByRole('combobox', { name: 'Department' }), 'GMED');
    await user.click(within(visit).getByRole('button', { name: 'Issue token' }));
    expect(await within(visit).findByText('#12')).toBeInTheDocument();
    expect(sent('POST', '/api/v1/opd/visits')[0]!.body).toEqual({ uhid: 'SYNC-20261121-0007', departmentCode: 'GMED', visitType: 'NEW' });

    await user.click(within(visit).getByRole('button', { name: 'Issue OPD slip' }));
    await user.selectOptions(await within(visit).findByRole('combobox', { name: 'Mode' }), 'UPI');
    await user.type(within(visit).getByRole('textbox', { name: 'Reference' }), '432198765012');
    await user.click(within(visit).getByRole('button', { name: /Receive ₹350/ }));
    await waitFor(() => expect(sent('POST', '/api/v1/billing/bills/b-opd/payments')).toHaveLength(1));
    expect(sent('POST', '/api/v1/billing/bills/b-opd/payments')[0]!.body).toEqual({
      type: 'SETTLEMENT', payment: { mode: 'UPI', amount: 350, reference: '432198765012' },
    });
    expect(await within(visit).findByText('Paid')).toBeInTheDocument();
    expect(within(visit).getByText('OPB/26-27/000031')).toBeInTheDocument();
  });

  it('shows the bed board, moves a patient to a free bed, and admits from the patient card', async () => {
    const user = userEvent.setup();
    render(<FrontOffice session={RECEPTION} />);
    await user.click(screen.getByRole('tab', { name: 'Admissions & beds' }));
    const board = screen.getByRole('region', { name: 'Bed board' });
    expect(await within(board).findByText('1 of 2 available')).toBeInTheDocument();
    expect(within(board).getByRole('button', { name: 'Bed FW-01: cleaning' })).toBeDisabled();
    await user.click(within(board).getByRole('button', { name: 'Bed MW-03: Ravi Kumar' }));

    const move = await screen.findByRole('region', { name: 'Move bed' });
    await user.click(within(move).getByRole('button', { name: 'Show free beds' }));
    await user.selectOptions(await within(move).findByRole('combobox', { name: 'To bed' }), 'MW-04');
    await user.click(within(move).getByRole('button', { name: 'Move' }));
    await waitFor(() => expect(sent('POST', '/api/v1/ipd/admissions/adm-1/bed')).toHaveLength(1));
    expect(sent('POST', '/api/v1/ipd/admissions/adm-1/bed')[0]!.body).toEqual({ bedCode: 'MW-04', reason: 'TRANSFER' });
    // A receptionist does not discharge (nurses, doctors and administrators do).
    expect(screen.queryByRole('region', { name: 'Discharge' })).not.toBeInTheDocument();

    // Admit: find the patient, then Admit… on the card.
    await user.click(screen.getByRole('tab', { name: 'Patients & OPD' }));
    vi.mocked(fetch).mockImplementationOnce(async () => new Response(JSON.stringify([{ uhid: PATIENT.uhid, fullName: 'Kavya Reddy', gender: 'F', ageYears: 34, abhaLinked: false, deceased: false, registeredOn: '2026-11-21' }]), { status: 200 }));
    await user.type(screen.getByRole('textbox', { name: 'Search patients' }), 'Kavya');
    await user.click(await screen.findByText('Kavya Reddy'));
    await user.click(await screen.findByRole('button', { name: 'Admit…' }));
    const admit = await screen.findByRole('form', { name: 'Admit a patient' });
    await user.click(within(admit).getByRole('button', { name: 'Admit' }));
    expect(within(admit).getByRole('alert')).toHaveTextContent('Choose the department');
    await user.selectOptions(within(admit).getByRole('combobox', { name: 'Department' }), 'GMED');
    await user.selectOptions(await within(admit).findByRole('combobox', { name: 'Admitting doctor' }), 'd1');
    await user.selectOptions(within(admit).getByRole('combobox', { name: /Bed/ }), 'MW-04');
    await user.type(within(admit).getByRole('textbox', { name: 'Provisional diagnosis' }), 'Dengue with warning signs');
    await user.click(within(admit).getByRole('button', { name: 'Admit' }));
    await waitFor(() => expect(sent('POST', '/api/v1/ipd/admissions')).toHaveLength(1));
    expect(sent('POST', '/api/v1/ipd/admissions')[0]!.body).toEqual({
      uhid: PATIENT.uhid, departmentCode: 'GMED', admittingDoctorId: 'd1', admissionType: 'ELECTIVE', bedCode: 'MW-04',
      tariffPlanCode: 'GENERAL', provisionalDiagnosis: 'Dengue with warning signs',
    });
  });

  it("works an in-patient's bill: adds a service from the rate card and takes an advance", async () => {
    const user = userEvent.setup();
    render(<FrontOffice session={CLERK} />);
    await user.click(screen.getByRole('tab', { name: 'Billing' }));
    const inpatients = await screen.findByRole('list', { name: 'In-patients' });
    await user.click(await within(inpatients).findByRole('button', { name: /Ravi Kumar/ }));
    const bill = await screen.findByRole('region', { name: 'Bill' });
    expect(await within(bill).findByText('Bed Charges (General)')).toBeInTheDocument();
    // Automatic lines (bed-days) cannot be removed by hand.
    expect(within(bill).queryByRole('button', { name: 'Remove Bed Charges (General)' })).not.toBeInTheDocument();

    const add = within(bill).getByRole('group', { name: 'Add a service' });
    // Bed, nursing and consultation charges come from the census, not from this list.
    expect(within(add).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose…', 'Complete Blood Count · ₹300.00']);
    await user.selectOptions(within(add).getByRole('combobox', { name: 'Add a service' }), 'LAB-CBC');
    await user.click(within(add).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(sent('POST', '/api/v1/billing/bills/b-ipd/lines')).toHaveLength(1));
    expect(sent('POST', '/api/v1/billing/bills/b-ipd/lines')[0]!.body).toEqual({ serviceCode: 'LAB-CBC', quantity: 1 });
    expect(await within(bill).findByRole('button', { name: 'Remove Complete Blood Count' })).toBeInTheDocument();

    const payment = within(bill).getByRole('region', { name: 'Payment' });
    await user.selectOptions(within(payment).getByRole('combobox', { name: 'Type' }), 'ADVANCE');
    await user.type(within(payment).getByRole('textbox', { name: 'Amount (₹)' }), '5000');
    await user.click(within(payment).getByRole('button', { name: 'Receive' }));
    await waitFor(() => expect(sent('POST', '/api/v1/billing/admissions/adm-1/advances')).toHaveLength(1));
    expect(sent('POST', '/api/v1/billing/admissions/adm-1/advances')[0]!.body).toEqual({ payment: { mode: 'CASH', amount: 5000 } });
  });
});
