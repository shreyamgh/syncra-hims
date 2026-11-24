import { type FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { endpoints } from '../api/endpoints';
import type { EncounterRecord, ImmunizationRecord, NewImmunization, Vaccine, VaccineRoute, VaccineSite } from '../api/types';
import {
  type VaccinationDraft, type VaccinationMode, emptyVaccination, vaccinationProblems,
} from '../emr/workspace';
import { Badge, sentence } from './ui';

interface Props {
  record: EncounterRecord;
  canRecord: boolean;
  onError(error: unknown): void;
  onDone(text: string): void;
}

const MODES: { id: VaccinationMode; label: string }[] = [
  { id: 'GIVEN', label: 'Given now' },
  { id: 'CARD', label: "From the patient's card" },
  { id: 'NOT_GIVEN', label: 'Not given' },
];
const ROUTES: VaccineRoute[] = ['IM', 'SC', 'ID', 'ORAL', 'INTRANASAL'];
const SITES: VaccineSite[] = ['LEFT_DELTOID', 'RIGHT_DELTOID', 'LEFT_ARM', 'RIGHT_ARM', 'LEFT_THIGH', 'RIGHT_THIGH', 'ORAL', 'NASAL'];
const PROGRAMMES: { id: Vaccine['programme']; label: string }[] = [
  { id: 'UIP', label: 'Universal Immunisation Programme' }, { id: 'ADULT', label: 'Adult' }, { id: 'OTHER', label: 'Other' },
];

/** Today in the hospital's time zone, as yyyy-mm-dd. */
export const hospitalToday = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });

/**
 * The patient's vaccinations across visits, and recording a dose: given here (batch and expiry
 * required), transcribed from the patient's card, or not given with a reason.
 */
export function VaccinationsPanel({ record, canRecord, onError, onDone }: Props) {
  const [vaccines, setVaccines] = useState<Vaccine[]>([]);
  const [history, setHistory] = useState<ImmunizationRecord[] | null>(null);
  const [draft, setDraft] = useState<VaccinationDraft>(emptyVaccination);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [withdrawing, setWithdrawing] = useState<{ id: string; reason: string } | null>(null);
  const uhid = record.patient.uhid;

  const load = useCallback(async () => {
    try {
      setHistory((await endpoints.immunizations(uhid)).immunizations);
    } catch (error) {
      onError(error);
    }
  }, [uhid, onError]);

  useEffect(() => {
    endpoints.vaccines().then(setVaccines, onError);
    void load();
  }, [load, onError]);

  const vaccine = vaccines.find((v) => v.code === draft.vaccineCode);
  const set = (patch: Partial<VaccinationDraft>) => setDraft((d) => ({ ...d, ...patch }));

  const chooseVaccine = (code: string) => {
    const v = vaccines.find((x) => x.code === code);
    const given = (history ?? []).filter((h) => h.vaccineCode === code && h.status === 'COMPLETED' && !h.enteredInError).length;
    const next = v?.seriesDoses ? Math.min(given + 1, v.seriesDoses) : undefined;
    set({
      vaccineCode: code, route: v?.defaultRoute ?? '', doseMl: v?.defaultDoseMl ? String(v.defaultDoseMl) : '',
      doseNumber: next ? String(next) : '', site: v?.defaultRoute === 'ORAL' ? 'ORAL' : '',
    });
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found = vaccinationProblems(draft, hospitalToday(), vaccine?.seriesDoses);
    setProblems(found);
    if (found.length > 0 || !vaccine) return;
    const given = draft.mode === 'GIVEN';
    const dose: NewImmunization = {
      vaccineCode: draft.vaccineCode,
      status: draft.mode === 'NOT_GIVEN' ? 'NOT_DONE' : 'COMPLETED',
      notDoneReason: draft.mode === 'NOT_GIVEN' ? draft.notDoneReason.trim() : undefined,
      doseNumber: draft.doseNumber ? Number(draft.doseNumber) : undefined,
      primarySource: draft.mode !== 'CARD',
      reportOrigin: draft.mode === 'CARD' ? draft.reportOrigin.trim() : undefined,
      administeredAt: draft.mode === 'CARD' ? new Date(`${draft.givenOn}T12:00:00+05:30`).toISOString() : undefined,
      lotNumber: draft.lotNumber.trim() || undefined,
      expiryDate: draft.expiryDate || undefined,
      manufacturer: draft.manufacturer.trim() || undefined,
      route: given && draft.route ? (draft.route as VaccineRoute) : undefined,
      site: given && draft.site ? (draft.site as VaccineSite) : undefined,
      doseMl: given && draft.doseMl ? Number(draft.doseMl) : undefined,
      nextDueDate: draft.nextDueDate || undefined,
      notes: draft.notes.trim() || undefined,
    };
    setBusy(true);
    try {
      await endpoints.recordImmunization(record.encounterId, dose);
      onDone(`${vaccine.name}${dose.doseNumber ? ` dose ${dose.doseNumber}` : ''}: ${draft.mode === 'NOT_GIVEN' ? 'recorded as not given' : 'recorded'}.`);
      setDraft(emptyVaccination());
      await load();
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  const withdraw = async (dose: ImmunizationRecord) => {
    if (!withdrawing?.reason.trim()) return;
    setBusy(true);
    try {
      await endpoints.immunizationInError(dose.encounterId, dose.id, withdrawing.reason.trim());
      setWithdrawing(null);
      onDone(`${dose.vaccineName}: marked entered in error.`);
      await load();
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  const today = hospitalToday();
  const due = useMemo(() => dueDoses(history ?? [], today), [history, today]);

  return (
    <section aria-label="Vaccinations" className="space-y-3 px-3 py-2">
      {due.length > 0 && (
        <p role="note" className="rounded border border-amber-200 bg-amber-50 px-2 py-1 text-[12px] text-amber-900">
          Due: {due.map((d) => `${d.vaccineName} (next dose since ${d.nextDueDate})`).join('; ')}.
        </p>
      )}
      {canRecord && (
        <form onSubmit={(e) => void submit(e)} aria-label="Record a vaccination" className="rounded border border-slate-200 bg-slate-50 p-2">
          <div className="mb-2 flex flex-wrap items-center gap-3">
            <h3 className="section-title">Record a vaccination</h3>
            <div role="radiogroup" aria-label="How this dose is recorded" className="flex gap-1">
              {MODES.map((m) => (
                <label key={m.id} className={`btn h-6 cursor-pointer border ${draft.mode === m.id ? 'border-indigo-600 bg-indigo-50 text-indigo-700' : 'border-slate-300 bg-white text-slate-600'}`}>
                  <input type="radio" name="vaccination-mode" className="sr-only" checked={draft.mode === m.id} onChange={() => set({ mode: m.id })} />
                  {m.label}
                </label>
              ))}
            </div>
          </div>
          <div className="grid grid-cols-[minmax(0,2fr)_6rem_minmax(0,1fr)_minmax(0,1fr)] gap-2">
            <label className="text-[11px] text-slate-500">Vaccine
              <select className="field" value={draft.vaccineCode} disabled={busy} onChange={(e) => chooseVaccine(e.target.value)}>
                <option value="">Choose…</option>
                {PROGRAMMES.map((p) => (
                  <optgroup key={p.id} label={p.label}>
                    {vaccines.filter((v) => v.programme === p.id).map((v) => <option key={v.code} value={v.code}>{v.name}</option>)}
                  </optgroup>
                ))}
              </select>
            </label>
            <label className="text-[11px] text-slate-500">Dose
              <select className="field" value={draft.doseNumber} disabled={busy || !vaccine} onChange={(e) => set({ doseNumber: e.target.value })}>
                <option value="">—</option>
                {Array.from({ length: vaccine?.seriesDoses ?? 10 }, (_, i) => String(i + 1)).map((n) => (
                  <option key={n} value={n}>{vaccine?.seriesDoses ? `${n} of ${vaccine.seriesDoses}` : n}</option>
                ))}
              </select>
            </label>
            {draft.mode === 'CARD' ? (
              <>
                <label className="text-[11px] text-slate-500">Date on the card
                  <input type="date" className="field" value={draft.givenOn} max={today} disabled={busy} onChange={(e) => set({ givenOn: e.target.value })} />
                </label>
                <label className="text-[11px] text-slate-500">Source
                  <input className="field" placeholder="e.g. MCP card" maxLength={100} value={draft.reportOrigin} disabled={busy}
                    onChange={(e) => set({ reportOrigin: e.target.value })} />
                </label>
              </>
            ) : draft.mode === 'NOT_GIVEN' ? (
              <label className="col-span-2 text-[11px] text-slate-500">Reason not given
                <input className="field" placeholder="e.g. febrile today, parent declined" maxLength={200} value={draft.notDoneReason} disabled={busy}
                  onChange={(e) => set({ notDoneReason: e.target.value })} />
              </label>
            ) : (
              <>
                <label className="text-[11px] text-slate-500">Batch (lot) number
                  <input className="field font-mono" maxLength={40} value={draft.lotNumber} disabled={busy} onChange={(e) => set({ lotNumber: e.target.value })} />
                </label>
                <label className="text-[11px] text-slate-500">Vial expiry
                  <input type="date" className="field" value={draft.expiryDate} disabled={busy} onChange={(e) => set({ expiryDate: e.target.value })} />
                </label>
              </>
            )}
          </div>
          {draft.mode === 'GIVEN' && (
            <div className="mt-2 grid grid-cols-[minmax(0,1fr)_6rem_minmax(0,1fr)_5rem_minmax(0,1fr)] gap-2">
              <label className="text-[11px] text-slate-500">Manufacturer
                <input className="field" maxLength={100} value={draft.manufacturer} disabled={busy} onChange={(e) => set({ manufacturer: e.target.value })} />
              </label>
              <label className="text-[11px] text-slate-500">Route
                <select className="field" value={draft.route} disabled={busy} onChange={(e) => set({ route: e.target.value })}>
                  <option value="">—</option>
                  {ROUTES.map((r) => <option key={r} value={r}>{r === 'INTRANASAL' ? 'Intranasal' : r}</option>)}
                </select>
              </label>
              <label className="text-[11px] text-slate-500">Site
                <select className="field" value={draft.site} disabled={busy} onChange={(e) => set({ site: e.target.value })}>
                  <option value="">—</option>
                  {SITES.map((s) => <option key={s} value={s}>{sentence(s)}</option>)}
                </select>
              </label>
              <label className="text-[11px] text-slate-500">Dose (mL)
                <input inputMode="decimal" className="field text-right" value={draft.doseMl} disabled={busy} onChange={(e) => set({ doseMl: e.target.value })} />
              </label>
              <label className="text-[11px] text-slate-500">Next dose due
                <input type="date" className="field" min={today} value={draft.nextDueDate} disabled={busy} onChange={(e) => set({ nextDueDate: e.target.value })} />
              </label>
            </div>
          )}
          <div className="mt-2 flex items-center gap-2">
            {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
            <button type="submit" className="btn-primary ml-auto" disabled={busy}>Record vaccination</button>
          </div>
        </form>
      )}

      <table className="w-full border-collapse" aria-label="Vaccination history">
        <thead>
          <tr className="border-b border-slate-200">
            <th className="th">Vaccine</th>
            <th className="th w-28">Date</th>
            <th className="th">Record</th>
            <th className="th w-40">Batch</th>
            <th className="th w-28">Next due</th>
            {canRecord && <th className="th w-44" />}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {history === null && <tr><td colSpan={6} className="td py-2 text-slate-400">Loading…</td></tr>}
          {history?.length === 0 && <tr><td colSpan={6} className="td py-2 text-slate-500">No vaccinations recorded.</td></tr>}
          {history?.map((h) => (
            <tr key={h.id} aria-label={`${h.vaccineName}${h.doseNumber ? ` dose ${h.doseNumber}` : ''}`} className={h.enteredInError ? 'text-slate-400' : ''}>
              <td className="td">
                <span className={h.enteredInError ? 'line-through' : 'font-medium'}>{h.vaccineName}</span>
                {h.doseNumber && <span className="ml-1 text-[11px] text-slate-500">dose {h.doseNumber}{h.seriesDoses ? ` of ${h.seriesDoses}` : ''}</span>}
              </td>
              <td className="td text-[12px] tabular-nums">{h.administeredAt.slice(0, 10)}</td>
              <td className="td text-[12px]">
                {h.enteredInError ? <Badge tone="slate">Entered in error</Badge>
                  : h.status === 'NOT_DONE' ? <><Badge tone="amber">Not given</Badge> {h.notDoneReason}</>
                    : h.primarySource ? <><Badge tone="emerald">Given here</Badge> {h.administeredBy}{h.site ? ` · ${sentence(h.site)}` : ''}</>
                      : <><Badge tone="sky">From card</Badge> {h.reportOrigin}</>}
              </td>
              <td className="td font-mono text-[11px] text-slate-600">{h.lotNumber}{h.expiryDate ? ` · exp ${h.expiryDate}` : ''}</td>
              <td className={`td text-[12px] tabular-nums ${h.nextDueDate && h.nextDueDate <= today && !h.enteredInError ? 'font-semibold text-amber-800' : ''}`}>{h.nextDueDate}</td>
              {canRecord && (
                <td className="td text-right">
                  {!h.enteredInError && withdrawing?.id !== h.id && (
                    <button type="button" className="btn-ghost h-6 text-red-700" disabled={busy} onClick={() => setWithdrawing({ id: h.id, reason: '' })}>
                      Entered in error
                    </button>
                  )}
                  {withdrawing?.id === h.id && (
                    <div className="flex gap-1">
                      <input aria-label={`Why ${h.vaccineName} is entered in error`} className="field h-6" maxLength={300} placeholder="Reason" autoFocus
                        value={withdrawing.reason} onChange={(e) => setWithdrawing({ id: h.id, reason: e.target.value })} />
                      <button type="button" className="btn-secondary h-6" disabled={busy || !withdrawing.reason.trim()} onClick={() => void withdraw(h)}>Confirm</button>
                    </div>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** Vaccines whose latest valid dose has a next-due date that has arrived. */
export function dueDoses(history: ImmunizationRecord[], today: string): ImmunizationRecord[] {
  const latest = new Map<string, ImmunizationRecord>();
  for (const h of history) {
    if (h.enteredInError || h.status !== 'COMPLETED') continue;
    const current = latest.get(h.vaccineCode);
    if (!current || h.administeredAt > current.administeredAt) latest.set(h.vaccineCode, h);
  }
  return [...latest.values()].filter((h) => h.nextDueDate && h.nextDueDate <= today);
}
