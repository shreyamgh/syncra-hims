import { type FormEvent, useCallback, useEffect, useState } from 'react';
import {
  type ExternalRecord, type ExternalRecordDetail, HI_TYPES, type HiType, type HiuArtefact, type HiuConsent,
  addDays, consentStatusText, hiu,
} from '../api/hiu';
import { hospitalToday } from './VaccinationsPanel';
import { Badge, when } from './ui';

interface Props {
  uhid: string;
  isDoctor: boolean;
  onError(error: unknown): void;
  onDone(text: string): void;
}

const MAX_KEEP_DAYS = 30;
const PURPOSES = [
  { code: 'CAREMGT', label: 'Care management (treating this patient)' },
  { code: 'BTG', label: 'Break the glass (emergency)' },
];
const STATUS_TONE: Record<string, 'emerald' | 'amber' | 'red' | 'slate' | 'indigo'> = {
  REQUESTED: 'amber', GRANTED: 'emerald', DENIED: 'red', EXPIRED: 'slate', REVOKED: 'slate', FAILED: 'red',
  ACKNOWLEDGED: 'amber', TRANSFERRED: 'emerald', PARTIAL: 'amber',
};

interface Ask { purpose: string; hiTypes: HiType[]; from: string; to: string; keepUntil: string }
const emptyAsk = (): Ask => {
  const today = hospitalToday();
  return { purpose: 'CAREMGT', hiTypes: HI_TYPES.filter((t) => t.code !== 'Invoice').map((t) => t.code),
    from: addDays(today, -730), to: today, keepUntil: addDays(today, 7) };
};

export function askProblems(ask: Ask, today: string): string[] {
  const found: string[] = [];
  if (ask.hiTypes.length === 0) found.push('Choose at least one kind of record');
  if (!ask.from || !ask.to || ask.from > ask.to) found.push('The period must start before it ends');
  if (ask.to > today) found.push('The period cannot end after today');
  if (!ask.keepUntil || ask.keepUntil < today) found.push('Keep the records until today or later');
  else if (ask.keepUntil > addDays(today, MAX_KEEP_DAYS)) found.push(`Records can be kept for at most ${MAX_KEEP_DAYS} days`);
  return found;
}

/**
 * The patient's records from other hospitals, through ABDM: the doctor asks for consent, the
 * patient approves in their ABHA app, and what each hospital sends appears here until the
 * keep-until date (or until the patient withdraws consent).
 */
export function ExternalRecordsPanel({ uhid, isDoctor, onError, onDone }: Props) {
  const [consents, setConsents] = useState<HiuConsent[] | null>(null);
  const [records, setRecords] = useState<ExternalRecord[]>([]);
  const [open, setOpen] = useState<ExternalRecordDetail | null>(null);
  const [asking, setAsking] = useState(false);
  const [ask, setAsk] = useState<Ask>(emptyAsk);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!isDoctor) return;
    try {
      const [c, r] = await Promise.all([hiu.consents(uhid), hiu.records(uhid)]);
      setConsents(c);
      setRecords(r);
    } catch (e) {
      onError(e);
    }
  }, [uhid, isDoctor, onError]);
  useEffect(() => {
    void load();
  }, [load]);

  if (!isDoctor) {
    return <p className="p-3 text-slate-500">Records from other hospitals are shown to the treating doctor.</p>;
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found = askProblems(ask, hospitalToday());
    setProblems(found);
    if (found.length > 0) return;
    setBusy(true);
    try {
      await hiu.ask({ uhid, ...ask });
      onDone('Consent asked: the patient approves in their ABHA app.');
      setAsking(false);
      setAsk(emptyAsk());
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  };

  const openRecord = async (id: string) => {
    try {
      setOpen(await hiu.record(id));
    } catch (e) {
      onError(e);
    }
  };

  const download = async (record: ExternalRecord) => {
    try {
      const document = await hiu.fhir(record.id);
      const url = URL.createObjectURL(new Blob([JSON.stringify(document, null, 2)], { type: 'application/fhir+json' }));
      const link = Object.assign(window.document.createElement('a'), { href: url, download: `${record.recordType ?? 'record'}-${record.id.slice(0, 8)}.json` });
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      onError(e);
    }
  };

  const again = async (artefact: HiuArtefact) => {
    setBusy(true);
    try {
      await hiu.again(artefact.consentId, uhid);
      onDone(`Asked ${artefact.hipName ?? 'the hospital'} again.`);
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label="Records from other hospitals" className="space-y-3 p-3">
      <header className="flex items-center gap-2">
        <h2 className="text-[14px] font-semibold">Records from other hospitals</h2>
        <span className="text-[11px] text-slate-500">via ABDM, with the patient's consent</span>
        <button type="button" className="btn-ghost ml-auto h-6" onClick={() => void load()}>Refresh</button>
        {!asking && <button type="button" className="btn-secondary h-6" onClick={() => setAsking(true)}>Ask for consent…</button>}
      </header>

      {asking && (
        <form aria-label="Ask for consent" onSubmit={(e) => void submit(e)} className="space-y-2 rounded border border-slate-200 bg-white p-2">
          <fieldset className="flex flex-wrap gap-x-3 gap-y-1 text-[12px]">
            <legend className="mb-1 text-[11px] text-slate-500">Records wanted</legend>
            {HI_TYPES.map((t) => (
              <label key={t.code} className="flex items-center gap-1">
                <input type="checkbox" checked={ask.hiTypes.includes(t.code)} disabled={busy}
                  onChange={(e) => setAsk({ ...ask, hiTypes: e.target.checked ? [...ask.hiTypes, t.code] : ask.hiTypes.filter((c) => c !== t.code) })} />
                {t.label}
              </label>
            ))}
          </fieldset>
          <div className="flex flex-wrap items-end gap-2">
            <label className="text-[11px] text-slate-500">From<input type="date" className="field" value={ask.from} disabled={busy} onChange={(e) => setAsk({ ...ask, from: e.target.value })} /></label>
            <label className="text-[11px] text-slate-500">To<input type="date" className="field" value={ask.to} max={hospitalToday()} disabled={busy} onChange={(e) => setAsk({ ...ask, to: e.target.value })} /></label>
            <label className="text-[11px] text-slate-500">Keep until<input type="date" className="field" value={ask.keepUntil} disabled={busy}
              max={addDays(hospitalToday(), MAX_KEEP_DAYS)} onChange={(e) => setAsk({ ...ask, keepUntil: e.target.value })} /></label>
            <label className="text-[11px] text-slate-500">Purpose
              <select className="field" value={ask.purpose} disabled={busy} onChange={(e) => setAsk({ ...ask, purpose: e.target.value })}>
                {PURPOSES.map((p) => <option key={p.code} value={p.code}>{p.label}</option>)}
              </select>
            </label>
          </div>
          <p className="text-[11px] text-slate-500">The patient approves in their ABHA (PHR) app. Records are erased here after the keep-until date, or as soon as the patient withdraws consent.</p>
          {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => { setAsking(false); setProblems([]); }}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={busy}>Ask the patient's consent</button>
          </div>
        </form>
      )}

      <section aria-label="Consent requests" className="space-y-1">
        {consents?.length === 0 && <p className="text-slate-400">No consent asked yet.</p>}
        {consents?.map((c) => (
          <article key={c.id} aria-label={`Consent asked ${when(c.requestedAt)}`} className="rounded border border-slate-200 bg-white p-2 text-[12px]">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={STATUS_TONE[c.status] ?? 'slate'}>{c.status.toLowerCase()}</Badge>
              <span>{consentStatusText(c)}</span>
              <span className="ml-auto text-[11px] text-slate-500">{c.requestedBy} · {when(c.requestedAt)} · keep until {when(c.eraseAt)}</span>
            </div>
            {c.artefacts.map((a) => {
              const latest = a.transfers[a.transfers.length - 1];
              const canAskAgain = a.status === 'GRANTED' && (!latest || latest.status === 'FAILED' || latest.status === 'PARTIAL');
              return (
                <div key={a.consentId} className="mt-1 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-1">
                  <b>{a.hipName ?? (a.detailsReceived ? a.hipId : 'Hospital details awaited')}</b>
                  {a.status !== 'GRANTED' && <Badge tone="slate">{a.status.toLowerCase()}</Badge>}
                  {latest && <Badge tone={STATUS_TONE[latest.status] ?? 'slate'}>{latest.status === 'TRANSFERRED' ? 'received' : latest.status.toLowerCase()}</Badge>}
                  {latest && <span className="text-slate-500">{latest.records} record(s){latest.error ? ` · ${latest.error}` : ''}</span>}
                  {canAskAgain && <button type="button" className="btn-ghost ml-auto h-6" disabled={busy} onClick={() => void again(a)}>Ask again</button>}
                </div>
              );
            })}
          </article>
        ))}
      </section>

      <section aria-label="Records received" className="rounded border border-slate-200 bg-white">
        <h3 className="border-b border-slate-200 px-2 py-1 text-[12px] font-semibold">Records received</h3>
        {records.length === 0 ? <p className="p-2 text-slate-400">None yet.</p> : (
          <ul className="divide-y divide-slate-100">
            {records.map((r) => (
              <li key={r.id} className="flex items-center gap-2 px-2 py-1 text-[12px]">
                <button type="button" className="text-left text-indigo-700 hover:underline" onClick={() => void openRecord(r.id)}>{r.title ?? 'Health record'}</button>
                <span className="text-slate-500">{r.hipName} · {r.recordDate ? when(r.recordDate) : 'undated'}</span>
                <button type="button" className="btn-ghost ml-auto h-6" onClick={() => void download(r)}>FHIR</button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {open && (
        <section aria-label={open.summary.title} className="rounded border border-indigo-200 bg-white p-2 text-[12px]">
          <header className="mb-1 flex items-start gap-2">
            <div>
              <h3 className="text-[13px] font-semibold">{open.summary.title}</h3>
              <p className="text-[11px] text-slate-500">{[open.record.hipName, open.summary.date, open.summary.author].filter(Boolean).join(' · ')}</p>
            </div>
            <button type="button" className="btn-ghost ml-auto h-6" onClick={() => setOpen(null)}>Close</button>
          </header>
          {open.summary.sections.map((s) => (
            <div key={s.title} className="mt-1">
              <h4 className="section-title">{s.title}</h4>
              <ul className="ml-1">{s.lines.map((l, i) => <li key={i} className="whitespace-pre-wrap">{l}</li>)}</ul>
            </div>
          ))}
          <p className="mt-2 text-[10px] text-slate-400">Shared by {open.record.hipName ?? 'another hospital'} under the patient's consent; erased here {when(open.record.eraseAt)}.</p>
        </section>
      )}
    </section>
  );
}
