import { useCallback, useEffect, useRef, useState } from 'react';
import { type VisitAction, endpoints } from '../api/endpoints';
import { ApiError } from '../api/http';
import type { CriticalResult, Department, EncounterRecord, Me, Queue, Visit } from '../api/types';
import type { Session } from '../auth/session';
import type { DeskId } from '../emr/diagnostics';
import type { Extraction, Language } from '../dictation/protocol';
import { useDictation } from '../dictation/useDictation';
import {
  applyExtraction, type ConsultationForm, editRow, editVital, emptyForm, isDirty, toConsultationRequest, validate,
} from '../emr/form';
import { missingOverrides, useCdss } from '../emr/cdss';
import { orderCounts } from '../emr/workspace';
import { AllergiesPanel } from './AllergiesPanel';
import { CdssPanel } from './CdssPanel';
import { ComplaintsSection } from './ComplaintsSection';
import { DiagnosesSection } from './DiagnosesSection';
import { CriticalBanner } from './CriticalResults';
import { DeskHeader, type Notice } from './DeskHeader';
import { DictationBar } from './DictationBar';
import { NotesPanel } from './NotesPanel';
import { OrdersPanel } from './OrdersPanel';
import { PatientHeader } from './PatientHeader';
import { PrescriptionSection } from './PrescriptionSection';
import { QueuePanel } from './QueuePanel';
import { RecordedSummary } from './RecordedSummary';
import { Badge } from './ui';
import { VaccinationsPanel } from './VaccinationsPanel';
import { ExternalRecordsPanel } from './ExternalRecordsPanel';
import { VitalsSection } from './VitalsSection';

const QUEUE_REFRESH_MS = 15_000;
const DEPARTMENT_KEY = 'syncra.doctorDesk.department';


type Tab = 'consultation' | 'orders' | 'notes' | 'allergies' | 'vaccinations' | 'external';
const TABS: { id: Tab; label: string }[] = [
  { id: 'consultation', label: 'Consultation' },
  { id: 'orders', label: 'Orders & results' },
  { id: 'notes', label: 'Notes' },
  { id: 'allergies', label: 'Allergies' },
  { id: 'vaccinations', label: 'Vaccinations' },
  { id: 'external', label: 'Other hospitals' },
];

function storedDepartment(): string {
  try {
    return localStorage.getItem(DEPARTMENT_KEY) ?? '';
  } catch {
    return '';
  }
}

/**
 * The Doctor Desk: today's queue on the left, the selected patient's encounter
 * on the right. Dictation fills the vitals, complaints and prescription live;
 * the doctor reviews, edits and signs everything in one action.
 */
export function DoctorDesk({ session, onDeskChange }: { session: Session; onDeskChange?(desk: DeskId): void }) {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [department, setDepartment] = useState<string>(storedDepartment);
  const [queue, setQueue] = useState<Queue | null>(null);
  const [visit, setVisit] = useState<Visit | null>(null);
  const [record, setRecord] = useState<EncounterRecord | null>(null);
  const [form, setForm] = useState<ConsultationForm>(emptyForm);
  const [language, setLanguage] = useState<Language>('en-IN');
  const [warnings, setWarnings] = useState<string[]>([]);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [tab, setTab] = useState<Tab>('consultation');
  const [opened, setOpened] = useState<Set<Tab>>(() => new Set(['consultation']));
  const [noteDirty, setNoteDirty] = useState(false);
  const [critical, setCritical] = useState<CriticalResult[]>([]);
  const isDoctor = session.roles.includes('DOCTOR');
  const isClinical = isDoctor || session.roles.includes('NURSE');

  const lastSessionRef = useRef<string | null>(null);
  const dictation = useDictation({
    encounterId: visit?.encounterId ?? null,
    language,
    token: session.token,
    onExtraction: (extraction: Extraction, sessionId: string) => {
      lastSessionRef.current = sessionId;
      setWarnings(extraction.warnings);
      setForm((current) => applyExtraction(current, extraction, sessionId));
    },
    onSessionEnd: (record) =>
      setForm((current) => ({
        ...current,
        sessions: [...current.sessions.filter((s) => s.aiSessionId !== record.aiSessionId), record],
      })),
  });
  const cdss = useCdss(form, record);
  const dictating = dictation.status === 'connecting' || dictation.status === 'listening' || dictation.status === 'finishing';
  const closed = record ? record.status === 'FINISHED' || record.status === 'CANCELLED' : false;

  const report = useCallback((error: unknown) => {
    setNotice({ kind: 'error', text: error instanceof Error ? error.message : 'Something went wrong' });
  }, []);
  const done = useCallback((text: string) => setNotice({ kind: 'ok', text }), []);
  const unsaved = isDirty(form) || noteDirty;

  const openTab = useCallback((next: Tab) => {
    if (dictating) return;
    setTab(next);
    setOpened((current) => (current.has(next) ? current : new Set(current).add(next)));
  }, [dictating]);

  useEffect(() => {
    // Who the notes and orders are attributed to; without a staff record notes are read-only.
    endpoints.me().then(setMe, () => setMe(null));
  }, []);

  // The doctor's own critical results still waiting for acknowledgement (refreshed with the queue).
  const refreshCritical = useCallback(async () => {
    if (!isDoctor) return;
    try {
      setCritical(await endpoints.criticalResults({ acknowledged: false, orderedByMe: true }));
    } catch {
      // keep the last list; the next refresh tries again
    }
  }, [isDoctor]);

  const refreshQueue = useCallback(async () => {
    void refreshCritical();
    if (!department) return;
    try {
      setQueue(await endpoints.queue(department));
    } catch (error) {
      report(error);
    }
  }, [department, report, refreshCritical]);

  useEffect(() => {
    endpoints.departments().then((list) => {
      setDepartments(list);
      setDepartment((current) => (list.some((d) => d.code === current) ? current : list[0]?.code ?? ''));
    }, report);
  }, [report]);

  useEffect(() => {
    try {
      if (department) localStorage.setItem(DEPARTMENT_KEY, department);
    } catch {
      // per-browser convenience only
    }
    void refreshQueue();
    const timer = window.setInterval(() => document.visibilityState === 'visible' && void refreshQueue(), QUEUE_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [department, refreshQueue]);

  const loadRecord = useCallback(async (encounterId: string) => {
    try {
      setRecord(await endpoints.encounter(encounterId));
    } catch (error) {
      report(error);
    }
  }, [report]);

  const select = useCallback((next: Visit) => {
    if (dictating || next.visitId === visit?.visitId) return;
    if (unsaved && !window.confirm('Discard the unsigned notes for the current patient?')) return;
    setVisit(next);
    setRecord(null);
    setTab('consultation');
    setOpened(new Set(['consultation']));
    setNoteDirty(false);
    setForm(emptyForm());
    setWarnings([]);
    setProblems([]);
    setNotice(null);
    dictation.setTranscript('');
    lastSessionRef.current = null;
    void loadRecord(next.encounterId);
  }, [dictating, visit, unsaved, loadRecord, dictation]);

  const callNext = useCallback(async () => {
    if (!department || dictating) return;
    if (unsaved && !window.confirm('Discard the unsigned notes for the current patient?')) return;
    setBusy(true);
    try {
      const called = await endpoints.callNext(department);
      setForm(emptyForm());
      setVisit(null);
      select(called);
      await refreshQueue();
    } catch (error) {
      report(error instanceof ApiError && error.code === 'QUEUE_EMPTY' ? new Error('No patients are waiting.') : error);
    } finally {
      setBusy(false);
    }
  }, [department, dictating, unsaved, select, refreshQueue, report]);

  const visitAction = useCallback(async (action: VisitAction) => {
    if (!visit) return;
    setBusy(true);
    try {
      setVisit(await endpoints.visitAction(visit.visitId, action));
      await Promise.all([refreshQueue(), loadRecord(visit.encounterId)]);
    } catch (error) {
      report(error);
    } finally {
      setBusy(false);
    }
  }, [visit, refreshQueue, loadRecord, report]);

  const sign = useCallback(async (completeVisit: boolean) => {
    if (!visit || dictating || !isDoctor) return;
    const found = validate(form);
    const unexplained = missingOverrides(cdss.result?.alerts ?? [], form.cdssOverrides);
    if (unexplained.length > 0) {
      found.push(`Give a reason to override ${unexplained.length} serious drug-safety alert(s), or change the prescription`);
    }
    setProblems(found);
    if (found.length > 0) return;
    if (!isDirty(form) && !completeVisit) return;
    setBusy(true);
    try {
      const saved = await endpoints.signConsultation(visit.encounterId, toConsultationRequest(form, completeVisit));
      setRecord(saved);
      setForm(emptyForm());
      setWarnings([]);
      dictation.setTranscript('');
      setNotice({ kind: 'ok', text: completeVisit ? 'Signed. Prescription issued and visit completed.' : 'Signed. Prescription issued.' });
      await refreshQueue();
    } catch (error) {
      report(error instanceof ApiError && error.code === 'CDSS_OVERRIDE_REQUIRED'
        ? new Error('Drug-safety check at signing: a serious alert needs a reason. Review the alerts above.')
        : error);
    } finally {
      setBusy(false);
    }
  }, [visit, dictating, isDoctor, form, cdss.result, dictation, refreshQueue, report]);

  const reextract = useCallback(async () => {
    const sessionId = lastSessionRef.current;
    if (!sessionId || !dictation.transcript.trim()) return;
    try {
      const extraction = await endpoints.extract(dictation.transcript);
      setWarnings(extraction.warnings);
      setForm((current) => applyExtraction(current, extraction, sessionId));
    } catch (error) {
      report(error);
    }
  }, [dictation.transcript, report]);

  const toggleDictation = useCallback(() => {
    if (!visit || closed) return;
    if (dictation.status === 'listening') void dictation.stop();
    else if (!dictating) dictation.start();
  }, [visit, closed, dictation, dictating]);

  // Keyboard-first: Alt+M mic, Alt+N call next, Alt+1..6 tabs, Ctrl/Cmd+Enter sign & complete.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const tabIndex = event.altKey ? ['Digit1', 'Digit2', 'Digit3', 'Digit4', 'Digit5', 'Digit6'].indexOf(event.code) : -1;
      if (tabIndex >= 0 && visit) {
        event.preventDefault();
        openTab(TABS[tabIndex]!.id);
      } else if (event.altKey && event.code === 'KeyM') {
        if (tab !== 'consultation') return;
        event.preventDefault();
        toggleDictation();
      } else if (event.altKey && event.code === 'KeyN') {
        event.preventDefault();
        void callNext();
      } else if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        // Only the consultation signs this way: Ctrl+Enter in a note must never complete the visit.
        if (tab !== 'consultation') return;
        event.preventDefault();
        void sign(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [toggleDictation, callNext, sign, openTab, tab, visit]);

  const editable = Boolean(visit) && !closed && isDoctor;

  return (
    <div className="flex h-full flex-col">
      <DeskHeader session={session} desk="doctor" onDeskChange={onDeskChange} notice={notice} />
      <CriticalBanner results={critical} onError={report} onAcknowledged={async (text) => {
        done(text);
        await refreshCritical();
        if (visit) await loadRecord(visit.encounterId);
      }} />

      <div className="grid min-h-0 flex-1 grid-cols-[19rem_1fr]">
        <QueuePanel
          departments={departments}
          department={department}
          onDepartmentChange={setDepartment}
          queue={queue}
          selectedVisitId={visit?.visitId ?? null}
          onSelect={select}
          onCallNext={() => void callNext()}
          busy={busy}
          locked={dictating}
        />

        <main className="flex min-h-0 flex-col bg-white">
          {!visit ? (
            <div className="m-auto text-center text-slate-400">
              <p className="text-base">Select a patient from the queue</p>
              <p className="mt-1 text-[12px]">or press <kbd>Alt</kbd>+<kbd>N</kbd> to call the next token</p>
            </div>
          ) : (
            <>
              <PatientHeader visit={visit} record={record} onAction={(a) => void visitAction(a)} busy={busy} locked={dictating}
                onOpenAllergies={() => openTab('allergies')} />
              <WorkspaceTabs tab={tab} record={record} onOpen={openTab} locked={dictating} />
              {tab === 'consultation' && !closed && isDoctor && (
                <DictationBar
                  status={dictation.status}
                  transcript={dictation.transcript}
                  level={dictation.level}
                  error={dictation.error}
                  warnings={warnings}
                  language={language}
                  onLanguageChange={setLanguage}
                  onToggle={toggleDictation}
                  onTranscriptChange={dictation.setTranscript}
                  onReextract={() => void reextract()}
                  canReextract={Boolean(lastSessionRef.current) && dictation.transcript.trim() !== ''}
                  disabled={!editable}
                />
              )}
              <div id="tab-consultation" role="tabpanel" aria-labelledby="tab-button-consultation" hidden={tab !== 'consultation'}
                className="min-h-0 flex-1 divide-y divide-slate-100 overflow-y-auto">
                {record && <RecordedSummary record={record} />}
                {!closed && (
                  <>
                    <VitalsSection form={form} disabled={!editable} onChange={(key, value) => setForm((f) => editVital(f, key, value))} />
                    <ComplaintsSection
                      rows={form.complaints}
                      disabled={!editable}
                      onChange={(rows) => setForm((f) => ({ ...f, complaints: rows }))}
                      onEdit={(id, patch) => setForm((f) => ({ ...f, complaints: editRow(f.complaints, id, patch) }))}
                    />
                    <DiagnosesSection
                      rows={form.diagnoses}
                      disabled={!editable}
                      onChange={(rows) => setForm((f) => ({ ...f, diagnoses: rows }))}
                      onEdit={(id, patch) => setForm((f) => ({ ...f, diagnoses: editRow(f.diagnoses, id, patch) }))}
                    />
                    <PrescriptionSection
                      form={form}
                      disabled={!editable}
                      onRowsChange={(rows) => setForm((f) => ({ ...f, prescriptions: rows }))}
                      onEdit={(id, patch) => setForm((f) => ({ ...f, prescriptions: editRow(f.prescriptions, id, patch) }))}
                      onAdviceChange={(advice) => setForm((f) => ({ ...f, advice }))}
                      onFollowUpChange={(followUpDate) => setForm((f) => ({ ...f, followUpDate }))}
                    />
                    <CdssPanel
                      state={cdss}
                      overrides={form.cdssOverrides}
                      disabled={!editable}
                      onOverride={(ruleCode, reason) =>
                        setForm((f) => ({ ...f, cdssOverrides: { ...f.cdssOverrides, [ruleCode]: reason } }))}
                    />
                  </>
                )}
              </div>
              {(['orders', 'notes', 'allergies', 'vaccinations', 'external'] as const).map((id) => (
                <div key={id} id={`tab-${id}`} role="tabpanel" aria-labelledby={`tab-button-${id}`} hidden={tab !== id}
                  className="min-h-0 flex-1 overflow-y-auto">
                  {!record ? (
                    <p className="p-3 text-slate-400">Loading…</p>
                  ) : opened.has(id) && id === 'orders' ? (
                    <OrdersPanel key={record.encounterId} record={record} canOrder={isDoctor && record.status !== 'CANCELLED'}
                      onChanged={() => loadRecord(record.encounterId)} onError={report} onDone={done} />
                  ) : opened.has(id) && id === 'notes' ? (
                    <NotesPanel key={record.encounterId} record={record} me={me} canWrite={isClinical && record.status !== 'CANCELLED'}
                      onChanged={() => loadRecord(record.encounterId)} onError={report} onDone={done} onDirtyChange={setNoteDirty} />
                  ) : opened.has(id) && id === 'vaccinations' ? (
                    <VaccinationsPanel key={record.patient.uhid} record={record} canRecord={isClinical && record.status !== 'CANCELLED'}
                      onError={report} onDone={done} />
                  ) : opened.has(id) && id === 'external' ? (
                    <ExternalRecordsPanel key={record.patient.uhid} uhid={record.patient.uhid} isDoctor={isDoctor} onError={report} onDone={done} />
                  ) : opened.has(id) && id === 'allergies' ? (
                    <AllergiesPanel key={record.patient.uhid} uhid={record.patient.uhid} canEdit={isClinical} refreshKey={record}
                      onChanged={() => loadRecord(record.encounterId)} onError={report} onDone={done} />
                  ) : null}
                </div>
              ))}
              {tab === 'consultation' && !closed && isDoctor && (
                <footer className="flex items-center gap-2 border-t border-slate-200 bg-slate-50 px-3 py-2">
                  {problems.length > 0 ? (
                    <ul role="alert" className="text-[12px] text-red-600">{problems.map((p) => <li key={p}>{p}</li>)}</ul>
                  ) : (
                    <span className="text-[11px] text-slate-500">
                      Fields with a violet outline came from dictation. Review before signing.
                    </span>
                  )}
                  <div className="ml-auto flex gap-2">
                    <button type="button" className="btn-secondary" onClick={() => void sign(false)} disabled={busy || dictating || !isDirty(form)}>
                      Sign
                    </button>
                    <button type="button" className="btn-primary" onClick={() => void sign(true)} disabled={busy || dictating} title="Ctrl+Enter">
                      Sign &amp; complete visit <kbd className="border-indigo-400 bg-indigo-500 text-indigo-100">Ctrl+↵</kbd>
                    </button>
                  </div>
                </footer>
              )}
            </>
          )}
        </main>
      </div>
    </div>
  );
}

function WorkspaceTabs({ tab, record, onOpen, locked }: {
  tab: Tab;
  record: EncounterRecord | null;
  onOpen(tab: Tab): void;
  locked: boolean;
}) {
  const orders = orderCounts(record?.orders ?? []);
  const notes = (record?.notes ?? []).filter((n) => !n.enteredInError);
  const drafts = notes.filter((n) => !n.signedAt).length;
  const allergies = record?.allergies.length ?? 0;
  const badge = (id: Tab) => {
    switch (id) {
      case 'orders':
        return (
          <>
            {orders.open > 0 && <Badge tone="indigo" title="Open orders">{orders.open}</Badge>}
            {orders.critical > 0 && <Badge tone="red" title="Critical results">{orders.critical} critical</Badge>}
            {orders.critical === 0 && orders.abnormal > 0 && <Badge tone="amber" title="Results out of range">{orders.abnormal} abnormal</Badge>}
          </>
        );
      case 'notes':
        return (
          <>
            {notes.length > 0 && <Badge tone="slate">{notes.length}</Badge>}
            {drafts > 0 && <Badge tone="amber">{drafts} draft</Badge>}
          </>
        );
      case 'allergies':
        return allergies > 0 ? <Badge tone="red" title="Active allergies">{allergies}</Badge> : null;
      default:
        return null;
    }
  };
  return (
    <nav role="tablist" aria-label="Patient workspace" className="flex shrink-0 gap-1 overflow-x-auto border-b border-slate-200 bg-white px-2">
      {TABS.map((t, i) => (
        <button key={t.id} id={`tab-button-${t.id}`} type="button" role="tab" aria-selected={tab === t.id} aria-controls={`tab-${t.id}`}
          title={`${t.label} (Alt+${i + 1})`} aria-keyshortcuts={`Alt+${i + 1}`} disabled={locked && tab !== t.id} onClick={() => onOpen(t.id)}
          className={`-mb-px flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap border-b-2 px-2 text-[12px] font-medium disabled:opacity-50 ${
            tab === t.id ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-600 hover:text-slate-900'}`}>
          {t.label}
          {badge(t.id)}
        </button>
      ))}
    </nav>
  );
}
