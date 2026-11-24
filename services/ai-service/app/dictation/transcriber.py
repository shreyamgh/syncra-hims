"""Streaming transcription for one dictation session.

The WebSocket layer depends only on :class:`StreamingTranscriber`.
:class:`SegmentingTranscriber` turns a continuous PCM16 stream into utterances
by detecting pauses, transcribes each utterance with the shared
:class:`~app.stt.engine.SpeechEngine`, and exposes the growing transcript —
committed text never changes, so form fields auto-filled from it stay stable.
"""

import math
from typing import Protocol

import numpy as np

from app.core.config import Settings
from app.dictation.protocol import DictationProtocolError, StartMessage
from app.stt.engine import CLINICAL_PROMPT, EngineStatus, SpeechEngine

TRY_AGAIN_LATER = 1013
_CONTEXT_CHARS = 200
_FRAME_MS = 20  # analysis frame for pause detection


class StreamingTranscriber(Protocol):
    """One instance per dictation session; not shared across sessions."""

    engine_name: str

    async def accept_audio(self, pcm16: bytes) -> str | None:
        """Consume PCM16 audio; return the updated transcript when it changed."""
        ...

    async def finalize(self) -> str:
        """Flush buffered audio and return the final transcript."""
        ...

    async def close(self) -> None:
        """Release resources. Must be safe to call more than once."""
        ...


class NullTranscriber:
    """Accepts audio and returns an empty transcript (STT disabled)."""

    engine_name = "none"

    async def accept_audio(self, pcm16: bytes) -> str | None:
        return None

    async def finalize(self) -> str:
        return ""

    async def close(self) -> None:
        return None


class SegmentingTranscriber:
    """Pause-segmented transcription on top of a shared speech engine.

    Audio is analysed in fixed 20 ms frames, so segmentation is independent of how
    the client chunks its stream, and each utterance is cut exactly at its pause.
    """

    def __init__(self, engine: SpeechEngine, language: str, sample_rate: int, settings: Settings) -> None:
        self.engine_name = engine.name
        self._engine = engine
        self._language = language
        self._sample_rate = sample_rate
        self._pause_samples = int(settings.segment_pause_ms * sample_rate / 1000)
        self._min_samples = int(settings.segment_min_s * sample_rate)
        self._max_samples = int(settings.segment_max_s * sample_rate)
        self._threshold = settings.silence_rms_threshold
        self._frame = max(1, sample_rate * _FRAME_MS // 1000)
        self._pending: list[np.ndarray] = []
        self._pending_samples = 0
        self._silent_run = 0
        self._heard_speech = False
        self._committed: list[str] = []

    @property
    def transcript(self) -> str:
        return " ".join(self._committed)

    async def accept_audio(self, pcm16: bytes) -> str | None:
        samples = _to_float(pcm16)
        changed = False
        for start in range(0, samples.size, self._frame):
            frame = samples[start : start + self._frame]
            self._pending.append(frame)
            self._pending_samples += frame.size
            if _rms(frame) < self._threshold:
                self._silent_run += frame.size
            else:
                self._silent_run = 0
                self._heard_speech = True
            paused = (
                self._heard_speech
                and self._silent_run >= self._pause_samples
                and self._pending_samples >= self._min_samples
            )
            if paused or self._pending_samples >= self._max_samples:
                changed = await self._commit() or changed
        return self.transcript if changed else None

    async def finalize(self) -> str:
        if self._pending_samples:
            await self._commit()
        return self.transcript

    async def close(self) -> None:
        self._pending = []
        self._pending_samples = 0

    async def _commit(self) -> bool:
        audio = np.concatenate(self._pending) if self._pending else np.zeros(0, dtype=np.float32)
        heard = self._heard_speech
        self._pending = []
        self._pending_samples = 0
        self._silent_run = 0
        self._heard_speech = False
        if not heard or audio.size < self._sample_rate // 4:
            return False  # pure silence/noise: never sent to the model (avoids hallucinated text)
        context = self.transcript[-_CONTEXT_CHARS:]
        prompt = f"{CLINICAL_PROMPT} {context}".strip()
        text = await self._engine.transcribe(audio, self._language, prompt)
        if text:
            self._committed.append(text)
            return True
        return False


def create_transcriber(settings: Settings, start: StartMessage, engine: SpeechEngine) -> StreamingTranscriber:
    """Builds the transcriber for a new session, or refuses while the model is not ready."""
    if engine.status is EngineStatus.DISABLED:
        return NullTranscriber()
    if engine.status is not EngineStatus.READY:
        raise DictationProtocolError(
            "engine_unavailable",
            "Speech recognition is starting up. Please try again in a minute."
            if engine.status is EngineStatus.LOADING
            else "Speech recognition is unavailable.",
            TRY_AGAIN_LATER,
        )
    return SegmentingTranscriber(engine, start.language, start.sample_rate, settings)


def _to_float(pcm16: bytes) -> np.ndarray:
    return np.frombuffer(pcm16, dtype="<i2").astype(np.float32) / 32768.0


def _rms(samples: np.ndarray) -> float:
    return math.sqrt(float(np.mean(np.square(samples)))) if samples.size else 0.0
