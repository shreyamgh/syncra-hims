import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { endpoints } from '../../api/endpoints';
import {
  type Admission, type AdmissionType, type AvailableBed, type BoardEntry, type DischargeOutcome, type Doctor, type Patient,
  type TariffPlan, type WardCensus, frontOffice,
} from '../../api/frontoffice';
import type { Department } from '../../api/types';
import { BED_TONE, byWard, daysAdmitted } from '../../emr/frontoffice';
import { Badge, label, when } from '../ui';

interface Props {
  /** Patient chosen on the Patients tab to admit. */
  admitFor: Patient | null;
  onAdmitDone(): void;
  canAdmit: boolean;
  canDischarge: boolean;
  onError(error: unknown): void;
  onDone(text: string): void;
  onOpenBill(admissionId: string): void;
}

const ADMISSION_TYPES: AdmissionType[] = ['ELECTIVE', 'EMERGENCY', 'MATERNITY', 'DAYCARE', 'TRANSFER_IN'];
const OUTCOMES: DischargeOutcome[] = ['DISCHARGED', 'LAMA', 'TRANSFERRED_OUT', 'ABSCONDED', 'EXPIRED'];

/** The 50-bed board: census by ward, every bed by status, and admit / transfer / discharge. */
export function AdmissionsPanel({ admitFor, onAdmitDone, canAdmit, canDischarge, onError, onDone, onOpenBill }: Props) {
  const [wards, setWards] = useState<WardCensus[]>([]);
  const [board, setBoard] = useState<BoardEntry[] | null>(null);
  const [admissions, setAdmissions] = useState<Admission[]>([]);
  const [selected, setSelected] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [w, b, a] = await Promise.all([frontOffice.wards(), frontOffice.board(), frontOffice.admissions()]);
      setWards(w);
      setBoard(b);
      setAdmissions(a);
    } catch (error) {
      onError(error);
    }
  }, [onError]);

  useEffect(() => {
    void load();
  }, [load]);

  const admission = admissions.find((a) => a.admissionId === selected) ?? null;
  const total = wards.reduce((n, w) => n + w.total, 0);
  const free = wards.reduce((n, w) => n + w.available, 0);

  return (
    <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(0,30rem)]">
      <section aria-label="Bed board" className="min-h-0 overflow-y-auto border-r border-slate-200 bg-white p-2">
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <h2 className="text-[15px] font-semibold">Beds</h2>
          <span className="text-[12px] text-slate-600">{free} of {total} available</span>
          <div className="ml-auto flex flex-wrap gap-1 text-[11px]">
            {(['AVAILABLE', 'OCCUPIED', 'RESERVED', 'CLEANING', 'MAINTENANCE'] as const).map((s) => (
              <span key={s} className={`rounded border px-1.5 ${BED_TONE[s]}`}>{label(s)}</span>
            ))}
          </div>
          <button type="button" className="btn-ghost h-6" onClick={() => void load()}>Refresh</button>
        </div>
        <div className="mb-3 grid grid-cols-[repeat(auto-fill,minmax(11rem,1fr))] gap-1">
          {wards.map((w) => (
            <div key={w.wardCode} className="rounded border border-slate-200 px-2 py-1 text-[12px]">
              <div className="font-medium">{w.wardName}</div>
              <div className="text-slate-600 tabular-nums">{w.available} free · {w.occupied} occupied{w.cleaning ? ` · ${w.cleaning} cleaning` : ''}</div>
            </div>
          ))}
        </div>
        {board === null && <p className="text-slate-400">Loading…</p>}
        {board && byWard(board).map((ward) => (
          <section key={ward.wardCode} aria-label={ward.wardName} className="mb-3">
            <h3 className="section-title mb-1">{ward.wardName}</h3>
            <div className="grid grid-cols-[repeat(auto-fill,minmax(8.5rem,1fr))] gap-1">
              {ward.beds.map((bed) => {
                const o = bed.occupant;
                return (
                  <button key={bed.bedCode} type="button" aria-label={`Bed ${bed.bedCode}${o ? `: ${o.patientName}` : `: ${label(bed.bedStatus)}`}`}
                    disabled={!o} onClick={() => o && setSelected(o.admissionId)}
                    className={`rounded border px-1.5 py-1 text-left text-[11px] ${BED_TONE[bed.bedStatus]} ${o && selected === o.admissionId ? 'ring-2 ring-indigo-500' : ''} disabled:cursor-default`}>
                    <div className="flex justify-between font-mono font-semibold"><span>{bed.bedCode}</span><span className="font-sans text-[10px] font-normal">{label(bed.bedCategory)}</span></div>
                    {o ? (
                      <>
                        <div className="truncate font-medium">{o.patientName}</div>
                        <div className="text-slate-500">{o.ageYears ?? '?'} / {o.gender} · day {daysAdmitted(o.admittedAt)}</div>
                      </>
                    ) : <div className="text-slate-500">{label(bed.bedStatus)}</div>}
                  </button>
                );
              })}
            </div>
          </section>
        ))}
      </section>
      <aside aria-label="Admission" className="min-h-0 overflow-y-auto bg-slate-50">
        {admitFor && canAdmit ? (
          <AdmitForm patient={admitFor} onError={onError} onCancel={onAdmitDone}
            onAdmitted={async (a) => {
              onDone(`Admitted ${a.patientName}: ${a.admissionNumber}, bed ${a.currentBed?.bedCode ?? 'to be assigned'}.`);
              onAdmitDone();
              await load();
              setSelected(a.admissionId);
            }} />
        ) : admission ? (
          <AdmissionCard key={admission.admissionId} admission={admission} canDischarge={canDischarge} canMove={canAdmit}
            onError={onError} onOpenBill={() => onOpenBill(admission.admissionId)}
            onChanged={async (text) => { onDone(text); await load(); }} />
        ) : (
          <p className="m-6 text-center text-slate-400">
            Choose an occupied bed to see the admission{canAdmit ? ', or find the patient on the Patients tab to admit them' : ''}.
          </p>
        )}
      </aside>
    </div>
  );
}

function AdmitForm({ patient, onAdmitted, onCancel, onError }: {
  patient: Patient;
  onAdmitted(admission: Admission): Promise<void>;
  onCancel(): void;
  onError(error: unknown): void;
}) {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [beds, setBeds] = useState<AvailableBed[]>([]);
  const [plans, setPlans] = useState<TariffPlan[]>([]);
  const [d, setD] = useState({
    departmentCode: '', admittingDoctorId: '', admissionType: 'ELECTIVE' as AdmissionType, bedCode: '', tariffPlanCode: '',
    provisionalDiagnosis: '', attendantName: '', attendantRelation: '', attendantMobile: '', medicoLegal: false,
  });
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    endpoints.departments().then(setDepartments, onError);
    frontOffice.availableBeds(patient.gender).then(setBeds, onError);
    frontOffice.tariffs().then((p) => {
      setPlans(p);
      setD((cur) => ({ ...cur, tariffPlanCode: cur.tariffPlanCode || (p.find((x) => x.isDefault)?.code ?? '') }));
    }, onError);
  }, [patient.gender, onError]);
  useEffect(() => {
    setDoctors([]);
    if (d.departmentCode) frontOffice.doctors(d.departmentCode).then(setDoctors, () => setDoctors([]));
  }, [d.departmentCode]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found: string[] = [];
    if (!d.departmentCode) found.push('Choose the department');
    if (!d.admittingDoctorId) found.push('Choose the admitting doctor');
    if (d.attendantMobile && !/^[6-9]\d{9}$/.test(d.attendantMobile)) found.push("The attendant's mobile must be 10 digits starting with 6 to 9");
    setProblems(found);
    if (found.length > 0) return;
    setBusy(true);
    try {
      await onAdmitted(await frontOffice.admit({
        uhid: patient.uhid, departmentCode: d.departmentCode, admittingDoctorId: d.admittingDoctorId, admissionType: d.admissionType,
        bedCode: d.bedCode || undefined, tariffPlanCode: d.tariffPlanCode || undefined,
        provisionalDiagnosis: d.provisionalDiagnosis.trim() || undefined, medicoLegal: d.medicoLegal || undefined,
        attendant: d.attendantName.trim() ? { name: d.attendantName.trim(), relation: d.attendantRelation.trim() || undefined, mobile: d.attendantMobile || undefined } : undefined,
      }));
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  const set = (patch: Partial<typeof d>) => setD((cur) => ({ ...cur, ...patch }));
  return (
    <form onSubmit={(e) => void submit(e)} aria-label="Admit a patient" className="space-y-2 p-3">
      <h2 className="text-[15px] font-semibold">Admit {patient.fullName}</h2>
      <p className="text-[12px] text-slate-600"><span className="font-mono">{patient.uhid}</span> · {patient.ageYears ?? '?'} y / {patient.gender}</p>
      <div className="grid grid-cols-2 gap-2">
        <label className="text-[11px] text-slate-500">Department
          <select className="field" value={d.departmentCode} disabled={busy} onChange={(e) => set({ departmentCode: e.target.value, admittingDoctorId: '' })}>
            <option value="">Choose…</option>{departments.map((x) => <option key={x.code} value={x.code}>{x.name}</option>)}
          </select>
        </label>
        <label className="text-[11px] text-slate-500">Admitting doctor
          <select className="field" value={d.admittingDoctorId} disabled={busy || !d.departmentCode} onChange={(e) => set({ admittingDoctorId: e.target.value })}>
            <option value="">Choose…</option>{doctors.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>
        </label>
        <label className="text-[11px] text-slate-500">Admission type
          <select className="field" value={d.admissionType} disabled={busy} onChange={(e) => set({ admissionType: e.target.value as AdmissionType })}>
            {ADMISSION_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
          </select>
        </label>
        <label className="text-[11px] text-slate-500">Bed ({beds.length} suitable free)
          <select className="field" value={d.bedCode} disabled={busy} onChange={(e) => set({ bedCode: e.target.value })}>
            <option value="">Assign later</option>
            {beds.map((b) => (
              <option key={b.bedCode} value={b.bedCode}>{b.bedCode} · {b.wardName} · {label(b.bedCategory)}{b.hasOxygen ? ' · O₂' : ''}{b.hasVentilator ? ' · ventilator' : ''}</option>
            ))}
          </select>
        </label>
        <label className="text-[11px] text-slate-500">Tariff plan
          <select className="field" value={d.tariffPlanCode} disabled={busy} onChange={(e) => set({ tariffPlanCode: e.target.value })}>
            {plans.map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}
          </select>
        </label>
        <label className="flex items-end gap-1 text-[12px]">
          <input type="checkbox" checked={d.medicoLegal} disabled={busy} onChange={(e) => set({ medicoLegal: e.target.checked })} /> Medico-legal case
        </label>
      </div>
      <label className="block text-[11px] text-slate-500">Provisional diagnosis
        <input className="field" maxLength={500} value={d.provisionalDiagnosis} disabled={busy} onChange={(e) => set({ provisionalDiagnosis: e.target.value })} />
      </label>
      <div className="grid grid-cols-[1fr_7rem_8rem] gap-2">
        <label className="text-[11px] text-slate-500">Attendant
          <input className="field" maxLength={150} value={d.attendantName} disabled={busy} onChange={(e) => set({ attendantName: e.target.value })} />
        </label>
        <label className="text-[11px] text-slate-500">Relation
          <input className="field" maxLength={30} value={d.attendantRelation} disabled={busy} onChange={(e) => set({ attendantRelation: e.target.value })} />
        </label>
        <label className="text-[11px] text-slate-500">Attendant mobile
          <input className="field" inputMode="numeric" maxLength={10} value={d.attendantMobile} disabled={busy} onChange={(e) => set({ attendantMobile: e.target.value })} />
        </label>
      </div>
      {problems.length > 0 && <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
      <div className="flex justify-end gap-2">
        <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={busy}>Admit</button>
      </div>
    </form>
  );
}

function AdmissionCard({ admission: a, canDischarge, canMove, onChanged, onError, onOpenBill }: {
  admission: Admission;
  canDischarge: boolean;
  canMove: boolean;
  onChanged(text: string): Promise<void>;
  onError(error: unknown): void;
  onOpenBill(): void;
}) {
  const [beds, setBeds] = useState<AvailableBed[] | null>(null);
  const [move, setMove] = useState({ bedCode: '', reason: 'TRANSFER' as 'TRANSFER' | 'UPGRADE' | 'DOWNGRADE' });
  const [outcome, setOutcome] = useState<DischargeOutcome>('DISCHARGED');
  const [busy, setBusy] = useState(false);

  const run = async (action: () => Promise<unknown>, text: string) => {
    setBusy(true);
    try {
      await action();
      await onChanged(text);
      setBeds(null);
    } catch (error) {
      onError(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-3 p-3">
      <section aria-label="Admission details" className="rounded border border-slate-200 bg-white p-2 text-[12px]">
        <div className="flex flex-wrap items-baseline gap-2">
          <h2 className="text-[15px] font-semibold">{a.patientName}</h2>
          <span className="font-mono text-slate-500">{a.uhid}</span>
          {a.dischargeInitiatedAt && <Badge tone="amber">Discharge initiated</Badge>}
        </div>
        <p>{a.ageYears ?? '?'} y / {a.gender} · {a.admissionNumber} · {label(a.admissionType)}</p>
        <p>{a.departmentName} · {a.admittingDoctor.name}</p>
        <p>Bed <b className="font-mono">{a.currentBed?.bedCode ?? 'not assigned'}</b>{a.currentBed ? ` (${a.currentBed.wardName})` : ''} · admitted {when(a.admittedAt)} · day {daysAdmitted(a.admittedAt)}</p>
        {a.provisionalDiagnosis && <p className="text-slate-600">Diagnosis: {a.provisionalDiagnosis}</p>}
        <button type="button" className="btn-secondary mt-2 h-6" onClick={onOpenBill}>Open the bill</button>
      </section>

      {canMove && !a.dischargeInitiatedAt && (
        <section aria-label="Move bed" className="rounded border border-slate-200 bg-white p-2">
          <h3 className="section-title mb-1">Transfer or change bed</h3>
          {beds === null ? (
            <button type="button" className="btn-ghost h-6" disabled={busy}
              onClick={() => void frontOffice.availableBeds(a.gender).then(setBeds, onError)}>Show free beds</button>
          ) : (
            <div className="flex flex-wrap items-end gap-2">
              <label className="text-[11px] text-slate-500">To bed
                <select className="field w-60" value={move.bedCode} disabled={busy} onChange={(e) => setMove({ ...move, bedCode: e.target.value })}>
                  <option value="">Choose…</option>
                  {beds.map((b) => <option key={b.bedCode} value={b.bedCode}>{b.bedCode} · {b.wardName} · {label(b.bedCategory)}</option>)}
                </select>
              </label>
              <label className="text-[11px] text-slate-500">Reason
                <select className="field w-32" value={move.reason} disabled={busy} onChange={(e) => setMove({ ...move, reason: e.target.value as typeof move.reason })}>
                  <option value="TRANSFER">Transfer</option><option value="UPGRADE">Upgrade</option><option value="DOWNGRADE">Downgrade</option>
                </select>
              </label>
              <button type="button" className="btn-primary h-7" disabled={busy || !move.bedCode}
                onClick={() => void run(() => frontOffice.moveBed(a.admissionId, move.bedCode, move.reason), `${a.patientName} moved to bed ${move.bedCode}.`)}>
                Move
              </button>
            </div>
          )}
        </section>
      )}

      {canDischarge && (
        <section aria-label="Discharge" className="rounded border border-slate-200 bg-white p-2">
          <h3 className="section-title mb-1">Discharge</h3>
          {!a.dischargeInitiatedAt ? (
            <button type="button" className="btn-secondary h-6" disabled={busy}
              onClick={() => void run(() => frontOffice.initiateDischarge(a.admissionId), `Discharge initiated for ${a.patientName}: the final bill can be prepared.`)}>
              Initiate discharge
            </button>
          ) : (
            <div className="flex items-end gap-2">
              <label className="text-[11px] text-slate-500">Outcome
                <select className="field w-44" value={outcome} disabled={busy} onChange={(e) => setOutcome(e.target.value as DischargeOutcome)}>
                  {OUTCOMES.map((o) => <option key={o} value={o}>{label(o)}</option>)}
                </select>
              </label>
              <button type="button" className="btn-primary h-7" disabled={busy}
                onClick={() => void run(() => frontOffice.discharge(a.admissionId, outcome), `${a.patientName} discharged; bed ${a.currentBed?.bedCode ?? ''} goes for cleaning.`)}>
                Discharge
              </button>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
