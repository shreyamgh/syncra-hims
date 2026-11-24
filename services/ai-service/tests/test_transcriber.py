import asyncio

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.dictation.protocol import DictationProtocolError, StartMessage
from app.dictation.transcriber import NullTranscriber, SegmentingTranscriber, create_transcriber
from app.stt.engine import CLINICAL_PROMPT, EngineStatus
from tests.conftest import FakeEngine

RATE = 16_000
CHUNK = RATE // 10  # 100 ms


def _tone(seconds: float, amplitude: float = 0.2) -> bytes:
    t = np.arange(int(seconds * RATE)) / RATE
    return (np.sin(2 * np.pi * 220 * t) * amplitude * 32767).astype("<i2").tobytes()


def _silence(seconds: float) -> bytes:
    return np.zeros(int(seconds * RATE), dtype="<i2").tobytes()


def _chunks(pcm: bytes):
    for i in range(0, len(pcm), CHUNK * 2):
        yield pcm[i : i + CHUNK * 2]


def _feed(transcriber, pcm: bytes) -> list[str]:
    async def run():
        return [r for c in _chunks(pcm) if (r := await transcriber.accept_audio(c))]

    return asyncio.run(run())


START = StartMessage(type="start", encounter_id="enc-1", language="en-IN")


def test_utterance_is_transcribed_when_the_speaker_pauses():
    engine = FakeEngine(replies=["BP 130/80", "pulse 88"])
    t = SegmentingTranscriber(engine, "en-IN", RATE, Settings())

    updates = _feed(t, _tone(1.0) + _silence(0.8))
    assert updates == ["BP 130/80"]
    updates = _feed(t, _tone(1.2) + _silence(0.8))
    assert updates == ["BP 130/80 pulse 88"]

    # Second segment is prompted with the clinical vocabulary plus earlier context.
    assert engine.calls[1][2].startswith(CLINICAL_PROMPT) and engine.calls[1][2].endswith("BP 130/80")
    assert engine.calls[0][1] == "en-IN"


def test_segmentation_does_not_depend_on_client_chunk_size():
    # The same audio delivered as one large chunk is cut at the same pauses.
    engine = FakeEngine(replies=["BP 130/80", "pulse 88"])
    t = SegmentingTranscriber(engine, "en-IN", RATE, Settings())
    audio = _tone(1.0) + _silence(0.8) + _tone(1.2) + _silence(0.8)

    result = asyncio.run(t.accept_audio(audio))

    assert result == "BP 130/80 pulse 88"
    assert len(engine.calls) == 2
    # Each utterance ends at its pause (speech + 700 ms of silence), not at the chunk boundary.
    assert engine.calls[0][0] == int(1.7 * RATE)


def test_silence_is_never_sent_to_the_model():
    engine = FakeEngine(replies=["should not appear"])
    t = SegmentingTranscriber(engine, "en-IN", RATE, Settings())
    assert _feed(t, _silence(3.0)) == []
    assert asyncio.run(t.finalize()) == ""
    assert engine.calls == []


def test_long_speech_is_cut_at_the_maximum_segment_length():
    engine = FakeEngine(replies=["first part", "second part"])
    t = SegmentingTranscriber(engine, "hi-IN", RATE, Settings(segment_max_s=2.0))
    assert _feed(t, _tone(3.0)) == ["first part"]
    assert engine.calls[0][0] == 2 * RATE
    assert asyncio.run(t.finalize()) == "first part second part"


def test_create_transcriber_depends_on_engine_state():
    assert isinstance(create_transcriber(Settings(), START, FakeEngine(EngineStatus.DISABLED)), NullTranscriber)
    assert isinstance(create_transcriber(Settings(), START, FakeEngine()), SegmentingTranscriber)
    with pytest.raises(DictationProtocolError) as loading:
        create_transcriber(Settings(), START, FakeEngine(EngineStatus.LOADING))
    assert (loading.value.code, loading.value.close_code) == ("engine_unavailable", 1013)


def test_websocket_streams_partials_with_extraction(client: TestClient):
    client.app.state.speech_engine = FakeEngine(
        replies=[
            "Complains of fever for 3 days. BP 130/80, pulse 96.",
            "Tab Paracetamol 650 mg 1-0-1 after food for 5 days.",
        ]
    )
    with client.websocket_connect("/ws/ai/dictation") as ws:
        ws.send_json({"type": "start", "encounter_id": "enc-42"})
        assert ws.receive_json()["engine"] == "fake-whisper"
        for chunk in _chunks(_tone(1.0) + _silence(0.8)):
            ws.send_bytes(chunk)
        partial = ws.receive_json()
        assert partial["type"] == "partial"
        assert partial["extraction"]["vitals"]["bp_systolic"] == 130
        assert partial["extraction"]["complaints"][0]["description"] == "Fever"

        for chunk in _chunks(_tone(1.0)):
            ws.send_bytes(chunk)
        ws.send_json({"type": "stop"})
        final = ws.receive_json()
        assert final["type"] == "final"
        assert final["text"].endswith("for 5 days.")
        assert final["extraction"]["prescriptions"][0]["drug_text"] == "Tab Paracetamol 650 mg"
        assert final["extraction"]["vitals"]["pulse_bpm"] == 96


def test_websocket_refuses_sessions_while_the_model_loads(client: TestClient):
    from starlette.websockets import WebSocketDisconnect

    client.app.state.speech_engine = FakeEngine(EngineStatus.LOADING)
    with client.websocket_connect("/ws/ai/dictation") as ws:
        ws.send_json({"type": "start", "encounter_id": "enc-1"})
        assert ws.receive_json()["code"] == "engine_unavailable"
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_text()
        assert closed.value.code == 1013


def test_extract_endpoint(client: TestClient):
    response = client.post("/api/v1/ai/extract", json={"text": "SpO2 95%. Syrup Ascoril 5 ml TDS"})
    assert response.status_code == 200
    body = response.json()
    assert body["vitals"]["spo2_percent"] == 95
    assert body["prescriptions"][0]["frequency"] == "TDS"
