import { type FormEvent, Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { type Bill, type PaymentMode, printBill, rupees } from '../../api/frontoffice';
import { type Batch, type Dispense, type Drug, type PendingPrescription, type RegisterEntry, type Requisition, endOfMonth, packExpiry, pharmacy } from '../../api/pharmacy';
import type { Session } from '../../auth/session';
import type { DeskId } from '../../emr/diagnostics';
import { DeskHeader, type Notice } from '../DeskHeader';
import { PAYMENT_MODES } from '../front/PatientsPanel';
import { Badge, label, when } from '../ui';

type Tab = 'counter' | 'stock' | 'receive' | 'requisitions' | 'register';
const TABS: { id: Tab; label: string }[] = [
  { id: 'counter', label: 'Counter' },
  { id: 'stock', label: 'Stock & alerts' },
  { id: 'receive', label: 'Receive goods' },
  { id: 'requisitions', label: 'Requisitions' },
  { id: 'register', label: 'H1 register' },
];

const qty = (n: number | undefined) => (n === undefined ? '' : Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 }));
const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });

function ScheduleBadge({ schedule }: { schedule?: string }) {
  if (!schedule || schedule === 'OTC' || schedule === 'G') return null;
  return <Badge tone={schedule === 'H1' || schedule === 'X' ? 'red' : 'amber'} title={`Schedule ${schedule}`}>Sch {schedule}</Badge>;
}

/** The pharmacy: dispense at the counter, keep stock by batch and expiry, receive goods, requisition, H1 register. */
export function PharmacyDesk({ session, onDeskChange }: { session: Session; onDeskChange?(desk: DeskId): void }) {
  const [tab, setTab] = useState<Tab>('counter');
  const [notice, setNotice] = useState<Notice | null>(null);
  const [drugs, setDrugs] = useState<Drug[]>([]);
  const report = useCallback((e: unknown) => setNotice({ kind: 'error', text: e instanceof Error ? e.message : 'Something went wrong' }), []);
  const done = useCallback((text: string) => setNotice({ kind: 'ok', text }), []);
  const loadDrugs = useCallback(() => pharmacy.drugs().then(setDrugs, report), [report]);
  useEffect(() => {
    void loadDrugs();
  }, [loadDrugs]);
  const alerts = drugs.filter((d) => d.belowReorder || d.expired > 0 || d.nearExpiry > 0).length;

  return (
    <div className="flex h-full flex-col">
      <DeskHeader session={session} desk="pharmacy" onDeskChange={onDeskChange} notice={notice} />
      <nav role="tablist" aria-label="Pharmacy" className="flex shrink-0 gap-1 border-b border-slate-200 bg-white px-2">
        {TABS.map((t) => (
          <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => { setTab(t.id); void loadDrugs(); }}
            className={`-mb-px flex h-8 items-center gap-1.5 whitespace-nowrap border-b-2 px-2 text-[12px] font-medium ${tab === t.id ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-600 hover:text-slate-900'}`}>
            {t.label}{t.id === 'stock' && alerts > 0 && <Badge tone="amber">{alerts}</Badge>}
          </button>
        ))}
      </nav>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {tab === 'counter' && <Counter drugs={drugs} onError={report} onDone={(t) => { done(t); void loadDrugs(); }} />}
        {tab === 'stock' && <Stock drugs={drugs} onError={report} onDone={(t) => { done(t); void loadDrugs(); }} />}
        {tab === 'receive' && <Receive drugs={drugs} onError={report} onDone={(t) => { done(t); void loadDrugs(); }} />}
        {tab === 'requisitions' && <Requisitions drugs={drugs} onError={report} onDone={(t) => { done(t); void loadDrugs(); }} />}
        {tab === 'register' && <Register onError={report} />}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- counter

function Counter({ drugs, onError, onDone }: { drugs: Drug[]; onError(e: unknown): void; onDone(text: string): void }) {
  const [pending, setPending] = useState<PendingPrescription[] | null>(null);
  const [selected, setSelected] = useState<PendingPrescription | null>(null);
  const load = useCallback(() => pharmacy.pending().then(setPending, onError), [onError]);
  useEffect(() => {
    void load();
  }, [load]);
  return (
    <div className="grid h-full grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
      <section aria-label="Prescriptions waiting" className="min-h-0 overflow-y-auto border-r border-slate-200 bg-white">
        <div className="flex items-center border-b border-slate-200 px-2 py-1">
          <h2 className="section-title">Signed prescriptions waiting</h2>
          <button type="button" className="btn-ghost ml-auto h-6" onClick={() => void load()}>Refresh</button>
        </div>
        <ul className="divide-y divide-slate-100">
          {pending === null && <li className="p-2 text-slate-400">Loading…</li>}
          {pending?.length === 0 && <li className="p-2 text-slate-400">No prescriptions waiting.</li>}
          {pending?.map((p) => (
            <li key={p.prescription.id}>
              <button type="button" onClick={() => setSelected(p)}
                className={`w-full px-2 py-1.5 text-left hover:bg-slate-50 ${selected?.prescription.id === p.prescription.id ? 'bg-indigo-50' : ''}`}>
                <div className="font-medium">{p.prescription.patientName} <span className="text-[11px] text-slate-500">{p.ageYears ?? '?'} y / {p.prescription.gender}</span></div>
                <div className="text-[11px] text-slate-500"><span className="font-mono">{p.prescription.uhid}</span> · {p.prescription.prescriber} · {p.items.length} item(s) · {when(p.prescription.issuedAt)}</div>
              </button>
            </li>
          ))}
        </ul>
      </section>
      <section aria-label="Dispense" className="min-h-0 overflow-y-auto bg-slate-50 p-3">
        {selected ? (
          <DispenseForm key={selected.prescription.id} rx={selected} drugs={drugs} onError={onError}
            onDone={(text) => { onDone(text); void load(); }} onClose={() => setSelected(null)} />
        ) : <p className="m-6 text-center text-slate-400">Choose a prescription to dispense.</p>}
      </section>
    </div>
  );
}

function DispenseForm({ rx, drugs, onError, onDone, onClose }: {
  rx: PendingPrescription;
  drugs: Drug[];
  onError(e: unknown): void;
  onDone(text: string): void;
  onClose(): void;
}) {
  const [lines, setLines] = useState(() => rx.items.map((i) => ({
    itemId: i.item.id, include: Boolean(i.suggested), drugId: i.suggested?.id ?? '', quantity: i.suggestedQuantity ? String(i.suggestedQuantity) : '',
  })));
  const [dispense, setDispense] = useState<Dispense | null>(null);
  const [bill, setBill] = useState<Bill | null>(null);
  const [payment, setPayment] = useState<{ mode: PaymentMode; reference: string }>({ mode: 'CASH', reference: '' });
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const byId = useMemo(() => new Map(drugs.map((d) => [d.id, d])), [drugs]);

  const submit = async () => {
    const chosen = lines.filter((l) => l.include);
    const found: string[] = [];
    if (chosen.length === 0) found.push('Choose at least one medicine to dispense');
    chosen.forEach((l, i) => {
      const drug = byId.get(l.drugId);
      if (!drug) found.push(`Line ${i + 1}: choose the medicine`);
      else if (!(Number(l.quantity) > 0)) found.push(`${drug.brandName}: enter the quantity`);
      else if (Number(l.quantity) > drug.dispensary) found.push(`${drug.brandName}: only ${qty(drug.dispensary)} ${drug.unit} at the counter; requisition more from the main store`);
      else if (drug.schedule === 'X') found.push(`${drug.brandName} is Schedule X: dispense it with the narcotics register`);
    });
    setProblems(found);
    if (found.length > 0) return;
    setBusy(true);
    try {
      const result = await pharmacy.dispense(rx.prescription.id, chosen.map((l) => ({ prescriptionItemId: l.itemId, drugId: l.drugId, quantity: Number(l.quantity) })));
      setDispense(result);
      onDone(`Dispensed ${result.number}.`);
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  };

  const sell = async () => {
    setBusy(true);
    try {
      const issued = await pharmacy.sell(dispense!.id, payment.mode, payment.reference.trim() || undefined);
      setBill(issued);
      onDone(`Invoice ${issued.billNumber}: received ${rupees(issued.totals.payable)}.`);
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-3">
      <header>
        <h2 className="text-[15px] font-semibold">{rx.prescription.patientName}</h2>
        <p className="text-[12px] text-slate-600"><span className="font-mono">{rx.prescription.uhid}</span> · {rx.ageYears ?? '?'} y / {rx.prescription.gender} · {rx.prescription.prescriber}
          {rx.prescription.prescriberRegistration && ` (Reg. ${rx.prescription.prescriberRegistration})`} · {when(rx.prescription.issuedAt)}</p>
      </header>
      {!dispense ? (
        <>
          <table className="w-full border-collapse bg-white text-[12px]" aria-label="Prescription lines">
            <thead>
              <tr className="border-b border-slate-200">
                <th className="th w-8" /><th className="th">Prescribed</th><th className="th">Medicine to give</th><th className="th w-20">Quantity</th><th className="th w-28">At counter</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rx.items.map((i, idx) => {
                const line = lines[idx]!;
                const drug = byId.get(line.drugId);
                const set = (patch: Partial<typeof line>) => setLines(lines.map((l, j) => (j === idx ? { ...l, ...patch } : l)));
                return (
                  <tr key={i.item.id} aria-label={i.item.drugText}>
                    <td className="td"><input type="checkbox" aria-label={`Dispense ${i.item.drugText}`} checked={line.include} disabled={busy} onChange={(e) => set({ include: e.target.checked })} /></td>
                    <td className="td"><b>{i.item.drugText}</b><div className="text-[11px] text-slate-500">{[i.item.dose, i.item.frequency, i.item.durationDays && `${i.item.durationDays} days`].filter(Boolean).join(' · ')}</div></td>
                    <td className="td">
                      <select aria-label={`Medicine for ${i.item.drugText}`} className="field h-6" value={line.drugId} disabled={busy || !line.include}
                        onChange={(e) => set({ drugId: e.target.value })}>
                        <option value="">Choose…</option>
                        {drugs.map((d) => <option key={d.id} value={d.id}>{d.brandName} {d.strength ?? ''}{d.schedule && !['OTC', 'G'].includes(d.schedule) ? ` · Sch ${d.schedule}` : ''}</option>)}
                      </select>
                      {drug && <span className="mt-0.5 flex gap-1"><ScheduleBadge schedule={drug.schedule} />{drug.highAlert && <Badge tone="red">High alert</Badge>}</span>}
                    </td>
                    <td className="td"><input aria-label={`Quantity of ${i.item.drugText}`} inputMode="decimal" className="field h-6 text-right" value={line.quantity} disabled={busy || !line.include} onChange={(e) => set({ quantity: e.target.value })} /></td>
                    <td className="td text-[11px]">{drug ? <span className={drug.dispensary < Number(line.quantity || 0) ? 'font-semibold text-red-700' : 'text-slate-600'}>{qty(drug.dispensary)} {drug.unit}</span> : ''}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn-ghost" disabled={busy} onClick={onClose}>Cancel</button>
            <button type="button" className="btn-primary" disabled={busy} onClick={() => void submit()}>Dispense</button>
          </div>
          <p className="text-[11px] text-slate-500">Batches are taken earliest expiry first; expired stock is never dispensed.</p>
        </>
      ) : (
        <section aria-label="Dispensed" className="space-y-2 rounded border border-slate-200 bg-white p-2">
          <p className="text-[12px]"><b className="font-mono">{dispense.number}</b> · dispensed by {dispense.dispensedBy} {when(dispense.dispensedAt)}</p>
          <table className="w-full border-collapse text-[12px]" aria-label="Dispensed batches">
            <thead><tr className="border-b border-slate-200"><th className="th">Medicine</th><th className="th">Batch</th><th className="th">Expiry</th><th className="th text-right">Qty</th><th className="th text-right">MRP</th><th className="th text-right">Amount</th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {dispense.lines.map((l) => (
                <tr key={l.id}><td className="td">{l.brandName} <ScheduleBadge schedule={l.schedule} /></td><td className="td font-mono">{l.batchNumber}</td><td className="td">{packExpiry(l.expiryDate)}</td>
                  <td className="td text-right tabular-nums">{qty(l.quantity)} {l.unit}</td><td className="td text-right tabular-nums">{rupees(l.mrp)}</td><td className="td text-right tabular-nums">{rupees(l.amount)}</td></tr>
              ))}
              <tr className="font-semibold"><td className="td" colSpan={5}>Total at MRP (GST included)</td><td className="td text-right tabular-nums">{rupees(dispense.total)}</td></tr>
            </tbody>
          </table>
          {!bill ? (
            <div className="flex flex-wrap items-end gap-2">
              <label className="text-[11px] text-slate-500">Mode
                <select className="field w-32" value={payment.mode} disabled={busy} onChange={(e) => setPayment({ ...payment, mode: e.target.value as PaymentMode })}>
                  {PAYMENT_MODES.map((m) => <option key={m} value={m}>{label(m)}</option>)}
                </select>
              </label>
              {payment.mode !== 'CASH' && (
                <label className="text-[11px] text-slate-500">Reference
                  <input className="field w-40" maxLength={64} value={payment.reference} disabled={busy} onChange={(e) => setPayment({ ...payment, reference: e.target.value })} />
                </label>
              )}
              <button type="button" className="btn-primary ml-auto" disabled={busy} onClick={() => void sell()}>Invoice and receive</button>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <Badge tone="emerald">Paid</Badge><span className="font-mono text-[12px]">{bill.billNumber}</span><span className="text-[12px]">{rupees(bill.totals.payable)}</span>
              <button type="button" className="btn-secondary ml-auto h-6" onClick={() => printBill(bill.billId)}>Print invoice</button>
              <button type="button" className="btn-ghost h-6" onClick={onClose}>Next patient</button>
            </div>
          )}
        </section>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- stock

function Stock({ drugs, onError, onDone }: { drugs: Drug[]; onError(e: unknown): void; onDone(text: string): void }) {
  const [alertsOnly, setAlertsOnly] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const [batches, setBatches] = useState<Batch[]>([]);
  const [writeOff, setWriteOff] = useState<{ key: string; reason: string; quantity: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const shown = drugs.filter((d) => !alertsOnly || d.belowReorder || d.expired > 0 || d.nearExpiry > 0);
  const now = today();

  const expand = async (drugId: string) => {
    setOpen(open === drugId ? null : drugId);
    if (open !== drugId) setBatches(await pharmacy.batches(drugId).catch((e) => { onError(e); return []; }));
  };

  return (
    <section aria-label="Stock" className="p-2">
      <label className="mb-2 flex items-center gap-1 text-[12px]"><input type="checkbox" checked={alertsOnly} onChange={(e) => setAlertsOnly(e.target.checked)} /> Only medicines needing attention</label>
      <table className="w-full border-collapse bg-white text-[12px]" aria-label="Stock by medicine">
        <thead>
          <tr className="border-b border-slate-200">
            <th className="th">Medicine</th><th className="th text-right">Main store</th><th className="th text-right">OPD pharmacy</th><th className="th text-right">Reorder at</th>
            <th className="th">Earliest expiry</th><th className="th">Alerts</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {shown.map((d) => (
            <Fragment key={d.id}>
              <tr aria-label={d.brandName} className="cursor-pointer hover:bg-slate-50" onClick={() => void expand(d.id)}>
                <td className="td"><b>{d.brandName}</b> {d.strength} <span className="text-[11px] text-slate-500">{d.generics}</span> <ScheduleBadge schedule={d.schedule} /></td>
                <td className="td text-right tabular-nums">{qty(d.mainStore)}</td>
                <td className="td text-right tabular-nums">{qty(d.dispensary)}</td>
                <td className="td text-right tabular-nums text-slate-500">{d.reorderLevel || ''}</td>
                <td className="td">{d.earliestExpiry ? packExpiry(d.earliestExpiry) : ''}</td>
                <td className="td"><span className="flex flex-wrap gap-1">
                  {d.belowReorder && <Badge tone="amber">Reorder</Badge>}
                  {d.nearExpiry > 0 && <Badge tone="amber">{qty(d.nearExpiry)} near expiry</Badge>}
                  {d.expired > 0 && <Badge tone="red">{qty(d.expired)} expired</Badge>}
                </span></td>
              </tr>
              {open === d.id && (
                <tr key={`${d.id}-batches`}>
                  <td colSpan={6} className="bg-slate-50 px-4 py-1">
                    <table className="w-full text-[11px]" aria-label={`Batches of ${d.brandName}`}>
                      <thead><tr><th className="th">Store</th><th className="th">Batch</th><th className="th">Expiry</th><th className="th text-right">MRP</th><th className="th text-right">On hand</th><th className="th" /></tr></thead>
                      <tbody>
                        {batches.map((b) => {
                          const key = `${b.storeCode}:${b.batchId}`;
                          const expired = b.expiryDate < now;
                          return (
                            <tr key={key} className={expired ? 'text-red-700' : ''}>
                              <td className="td">{label(b.storeCode)}</td><td className="td font-mono">{b.batchNumber}</td>
                              <td className="td">{packExpiry(b.expiryDate)}{expired && ' (expired)'}</td><td className="td text-right">{rupees(b.mrp)}</td><td className="td text-right">{qty(b.quantity)}</td>
                              <td className="td text-right">
                                {writeOff?.key === key ? (() => {
                                  const amount = Number(writeOff.quantity);
                                  const valid = writeOff.reason.trim() !== '' && amount > 0 && amount <= b.quantity;
                                  return (
                                    <span className="flex justify-end gap-1">
                                      <input aria-label={`Quantity to write off from ${b.batchNumber}`} inputMode="decimal" className="field h-6 w-16 text-right" value={writeOff.quantity}
                                        onChange={(e) => setWriteOff({ ...writeOff, quantity: e.target.value })} />
                                      <input aria-label={`Reason to write off ${b.batchNumber}`} className="field h-6 w-44" maxLength={200} placeholder="Reason (e.g. expired, damaged)" value={writeOff.reason}
                                        onChange={(e) => setWriteOff({ ...writeOff, reason: e.target.value })} />
                                      <button type="button" className="btn-secondary h-6 text-red-700" disabled={busy || !valid} onClick={() => {
                                        setBusy(true);
                                        void pharmacy.writeOff(b.storeCode, b.batchId, amount, writeOff.reason.trim())
                                          .then(() => { onDone(`Wrote off ${qty(amount)} of batch ${b.batchNumber}.`); setWriteOff(null); setOpen(null); }, onError)
                                          .finally(() => setBusy(false));
                                      }}>Write off</button>
                                      <button type="button" className="btn-ghost h-6" onClick={() => setWriteOff(null)}>Cancel</button>
                                    </span>
                                  );
                                })() : (
                                  <button type="button" className="btn-ghost h-6" onClick={() => setWriteOff({ key, reason: expired ? 'Expired' : '', quantity: String(b.quantity) })}>Write off…</button>
                                )}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
    </section>
  );
}

// ---------------------------------------------------------------- receive

interface ReceiveRow { drugId: string; batchNumber: string; expiryMonth: string; quantity: string; freeQuantity: string; purchaseRate: string; mrp: string }
const emptyRow = (): ReceiveRow => ({ drugId: '', batchNumber: '', expiryMonth: '', quantity: '', freeQuantity: '', purchaseRate: '', mrp: '' });

function Receive({ drugs, onError, onDone }: { drugs: Drug[]; onError(e: unknown): void; onDone(text: string): void }) {
  const [head, setHead] = useState({ supplier: '', invoiceNumber: '', invoiceDate: today() });
  const [rows, setRows] = useState<ReceiveRow[]>([emptyRow()]);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const thisMonth = today().slice(0, 7);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found: string[] = [];
    if (!head.supplier.trim() || !head.invoiceNumber.trim()) found.push('Enter the supplier and the invoice number');
    rows.forEach((r, i) => {
      const n = `Line ${i + 1}`;
      if (!r.drugId) found.push(`${n}: choose the medicine`);
      if (!/^[A-Za-z0-9./-]{1,30}$/.test(r.batchNumber.trim())) found.push(`${n}: enter the batch number as printed`);
      if (!r.expiryMonth) found.push(`${n}: enter the expiry month`);
      else if (r.expiryMonth < thisMonth) found.push(`${n}: this batch has expired; return it to the supplier`);
      if (!(Number(r.quantity) > 0)) found.push(`${n}: enter the quantity received`);
      if (!(Number(r.mrp) > 0)) found.push(`${n}: enter the MRP per ${drugs.find((d) => d.id === r.drugId)?.unit ?? 'unit'}`);
      if (Number(r.purchaseRate) > Number(r.mrp)) found.push(`${n}: the purchase rate is above the MRP`);
    });
    setProblems(found);
    if (found.length > 0) return;
    setBusy(true);
    try {
      const receipt = await pharmacy.receive({
        supplier: head.supplier.trim(), invoiceNumber: head.invoiceNumber.trim(), invoiceDate: head.invoiceDate,
        lines: rows.map((r) => ({ drugId: r.drugId, batchNumber: r.batchNumber.trim(), expiryDate: endOfMonth(r.expiryMonth), quantity: Number(r.quantity),
          freeQuantity: r.freeQuantity ? Number(r.freeQuantity) : undefined, purchaseRate: Number(r.purchaseRate || 0), mrp: Number(r.mrp) })),
      });
      onDone(`Goods received: ${receipt.number}, into the main store.`);
      setHead({ supplier: '', invoiceNumber: '', invoiceDate: today() });
      setRows([emptyRow()]);
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  };

  const set = (i: number, patch: Partial<ReceiveRow>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <form onSubmit={(e) => void submit(e)} aria-label="Goods receipt" className="space-y-2 p-3">
      <div className="grid grid-cols-[1fr_12rem_10rem] gap-2">
        <label className="text-[11px] text-slate-500">Supplier<input className="field" maxLength={150} value={head.supplier} disabled={busy} onChange={(e) => setHead({ ...head, supplier: e.target.value })} /></label>
        <label className="text-[11px] text-slate-500">Invoice number<input className="field" maxLength={40} value={head.invoiceNumber} disabled={busy} onChange={(e) => setHead({ ...head, invoiceNumber: e.target.value })} /></label>
        <label className="text-[11px] text-slate-500">Invoice date<input type="date" className="field" max={today()} value={head.invoiceDate} disabled={busy} onChange={(e) => setHead({ ...head, invoiceDate: e.target.value })} /></label>
      </div>
      <table className="w-full border-collapse bg-white text-[12px]" aria-label="Received lines">
        <thead><tr className="border-b border-slate-200"><th className="th">Medicine</th><th className="th w-32">Batch</th><th className="th w-32">Expiry (month)</th><th className="th w-20">Qty</th><th className="th w-16">Free</th><th className="th w-24">Rate</th><th className="th w-24">MRP</th><th className="th w-6" /></tr></thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((r, i) => (
            <tr key={i}>
              <td className="td"><select aria-label={`Medicine, line ${i + 1}`} className="field h-6" value={r.drugId} disabled={busy} onChange={(e) => set(i, { drugId: e.target.value })}>
                <option value="">Choose…</option>{drugs.map((d) => <option key={d.id} value={d.id}>{d.brandName} {d.strength ?? ''}</option>)}
              </select></td>
              <td className="td"><input aria-label={`Batch, line ${i + 1}`} className="field h-6 font-mono uppercase" value={r.batchNumber} disabled={busy} onChange={(e) => set(i, { batchNumber: e.target.value })} /></td>
              <td className="td"><input aria-label={`Expiry, line ${i + 1}`} type="month" className="field h-6" value={r.expiryMonth} disabled={busy} onChange={(e) => set(i, { expiryMonth: e.target.value })} /></td>
              <td className="td"><input aria-label={`Quantity, line ${i + 1}`} inputMode="decimal" className="field h-6 text-right" value={r.quantity} disabled={busy} onChange={(e) => set(i, { quantity: e.target.value })} /></td>
              <td className="td"><input aria-label={`Free, line ${i + 1}`} inputMode="decimal" className="field h-6 text-right" value={r.freeQuantity} disabled={busy} onChange={(e) => set(i, { freeQuantity: e.target.value })} /></td>
              <td className="td"><input aria-label={`Rate, line ${i + 1}`} inputMode="decimal" className="field h-6 text-right" value={r.purchaseRate} disabled={busy} onChange={(e) => set(i, { purchaseRate: e.target.value })} /></td>
              <td className="td"><input aria-label={`MRP, line ${i + 1}`} inputMode="decimal" className="field h-6 text-right" value={r.mrp} disabled={busy} onChange={(e) => set(i, { mrp: e.target.value })} /></td>
              <td className="td"><button type="button" aria-label={`Remove line ${i + 1}`} className="text-slate-400 hover:text-red-600" disabled={busy || rows.length === 1} onClick={() => setRows(rows.filter((_, j) => j !== i))}>×</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-[11px] text-slate-500">Rates and MRP are per dispensing unit (tablet, capsule, sachet, vial…); the MRP includes GST.</p>
      {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
      <div className="flex gap-2">
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => setRows([...rows, emptyRow()])}>+ Add line</button>
        <button type="submit" className="btn-primary ml-auto" disabled={busy}>Receive into main store</button>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------- requisitions

function Requisitions({ drugs, onError, onDone }: { drugs: Drug[]; onError(e: unknown): void; onDone(text: string): void }) {
  const [list, setList] = useState<Requisition[] | null>(null);
  const [lines, setLines] = useState<{ drugId: string; quantity: string }[]>([{ drugId: '', quantity: '' }]);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => pharmacy.requisitions().then(setList, onError), [onError]);
  useEffect(() => {
    void load();
  }, [load]);
  const low = drugs.filter((d) => d.dispensary < Math.max(1, d.reorderLevel / 4));

  const create = async () => {
    const chosen = lines.filter((l) => l.drugId && Number(l.quantity) > 0);
    if (chosen.length === 0) return;
    setBusy(true);
    try {
      const r = await pharmacy.requisition(chosen.map((l) => ({ drugId: l.drugId, quantity: Number(l.quantity) })));
      onDone(`Requisition ${r.number} sent to the main store.`);
      setLines([{ drugId: '', quantity: '' }]);
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,24rem)] gap-3 p-3">
      <section aria-label="Requisitions list" className="space-y-2">
        {list?.length === 0 && <p className="text-slate-400">No requisitions.</p>}
        {list?.map((r) => (
          <article key={r.id} aria-label={r.number} className="rounded border border-slate-200 bg-white p-2 text-[12px]">
            <header className="flex items-center gap-2">
              <b className="font-mono">{r.number}</b><Badge tone={r.status === 'ISSUED' ? 'emerald' : r.status === 'PARTIAL' ? 'amber' : 'indigo'}>{label(r.status)}</Badge>
              <span className="text-slate-500">{label(r.requestingStore)} asks {label(r.issuingStore)} · {r.requestedBy} {when(r.requestedAt)}</span>
              {r.status === 'REQUESTED' && (
                <button type="button" className="btn-primary ml-auto h-6" disabled={busy} onClick={() => {
                  setBusy(true);
                  void pharmacy.issue(r.id).then((x) => { onDone(`${x.number} ${x.status === 'ISSUED' ? 'issued in full' : 'part-issued'} (earliest expiry first).`); return load(); }, onError).finally(() => setBusy(false));
                }}>Issue from main store</button>
              )}
            </header>
            <ul className="mt-1">
              {r.lines.map((l) => <li key={l.id}>{l.brandName}: {qty(l.requested)} asked{r.status !== 'REQUESTED' && `, ${qty(l.issued)} issued`}</li>)}
            </ul>
          </article>
        ))}
      </section>
      <section aria-label="New requisition" className="space-y-2 rounded border border-slate-200 bg-white p-2">
        <h3 className="text-[14px] font-semibold">Ask the main store</h3>
        {low.length > 0 && <p className="text-[11px] text-amber-800">Low at the counter: {low.map((d) => d.brandName).join(', ')}</p>}
        {lines.map((l, i) => (
          <div key={i} className="flex gap-1">
            <select aria-label={`Requisition medicine ${i + 1}`} className="field h-7" value={l.drugId} disabled={busy} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, drugId: e.target.value } : x)))}>
              <option value="">Choose…</option>{drugs.map((d) => <option key={d.id} value={d.id}>{d.brandName} · store {qty(d.mainStore)}</option>)}
            </select>
            <input aria-label={`Requisition quantity ${i + 1}`} inputMode="decimal" className="field h-7 w-20 text-right" value={l.quantity} disabled={busy} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))} />
          </div>
        ))}
        <div className="flex gap-2">
          <button type="button" className="btn-ghost" disabled={busy} onClick={() => setLines([...lines, { drugId: '', quantity: '' }])}>+ Line</button>
          <button type="button" className="btn-primary ml-auto" disabled={busy} onClick={() => void create()}>Send requisition</button>
        </div>
      </section>
    </div>
  );
}

// ---------------------------------------------------------------- register

function Register({ onError }: { onError(e: unknown): void }) {
  const [range, setRange] = useState({ from: today().slice(0, 8) + '01', to: today() });
  const [entries, setEntries] = useState<RegisterEntry[] | null>(null);
  const load = useCallback(() => pharmacy.scheduleH1(range.from, range.to).then(setEntries, onError), [range, onError]);
  useEffect(() => {
    void load();
  }, [load]);
  return (
    <section aria-label="Schedule H1 register" className="space-y-2 p-3">
      <div className="flex items-end gap-2">
        <h2 className="text-[14px] font-semibold">Schedule H1 register</h2>
        <label className="ml-auto text-[11px] text-slate-500">From<input type="date" className="field" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} /></label>
        <label className="text-[11px] text-slate-500">To<input type="date" className="field" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      <p className="text-[11px] text-slate-500">Drugs and Cosmetics Rules 1945, rule 65(3): prescriber, patient, drug and quantity of every H1 supply, kept three years.</p>
      <table className="w-full border-collapse bg-white text-[12px]" aria-label="H1 entries">
        <thead><tr className="border-b border-slate-200"><th className="th">Date</th><th className="th">Patient</th><th className="th">Prescriber</th><th className="th">Drug</th><th className="th">Batch</th><th className="th text-right">Qty</th><th className="th">By</th></tr></thead>
        <tbody className="divide-y divide-slate-100">
          {entries?.length === 0 && <tr><td colSpan={7} className="td py-2 text-slate-400">No Schedule H1 supplies in this period.</td></tr>}
          {entries?.map((e, i) => (
            <tr key={i}>
              <td className="td">{when(e.dispensedAt)}<div className="font-mono text-[10px] text-slate-500">{e.dispenseNumber}</div></td>
              <td className="td">{e.patientName}<div className="text-[10px] text-slate-500">{e.patientAddress}</div></td>
              <td className="td">{e.prescriber}<div className="text-[10px] text-slate-500">{e.prescriberRegistration}</div></td>
              <td className="td">{e.brandName} {e.strength}</td><td className="td font-mono">{e.batchNumber}</td>
              <td className="td text-right">{qty(e.quantity)} {e.unit}</td><td className="td">{e.dispensedBy}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
