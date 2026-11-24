import type { ResultValue } from '../api/types';
import { FLAG_LABEL, isAbnormal } from '../emr/workspace';

/** Reported values with units, reference ranges and flags; critical rows are red. */
export function ResultTable({ values }: { values: ResultValue[] }) {
  return (
    <table className="my-1 w-full max-w-2xl border-collapse">
      <thead>
        <tr className="border-b border-slate-200">
          <th className="th">Test</th>
          <th className="th w-28 text-right">Result</th>
          <th className="th w-20">Unit</th>
          <th className="th w-28">Reference</th>
          <th className="th w-20">Flag</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {values.map((v) => {
          const abnormal = isAbnormal(v.flag);
          const critical = v.flag === 'C';
          const tone = critical ? 'text-red-700' : v.flag === 'L' ? 'text-sky-700' : abnormal ? 'text-amber-800' : '';
          return (
            <tr key={v.code} className={critical ? 'bg-red-50' : ''}>
              <td className="td">{v.name}</td>
              <td className={`td text-right tabular-nums ${abnormal ? `font-semibold ${tone}` : ''}`}>
                {v.value ?? v.text}
                {v.flag === 'H' && ' ↑'}
                {v.flag === 'L' && ' ↓'}
              </td>
              <td className="td text-slate-500">{v.unit}</td>
              <td className="td text-slate-500">{v.referenceRange}</td>
              <td className={`td ${tone} ${critical ? 'font-bold' : ''}`}>{v.flag && v.flag !== 'N' ? FLAG_LABEL[v.flag] : ''}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
