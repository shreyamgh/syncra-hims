import type { ReactNode } from 'react';
import type { Priority, QueueStatus } from '../api/types';

export function MicIcon({ className = 'h-4 w-4' }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} className={className} aria-hidden="true">
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v3" strokeLinecap="round" />
    </svg>
  );
}

export function StopIcon({ className = 'h-4 w-4' }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className} aria-hidden="true">
      <rect x="6" y="6" width="12" height="12" rx="2" />
    </svg>
  );
}

/** Marks a value that was filled by dictation (and not yet edited by hand). */
export function DictatedMark() {
  return (
    <span title="Filled by voice dictation" className="inline-flex text-violet-600">
      <MicIcon className="h-3 w-3" />
      <span className="sr-only">dictated</span>
    </span>
  );
}

const PRIORITY_STYLE: Record<Priority, string> = {
  EMERGENCY: 'bg-red-100 text-red-700 ring-red-200',
  SENIOR_CITIZEN: 'bg-amber-100 text-amber-800 ring-amber-200',
  PREGNANT: 'bg-pink-100 text-pink-700 ring-pink-200',
  DIFFERENTLY_ABLED: 'bg-sky-100 text-sky-800 ring-sky-200',
  CHILD_UNDER_5: 'bg-lime-100 text-lime-800 ring-lime-200',
  NORMAL: '',
};
const PRIORITY_LABEL: Record<Priority, string> = {
  EMERGENCY: 'EMG',
  SENIOR_CITIZEN: 'SR',
  PREGNANT: 'PRG',
  DIFFERENTLY_ABLED: 'DA',
  CHILD_UNDER_5: '<5',
  NORMAL: '',
};

export function PriorityBadge({ priority }: { priority: Priority }) {
  if (priority === 'NORMAL') return null;
  return (
    <span
      title={priority.replace(/_/g, ' ').toLowerCase()}
      className={`rounded px-1 py-px text-[10px] font-semibold ring-1 ring-inset ${PRIORITY_STYLE[priority]}`}
    >
      {PRIORITY_LABEL[priority]}
    </span>
  );
}

const STATUS_STYLE: Record<QueueStatus, string> = {
  WAITING: 'text-slate-600',
  CALLED: 'text-indigo-700 font-semibold',
  IN_CONSULTATION: 'text-emerald-700 font-semibold',
  ON_HOLD: 'text-amber-700',
  COMPLETED: 'text-slate-400',
  NO_SHOW: 'text-slate-400 line-through',
  CANCELLED: 'text-slate-400 line-through',
};
const STATUS_LABEL: Record<QueueStatus, string> = {
  WAITING: 'Waiting',
  CALLED: 'Called',
  IN_CONSULTATION: 'In consult',
  ON_HOLD: 'On hold',
  COMPLETED: 'Done',
  NO_SHOW: 'No show',
  CANCELLED: 'Cancelled',
};

export function QueueStatusText({ status }: { status: QueueStatus }) {
  return <span className={`text-[12px] ${STATUS_STYLE[status]}`}>{STATUS_LABEL[status]}</span>;
}

export function waitedMinutes(fromIso: string, now = Date.now()): string {
  const minutes = Math.max(0, Math.round((now - Date.parse(fromIso)) / 60_000));
  return minutes < 60 ? `${minutes}m` : `${Math.floor(minutes / 60)}h${String(minutes % 60).padStart(2, '0')}`;
}

export const label = (code: string | undefined | null) => (code ? code.replace(/_/g, ' ').toLowerCase() : '');

/** "LIFE_THREATENING" → "Life threatening": a code as a badge or option caption. */
export const sentence = (code: string | undefined | null) => {
  const text = label(code);
  return text.charAt(0).toUpperCase() + text.slice(1);
};

/** "3 days", "1 week" — a duration with its unit in readable, correctly pluralised form. */
export function duration(value: number | undefined | null, unit: string | undefined | null): string {
  if (!value || !unit) return '';
  const word = unit.toLowerCase().replace(/s$/, '');
  return `${value} ${word}${value === 1 ? '' : 's'}`;
}

/** "20 Nov, 14:05" in the hospital's locale (24-hour clock). */
export function when(iso: string | undefined | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
}

/** A small rounded status/kind badge. */
export function Badge({ tone = 'slate', children, title }: {
  tone?: 'slate' | 'red' | 'amber' | 'emerald' | 'indigo' | 'sky';
  children: ReactNode;
  title?: string;
}) {
  const tones = {
    slate: 'bg-slate-100 text-slate-700 ring-slate-200',
    red: 'bg-red-50 text-red-700 ring-red-200',
    amber: 'bg-amber-50 text-amber-800 ring-amber-200',
    emerald: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    indigo: 'bg-indigo-50 text-indigo-700 ring-indigo-200',
    sky: 'bg-sky-50 text-sky-800 ring-sky-200',
  } as const;
  return (
    <span title={title} className={`inline-flex items-center whitespace-nowrap rounded px-1.5 py-px text-[11px] font-medium ring-1 ring-inset ${tones[tone]}`}>
      {children}
    </span>
  );
}
