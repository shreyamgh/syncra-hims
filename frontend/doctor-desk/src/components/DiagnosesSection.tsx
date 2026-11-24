import { useEffect, useState } from 'react';
import { endpoints } from '../api/endpoints';
import type { Icd10Code } from '../api/types';
import { DIAGNOSIS_TYPES, type DiagnosisRow, type DiagnosisType, emptyDiagnosis } from '../emr/form';
import { DictatedMark, label } from './ui';

interface Props {
  rows: DiagnosisRow[];
  onChange(rows: DiagnosisRow[]): void;
  onEdit(id: string, patch: Partial<DiagnosisRow>): void;
  disabled: boolean;
}

/** Diagnoses with ICD-10 codes: suggested by dictation, or picked from the code search. */
export function DiagnosesSection({ rows, onChange, onEdit, disabled }: Props) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Icd10Code[]>([]);

  useEffect(() => {
    if (query.trim().length < 2) {
      setResults([]);
      return undefined;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      endpoints.icd10(query.trim(), controller.signal).then(setResults, () => setResults([]));
    }, 250);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [query]);

  const pick = (code: Icd10Code) => {
    onChange([...rows, { ...emptyDiagnosis(), description: code.title, icd10Code: code.code, primary: rows.length === 0 }]);
    setQuery('');
    setResults([]);
  };

  return (
    <section aria-label="Diagnoses" className="px-3 py-2">
      <div className="mb-1 flex items-center gap-2">
        <h3 className="section-title">Diagnosis</h3>
        <div className="relative ml-auto">
          <input aria-label="Find ICD-10 code" placeholder="Find ICD-10 (e.g. dengue, J06)" className="field h-6 w-60"
            value={query} onChange={(e) => setQuery(e.target.value)} disabled={disabled} />
          {results.length > 0 && (
            <ul role="listbox" aria-label="ICD-10 results"
              className="absolute right-0 z-10 mt-0.5 w-80 rounded border border-slate-200 bg-white shadow">
              {results.map((r) => (
                <li key={r.code}>
                  <button type="button" role="option" aria-selected="false" className="w-full px-2 py-1 text-left hover:bg-indigo-50"
                    onClick={() => pick(r)}>
                    <span className="font-mono text-[12px] text-indigo-700">{r.code}</span> {r.title}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <button type="button" className="btn-ghost h-6" onClick={() => onChange([...rows, emptyDiagnosis()])} disabled={disabled}>
          + Add
        </button>
      </div>
      <table className="w-full border-collapse">
        <thead>
          <tr className="border-b border-slate-200">
            <th className="th">Diagnosis</th>
            <th className="th w-24">ICD-10</th>
            <th className="th w-32">Type</th>
            <th className="th w-14 text-center">Primary</th>
            <th className="th w-6" />
            <th className="th w-6" />
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((row) => {
            const dictated = row.source === 'DICTATION';
            return (
              <tr key={row.id}>
                <td className="td">
                  <input aria-label="Diagnosis" className={`field ${dictated ? 'field-dictated' : ''}`} value={row.description}
                    onChange={(e) => onEdit(row.id, { description: e.target.value })} disabled={disabled} />
                </td>
                <td className="td">
                  <input aria-label="ICD-10 code" className={`field font-mono uppercase ${dictated ? 'field-dictated' : ''}`}
                    value={row.icd10Code} onChange={(e) => onEdit(row.id, { icd10Code: e.target.value })} disabled={disabled} />
                </td>
                <td className="td">
                  <select aria-label="Diagnosis type" className={`field ${dictated ? 'field-dictated' : ''}`} value={row.type}
                    onChange={(e) => onEdit(row.id, { type: e.target.value as DiagnosisType })} disabled={disabled}>
                    {DIAGNOSIS_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
                  </select>
                </td>
                <td className="td text-center">
                  <input type="checkbox" aria-label="Primary diagnosis" checked={row.primary}
                    onChange={(e) => onEdit(row.id, { primary: e.target.checked })} disabled={disabled} />
                </td>
                <td className="td">{dictated && <DictatedMark />}</td>
                <td className="td">
                  <button type="button" aria-label="Remove diagnosis" className="text-slate-400 hover:text-red-600"
                    onClick={() => onChange(rows.filter((r) => r.id !== row.id))} disabled={disabled}>×</button>
                </td>
              </tr>
            );
          })}
          {rows.length === 0 && (
            <tr><td colSpan={6} className="td py-2 text-slate-400">No diagnosis yet — dictate &quot;diagnosis …&quot; or search a code.</td></tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
