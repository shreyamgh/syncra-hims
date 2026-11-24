import { type FormEvent, useCallback, useEffect, useState } from 'react';
import {
  type Admission, type Bill, type BillSummary, type PaymentMode, type Rate, frontOffice, printBill, rupees,
} from '../../api/frontoffice';
import { Badge, label, when } from '../ui';
import { PAYMENT_MODES } from './PatientsPanel';

interface Props {
  /** A bill to open, from another tab (an admission, or a patient's bills). */
  focus: { admissionId?: string; uhid?: string } | null;
  canIpdBill: boolean;
  canCollect: boolean;
  onError(error: unknown): void;
  onDone(text: string): void;
}

/**
 * Bills: in-patients' running bills (recalculate, extra services, advances, finalize at
 * discharge) and any patient's bills by UHID, with collection, refunds and printing.
 */
export function BillingPanel({ focus, canIpdBill, canCollect, onError, onDone }: Props) {
  const [inpatients, setInpatients] = useState<Admission[]>([]);
  const [uhid, setUhid] = useState('');
  const [bills, setBills] = useState<BillSummary[] | null>(null);
  const [bill, setBill] = useState<Bill | null>(null);

  const loadInpatients = useCallback(() => frontOffice.admissions().then(setInpatients, onError), [onError]);
  useEffect(() => {
    void loadInpatients();
  }, [loadInpatients]);

  const openAdmission = useCallback(async (admissionId: string) => {
    try {
      setBill(await frontOffice.admissionBill(admissionId));
    } catch (error) {
      onError(error);
    }
  }, [onError]);

  const findBills = useCallback(async (forUhid: string) => {
    try {
      setBills(await frontOffice.bills(forUhid.trim().toUpperCase()));
    } catch (error) {
      onError(error);
    }
  }, [onError]);

  useEffect(() => {
    if (focus?.admissionId) void openAdmission(focus.admissionId);
    if (focus?.uhid) {
      setUhid(focus.uhid);
      void findBills(focus.uhid);
    }
  }, [focus, openAdmission, findBills]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (uhid.trim()) void findBills(uhid);
  };

  return (
    <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
      <section aria-label="Find bills" className="min-h-0 overflow-y-auto border-r border-slate-200 bg-white p-2">
        <form onSubmit={submit} className="flex gap-1">
          <input aria-label="Patient UHID" className="field font-mono" placeholder="SYNC-YYYYMMDD-NNNN" value={uhid} onChange={(e) => setUhid(e.target.value)} />
          <button type="submit" className="btn-secondary">Find</button>
        </form>
        {bills && (
          <ul aria-label="Bills of the patient" className="mt-2 divide-y divide-slate-100">
            {bills.length === 0 && <li className="py-1 text-slate-500">No bills for this patient.</li>}
            {bills.map((b) => (
              <li key={b.billId}>
                <button type="button" className={`flex w-full items-center gap-2 px-1 py-1 text-left text-[12px] hover:bg-slate-50 ${bill?.billId === b.billId ? 'bg-indigo-50' : ''}`}
                  onClick={() => void frontOffice.bill(b.billId).then(setBill, onError)}>
                  <Badge tone={b.billType === 'IPD' ? 'indigo' : 'slate'}>{b.billType}</Badge>
                  <span className="font-mono">{b.billNumber ?? 'Draft'}</span>
                  <span className="text-slate-500">{label(b.status)}</span>
                  <span className="ml-auto tabular-nums">{rupees(b.payable)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        <h3 className="section-title mb-1 mt-4">In-patients</h3>
        <ul aria-label="In-patients" className="divide-y divide-slate-100">
          {inpatients.length === 0 && <li className="py-1 text-slate-400">No one is admitted.</li>}
          {inpatients.map((a) => (
            <li key={a.admissionId}>
              <button type="button" className="w-full px-1 py-1 text-left text-[12px] hover:bg-slate-50" onClick={() => void openAdmission(a.admissionId)}>
                <span className="font-medium">{a.patientName}</span> <span className="font-mono text-slate-500">{a.currentBed?.bedCode}</span>
                {a.dischargeInitiatedAt && <Badge tone="amber">Discharge</Badge>}
                <div className="text-slate-500">{a.admissionNumber} · {a.departmentName}</div>
              </button>
            </li>
          ))}
        </ul>
      </section>
      <section aria-label="Bill" className="min-h-0 overflow-y-auto bg-slate-50 p-3">
        {bill ? (
          <BillView key={bill.billId} bill={bill} canIpdBill={canIpdBill} canCollect={canCollect} onError={onError}
            onChanged={async (text, next) => {
              onDone(text);
              setBill(next ?? (bill.admissionId ? await frontOffice.admissionBill(bill.admissionId) : await frontOffice.bill(bill.billId)));
              void loadInpatients();
            }} />
        ) : <p className="m-6 text-center text-slate-400">Open an in-patient&apos;s bill, or find a patient&apos;s bills by UHID.</p>}
      </section>
    </div>
  );
}

function BillView({ bill, canIpdBill, canCollect, onChanged, onError }: {
  bill: Bill;
  canIpdBill: boolean;
  canCollect: boolean;
  onChanged(text: string, next?: Bill): Promise<void>;
  onError(error: unknown): void;
}) {
  const draft = bill.status === 'DRAFT' || bill.status === 'INTERIM';
  const ipdEditable = bill.billType === 'IPD' && draft && canIpdBill;
  const [rates, setRates] = useState<Rate[]>([]);
  const [line, setLine] = useState({ serviceCode: '', quantity: '1' });
  const [pay, setPay] = useState({ kind: 'SETTLEMENT' as 'SETTLEMENT' | 'REFUND' | 'ADVANCE', mode: 'CASH' as PaymentMode, amount: '', reference: '' });
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (ipdEditable) frontOffice.rates(bill.tariffPlan || 'GENERAL').then((r) => setRates(r.filter((x) => !['BED', 'NURSING', 'ADMISSION', 'REGISTRATION', 'CONSULTATION'].includes(x.category))), () => setRates([]));
  }, [ipdEditable, bill.tariffPlan]);

  const run = async (action: () => Promise<Bill | unknown>, text: string) => {
    setBusy(true);
    try {
      const result = await action();
      await onChanged(text, result && typeof result === 'object' && 'billId' in result ? (result as Bill) : undefined);
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  const t = bill.totals;
  const amount = Number(pay.amount);
  const pending = t.balanceDue;
  return (
    <div className="space-y-3">
      <header className="flex flex-wrap items-baseline gap-2">
        <h2 className="text-[15px] font-semibold">{bill.title}</h2>
        <span className="font-mono text-[12px]">{bill.billNumber ?? 'Not yet numbered'}</span>
        <Badge tone={bill.status === 'FINAL' ? 'emerald' : bill.status === 'CANCELLED' ? 'slate' : 'amber'}>{label(bill.status)}</Badge>
        <span className="text-[12px] text-slate-600">{bill.patient.name} · <span className="font-mono">{bill.patient.uhid}</span>{bill.admissionNumber ? ` · ${bill.admissionNumber}` : ''}</span>
        <div className="ml-auto flex gap-1">
          {ipdEditable && (
            <button type="button" className="btn-secondary h-6" disabled={busy}
              onClick={() => void run(() => frontOffice.refreshBill(bill.admissionId!), 'Bill recalculated: bed-days, nursing and admission charges are up to date.')}>
              Recalculate
            </button>
          )}
          {ipdEditable && (
            <button type="button" className="btn-secondary h-6" disabled={busy}
              onClick={() => {
                if (window.confirm('Finalize this bill? It is numbered and cannot be edited afterwards.')) {
                  void run(() => frontOffice.finalizeBill(bill.admissionId!), 'Final bill issued.');
                }
              }}>Finalize</button>
          )}
          {bill.billNumber && <button type="button" className="btn-secondary h-6" onClick={() => printBill(bill.billId)}>Print</button>}
        </div>
      </header>

      <div className="overflow-x-auto rounded border border-slate-200 bg-white">
        <table className="w-full border-collapse text-[12px]" aria-label="Bill lines">
          <thead>
            <tr className="border-b border-slate-200">
              <th className="th">Service</th><th className="th w-24">Date</th><th className="th w-14 text-right">Qty</th>
              <th className="th w-24 text-right">Rate</th><th className="th w-20 text-right">Discount</th><th className="th w-16 text-right">GST</th>
              <th className="th w-24 text-right">Amount</th>{ipdEditable && <th className="th w-8" />}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {bill.lines.map((l) => (
              <tr key={l.lineId}>
                <td className="td">{l.description}{l.automatic && <span className="ml-1 text-[10px] text-slate-400">auto</span>}</td>
                <td className="td tabular-nums">{l.serviceDate}</td>
                <td className="td text-right tabular-nums">{l.quantity}</td>
                <td className="td text-right tabular-nums">{rupees(l.unitPrice)}</td>
                <td className="td text-right tabular-nums">{l.discountAmount ? rupees(l.discountAmount) : ''}</td>
                <td className="td text-right tabular-nums">{l.gstRate ? `${l.gstRate}%` : ''}</td>
                <td className="td text-right tabular-nums">{rupees(l.lineTotal)}</td>
                {ipdEditable && (
                  <td className="td text-right">
                    {!l.automatic && (
                      <button type="button" aria-label={`Remove ${l.description}`} className="text-slate-400 hover:text-red-600" disabled={busy}
                        onClick={() => void run(() => frontOffice.removeLine(bill.billId, l.lineId), `Removed ${l.description}.`)}>×</button>
                    )}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {ipdEditable && (
        <div aria-label="Add a service" role="group" className="flex flex-wrap items-end gap-2">
          <label className="text-[11px] text-slate-500">Add a service
            <select className="field w-72" value={line.serviceCode} disabled={busy} onChange={(e) => setLine({ ...line, serviceCode: e.target.value })}>
              <option value="">Choose…</option>
              {rates.map((r) => <option key={r.serviceCode} value={r.serviceCode}>{r.serviceName} · {rupees(r.amount)}</option>)}
            </select>
          </label>
          <label className="text-[11px] text-slate-500">Qty
            <input className="field w-16 text-right" inputMode="decimal" value={line.quantity} disabled={busy} onChange={(e) => setLine({ ...line, quantity: e.target.value })} />
          </label>
          <button type="button" className="btn-secondary h-7" disabled={busy || !line.serviceCode || !(Number(line.quantity) > 0)}
            onClick={() => void run(() => frontOffice.addLine(bill.billId, line.serviceCode, Number(line.quantity)), 'Service added to the bill.').then(() => setLine({ serviceCode: '', quantity: '1' }))}>
            Add
          </button>
        </div>
      )}

      <dl aria-label="Totals" className="ml-auto grid w-72 grid-cols-[1fr_auto] gap-x-4 text-[12px] tabular-nums">
        <dt>Gross</dt><dd className="text-right">{rupees(t.gross)}</dd>
        {t.discount > 0 && <><dt>Discount</dt><dd className="text-right">−{rupees(t.discount)}</dd></>}
        {t.tax > 0 && <><dt>GST</dt><dd className="text-right">{rupees(t.tax)}</dd></>}
        {t.roundOff !== 0 && <><dt>Round-off</dt><dd className="text-right">{rupees(t.roundOff)}</dd></>}
        <dt className="font-semibold">Payable</dt><dd className="text-right font-semibold">{rupees(t.payable)}</dd>
        <dt>Received</dt><dd className="text-right">{rupees(t.received)}</dd>
        {t.refunded > 0 && <><dt>Refunded</dt><dd className="text-right">{rupees(t.refunded)}</dd></>}
        <dt className={pending > 0 ? 'font-semibold text-red-700' : pending < 0 ? 'font-semibold text-emerald-700' : ''}>
          {pending < 0 ? 'Refund due' : 'Balance due'}
        </dt>
        <dd className="text-right font-semibold">{rupees(Math.abs(pending))}</dd>
      </dl>

      {canCollect && bill.status !== 'CANCELLED' && (
        <section aria-label="Payment" className="rounded border border-slate-200 bg-white p-2">
          <h3 className="section-title mb-1">Payment</h3>
          <div className="flex flex-wrap items-end gap-2">
            <label className="text-[11px] text-slate-500">Type
              <select className="field w-36" value={pay.kind} disabled={busy} onChange={(e) => setPay({ ...pay, kind: e.target.value as typeof pay.kind })}>
                {bill.billType === 'IPD' && draft && <option value="ADVANCE">Advance</option>}
                <option value="SETTLEMENT">Payment</option>
                <option value="REFUND">Refund</option>
              </select>
            </label>
            <label className="text-[11px] text-slate-500">Mode
              <select className="field w-32" value={pay.mode} disabled={busy} onChange={(e) => setPay({ ...pay, mode: e.target.value as PaymentMode })}>
                {PAYMENT_MODES.map((m) => <option key={m} value={m}>{label(m)}</option>)}
              </select>
            </label>
            <label className="text-[11px] text-slate-500">Amount (₹)
              <input className="field w-28 text-right" inputMode="decimal" value={pay.amount} disabled={busy}
                placeholder={pending > 0 ? String(pending) : ''} onChange={(e) => setPay({ ...pay, amount: e.target.value })} />
            </label>
            {pay.mode !== 'CASH' && (
              <label className="text-[11px] text-slate-500">Reference
                <input className="field w-40" maxLength={64} value={pay.reference} disabled={busy} onChange={(e) => setPay({ ...pay, reference: e.target.value })} />
              </label>
            )}
            <button type="button" className="btn-primary h-7" disabled={busy || !(amount > 0)}
              onClick={() => {
                const payment = { mode: pay.mode, amount, reference: pay.reference.trim() || undefined };
                void run(() => (pay.kind === 'ADVANCE'
                  ? frontOffice.advance(bill.admissionId!, payment)
                  : frontOffice.pay(bill.billId, pay.kind, payment)),
                `${pay.kind === 'REFUND' ? 'Refunded' : 'Received'} ${rupees(amount)}${pay.kind === 'ADVANCE' ? ' as advance' : ''}.`)
                  .then(() => setPay({ ...pay, amount: '', reference: '' }));
              }}>
              {pay.kind === 'REFUND' ? 'Refund' : 'Receive'}
            </button>
          </div>
        </section>
      )}

      {bill.receipts.length > 0 && (
        <table className="w-full border-collapse text-[12px]" aria-label="Receipts">
          <thead><tr className="border-b border-slate-200"><th className="th">Receipt</th><th className="th">Type</th><th className="th">Mode</th><th className="th text-right">Amount</th><th className="th">When</th><th className="th">By</th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {bill.receipts.map((r) => (
              <tr key={r.paymentId} className={r.status === 'VOIDED' ? 'text-slate-400 line-through' : ''}>
                <td className="td font-mono">{r.receiptNumber}</td><td className="td">{label(r.type)}</td><td className="td">{label(r.mode)}</td>
                <td className="td text-right tabular-nums">{rupees(r.amount)}</td><td className="td">{when(r.receivedAt)}</td><td className="td">{r.receivedBy}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
