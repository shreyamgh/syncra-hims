import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Drug, PendingPrescription } from '../../api/pharmacy';
import { endOfMonth, packExpiry } from '../../api/pharmacy';
import type { Session } from '../../auth/session';
import { PharmacyDesk } from './PharmacyDesk';

const PHARMACIST: Session = { token: 't', subject: 'dev.pharmacist', roles: ['PHARMACIST'], expiresAt: Date.now() + 3_600_000 };

interface Call { method: string; url: string; body?: Record<string, unknown> }
let calls: Call[];
const sent = (method: string, url: string) => calls.filter((c) => c.method === method && c.url === url);

const drug = (patch: Partial<Drug>): Drug => ({
  id: 'd', code: 'X', brandName: 'X', dosageForm: 'TABLET', highAlert: false, gstRate: 12, unit: 'tablet', reorderLevel: 0,
  mainStore: 0, dispensary: 0, nearExpiry: 0, expired: 0, belowReorder: false, ...patch,
});
const MOX = drug({ id: 'amx', code: 'AMX-500', brandName: 'Mox', strength: '500 mg', dosageForm: 'CAPSULE', schedule: 'H', unit: 'capsule', reorderLevel: 100, mainStore: 200, dispensary: 40, earliestExpiry: '2027-03-31', generics: 'Amoxicillin' });
const TAXIM = drug({ id: 'cfx', code: 'CFX-200', brandName: 'Taxim-O', strength: '200 mg', schedule: 'H1', reorderLevel: 50, mainStore: 30, dispensary: 6, nearExpiry: 6, earliestExpiry: '2026-12-31', belowReorder: true, generics: 'Cefixime' });
const DOLO = drug({ id: 'pcm', code: 'PCM-650', brandName: 'Dolo 650', schedule: 'OTC', reorderLevel: 100, mainStore: 500, dispensary: 100, generics: 'Paracetamol' });

const PENDING: PendingPrescription[] = [{
  prescription: { id: 'rx-1', uhid: 'SYNC-20261121-0004', patientName: 'Arjun Nair', gender: 'M', prescriber: 'Dr Anil Mehta', prescriberRegistration: 'KMC-45678', encounterType: 'OPD', issuedAt: '2026-11-21T05:10:00Z' },
  ageYears: 41,
  items: [
    { item: { id: 'i1', lineNo: 1, drugId: 'amx', drugText: 'Amoxicillin 500 mg capsule', dose: '1 capsule', frequency: 'TDS', durationDays: 5 }, suggested: MOX, suggestedQuantity: 15 },
    { item: { id: 'i2', lineNo: 2, drugText: 'Cefixime 200 mg tablet', dose: '1 tablet', frequency: 'BD', durationDays: 5 }, suggested: TAXIM, suggestedQuantity: 10 },
    { item: { id: 'i3', lineNo: 3, drugText: 'Steam inhalation', frequency: 'BD' } },
  ],
}];

function mockGateway() {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ method, url, body });
    const json = (payload: unknown, status = 200) => new Response(payload === undefined ? null : JSON.stringify(payload), { status });
    if (url === '/api/v1/pharmacy/drugs') return json([MOX, TAXIM, DOLO]);
    if (url === '/api/v1/pharmacy/prescriptions') return json(PENDING);
    if (url === '/api/v1/pharmacy/dispenses' && method === 'POST') {
      return json({
        id: 'dsp-1', number: 'DSP/26-27/000012', prescriptionId: 'rx-1', uhid: 'SYNC-20261121-0004', patientName: 'Arjun Nair', prescriber: 'Dr Anil Mehta',
        dispensedBy: 'Nandini Shetty', dispensedAt: '2026-11-21T06:00:00Z', total: 202.5,
        lines: [
          { id: 'l1', prescriptionItemId: 'i1', drugCode: 'AMX-500', brandName: 'Mox', strength: '500 mg', schedule: 'H', batchNumber: 'MX2611', expiryDate: '2027-03-31', quantity: 15, unit: 'capsule', mrp: 7.5, gstRate: 12, amount: 112.5 },
          { id: 'l2', prescriptionItemId: 'i2', drugCode: 'CFX-200', brandName: 'Taxim-O', strength: '200 mg', schedule: 'H1', batchNumber: 'TX0912', expiryDate: '2026-12-31', quantity: 6, unit: 'tablet', mrp: 15, gstRate: 12, amount: 90 },
        ],
      }, 201);
    }
    if (url === '/api/v1/billing/pharmacy-sales') {
      return json({
        billId: 'b-ph', billNumber: 'PHB/26-27/000003', title: 'Tax Invoice', billType: 'PHARMACY', status: 'FINAL', patient: { uhid: 'SYNC-20261121-0004', name: 'Arjun Nair', gender: 'M' },
        tariffPlan: 'GENERAL', lines: [], receipts: [], createdAt: '2026-11-21T06:01:00Z',
        totals: { gross: 180.8, discount: 0, tax: 21.7, net: 202.5, roundOff: 0.5, payable: 203, received: 203, refunded: 0, balanceDue: 0 },
      }, 201);
    }
    if (url === '/api/v1/pharmacy/batches?drugId=cfx') {
      return json([
        { batchId: 'bt-old', drugId: 'cfx', drugCode: 'CFX-200', brandName: 'Taxim-O', batchNumber: 'TX0101', expiryDate: '2026-01-31', mrp: 15, storeCode: 'MAIN', quantity: 20 },
        { batchId: 'bt-new', drugId: 'cfx', drugCode: 'CFX-200', brandName: 'Taxim-O', batchNumber: 'TX0912', expiryDate: '2026-12-31', mrp: 15, storeCode: 'DISPENSARY', quantity: 6 },
      ]);
    }
    if (url === '/api/v1/pharmacy/write-offs') return json(undefined, 204);
    if (url === '/api/v1/pharmacy/receipts') return json({ id: 'grn-1', number: 'GRN/26-27/000007' }, 201);
    if (url === '/api/v1/pharmacy/requisitions' && method === 'GET') {
      return json([{ id: 'req-1', number: 'REQ/26-27/000002', requestingStore: 'DISPENSARY', issuingStore: 'MAIN', status: 'REQUESTED', requestedBy: 'Nandini Shetty', requestedAt: '2026-11-21T04:00:00Z',
        lines: [{ id: 'rl', drugId: 'cfx', drugCode: 'CFX-200', brandName: 'Taxim-O', requested: 30, issued: 0 }] }]);
    }
    if (url === '/api/v1/pharmacy/requisitions/req-1/issue') {
      return json({ id: 'req-1', number: 'REQ/26-27/000002', requestingStore: 'DISPENSARY', issuingStore: 'MAIN', status: 'PARTIAL', requestedBy: 'Nandini Shetty', requestedAt: '2026-11-21T04:00:00Z', lines: [] });
    }
    if (url.startsWith('/api/v1/pharmacy/registers/schedule-h1')) return json([]);
    return json({ detail: `unexpected ${method} ${url}` }, 404);
  }));
}

beforeEach(mockGateway);
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('pack expiry', () => {
  it('reads an expiry month as good to its last day', () => {
    expect(endOfMonth('2027-06')).toBe('2027-06-30');
    expect(endOfMonth('2028-02')).toBe('2028-02-29');
    expect(endOfMonth('2027-02')).toBe('2027-02-28');
    expect(packExpiry('2027-06-30')).toBe('06/2027');
  });
});

describe('Pharmacy desk', () => {
  it('dispenses a signed prescription within counter stock, then invoices and receives payment', async () => {
    const user = userEvent.setup();
    render(<PharmacyDesk session={PHARMACIST} />);
    await user.click(await screen.findByText('Arjun Nair'));
    const lines = screen.getByRole('table', { name: 'Prescription lines' });
    // The suggestion is filled in; a line with no medicine (steam inhalation) is left out.
    expect(within(lines).getByRole('combobox', { name: 'Medicine for Amoxicillin 500 mg capsule' })).toHaveValue('amx');
    expect(within(lines).getByRole('checkbox', { name: 'Dispense Steam inhalation' })).not.toBeChecked();
    expect(within(within(lines).getByRole('row', { name: 'Cefixime 200 mg tablet' })).getByText('Sch H1')).toBeInTheDocument();

    // Ten Taxim-O asked, six at the counter: the desk says so before the server does.
    await user.click(screen.getByRole('button', { name: 'Dispense' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Taxim-O: only 6 tablet at the counter');
    expect(sent('POST', '/api/v1/pharmacy/dispenses')).toHaveLength(0);

    const quantity = within(lines).getByRole('textbox', { name: 'Quantity of Cefixime 200 mg tablet' });
    await user.clear(quantity);
    await user.type(quantity, '6');
    await user.click(screen.getByRole('button', { name: 'Dispense' }));
    await waitFor(() => expect(sent('POST', '/api/v1/pharmacy/dispenses')).toHaveLength(1));
    expect(sent('POST', '/api/v1/pharmacy/dispenses')[0]!.body).toEqual({
      prescriptionId: 'rx-1',
      lines: [{ prescriptionItemId: 'i1', drugId: 'amx', quantity: 15 }, { prescriptionItemId: 'i2', drugId: 'cfx', quantity: 6 }],
    });

    const dispensed = await screen.findByRole('region', { name: 'Dispensed' });
    expect(within(dispensed).getByText('DSP/26-27/000012')).toBeInTheDocument();
    expect(within(dispensed).getByText('TX0912')).toBeInTheDocument();
    expect(within(dispensed).getByText('12/2026')).toBeInTheDocument();
    expect(within(dispensed).getByText('₹202.50')).toBeInTheDocument();

    await user.selectOptions(within(dispensed).getByRole('combobox', { name: 'Mode' }), 'UPI');
    await user.type(within(dispensed).getByRole('textbox', { name: 'Reference' }), '611234567890');
    await user.click(within(dispensed).getByRole('button', { name: 'Invoice and receive' }));
    await waitFor(() => expect(sent('POST', '/api/v1/billing/pharmacy-sales')).toHaveLength(1));
    expect(sent('POST', '/api/v1/billing/pharmacy-sales')[0]!.body).toEqual({ dispenseId: 'dsp-1', payment: { mode: 'UPI', reference: '611234567890' } });
    expect(await within(dispensed).findByText('Paid')).toBeInTheDocument();
    expect(within(dispensed).getByText('PHB/26-27/000003')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Invoice PHB/26-27/000003: received ₹203.00.');
  });

  it('shows stock needing attention and writes off part of an expired batch with a reason', async () => {
    const user = userEvent.setup();
    render(<PharmacyDesk session={PHARMACIST} />);
    await user.click(screen.getByRole('tab', { name: /Stock & alerts/ }));
    const stock = await screen.findByRole('table', { name: 'Stock by medicine' });
    await within(stock).findByRole('row', { name: 'Dolo 650' });
    await user.click(screen.getByRole('checkbox', { name: 'Only medicines needing attention' }));
    expect(within(stock).queryByRole('row', { name: 'Dolo 650' })).not.toBeInTheDocument();
    const taxim = within(stock).getByRole('row', { name: 'Taxim-O' });
    expect(taxim).toHaveTextContent('Reorder');
    expect(taxim).toHaveTextContent('6 near expiry');

    await user.click(taxim);
    const batches = await screen.findByRole('table', { name: 'Batches of Taxim-O' });
    expect(within(batches).getByText('01/2026 (expired)')).toBeInTheDocument();
    await user.click(within(batches).getAllByRole('button', { name: 'Write off…' })[0]!);
    const amount = within(batches).getByRole('textbox', { name: 'Quantity to write off from TX0101' });
    expect(amount).toHaveValue('20');
    expect(within(batches).getByRole('textbox', { name: 'Reason to write off TX0101' })).toHaveValue('Expired');
    await user.clear(amount);
    await user.type(amount, '25');
    expect(within(batches).getByRole('button', { name: 'Write off' })).toBeDisabled();
    await user.clear(amount);
    await user.type(amount, '12');
    await user.click(within(batches).getByRole('button', { name: 'Write off' }));
    await waitFor(() => expect(sent('POST', '/api/v1/pharmacy/write-offs')).toHaveLength(1));
    expect(sent('POST', '/api/v1/pharmacy/write-offs')[0]!.body).toEqual({ store: 'MAIN', batchId: 'bt-old', quantity: 12, reason: 'Expired' });
    expect(await screen.findByRole('status')).toHaveTextContent('Wrote off 12 of batch TX0101.');
  });

  it('receives a supplier invoice by batch and expiry month, refusing expired or over-MRP lines', async () => {
    const user = userEvent.setup();
    render(<PharmacyDesk session={PHARMACIST} />);
    await user.click(screen.getByRole('tab', { name: 'Receive goods' }));
    const form = screen.getByRole('form', { name: 'Goods receipt' });
    await within(form).findByRole('option', { name: /Mox/ });
    await user.type(within(form).getByRole('textbox', { name: 'Supplier' }), 'Sri Balaji Pharma Distributors');
    await user.type(within(form).getByRole('textbox', { name: 'Invoice number' }), 'SBP/2611/0457');
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Medicine, line 1' }), 'amx');
    await user.type(within(form).getByRole('textbox', { name: 'Batch, line 1' }), 'MX2702');
    fireEvent.change(within(form).getByLabelText('Expiry, line 1'), { target: { value: '2020-01' } });
    await user.type(within(form).getByRole('textbox', { name: 'Quantity, line 1' }), '300');
    await user.type(within(form).getByRole('textbox', { name: 'Rate, line 1' }), '8');
    await user.type(within(form).getByRole('textbox', { name: 'MRP, line 1' }), '7.5');
    await user.click(within(form).getByRole('button', { name: 'Receive into main store' }));
    const alert = within(form).getByRole('alert');
    expect(alert).toHaveTextContent('Line 1: this batch has expired; return it to the supplier');
    expect(alert).toHaveTextContent('Line 1: the purchase rate is above the MRP');
    expect(sent('POST', '/api/v1/pharmacy/receipts')).toHaveLength(0);

    fireEvent.change(within(form).getByLabelText('Expiry, line 1'), { target: { value: '2028-02' } });
    await user.clear(within(form).getByRole('textbox', { name: 'Rate, line 1' }));
    await user.type(within(form).getByRole('textbox', { name: 'Rate, line 1' }), '5.2');
    await user.type(within(form).getByRole('textbox', { name: 'Free, line 1' }), '30');
    await user.click(within(form).getByRole('button', { name: 'Receive into main store' }));
    await waitFor(() => expect(sent('POST', '/api/v1/pharmacy/receipts')).toHaveLength(1));
    expect(sent('POST', '/api/v1/pharmacy/receipts')[0]!.body).toMatchObject({
      supplier: 'Sri Balaji Pharma Distributors', invoiceNumber: 'SBP/2611/0457',
      lines: [{ drugId: 'amx', batchNumber: 'MX2702', expiryDate: '2028-02-29', quantity: 300, freeQuantity: 30, purchaseRate: 5.2, mrp: 7.5 }],
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Goods received: GRN/26-27/000007');
  });

  it('issues a waiting requisition from the main store', async () => {
    const user = userEvent.setup();
    render(<PharmacyDesk session={PHARMACIST} />);
    await user.click(screen.getByRole('tab', { name: 'Requisitions' }));
    const req = await screen.findByRole('article', { name: 'REQ/26-27/000002' });
    expect(req).toHaveTextContent('Taxim-O: 30 asked');
    await user.click(within(req).getByRole('button', { name: 'Issue from main store' }));
    await waitFor(() => expect(sent('POST', '/api/v1/pharmacy/requisitions/req-1/issue')).toHaveLength(1));
    expect(await screen.findByRole('status')).toHaveTextContent('REQ/26-27/000002 part-issued');
  });
});
