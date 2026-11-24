"""WebSocket endpoint for streaming clinical dictation (Shrutlekhan).

See :mod:`app.dictation.protocol` for the message contract. The endpoint is only
reachable through the API gateway, which authenticates the handshake and strips
the ``access_token`` query parameter before proxying.
"""

import asyncio
import logging
import time
import uuid

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ValidationError
from starlette.websockets import WebSocketState

from app.clinical.extraction import extract
from app.core.config import Settings, get_settings
from app.dictation.protocol import (
    DictationProtocolError,
    ErrorMessage,
    FinalMessage,
    PartialMessage,
    ReadyMessage,
    StartMessage,
    StopMessage,
    client_control_adapter,
)
from app.dictation.transcriber import StreamingTranscriber, create_transcriber
from app.stt.engine import SpeechEngine

logger = logging.getLogger(__name__)
router = APIRouter()

BYTES_PER_SAMPLE = 2  # PCM16 mono
INTERNAL_ERROR = 1011


@router.websocket("/ws/ai/dictation")
async def dictation_endpoint(websocket: WebSocket) -> None:
    await websocket.accept()
    await DictationSession(websocket, get_settings(), websocket.app.state.speech_engine).run()


class DictationSession:
    """State machine for one dictation stream: handshake → audio → stop."""

    def __init__(self, websocket: WebSocket, settings: Settings, engine: SpeechEngine) -> None:
        self._ws = websocket
        self._settings = settings
        self._engine = engine
        self._session_id = uuid.uuid4().hex
        self._start: StartMessage | None = None
        self._transcriber: StreamingTranscriber | None = None
        self._audio_bytes = 0

    async def run(self) -> None:
        started_at = time.monotonic()
        outcome = "completed"
        try:
            self._start = await self._handshake()
            self._transcriber = create_transcriber(self._settings, self._start, self._engine)
            await self._send(ReadyMessage(session_id=self._session_id, engine=self._transcriber.engine_name))
            await self._stream(deadline=started_at + self._settings.dictation_max_session_s)
        except DictationProtocolError as exc:
            outcome = exc.code
            await self._fail(exc)
        except WebSocketDisconnect:
            outcome = "client_disconnected"
        except Exception:
            outcome = "internal_error"
            logger.exception("dictation session failed", extra={"session_id": self._session_id})
            await self._fail(
                DictationProtocolError("internal_error", "Dictation failed; please retry.", INTERNAL_ERROR)
            )
        finally:
            if self._transcriber is not None:
                await self._transcriber.close()
            # Operational metadata only — never transcript text or audio.
            logger.info(
                "dictation session ended",
                extra={
                    "session_id": self._session_id,
                    "encounter_ref": self._start.encounter_id if self._start else None,
                    "event": outcome,
                    "audio_bytes": self._audio_bytes,
                    "duration_s": round(time.monotonic() - started_at, 2),
                },
            )

    async def _handshake(self) -> StartMessage:
        try:
            message = await asyncio.wait_for(self._ws.receive(), timeout=self._settings.dictation_handshake_timeout_s)
        except TimeoutError:
            raise DictationProtocolError("handshake_timeout", "Expected a 'start' message after connecting.") from None
        self._raise_if_disconnected(message)

        text = message.get("text")
        control = self._parse_control(text) if text is not None else None
        if not isinstance(control, StartMessage):
            raise DictationProtocolError("start_required", "The first message must be a JSON 'start' message.")
        return control

    async def _stream(self, deadline: float) -> None:
        while True:
            remaining = deadline - time.monotonic()
            try:
                if remaining <= 0:
                    raise TimeoutError
                message = await asyncio.wait_for(self._ws.receive(), timeout=remaining)
            except TimeoutError:
                raise DictationProtocolError(
                    "session_time_limit",
                    f"Dictation sessions are limited to {self._settings.dictation_max_session_s}s.",
                ) from None
            self._raise_if_disconnected(message)

            chunk = message.get("bytes")
            if chunk is not None:
                await self._on_audio(chunk)
                continue

            control = self._parse_control(message.get("text") or "")
            if isinstance(control, StopMessage):
                await self._finish()
                return
            raise DictationProtocolError("already_started", "This session has already started.")

    async def _on_audio(self, chunk: bytes) -> None:
        if len(chunk) > self._settings.dictation_max_frame_bytes:
            raise DictationProtocolError(
                "frame_too_large",
                f"Audio frames must not exceed {self._settings.dictation_max_frame_bytes} bytes.",
                DictationProtocolError.MESSAGE_TOO_BIG,
            )
        if len(chunk) % BYTES_PER_SAMPLE:
            raise DictationProtocolError("invalid_frame", "PCM16 frames must contain whole 16-bit samples.")
        self._audio_bytes += len(chunk)
        if self._audio_bytes > self._settings.dictation_max_audio_bytes:
            raise DictationProtocolError(
                "audio_limit_exceeded",
                "Maximum audio size for one session exceeded.",
                DictationProtocolError.MESSAGE_TOO_BIG,
            )
        assert self._transcriber is not None
        transcript = await self._transcriber.accept_audio(chunk)
        if transcript:
            await self._send(PartialMessage(text=transcript, extraction=extract(transcript)))

    async def _finish(self) -> None:
        assert self._transcriber is not None and self._start is not None
        text = await self._transcriber.finalize()
        seconds = self._audio_bytes / (BYTES_PER_SAMPLE * self._start.sample_rate)
        await self._send(
            FinalMessage(
                session_id=self._session_id,
                text=text,
                audio_seconds=round(seconds, 3),
                engine=self._transcriber.engine_name,
                language=self._start.language,
                extraction=extract(text),
            )
        )
        await self._ws.close(code=1000)

    async def _fail(self, exc: DictationProtocolError) -> None:
        if self._ws.client_state != WebSocketState.CONNECTED or self._ws.application_state != WebSocketState.CONNECTED:
            return
        try:
            await self._send(ErrorMessage(code=exc.code, message=exc.message))
            await self._ws.close(code=exc.close_code)
        except (WebSocketDisconnect, RuntimeError):
            pass  # client already gone

    async def _send(self, message: BaseModel) -> None:
        await self._ws.send_text(message.model_dump_json())

    @staticmethod
    def _parse_control(text: str) -> StartMessage | StopMessage:
        try:
            return client_control_adapter.validate_json(text)
        except ValidationError:
            raise DictationProtocolError("invalid_message", "Malformed or unknown control message.") from None

    @staticmethod
    def _raise_if_disconnected(message: dict) -> None:
        if message["type"] == "websocket.disconnect":
            raise WebSocketDisconnect(code=message.get("code", 1000))
