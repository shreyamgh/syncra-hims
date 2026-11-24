import { type KeyboardEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { endpoints } from '../api/endpoints';
import { ApiError } from '../api/http';
import type { CommunicationMethod, CriticalResult, OrderRecord, OrderStatus, ResultFlag, ResultTemplate } from '../api/types';
import type { Session } from '../auth/session';
import {
  type DeskId, type FlagChoice, type ResultRow, criticalValues, effectiveFlag, emptyRows, hasRange, resultProblems,
  resultValues,
} from '../emr/diagnostics';
import { FLAG_LABEL } from '../emr/workspace';
import { criticalValuesText } from './CriticalResults';
import { DeskHeader, type Notice } from './DeskHeader';
import { ResultTable } from './ResultTable';
import { Badge, waitedMinutes, when } from './ui';

const REFRESH_MS = 20_000;
const OPEN: OrderStatus[] = ['ORDERED', 'SCHEDULED', 'SAMPLE_COLLECTED', 'IN_PROGRESS'];
const TO_COLLECT: OrderStatus[] = ['ORDERED', 'SCHEDULED'];
const REPORTED_WINDOW_MS = 24 * 3_600_000;

type View = 'collect' | 'report' | 'reported' | 'critical';
const VIEWS: { id: View; label: string }[] = [
  { id: 'collect', label: 'To collect' },
  { id: 'report', label: 'To report' },
  { id: 'reported', label: 'Reported (24 h)' },
  { id: 'critical', label: 'Critical to call' },
];
const PRIORITY_TONE = { STAT: 'red', URGENT: 'amber', ROUTINE: 'slate' } as const;
const PRIORITY_LABEL = { STAT: 'STAT', URGENT: 'Urgent', ROUTINE: 'Routine' } as const;

interface Props {
  session: Session;
  onDeskChange?(desk: DeskId): void;
}

/**
 * The laboratory worklist (LIS): collect samples, then enter results against each test's
 * parameters. The Patient Service flags every value from the lab's reference ranges.
 */
export function LabDesk({ session, onDeskChange }: Props) {
  const canCollect = session.roles.includes('LAB_TECHNICIAN') || session.roles.includes('NURSE');
  const canReport = session.roles.includes('LAB_TECHNICIAN');
  const [view, setView] = useState<View>(canReport ? 'report' : 'collect');
  const [open, setOpen] = useState<OrderRecord[] | null>(null);
  const [reported, setReported] = useState<OrderRecord[] | null>(null);
  const [toCall, setToCall] = useState<CriticalResult[]>([]);
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<OrderRecord | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);

  const report = useCallback((error: unknown) => {
    setNotice({ kind: 'error', text: error instanceof Error ? error.message : 'Something went wrong' });
  }, []);

  const load = useCallback(async () => {
    try {
      const since = new Date(Date.now() - REPORTED_WINDOW_MS).toISOString();
      const [openOrders, done, critical] = await Promise.all([
        endpoints.worklist('LABORATORY', OPEN),
        endpoints.worklist('LABORATORY', ['REPORTED'], since),
        endpoints.criticalResults({ communicated: false }),
      ]);
      setOpen(openOrders);
      setToCall(critical);
      setReported([...done].sort((a, b) => Date.parse(b.completedAt ?? b.orderedAt) - Date.parse(a.completedAt ?? a.orderedAt)));
    } catch (error) {
      report(error);
    }
  }, [report]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => document.visibilityState === 'visible' && void load(), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  const lists: Record<Exclude<View, 'critical'>, OrderRecord[]> = useMemo(() => ({
    collect: (open ?? []).filter((o) => TO_COLLECT.includes(o.status)),
    report: (open ?? []).filter((o) => !TO_COLLECT.includes(o.status)),
    reported: reported ?? [],
  }), [open, reported]);
  const count = (v: View) => (v === 'critical' ? toCall.length : lists[v].length);

  const query = search.trim().toLowerCase();
  const rows = (view === 'critical' ? [] : lists[view]).filter((o) => !query
    || [o.patientName, o.uhid, o.serviceName, o.serviceCode].some((f) => f?.toLowerCase().includes(query)));

  const collect = async (order: OrderRecord) => {
    setBusy(true);
    try {
      await endpoints.collectSample(order.id);
      setNotice({ kind: 'ok', text: `Sample collected: ${order.serviceName} for ${order.patientName}.` });
      await load();
    } catch (error) {
      report(error);
    } finally {
      setBusy(false);
    }
  };

  const onReported = async (text: string, critical: boolean) => {
    setNotice({ kind: 'ok', text });
    setSelected(null);
    if (critical) setView('critical');
    await load();
  };

  return (
    <div className="flex h-full flex-col">
      <DeskHeader session={session} desk="lab" onDeskChange={onDeskChange} notice={notice} />
      <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(0,30rem)]">
        <section aria-label="Laboratory worklist" className="flex min-h-0 flex-col border-r border-slate-200 bg-white">
          <div className="flex items-center gap-2 border-b border-slate-200 px-2">
            <nav role="tablist" aria-label="Worklist views" className="flex gap-1">
              {VIEWS.map((v) => (
                <button key={v.id} type="button" role="tab" aria-selected={view === v.id}
                  onClick={() => { setView(v.id); setSelected(null); }}
                  className={`-mb-px flex h-8 items-center gap-1.5 whitespace-nowrap border-b-2 px-2 text-[12px] font-medium ${
                    view === v.id ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-600 hover:text-slate-900'}`}>
                  {v.label}
                  <Badge tone={v.id === 'critical' ? (toCall.length > 0 ? 'red' : 'slate') : v.id === 'reported' ? 'slate' : 'indigo'}>{count(v.id)}</Badge>
                </button>
              ))}
            </nav>
            <input aria-label="Search the worklist" placeholder="Patient, UHID or test" className="field ml-auto h-6 w-56"
              value={search} onChange={(e) => setSearch(e.target.value)} />
            <button type="button" className="btn-ghost h-6" onClick={() => void load()}>Refresh</button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            {view === 'critical' ? (
              <CallList results={toCall} onError={report} onLogged={async (text) => { setNotice({ kind: 'ok', text }); await load(); }} />
            ) : (
            <table className="w-full border-collapse" aria-label={VIEWS.find((v) => v.id === view)!.label}>
              <thead className="sticky top-0 bg-white">
                <tr className="border-b border-slate-200">
                  <th className="th w-16">Priority</th>
                  <th className="th">Patient</th>
                  <th className="th">Test</th>
                  <th className="th w-40">{view === 'reported' ? 'Reported' : 'Ordered'}</th>
                  <th className="th w-16">{view === 'reported' ? '' : 'Waiting'}</th>
                  <th className="th w-32" />
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {open === null && <tr><td colSpan={6} className="td py-2 text-slate-400">Loading…</td></tr>}
                {open !== null && rows.length === 0 && (
                  <tr><td colSpan={6} className="td py-2 text-slate-400">Nothing here{query ? ' matches the search' : ''}.</td></tr>
                )}
                {rows.map((o) => (
                  <tr key={o.id} aria-label={`${o.serviceName} for ${o.patientName}`}
                    className={selected?.id === o.id ? 'bg-indigo-50' : 'hover:bg-slate-50'}>
                    <td className="td"><Badge tone={PRIORITY_TONE[o.priority]}>{PRIORITY_LABEL[o.priority]}</Badge></td>
                    <td className="td">
                      <div className="font-medium">{o.patientName}</div>
                      <div className="font-mono text-[11px] text-slate-500">{o.uhid}</div>
                    </td>
                    <td className="td">
                      <div>{o.serviceName}</div>
                      {o.clinicalIndication && <div className="text-[11px] text-slate-500">For: {o.clinicalIndication}</div>}
                    </td>
                    <td className="td text-[11px] text-slate-500">
                      {view === 'reported'
                        ? <>{when(o.completedAt)}{o.reportedBy ? ` · ${o.reportedBy}` : ''}</>
                        : <>{when(o.orderedAt)} · {o.orderedBy}</>}
                    </td>
                    <td className="td text-[12px] tabular-nums text-slate-600">
                      {view === 'reported' ? '' : waitedMinutes(o.sampleCollectedAt ?? o.orderedAt)}
                    </td>
                    <td className="td text-right">
                      {view === 'collect' && canCollect && (
                        <button type="button" className="btn-secondary h-6" disabled={busy} onClick={() => void collect(o)}>
                          Collect sample
                        </button>
                      )}
                      {view === 'report' && canReport && (
                        <button type="button" className="btn-primary h-6" disabled={busy} onClick={() => setSelected(o)}>
                          Enter result
                        </button>
                      )}
                      {view === 'report' && !canReport && <span className="text-[11px] text-slate-500">Sample collected</span>}
                      {view === 'reported' && (
                        <button type="button" className="btn-ghost h-6" onClick={() => setSelected(o)}>View</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            )}
          </div>
        </section>

        <aside aria-label="Result" className="min-h-0 overflow-y-auto bg-slate-50">
          {!selected ? (
            <p className="m-6 text-center text-slate-400">
              {canReport ? 'Choose a test in "To report" to enter its result.' : 'Collected samples go to the laboratory for reporting.'}
            </p>
          ) : selected.status === 'REPORTED' ? (
            <ReportedResult order={selected} onClose={() => setSelected(null)} />
          ) : (
            <ResultEntry key={selected.id} order={selected} onError={report} onReported={onReported}
              onClose={() => { setSelected(null); void load(); }} />
          )}
        </aside>
      </div>
    </div>
  );
}

function OrderHeading({ order, template }: { order: OrderRecord; template?: ResultTemplate | null }) {
  return (
    <div className="space-y-0.5 border-b border-slate-200 bg-white px-3 py-2">
      <div className="flex flex-wrap items-baseline gap-2">
        <h2 className="text-[15px] font-semibold">{order.serviceName}</h2>
        <Badge tone={PRIORITY_TONE[order.priority]}>{PRIORITY_LABEL[order.priority]}</Badge>
      </div>
      <p>
        <span className="font-medium">{order.patientName}</span>{' '}
        <span className="font-mono text-[12px] text-slate-500">{order.uhid}</span>
        {template && <span className="text-slate-500"> · {template.ageYears ?? '?'} y / {template.sex}</span>}
      </p>
      {order.clinicalIndication && <p className="text-[12px]"><span className="text-slate-500">For: </span>{order.clinicalIndication}</p>}
      <p className="text-[11px] text-slate-500">
        Ordered by {order.orderedBy} {when(order.orderedAt)}
        {order.sampleCollectedAt && ` · sample ${when(order.sampleCollectedAt)}`}
      </p>
    </div>
  );
}

function ReportedResult({ order, onClose }: { order: OrderRecord; onClose(): void }) {
  return (
    <div>
      <OrderHeading order={order} />
      <div className="space-y-1 px-3 py-2 text-[12px]">
        {order.resultSummary && <p><span className="text-slate-500">Result: </span><span className="font-medium">{order.resultSummary}</span></p>}
        {(order.resultValues ?? []).length > 0 && <ResultTable values={order.resultValues!} />}
        <p className="text-[11px] text-slate-500">Reported{order.reportedBy ? ` by ${order.reportedBy}` : ''} {when(order.completedAt)}</p>
        <button type="button" className="btn-ghost" onClick={onClose}>Close</button>
      </div>
    </div>
  );
}

const FLAG_OPTIONS: ResultFlag[] = ['N', 'L', 'H', 'A', 'C'];

function FlagPreview({ flag }: { flag: ResultFlag | undefined }) {
  if (!flag) return <span className="text-slate-400">—</span>;
  const tone = flag === 'C' ? 'font-bold text-red-700' : flag === 'L' ? 'font-semibold text-sky-700'
    : flag === 'N' ? 'text-slate-500' : 'font-semibold text-amber-800';
  return <span className={tone}>{FLAG_LABEL[flag]}{flag === 'H' ? ' ↑' : flag === 'L' ? ' ↓' : ''}</span>;
}

interface EntryProps {
  order: OrderRecord;
  onError(error: unknown): void;
  onReported(text: string, critical: boolean): Promise<void>;
  onClose(): void;
}

function ResultEntry({ order, onError, onReported, onClose }: EntryProps) {
  const [template, setTemplate] = useState<ResultTemplate | null>(null);
  const [rows, setRows] = useState<ResultRow[]>([]);
  const [summary, setSummary] = useState('');
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    endpoints.resultTemplate(order.id).then((t) => {
      setTemplate(t);
      setRows(emptyRows(t));
    }, onError);
  }, [order.id, onError]);

  const edit = (code: string, patch: Partial<ResultRow>) =>
    setRows((current) => current.map((r) => (r.analyte.code === code ? { ...r, ...patch } : r)));

  // Enter moves down the column, as on a paper worksheet; the last value goes on to the summary.
  const nextOnEnter = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    const inputs = [...document.querySelectorAll<HTMLElement>('[data-result-input], [data-result-summary]')];
    inputs[inputs.indexOf(event.currentTarget) + 1]?.focus();
  };

  const submit = async () => {
    const found = resultProblems(rows, summary);
    setProblems(found);
    if (found.length > 0) return;
    const critical = criticalValues(rows);
    if (critical.length > 0 && !window.confirm(
      `Critical value${critical.length > 1 ? 's' : ''}: ${critical.join(', ')}.\n\n`
      + `Report now, and tell ${order.orderedBy} (the ordering doctor) straight away?`)) return;
    setBusy(true);
    try {
      await endpoints.reportResult(order.id, { resultSummary: summary.trim() || undefined, values: resultValues(rows) });
      await onReported(critical.length > 0
        ? `Reported with critical value: ${critical.join(', ')}. Call ${order.orderedBy} now and log the call.`
        : `Reported: ${order.serviceName} for ${order.patientName}.`, critical.length > 0);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'ORDER_CLOSED') {
        onError(new Error('This test was reported or cancelled meanwhile. The worklist has been refreshed.'));
        onClose();
      } else {
        onError(error);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <OrderHeading order={order} template={template} />
      <div className="space-y-2 px-3 py-2">
        {template?.note && (
          <p role="note" className="rounded border border-amber-200 bg-amber-50 px-2 py-1 text-[12px] text-amber-900">{template.note}</p>
        )}
        {template === null ? (
          <p className="text-slate-400">Loading the test parameters…</p>
        ) : rows.length > 0 && (
          <table className="w-full border-collapse bg-white" aria-label="Result values">
            <thead>
              <tr className="border-b border-slate-200">
                <th className="th">Parameter</th>
                <th className="th w-24">Result</th>
                <th className="th w-16">Unit</th>
                <th className="th w-20">Reference</th>
                <th className="th w-24">Flag</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((row) => {
                const flag = effectiveFlag(row);
                const auto = row.flag === 'AUTO';
                return (
                  <tr key={row.analyte.code} className={flag === 'C' ? 'bg-red-50' : ''}>
                    <td className="td">{row.analyte.name}</td>
                    <td className="td">
                      <input aria-label={`${row.analyte.name} result`} inputMode="decimal" className="field h-6 text-right tabular-nums"
                        data-result-input value={row.value} disabled={busy} onKeyDown={nextOnEnter}
                        onChange={(e) => edit(row.analyte.code, { value: e.target.value })} />
                    </td>
                    <td className="td text-[12px] text-slate-500">{row.analyte.unit}</td>
                    <td className="td text-[12px] text-slate-500">{row.analyte.referenceRange}</td>
                    <td className="td">
                      <div className="flex items-center gap-1">
                        <select aria-label={`${row.analyte.name} flag`} className="field h-6 w-16 px-0.5 text-[11px]" value={row.flag}
                          disabled={busy} onChange={(e) => edit(row.analyte.code, { flag: e.target.value as FlagChoice })}>
                          {hasRange(row.analyte) ? <option value="AUTO">Auto</option> : <option value="">—</option>}
                          {FLAG_OPTIONS.map((f) => <option key={f} value={f}>{FLAG_LABEL[f]}</option>)}
                        </select>
                        <span aria-label={`${row.analyte.name} flag preview`} className="text-[11px]">
                          {row.value.trim() !== '' && (auto || flag) ? <FlagPreview flag={flag} /> : null}
                        </span>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
        <label className="block text-[11px] text-slate-500">Result summary / comments
          <textarea data-result-summary rows={3} maxLength={4000} disabled={busy} className="field h-auto py-1 text-[13px] leading-snug"
            value={summary} onChange={(e) => setSummary(e.target.value)}
            placeholder={rows.length === 0 ? 'This test has no parameters set up: write the result here' : 'Optional'} />
        </label>
        {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
        <div className="flex items-center gap-2">
          <span className="text-[11px] text-slate-500">Flags are set by the laboratory&apos;s ranges; a hand-chosen flag can only raise one.</span>
          <button type="button" className="btn-ghost ml-auto" disabled={busy} onClick={onClose}>Cancel</button>
          <button type="button" className="btn-primary" disabled={busy || template === null} onClick={() => void submit()}>Report result</button>
        </div>
      </div>
    </div>
  );
}

const METHODS: { id: CommunicationMethod; label: string }[] = [
  { id: 'PHONE', label: 'Phone' }, { id: 'IN_PERSON', label: 'In person' }, { id: 'WARD_NURSE', label: 'Ward nurse' },
];

/** Critical results not yet called to the treating team, each with its call log form. */
function CallList({ results, onLogged, onError }: {
  results: CriticalResult[];
  onLogged(text: string): Promise<void>;
  onError(error: unknown): void;
}) {
  const [calls, setCalls] = useState<Record<string, { to: string; method: CommunicationMethod; readBack: boolean }>>({});
  const [busy, setBusy] = useState(false);
  if (results.length === 0) {
    return <p className="p-3 text-slate-400">Every critical result has been called to the treating team.</p>;
  }
  const callOf = (c: CriticalResult) => calls[c.id] ?? { to: c.orderedBy, method: 'PHONE' as CommunicationMethod, readBack: false };
  const log = async (c: CriticalResult) => {
    const call = callOf(c);
    setBusy(true);
    try {
      await endpoints.communicateCritical(c.id, { communicatedTo: call.to.trim(), method: call.method, readBack: call.readBack });
      await onLogged(`Call logged: ${criticalValuesText(c)} for ${c.patientName}, told ${call.to.trim()}.`);
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <table className="w-full border-collapse" aria-label="Critical to call">
      <thead className="sticky top-0 bg-white">
        <tr className="border-b border-slate-200">
          <th className="th">Patient</th>
          <th className="th">Critical value</th>
          <th className="th w-32">Reported</th>
          <th className="th w-[26rem]">Call</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {results.map((c) => {
          const call = callOf(c);
          const set = (patch: Partial<typeof call>) => setCalls({ ...calls, [c.id]: { ...call, ...patch } });
          return (
            <tr key={c.id} aria-label={`Critical ${c.serviceName} for ${c.patientName}`} className="bg-red-50/40">
              <td className="td">
                <div className="font-medium">{c.patientName}</div>
                <div className="font-mono text-[11px] text-slate-500">{c.uhid}</div>
              </td>
              <td className="td">
                <div className="font-bold text-red-700">{criticalValuesText(c)}</div>
                <div className="text-[11px] text-slate-500">{c.serviceName} · ordered by {c.orderedBy}</div>
              </td>
              <td className="td text-[11px] text-slate-500">{when(c.reportedAt)} · {c.reportedBy}</td>
              <td className="td">
                <div className="flex flex-wrap items-center gap-1">
                  <input aria-label={`Told whom about ${c.patientName}`} className="field h-6 w-40" maxLength={150} value={call.to} disabled={busy}
                    onChange={(e) => set({ to: e.target.value })} />
                  <select aria-label={`How ${c.patientName}'s result was told`} className="field h-6 w-28" value={call.method} disabled={busy}
                    onChange={(e) => set({ method: e.target.value as CommunicationMethod })}>
                    {METHODS.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
                  </select>
                  <label className="flex items-center gap-1 text-[11px]">
                    <input type="checkbox" checked={call.readBack} disabled={busy} onChange={(e) => set({ readBack: e.target.checked })} />
                    Read back
                  </label>
                  <button type="button" className="btn h-6 bg-red-700 text-white hover:bg-red-800" disabled={busy || !call.readBack || !call.to.trim()}
                    onClick={() => void log(c)}>Log call</button>
                </div>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
