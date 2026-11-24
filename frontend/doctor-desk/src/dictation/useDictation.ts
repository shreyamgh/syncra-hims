import { useCallback, useEffect, useRef, useState } from 'react';
import type { DictationSessionRecord } from '../api/types';
import { type MicrophoneCapture, startMicrophone } from './microphone';
import { type Extraction, type Language, SAMPLE_RATE, type ServerMessage } from './protocol';

export type DictationStatus = 'idle' | 'connecting' | 'listening' | 'finishing' | 'error';

export interface DictationOptions {
  encounterId: string | null;
  language: Language;
  token: string | null;
  /** Called with the cumulative extraction after every utterance and at the end. */
  onExtraction(extraction: Extraction, sessionId: string): void;
  /** Called once per session (completed or not) with its provenance for the EMR. */
  onSessionEnd(session: DictationSessionRecord, transcript: string): void;
  socketUrl?: string;
}

export function dictationSocketUrl(token: string, location: Pick<Location, 'protocol' | 'host'> = window.location) {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${location.host}/ws/ai/dictation?access_token=${encodeURIComponent(token)}`;
}

/**
 * Shrutlekhan dictation over the AI service WebSocket: opens the session, streams
 * microphone PCM once the server is ready, surfaces the live transcript and
 * forwards each extraction so the caller can auto-fill the EMR form.
 */
export function useDictation(options: DictationOptions) {
  const [status, setStatus] = useState<DictationStatus>('idle');
  const [transcript, setTranscript] = useState('');
  const [level, setLevel] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const latest = useRef(options);
  latest.current = options;
  const socketRef = useRef<WebSocket | null>(null);
  const captureRef = useRef<MicrophoneCapture | null>(null);
  const sessionRef = useRef<{ id: string; engine: string; startedAt: string } | null>(null);
  const endedRef = useRef(false);

  const stopCapture = useCallback(async () => {
    const capture = captureRef.current;
    captureRef.current = null;
    setLevel(0);
    if (capture) await capture.stop().catch(() => undefined);
  }, []);

  const endSession = useCallback(
    (outcome: string, details: { audioSeconds?: number; engine?: string; language?: string; text?: string } = {}) => {
      const session = sessionRef.current;
      if (!session || endedRef.current) return;
      endedRef.current = true;
      latest.current.onSessionEnd(
        {
          aiSessionId: session.id,
          language: details.language ?? latest.current.language,
          engine: details.engine ?? session.engine,
          audioSeconds: details.audioSeconds ?? 0,
          outcome,
          startedAt: session.startedAt,
          endedAt: new Date().toISOString(),
        },
        details.text ?? '',
      );
    },
    [],
  );

  const start = useCallback(() => {
    const { encounterId, token, language, socketUrl } = latest.current;
    if (!encounterId || !token || socketRef.current) return;
    setError(null);
    setTranscript('');
    setStatus('connecting');
    sessionRef.current = null;
    endedRef.current = false;

    const socket = new WebSocket(socketUrl ?? dictationSocketUrl(token));
    socket.binaryType = 'arraybuffer';
    socketRef.current = socket;

    socket.onopen = () => {
      socket.send(
        JSON.stringify({
          type: 'start',
          encounter_id: encounterId,
          language,
          sample_rate: SAMPLE_RATE,
          encoding: 'pcm_s16le',
          channels: 1,
        }),
      );
    };

    socket.onmessage = async (event: MessageEvent) => {
      if (typeof event.data !== 'string') return;
      const message = JSON.parse(event.data) as ServerMessage;
      switch (message.type) {
        case 'ready':
          sessionRef.current = { id: message.session_id, engine: message.engine, startedAt: new Date().toISOString() };
          try {
            captureRef.current = await startMicrophone((pcm, micLevel) => {
              if (socket.readyState === WebSocket.OPEN) socket.send(pcm.buffer);
              setLevel(micLevel);
            });
            setStatus('listening');
          } catch (failure) {
            setError(microphoneError(failure));
            setStatus('error');
            endSession('microphone_error');
            socket.close(1000);
          }
          break;
        case 'partial':
          setTranscript(message.text);
          latest.current.onExtraction(message.extraction, sessionRef.current?.id ?? '');
          break;
        case 'final':
          setTranscript(message.text);
          latest.current.onExtraction(message.extraction, message.session_id);
          endSession('completed', {
            audioSeconds: message.audio_seconds,
            engine: message.engine,
            language: message.language,
            text: message.text,
          });
          setStatus('idle');
          break;
        case 'error':
          setError(message.message);
          setStatus('error');
          await stopCapture();
          endSession(message.code);
          break;
      }
    };

    socket.onclose = (event: CloseEvent) => {
      socketRef.current = null;
      void stopCapture();
      if (sessionRef.current && !endedRef.current) endSession('disconnected');
      setStatus((current) => {
        if (current === 'idle' || current === 'error') return current;
        return event.code === 1000 ? 'idle' : 'error';
      });
      if (event.code !== 1000) {
        setError((current) => current ?? 'Could not reach the dictation service. Check the connection and try again.');
      }
    };
  }, [endSession, stopCapture]);

  const stop = useCallback(async () => {
    const socket = socketRef.current;
    await stopCapture();
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'stop' }));
      setStatus('finishing');
    } else {
      setStatus('idle');
    }
  }, [stopCapture]);

  useEffect(
    () => () => {
      void captureRef.current?.stop();
      socketRef.current?.close(1000);
    },
    [],
  );

  return { status, transcript, level, error, start, stop, setTranscript };
}

function microphoneError(failure: unknown): string {
  const name = failure instanceof DOMException ? failure.name : '';
  if (name === 'NotAllowedError') return 'Microphone permission was denied. Allow it in the browser and try again.';
  if (name === 'NotFoundError') return 'No microphone was found on this computer.';
  return failure instanceof Error ? failure.message : 'The microphone could not be started.';
}
