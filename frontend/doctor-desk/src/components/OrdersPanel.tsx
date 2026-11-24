import { type KeyboardEvent, useEffect, useState } from 'react';
import { endpoints } from '../api/endpoints';
import { ApiError } from '../api/http';
import type { CriticalResult, EncounterRecord, Orderable, OrderPriority, OrderRecord } from '../api/types';
import { ORDER_STATUS_LABEL, isOpenOrder, orderProblems } from '../emr/workspace';
import { CriticalTrail } from './CriticalResults';
import { openImages } from '../emr/pacs';
import { ResultTable } from './ResultTable';
import { Badge, when } from './ui';

interface Props {
  record: EncounterRecord;
  canOrder: boolean;
  onChanged(): Promise<void>;
  onError(error: unknown): void;
  onDone(text: string): void;
}

const PRIORITIES: OrderPriority[] = ['ROUTINE', 'URGENT', 'STAT'];
const TYPE_LABEL = { LABORATORY: 'Lab', RADIOLOGY: 'Imaging', PROCEDURE: 'Procedure' } as const;
const PRIORITY_LABEL: Record<OrderPriority, string> = { ROUTINE: 'Routine', URGENT: 'Urgent', STAT: 'STAT' };

function primaryDiagnosis(record: EncounterRecord): string {
  const live = (record.diagnoses ?? []).filter((d) => !d.enteredInError);
  return (live.find((d) => d.primary) ?? live[0])?.description ?? '';
}

/**
 * Investigation orders (CPOE) for this encounter and their results. Orders go to the lab and
 * imaging worklists at once; results appear here as the lab and radiologist report them.
 */
export function OrdersPanel({ record, canOrder, onChanged, onError, onDone }: Props) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Orderable[]>([]);
  const [active, setActive] = useState(0);
  const [picked, setPicked] = useState<Orderable | null>(null);
  const [priority, setPriority] = useState<OrderPriority>('ROUTINE');
  const [indication, setIndication] = useState('');
  const [problems, setProblems] = useState<string[]>([]);
  const [cancelling, setCancelling] = useState<{ id: string; reason: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const orders = record.orders ?? [];

  useEffect(() => {
    if (query.trim().length < 2) {
      setResults([]);
      return undefined;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      endpoints.orderables(query.trim(), undefined, controller.signal).then((found) => {
        setResults(found);
        setActive(0);
      }, () => setResults([]));
    }, 250);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [query]);

  const pick = (orderable: Orderable) => {
    setPicked(orderable);
    setQuery('');
    setResults([]);
    setProblems([]);
    if (!indication) setIndication(primaryDiagnosis(record));
  };

  const onSearchKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (results.length === 0) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((i) => Math.min(i + 1, results.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const choice = results[active];
      if (choice) pick(choice);
    } else if (event.key === 'Escape') {
      setResults([]);
    }
  };

  const place = async () => {
    const found = orderProblems({ serviceCode: picked?.code, category: picked?.category, clinicalIndication: indication });
    setProblems(found);
    if (found.length > 0 || !picked) return;
    setBusy(true);
    try {
      const order = await endpoints.placeOrder(record.encounterId, {
        serviceCode: picked.code, priority, clinicalIndication: indication.trim() || undefined,
      });
      setPicked(null);
      setPriority('ROUTINE');
      setIndication('');
      onDone(`Ordered ${order.serviceName}${order.accessionNumber ? ` (${order.accessionNumber})` : ''}.`);
      await onChanged();
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  const cancel = async (order: OrderRecord) => {
    if (!cancelling || cancelling.id !== order.id || !cancelling.reason.trim()) return;
    setBusy(true);
    try {
      await endpoints.cancelOrder(order.id, cancelling.reason.trim());
      setCancelling(null);
      onDone(`Cancelled ${order.serviceName}.`);
      await onChanged();
    } catch (error) {
      onError(error instanceof ApiError && error.code === 'ORDER_BILLED'
        ? new Error(`${order.serviceName} is already billed; ask the billing desk to reverse the charge first.`)
        : error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label="Orders and results" className="space-y-3 px-3 py-2">
      {canOrder && (
        <div role="group" aria-label="Place an order" className="rounded border border-slate-200 bg-slate-50 p-2">
          <div className="mb-1 flex items-center gap-2">
            <h3 className="section-title">Order investigation</h3>
            <span className="text-[11px] text-slate-500">Goes to the lab or imaging worklist as soon as it is placed.</span>
          </div>
          <div className="grid grid-cols-[1fr_7rem] gap-2">
            {picked ? (
              <div className="flex h-7 items-center gap-2 rounded border border-indigo-300 bg-white px-2">
                <Badge tone="indigo">{TYPE_LABEL[picked.category]}</Badge>
                <span className="font-medium">{picked.name}</span>
                <span className="font-mono text-[11px] text-slate-500">{picked.code}</span>
                <button type="button" aria-label="Change test" className="ml-auto text-slate-400 hover:text-red-600"
                  onClick={() => setPicked(null)} disabled={busy}>×</button>
              </div>
            ) : (
              <div className="relative">
                <input aria-label="Find a test" placeholder="Find a test or scan (e.g. CBC, renal, chest x-ray)" className="field"
                  role="combobox" aria-expanded={results.length > 0} aria-controls="orderable-results" aria-autocomplete="list"
                  value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={onSearchKey} disabled={busy} />
                {results.length > 0 && (
                  <ul id="orderable-results" role="listbox" aria-label="Tests"
                    className="absolute left-0 right-0 z-10 mt-0.5 rounded border border-slate-200 bg-white shadow">
                    {results.map((r, i) => (
                      <li key={r.code} role="option" aria-selected={i === active}
                        className={`flex cursor-pointer items-center gap-2 px-2 py-1 ${i === active ? 'bg-indigo-50' : 'hover:bg-slate-50'}`}
                        onMouseDown={(e) => {
                          e.preventDefault();
                          pick(r);
                        }}>
                        <Badge tone="slate">{TYPE_LABEL[r.category]}</Badge>
                        <span>{r.name}</span>
                        <span className="ml-auto font-mono text-[11px] text-slate-500">{r.code}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
            <select aria-label="Priority" className="field" value={priority} disabled={busy}
              onChange={(e) => setPriority(e.target.value as OrderPriority)}>
              {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABEL[p]}</option>)}
            </select>
            <input aria-label="Clinical indication" maxLength={500} disabled={busy} className="field"
              placeholder={picked?.category === 'RADIOLOGY' ? 'Clinical indication (needed by the radiologist)' : 'Clinical indication'}
              value={indication} onChange={(e) => setIndication(e.target.value)} />
            <button type="button" className="btn-primary justify-center" disabled={busy || !picked} onClick={() => void place()}>
              Place order
            </button>
          </div>
          {problems.length > 0 && <ul role="alert" className="mt-1 text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
        </div>
      )}

      {orders.length === 0 ? (
        <p className="text-slate-400">No investigations ordered for this visit.</p>
      ) : (
        <ol aria-label="Orders" className="space-y-2">
          {orders.map((order) => (
            <li key={order.id} aria-label={order.serviceName}
              className={`rounded border ${order.status === 'CANCELLED' ? 'border-slate-100 text-slate-400' : 'border-slate-200'} bg-white`}>
              <header className="flex flex-wrap items-center gap-2 px-2 py-1">
                <Badge tone="slate">{TYPE_LABEL[order.orderType]}</Badge>
                <span className={`font-medium ${order.status === 'CANCELLED' ? 'line-through' : ''}`}>{order.serviceName}</span>
                {order.priority !== 'ROUTINE' && <Badge tone={order.priority === 'STAT' ? 'red' : 'amber'}>{PRIORITY_LABEL[order.priority]}</Badge>}
                <StatusBadge order={order} />
                {order.accessionNumber && <span className="font-mono text-[11px] text-slate-500">{order.accessionNumber}</span>}
                <span className="text-[11px] text-slate-500">{order.orderedBy} · {when(order.orderedAt)}</span>
                {order.studyInstanceUid && (
                  <button type="button" className="btn-secondary ml-auto h-6"
                    onClick={() => void openImages(order.studyInstanceUid!).catch(onError)}>View images</button>
                )}
                {canOrder && isOpenOrder(order) && cancelling?.id !== order.id && (
                  <button type="button" className="btn-ghost ml-auto h-6" disabled={busy}
                    onClick={() => setCancelling({ id: order.id, reason: '' })}>Cancel order</button>
                )}
              </header>
              {cancelling?.id === order.id && (
                <div className="flex items-center gap-2 border-t border-slate-100 px-2 py-1">
                  <input aria-label={`Reason to cancel ${order.serviceName}`} className="field h-6" maxLength={300} autoFocus
                    placeholder="Reason (e.g. ordered in error, done elsewhere)" value={cancelling.reason} disabled={busy}
                    onChange={(e) => setCancelling({ id: order.id, reason: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') void cancel(order);
                      if (e.key === 'Escape') setCancelling(null);
                    }} />
                  <button type="button" className="btn-ghost h-6" disabled={busy} onClick={() => setCancelling(null)}>Keep</button>
                  <button type="button" className="btn-secondary h-6 text-red-700" disabled={busy || !cancelling.reason.trim()}
                    onClick={() => void cancel(order)}>Confirm cancel</button>
                </div>
              )}
              <OrderDetail order={order} critical={record.criticalResults?.find((c) => c.orderId === order.id)} />
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function StatusBadge({ order }: { order: OrderRecord }) {
  const tone = order.status === 'REPORTED' ? 'emerald'
    : order.status === 'CANCELLED' ? 'slate'
      : order.status === 'COMPLETED' ? 'amber'
        : 'indigo';
  const text = order.status === 'COMPLETED' && order.orderType === 'RADIOLOGY' ? 'Images ready, awaiting report' : ORDER_STATUS_LABEL[order.status];
  return <Badge tone={tone}>{text}</Badge>;
}

function OrderDetail({ order, critical }: { order: OrderRecord; critical?: CriticalResult }) {
  const values = order.resultValues ?? [];
  const lines = [
    order.clinicalIndication && <p key="for"><span className="text-slate-500">For: </span>{order.clinicalIndication}</p>,
    order.status === 'CANCELLED' && order.cancelledReason && <p key="why"><span className="text-slate-500">Cancelled: </span>{order.cancelledReason}</p>,
  ].filter(Boolean);
  const reported = order.status === 'REPORTED';
  if (lines.length === 0 && !reported) return null;
  return (
    <div className="space-y-1 border-t border-slate-100 px-2 py-1.5 text-[12px]">
      {lines}
      {reported && (
        <div aria-label={`Result of ${order.serviceName}`}>
          {order.resultSummary && (
            <p>
              <span className="text-slate-500">{order.orderType === 'RADIOLOGY' ? 'Impression: ' : 'Result: '}</span>
              <span className="font-medium">{order.resultSummary}</span>
            </p>
          )}
          {values.length > 0 && <ResultTable values={values} />}
          {critical && (
            <p aria-label={`Critical result trail for ${order.serviceName}`} className="rounded bg-red-50 px-1.5 py-0.5 text-[11px] text-red-900">
              <b>Critical:</b> <CriticalTrail result={critical} />
            </p>
          )}
          <p className="text-[11px] text-slate-500">
            Reported{order.reportedBy ? ` by ${order.reportedBy}` : ''} {when(order.completedAt)}
            {order.orderType === 'RADIOLOGY' && ' · full report under Notes'}
          </p>
        </div>
      )}
    </div>
  );
}
