"""Wire protocol for ``/ws/ai/dictation`` (Shrutlekhan streaming dictation).

Client → server
  1. text  ``{"type":"start","encounter_id":"...","language":"en-IN",
             "sample_rate":16000,"encoding":"pcm_s16le","channels":1}``
  2. binary frames of raw little-endian PCM16 mono audio (≤ 64 KiB each)
  3. text  ``{"type":"stop"}``

Server → client
  ``{"type":"ready","session_id":"...","engine":"..."}``   after a valid start
  ``{"type":"partial","text":"...","extraction":{...}}``  after each utterance
  ``{"type":"final","session_id":"...","text":"...","audio_seconds":12.5,
     "engine":"...","language":"en-IN","extraction":{...}}``
  ``{"type":"error","code":"...","message":"..."}``        then the socket closes

``text`` is the whole transcript so far (it only ever grows) and ``extraction``
holds the vitals, complaints and prescription lines found in it (see
:mod:`app.clinical.extraction`), so the client can auto-fill forms live.

Close codes: 1000 normal, 1008 protocol/policy violation, 1009 limit exceeded,
1011 internal error, 1013 try again later (speech model still loading).
"""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter

from app.clinical.extraction import ClinicalExtraction


class _Message(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


# ---- client → server --------------------------------------------------------
class StartMessage(_Message):
    type: Literal["start"]
    encounter_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    language: Literal["en-IN", "hi-IN"] = "en-IN"
    sample_rate: Literal[16000] = 16000
    encoding: Literal["pcm_s16le"] = "pcm_s16le"
    channels: Literal[1] = 1


class StopMessage(_Message):
    type: Literal["stop"]


ClientControlMessage = Annotated[StartMessage | StopMessage, Field(discriminator="type")]
client_control_adapter: TypeAdapter[StartMessage | StopMessage] = TypeAdapter(ClientControlMessage)


# ---- server → client --------------------------------------------------------
class ReadyMessage(_Message):
    type: Literal["ready"] = "ready"
    session_id: str
    engine: str


class PartialMessage(_Message):
    type: Literal["partial"] = "partial"
    text: str
    extraction: ClinicalExtraction


class FinalMessage(_Message):
    type: Literal["final"] = "final"
    session_id: str
    text: str
    audio_seconds: float
    engine: str
    language: str
    extraction: ClinicalExtraction


class ErrorMessage(_Message):
    type: Literal["error"] = "error"
    code: str
    message: str


# ---- errors -----------------------------------------------------------------
class DictationProtocolError(Exception):
    """A client violated the protocol or a session limit."""

    POLICY_VIOLATION = 1008
    MESSAGE_TOO_BIG = 1009

    def __init__(self, code: str, message: str, close_code: int = POLICY_VIOLATION) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.close_code = close_code
