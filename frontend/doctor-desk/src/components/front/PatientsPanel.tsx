import { type FormEvent, useEffect, useState } from 'react';
import { endpoints } from '../../api/endpoints';
import {
  type Bill, type Doctor, type Patient, type PatientSummary, type PaymentMode, type VisitType,
  frontOffice, printBill, rupees,
} from '../../api/frontoffice';
import { ApiError } from '../../api/http';
import type { Department, Gender, Priority, Visit } from '../../api/types';
import { type RegistrationDraft, emptyRegistration, registrationProblems, toPatientDetails } from '../../emr/frontoffice';
import { Badge, label } from '../ui';

interface Props {
  canRegister: boolean;
  canCollect: boolean;
  onError(error: unknown): void;
  onDone(text: string): void;
  onAdmit(patient: Patient): void;
  onBills(uhid: string): void;
}

export const PAYMENT_MODES: PaymentMode[] = ['CASH', 'UPI', 'CARD', 'NET_BANKING', 'CHEQUE'];
const VISIT_TYPES: VisitType[] = ['NEW', 'FOLLOW_UP', 'REFERRAL', 'EMERGENCY'];
const PRIORITIES: Priority[] = ['EMERGENCY', 'SENIOR_CITIZEN', 'PREGNANT', 'DIFFERENTLY_ABLED', 'CHILD_UNDER_5', 'NORMAL'];

/** Registration and the OPD counter: find or register the patient, issue a token, collect the fee. */
export function PatientsPanel({ canRegister, canCollect, onError, onDone, onAdmit, onBills }: Props) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<PatientSummary[] | null>(null);
  const [patient, setPatient] = useState<Patient | null>(null);
  const [registering, setRegistering] = useState(false);

  useEffect(() => {
    if (query.trim().length < 3) {
      setResults(null);
      return undefined;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      frontOffice.searchPatients(query.trim(), controller.signal).then(setResults, () => setResults([]));
    }, 300);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [query]);

  const open = async (uhid: string) => {
    try {
      setPatient(await frontOffice.patient(uhid));
      setRegistering(false);
    } catch (error) {
      onError(error);
    }
  };

  return (
    <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(0,34rem)]">
      <section aria-label="Find a patient" className="flex min-h-0 flex-col border-r border-slate-200 bg-white">
        <div className="flex items-center gap-2 border-b border-slate-200 p-2">
          <input aria-label="Search patients" autoFocus className="field" placeholder="Name, UHID or mobile (3 characters or more)"
            value={query} onChange={(e) => setQuery(e.target.value)} />
          {canRegister && (
            <button type="button" className="btn-primary" onClick={() => { setRegistering(true); setPatient(null); }}>Register new patient</button>
          )}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          <table className="w-full border-collapse" aria-label="Patients found">
            <thead className="sticky top-0 bg-white">
              <tr className="border-b border-slate-200">
                <th className="th">Patient</th><th className="th w-40">UHID</th><th className="th w-20">Age / sex</th>
                <th className="th w-28">Mobile</th><th className="th w-16">ABHA</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {results === null && <tr><td colSpan={5} className="td py-2 text-slate-400">Search by name, UHID or mobile number.</td></tr>}
              {results?.length === 0 && <tr><td colSpan={5} className="td py-2 text-slate-500">No patient found. Register them as new.</td></tr>}
              {results?.map((r) => (
                <tr key={r.uhid} className={`cursor-pointer ${patient?.uhid === r.uhid ? 'bg-indigo-50' : 'hover:bg-slate-50'}`}
                  onClick={() => void open(r.uhid)}>
                  <td className="td font-medium">{r.fullName}{r.deceased && <Badge tone="slate">Deceased</Badge>}</td>
                  <td className="td font-mono text-[12px]">{r.uhid}</td>
                  <td className="td text-[12px]">{r.ageYears ?? '?'} / {r.gender}</td>
                  <td className="td font-mono text-[12px] text-slate-500">{r.maskedMobile}</td>
                  <td className="td">{r.abhaLinked && <Badge tone="emerald">Linked</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <aside aria-label="Patient" className="min-h-0 overflow-y-auto bg-slate-50">
        {registering ? (
          <RegistrationForm onCancel={() => setRegistering(false)} onError={onError}
            onRegistered={(p, existing) => {
              setPatient(p);
              setRegistering(false);
              onDone(existing ? `Using the existing record ${p.uhid}.` : `Registered ${p.fullName}: UHID ${p.uhid}.`);
            }} />
        ) : patient ? (
          <PatientCard key={patient.uhid} patient={patient} canCollect={canCollect} onError={onError} onDone={onDone}
            onAdmit={() => onAdmit(patient)} onBills={() => onBills(patient.uhid)} />
        ) : (
          <p className="m-6 text-center text-slate-400">Find a patient, or register a new one.</p>
        )}
      </aside>
    </div>
  );
}

function RegistrationForm({ onRegistered, onCancel, onError }: {
  onRegistered(patient: Patient, existing: boolean): void;
  onCancel(): void;
  onError(error: unknown): void;
}) {
  const [d, setD] = useState<RegistrationDraft>(emptyRegistration);
  const [problems, setProblems] = useState<string[]>([]);
  const [candidates, setCandidates] = useState<PatientSummary[] | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<RegistrationDraft>) => setD((current) => ({ ...current, ...patch }));

  const register = async (confirmNotDuplicate: boolean) => {
    setBusy(true);
    try {
      onRegistered(await frontOffice.register(toPatientDetails(d), confirmNotDuplicate), false);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'POSSIBLE_DUPLICATE') {
        setCandidates((error.problem?.candidates as PatientSummary[] | undefined) ?? []);
      } else {
        onError(error);
      }
    } finally {
      setBusy(false);
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = registrationProblems(d);
    setProblems(found);
    if (found.length === 0) void register(false);
  };

  const text = (key: keyof RegistrationDraft, labelText: string, extra: Record<string, unknown> = {}) => (
    <label className="text-[11px] text-slate-500">{labelText}
      <input className="field" value={d[key] as string} disabled={busy} onChange={(e) => set({ [key]: e.target.value })} {...extra} />
    </label>
  );

  return (
    <form onSubmit={submit} aria-label="Register a patient" className="space-y-2 p-3">
      <h2 className="text-[15px] font-semibold">Register a new patient</h2>
      <div className="grid grid-cols-[5.5rem_1fr_1fr_1fr] gap-2">
        <label className="text-[11px] text-slate-500">Title
          <select className="field" value={d.title} disabled={busy} onChange={(e) => set({ title: e.target.value })}>
            <option value="">—</option>
            {['Mr', 'Mrs', 'Ms', 'Miss', 'Master', 'Baby', 'Shri', 'Smt', 'Kumari', 'Dr'].map((t) => <option key={t}>{t}</option>)}
          </select>
        </label>
        {text('firstName', 'First name', { maxLength: 60 })}
        {text('middleName', 'Middle name', { maxLength: 60 })}
        {text('lastName', 'Last name', { maxLength: 60 })}
      </div>
      <div className="grid grid-cols-[8rem_9rem_1fr_9rem] items-end gap-2">
        <label className="text-[11px] text-slate-500">Sex
          <select className="field" value={d.gender} disabled={busy} onChange={(e) => set({ gender: e.target.value as Gender })}>
            <option value="">—</option><option value="M">Male</option><option value="F">Female</option><option value="O">Other</option>
          </select>
        </label>
        <label className="text-[11px] text-slate-500">Age known as
          <select className="field" value={d.ageBy} disabled={busy} onChange={(e) => set({ ageBy: e.target.value as 'DOB' | 'AGE' })}>
            <option value="DOB">Date of birth</option><option value="AGE">Age in years</option>
          </select>
        </label>
        {d.ageBy === 'DOB'
          ? text('dateOfBirth', 'Date of birth', { type: 'date' })
          : text('ageYears', 'Age (years)', { inputMode: 'numeric', maxLength: 3 })}
        {text('mobile', 'Mobile', { inputMode: 'numeric', maxLength: 10 })}
      </div>
      <div className="grid grid-cols-[7rem_1fr] gap-2">
        <label className="text-[11px] text-slate-500">Relation
          <select className="field" value={d.relationType} disabled={busy} onChange={(e) => set({ relationType: e.target.value })}>
            <option value="">—</option>{['S/O', 'D/O', 'W/O', 'H/O', 'M/O', 'F/O', 'C/O'].map((r) => <option key={r}>{r}</option>)}
          </select>
        </label>
        {text('relationName', 'Name of father / husband / guardian', { maxLength: 150 })}
      </div>
      {text('line', 'Address', { maxLength: 250 })}
      <div className="grid grid-cols-4 gap-2">
        {text('cityVillage', 'City / village', { maxLength: 100 })}
        {text('district', 'District', { maxLength: 100 })}
        {text('state', 'State', { maxLength: 60 })}
        {text('pincode', 'PIN code', { inputMode: 'numeric', maxLength: 6 })}
      </div>
      {text('abhaNumber', 'ABHA number (optional, 14 digits)', { inputMode: 'numeric', maxLength: 17 })}
      {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
      {candidates && (
        <section aria-label="Possible duplicates" className="rounded border border-amber-300 bg-amber-50 p-2">
          <p className="text-[12px] font-medium text-amber-900">A similar patient is already registered. Use the existing record if it is the same person.</p>
          <ul className="mt-1 divide-y divide-amber-200">
            {candidates.map((c) => (
              <li key={c.uhid} className="flex items-center gap-2 py-1 text-[12px]">
                <b>{c.fullName}</b> <span className="font-mono">{c.uhid}</span> · {c.ageYears ?? '?'} / {c.gender} · {c.maskedMobile}
                <button type="button" className="btn-secondary ml-auto h-6" disabled={busy}
                  onClick={() => void frontOffice.patient(c.uhid).then((p) => onRegistered(p, true), onError)}>Use this record</button>
              </li>
            ))}
          </ul>
          <button type="button" className="btn-ghost mt-1 h-6" disabled={busy} onClick={() => void register(true)}>
            It is a different person: register as new
          </button>
        </section>
      )}
      <div className="flex justify-end gap-2">
        <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={busy}>Register</button>
      </div>
    </form>
  );
}

function PatientCard({ patient, canCollect, onError, onDone, onAdmit, onBills }: {
  patient: Patient;
  canCollect: boolean;
  onError(error: unknown): void;
  onDone(text: string): void;
  onAdmit(): void;
  onBills(): void;
}) {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [visitDraft, setVisitDraft] = useState({ departmentCode: '', doctorId: '', visitType: 'NEW' as VisitType, priority: '' });
  const [visit, setVisit] = useState<Visit | null>(null);
  const [bill, setBill] = useState<Bill | null>(null);
  const [payment, setPayment] = useState<{ mode: PaymentMode; reference: string }>({ mode: 'CASH', reference: '' });
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    endpoints.departments().then(setDepartments, onError);
  }, [onError]);
  useEffect(() => {
    setDoctors([]);
    if (visitDraft.departmentCode) frontOffice.doctors(visitDraft.departmentCode).then(setDoctors, () => setDoctors([]));
  }, [visitDraft.departmentCode]);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    try {
      await action();
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  const issueToken = () => run(async () => {
    const created = await frontOffice.newVisit({
      uhid: patient.uhid, departmentCode: visitDraft.departmentCode, doctorId: visitDraft.doctorId || undefined,
      visitType: visitDraft.visitType, priority: (visitDraft.priority || undefined) as Priority | undefined,
    });
    setVisit(created);
    setBill(null);
    onDone(`Token #${created.tokenNumber} issued for ${created.departmentName}.`);
  });

  const issueSlip = () => run(async () => {
    setBill(await frontOffice.opdSlip(visit!.visitId));
  });

  const collect = () => run(async () => {
    await frontOffice.pay(bill!.billId, 'SETTLEMENT', {
      mode: payment.mode, amount: bill!.totals.balanceDue, reference: payment.reference.trim() || undefined,
    });
    const paid = await frontOffice.bill(bill!.billId);
    setBill(paid);
    onDone(`Received ${rupees(bill!.totals.balanceDue)} for ${paid.billNumber ?? 'the OPD slip'}.`);
  });

  return (
    <div className="space-y-3 p-3">
      <section aria-label="Patient details" className="rounded border border-slate-200 bg-white p-2">
        <div className="flex flex-wrap items-baseline gap-2">
          <h2 className="text-[15px] font-semibold">{patient.fullName}</h2>
          <span className="font-mono text-[12px] text-slate-500">{patient.uhid}</span>
          {patient.abhaNumber && <Badge tone={patient.abhaVerified ? 'emerald' : 'slate'}>ABHA {patient.abhaNumber}</Badge>}
        </div>
        <p className="text-[12px] text-slate-600">
          {patient.ageYears ?? '?'} y{patient.dobEstimated ? ' (estimated)' : ''} / {patient.gender}
          {patient.mobile && <> · {patient.mobile}</>}
          {patient.relationType && <> · {patient.relationType} {patient.relationName}</>}
        </p>
        {patient.address && <p className="text-[12px] text-slate-500">{[patient.address.line, patient.address.cityVillage, patient.address.district, patient.address.state, patient.address.pincode].filter(Boolean).join(', ')}</p>}
        <div className="mt-2 flex gap-2">
          <button type="button" className="btn-secondary h-6" onClick={onAdmit}>Admit…</button>
          <button type="button" className="btn-ghost h-6" onClick={onBills}>Bills</button>
        </div>
      </section>

      <section aria-label="OPD visit" className="rounded border border-slate-200 bg-white p-2">
        <h3 className="section-title mb-1">OPD visit</h3>
        {!visit ? (
          <div className="grid grid-cols-2 gap-2">
            <label className="text-[11px] text-slate-500">Department
              <select className="field" value={visitDraft.departmentCode} disabled={busy}
                onChange={(e) => setVisitDraft({ ...visitDraft, departmentCode: e.target.value, doctorId: '' })}>
                <option value="">Choose…</option>
                {departments.map((dep) => <option key={dep.code} value={dep.code}>{dep.name}</option>)}
              </select>
            </label>
            <label className="text-[11px] text-slate-500">Doctor
              <select className="field" value={visitDraft.doctorId} disabled={busy || !visitDraft.departmentCode}
                onChange={(e) => setVisitDraft({ ...visitDraft, doctorId: e.target.value })}>
                <option value="">Any doctor in the department</option>
                {doctors.map((doc) => <option key={doc.id} value={doc.id}>{doc.name}</option>)}
              </select>
            </label>
            <label className="text-[11px] text-slate-500">Visit type
              <select className="field" value={visitDraft.visitType} disabled={busy}
                onChange={(e) => setVisitDraft({ ...visitDraft, visitType: e.target.value as VisitType })}>
                {VISIT_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
              </select>
            </label>
            <label className="text-[11px] text-slate-500">Priority
              <select className="field" value={visitDraft.priority} disabled={busy}
                onChange={(e) => setVisitDraft({ ...visitDraft, priority: e.target.value })}>
                <option value="">Automatic (age, emergency)</option>
                {PRIORITIES.map((p) => <option key={p} value={p}>{label(p)}</option>)}
              </select>
            </label>
            <button type="button" className="btn-primary col-span-2 justify-center" disabled={busy || !visitDraft.departmentCode}
              onClick={() => void issueToken()}>Issue token</button>
          </div>
        ) : (
          <div className="space-y-2">
            <p aria-label="Token issued" className="flex items-baseline gap-2">
              <span className="font-mono text-2xl font-bold text-indigo-700">#{visit.tokenNumber}</span>
              <span>{visit.departmentName}{visit.doctor ? ` · ${visit.doctor.name}` : ''}</span>
              {visit.priority !== 'NORMAL' && <Badge tone="amber">{label(visit.priority)}</Badge>}
            </p>
            {canCollect && !bill && (
              <button type="button" className="btn-secondary" disabled={busy} onClick={() => void issueSlip()}>Issue OPD slip</button>
            )}
            {bill && (
              <div aria-label="OPD slip" className="rounded border border-slate-200 p-2">
                <table className="w-full border-collapse text-[12px]">
                  <tbody>
                    {bill.lines.map((l) => (
                      <tr key={l.lineId}><td className="td">{l.description}</td><td className="td text-right tabular-nums">{rupees(l.lineTotal)}</td></tr>
                    ))}
                    <tr className="border-t border-slate-200 font-semibold">
                      <td className="td">Payable</td><td className="td text-right tabular-nums">{rupees(bill.totals.payable)}</td>
                    </tr>
                  </tbody>
                </table>
                {bill.totals.balanceDue > 0 ? (
                  <div className="mt-2 flex items-end gap-2">
                    <label className="text-[11px] text-slate-500">Mode
                      <select className="field w-32" value={payment.mode} disabled={busy}
                        onChange={(e) => setPayment({ ...payment, mode: e.target.value as PaymentMode })}>
                        {PAYMENT_MODES.map((m) => <option key={m} value={m}>{label(m)}</option>)}
                      </select>
                    </label>
                    {payment.mode !== 'CASH' && (
                      <label className="text-[11px] text-slate-500">Reference
                        <input className="field w-40" maxLength={64} value={payment.reference} disabled={busy}
                          onChange={(e) => setPayment({ ...payment, reference: e.target.value })} placeholder="UTR / transaction no." />
                      </label>
                    )}
                    <button type="button" className="btn-primary ml-auto" disabled={busy} onClick={() => void collect()}>
                      Receive {rupees(bill.totals.balanceDue)}
                    </button>
                  </div>
                ) : (
                  <div className="mt-2 flex items-center gap-2">
                    <Badge tone="emerald">Paid</Badge>
                    <span className="font-mono text-[12px]">{bill.billNumber}</span>
                    <button type="button" className="btn-secondary ml-auto h-6" onClick={() => printBill(bill.billId)}>Print</button>
                  </div>
                )}
              </div>
            )}
            <button type="button" className="btn-ghost h-6" onClick={() => { setVisit(null); setBill(null); }}>New visit</button>
          </div>
        )}
      </section>
    </div>
  );
}
