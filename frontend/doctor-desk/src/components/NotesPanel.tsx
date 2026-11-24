import { useEffect, useMemo, useState } from 'react';
import { endpoints } from '../api/endpoints';
import { ApiError } from '../api/http';
import type { EncounterRecord, Me, NoteRecord, NoteType } from '../api/types';
import { NOTE_TYPE_LABEL, type NoteState, type NoteView, noteTypesFor, noteViews } from '../emr/workspace';
import { Badge, when } from './ui';

interface Props {
  record: EncounterRecord;
  me: Me | null;
  canWrite: boolean;
  onChanged(): Promise<void>;
  onError(error: unknown): void;
  onDone(text: string): void;
  /** True while the composer or a draft being edited holds text that is not saved. */
  onDirtyChange(dirty: boolean): void;
}

interface Composer {
  noteType: NoteType;
  content: string;
  amends?: NoteRecord;
}

const STATE_BADGE: Record<NoteState, { tone: 'amber' | 'emerald' | 'slate' | 'red'; text: string }> = {
  DRAFT: { tone: 'amber', text: 'Draft' },
  SIGNED: { tone: 'emerald', text: 'Signed' },
  AMENDED: { tone: 'slate', text: 'Amended' },
  IN_ERROR: { tone: 'red', text: 'Entered in error' },
};

/**
 * Free-text clinical notes for this encounter. A note is saved as a draft or signed; a signed
 * note is never edited, only corrected by a signed amendment, and the original stays readable.
 */
export function NotesPanel({ record, me, canWrite, onChanged, onError, onDone, onDirtyChange }: Props) {
  const types = noteTypesFor(me);
  const defaultType: NoteType = me?.doctor ? 'PROGRESS' : 'NURSING';
  const [composer, setComposer] = useState<Composer>({ noteType: defaultType, content: '' });
  const [editing, setEditing] = useState<{ id: string; content: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const views = useMemo(() => noteViews(record.notes ?? [], me), [record.notes, me]);
  const writable = canWrite && types.length > 0;
  // The staff record arrives after the first render: fall back to a type this author may write.
  const noteType = composer.amends || types.includes(composer.noteType) ? composer.noteType : defaultType;

  const dirty = composer.content.trim() !== '' || editing !== null;
  useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange]);

  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true);
    try {
      await action();
      onDone(done);
      await onChanged();
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.code === 'STALE_NOTE') {
        onError(new Error('This draft changed elsewhere; it has been reloaded. Re-apply your edit.'));
        setEditing(null);
        await onChanged();
      } else {
        onError(error);
      }
      return false;
    } finally {
      setBusy(false);
    }
  };

  const submit = async (sign: boolean) => {
    const content = composer.content.trim();
    if (!content) return;
    const { amends } = composer;
    const ok = await run(
      () => endpoints.addNote(record.encounterId, { noteType, content, sign: sign || Boolean(amends), amendsNoteId: amends?.id }),
      amends ? 'Amendment signed.' : sign ? `${NOTE_TYPE_LABEL[noteType]} signed.` : 'Draft saved.',
    );
    if (ok) setComposer({ noteType: defaultType, content: '' });
  };

  const saveDraft = async (note: NoteRecord, andSign: boolean) => {
    if (!editing) return;
    const content = editing.content.trim();
    if (!content) return;
    const ok = await run(async () => {
      if (content !== note.content) await endpoints.editNote(record.encounterId, note, content);
      if (andSign) await endpoints.signNote(record.encounterId, note.id);
    }, andSign ? 'Note signed.' : 'Draft saved.');
    if (ok) setEditing(null);
  };

  const amend = (note: NoteRecord) => {
    if (composer.content.trim() && !window.confirm('Replace the note you are writing with an amendment?')) return;
    setComposer({ noteType: note.noteType, content: note.content, amends: note });
  };

  const markInError = (note: NoteRecord) => {
    if (!window.confirm(`Mark this ${NOTE_TYPE_LABEL[note.noteType].toLowerCase()} as entered in error? It stays in the record, struck through.`)) return;
    void run(() => endpoints.noteInError(record.encounterId, note.id), 'Note marked entered in error.');
  };

  return (
    <section aria-label="Clinical notes" className="space-y-3 px-3 py-2">
      {canWrite && me && !me.linked && (
        <p role="note" className="rounded border border-amber-200 bg-amber-50 px-2 py-1 text-[12px] text-amber-900">
          Your login is not linked to a staff record, so you can read notes but not write them. Ask the administrator to link it.
        </p>
      )}

      {writable && (
        <div aria-label={composer.amends ? 'Amend note' : 'Write a note'} role="group" className="rounded border border-slate-200 bg-slate-50 p-2">
          <div className="mb-1 flex items-center gap-2">
            <h3 className="section-title">{composer.amends ? 'Amend note' : 'New note'}</h3>
            {composer.amends ? (
              <span className="text-[12px] text-slate-600">
                Correcting the {NOTE_TYPE_LABEL[composer.amends.noteType].toLowerCase()} signed {when(composer.amends.signedAt)}.
                The original stays in the record.
              </span>
            ) : (
              <select aria-label="Note type" className="field h-6 w-44" value={noteType} disabled={busy}
                onChange={(e) => setComposer({ ...composer, noteType: e.target.value as NoteType })}>
                {types.map((t) => <option key={t} value={t}>{NOTE_TYPE_LABEL[t]}</option>)}
              </select>
            )}
          </div>
          <textarea aria-label="Note text" rows={5} maxLength={100_000} disabled={busy}
            className="field h-auto min-h-[6rem] py-1 leading-snug" value={composer.content}
            onChange={(e) => setComposer({ ...composer, content: e.target.value })} />
          <div className="mt-1 flex items-center gap-2">
            <span className="text-[11px] text-slate-500">A signed note cannot be edited; it can only be amended.</span>
            <div className="ml-auto flex gap-2">
              {composer.amends ? (
                <>
                  <button type="button" className="btn-ghost" disabled={busy}
                    onClick={() => setComposer({ noteType: defaultType, content: '' })}>Cancel</button>
                  <button type="button" className="btn-primary" disabled={busy || !composer.content.trim() || composer.content.trim() === composer.amends.content}
                    onClick={() => void submit(true)}>Sign amendment</button>
                </>
              ) : (
                <>
                  <button type="button" className="btn-secondary" disabled={busy || !composer.content.trim()} onClick={() => void submit(false)}>
                    Save draft
                  </button>
                  <button type="button" className="btn-primary" disabled={busy || !composer.content.trim()} onClick={() => void submit(true)}>
                    Sign note
                  </button>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {views.length === 0 ? (
        <p className="text-slate-400">No notes for this visit yet.</p>
      ) : (
        <ol aria-label="Notes" className="space-y-2">
          {views.map((view) => (
            <NoteItem key={view.note.id} view={view} busy={busy}
              editing={editing?.id === view.note.id ? editing.content : null}
              onStartEdit={() => setEditing({ id: view.note.id, content: view.note.content })}
              onEditChange={(content) => setEditing({ id: view.note.id, content })}
              onCancelEdit={() => setEditing(null)}
              onSave={(andSign) => void saveDraft(view.note, andSign)}
              onSign={() => void run(() => endpoints.signNote(record.encounterId, view.note.id), 'Note signed.')}
              onAmend={() => amend(view.note)}
              onInError={() => markInError(view.note)} />
          ))}
        </ol>
      )}
    </section>
  );
}

interface ItemProps {
  view: NoteView;
  busy: boolean;
  editing: string | null;
  onStartEdit(): void;
  onEditChange(content: string): void;
  onCancelEdit(): void;
  onSave(andSign: boolean): void;
  onSign(): void;
  onAmend(): void;
  onInError(): void;
}

function NoteItem({ view, busy, editing, onStartEdit, onEditChange, onCancelEdit, onSave, onSign, onAmend, onInError }: ItemProps) {
  const { note, state } = view;
  const badge = STATE_BADGE[state];
  const muted = state === 'AMENDED' || state === 'IN_ERROR';
  return (
    <li aria-label={`${NOTE_TYPE_LABEL[note.noteType]} by ${note.author}`} className="rounded border border-slate-200 bg-white">
      <header className="flex flex-wrap items-center gap-2 border-b border-slate-100 px-2 py-1">
        <span className="font-medium">{NOTE_TYPE_LABEL[note.noteType]}</span>
        <Badge tone={badge.tone}>{badge.text}</Badge>
        <span className="text-[11px] text-slate-500">
          {note.author} · {state === 'DRAFT' ? `started ${when(note.createdAt)}` : `signed ${when(note.signedAt)}`}
        </span>
        {view.amends && <span className="text-[11px] text-slate-500">· amends the note signed {when(view.amends.signedAt)}</span>}
        {view.amendedBy && <span className="text-[11px] text-slate-500">· replaced by the amendment of {when(view.amendedBy.signedAt)}</span>}
        <div className="ml-auto flex gap-1">
          {view.canEdit && editing === null && <button type="button" className="btn-ghost h-6" disabled={busy} onClick={onStartEdit}>Edit</button>}
          {view.canSign && editing === null && <button type="button" className="btn-secondary h-6" disabled={busy} onClick={onSign}>Sign</button>}
          {view.canAmend && <button type="button" className="btn-ghost h-6" disabled={busy} onClick={onAmend}>Amend</button>}
          {view.canMarkInError && editing === null && (
            <button type="button" className="btn-ghost h-6 text-red-700 hover:bg-red-50" disabled={busy} onClick={onInError}>
              Entered in error
            </button>
          )}
        </div>
      </header>
      {editing !== null ? (
        <div className="p-2">
          <textarea aria-label="Edit draft" rows={5} maxLength={100_000} disabled={busy}
            className="field h-auto min-h-[6rem] py-1 leading-snug" value={editing} onChange={(e) => onEditChange(e.target.value)} />
          <div className="mt-1 flex justify-end gap-2">
            <button type="button" className="btn-ghost" disabled={busy} onClick={onCancelEdit}>Cancel</button>
            <button type="button" className="btn-secondary" disabled={busy || !editing.trim()} onClick={() => onSave(false)}>Save draft</button>
            <button type="button" className="btn-primary" disabled={busy || !editing.trim()} onClick={() => onSave(true)}>Save &amp; sign</button>
          </div>
        </div>
      ) : (
        <p className={`whitespace-pre-wrap px-2 py-1.5 leading-snug ${muted ? 'text-slate-400' : ''} ${state === 'IN_ERROR' ? 'line-through' : ''}`}>
          {note.content}
        </p>
      )}
    </li>
  );
}
