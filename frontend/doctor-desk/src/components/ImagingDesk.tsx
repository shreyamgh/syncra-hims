import { useCallback, useEffect, useMemo, useState } from 'react';
import { endpoints } from '../api/endpoints';
import type { ImagingStudy, OrderRecord, OrderStatus } from '../api/types';
import type { Session } from '../auth/session';
import type { DeskId } from '../emr/diagnostics';
import { openImages } from '../emr/pacs';
import { DeskHeader, type Notice } from './DeskHeader';
import { Badge, waitedMinutes, when } from './ui';

const REFRESH_MS = 30_000;
const AWAITING: OrderStatus[] = ['ORDERED', 'SCHEDULED', 'IN_PROGRESS'];
const REPORTED_WINDOW_MS = 7 * 24 * 3_600_000;

type View = 'awaiting' | 'unmatched' | 'report' | 'reported';
const VIEWS: { id: View; label: string }[] = [
  { id: 'awaiting', label: 'Awaiting images' },
  { id: 'unmatched', label: 'Unmatched studies' },
  { id: 'report', label: 'To report' },
  { id: 'reported', label: 'Reported (7 days)' },
];
const PRIORITY_TONE = { STAT: 'red', URGENT: 'amber', ROUTINE: 'slate' } as const;
const PRIORITY_LABEL = { STAT: 'STAT', URGENT: 'Urgent', ROUTINE: 'Routine' } as const;

interface Props {
  session: Session;
  onDeskChange?(desk: DeskId): void;
}

interface Lists {
  awaiting: OrderRecord[];
  unmatched: ImagingStudy[];
  report: ImagingStudy[];
  reported: ImagingStudy[];
}

/**
 * The radiology worklist (RIS): orders waiting to be imaged, studies the PACS could not match,
 * and studies to report. Reports are signed notes; a signed report is corrected by an addendum.
 */
export function ImagingDesk({ session, onDeskChange }: Props) {
  const isRadiologist = session.roles.includes('RADIOLOGIST');
  const [view, setView] = useState<View>(isRadiologist ? 'report' : 'awaiting');
  const [lists, setLists] = useState<Lists | null>(null);
  const [selected, setSelected] = useState<ImagingStudy | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [matchChoice, setMatchChoice] = useState<Record<string, string>>({});

  const report = useCallback((error: unknown) => {
    setNotice({ kind: 'error', text: error instanceof Error ? error.message : 'Something went wrong' });
  }, []);

  const load = useCallback(async () => {
    try {
      const [awaiting, unmatched, toReport, reported] = await Promise.all([
        endpoints.worklist('RADIOLOGY', AWAITING),
        endpoints.studies({ matched: false }),
        endpoints.studies({ matched: true, reportStatus: ['UNREPORTED', 'DRAFT'] }),
        endpoints.studies({
          reportStatus: ['FINAL', 'ADDENDUM'], reportedSince: new Date(Date.now() - REPORTED_WINDOW_MS).toISOString(),
        }),
      ]);
      setLists({
        awaiting,
        unmatched,
        report: toReport,
        reported: [...reported].sort((a, b) => Date.parse(b.reportedAt ?? '') - Date.parse(a.reportedAt ?? '')),
      });
      // Keep the open study current (e.g. after another radiologist's addendum).
      setSelected((current) => (current ? [...toReport, ...reported].find((s) => s.id === current.id) ?? current : null));
    } catch (error) {
      report(error);
    }
  }, [report]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => document.visibilityState === 'visible' && void load(), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  const ordersByUhid = useMemo(() => {
    const map = new Map<string, OrderRecord[]>();
    for (const o of lists?.awaiting ?? []) map.set(o.uhid ?? '', [...(map.get(o.uhid ?? '') ?? []), o]);
    return map;
  }, [lists]);

  const match = async (study: ImagingStudy) => {
    const orderId = matchChoice[study.id];
    if (!orderId) return;
    setBusy(true);
    try {
      await endpoints.matchStudy(study.id, orderId);
      const order = lists?.awaiting.find((o) => o.id === orderId);
      setNotice({ kind: 'ok', text: `Matched ${study.description ?? 'the study'} to ${order?.serviceName ?? 'its order'}${order?.accessionNumber ? ` (${order.accessionNumber})` : ''}.` });
      await load();
    } catch (error) {
      report(error);
    } finally {
      setBusy(false);
    }
  };

  const counts: Record<View, number> = {
    awaiting: lists?.awaiting.length ?? 0,
    unmatched: lists?.unmatched.length ?? 0,
    report: lists?.report.length ?? 0,
    reported: lists?.reported.length ?? 0,
  };

  return (
    <div className="flex h-full flex-col">
      <DeskHeader session={session} desk="imaging" onDeskChange={onDeskChange} notice={notice} />
      <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(0,32rem)]">
        <section aria-label="Imaging worklist" className="flex min-h-0 flex-col border-r border-slate-200 bg-white">
          <div className="flex items-center gap-2 border-b border-slate-200 px-2">
            <nav role="tablist" aria-label="Worklist views" className="flex gap-1 overflow-x-auto">
              {VIEWS.map((v) => (
                <button key={v.id} type="button" role="tab" aria-selected={view === v.id}
                  onClick={() => { setView(v.id); setSelected(null); }}
                  className={`-mb-px flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap border-b-2 px-2 text-[12px] font-medium ${
                    view === v.id ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-600 hover:text-slate-900'}`}>
                  {v.label}
                  <Badge tone={v.id === 'unmatched' && counts.unmatched > 0 ? 'amber' : v.id === 'reported' ? 'slate' : 'indigo'}>
                    {counts[v.id]}
                  </Badge>
                </button>
              ))}
            </nav>
            <button type="button" className="btn-ghost ml-auto h-6" onClick={() => void load()}>Refresh</button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            {lists === null ? (
              <p className="p-3 text-slate-400">Loading…</p>
            ) : view === 'awaiting' ? (
              <AwaitingTable orders={lists.awaiting} />
            ) : view === 'unmatched' ? (
              <table className="w-full border-collapse" aria-label="Unmatched studies">
                <thead className="sticky top-0 bg-white">
                  <tr className="border-b border-slate-200">
                    <th className="th">Patient</th>
                    <th className="th">Study</th>
                    <th className="th w-32">Received</th>
                    <th className="th w-80">Match to order</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {lists.unmatched.length === 0 && (
                    <tr><td colSpan={4} className="td py-2 text-slate-400">Every study in the PACS is matched to its order.</td></tr>
                  )}
                  {lists.unmatched.map((s) => {
                    const candidates = ordersByUhid.get(s.uhid) ?? [];
                    return (
                      <tr key={s.id} aria-label={`${s.description ?? s.modality} for ${s.patientName}`}>
                        <td className="td"><PatientCell name={s.patientName} uhid={s.uhid} /></td>
                        <td className="td"><StudyCell study={s} /></td>
                        <td className="td text-[11px] text-slate-500">{when(s.receivedAt)}</td>
                        <td className="td">
                          {candidates.length === 0 ? (
                            <span className="text-[12px] text-slate-500">No open imaging order for this patient: ask the doctor to order it.</span>
                          ) : (
                            <div className="flex gap-1">
                              <select aria-label={`Order for ${s.patientName}'s study`} className="field h-6 text-[12px]" disabled={busy}
                                value={matchChoice[s.id] ?? ''} onChange={(e) => setMatchChoice({ ...matchChoice, [s.id]: e.target.value })}>
                                <option value="">Choose the order…</option>
                                {candidates.map((o) => (
                                  <option key={o.id} value={o.id}>{o.serviceName} · {o.accessionNumber} · {when(o.orderedAt)}</option>
                                ))}
                              </select>
                              <button type="button" className="btn-secondary h-6" disabled={busy || !matchChoice[s.id]} onClick={() => void match(s)}>
                                Match
                              </button>
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            ) : (
              <StudyTable studies={view === 'report' ? lists.report : lists.reported} reported={view === 'reported'}
                selectedId={selected?.id ?? null} onOpen={setSelected} />
            )}
          </div>
        </section>

        <aside aria-label="Report" className="min-h-0 overflow-y-auto bg-slate-50">
          {selected ? (
            <ReportPanel key={selected.id + selected.reportStatus} study={selected} canReport={isRadiologist} onError={report}
              onClose={() => setSelected(null)}
              onSigned={async (text) => { setNotice({ kind: 'ok', text }); await load(); }} />
          ) : (
            <p className="m-6 text-center text-slate-400">
              {isRadiologist ? 'Choose a study in "To report" to write its report.' : 'Studies reach "To report" once the PACS has them and they are matched.'}
            </p>
          )}
        </aside>
      </div>
    </div>
  );
}

function PatientCell({ name, uhid }: { name?: string; uhid?: string }) {
  return (
    <>
      <div className="font-medium">{name}</div>
      <div className="font-mono text-[11px] text-slate-500">{uhid}</div>
    </>
  );
}

function StudyCell({ study }: { study: ImagingStudy }) {
  return (
    <>
      <div>{study.serviceName ?? study.description ?? 'Study'}</div>
      <div className="text-[11px] text-slate-500">
        {[study.modality, study.description !== study.serviceName && study.description, study.instanceCount !== undefined && `${study.instanceCount} image${study.instanceCount === 1 ? '' : 's'}`]
          .filter(Boolean).join(' · ')}
      </div>
    </>
  );
}

function AwaitingTable({ orders }: { orders: OrderRecord[] }) {
  return (
    <>
      <p className="border-b border-slate-100 px-3 py-1.5 text-[12px] text-slate-600">
        Key the accession number in at the modality: the study is matched to its order when it reaches the PACS.
      </p>
      <table className="w-full border-collapse" aria-label="Awaiting images">
        <thead className="sticky top-0 bg-white">
          <tr className="border-b border-slate-200">
            <th className="th w-16">Priority</th>
            <th className="th">Patient</th>
            <th className="th">Examination</th>
            <th className="th w-36">Accession</th>
            <th className="th w-36">Ordered</th>
            <th className="th w-16">Waiting</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {orders.length === 0 && <tr><td colSpan={6} className="td py-2 text-slate-400">No examinations waiting.</td></tr>}
          {orders.map((o) => (
            <tr key={o.id} aria-label={`${o.serviceName} for ${o.patientName}`}>
              <td className="td"><Badge tone={PRIORITY_TONE[o.priority]}>{PRIORITY_LABEL[o.priority]}</Badge></td>
              <td className="td"><PatientCell name={o.patientName} uhid={o.uhid} /></td>
              <td className="td">
                <div>{o.serviceName}</div>
                {o.clinicalIndication && <div className="text-[11px] text-slate-500">For: {o.clinicalIndication}</div>}
              </td>
              <td className="td whitespace-nowrap font-mono text-[13px] font-semibold">{o.accessionNumber}</td>
              <td className="td text-[11px] text-slate-500">{when(o.orderedAt)} · {o.orderedBy}</td>
              <td className="td text-[12px] tabular-nums text-slate-600">{waitedMinutes(o.orderedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function StudyTable({ studies, reported, selectedId, onOpen }: {
  studies: ImagingStudy[];
  reported: boolean;
  selectedId: string | null;
  onOpen(study: ImagingStudy): void;
}) {
  return (
    <table className="w-full border-collapse" aria-label={reported ? 'Reported studies' : 'Studies to report'}>
      <thead className="sticky top-0 bg-white">
        <tr className="border-b border-slate-200">
          <th className="th w-16">Priority</th>
          <th className="th">Patient</th>
          <th className="th">Study</th>
          <th className="th w-36">{reported ? 'Reported' : 'Received'}</th>
          <th className="th w-20" />
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {studies.length === 0 && (
          <tr><td colSpan={5} className="td py-2 text-slate-400">{reported ? 'No reports in the last 7 days.' : 'No studies waiting for a report.'}</td></tr>
        )}
        {studies.map((s) => (
          <tr key={s.id} aria-label={`${s.serviceName ?? s.description} for ${s.patientName}`}
            className={selectedId === s.id ? 'bg-indigo-50' : 'hover:bg-slate-50'}>
            <td className="td">{s.priority && <Badge tone={PRIORITY_TONE[s.priority]}>{PRIORITY_LABEL[s.priority]}</Badge>}</td>
            <td className="td"><PatientCell name={s.patientName} uhid={s.uhid} /></td>
            <td className="td"><StudyCell study={s} /></td>
            <td className="td text-[11px] text-slate-500">
              {reported ? <>{when(s.reportedAt)} · {s.reportedBy} {s.reportStatus === 'ADDENDUM' && <Badge tone="amber">Addendum</Badge>}</> : when(s.receivedAt)}
            </td>
            <td className="td text-right">
              <button type="button" className={reported ? 'btn-ghost h-6' : 'btn-primary h-6'} onClick={() => onOpen(s)}>
                {reported ? 'View' : 'Report'}
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

interface PanelProps {
  study: ImagingStudy;
  canReport: boolean;
  onError(error: unknown): void;
  onClose(): void;
  onSigned(text: string): Promise<void>;
}

function ReportPanel({ study, canReport, onError, onClose, onSigned }: PanelProps) {
  const signed = study.reportStatus === 'FINAL' || study.reportStatus === 'ADDENDUM';
  const [writing, setWriting] = useState(!signed);
  const [content, setContent] = useState(signed ? study.reportContent ?? '' : '');
  const [impression, setImpression] = useState('');
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const sign = async () => {
    const found: string[] = [];
    if (!content.trim()) found.push('Write the findings');
    if (!impression.trim()) found.push('Give the impression (the one line the doctor sees first)');
    if (signed && content.trim() === (study.reportContent ?? '').trim()) found.push('Change the report to add an addendum');
    setProblems(found);
    if (found.length > 0) return;
    setBusy(true);
    try {
      const saved = await endpoints.reportStudy(study.id, { content: content.trim(), impression: impression.trim() });
      setWriting(false);
      await onSigned(`${saved.reportStatus === 'ADDENDUM' ? 'Addendum signed' : 'Report signed'}: ${study.serviceName ?? 'study'} for ${study.patientName}.`);
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="space-y-0.5 border-b border-slate-200 bg-white px-3 py-2">
        <div className="flex flex-wrap items-baseline gap-2">
          <h2 className="text-[15px] font-semibold">{study.serviceName ?? study.description}</h2>
          {study.priority && <Badge tone={PRIORITY_TONE[study.priority]}>{PRIORITY_LABEL[study.priority]}</Badge>}
          {signed && <Badge tone={study.reportStatus === 'ADDENDUM' ? 'amber' : 'emerald'}>{study.reportStatus === 'ADDENDUM' ? 'Addendum' : 'Final'}</Badge>}
        </div>
        <p>
          <span className="font-medium">{study.patientName}</span>{' '}
          <span className="font-mono text-[12px] text-slate-500">{study.uhid}</span>
        </p>
        <p className="text-[12px] text-slate-600">
          {[study.modality, study.description, study.studyDate && `study ${study.studyDate}`,
            study.seriesCount !== undefined && `${study.seriesCount} series`,
            study.instanceCount !== undefined && `${study.instanceCount} image${study.instanceCount === 1 ? '' : 's'}`,
            study.accessionNumber && `accession ${study.accessionNumber}`].filter(Boolean).join(' · ')}
        </p>
        <p className="text-[12px]">
          <span className="text-slate-500">Clinical question: </span>
          {study.clinicalIndication ?? <span className="text-slate-400">not given</span>}
          {study.orderedBy && <span className="text-slate-500"> · ordered by {study.orderedBy}</span>}
        </p>
        <div className="pt-1">
          <button type="button" className="btn-secondary h-6" onClick={() => void openImages(study.studyInstanceUid).catch(onError)}>
            View images
          </button>
        </div>
      </div>

      <div className="space-y-2 px-3 py-2">
        {signed && !writing && (
          <>
            <section aria-label="Signed report" className="rounded border border-slate-200 bg-white px-2 py-1.5">
              <p className="whitespace-pre-wrap leading-snug">{study.reportContent}</p>
              <p className="mt-1 text-[11px] text-slate-500">Signed by {study.reportedBy} {when(study.reportedAt)}</p>
            </section>
            <div className="flex gap-2">
              <button type="button" className="btn-ghost" onClick={onClose}>Close</button>
              {canReport && <button type="button" className="btn-secondary ml-auto" onClick={() => setWriting(true)}>Add addendum</button>}
            </div>
          </>
        )}
        {writing && canReport && (
          <div role="group" aria-label={signed ? 'Addendum' : 'New report'} className="space-y-2">
            {signed && (
              <p className="text-[12px] text-slate-600">
                The addendum replaces the signed report as the current version; the original stays in the record.
              </p>
            )}
            <label className="block text-[11px] text-slate-500">Findings
              <textarea aria-label="Findings" rows={10} maxLength={20_000} disabled={busy}
                className="field h-auto py-1 text-[13px] leading-snug" value={content} onChange={(e) => setContent(e.target.value)} />
            </label>
            <label className="block text-[11px] text-slate-500">Impression
              <input aria-label="Impression" maxLength={500} disabled={busy} className="field"
                placeholder="One line, shown to the doctor with the order" value={impression} onChange={(e) => setImpression(e.target.value)} />
            </label>
            {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
            <div className="flex gap-2">
              <button type="button" className="btn-ghost" disabled={busy} onClick={() => (signed ? setWriting(false) : onClose())}>Cancel</button>
              <button type="button" className="btn-primary ml-auto" disabled={busy} onClick={() => void sign()}>
                {signed ? 'Sign addendum' : 'Sign report'}
              </button>
            </div>
          </div>
        )}
        {writing && !canReport && <p className="text-slate-500">Waiting for a radiologist to report.</p>}
      </div>
    </div>
  );
}
