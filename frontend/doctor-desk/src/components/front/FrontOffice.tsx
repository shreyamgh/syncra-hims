import { useCallback, useState } from 'react';
import type { Patient } from '../../api/frontoffice';
import type { Session } from '../../auth/session';
import type { DeskId } from '../../emr/diagnostics';
import { DeskHeader, type Notice } from '../DeskHeader';
import { AdmissionsPanel } from './AdmissionsPanel';
import { BillingPanel } from './BillingPanel';
import { PatientsPanel } from './PatientsPanel';

type Tab = 'patients' | 'admissions' | 'billing';
const TABS: { id: Tab; label: string }[] = [
  { id: 'patients', label: 'Patients & OPD' },
  { id: 'admissions', label: 'Admissions & beds' },
  { id: 'billing', label: 'Billing' },
];

/**
 * The front office: registration and the OPD counter, the bed board and admissions, and
 * billing. Each tab stays mounted, so work in progress survives switching (Alt+1..3).
 */
export function FrontOffice({ session, onDeskChange }: { session: Session; onDeskChange?(desk: DeskId): void }) {
  const has = (...roles: string[]) => roles.some((r) => session.roles.includes(r));
  const [tab, setTab] = useState<Tab>('patients');
  const [notice, setNotice] = useState<Notice | null>(null);
  const [admitFor, setAdmitFor] = useState<Patient | null>(null);
  const [billFocus, setBillFocus] = useState<{ admissionId?: string; uhid?: string } | null>(null);
  const report = useCallback((error: unknown) => {
    setNotice({ kind: 'error', text: error instanceof Error ? error.message : 'Something went wrong' });
  }, []);
  const done = useCallback((text: string) => setNotice({ kind: 'ok', text }), []);

  return (
    <div className="flex h-full flex-col" onKeyDown={(e) => {
      const i = e.altKey ? ['Digit1', 'Digit2', 'Digit3'].indexOf(e.code) : -1;
      if (i >= 0) {
        e.preventDefault();
        setTab(TABS[i]!.id);
      }
    }}>
      <DeskHeader session={session} desk="front" onDeskChange={onDeskChange} notice={notice} />
      <nav role="tablist" aria-label="Front office" className="flex shrink-0 gap-1 border-b border-slate-200 bg-white px-2">
        {TABS.map((t, i) => (
          <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} title={`${t.label} (Alt+${i + 1})`} onClick={() => setTab(t.id)}
            className={`-mb-px h-8 whitespace-nowrap border-b-2 px-2 text-[12px] font-medium ${tab === t.id ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-600 hover:text-slate-900'}`}>
            {t.label}
          </button>
        ))}
      </nav>
      <div role="tabpanel" aria-label="patients" className={tab === 'patients' ? 'flex min-h-0 flex-1' : 'hidden'}>
        <PatientsPanel canRegister={has('RECEPTIONIST', 'ADMINISTRATOR')} canCollect={has('RECEPTIONIST', 'BILLING_CLERK', 'ADMINISTRATOR')}
          onError={report} onDone={done}
          onAdmit={(p) => { setAdmitFor(p); setTab('admissions'); }}
          onBills={(uhid) => { setBillFocus({ uhid }); setTab('billing'); }} />
      </div>
      <div role="tabpanel" aria-label="admissions" className={tab === 'admissions' ? 'flex min-h-0 flex-1' : 'hidden'}>
        <AdmissionsPanel admitFor={admitFor} onAdmitDone={() => setAdmitFor(null)} canAdmit={has('RECEPTIONIST', 'ADMINISTRATOR')}
          canDischarge={has('ADMINISTRATOR')} onError={report} onDone={done}
          onOpenBill={(admissionId) => { setBillFocus({ admissionId }); setTab('billing'); }} />
      </div>
      <div role="tabpanel" aria-label="billing" className={tab === 'billing' ? 'flex min-h-0 flex-1' : 'hidden'}>
        <BillingPanel focus={billFocus} canIpdBill={has('BILLING_CLERK', 'ADMINISTRATOR')} canCollect={has('RECEPTIONIST', 'BILLING_CLERK', 'ADMINISTRATOR')}
          onError={report} onDone={done} />
      </div>
    </div>
  );
}
