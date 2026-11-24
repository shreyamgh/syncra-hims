import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { type NewAllergy, endpoints } from '../api/endpoints';
import { ApiError } from '../api/http';
import type { AllergenType, AllergyEntry, AllergySeverity, AllergyStatus } from '../api/types';
import { allergyProblems } from '../emr/workspace';
import { Badge, label, sentence, when } from './ui';

interface Props {
  uhid: string;
  canEdit: boolean;
  /** Changes when the encounter record reloads, so the list re-reads after edits elsewhere. */
  refreshKey: unknown;
  onChanged(): Promise<void>;
  onError(error: unknown): void;
  onDone(text: string): void;
}

const TYPES: { value: AllergenType; label: string; placeholder: string }[] = [
  { value: 'DRUG', label: 'Drug', placeholder: 'e.g. Amoxicillin' },
  { value: 'DRUG_CLASS', label: 'Drug class', placeholder: 'e.g. Penicillin' },
  { value: 'FOOD', label: 'Food', placeholder: 'e.g. Peanut' },
  { value: 'ENVIRONMENT', label: 'Environment', placeholder: 'e.g. Latex, dust' },
  { value: 'OTHER', label: 'Other', placeholder: 'Allergen' },
];
const SEVERITIES: AllergySeverity[] = ['MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING'];
const SEVERITY_TONE = { MILD: 'slate', MODERATE: 'amber', SEVERE: 'red', LIFE_THREATENING: 'red' } as const;
const END_STATUSES: { value: AllergyStatus; label: string }[] = [
  { value: 'INACTIVE', label: 'Inactive' },
  { value: 'RESOLVED', label: 'Resolved' },
  { value: 'REFUTED', label: 'Refuted (recorded wrongly)' },
];

const emptyAllergy = (): NewAllergy => ({ allergenType: 'DRUG', allergenText: '', drugClass: '', reaction: '', severity: undefined });

/**
 * The patient's allergy list, across visits. Active allergies feed the drug-safety check;
 * an allergy is never deleted, only ended (inactive, resolved, or refuted when recorded wrongly).
 */
export function AllergiesPanel({ uhid, canEdit, refreshKey, onChanged, onError, onDone }: Props) {
  const [allergies, setAllergies] = useState<AllergyEntry[] | null>(null);
  const [draft, setDraft] = useState<NewAllergy>(emptyAllergy);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setAllergies(await endpoints.allergies(uhid));
    } catch (error) {
      onError(error);
    }
  }, [uhid, onError]);

  useEffect(() => {
    void load();
  }, [load, refreshKey]);

  const record = async (event: FormEvent) => {
    event.preventDefault();
    const found = allergyProblems(draft);
    setProblems(found);
    if (found.length > 0) return;
    setBusy(true);
    try {
      const saved = await endpoints.addAllergy(uhid, {
        allergenType: draft.allergenType,
        allergenText: draft.allergenText.trim(),
        drugClass: draft.drugClass?.trim() || undefined,
        reaction: draft.reaction?.trim() || undefined,
        severity: draft.severity,
      });
      setDraft(emptyAllergy());
      onDone(`Allergy recorded: ${saved.allergenText}`);
      await Promise.all([load(), onChanged()]);
    } catch (error) {
      onError(error instanceof ApiError && error.code === 'ALLERGY_EXISTS'
        ? new Error(`${draft.allergenText.trim()} is already recorded as an active allergy.`)
        : error);
    } finally {
      setBusy(false);
    }
  };

  const changeStatus = async (allergy: AllergyEntry, status: AllergyStatus) => {
    if (status === 'REFUTED' && !window.confirm(`Mark the ${allergy.allergenText} allergy as recorded wrongly?`)) return;
    setBusy(true);
    try {
      await endpoints.allergyStatus(uhid, allergy.id, status);
      onDone(`${allergy.allergenText}: ${label(status)}.`);
      await Promise.all([load(), onChanged()]);
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  const type = TYPES.find((t) => t.value === draft.allergenType) ?? TYPES[0]!;
  const activeNames = new Set((allergies ?? []).filter((a) => a.status === 'ACTIVE').map((a) => a.allergenText.toLowerCase()));

  return (
    <section aria-label="Allergies" className="space-y-3 px-3 py-2">
      {canEdit && (
        <form onSubmit={(e) => void record(e)} aria-label="Record an allergy" className="rounded border border-slate-200 bg-slate-50 p-2">
          <h3 className="section-title mb-1">Record an allergy</h3>
          <div className="grid grid-cols-[8rem_1fr_10rem] gap-2">
            <label className="text-[11px] text-slate-500">Type
              <select className="field" value={draft.allergenType} disabled={busy}
                onChange={(e) => setDraft({ ...draft, allergenType: e.target.value as AllergenType })}>
                {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
            </label>
            <label className="text-[11px] text-slate-500">Allergen
              <input className="field" value={draft.allergenText} placeholder={type.placeholder} maxLength={200} disabled={busy}
                onChange={(e) => setDraft({ ...draft, allergenText: e.target.value })} />
            </label>
            <label className="text-[11px] text-slate-500">Severity
              <select className="field" value={draft.severity ?? ''} disabled={busy}
                onChange={(e) => setDraft({ ...draft, severity: (e.target.value || undefined) as AllergySeverity | undefined })}>
                <option value="">Not known</option>
                {SEVERITIES.map((s) => <option key={s} value={s}>{sentence(s)}</option>)}
              </select>
            </label>
            {(draft.allergenType === 'DRUG' || draft.allergenType === 'DRUG_CLASS') && (
              <label className="text-[11px] text-slate-500">Drug class{draft.allergenType === 'DRUG_CLASS' ? '' : ' (optional)'}
                <input className="field" value={draft.drugClass ?? ''} placeholder="e.g. Penicillins" maxLength={100} disabled={busy}
                  onChange={(e) => setDraft({ ...draft, drugClass: e.target.value })} />
              </label>
            )}
            <label className={`text-[11px] text-slate-500 ${draft.allergenType === 'DRUG' || draft.allergenType === 'DRUG_CLASS' ? 'col-span-2' : 'col-span-3'}`}>
              Reaction
              <input className="field" value={draft.reaction ?? ''} placeholder="e.g. Urticaria, angioedema" maxLength={300} disabled={busy}
                onChange={(e) => setDraft({ ...draft, reaction: e.target.value })} />
            </label>
          </div>
          <div className="mt-2 flex items-center gap-2">
            {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
            <button type="submit" className="btn-primary ml-auto" disabled={busy}>Record allergy</button>
          </div>
        </form>
      )}

      <table className="w-full border-collapse" aria-label="Allergy list">
        <thead>
          <tr className="border-b border-slate-200">
            <th className="th">Allergen</th>
            <th className="th w-24">Type</th>
            <th className="th">Reaction</th>
            <th className="th w-28">Severity</th>
            <th className="th w-20">Status</th>
            <th className="th w-36">Recorded</th>
            {canEdit && <th className="th w-40" />}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {allergies === null && <tr><td colSpan={7} className="td py-2 text-slate-400">Loading…</td></tr>}
          {allergies?.length === 0 && (
            <tr>
              <td colSpan={7} className="td py-2 text-slate-500">
                No allergies recorded. Ask the patient: an empty list is not the same as &quot;no known allergies&quot;.
              </td>
            </tr>
          )}
          {allergies?.map((a) => {
            const active = a.status === 'ACTIVE';
            return (
              <tr key={a.id} className={active ? '' : 'text-slate-400'}>
                <td className="td">
                  <span className={active ? 'font-medium text-slate-900' : a.status === 'REFUTED' ? 'line-through' : ''}>{a.allergenText}</span>
                  {a.drugClass && <span className="ml-1 text-[11px] text-slate-500">({a.drugClass})</span>}
                </td>
                <td className="td text-[12px]">{TYPES.find((t) => t.value === a.allergenType)?.label ?? a.allergenType}</td>
                <td className="td text-[12px]">{a.reaction}</td>
                <td className="td">{a.severity && <Badge tone={active ? SEVERITY_TONE[a.severity] : 'slate'}>{sentence(a.severity)}</Badge>}</td>
                <td className="td text-[12px]">{active ? <Badge tone="red">Active</Badge> : sentence(a.status)}</td>
                <td className="td text-[11px] text-slate-500">{when(a.recordedAt)} · {a.recordedBy}</td>
                {canEdit && (
                  <td className="td text-right">
                    {active && (
                      <select aria-label={`End the ${a.allergenText} allergy`} className="field h-6 w-36 text-[12px]" value="" disabled={busy}
                        onChange={(e) => e.target.value && void changeStatus(a, e.target.value as AllergyStatus)}>
                        <option value="">End as…</option>
                        {END_STATUSES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
                      </select>
                    )}
                    {(a.status === 'INACTIVE' || a.status === 'RESOLVED') && !activeNames.has(a.allergenText.toLowerCase()) && (
                      <button type="button" className="btn-ghost h-6" disabled={busy} onClick={() => void changeStatus(a, 'ACTIVE')}>
                        Reactivate
                      </button>
                    )}
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}
