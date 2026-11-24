import { useState } from 'react';
import { endpoints } from '../api/endpoints';
import type { CommunicationMethod, CriticalResult } from '../api/types';
import { when } from './ui';

export const METHOD_LABEL: Record<CommunicationMethod, string> = {
  PHONE: 'by phone', IN_PERSON: 'in person', WARD_NURSE: 'through the ward nurse',
};

export const criticalValuesText = (c: CriticalResult) =>
  c.criticalValues.map((v) => `${v.name} ${v.value ?? v.text ?? ''}${v.unit ? ` ${v.unit}` : ''}`).join(', ');

/** One line of the call trail: "Told Dr X by phone 10:02, read back · Acknowledged by Dr Y 10:15". */
export function CriticalTrail({ result }: { result: CriticalResult }) {
  return (
    <span>
      {result.communicatedAt
        ? <>Told {result.communicatedTo} {METHOD_LABEL[result.communicationMethod!]} {when(result.communicatedAt)}, read back ({result.communicatedBy})</>
        : <span className="font-semibold text-red-700">Not yet called to the ward</span>}
      {' · '}
      {result.acknowledgedAt
        ? <>Acknowledged by {result.acknowledgedBy} {when(result.acknowledgedAt)}{result.acknowledgementNote ? `: ${result.acknowledgementNote}` : ''}</>
        : <span className="font-semibold text-red-700">not yet acknowledged</span>}
    </span>
  );
}

/**
 * The doctor's critical results waiting for acknowledgement, above everything else on the desk.
 * Acknowledging records who saw it and when, with an optional note of the action taken.
 */
export function CriticalBanner({ results, onAcknowledged, onError }: {
  results: CriticalResult[];
  onAcknowledged(text: string): Promise<void>;
  onError(error: unknown): void;
}) {
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  if (results.length === 0) return null;
  const acknowledge = async (c: CriticalResult) => {
    setBusy(true);
    try {
      await endpoints.acknowledgeCritical(c.id, notes[c.id]?.trim() || undefined);
      await onAcknowledged(`Acknowledged: ${criticalValuesText(c)} for ${c.patientName}.`);
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <section aria-live="assertive" aria-label="Critical results to acknowledge" className="shrink-0 border-b border-red-200 bg-red-50 px-3 py-1.5">
      <h2 className="text-[12px] font-semibold text-red-800">
        {results.length === 1 ? 'A critical result needs' : `${results.length} critical results need`} your acknowledgement
      </h2>
      <ul className="divide-y divide-red-100">
        {results.map((c) => (
          <li key={c.id} aria-label={`Critical ${c.serviceName} for ${c.patientName}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-1 text-[12px]">
            <span className="font-bold text-red-700">{criticalValuesText(c)}</span>
            <span><b>{c.patientName}</b> <span className="font-mono text-[11px] text-slate-500">{c.uhid}</span> · {c.serviceName} · reported {when(c.reportedAt)}</span>
            <span className="text-slate-600">
              {c.communicatedAt ? `called by ${c.communicatedBy} to ${c.communicatedTo}` : 'not yet called by the lab'}
            </span>
            <span className="ml-auto flex gap-1">
              <input aria-label={`Action taken for ${c.patientName}`} className="field h-6 w-56" maxLength={500} placeholder="Action taken (optional)"
                value={notes[c.id] ?? ''} disabled={busy} onChange={(e) => setNotes({ ...notes, [c.id]: e.target.value })} />
              <button type="button" className="btn h-6 bg-red-700 text-white hover:bg-red-800" disabled={busy} onClick={() => void acknowledge(c)}>
                Acknowledge
              </button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
