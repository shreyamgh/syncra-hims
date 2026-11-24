import type { Language } from '../dictation/protocol';
import type { DictationStatus } from '../dictation/useDictation';
import { MicIcon, StopIcon } from './ui';

interface Props {
  status: DictationStatus;
  transcript: string;
  level: number;
  error: string | null;
  warnings: string[];
  language: Language;
  onLanguageChange(language: Language): void;
  onToggle(): void;
  onTranscriptChange(text: string): void;
  onReextract(): void;
  canReextract: boolean;
  disabled: boolean;
}

const STATUS_TEXT: Record<DictationStatus, string> = {
  idle: 'Press the mic (Alt+M) and dictate complaints, vitals and the prescription.',
  connecting: 'Connecting to Shrutlekhan…',
  listening: 'Listening… pause briefly between sentences.',
  finishing: 'Finishing the last sentence…',
  error: '',
};

export function DictationBar(props: Props) {
  const { status, transcript, level, error, warnings, language, disabled } = props;
  const live = status === 'connecting' || status === 'listening' || status === 'finishing';
  return (
    <section aria-label="Voice dictation" className="flex gap-3 border-b border-slate-200 bg-slate-50 px-3 py-2">
      <div className="flex flex-col items-center gap-1">
        <button
          type="button"
          onClick={props.onToggle}
          disabled={disabled || status === 'connecting' || status === 'finishing'}
          aria-pressed={status === 'listening'}
          aria-label={status === 'listening' ? 'Stop dictation' : 'Start dictation'}
          title="Start / stop dictation (Alt+M)"
          className={`flex h-11 w-11 items-center justify-center rounded-full text-white shadow-sm transition-colors disabled:opacity-40 ${
            status === 'listening' ? 'bg-red-600 hover:bg-red-700' : 'bg-violet-600 hover:bg-violet-700'
          }`}
        >
          {status === 'listening' ? <StopIcon className="h-4 w-4" /> : <MicIcon className="h-5 w-5" />}
        </button>
        <div className="h-1 w-11 overflow-hidden rounded bg-slate-200" aria-hidden="true">
          <div className="h-full bg-emerald-500 transition-[width] duration-75" style={{ width: `${Math.min(100, level * 400)}%` }} />
        </div>
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="section-title">Shrutlekhan dictation</span>
          <select
            aria-label="Dictation language"
            className="field h-6 w-auto py-0 text-[11px]"
            value={language}
            onChange={(e) => props.onLanguageChange(e.target.value as Language)}
            disabled={live}
          >
            <option value="en-IN">English (India)</option>
            <option value="hi-IN">Hindi</option>
          </select>
          <span className={`text-[12px] ${error ? 'text-red-600' : 'text-slate-500'}`} role="status">
            {error ?? STATUS_TEXT[status]}
          </span>
          <button type="button" className="btn-ghost ml-auto h-6" onClick={props.onReextract} disabled={!props.canReextract || live} title="Fill the form again from the edited transcript">
            Re-extract
          </button>
        </div>
        <textarea
          aria-label="Transcript"
          aria-live="polite"
          readOnly={live}
          value={transcript}
          onChange={(e) => props.onTranscriptChange(e.target.value)}
          placeholder="The transcript appears here as you speak. You can correct it and press Re-extract."
          rows={2}
          className="field mt-1 h-auto resize-y py-1 leading-snug"
        />
        {warnings.length > 0 && (
          <p className="mt-0.5 text-[11px] text-amber-700">{warnings.join(' · ')}</p>
        )}
      </div>
    </section>
  );
}
