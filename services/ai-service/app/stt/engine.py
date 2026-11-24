"""Speech-to-text engine: faster-whisper on CPU, loaded once and shared.

The model is downloaded on first start into ``settings.model_dir`` (a persistent
volume) and loaded in a background thread, so the service answers health
checks immediately; dictation sessions are refused with ``engine_unavailable``
until the model is ready. All inference runs on-premises.
"""

import asyncio
import logging
import os
import re
from enum import StrEnum
from typing import Protocol

import numpy as np

from app.core.config import Settings

logger = logging.getLogger(__name__)

# Biases Whisper towards clinical vocabulary and Indian prescription notation
# (digits, "130/80", "1-0-1", dosage-form abbreviations).
CLINICAL_PROMPT = (
    "Clinical dictation by a doctor in India. BP 130/80, pulse 88, SpO2 97%, temperature 101 F, "
    "respiratory rate 18, weight 70 kg, RBS 140 mg/dl. Complaints of fever for 3 days and cough for "
    "1 week. Tab Paracetamol 650 mg 1-0-1 after food for 5 days. Cap Amoxicillin 500 mg TDS for "
    "7 days. Syrup, OD, BD, TDS, HS, SOS."
)

LANGUAGE_CODES = {"en-IN": "en", "hi-IN": "hi"}

# Phrases Whisper is known to hallucinate on noise; dropped when a segment is only that.
_HALLUCINATIONS = re.compile(
    r"^(thank you( for watching)?|thanks for watching|subtitles by.*|please subscribe.*|you)[.!]?$",
    re.IGNORECASE,
)


class EngineStatus(StrEnum):
    DISABLED = "DISABLED"
    LOADING = "LOADING"
    READY = "READY"
    FAILED = "FAILED"


class SpeechEngine(Protocol):
    name: str

    @property
    def status(self) -> EngineStatus: ...

    async def transcribe(self, audio: np.ndarray, language: str, prompt: str) -> str: ...


class WhisperEngine:
    """Shared faster-whisper model with bounded concurrency."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._status = EngineStatus.LOADING
        self._model = None
        self._semaphore = asyncio.Semaphore(settings.stt_max_concurrency)
        self.name = f"whisper-{os.path.basename(settings.whisper_model.rstrip('/'))}"

    @property
    def status(self) -> EngineStatus:
        return self._status

    async def start_loading(self) -> None:
        """Loads (and on first run downloads) the model without blocking startup."""
        await asyncio.to_thread(self._load)

    def _load(self) -> None:
        try:
            from faster_whisper import WhisperModel  # heavy import, deferred

            logger.info("loading speech model %s", self._settings.whisper_model)
            self._model = WhisperModel(
                self._settings.whisper_model,
                device="cpu",
                compute_type=self._settings.whisper_compute_type,
                cpu_threads=self._settings.whisper_cpu_threads,
                num_workers=self._settings.stt_max_concurrency,
                download_root=self._settings.model_dir,
            )
            self._status = EngineStatus.READY
            logger.info("speech model ready (%s)", self.name)
        except Exception:
            self._status = EngineStatus.FAILED
            logger.exception("speech model failed to load")

    async def transcribe(self, audio: np.ndarray, language: str, prompt: str) -> str:
        if self._status is not EngineStatus.READY:
            raise RuntimeError(f"speech engine is {self._status}")
        async with self._semaphore:
            return await asyncio.to_thread(self._transcribe_sync, audio, language, prompt)

    def _transcribe_sync(self, audio: np.ndarray, language: str, prompt: str) -> str:
        segments, _info = self._model.transcribe(
            audio,
            language=LANGUAGE_CODES.get(language, "en"),
            beam_size=self._settings.whisper_beam_size,
            temperature=0.0,
            initial_prompt=prompt,
            condition_on_previous_text=False,
            vad_filter=True,
            without_timestamps=True,
        )
        # `segments` is lazy: iterating performs the decoding (inside this worker thread).
        parts = [s.text.strip() for s in segments if s.text and s.text.strip()]
        text = " ".join(parts).strip()
        return "" if _HALLUCINATIONS.match(text) else text


class DisabledEngine:
    name = "none"

    @property
    def status(self) -> EngineStatus:
        return EngineStatus.DISABLED

    async def transcribe(self, audio: np.ndarray, language: str, prompt: str) -> str:
        return ""


def build_engine(settings: Settings) -> SpeechEngine:
    return WhisperEngine(settings) if settings.stt_engine == "whisper" else DisabledEngine()
